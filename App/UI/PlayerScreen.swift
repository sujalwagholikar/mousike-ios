import SwiftUI
import AVKit
import MediaPlayer

@MainActor struct MiniPlayer: View {
    @EnvironmentObject var player: MusicPlayer
    var body: some View {
        HStack(spacing: 10) {
            Button { player.expanded = true } label: {
                HStack(spacing: 10) {
                    Cover(url: player.track?.cover ?? "").frame(width: 44, height: 44).cornerRadius(3)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(player.track?.title ?? "").font(Visual.font(12, bold: true, family: "Inter")).lineLimit(1).foregroundColor(.primary)
                        Text(player.phase.busy ? player.phase.label : player.track?.artist ?? "").font(.system(size: 11)).foregroundColor(.secondary).lineLimit(1)
                    }; Spacer(minLength: 0)
                }.contentShape(Rectangle())
            }.buttonStyle(.plain)
            Button { player.toggle() } label: {
                if player.phase.busy { ProgressView().frame(width: 24, height: 24) }
                else { Image(systemName: player.phase == .playing ? "pause.fill" : "play.fill").font(.system(size: 22)).frame(width: 30, height: 42) }
            }.accessibilityLabel(player.phase == .playing ? "Pause" : "Play")
            Button { player.next() } label: { Image(systemName: "forward.end.fill").frame(width: 30, height: 42) }.disabled(player.queue.isEmpty).accessibilityLabel("Next song")
        }.padding(8).foregroundColor(.primary).background(Visual.panel).cornerRadius(12)
    }
}
@MainActor struct ExpandedPlayer: View {
    @EnvironmentObject var player: MusicPlayer
    @EnvironmentObject var library: LibraryStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @AppStorage("artBackground") var artBackground = true
    @AppStorage("motion") var motion = true
    @State private var queueVisible = false
    @State private var seeking = false
    @State private var seekPosition = 0.0
    var body: some View {
        GeometryReader { geometry in
            ZStack {
                Color(red: 0.04, green: 0.04, blue: 0.055)
                if artBackground {
                    Cover(url: player.track?.cover ?? "", fill: true).blur(radius: 38).opacity(0.45)
                    LinearGradient(colors: [.black.opacity(0.15), .black.opacity(0.9)], startPoint: .top, endPoint: .bottom)
                }
                ScrollView {
                    VStack(spacing: 24) {
                        HStack {
                            Button { dismiss() } label: { Image(systemName: "chevron.down").font(.system(size: 21, weight: .semibold)).frame(width: 44, height: 44) }.accessibilityLabel("Close player")
                            Spacer(); Text("NOW PLAYING").font(Visual.font(10, bold: true, family: "Inter")).tracking(2); Spacer()
                            Menu {
                                if let track = player.track {
                                    Button { library.toggleLike(track) } label: { Label("Like song", systemImage: "heart") }
                                }
                                Button { queueVisible = true } label: { Label("View queue", systemImage: "list.bullet") }
                            } label: { Image(systemName: "ellipsis").frame(width: 44, height: 44) }
                        }
                        Cover(url: player.track?.cover ?? "").aspectRatio(1, contentMode: .fit)
                            .frame(maxWidth: min(430, geometry.size.width - 44))
                            .scaleEffect(player.phase == .playing ? 1 : 0.96)
                            .animation(reduceMotion || !motion ? nil : .easeOut(duration: 0.45), value: player.phase == .playing)
                            .shadow(color: .black.opacity(0.3), radius: 20, y: 12)
                        HStack(alignment: .center, spacing: 12) {
                            VStack(alignment: .leading, spacing: 7) {
                                Text(player.track?.title ?? "Choose your music").font(Visual.font(25, bold: true)).lineLimit(2)
                                Text(player.track?.artist ?? "").font(Visual.font(15, family: "Inter")).foregroundColor(.white.opacity(0.65)).lineLimit(1)
                            }; Spacer(minLength: 0)
                            if let current = player.track {
                                Button { library.toggleLike(current) } label: {
                                    Image(systemName: library.liked.contains(where: { $0.id == current.id }) ? "heart.fill" : "heart").font(.system(size: 26)).foregroundColor(Visual.coral).frame(width: 44, height: 44)
                                }.accessibilityLabel("Like song")
                            }
                        }
                        VStack(spacing: 4) {
                            Slider(value: Binding(get: { seeking ? seekPosition : min(player.position, max(1, player.duration)) }, set: { seekPosition = $0 }), in: 0...max(1, player.duration), onEditingChanged: { editing in
                                if editing { seekPosition = player.position; seeking = true }
                                else { seeking = false; player.seek(seekPosition) }
                            }).tint(Visual.coral).accessibilityLabel("Song position")
                            HStack { Text(TimeLabel.string(seeking ? seekPosition : player.position)); Spacer(); Text(TimeLabel.string(player.duration)) }.font(Visual.font(11, family: "Roboto")).foregroundColor(.white.opacity(0.55)).monospacedDigit()
                        }
                        HStack(spacing: 38) {
                            Button { player.previous() } label: { Image(systemName: "backward.end.fill").font(.system(size: 27)).frame(width: 44, height: 44) }.accessibilityLabel("Restart song")
                            Button { player.toggle() } label: {
                                ZStack {
                                    Circle().fill(Visual.coral).frame(width: 76, height: 76)
                                    if player.phase.busy { ProgressView().tint(.black).scaleEffect(1.2) }
                                    else { Image(systemName: player.phase == .playing ? "pause.fill" : "play.fill").font(.system(size: 29)).foregroundColor(.black) }
                                }
                            }.accessibilityLabel(player.phase == .playing ? "Pause" : "Play")
                            Button { player.next() } label: { Image(systemName: "forward.end.fill").font(.system(size: 27)).frame(width: 44, height: 44) }.disabled(player.queue.isEmpty).accessibilityLabel("Next song")
                        }
                        if case .failed(let message) = player.phase {
                            VStack(spacing: 8) { Text(message).font(.footnote).multilineTextAlignment(.center); Button("Retry playback") { player.resume() }.buttonStyle(.bordered) }.foregroundColor(Visual.coral)
                        } else if player.phase.busy { Text(player.phase.label).font(.footnote).foregroundColor(.white.opacity(0.6)) }
                        HStack(spacing: 7) {
                            RoutePicker().frame(width: 28, height: 28)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(player.outputName).font(.system(size: 11, weight: .medium)).lineLimit(1)
                                Text(player.sourceLabel).font(.system(size: 10)).foregroundColor(.white.opacity(0.5)).lineLimit(1)
                            }; Spacer()
                            Button { queueVisible = true } label: { Image(systemName: "list.bullet").font(.system(size: 23)).frame(width: 44, height: 44) }.accessibilityLabel("Open queue")
                        }.foregroundColor(Visual.coral)
                    }.padding(.horizontal, 24).padding(.top, 7).padding(.bottom, 28).frame(maxWidth: 600).frame(maxWidth: .infinity)
                }
            }.ignoresSafeArea(edges: .bottom)
        }.foregroundColor(.white).preferredColorScheme(.dark)
            .sheet(isPresented: $queueVisible) { QueueScreen() }
    }
}
@MainActor struct QueueScreen: View {
    @EnvironmentObject var player: MusicPlayer
    @Environment(\.dismiss) var dismiss
    var body: some View {
        NavigationView {
            List {
                if let current = player.track { Section("Now playing") { Text(current.title).foregroundColor(Visual.coral) } }
                Section("Play next") {
                    ForEach(Array(player.queue.enumerated()), id: \.element.id) { index, track in
                        Button { player.play(track, context: Array(player.queue.dropFirst(index + 1))) } label: { VStack(alignment: .leading, spacing: 4) { Text(track.title); Text(track.artist).font(.caption).foregroundColor(.secondary) } }
                    }.onDelete { indices in for index in indices.sorted(by: >) { player.removeQueued(index) } }
                    if player.queue.isEmpty { Text("Related music will appear here.").foregroundColor(.secondary) }
                }
            }.navigationTitle("Your queue").toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }.navigationViewStyle(.stack)
    }
}
@MainActor struct RoutePicker: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let view = AVRoutePickerView(); view.tintColor = UIColor(Visual.coral); view.activeTintColor = UIColor(Visual.coral); view.prioritizesVideoDevices = false; return view
    }
    func updateUIView(_ uiView: AVRoutePickerView, context: Context) { }
}
@MainActor struct SystemVolume: UIViewRepresentable {
    func makeUIView(context: Context) -> MPVolumeView { let view = MPVolumeView(); view.showsRouteButton = false; return view }
    func updateUIView(_ uiView: MPVolumeView, context: Context) { }
}
