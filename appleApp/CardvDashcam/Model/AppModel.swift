import Combine
import Foundation
import Shared
import SwiftUI

enum ConnectionPhase: Equatable {
    case idle
    case connecting
    case connected
    case failed
}

/// Failures the app raises itself. Kotlin exceptions arrive as `NSError`, never as Swift `Error`.
enum CameraFailure: LocalizedError {
    case hung

    var errorDescription: String? {
        "The dashcam stopped responding. Restart the car, then try again."
    }
}

/// One camera operation, so only the pressed control shows a spinner.
enum BusyAction: Equatable {
    case microphone
    case snapshot
    case lock
    case record
    case clock
    case setting
}

struct Feedback: Identifiable, Equatable {
    let id = UUID()
    let message: String
    let isError: Bool
}

struct ClipGroup: Identifiable {
    let id: String
    let date: Kotlinx_datetimeLocalDate?
    let clips: [Clip]
}

/// What the player or photo viewer is showing.
struct OpenClip: Identifiable {
    enum Kind {
        case video
        case photo
    }

    let clip: Clip
    let url: URL
    let kind: Kind
    var id: String { clip.path }
}

/// One connected camera session. Closing it releases the shared HTTP client.
struct CameraSession {
    let baseURL: String
    let client: CameraClient
}

@MainActor
final class AppModel: ObservableObject {
    @Published private(set) var phase: ConnectionPhase = .idle
    @Published private(set) var baseURL: String = CameraPreferences.effectiveBaseURL
    @Published private(set) var errorMessage: String?
    @Published private(set) var hung = false
    @Published private(set) var status: CameraStatus?
    @Published private(set) var clockMessage: String?
    @Published private(set) var busy: BusyAction?
    @Published var feedback: Feedback?

    @Published var folder: Folder = Folder.dcim
    @Published var sort: ClipSort = ClipSort.newestFirst
    @Published var groupByDay = true
    @Published private(set) var clipsByFolder: [String: [Clip]] = [:]
    @Published private(set) var loadingFolders: Set<String> = []
    @Published private(set) var folderErrors: [String: String] = [:]

    @Published private(set) var thumbnails: [String: PlatformImage] = [:]
    @Published var openClip: OpenClip?

    let downloads = DownloadStore()

    private var session: CameraSession?
    private var connectTask: Task<Void, Never>?
    private var thumbnailsInFlight: Set<String> = []
    private var downloadObserver: AnyCancellable?
    private var statusRefreshInFlight = false

    init() {
        downloadObserver = downloads.objectWillChange.sink { [weak self] _ in
            self?.objectWillChange.send()
        }
        #if DEBUG
        // A debug camera address in the environment also skips the tap, for headless runs.
        if CameraPreferences.hasEnvironmentBaseURL {
            Task { @MainActor [weak self] in
                self?.connect()
            }
        }
        #endif
    }

    // MARK: - Derived state

    var isConnected: Bool { phase == .connected }

    var isRecording: Bool { status?.recording ?? false }

    var microphoneOn: Bool {
        status?.soundRecord?.caseInsensitiveCompare("ON") == .orderedSame
    }

    var currentClips: [Clip] { clipsByFolder[folder.property] ?? [] }

    var groups: [ClipGroup] {
        ClipLibrary.shared.byDay(clips: currentClips, sort: sort).map { group in
            ClipGroup(
                id: group.date.map { "\($0.year)-\($0.monthNumber)-\($0.dayOfMonth)" } ?? "unknown",
                date: group.date,
                clips: group.clips
            )
        }
    }

    var isLoadingFolder: Bool { loadingFolders.contains(folder.property) }

    var folderError: String? { folderErrors[folder.property] }

    var currentRecordingName: String? {
        guard let raw = status?.recFileName, !raw.isEmpty else { return nil }
        let name = raw.replacingOccurrences(of: "\\", with: "/").split(separator: "/").last
        return name.map(String.init)
    }

    func isCurrentRecording(_ clip: Clip) -> Bool {
        clip.name == currentRecordingName
    }

    func count(in folder: Folder) -> Int? {
        clipsByFolder[folder.property]?.count
    }

    func thumbnail(for clip: Clip) -> PlatformImage? {
        thumbnails[clip.path]
    }

    func downloadState(for clip: Clip) -> DownloadStore.State? {
        downloads.states[clip.path]
    }

    // MARK: - Connection

    func connect() {
        if phase == .connecting { return }
        connectTask?.cancel()
        closeSession()

        let target = CameraPreferences.effectiveBaseURL
        baseURL = target
        phase = .connecting
        errorMessage = nil
        hung = false
        status = nil
        clockMessage = nil
        feedback = nil
        clipsByFolder = [:]
        folderErrors = [:]
        loadingFolders = []
        thumbnails = [:]
        thumbnailsInFlight = []
        openClip = nil

        connectTask = Task { [weak self] in
            guard let self else { return }
            do {
                let client = AppleCamera.shared.create(
                    baseUrl: target,
                    allowExperimental: CameraPreferences.experimentalEnabled
                )
                do {
                    _ = try await client.health()
                    let cameraStatus = try await client.status()
                    let clock = try await AppleCamera.shared.clock(client: client)
                        .sync(cameraTime: cameraStatus.cameraTime)
                    if client.isHung { throw CameraFailure.hung }
                    if let result = clock.result, AppleWriteResult.shared.outcome(result: result).hung {
                        throw CameraFailure.hung
                    }
                    try Task.checkCancellation()

                    self.session = CameraSession(baseURL: target, client: client)
                    self.apply(cameraStatus)
                    self.clockMessage = Self.clockMessage(clock)
                    self.phase = .connected
                } catch {
                    client.close()
                    throw error
                }
            } catch is CancellationError {
                return
            } catch {
                if self.phase == .connected { return }
                self.session = nil
                self.hung = Self.isHung(error)
                self.errorMessage = Self.friendlyMessage(error)
                self.phase = .failed
                return
            }

            await self.loadFolder(Folder.dcim, force: true)
#if DEBUG
            // Launch arguments make headless smoke tests possible:
            //   xcrun simctl launch booted com.cardv.dashcam -openFirstClip
            let arguments = ProcessInfo.processInfo.arguments
            if arguments.contains("-openFirstClip"),
               let clip = self.clipsByFolder[Folder.dcim.property]?.last(where: {
                   $0.isVideo && !self.isCurrentRecording($0)
               }) {
                self.open(clip)
            } else if arguments.contains("-openFirstPhoto") {
                await self.loadFolder(Folder.photo, force: true)
                if let photo = self.clipsByFolder[Folder.photo.property]?.first {
                    self.open(photo)
                }
            }
#endif
            Task { [weak self] in
                guard let self else { return }
                for folder in [Folder.sos, Folder.lock, Folder.photo] {
                    await self.loadFolder(folder)
                }
            }
        }
    }

    func disconnect() {
        connectTask?.cancel()
        closeSession()
        phase = .idle
        errorMessage = nil
        hung = false
        status = nil
        clockMessage = nil
        feedback = nil
        clipsByFolder = [:]
        folderErrors = [:]
        loadingFolders = []
        thumbnails = [:]
        thumbnailsInFlight = []
        openClip = nil
    }

    /// Debug-only override of the camera address (release builds ignore it).
    func useBaseURL(_ raw: String?) {
        CameraPreferences.baseURLOverride = raw
        connect()
    }

    // MARK: - Library

    func select(folder newFolder: Folder) {
        folder = newFolder
        guard clipsByFolder[newFolder.property] == nil || folderErrors[newFolder.property] != nil else {
            return
        }
        Task { await loadFolder(newFolder) }
    }

    func setSort(_ newSort: ClipSort) {
        sort = newSort
    }

    func toggleGrouping() {
        groupByDay.toggle()
    }

    func refreshLibrary() {
        Task {
            await refreshStatus()
            await loadFolder(folder, force: true)
        }
    }

    func loadFolder(_ folder: Folder, force: Bool = false) async {
        guard let active = session else { return }
        let key = folder.property
        if !force, clipsByFolder[key] != nil, folderErrors[key] == nil { return }
        if loadingFolders.contains(key) { return }

        loadingFolders.insert(key)
        folderErrors[key] = nil
        do {
            let clips = try await active.client.list(folder: folder)
            guard self.session?.client === active.client else { return }
            clipsByFolder[key] = clips
        } catch is CancellationError {
            loadingFolders.remove(key)
            return
        } catch {
            guard self.session?.client === active.client else { return }
            folderErrors[key] = Self.friendlyMessage(error)
            if Self.isHung(error) { markHung() }
        }
        loadingFolders.remove(key)
    }

    func ensureThumbnail(_ clip: Clip) {
        guard let active = session else { return }
        guard thumbnails[clip.path] == nil, !thumbnailsInFlight.contains(clip.path) else { return }
        thumbnailsInFlight.insert(clip.path)
        let path = clip.path

        Task { [weak self] in
            defer { self?.thumbnailsInFlight.remove(path) }
            let data: Data?
            if clip.isVideo {
                data = (try? await active.client.thumbnail(clip: clip)).map(Self.data(from:))
            } else {
                data = await Self.fetchData(active.client.clipUrl(clip: clip))
            }
            guard let data, let image = PlatformImage.from(data) else { return }
            guard let self, self.session?.client === active.client else { return }
            self.thumbnails[path] = image
        }
    }

    func open(_ clip: Clip) {
        guard let active = session else { return }
        guard let url = URL(string: active.client.clipUrl(clip: clip)) else { return }
        openClip = OpenClip(clip: clip, url: url, kind: clip.isVideo ? .video : .photo)
    }

    func closeOpenClip() {
        openClip = nil
    }

    func download(_ clip: Clip) {
        guard let active = session else { return }
        guard !isCurrentRecording(clip) else {
            feedback = Feedback(
                message: "Stop recording before downloading the clip that is being written.",
                isError: true
            )
            return
        }
        downloads.enqueue(clip, baseURL: active.baseURL)
    }

    /// Queue every SOS and LOCK clip, offered before formatting or resetting the camera.
    func downloadArchive() {
        guard let active = session else { return }
        Task { [weak self] in
            guard let self else { return }
            var queued = 0
            for folder in [Folder.sos, Folder.lock] {
                var clips = self.clipsByFolder[folder.property] ?? []
                if clips.isEmpty {
                    clips = (try? await active.client.list(folder: folder)) ?? []
                    self.clipsByFolder[folder.property] = clips
                }
                for clip in clips where self.downloads.states[clip.path]?.phase != .finished {
                    self.downloads.enqueue(clip, baseURL: active.baseURL)
                    queued += 1
                }
            }
            self.feedback = Feedback(
                message: queued == 0
                    ? "No SOS or LOCK clips to download."
                    : "Queued \(queued) SOS/LOCK \(queued == 1 ? "clip" : "clips") for download.",
                isError: false
            )
        }
    }

    // MARK: - Status and controls

    func refreshStatus() async {
        guard let active = session, !statusRefreshInFlight else { return }
        statusRefreshInFlight = true
        defer { statusRefreshInFlight = false }
        if let latest = try? await active.client.status() {
            apply(latest)
        }
    }

    func toggleMicrophone() {
        runControl(.microphone) { active in
            let on = self.microphoneOn
            let result = try await AppleCamera.shared.controls(client: active.client)
                .microphone(on: !on)
            let outcome = AppleWriteResult.shared.outcome(result: result)
            await self.refreshStatus()
            if outcome.applied {
                return Feedback(message: on ? "Microphone muted." : "Microphone on.", isError: false)
            }
            return Feedback(message: outcome.failure ?? "The camera did not change the microphone.", isError: true)
        }
    }

    func captureSnapshot() {
        runControl(.snapshot) { active in
            let result = try await AppleCamera.shared.controls(client: active.client).snapshot()
            let outcome = AppleWriteResult.shared.outcome(result: result)
            if outcome.applied {
                await self.loadFolder(Folder.photo, force: true)
                return Feedback(message: "Snapshot saved to the camera's Photos folder.", isError: false)
            }
            return Feedback(message: outcome.failure ?? "The snapshot was not taken.", isError: true)
        }
    }

    func toggleRecording() {
        runControl(.record) { active in
            let result = try await AppleCamera.shared.controls(client: active.client).toggleRecording()
            let outcome = AppleWriteResult.shared.outcome(result: result)
            let latest = try? await active.client.status()
            if let latest { self.apply(latest) }
            if outcome.applied {
                await self.loadFolder(Folder.dcim, force: true)
                if let latest {
                    return Feedback(
                        message: latest.recording ? "Recording started." : "Recording stopped.",
                        isError: false
                    )
                }
                return Feedback(message: "Recording toggled.", isError: false)
            }
            return Feedback(message: outcome.failure ?? "The camera did not change recording.", isError: true)
        }
    }

    func lockCurrentClip() {
        runControl(.lock) { active in
            let known = Set((self.clipsByFolder[Folder.lock.property] ?? []).map(\.path))
            let outcome = try await AppleCamera.shared.lockCurrentClip(
                controls: AppleCamera.shared.controls(client: active.client),
                knownLockPaths: known
            )
            if outcome.applied {
                if !outcome.clips.isEmpty {
                    self.clipsByFolder[Folder.lock.property] = outcome.clips
                }
                await self.loadFolder(Folder.dcim, force: true)
                if !outcome.found {
                    await self.loadFolder(Folder.lock, force: true)
                }
                if let name = outcome.newPaths.first.map({ String($0.split(separator: "/").last ?? "") }) {
                    return Feedback(message: "Locked \(name).", isError: false)
                }
                return Feedback(
                    message: "Lock sent. LOCK has not listed the clip yet; refresh in a moment.",
                    isError: false
                )
            }
            let write = AppleWriteResult.shared.outcome(result: outcome.write)
            return Feedback(message: write.failure ?? "The clip was not locked.", isError: true)
        }
    }

    func syncClockNow() {
        runControl(.clock) { active in
            let outcome = try await AppleCamera.shared.clock(client: active.client)
                .sync(cameraTime: self.status?.cameraTime)
            await self.refreshStatus()
            self.clockMessage = Self.clockMessage(outcome)
            if outcome.applied {
                return Feedback(message: Self.clockMessage(outcome), isError: false)
            }
            return Feedback(message: Self.clockMessage(outcome), isError: true)
        }
    }

    func setSetting(_ setting: Setting, value: String) {
        guard busy == nil, let active = session else { return }
        let level = SettingsCatalog.shared.levelOf(name: setting.name)
        if level == SafetyLevel.forbidden {
            feedback = Feedback(message: "This setting is blocked for safety.", isError: true)
            return
        }
        if level == SafetyLevel.experimental && !CameraPreferences.experimentalEnabled {
            feedback = Feedback(message: "Enable experimental settings first.", isError: true)
            return
        }

        busy = .setting
        feedback = nil
        Task { [weak self] in
            guard let self else { return }
            var message: String
            var isError = false
            do {
                if level == SafetyLevel.needsStandby {
                    let outcome = try await AppleCamera.shared.settings(client: active.client)
                        .setNeedsStandby(setting: setting, value: value, resumeRecording: true)
                    if let latest = outcome.statusAfter { self.apply(latest) }
                    let write = AppleWriteResult.shared.outcome(result: outcome.result)
                    message = write.applied ? "\(setting.name) set to \(value)." : (write.failure ?? "Not applied.")
                    isError = !write.applied || outcome.verified == false || outcome.recordingLeftStopped
                    if outcome.verified == false {
                        message += " The SD card did not report READY; check it before recording again."
                    }
                    if outcome.recordingLeftStopped {
                        message += " Recording was not restarted — start it again from Camera."
                    }
                    if write.hung { self.markHung() }
                } else {
                    let result = try await active.client.setSetting(setting: setting, value: value)
                    let write = AppleWriteResult.shared.outcome(result: result)
                    message = write.applied ? "\(setting.name) set to \(value)." : (write.failure ?? "Not applied.")
                    isError = !write.applied
                    if write.hung { self.markHung() }
                    await self.refreshStatus()
                    if write.applied, self.isDestructive(setting) {
                        await self.reloadEverything()
                    }
                }
            } catch {
                message = Self.friendlyMessage(error)
                isError = true
                if Self.isHung(error) { self.markHung() }
            }
            self.busy = nil
            self.feedback = Feedback(message: message, isError: isError)
        }
    }

    func setExperimentalEnabled(_ enabled: Bool) {
        CameraPreferences.experimentalEnabled = enabled
        session?.client.setExperimentalAllowed(allowed: enabled)
        objectWillChange.send()
    }

    var experimentalEnabled: Bool { CameraPreferences.experimentalEnabled }

    func clearFeedback() {
        feedback = nil
    }

    // MARK: - Internals

    private func runControl(
        _ action: BusyAction,
        _ block: @escaping (CameraSession) async throws -> Feedback
    ) {
        guard busy == nil, let active = session else {
            if session == nil {
                feedback = Feedback(message: "Not connected to the camera.", isError: true)
            }
            return
        }
        busy = action
        feedback = nil
        Task { [weak self] in
            guard let self else { return }
            var result: Feedback
            do {
                result = try await block(active)
            } catch {
                if Self.isHung(error) { self.markHung() }
                result = Feedback(message: Self.friendlyMessage(error), isError: true)
            }
            self.busy = nil
            if active.client.isHung { self.markHung() }
            if self.session?.client === active.client {
                self.feedback = result
            }
        }
    }

    private func apply(_ latest: CameraStatus) {
        status = latest
    }

    private func markHung() {
        hung = true
    }

    private func isDestructive(_ setting: Setting) -> Bool {
        setting.name == SettingsCatalog.shared.SD0.name
            || setting.name == SettingsCatalog.shared.FACTORY_RESET.name
    }

    /// After an SD format or factory reset the listings and thumbnails are stale.
    private func reloadEverything() async {
        clipsByFolder = [:]
        thumbnails = [:]
        for folder in Folder.entries {
            await loadFolder(folder, force: true)
        }
        await refreshStatus()
    }

    private func closeSession() {
        session?.client.close()
        session = nil
    }

    private static func data(from bytes: KotlinByteArray) -> Data {
        var data = Data(capacity: Int(bytes.size))
        for index in 0..<Int(bytes.size) {
            data.append(UInt8(bitPattern: bytes.get(index: Int32(index))))
        }
        return data
    }

    private static func fetchData(_ urlString: String) async -> Data? {
        guard let url = URL(string: urlString) else { return nil }
        var request = URLRequest(url: url)
        request.timeoutInterval = 20
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              let http = response as? HTTPURLResponse,
              (200..<300).contains(http.statusCode) else { return nil }
        return data
    }

    private static func clockMessage(_ outcome: ClockSyncOutcome) -> String {
        if outcome.applied {
            guard let drift = outcome.driftSeconds?.int64Value, drift != 0 else {
                return "Camera clock synced."
            }
            let direction = drift > 0 ? "behind" : "ahead"
            return "Camera clock synced (was \(Format.humanDuration(abs(drift))) \(direction))."
        }
        let failure = outcome.result.flatMap {
            AppleWriteResult.shared.outcomeOrNull(result: $0)?.failure
        }
        return "Camera clock not updated: \(failure ?? "not sent")."
    }

    private static func isHung(_ error: Error) -> Bool {
        if error is CameraFailure { return true }
        if let kotlin = (error as NSError).kotlinException {
            return kotlin is CameraHungException
        }
        return false
    }

    static func friendlyMessage(_ error: Error) -> String {
        if let failure = error as? CameraFailure {
            return failure.errorDescription ?? "Something went wrong."
        }
        let ns = error as NSError
        if let kotlin = ns.kotlinException {
            if kotlin is CameraHungException {
                return "The dashcam stopped responding. Restart the car, then try again."
            }
            if kotlin is CameraException {
                return "The camera answered in an unexpected way. Try reconnecting."
            }
            if let message = (kotlin as? KotlinThrowable)?.message, !message.isEmpty {
                return message
            }
        }
        switch ns.code {
        case NSURLErrorCannotConnectToHost,
             NSURLErrorCannotFindHost,
             NSURLErrorTimedOut,
             NSURLErrorNetworkConnectionLost,
             NSURLErrorNotConnectedToInternet,
             NSURLErrorSecureConnectionFailed:
            return "Can't reach the dashcam. Check that it is on and your phone is on its Wi-Fi."
        case NSURLErrorCancelled:
            return "The request was cancelled."
        default:
            return ns.localizedDescription
        }
    }
}

// MARK: - Folder presentation

extension Folder {
    var title: String {
        switch property {
        case "DCIM": return "Loop"
        case "SOS": return "SOS"
        case "LOCK": return "Lock"
        case "Photo": return "Photos"
        default: return property
        }
    }

    var symbol: String {
        switch property {
        case "DCIM": return "video.fill"
        case "SOS": return "exclamationmark.triangle.fill"
        case "LOCK": return "lock.fill"
        case "Photo": return "camera.fill"
        default: return "folder.fill"
        }
    }
}

extension ClipSort {
    var title: String {
        if self == ClipSort.newestFirst { return "Newest" }
        if self == ClipSort.oldestFirst { return "Oldest" }
        return "Name"
    }
}
