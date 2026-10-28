import Foundation
import SwiftUI
import UIKit

struct Track: Codable, Identifiable, Equatable {
    var id: String
    var title: String
    var artist: String
    var cover: String
    var duration: Double
    var localFile: String?
    var sourceLabel: String?
}
struct CollectionCard: Codable, Identifiable, Equatable {
    var id: String
    var title: String
    var artist: String
    var cover: String
}
struct Catalog: Codable {
    var tracks: [Track]
    var albums: [CollectionCard]
    var artists: [CollectionCard]
    static let empty = Catalog(tracks: [], albums: [], artists: [])
}
struct Source: Decodable {
    var url: String
    var bitrate: Int
    var label: String
    var expiresIn: Double
    var hls: Bool
}
struct ListenEvent: Codable {
    var track: Track
    var date: Date
    var completion: Double
    var skipped: Bool
    var failed: Bool
}
enum Quality: String, CaseIterable, Codable { case superHigh = "Super high", high = "High", low = "Low" }
enum AppFont: String, CaseIterable { case manrope = "Manrope", inter = "Inter", roboto = "Roboto" }
enum PlaybackPhase: Equatable {
    case idle, connecting, buffering, playing, paused, failed(String)
    var label: String {
        switch self {
        case .idle: return "Choose a song"
        case .connecting: return "Connecting"
        case .buffering: return "Buffering"
        case .playing: return "Playing"
        case .paused: return "Paused"
        case .failed(let reason): return reason
        }
    }
    var busy: Bool { self == .connecting || self == .buffering }
}
enum TimeLabel {
    static func string(_ value: Double) -> String {
        let seconds = value.isFinite ? Int(min(31_536_000, max(0, value))) : 0
        return String(format: "%d:%02d", seconds / 60, seconds % 60)
    }
}
enum Visual {
    static let background = Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(red: 0.035, green: 0.043, blue: 0.055, alpha: 1) : .systemBackground })
    static let panel = Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(red: 0.085, green: 0.098, blue: 0.115, alpha: 1) : .secondarySystemBackground })
    static let coral = Color(red: 1, green: 0.43, blue: 0.35)
    static func font(_ size: CGFloat, bold: Bool = false, family: String = "Manrope") -> Font {
        .custom("Mousike\(family)-\(bold ? "Bold" : "Regular")", size: size, relativeTo: .body)
    }
}
