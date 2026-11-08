import Shared
import SwiftUI

struct ControlsView: View {
    @Environment(\.colorScheme) private var colorScheme
    @EnvironmentObject private var model: AppModel

    private var theme: CarDVTheme { CarDVTheme.of(colorScheme) }

    private var bottomInset: CGFloat {
        #if os(iOS)
        return 86
        #else
        return 28
        #endif
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                header
                statusCard
                actionGrid
                if let feedback = model.feedback {
                    feedbackBanner(feedback)
                }
                detailsCard
            }
            .padding(.horizontal, 20)
            .padding(.top, 12)
            .padding(.bottom, bottomInset)
            .frame(maxWidth: 720, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .background(theme.background)
        .task {
            await model.refreshStatus()
        }
    }

    private var header: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                Text("CAMERA")
                    .font(.system(size: 10, weight: .semibold))
                    .tracking(2)
                    .foregroundStyle(theme.secondaryText)
                Text(model.isRecording ? "Recording" : "Standby")
                    .font(.system(size: 26, weight: .bold))
                    .tracking(-0.6)
                    .foregroundStyle(model.isRecording ? theme.ember : theme.text)
            }
            Spacer()
            Button {
                Task { await model.refreshStatus() }
            } label: {
                Image(systemName: "arrow.clockwise")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(theme.secondaryText)
                    .frame(width: 34, height: 34)
                    .background(Circle().fill(theme.raised.opacity(0.75)))
            }
            .buttonStyle(.plain)
        }
    }

    private var statusCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            if model.hung {
                HStack(spacing: 10) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(theme.error)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("The camera stopped responding")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(theme.text)
                        Text("Restart the car to reset the dashcam, then reconnect.")
                            .font(.system(size: 12))
                            .foregroundStyle(theme.error)
                    }
                }
            }

            HStack(spacing: 10) {
                statePill(
                    title: model.isRecording ? "REC" : "STBY",
                    symbol: model.isRecording ? "record.circle.fill" : "pause.circle.fill",
                    accent: model.isRecording
                )
                if let sd = model.status?.sdStatus {
                    statePill(
                        title: "SD \(sd.uppercased())",
                        symbol: "sdcard.fill",
                        accent: sd.caseInsensitiveCompare("READY") != .orderedSame
                    )
                }
                if let free = model.status?.freeMb {
                    statePill(
                        title: "\(Format.megabytes(free)) FREE",
                        symbol: "internaldrive.fill",
                        accent: false
                    )
                }
                Spacer()
            }

            HStack(spacing: 16) {
                info("Camera clock", Format.clock(model.status?.cameraTime))
                if let file = model.currentRecordingName {
                    info("Writing", file)
                }
            }
        }
        .padding(18)
        .background(card)
    }

    private var detailsCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("DEVICE")
                .font(.system(size: 10, weight: .semibold))
                .tracking(1.6)
                .foregroundStyle(theme.secondaryText)

            detailRow("Firmware", model.status?.firmware ?? "—")
            if let modelName = model.status?.model, !modelName.isEmpty {
                detailRow("Model", modelName)
            }
            detailRow("Address", model.baseURL)
            detailRow("Microphone", model.microphoneOn ? "ON" : "OFF")
            detailRow("Exposure", model.status?.ev ?? "—")
            detailRow("White balance", model.status?.awb ?? "—")
            if let clipTime = model.status?.videoClipTime, !clipTime.isEmpty {
                detailRow("Clip length", clipTime)
            }
            if let message = model.clockMessage {
                Text(message)
                    .font(.system(size: 11))
                    .foregroundStyle(theme.secondaryText)
                    .padding(.top, 2)
            }
        }
        .padding(18)
        .background(card)
    }

    private var actionGrid: some View {
        LazyVGrid(
            columns: [GridItem(.adaptive(minimum: 150), spacing: 10)],
            spacing: 10
        ) {
            action(
                title: model.isRecording ? "Stop recording" : "Start recording",
                symbol: model.isRecording ? "stop.fill" : "record.circle",
                busy: model.busy == .record,
                tint: model.isRecording ? theme.error : theme.ember
            ) {
                model.toggleRecording()
            }
            action(
                title: model.microphoneOn ? "Mute mic" : "Unmute mic",
                symbol: model.microphoneOn ? "mic.fill" : "mic.slash.fill",
                busy: model.busy == .microphone
            ) {
                model.toggleMicrophone()
            }
            action(
                title: "Snapshot",
                symbol: "camera.fill",
                busy: model.busy == .snapshot
            ) {
                model.captureSnapshot()
            }
            action(
                title: "Lock current clip",
                symbol: "lock.fill",
                busy: model.busy == .lock
            ) {
                model.lockCurrentClip()
            }
            action(
                title: "Sync clock",
                symbol: "clock.arrow.circlepath",
                busy: model.busy == .clock
            ) {
                model.syncClockNow()
            }
        }
    }

    private func action(
        title: String,
        symbol: String,
        busy: Bool,
        tint: Color? = nil,
        onTap: @escaping () -> Void
    ) -> some View {
        Button(action: onTap) {
            HStack(spacing: 10) {
                if busy {
                    ProgressView()
                        .controlSize(.small)
                        .tint(theme.ember)
                } else {
                    Image(systemName: symbol)
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(tint ?? theme.ember)
                }
                Text(title)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(theme.text)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 14)
            .frame(height: 48)
            .background(card)
        }
        .buttonStyle(.plain)
        .disabled(model.busy != nil || model.hung)
        .opacity(model.busy != nil && !busy ? 0.55 : 1)
    }

    private func feedbackBanner(_ feedback: Feedback) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: feedback.isError ? "exclamationmark.circle.fill" : "checkmark.circle.fill")
                .foregroundStyle(feedback.isError ? theme.error : theme.ember)
            Text(feedback.message)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(feedback.isError ? theme.error : theme.text)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
            Button {
                model.clearFeedback()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundStyle(theme.secondaryText)
            }
            .buttonStyle(.plain)
        }
        .padding(14)
        .background(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(feedback.isError ? theme.errorFill.opacity(0.6) : theme.raised.opacity(0.7))
        )
    }

    private func statePill(title: String, symbol: String, accent: Bool) -> some View {
        HStack(spacing: 5) {
            Image(systemName: symbol)
                .font(.system(size: 10, weight: .bold))
            Text(title)
                .font(.system(size: 10, weight: .bold))
                .tracking(0.5)
        }
        .foregroundStyle(accent ? theme.ember : theme.secondaryText)
        .padding(.horizontal, 9)
        .padding(.vertical, 6)
        .background(Capsule().fill(accent ? theme.ember.opacity(0.12) : theme.raised))
    }

    private func info(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label.uppercased())
                .font(.system(size: 9, weight: .semibold))
                .tracking(0.8)
                .foregroundStyle(theme.secondaryText)
            Text(value)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(theme.text)
                .lineLimit(1)
        }
    }

    private func detailRow(_ label: String, _ value: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(label)
                .font(.system(size: 12))
                .foregroundStyle(theme.secondaryText)
                .frame(width: 110, alignment: .leading)
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
}
