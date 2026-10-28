import Foundation

enum MusicError: LocalizedError {
    case message(String)
    var errorDescription: String? { if case .message(let text) = self { return text }; return nil }
}
final class Backend {
    static let shared = Backend()
    private let queue: OperationQueue = {
        let queue = OperationQueue(); queue.name = "Mousike.Python"; queue.maxConcurrentOperationCount = 2
        queue.qualityOfService = .userInitiated; return queue
    }()
    func call<T: Decodable>(_ operation: String, _ payload: [String: Any] = [:]) async throws -> T {
        let data = try JSONSerialization.data(withJSONObject: payload)
        let json = String(decoding: data, as: UTF8.self)
        let token = UUID().uuidString
        return try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { continuation in
                queue.addOperation {
                    autoreleasepool {
                        do {
                            let raw = MousikePython.execute(operation, payload: json, token: token)
                            guard let envelope = try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: String] else {
                                throw MusicError.message("Invalid engine response")
                            }
                            if let error = envelope["error"] { throw MusicError.message(error) }
                            guard let result = envelope["result"] else { throw MusicError.message("Empty engine response") }
                            continuation.resume(returning: try JSONDecoder().decode(T.self, from: Data(result.utf8)))
                        } catch { continuation.resume(throwing: error) }
                    }
                }
            }
        }, onCancel: { MousikePython.cancel(token) })
    }
    func trim() { Task { let _: [String: Bool]? = try? await call("trim") } }
}
