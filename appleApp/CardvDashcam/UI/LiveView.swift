import Shared
import SwiftUI

/// Live view: the same screen in the iOS Live tab and the macOS Live sidebar item.
///
/// The camera stream is separate from HTTP playback: RTSP `liveRTSP/av2` (H.264 720p + AAC),
/// RTP over TCP. Live view does not stop recording.
struct LiveView: View {
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.scenePhase) private var scenePhase
    @EnvironmentObject private var model: AppModel

    @StateObject private var live = LiveModel()
    @State private var manuallyStopped = false
    @State private var visible = false

    private var theme: CarDVTheme { CarDVTheme.of(colorScheme) }

    private var bottomInset: CGFloat {
        #if os(iOS)
        return 92
        #else
        return 28
        #endif
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                header
                stage
                controls
                if let stats = live.stats, live.isLive {
                    details(stats)
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 12)
            .padding(.bottom, bottomInset)
            .frame(maxWidth: 900, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .background(theme.background)
        .onAppear {
            visible = true
            if !manuallyStopped { start() }
        }
        .onDisappear {
            visible = false
            live.stop(showStopped: false)
            manuallyStopped = false
        }
        .task {
            #if DEBUG
            // Headless spike runs can stop the stream after a fixed time, like the Android harness.
            if let text = ProcessInfo.processInfo.environment["CARDV_LIVE_STOP_AFTER"],
               let seconds = Double(text), seconds > 0 {
                try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                if visible {
                    manuallyStopped = true
                    live.stop()
                }
            }
            #endif
        }
        .onChange(of: scenePhase) { phase in
            guard visible else { return }
            if phase == .background {
                live.stop(showStopped: false)
            } else if phase == .active, !manuallyStopped {
                start()
            }
        }
    }

    // MARK: - Pieces

    private var header: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                Text("LIVE VIEW")
                    .font(.system(size: 10, weight: .semibold))
                    .tracking(2)
                    .foregroundStyle(theme.secondaryText)
                Text(stateTitle)
                    .font(.system(size: 26, weight: .bold))
                    .tracking(-0.6)
                    .foregroundStyle(live.isLive ? theme.ember : theme.text)
            }
            Spacer()
            if live.isOverride {
                Text("TEST STREAM")
                    .font(.system(size: 9, weight: .bold))
                    .tracking(0.8)
                    .foregroundStyle(theme.secondaryText)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 6)
                    .background(Capsule().fill(theme.raised))
            }
            liveBadge
        }
    }

    private var stage: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .fill(Color.black)
            LiveVideoSurface(decoder: live.decoder)
                .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))

            switch live.phase {
            case .connecting:
                messageOverlay(
                    symbol: nil,
                    title: "Connecting to live view…",
                    body: "The camera can take a few seconds to start the stream."
                )
            case .failed:
                messageOverlay(
                    symbol: "exclamationmark.triangle.fill",
                    title: "Live view unavailable",
                    body: live.message ?? "The camera did not send video.",
                    action: "Try again"
                ) {
                    manuallyStopped = false
                    live.retry()
                }
            case .stopped, .idle:
                messageOverlay(
                    symbol: "video.fill",
                    title: "Live view stopped",
                    body: "The camera is no longer sending video. Start again when you are ready.",
                    action: "Start live view"
                ) {
                    manuallyStopped = false
                    start()
                }
            case .live:
                EmptyView()
            }
        }
        .aspectRatio(16.0 / 9.0, contentMode: .fit)
        .frame(maxWidth: .infinity)
        .overlay(
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .strokeBorder(theme.hairline, lineWidth: 1)
        )
    }

    private var controls: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                Circle()
                    .fill(live.isLive ? theme.ember : theme.secondaryText.opacity(0.4))
                    .frame(width: 9, height: 9)
                VStack(alignment: .leading, spacing: 2) {
                    Text(controlTitle)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(theme.text)
                    Text("Live view does not stop the camera from recording.")
                        .font(.system(size: 11))
                        .foregroundStyle(theme.secondaryText)
                }
                Spacer(minLength: 8)
                if live.phase != .idle {
                    Button {
                        if live.isLive || live.phase == .connecting {
                            manuallyStopped = true
                            live.stop()
                        } else {
                            manuallyStopped = false
                            start()
                        }
                    } label: {
                        HStack(spacing: 7) {
                            Image(systemName: buttonSymbol)
                                .font(.system(size: 13, weight: .bold))
                            Text(buttonTitle)
                                .font(.system(size: 13, weight: .semibold))
                        }
                        .foregroundStyle(theme.text)
                        .padding(.horizontal, 16)
                        .frame(height: 40)
                        .background(
                            Capsule().fill(
                                live.isLive || live.phase == .connecting
                                    ? theme.raised
                                    : theme.ember.opacity(0.16)
                            )
                        )
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .padding(16)
        .background(card)
    }

    private func details(_ stats: LiveStats) -> some View {
        VStack(alignment: .leading, spacing: 9) {
            Text("STREAM")
                .font(.system(size: 10, weight: .semibold))
                .tracking(1.6)
                .foregroundStyle(theme.secondaryText)

            detailRow("Address", model.liveURL ?? "—")
            detailRow(
                "Video",
                "\(Int(stats.videoFps.rounded())) fps · \(Int(stats.videoKbitPerSecond.rounded())) kbit/s"
            )
            if stats.audioFrames > 0 {
                detailRow(
                    "Audio",
                    "AAC · \(Int(stats.audioKbitPerSecond.rounded())) kbit/s"
                )
            } else {
                detailRow("Audio", "waiting for AAC")
            }
            if let first = live.firstFrameMillis ?? stats.firstFrameMillis?.int64Value {
                detailRow("First frame", "\(first) ms")
            }
            if stats.lostRtpPackets > 0 {
                detailRow("Lost RTP packets", "\(stats.lostRtpPackets)")
            }
        }
        .padding(18)
        .background(card)
    }

    private func messageOverlay(
        symbol: String?,
        title: String,
        body: String,
        action: String? = nil,
        onAction: (() -> Void)? = nil
    ) -> some View {
        VStack(spacing: 10) {
            if let symbol {
                Image(systemName: symbol)
                    .font(.system(size: 30, weight: .semibold))
                    .foregroundStyle(live.phase == .failed ? theme.error : theme.ember)
            } else {
                ProgressView()
                    .controlSize(.regular)
                    .tint(theme.ember)
            }
            Text(title)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(.white)
            Text(body)
                .font(.system(size: 12))
                .foregroundStyle(.white.opacity(0.65))
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: 380)
            if let action, let onAction {
                Button(action: onAction) {
                    Text(action)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(theme.onEmber)
                        .padding(.horizontal, 18)
                        .frame(height: 40)
                        .background(Capsule().fill(theme.ember))
                }
                .buttonStyle(.plain)
                .padding(.top, 4)
            }
        }
        .padding(24)
    }

    private var liveBadge: some View {
        HStack(spacing: 5) {
            Circle()
                .fill(live.isLive ? theme.ember : Color.white.opacity(0.35))
                .frame(width: 6, height: 6)
            Text(live.isLive ? "LIVE" : "IDLE")
                .font(.system(size: 9, weight: .bold))
                .tracking(0.8)
                .foregroundStyle(live.isLive ? theme.ember : theme.secondaryText)
        }
        .padding(.horizontal, 9)
        .padding(.vertical, 6)
        .background(Capsule().fill(live.isLive ? theme.ember.opacity(0.14) : theme.raised))
    }

    private func detailRow(_ label: String, _ value: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(label)
                .font(.system(size: 12))
                .foregroundStyle(theme.secondaryText)
                .frame(width: 120, alignment: .leading)
            Text(value)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(theme.text)
                .textSelection(.enabled)
                .lineLimit(2)
            Spacer(minLength: 0)
        }
    }

    private var card: some View {
        RoundedRectangle(cornerRadius: 20, style: .continuous)
            .fill(theme.raised.opacity(0.55))
            .overlay(
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .strokeBorder(theme.hairline, lineWidth: 1)
            )
    }

    // MARK: - Actions and copy

    private func start() {
        guard let url = model.liveURL else {
            return
        }
        live.start(url: url, isOverride: model.isLiveOverridden)
    }

    private var stateTitle: String {
        switch live.phase {
        case .idle: return "Live view"
        case .connecting: return "Connecting"
        case .live: return "Live"
        case .stopped: return "Stopped"
        case .failed: return "Unavailable"
        }
    }

    private var controlTitle: String {
        switch live.phase {
        case .idle: return "Not started"
        case .connecting: return "Starting…"
        case .live: return "Streaming"
        case .stopped: return "Stopped"
        case .failed: return "Stream error"
        }
    }

    private var buttonTitle: String {
        switch live.phase {
        case .connecting, .live: return "Stop"
        default: return "Start"
        }
    }

    private var buttonSymbol: String {
        switch live.phase {
        case .connecting, .live: return "stop.fill"
        default: return "play.fill"
        }
    }
}
