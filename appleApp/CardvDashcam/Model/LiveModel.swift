import AVFoundation
import Combine
import Foundation
import Shared
import os

enum LivePhase: Equatable {
    case idle
    case connecting
    case live
    case stopped
    case failed
}

private let liveLogger = Logger(subsystem: "com.cardv.dashcam", category: "live")

/// One live view session: owns the shared RTSP client and feeds the platform decoder.
///
/// The shared client reports on a Kotlin background thread; state changes are marshalled to the
/// main actor, while video/audio go straight to the decoder's serial queue.
@MainActor
final class LiveModel: ObservableObject {
    @Published private(set) var phase: LivePhase = .idle
    @Published private(set) var message: String?
    @Published private(set) var stats: LiveStats?
    @Published private(set) var firstFrameMillis: Int64?
    @Published private(set) var isOverride = false

    let decoder: AVFoundationLiveDecoder

    private var stream: AppleLiveStream?
    private var bridge: Bridge?
    private var url = ""
    private var startedAt = Date()
    private var lastStatsLog = Date.distantPast
    /// Identifies the current bridge, so events from a stopped session cannot overwrite a new one.
    private var bridgeID = UUID()

    init() {
        let decoder = AVFoundationLiveDecoder()
        self.decoder = decoder
        decoder.onFirstVideoFrame = { [weak self] in
            Task { @MainActor [weak self] in
                self?.noteFirstFrame()
            }
        }
        decoder.onFailure = { [weak self] text in
            Task { @MainActor [weak self] in
                self?.fail(text)
            }
        }
    }

    var isLive: Bool { phase == .live }

    func start(url: String, isOverride: Bool) {
        guard !url.isEmpty else {
            phase = .failed
            message = "Reconnect to the dashcam to start the live stream."
            return
        }
        if self.url == url, phase == .connecting || phase == .live { return }

        stop(showStopped: false)
        self.url = url
        self.isOverride = isOverride
        phase = .connecting
        message = nil
        stats = nil
        firstFrameMillis = nil
        startedAt = Date()

        #if DEBUG
        liveLogger.info("start \(url, privacy: .public) override=\(isOverride, privacy: .public)")
        #endif
        #if os(iOS)
        // Play the camera's audio through the silent switch, like a video player.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
        #endif
        decoder.start()
        let bridge = Bridge(decoder: decoder, model: self)
        bridgeID = bridge.id
        let stream = AppleLive.shared.stream(url: url, sink: bridge)
        self.bridge = bridge
        self.stream = stream
        stream.start()
    }

    func stop(showStopped: Bool = true) {
        #if DEBUG
        liveLogger.info("stop requested")
        #endif
        stream?.stop()
        stream = nil
        bridge = nil
        bridgeID = UUID()
        decoder.stop()
        #if os(iOS)
        try? AVAudioSession.sharedInstance().setActive(
            false,
            options: .notifyOthersOnDeactivation
        )
        #endif
        stats = nil
        if showStopped, phase != .failed {
            phase = .stopped
        } else if !showStopped {
            phase = .idle
        }
    }

    /// A retry after an error keeps the same URL and clears the failure.
    func retry() {
        let target = url
        let override = isOverride
        guard !target.isEmpty else { return }
        phase = .stopped
        start(url: target, isOverride: override)
    }

    fileprivate func apply(state: LiveState, message text: String?, from id: UUID) {
        guard id == bridgeID else { return }
        #if DEBUG
        liveLogger.info("state \(String(describing: state), privacy: .public) \(text ?? "", privacy: .public)")
        #endif
        if state == LiveState.connecting {
            if phase != .failed { phase = .connecting }
        } else if state == LiveState.playing {
            phase = .live
            message = nil
        } else if state == LiveState.failed {
            phase = .failed
            message = text
        } else if state == LiveState.stopped {
            if phase != .failed { phase = .stopped }
        }
    }

    fileprivate func noteFirstFrame() {
        guard firstFrameMillis == nil else { return }
        firstFrameMillis = Int64(Date().timeIntervalSince(startedAt) * 1_000)
        #if DEBUG
        liveLogger.info("first frame after \(self.firstFrameMillis ?? 0) ms")
        #endif
        if phase == .connecting { phase = .live }
    }

    fileprivate func update(stats: LiveStats, from id: UUID) {
        guard id == bridgeID else { return }
        self.stats = stats
        if firstFrameMillis == nil, let fromClient = stats.firstFrameMillis?.int64Value {
            firstFrameMillis = fromClient
        }
        #if DEBUG
        if Date().timeIntervalSince(lastStatsLog) >= 3 {
            lastStatsLog = Date()
            liveLogger.info("stats video=\(stats.videoFrames) audio=\(stats.audioFrames) fps=\(Int(stats.videoFps.rounded())) kbit=\(Int(stats.videoKbitPerSecond.rounded())) audioKbit=\(Int(stats.audioKbitPerSecond.rounded())) lost=\(stats.lostRtpPackets)")
        }
        #endif
    }

    private func fail(_ text: String) {
        phase = .failed
        message = text
    }
}

/// Kotlin callbacks arrive on a reader thread. Video and audio go to the decoder directly;
/// state and counters hop to the main actor.
private final class Bridge: NSObject, AppleLiveSink {
    let id = UUID()
    private let decoder: AVFoundationLiveDecoder
    private weak var model: LiveModel?

    init(decoder: AVFoundationLiveDecoder, model: LiveModel) {
        self.decoder = decoder
        self.model = model
    }

    func onState(state: LiveState, message: String?) {
        let id = id
        Task { @MainActor [weak model] in
            model?.apply(state: state, message: message, from: id)
        }
    }

    func onVideo(sample: AppleLiveVideoSample) {
        decoder.consume(video: sample)
    }

    func onAudio(sample: AppleLiveAudioSample) {
        decoder.consume(audio: sample)
    }

    func onStats(stats: LiveStats) {
        let id = id
        Task { @MainActor [weak model] in
            model?.update(stats: stats, from: id)
        }
    }
}
