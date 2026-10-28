import SwiftUI
import AVKit
import MediaPlayer
import UniformTypeIdentifiers

@MainActor struct RootScreen: View {
    @EnvironmentObject private var player: MusicPlayer
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var library: LibraryStore
    @AppStorage("onboarded") private var onboarded = false
    @AppStorage("font") private var font = "Manrope"
    @AppStorage("appearance") private var appearance = "Dark"
    @State private var tab = 0
    var body: some View {
        Group {
            if !onboarded { OnboardingScreen() }
            else {
                NavigationView {
                    Group {
                        switch tab {
                        case 0: HomeScreen()
                        case 1: SearchScreen()
                        case 2: LibraryScreen()
                        default: SettingsScreen()
                        }
                    }.background(Visual.background.ignoresSafeArea())
                        .safeAreaInset(edge: .bottom, spacing: 0) {
                            VStack(spacing: 0) {
                                if player.track != nil { MiniPlayer().padding(.horizontal, 12).padding(.bottom, 8) }
                                HStack(spacing: 0) {
                                    ForEach(0..<4) { index in
                                        Button { tab = index } label: {
                                            VStack(spacing: 5) {
                                                NavGlyph(kind: index).stroke(style: StrokeStyle(lineWidth: 1.8, lineCap: .round, lineJoin: .round)).frame(width: 23, height: 23)
                                                Text(["Home", "Search", "Library", "Settings"][index]).font(.system(size: 10, weight: .medium))
                                            }.foregroundColor(tab == index ? Visual.coral : .secondary).frame(maxWidth: .infinity).frame(minHeight: 52)
                                        }.accessibilityLabel(["Home", "Search", "Library", "Settings"][index])
                                    }
                                }.padding(.top, 7).background(Visual.background)
                            }.background(Visual.background)
                        }
                }.navigationViewStyle(.stack)
            }
        }.font(Visual.font(15, family: font)).tint(Visual.coral)
            .preferredColorScheme(appearance == "System" ? nil : appearance == "Light" ? .light : .dark)
            .sheet(isPresented: $player.expanded) { ExpandedPlayer() }
    }
}

@MainActor struct OnboardingScreen: View {
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var model: AppModel
    @AppStorage("onboarded") var onboarded = false
    @State private var selected = Set<String>()
    let genres = ["Bollywood", "Indie", "Pop", "Marathi", "Hip-hop", "Electronic", "Classical", "Rock"]
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 25) {
                Image("MousikeLogo").resizable().scaledToFit().frame(width: 64, height: 64).padding(.top, 50)
                Text("Make room\nfor your music.").font(Visual.font(38, bold: true)).lineSpacing(-2)
                Text("A familiar feeling. A new discovery.\nStart with the sounds you love.").foregroundColor(.secondary)
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                    ForEach(genres, id: \.self) { genre in
                        Button { if selected.contains(genre) { selected.remove(genre) } else { selected.insert(genre) } } label: {
                            HStack { Text(genre); Spacer(); Image(systemName: selected.contains(genre) ? "checkmark.circle.fill" : "plus") }
                                .padding(16).background(selected.contains(genre) ? Visual.coral.opacity(0.2) : Visual.panel).cornerRadius(12)
                        }.foregroundColor(.primary)
                    }
                }
                Button {
                    UserDefaults.standard.set(Array(selected), forKey: "genres"); onboarded = true
                } label: { Text("Find my music").font(Visual.font(16, bold: true)).frame(maxWidth: .infinity).padding(18).background(Visual.coral).foregroundColor(.black).cornerRadius(28) }
                    .accessibilityIdentifier("startListening")
                Text("No code. No account required.").font(.footnote).foregroundColor(.secondary).frame(maxWidth: .infinity)
            }.padding(25)
        }.background(Visual.background.ignoresSafeArea())
    }
}

@MainActor struct HomeScreen: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var player: MusicPlayer
    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 28) {
                HStack(spacing: 10) {
                    Image("MousikeLogo").resizable().scaledToFit().frame(width: 36, height: 36)
                    Text("Mousiké").font(Visual.font(29, bold: true)); Spacer()
                    Image(systemName: "waveform").foregroundColor(Visual.coral)
                }.padding(.top, 10)
                VStack(alignment: .leading, spacing: 7) {
                    Text("A little more you.").font(Visual.font(31, bold: true))
                    Text("Your familiar favourites. Your next discovery.").font(Visual.font(13, family: "Inter")).foregroundColor(.secondary)
                }
                if model.homeLoading && model.home.tracks.isEmpty { SkeletonShelf() }
                if let error = model.homeError { RetryPanel(message: error) { Task { await model.refresh(library) } } }
                if !model.home.albums.isEmpty { CollectionShelf(title: "Albums to get lost in", cards: model.home.albums) }
                if !model.home.tracks.isEmpty {
                    Text("In your rotation").font(Visual.font(22, bold: true))
                    ForEach(model.home.tracks.prefix(12)) { track in TrackRow(track: track) { player.play(track) } }
                }
                NavigationLink { TasteMixScreen() } label: {
                    HStack(alignment: .top) {
                        VStack(alignment: .leading, spacing: 9) {
                            Text("MADE FROM YOUR LISTENING").font(.system(size: 10, weight: .semibold)).tracking(2)
                            Text("Build my taste").font(Visual.font(27, bold: true))
                            Text("A collection shaped by what you finish, like and revisit.").font(.system(size: 13)).opacity(0.8)
                        }; Spacer(); Image(systemName: "arrow.up.right").font(.title2)
                    }.foregroundColor(.black).padding(22).background(LinearGradient(colors: [Visual.coral, Color(red: 1, green: 0.76, blue: 0.56)], startPoint: .topLeading, endPoint: .bottomTrailing)).cornerRadius(18)
                }
                if !model.home.artists.isEmpty { CollectionShelf(title: "Artists in your orbit", cards: model.home.artists, artist: true) }
                if model.home.tracks.count > 12 {
                    Text("Keep discovering").font(Visual.font(22, bold: true))
                    ForEach(model.home.tracks.dropFirst(12).prefix(36)) { track in TrackRow(track: track) { player.play(track) } }
                }
            }.padding(.horizontal, 20).padding(.bottom, 24)
        }.navigationBarHidden(true).refreshable { await model.refresh(library) }
            .task { if model.home.tracks.isEmpty { await model.refresh(library) } }
    }
}

@MainActor struct TrackRow: View {
    let track: Track
    var onTap: () -> Void
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var player: MusicPlayer
    var body: some View {
        HStack(spacing: 12) {
            Button(action: onTap) {
                HStack(spacing: 12) {
                    Cover(url: track.cover).frame(width: 50, height: 50).cornerRadius(4)
                    VStack(alignment: .leading, spacing: 5) {
                        Text(track.title).font(Visual.font(14, bold: true, family: "Inter")).foregroundColor(player.track?.id == track.id ? Visual.coral : .primary).lineLimit(1)
                        Text(track.artist).font(Visual.font(12, family: "Roboto")).foregroundColor(.secondary).lineLimit(1)
                    }; Spacer(minLength: 0)
                }.contentShape(Rectangle())
            }.buttonStyle(.plain)
            Menu {
                Button { library.toggleLike(track) } label: { Label(library.liked.contains(where: { $0.id == track.id }) ? "Remove from liked" : "Like song", systemImage: "heart") }
                Button { player.enqueue(track) } label: { Label("Add to queue", systemImage: "text.line.first.and.arrowtriangle.forward") }
                if track.localFile != nil {
                    Button("Remove imported copy", role: .destructive) { player.stopForDeletion(track); library.removeLocal(track) }
                }
            } label: { Image(systemName: "ellipsis").foregroundColor(.secondary).frame(width: 36, height: 44).contentShape(Rectangle()) }
        }.padding(.vertical, 3)
    }
}
@MainActor struct CollectionShelf: View {
    let title: String
    let cards: [CollectionCard]
    var artist = false
    var body: some View {
        VStack(alignment: .leading, spacing: 15) {
            Text(title).font(Visual.font(22, bold: true))
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 16) {
                    ForEach(cards) { card in
                        NavigationLink { CollectionScreen(card: card) } label: {
                            VStack(alignment: .leading, spacing: 9) {
                                Cover(url: card.cover).frame(width: 144, height: 144).clipShape(RoundedRectangle(cornerRadius: artist ? 72 : 5))
                                Text(card.title).font(Visual.font(14, bold: true)).lineLimit(2).foregroundColor(.primary)
                                if !card.artist.isEmpty { Text(card.artist).font(.system(size: 11)).foregroundColor(.secondary).lineLimit(1) }
                            }.frame(width: 144, alignment: .leading)
                        }
                    }
                }
            }
        }
    }
}
@MainActor struct CollectionScreen: View {
    let card: CollectionCard
    @State private var page = Catalog.empty
    @State private var loading = true
    @State private var error: String?
    @EnvironmentObject var player: MusicPlayer
    @EnvironmentObject var library: LibraryStore
    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 18) {
                Cover(url: card.cover).aspectRatio(1, contentMode: .fit).frame(maxWidth: 300).frame(maxWidth: .infinity)
                Text(card.title).font(Visual.font(28, bold: true))
                Text(card.artist).foregroundColor(.secondary)
                if card.id.hasPrefix("UC") {
                    Button(library.preferredArtists.contains(card.title) ? "Following" : "Follow artist") {
                        if library.preferredArtists.contains(card.title) { library.preferredArtists.removeAll { $0 == card.title } }
                        else { library.preferredArtists.append(card.title) }
                    }.buttonStyle(.bordered)
                }
                if loading { SkeletonShelf() }
                if let error = error { RetryPanel(message: error) { Task { await load() } } }
                if !page.tracks.isEmpty {
                    Button { player.play(page.tracks[0], context: Array(page.tracks.dropFirst())) } label: {
                        Label("Play collection", systemImage: "play.fill").padding(14).frame(maxWidth: .infinity).background(Visual.coral).foregroundColor(.black).cornerRadius(24)
                    }
                    ForEach(page.tracks) { track in
                        TrackRow(track: track) { player.play(track, context: Array(page.tracks.drop(while: { $0.id != track.id }).dropFirst())) }
                    }
                } else if !loading && error == nil { Text("No song list is available for this collection.").foregroundColor(.secondary) }
                if !page.albums.isEmpty { CollectionShelf(title: "More albums", cards: page.albums) }
            }.padding(20)
        }.background(Visual.background.ignoresSafeArea()).navigationBarHidden(false).navigationBarTitleDisplayMode(.inline)
            .task { await load() }
    }
    private func load() async {
        loading = true; error = nil; defer { loading = false }
        do { page = try await Backend.shared.call("browse", ["id": card.id]) }
        catch { self.error = error.localizedDescription }
    }
}

@MainActor struct SearchScreen: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var player: MusicPlayer
    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 20) {
                Text("Find your next feeling.").font(Visual.font(30, bold: true))
                HStack {
                    Image(systemName: "magnifyingglass").foregroundColor(.secondary)
                    TextField("Songs, albums, artists", text: $model.query).autocapitalization(.none).disableAutocorrection(true).accessibilityIdentifier("musicSearch")
                    if !model.query.isEmpty { Button { model.query = "" } label: { Image(systemName: "xmark.circle.fill") } }
                }.padding(15).background(Visual.panel).cornerRadius(12)
                if model.searching { HStack { ProgressView(); Text("Finding your music").foregroundColor(.secondary) }; SkeletonShelf() }
                else if let error = model.searchError { RetryPanel(message: error) { model.search() } }
                else if !model.query.isEmpty && model.results.tracks.isEmpty && model.results.albums.isEmpty && model.results.artists.isEmpty {
                    VStack(alignment: .leading, spacing: 10) {
                        Image(systemName: "sparkle.magnifyingglass").font(.system(size: 36)).foregroundColor(Visual.coral)
                        Text("Try another name or a different spelling.").font(Visual.font(20, bold: true))
                    }.padding(.vertical, 30)
                }
                if !model.results.albums.isEmpty { CollectionShelf(title: "Albums", cards: model.results.albums) }
                ForEach(model.results.tracks) { track in TrackRow(track: track) { player.play(track) } }
                if !model.results.artists.isEmpty { CollectionShelf(title: "Artists", cards: model.results.artists, artist: true) }
            }.padding(20)
        }.navigationBarHidden(true).onChange(of: model.query) { _ in model.search() }
    }
}

@MainActor struct LibraryScreen: View {
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var player: MusicPlayer
    @State private var importing = false
    @State private var importBusy = false
    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 18) {
                Text("Your library.").font(Visual.font(32, bold: true))
                Button { importing = true } label: { Label("Import lossless from Files", systemImage: "plus.circle.fill").font(Visual.font(15, bold: true)).padding(17).frame(maxWidth: .infinity).background(Visual.panel).cornerRadius(12) }.disabled(importBusy)
                Text("FLAC, Apple Lossless and PCM WAV/AIFF. Original files stay unchanged. Imported copies use device storage.").font(.system(size: 12)).foregroundColor(.secondary)
                if importBusy { ProgressView("Checking source format and importing…") }
                if let error = library.importError { Text(error).font(.footnote).foregroundColor(Visual.coral) }
                if !library.localTracks.isEmpty {
                    Text("Lossless on this iPhone").font(Visual.font(22, bold: true))
                    ForEach(library.localTracks) { track in TrackRow(track: track) { player.play(track, context: library.localTracks.filter { $0.id != track.id }) } }
                }
                Text("Liked songs").font(Visual.font(22, bold: true))
                if library.liked.isEmpty { Text("Tap a song’s heart to keep it close.").foregroundColor(.secondary) }
                ForEach(library.liked) { track in TrackRow(track: track) { player.play(track) } }
            }.padding(20)
        }.navigationBarHidden(true)
            .fileImporter(isPresented: $importing, allowedContentTypes: [.audio], allowsMultipleSelection: true) { result in
                switch result {
                case .success(let urls): Task { importBusy = true; await library.importFiles(urls); importBusy = false }
                case .failure(let error): library.importError = error.localizedDescription
                }
            }
    }
}

@MainActor struct TasteMixScreen: View {
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var player: MusicPlayer
    @State private var tracks: [Track] = []
    @State private var busy = true
    @State private var error: String?
    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 18) {
                Text("Built around you.").font(Visual.font(32, bold: true))
                Text("Completed listens and liked artists shape this mix. Connection failures never count against your taste.").foregroundColor(.secondary)
                if busy { ProgressView("Finding songs for your taste…"); SkeletonShelf() }
                if let error = error { Text(error).foregroundColor(.secondary) }
                ForEach(tracks) { track in TrackRow(track: track) { player.play(track, context: Array(tracks.drop(while: { $0.id != track.id }).dropFirst())) } }
            }.padding(20)
        }.navigationBarHidden(false).navigationTitle("Your taste mix").navigationBarTitleDisplayMode(.inline)
            .task {
                let completed = library.history.filter { !$0.failed && $0.completion >= 0.75 }.suffix(20).map { $0.track.artist }
                let names = Array(Set(library.preferredArtists + library.liked.map(\.artist) + completed)).sorted()
                let query = Array(names.prefix(3)).joined(separator: " ")
                do {
                    let genres = (UserDefaults.standard.stringArray(forKey: "genres") ?? ["indie", "Hindi", "English"]).joined(separator: " ")
                    let page: Catalog = try await Backend.shared.call("search", ["query": query.isEmpty ? genres + " songs" : query + " songs"])
                    let recent = library.recentIDs(); tracks = page.tracks.filter { !recent.contains($0.id) }
                    if tracks.isEmpty { error = "No fresh songs were found. Listen and like more music, then build again." }
                } catch { self.error = error.localizedDescription }; busy = false
            }
    }
}
@MainActor struct SkeletonShelf: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            RoundedRectangle(cornerRadius: 5).fill(Visual.panel).frame(width: 180, height: 20)
            HStack(spacing: 12) { ForEach(0..<3) { _ in RoundedRectangle(cornerRadius: 5).fill(Visual.panel).aspectRatio(1, contentMode: .fit) } }
        }.accessibilityLabel("Loading music")
    }
}
@MainActor struct RetryPanel: View {
    var message: String
    var retry: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 12) { Text(message).font(.footnote).foregroundColor(.secondary); Button("Try again", action: retry).buttonStyle(.bordered) }.padding(18).frame(maxWidth: .infinity, alignment: .leading).background(Visual.panel).cornerRadius(14)
    }
}
