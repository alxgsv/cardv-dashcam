import Foundation
import Shared
import SwiftUI

#if os(iOS)
import Photos
import UIKit
typealias PlatformImage = UIImage
#else
import AppKit
typealias PlatformImage = NSImage
#endif

extension PlatformImage {
    static func from(_ data: Data) -> PlatformImage? {
        #if os(iOS)
        return UIImage(data: data)
        #else
        return NSImage(data: data)
        #endif
    }

    var swiftUI: Image {
        #if os(iOS)
        return Image(uiImage: self)
        #else
        return Image(nsImage: self)
        #endif
    }
}

/// Debug camera settings. Release builds always talk to the real camera.
enum CameraPreferences {
    private static let baseKey = "debug.baseURL"
    private static let liveKey = "debug.liveURL"
    private static let experimentalKey = "experimental"

    static let cameraBaseURL = "http://192.168.42.1"

    static var baseURLOverride: String? {
        get {
            #if DEBUG
            return normalized(UserDefaults.standard.string(forKey: baseKey))
            #else
            return nil
            #endif
        }
        set {
            #if DEBUG
            UserDefaults.standard.set(newValue, forKey: baseKey)
            #endif
        }
    }

    static var liveURLOverride: String? {
        get {
            #if DEBUG
            if let fromEnvironment = normalized(ProcessInfo.processInfo.environment["CARDV_LIVE_URL"]) {
                return fromEnvironment
            }
            return normalized(UserDefaults.standard.string(forKey: liveKey))
            #else
            return nil
            #endif
        }
        set {
            #if DEBUG
            UserDefaults.standard.set(newValue, forKey: liveKey)
            #endif
        }
    }

    static var effectiveBaseURL: String {
        #if DEBUG
        if let fromEnvironment = normalized(ProcessInfo.processInfo.environment["CARDV_BASE_URL"]) {
            return fromEnvironment
        }
        return baseURLOverride ?? cameraBaseURL
        #else
        return cameraBaseURL
        #endif
    }

    static var experimentalEnabled: Bool {
        get { UserDefaults.standard.bool(forKey: experimentalKey) }
        set { UserDefaults.standard.set(newValue, forKey: experimentalKey) }
    }

    static var hasEnvironmentBaseURL: Bool {
        #if DEBUG
        return normalized(ProcessInfo.processInfo.environment["CARDV_BASE_URL"]) != nil
        #else
        return false
        #endif
    }

    /// Trim, add a scheme, drop a trailing slash; `nil` for blank or host-less input.
    static func normalized(_ raw: String?) -> String? {
        var text = raw?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !text.isEmpty else { return nil }
        if !text.lowercased().hasPrefix("http://"), !text.lowercased().hasPrefix("https://") {
            text = "http://" + text
        }
        guard let components = URLComponents(string: text), components.host?.isEmpty == false else {
            return nil
        }
        while text.hasSuffix("/") { text.removeLast() }
        return text
    }
}

/// Saving a finished download where the user can find it.
enum MediaExporter {
    enum ExportError: LocalizedError {
        case photoPermission
        case notSaved

        var errorDescription: String? {
            switch self {
            case .photoPermission: return "Allow access to Photos in System Settings to save the clip."
            case .notSaved: return "The file could not be saved."
            }
        }
    }

    @MainActor
    static func save(_ fileURL: URL, isVideo: Bool) async throws {
        #if os(iOS)
        try await saveToPhotos(fileURL, isVideo: isVideo)
        #else
        try saveWithPanel(fileURL)
        #endif
    }

    #if os(iOS)
    private static func saveToPhotos(_ fileURL: URL, isVideo: Bool) async throws {
        let status = await withCheckedContinuation { continuation in
            PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
                continuation.resume(returning: status)
            }
        }
        guard status == .authorized || status == .limited else {
            throw ExportError.photoPermission
        }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            PHPhotoLibrary.shared().performChanges {
                if isVideo {
                    PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: fileURL)
                } else {
                    PHAssetChangeRequest.creationRequestForAssetFromImage(atFileURL: fileURL)
                }
            } completionHandler: { success, error in
                if success {
                    continuation.resume()
                } else {
                    continuation.resume(throwing: error ?? ExportError.notSaved)
                }
            }
        }
    }
    #else
    private static func saveWithPanel(_ fileURL: URL) throws {
        let panel = NSSavePanel()
        panel.nameFieldStringValue = fileURL.lastPathComponent
        panel.canCreateDirectories = true
        panel.isExtensionHidden = false
        guard panel.runModal() == .OK, let destination = panel.url else { return }
        if FileManager.default.fileExists(atPath: destination.path) {
            try FileManager.default.removeItem(at: destination)
        }
        try FileManager.default.copyItem(at: fileURL, to: destination)
    }

    static func revealInFinder(_ fileURL: URL) {
        NSWorkspace.shared.activateFileViewerSelecting([fileURL])
    }
    #endif
}

/// The app's download folder inside the container (Files app on iOS, Finder via the app on macOS).
enum LibraryFiles {
    static var directory: URL {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let folder = documents.appendingPathComponent("CarDV", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }

    /// The camera path is flattened so `/SD/MOVIE` and `/SD/LOCK` can never collide on disk.
    static func fileName(for clip: Clip) -> String {
        var name = clip.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            .replacingOccurrences(of: "/", with: "_")
        name = name.replacingOccurrences(
            of: "[^A-Za-z0-9._-]",
            with: "_",
            options: .regularExpression
        )
        let trimmed = String(name.prefix(120))
        return trimmed.isEmpty ? "clip" : trimmed
    }

    static func finalURL(for clip: Clip) -> URL {
        directory.appendingPathComponent(fileName(for: clip))
    }

    static func partURL(for clip: Clip) -> URL {
        directory.appendingPathComponent(fileName(for: clip) + ".part")
    }
}

enum Format {
    private static let months = [
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    ]

    static func size(_ bytes: Int64) -> String {
        guard bytes > 0 else { return "—" }
        let units = ["B", "KB", "MB", "GB", "TB"]
        var value = Double(bytes)
        var unit = 0
        while value >= 1024, unit < units.count - 1 {
            value /= 1024
            unit += 1
        }
        if unit == 0 { return "\(bytes) B" }
        return String(format: "%.1f %@", value, units[unit])
    }

    static func duration(_ seconds: Double?) -> String? {
        guard let seconds, seconds > 0 else { return nil }
        let total = Int(seconds)
        return String(format: "%d:%02d", total / 60, total % 60)
    }

    static func two(_ number: Int32) -> String {
        number < 10 ? "0\(number)" : "\(number)"
    }

    static func time(_ value: Kotlinx_datetimeLocalDateTime?) -> String {
        guard let value else { return "—" }
        return "\(two(value.hour)):\(two(value.minute))"
    }

    static func timestamp(_ clip: Clip) -> String {
        guard let stamp = clip.timestamp else { return clip.name }
        let month = months[max(0, min(11, Int(stamp.monthNumber) - 1))]
        return "\(two(stamp.hour)):\(two(stamp.minute)) · \(stamp.dayOfMonth) \(month) \(stamp.year)"
    }

    static func clock(_ value: Kotlinx_datetimeLocalDateTime?) -> String {
        guard let value else { return "—" }
        let month = months[max(0, min(11, Int(value.monthNumber) - 1))]
        return "\(value.dayOfMonth) \(month) \(value.year), \(two(value.hour)):\(two(value.minute)):\(two(value.second))"
    }

    static func dayLabel(_ date: Kotlinx_datetimeLocalDate?) -> String {
        guard let date else { return "UNKNOWN DATE" }
        let month = months[max(0, min(11, Int(date.monthNumber) - 1))]
        let label = "\(date.dayOfMonth) \(month) \(date.year)"
        let calendar = Calendar.current
        let now = Date()
        let today = calendar.dateComponents([.year, .month, .day], from: now)
        let then = DateComponents(
            year: Int(date.year),
            month: Int(date.monthNumber),
            day: Int(date.dayOfMonth)
        )
        if let todayDate = calendar.date(from: today), let thenDate = calendar.date(from: then) {
            let days = calendar.dateComponents([.day], from: thenDate, to: todayDate).day ?? 99
            if days == 0 { return "TODAY" }
            if days == 1 { return "YESTERDAY" }
        }
        return label.uppercased()
    }

    static func megabytes(_ value: KotlinLong?) -> String {
        guard let value else { return "—" }
        return size(value.int64Value * 1024 * 1024)
    }

    static func humanDuration(_ seconds: Int64) -> String {
        if seconds >= 86_400 { return "\(seconds / 86_400) d" }
        if seconds >= 3_600 { return "\(seconds / 3_600) h" }
        if seconds >= 60 { return "\(seconds / 60) min" }
        return "\(seconds) s"
    }
}
