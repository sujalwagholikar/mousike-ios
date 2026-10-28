import SwiftUI
import Combine

@MainActor final class AppModel: ObservableObject {
    @Published var home = Catalog.empty
    @Published var results = Catalog.empty
    @Published var homeLoading = false
    @Published var searching = false
    @Published var homeError: String?
    @Published var searchError: String?
    @Published var query = ""
    private var searchTask: Task<Void, Never>?
    func refresh(_ library: LibraryStore) async {
        guard !homeLoading else { return }; homeLoading = true; homeError = nil
        defer { homeLoading = false }
        do {
            let first: Catalog = try await Backend.shared.call("home")
            home = first
            let artists = library.preferredArtists + library.history.filter { !$0.failed && $0.completion > 0.7 }.suffix(5).map { $0.track.artist }
            let seeds = Array(Set(artists)).sorted().prefix(2)
            // Keep at most three small pages; never launch dozens of feed jobs.
            let genres = Array((UserDefaults.standard.stringArray(forKey: "genres") ?? ["Hindi", "English indie"]).prefix(2))
            let queries = seeds.isEmpty ? (genres.isEmpty ? ["Hindi English music albums", "Marathi indie songs"] : genres.map { $0 + " music songs albums" }) : seeds.map { $0 + " songs albums" }
            for query in queries {
                try Task.checkCancellation()
                if let page: Catalog = try? await Backend.shared.call("search", ["query": query]) { home = Self.merge(home, page) }
            }
            if home.tracks.isEmpty && home.albums.isEmpty { homeError = "Music could not be loaded. Check your connection and try again." }
        } catch is CancellationError { }
        catch { homeError = error.localizedDescription }
    }
    static func merge(_ a: Catalog, _ b: Catalog) -> Catalog {
        func unique<T: Identifiable>(_ items: [T], limit: Int) -> [T] where T.ID: Hashable {
            var seen = Set<T.ID>(); return Array(items.filter { seen.insert($0.id).inserted }.prefix(limit))
        }
        return Catalog(tracks: unique(a.tracks + b.tracks, limit: 240), albums: unique(a.albums + b.albums, limit: 100), artists: unique(a.artists + b.artists, limit: 80))
    }
    func search() {
        searchTask?.cancel(); let current = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !current.isEmpty else { results = .empty; searching = false; searchError = nil; return }
        searching = true; searchError = nil
        searchTask = Task {
            do {
                try await Task.sleep(nanoseconds: 400_000_000)
                let page: Catalog = try await Backend.shared.call("search", ["query": current])
                try Task.checkCancellation(); guard query.trimmingCharacters(in: .whitespacesAndNewlines) == current else { return }
                results = page; searching = false
            } catch is CancellationError { }
            catch { if query.trimmingCharacters(in: .whitespacesAndNewlines) == current { searchError = error.localizedDescription; searching = false } }
        }
    }
}
