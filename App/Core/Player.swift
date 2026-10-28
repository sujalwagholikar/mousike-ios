import Foundation
import AVFoundation
import MediaPlayer
import Combine
import UIKit
import Network

@MainActor final class MusicPlayer: ObservableObject {
    let native = AVPlayer()
    @Published private(set) var track: Track?
    @Published private(set) var queue: [Track] = []
    @Published private(set) var phase: PlaybackPhase = .idle
    @Published private(set) var position: Double = 0
    @Published private(set) var duration: Double = 0
    @Published private(set) var sourceLabel = ""
    @Published private(set) var outputName = "iPhone"
    @Published private(set) var startupMilliseconds: Int?
    @Published var expanded = false
    private let library: LibraryStore
    private var resolveTask: Task<Void, Never>?
    private var radioTask: Task<Void, Never>?
    private var preloadTask: Task<Void, Never>?
    private var observation: NSKeyValueObservation?
    private var statusObservation: NSKeyValueObservation?
    private var timeObserver: Any?
    private var notifications: [NSObjectProtocol] = []
    private var generation = UUID()
    private var wantsPlay = false
    private var retryUsed = false
    private var startDate = Date()
    private var lastProgress = Date()
    private var savedProgress: Double = 0
    private var watchdog: Timer?
    private var prefetched: (id: String, quality: String, source: Source, expires: Date)?
    private let monitor = NWPathMonitor()
    private var inexpensiveNetwork = false
    private var interruptionResume = false
    private var lastSessionTrack: String?
    private var historyRecorded = false
    init(library: LibraryStore) {
        self.library = library
        native.automaticallyWaitsToMinimizeStalling = true
        observation = native.observe(\.timeControlStatus, options: [.new]) { [weak self] _, _ in
            Task { @MainActor in self?.updatePhase() }
        }
        timeObserver = native.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.5, preferredTimescale: 600), queue: .main) { [weak self] time in
            Task { @MainActor in
                guard let self = self else { return }
                let seconds = time.seconds
                if seconds.isFinite { self.position = max(0, seconds) }
                if self.position > self.savedProgress + 0.1 { self.lastProgress = Date(); self.savedProgress = self.position }
                if let value = self.native.currentItem?.duration.seconds, value.isFinite && value > 0 { self.duration = value }
                self.publishNowPlaying()
            }
        }
        let center = NotificationCenter.default
        notifications.append(center.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: nil, queue: .main) { [weak self] notification in
            Task { @MainActor in guard let self = self, notification.object as? AVPlayerItem === self.native.currentItem else { return }; self.next(completed: true) }
        })
        notifications.append(center.addObserver(forName: .AVPlayerItemFailedToPlayToEndTime, object: nil, queue: .main) { [weak self] notification in
            Task { @MainActor in guard let self = self, notification.object as? AVPlayerItem === self.native.currentItem else { return }; self.recover("Source stopped delivering audio.") }
        })
        notifications.append(center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] notification in
            Task { @MainActor in
                guard let self = self else { return }; self.updateOutput()
                if let raw = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                   AVAudioSession.RouteChangeReason(rawValue: raw) == .oldDeviceUnavailable { self.pause() }
            }
        })
        notifications.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] notification in
            Task { @MainActor in
                guard let self = self, let type = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt else { return }
                if type == AVAudioSession.InterruptionType.began.rawValue { self.interruptionResume = self.wantsPlay; self.pause() }
                else {
                    let raw = notification.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
                    if self.interruptionResume && AVAudioSession.InterruptionOptions(rawValue: raw).contains(.shouldResume) { self.resume() }
                }
            }
        })
        notifications.append(center.addObserver(forName: UIApplication.didReceiveMemoryWarningNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.preloadTask?.cancel(); self?.prefetched = nil; ArtworkLoader.clear(); Backend.shared.trim() }
        })
        monitor.pathUpdateHandler = { [weak self] path in
            Task { @MainActor in self?.inexpensiveNetwork = path.status == .satisfied && !path.isExpensive && !path.isConstrained }
        }
        monitor.start(queue: DispatchQueue(label: "Mousike.Network"))
        watchdog = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self = self, self.wantsPlay, self.track != nil, self.phase != .connecting else { return }
                if Date().timeIntervalSince(self.lastProgress) > 16 && self.native.timeControlStatus != .playing {
                    self.recover("Connection stalled. Check your network and retry.")
                }
            }
        }
        remoteControls(); updateOutput()
    }
    func play(_ selected: Track, context: [Track] = [], fresh: Bool = false, restoring: Bool = false) {
        if !restoring { recordCurrent(completed: false, skipped: true) }
        resolveTask?.cancel(); radioTask?.cancel(); preloadTask?.cancel()
        generation = UUID(); let ticket = generation
        if !restoring { queue = context.filter { $0.id != selected.id }; retryUsed = false; historyRecorded = false }
        track = selected; position = 0; duration = selected.duration; sourceLabel = selected.sourceLabel ?? ""
        savedProgress = 0; startDate = Date(); lastProgress = Date(); wantsPlay = true; phase = .connecting; lastSessionTrack = nil
        native.pause(); native.replaceCurrentItem(with: nil); statusObservation = nil
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, policy: .longFormAudio, options: [])
            try AVAudioSession.sharedInstance().setActive(true)
        } catch { terminal(error.localizedDescription); return }
        resolveTask = Task {
            do {
                let url: URL
                var resolvedLabel = selected.sourceLabel ?? ""
                if let local = library.localURL(selected) {
                    guard FileManager.default.fileExists(atPath: local.path) else { throw MusicError.message("Imported file is missing. Import it again from Files.") }
                    url = local
                } else {
                    let quality = UserDefaults.standard.string(forKey: "quality") ?? "High"
                    let source: Source
                    if !fresh, let p = prefetched, p.id == selected.id, p.quality == quality, p.expires.timeIntervalSinceNow > 30 { source = p.source }
                    else { source = try await Backend.shared.call("resolve", ["id": selected.id, "quality": quality, "fresh": fresh]) }
                    guard let sourceURL = URL(string: source.url) else { throw MusicError.message("Invalid audio URL") }
                    url = sourceURL; resolvedLabel = source.label
                }
                try Task.checkCancellation(); guard generation == ticket else { return }
                sourceLabel = resolvedLabel
                var options: [String: Any] = [AVURLAssetAllowsCellularAccessKey: UserDefaults.standard.object(forKey: "cellular") as? Bool ?? true]
                if #available(iOS 16.0, *) { options[AVURLAssetHTTPUserAgentKey] = "Mozilla/5.0 (Macintosh; Intel Mac OS X 25_6_0) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/25.6 Safari/605.1.15" }
                let asset = AVURLAsset(url: url, options: options)
                let item = AVPlayerItem(asset: asset); item.preferredForwardBufferDuration = 5
                native.replaceCurrentItem(with: item)
                statusObservation = item.observe(\.status, options: [.new, .initial]) { [weak self, weak item] _, _ in
                    Task { @MainActor in
                        guard let self = self, self.generation == ticket, let item = item else { return }
                        if item.status == .failed { self.recover(item.error?.localizedDescription ?? "This source could not be played.") }
                        else if item.status == .readyToPlay { self.updatePhase() }
                    }
                }
                phase = wantsPlay ? .buffering : .paused
                if wantsPlay { native.play() }
                publishNowPlaying(); refreshRadio(seed: selected, ticket: ticket)
            } catch is CancellationError { }
            catch { if generation == ticket { terminal(error.localizedDescription) } }
        }
    }
    private func updatePhase() {
        guard track != nil else { phase = .idle; return }
        if case .failed = phase { return }
        if !wantsPlay { phase = .paused }
        else if native.timeControlStatus == .playing {
            phase = .playing
            if lastSessionTrack != track?.id {
                startupMilliseconds = Int(Date().timeIntervalSince(startDate) * 1000); lastSessionTrack = track?.id
            }
            prepareNext()
        } else if native.currentItem != nil { phase = .buffering }
        publishNowPlaying()
    }
    private func recover(_ reason: String) {
        guard wantsPlay, let current = track else { return }
        let saved = position
        if current.localFile == nil && !retryUsed {
            retryUsed = true; play(current, fresh: true, restoring: true)
            let ticket = generation
            Task {
                for _ in 0..<60 {
                    try? await Task.sleep(nanoseconds: 250_000_000)
                    guard generation == ticket else { return }
                    if native.currentItem?.status == .readyToPlay { if saved > 1 { seek(saved) }; return }
                }
            }
        } else { terminal(reason) }
    }
    private func terminal(_ reason: String) {
        wantsPlay = false; native.pause(); phase = .failed(reason); publishNowPlaying()
        // Network failures intentionally do not create a dislike/skip event.
        historyRecorded = true
    }
    func toggle() { if wantsPlay { pause() } else { resume() } }
    func pause() { wantsPlay = false; native.pause(); phase = track == nil ? .idle : .paused; publishNowPlaying() }
    func resume() {
        guard let current = track else { return }
        if case .failed = phase { play(current, fresh: true, restoring: true); return }
        if native.currentItem?.status == .failed { play(current, fresh: true, restoring: true); return }
        wantsPlay = true; lastProgress = Date()
        do { try AVAudioSession.sharedInstance().setActive(true) } catch { terminal(error.localizedDescription); return }
        if native.currentItem == nil { return } // Resolution completion observes wantsPlay.
        native.play(); updatePhase()
    }
    func seek(_ seconds: Double) {
        guard seconds.isFinite else { return }; let value = duration > 0 ? min(max(0, seconds), duration) : max(0, seconds)
        native.seek(to: CMTime(seconds: value, preferredTimescale: 600)); position = value; savedProgress = value; lastProgress = Date()
        publishNowPlaying()
    }
    func next(completed: Bool = false) {
        recordCurrent(completed: completed, skipped: !completed)
        if let next = queue.first {
            let rest = Array(queue.dropFirst()); play(next, context: rest)
        } else { pause() }
    }
    func previous() { seek(0) }
    func enqueue(_ item: Track) { guard queue.count < 60, !queue.contains(where: { $0.id == item.id }) else { return }; queue.append(item); prepareNext() }
    func removeQueued(_ index: Int) { if queue.indices.contains(index) { queue.remove(at: index) } }
    func stopForDeletion(_ item: Track) {
        if track?.id == item.id { resolveTask?.cancel(); generation = UUID(); native.pause(); native.replaceCurrentItem(with: nil); track = nil; wantsPlay = false; phase = .idle; MPNowPlayingInfoCenter.default().nowPlayingInfo = nil }
    }
    private func recordCurrent(completed: Bool, skipped: Bool) {
        guard let current = track, !historyRecorded else { return }
        if !completed && (phase == .connecting || phase == .buffering || position < 1) { return }
        library.record(current, completion: completed ? 1 : min(1, position / max(1, duration)), skipped: skipped)
        historyRecorded = true
    }
    private func refreshRadio(seed: Track, ticket: UUID) {
        guard seed.localFile == nil else { return }
        radioTask = Task {
            guard let page: Catalog = try? await Backend.shared.call("radio", ["id": seed.id]), generation == ticket, !Task.isCancelled else { return }
            let ids = Set(queue.map(\.id)); let proposed = library.orderRadio(page.tracks, seed: seed).filter { !ids.contains($0.id) }
            queue.append(contentsOf: proposed.prefix(max(0, 30 - queue.count))); prepareNext()
        }
    }
    private func prepareNext() {
        guard UserDefaults.standard.object(forKey: "preload") as? Bool ?? true, inexpensiveNetwork,
              !ProcessInfo.processInfo.isLowPowerModeEnabled, let next = queue.first, next.localFile == nil else { return }
        let quality = UserDefaults.standard.string(forKey: "quality") ?? "High"
        if let p = prefetched, p.id == next.id, p.quality == quality, p.expires.timeIntervalSinceNow > 60 { return }
        guard preloadTask == nil else { return }
        preloadTask = Task {
            defer { preloadTask = nil }
            if let source: Source = try? await Backend.shared.call("resolve", ["id": next.id, "quality": quality]), !Task.isCancelled {
                prefetched = (next.id, quality, source, Date().addingTimeInterval(max(0, source.expiresIn - 30)))
            }
        }
    }
    func updateOutput() {
        outputName = AVAudioSession.sharedInstance().currentRoute.outputs.map(\.portName).joined(separator: ", ")
        if outputName.isEmpty { outputName = "iPhone" }
    }
    private func publishNowPlaying() {
        guard let current = track else { return }
        var info: [String: Any] = [MPMediaItemPropertyTitle: current.title, MPMediaItemPropertyArtist: current.artist,
                                   MPNowPlayingInfoPropertyElapsedPlaybackTime: position,
                                   MPMediaItemPropertyPlaybackDuration: duration,
                                   MPNowPlayingInfoPropertyPlaybackRate: phase == .playing ? 1.0 : 0.0,
                                   MPNowPlayingInfoPropertyIsLiveStream: false]
        if let image = ArtworkLoader.cached(current.cover) { info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: image.size) { _ in image } }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }
    private func remoteControls() {
        let remote = MPRemoteCommandCenter.shared()
        remote.playCommand.addTarget { [weak self] _ in Task { @MainActor in self?.resume() }; return .success }
        remote.pauseCommand.addTarget { [weak self] _ in Task { @MainActor in self?.pause() }; return .success }
        remote.togglePlayPauseCommand.addTarget { [weak self] _ in Task { @MainActor in self?.toggle() }; return .success }
        remote.nextTrackCommand.addTarget { [weak self] _ in Task { @MainActor in self?.next() }; return .success }
        remote.previousTrackCommand.addTarget { [weak self] _ in Task { @MainActor in self?.previous() }; return .success }
        remote.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            Task { @MainActor in self?.seek(event.positionTime) }; return .success
        }
    }
}
