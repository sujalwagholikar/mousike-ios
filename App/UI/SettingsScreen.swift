import SwiftUI

@MainActor struct SettingsScreen: View {
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var player: MusicPlayer
    @EnvironmentObject var model: AppModel
    @AppStorage("quality") var quality = "High"
    @AppStorage("cellular") var cellular = true
    @AppStorage("preload") var preload = true
    @AppStorage("font") var font = "Manrope"
    @AppStorage("artBackground") var artBackground = true
    @AppStorage("motion") var motion = true
    @AppStorage("appearance") var appearance = "Dark"
    @AppStorage("onboarded") var onboarded = true
    @State private var cleared = false
    var body: some View {
        Form {
            Section(header: Text("Audio & connection"), footer: Text("Quality applies to the next source resolution. Actual online bitrate depends on the supplied AAC/HLS formats. Lossless is available for genuine FLAC, ALAC and PCM files imported from Files.")) {
                Picker("Online quality", selection: $quality) { ForEach(Quality.allCases, id: \.rawValue) { Text($0.rawValue).tag($0.rawValue) } }
                Toggle("Use mobile data for audio", isOn: $cellular)
                Toggle("Prepare next source on Wi-Fi", isOn: $preload)
                if let milliseconds = player.startupMilliseconds { LabeledValue(title: "Last measured start", value: "\(milliseconds) ms") }
                LabeledValue(title: "Audio output", value: player.outputName)
                SystemVolume().frame(height: 30).padding(.top, 8)
            }
            Section(header: Text("Appearance"), footer: Text("Animations respect Reduce Motion. Rendering follows the device’s supported refresh rate; this app cannot force 120 Hz on every iPhone.")) {
                Picker("Typography", selection: $font) { ForEach(AppFont.allCases, id: \.rawValue) { Text($0.rawValue).tag($0.rawValue) } }
                Picker("Colour scheme", selection: $appearance) { ForEach(["Dark", "Light", "System"], id: \.self) { Text($0).tag($0) } }
                Toggle("Full player artwork background", isOn: $artBackground)
                Toggle("Player motion", isOn: $motion)
            }
            Section(header: Text("Your taste"), footer: Text("Taste and likes remain on this iPhone. No Gemini key, activation code or account is required.")) {
                NavigationLink("Build my taste mix") { TasteMixScreen() }
                if !library.preferredArtists.isEmpty {
                    ForEach(library.preferredArtists, id: \.self) { artist in
                        HStack { Text(artist); Spacer(); Button { library.preferredArtists.removeAll { $0 == artist } } label: { Image(systemName: "minus.circle") }.accessibilityLabel("Unfollow \(artist)") }
                    }
                }
                Button("Refresh home recommendations") { Task { await model.refresh(library) } }
                Button("Choose genres again") { onboarded = false }
            }
            Section(header: Text("Storage & privacy"), footer: Text("Artwork cache is capped at 24 MiB decoded memory. API cache retains up to 8 responses / 3 MiB / 90 seconds. Imported audio is explicit library storage, not an automatic cache.")) {
                LabeledValue(title: "Imported lossless files", value: "\(library.localTracks.count) / 250")
                Button(cleared ? "Temporary cache cleared" : "Clear temporary cache") { ArtworkLoader.clear(); Backend.shared.trim(); cleared = true }
                Button("Delete listening history", role: .destructive) { library.clearHistory() }
            }
            Section("About Mousiké") {
                HStack(spacing: 15) {
                    Image("Developer").resizable().scaledToFit().frame(width: 64, height: 75).cornerRadius(7)
                    VStack(alignment: .leading, spacing: 5) { Text("Sujal Wagholikar").font(Visual.font(17, bold: true)); Text("Developer & owner").font(.caption).foregroundColor(.secondary) }
                }.padding(.vertical, 8)
                Link(destination: URL(string: "https://www.instagram.com/sujalwagholikar/")!) { Label("Instagram · @sujalwagholikar", systemImage: "camera") }
                Link(destination: URL(string: "https://github.com/sujalwagholikar")!) { Label("GitHub · sujalwagholikar", systemImage: "chevron.left.forwardslash.chevron.right") }
                Link(destination: URL(string: "https://sujalwagholikar.github.io/mousike/")!) { Label("Mousiké website", systemImage: "globe") }
                LabeledValue(title: "iOS port", value: "3.9.1 · source preview")
                Text("© 2026 Sujal Wagholikar.\nIndependent app. Not affiliated with YouTube, Google, Spotify or Apple.").font(.caption).foregroundColor(.secondary)
            }
        }.navigationTitle("Settings").navigationBarHidden(false)
    }
}
@MainActor struct LabeledValue: View {
    let title: String
    let value: String
    var body: some View { HStack { Text(title); Spacer(); Text(value).foregroundColor(.secondary).multilineTextAlignment(.trailing) } }
}
