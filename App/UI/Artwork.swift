import SwiftUI
import UIKit
import ImageIO

@MainActor final class ArtworkLoader: ObservableObject {
    @Published var image: UIImage?
    private static let cache: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>(); cache.countLimit = 80; cache.totalCostLimit = 24 * 1024 * 1024; return cache
    }()
    private static let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral; config.timeoutIntervalForRequest = 8
        config.timeoutIntervalForResource = 12; config.URLCache = nil; config.HTTPMaximumConnectionsPerHost = 4
        return URLSession(configuration: config)
    }()
    static func cached(_ key: String) -> UIImage? { cache.object(forKey: key as NSString) }
    static func clear() { cache.removeAllObjects() }
    func load(_ key: String) async {
        image = Self.cached(key)
        guard image == nil, let url = URL(string: key), url.scheme == "https", let host = url.host,
              ["ytimg.com", "googleusercontent.com", "ggpht.com"].contains(where: { host == $0 || host.hasSuffix("." + $0) }) else { return }
        do {
            let (data, response) = try await Self.session.data(from: url)
            guard !Task.isCancelled, (response as? HTTPURLResponse)?.statusCode == 200, data.count <= 4 * 1024 * 1024 else { return }
            let decoded = await Task.detached(priority: .utility) { () -> UIImage? in
                guard let source = CGImageSourceCreateWithData(data as CFData, nil),
                      let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true,
                        kCGImageSourceThumbnailMaxPixelSize: 720, kCGImageSourceCreateThumbnailWithTransform: true] as CFDictionary) else { return nil }
                return UIImage(cgImage: cg)
            }.value
            guard !Task.isCancelled, let decoded = decoded else { return }
            Self.cache.setObject(decoded, forKey: key as NSString, cost: Int(decoded.size.width * decoded.size.height * 4))
            image = decoded
        } catch { }
    }
}
@MainActor struct Cover: View {
    let url: String
    var fill = false
    @StateObject private var loader = ArtworkLoader()
    var body: some View {
        GeometryReader { geometry in
            ZStack {
                LinearGradient(colors: [Visual.coral.opacity(0.3), Visual.panel], startPoint: .topLeading, endPoint: .bottomTrailing)
                if let image = loader.image {
                    if fill { Image(uiImage: image).resizable().scaledToFill().frame(width: geometry.size.width, height: geometry.size.height).clipped() }
                    else { Image(uiImage: image).resizable().scaledToFit().frame(width: geometry.size.width, height: geometry.size.height) }
                } else { Image(systemName: "waveform").font(.system(size: max(18, min(42, geometry.size.width / 4)))).foregroundColor(Visual.coral) }
            }.frame(width: geometry.size.width, height: geometry.size.height).clipped()
        }.task(id: url) { await loader.load(url) }
    }
}

struct NavGlyph: Shape {
    var kind: Int
    func path(in r: CGRect) -> Path {
        let w = r.width, h = r.height
        return Path { p in
            switch kind {
            case 0:
                p.move(to: CGPoint(x: 0.12*w, y: 0.46*h)); p.addLine(to: CGPoint(x: 0.5*w, y: 0.13*h)); p.addLine(to: CGPoint(x: 0.88*w, y: 0.46*h))
                p.move(to: CGPoint(x: 0.23*w, y: 0.43*h)); p.addLine(to: CGPoint(x: 0.23*w, y: 0.88*h)); p.addLine(to: CGPoint(x: 0.77*w, y: 0.88*h)); p.addLine(to: CGPoint(x: 0.77*w, y: 0.43*h))
                p.move(to: CGPoint(x: 0.43*w, y: 0.87*h)); p.addLine(to: CGPoint(x: 0.43*w, y: 0.65*h)); p.addLine(to: CGPoint(x: 0.58*w, y: 0.65*h)); p.addLine(to: CGPoint(x: 0.58*w, y: 0.87*h))
            case 1:
                p.addEllipse(in: CGRect(x: w*0.13, y: h*0.12, width: w*0.56, height: h*0.56))
                p.move(to: CGPoint(x: w*0.64, y: h*0.64)); p.addLine(to: CGPoint(x: w*0.88, y: h*0.88))
            case 2:
                for x in [0.15, 0.36, 0.61] {
                    p.move(to: CGPoint(x: x*w, y: 0.2*h)); p.addLine(to: CGPoint(x: (x+0.03)*w, y: 0.86*h))
                }
                p.move(to: CGPoint(x: 0.65*w, y: 0.2*h)); p.addLine(to: CGPoint(x: 0.88*w, y: 0.81*h))
            default:
                for (x, y) in [(0.23,0.3),(0.5,0.68),(0.78,0.43)] {
                    p.move(to: CGPoint(x: w*x, y: h*0.12)); p.addLine(to: CGPoint(x: w*x, y: h*(y-0.1)))
                    p.addEllipse(in: CGRect(x: w*(x-0.09), y: h*(y-0.09), width: w*0.18, height: h*0.18))
                    p.move(to: CGPoint(x: w*x, y: h*(y+0.1))); p.addLine(to: CGPoint(x: w*x, y: h*0.88))
                }
            }
        }
    }
}
