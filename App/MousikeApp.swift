import SwiftUI

@main @MainActor struct MousikeApp: App {
    @StateObject private var library: LibraryStore
    @StateObject private var player: MusicPlayer
    @StateObject private var model = AppModel()
    init() {
        let library = LibraryStore()
        _library = StateObject(wrappedValue: library)
        _player = StateObject(wrappedValue: MusicPlayer(library: library))
        UserDefaults.standard.register(defaults: ["quality": "High", "cellular": true, "preload": true, "artBackground": true, "font": "Manrope", "appearance": "Dark", "motion": true])
    }
    var body: some Scene {
        WindowGroup {
            RootScreen().environmentObject(library).environmentObject(player).environmentObject(model)
        }
    }
}
