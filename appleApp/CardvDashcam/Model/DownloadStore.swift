import Foundation
import Shared

/// Resumable downloads of camera files into the app's CarDV folder.
///
/// The camera lists sizes rounded up to 32 KB clusters, so the real total comes from
/// `Content-Range`; the shared `DownloadRanges` does that arithmetic on both platforms. One
/// download runs at a time — the camera's HTTP server is small and this keeps the library snappy.
@MainActor
final class DownloadStore: ObservableObject {
    struct State {
        enum Phase {
            case queued
            case downloading
            case finished
            case failed
        }

        var phase: Phase
        var received: Int64 = 0
        var total: Int64? = nil
        var fileURL: URL? = nil
        var error: String? = nil

        var fraction: Double? {
            guard let total, total > 0 else { return nil }
            return min(1, max(0, Double(received) / Double(total)))
        }

        var isActive: Bool { phase == .queued || phase == .downloading }
    }

    private struct Job {
        let clip: Clip
        let baseURL: String
    }

    private struct Transfer {
        let received: Int64
        let total: Int64?
    }

    enum DownloadError: LocalizedError {
        case badStatus(Int)
        case incomplete

        var errorDescription: String? {
            switch self {
            case .badStatus(let code): return "The camera answered HTTP \(code)."
            case .incomplete: return "The download stopped early. Tap again to resume."
            }
        }
    }

    @Published private(set) var states: [String: State] = [:]

    private var queue: [Job] = []
    private var pumping = false
    private let urlSession: URLSession

    init() {
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 30
        configuration.timeoutIntervalForResource = 900
        configuration.waitsForConnectivity = false
        urlSession = URLSession(configuration: configuration)
    }

    func state(for clip: Clip) -> State? { states[clip.path] }

    func isDownloaded(_ clip: Clip) -> Bool {
        states[clip.path]?.phase == .finished
    }

    func enqueue(_ clip: Clip, baseURL: String) {
        if let existing = states[clip.path], existing.isActive { return }
        if existingFinishedFile(clip) != nil && states[clip.path] == nil {
            let url = LibraryFiles.finalURL(for: clip)
            states[clip.path] = State(phase: .finished, received: fileSize(url), total: fileSize(url), fileURL: url)
            return
        }
        states[clip.path] = State(
            phase: .queued,
            received: fileSize(LibraryFiles.partURL(for: clip)),
            total: nil
        )
        queue.append(Job(clip: clip, baseURL: baseURL))
        pump()
    }

    /// Save a finished download where the user can find it (Photos on iOS, Save As on macOS).
    func save(_ clip: Clip) async throws {
        guard let fileURL = states[clip.path]?.fileURL else { return }
        try await MediaExporter.save(fileURL, isVideo: clip.isVideo)
    }

    func dismiss(_ clip: Clip) {
        states.removeValue(forKey: clip.path)
    }

    // MARK: - Queue

    private func pump() {
        guard !pumping else { return }
        pumping = true
        Task { [weak self] in
            guard let self else { return }
            while !self.queue.isEmpty {
                let job = self.queue.removeFirst()
                await self.run(job)
            }
            self.pumping = false
        }
    }

    private func run(_ job: Job) async {
        let clip = job.clip
        let path = clip.path
        let part = LibraryFiles.partURL(for: clip)
        let offset = fileSize(part)
        states[path] = State(phase: .downloading, received: offset, total: nil)

        do {
            let transfer = try await Self.transfer(
                url: clipURL(baseURL: job.baseURL, clip: clip),
                partURL: part,
                session: urlSession
            ) { [weak self] received, total in
                Task { @MainActor in
                    guard let self, var state = self.states[path] else { return }
                    state.received = received
                    state.total = total
                    self.states[path] = state
                }
            }

            if let total = transfer.total, transfer.received < total {
                throw DownloadError.incomplete
            }

            let destination = LibraryFiles.finalURL(for: clip)
            if FileManager.default.fileExists(atPath: destination.path) {
                try FileManager.default.removeItem(at: destination)
            }
            try FileManager.default.moveItem(at: part, to: destination)
            states[path] = State(
                phase: .finished,
                received: transfer.received,
                total: transfer.total ?? transfer.received,
                fileURL: destination
            )
        } catch {
            var state = states[path] ?? State(phase: .failed)
            state.phase = .failed
            state.received = fileSize(part)
            state.error = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            states[path] = state
        }
    }

    private func clipURL(baseURL: String, clip: Clip) -> URL? {
        let path = clip.path.hasPrefix("/") ? clip.path : "/" + clip.path
        return URL(string: baseURL + path)
    }

    // MARK: - Transfer

    /// Append the camera file to `partURL`, resuming from its current length. Runs off the main actor.
    private nonisolated static func transfer(
        url: URL?,
        partURL: URL,
        session: URLSession,
        progress: @escaping (Int64, Int64?) -> Void
    ) async throws -> Transfer {
        guard let url else { throw DownloadError.badStatus(0) }

        var offset = fileSize(partURL)
        var request = URLRequest(url: url)
        if offset > 0 {
            request.setValue("bytes=\(offset)-", forHTTPHeaderField: "Range")
        }

        let (bytes, response) = try await session.bytes(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw DownloadError.badStatus(0)
        }

        var total = contentRangeTotal(http.value(forHTTPHeaderField: "Content-Range"))
        if http.statusCode == 416 {
            if let total, total == offset { return Transfer(received: offset, total: total) }
            throw DownloadError.incomplete
        }
        guard (200..<300).contains(http.statusCode) else {
            throw DownloadError.badStatus(http.statusCode)
        }

        // A server that ignored Range sends the whole file; start over instead of appending.
        if http.statusCode == 200, offset > 0 {
            try? FileManager.default.removeItem(at: partURL)
            offset = 0
        }
        if total == nil {
            let expected = http.expectedContentLength
            if expected > 0 { total = offset + expected }
        }

        if !FileManager.default.fileExists(atPath: partURL.path) {
            FileManager.default.createFile(atPath: partURL.path, contents: nil)
        }
        let handle = try FileHandle(forWritingTo: partURL)
        var received = offset
        var buffer = [UInt8]()
        buffer.reserveCapacity(64 * 1024)
        var lastReported = offset

        func flush() throws {
            guard !buffer.isEmpty else { return }
            try handle.write(contentsOf: Data(buffer))
            received += Int64(buffer.count)
            buffer.removeAll(keepingCapacity: true)
        }

        defer { try? handle.close() }
        try handle.seekToEnd()

        for try await byte in bytes {
            buffer.append(byte)
            if buffer.count >= 64 * 1024 {
                try flush()
                if received - lastReported >= 256 * 1024 {
                    progress(received, total)
                    lastReported = received
                }
            }
        }
        try flush()
        progress(received, total)
        return Transfer(received: received, total: total)
    }

    private nonisolated static func contentRangeTotal(_ value: String?) -> Int64? {
        DownloadRanges.shared.parseContentRange(value: value)?.totalBytes?.int64Value
    }
}

private func fileSize(_ url: URL) -> Int64 {
    let attributes = try? FileManager.default.attributesOfItem(atPath: url.path)
    return (attributes?[.size] as? NSNumber)?.int64Value ?? 0
}

private func existingFinishedFile(_ clip: Clip) -> URL? {
    let url = LibraryFiles.finalURL(for: clip)
    return FileManager.default.fileExists(atPath: url.path) ? url : nil
}
