import Foundation
import AVFoundation
import AudioToolbox
import Combine

@MainActor final class LibraryStore: ObservableObject {
    @Published private(set) var localTracks: [Track] = []
    @Published private(set) var liked: [Track] = []
    @Published private(set) var history: [ListenEvent] = []
    @Published var preferredArtists: [String] = [] { didSet { save() } }
    @Published var importError: String?
    private let file: URL
    let audioFolder: URL
    private var saveTask: Task<Void, Never>?
    private var restoring = true
    init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Mousike", isDirectory: true)
        audioFolder = base.appendingPathComponent("Lossless", isDirectory: true)
        file = base.appendingPathComponent("library.json")
        try? FileManager.default.createDirectory(at: audioFolder, withIntermediateDirectories: true)
        if let data = try? Data(contentsOf: file), let saved = try? JSONDecoder().decode(Snapshot.self, from: data) {
            localTracks = saved.localTracks; liked = saved.liked; history = saved.history; preferredArtists = saved.artists
        }
        restoring = false
    }
    struct Snapshot: Codable { var localTracks: [Track]; var liked: [Track]; var history: [ListenEvent]; var artists: [String] }
    func save() {
        guard !restoring else { return }
        saveTask?.cancel()
        let snapshot = Snapshot(localTracks: localTracks, liked: liked, history: history, artists: preferredArtists)
        let destination = file
        saveTask = Task {
            try? await Task.sleep(nanoseconds: 300_000_000)
            guard !Task.isCancelled else { return }
            await Task.detached(priority: .utility) {
                guard let data = try? JSONEncoder().encode(snapshot) else { return }
                try? data.write(to: destination, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            }.value
        }
    }
    func toggleLike(_ track: Track) {
        if let i = liked.firstIndex(where: { $0.id == track.id }) { liked.remove(at: i) }
        else { liked.insert(track, at: 0); liked = Array(liked.prefix(1800)) }
        save()
    }
    func record(_ track: Track, completion: Double, skipped: Bool, failed: Bool = false) {
        history.append(ListenEvent(track: track, date: Date(), completion: completion, skipped: skipped, failed: failed))
        history = Array(history.suffix(1000)); save()
    }
    func recentIDs(hours: Double = 6) -> Set<String> {
        Set(history.filter { !$0.failed && Date().timeIntervalSince($0.date) < hours * 3600 }.map { $0.track.id })
    }
    func orderRadio(_ tracks: [Track], seed: Track) -> [Track] {
        let recent = recentIDs()
        let topArtists = Set(preferredArtists + liked.map(\.artist))
        let valid = tracks.enumerated().filter { $0.element.id != seed.id && !recent.contains($0.element.id) }
        return valid.sorted { a, b in
            let aBoost = topArtists.contains(a.element.artist) ? 10 : 0
            let bBoost = topArtists.contains(b.element.artist) ? 10 : 0
            return a.offset - aBoost < b.offset - bBoost
        }.map(\.element)
    }
    func localURL(_ track: Track) -> URL? {
        guard let file = track.localFile, file == URL(fileURLWithPath: file).lastPathComponent else { return nil }
        return audioFolder.appendingPathComponent(file)
    }
    func importFiles(_ urls: [URL]) async {
        guard localTracks.count < 250 else { importError = "Library limit: 250 imported tracks."; return }
        let destination = audioFolder
        let slots = 250 - localTracks.count
        let result = await Task.detached(priority: .userInitiated) { () -> ([Track], [String]) in
            var tracks: [Track] = []; var errors: [String] = []
            for url in urls.prefix(min(slots, 50)) {
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                do {
                    let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                    guard size > 0 && size <= 512 * 1024 * 1024 else { throw MusicError.message("Choose a file below 512 MiB.") }
                    let available = (try? destination.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage) ?? 0
                    guard available > Int64(size) + 128 * 1024 * 1024 else { throw MusicError.message("Not enough free storage.") }
                    let audio = try AVAudioFile(forReading: url)
                    let format = audio.fileFormat.streamDescription.pointee
                    guard [kAudioFormatFLAC, kAudioFormatAppleLossless, kAudioFormatLinearPCM].contains(format.mFormatID) else {
                        throw MusicError.message("This file is compressed audio, not a lossless FLAC, ALAC or PCM source.")
                    }
                    let name = UUID().uuidString + "." + url.pathExtension.lowercased()
                    let target = destination.appendingPathComponent(name)
                    try FileManager.default.copyItem(at: url, to: target)
                    try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: target.path)
                    var values = URLResourceValues(); values.isExcludedFromBackup = true
                    var targetURL = target; try? targetURL.setResourceValues(values)
                    let codec = format.mFormatID == kAudioFormatFLAC ? "FLAC" : format.mFormatID == kAudioFormatAppleLossless ? "ALAC" : "PCM"
                    let label = "Lossless · \(codec) · \(String(format: "%.1f", format.mSampleRate / 1000)) kHz"
                    tracks.append(Track(id: "local-" + name, title: url.deletingPathExtension().lastPathComponent,
                                        artist: "On your iPhone", cover: "", duration: Double(audio.length) / audio.processingFormat.sampleRate,
                                        localFile: name, sourceLabel: label))
                } catch { errors.append("\(url.lastPathComponent): \(error.localizedDescription)") }
            }
            return (tracks, errors)
        }.value
        localTracks.append(contentsOf: result.0); save()
        if !result.1.isEmpty { importError = result.1.joined(separator: "\n") }
    }
    func removeLocal(_ track: Track) {
        if let url = localURL(track) { try? FileManager.default.removeItem(at: url) }
        localTracks.removeAll { $0.id == track.id }; liked.removeAll { $0.id == track.id }; save()
    }
    func clearHistory() { history = []; save() }
}
