import Shared
import SwiftUI

struct ConnectView: View {
    @Environment(\.colorScheme) private var colorScheme
    @EnvironmentObject private var model: AppModel

    @State private var developerOpen = false
    @State private var versionTaps = 0
    @State private var debugURL = ""
    @State private var debugLiveURL = ""

    private var connecting: Bool { model.phase == .connecting }
    private var failed: Bool { model.phase == .failed }

    var body: some View {
        let theme = CarDVTheme.of(colorScheme)
        ScrollView {
            VStack(spacing: 0) {
                header(theme)

                Spacer(minLength: 18)

                hero(theme)

                Text(wordmark(theme))
                    .font(.system(size: 40, weight: .black, design: .default))
                    .tracking(-1.2)
                    .padding(.top, 6)

                Spacer(minLength: 22)

                connectionCard(theme)

                Button {
                    model.connect()
                } label: {
                    HStack(spacing: 10) {
                        if connecting {
                            ProgressView()
                                .controlSize(.small)
                                .tint(theme.onEmber)
                        } else {
                            Image(systemName: failed ? "exclamationmark.triangle.fill" : "wifi")
                        }
                        Text(connecting ? "Connecting…" : (failed ? "Try again" : "Connect to dashcam"))
                    }
                }
                .buttonStyle(PrimaryButtonStyle(theme: theme, enabled: !connecting))
                .disabled(connecting)
                .padding(.top, 16)

                VStack(spacing: 6) {
                    Text("Power the camera on, join its Wi-Fi, then connect.")
                        .font(.system(size: 13))
                        .foregroundStyle(theme.secondaryText)
                    Text("The camera has no internet. Your phone may warn about that; keep the Wi-Fi joined.")
                        .font(.system(size: 11))
                        .foregroundStyle(theme.secondaryText.opacity(0.8))
                        .multilineTextAlignment(.center)
                }
                .padding(.top, 14)

                HStack(spacing: 8) {
                    step("01", "Power on", theme)
                    step("02", "Join Wi-Fi", theme)
                    step("03", "Connect", theme)
                }
                .padding(.top, 18)

                if isDebugBuild && (versionTaps >= 5 || CameraPreferences.baseURLOverride != nil || CameraPreferences.hasEnvironmentBaseURL) {
                    developerSection(theme)
                        .padding(.top, 18)
                }
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 18)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
        }
        #if os(iOS)
        .scrollDismissesKeyboard(.interactively)
        #endif
        .onAppear {
            if debugURL.isEmpty {
                if let override = CameraPreferences.baseURLOverride {
                    debugURL = override
                } else if CameraPreferences.hasEnvironmentBaseURL {
                    debugURL = CameraPreferences.effectiveBaseURL
                }
            }
            if debugLiveURL.isEmpty, let override = CameraPreferences.liveURLOverride {
                debugLiveURL = override
            }
        }
    }

    private var isDebugBuild: Bool {
        #if DEBUG
        return true
        #else
        return false
        #endif
    }

    // MARK: - Pieces

    private func header(_ theme: CarDVTheme) -> some View {
        HStack(spacing: 8) {
            Circle()
                .fill(theme.ember)
                .frame(width: 6, height: 6)
            Text("DASHCAM")
                .font(.system(size: 11, weight: .semibold))
                .tracking(3)
                .foregroundStyle(theme.secondaryText)
            Spacer()
            Button {
                versionTaps += 1
                if versionTaps >= 5 { developerOpen = true }
            } label: {
                Text("v\(BuildInfo.shared.VERSION)")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(theme.secondaryText)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(Capsule().fill(theme.raised))
            }
            .buttonStyle(.plain)
        }
    }

    private func hero(_ theme: CarDVTheme) -> some View {
        ZStack {
            Circle()
                .fill(
                    RadialGradient(
                        colors: [theme.ember.opacity(0.20), .clear],
                        center: .center,
                        startRadius: 4,
                        endRadius: 118
                    )
                )
                .frame(width: 236, height: 236)
            Circle()
                .strokeBorder(theme.outline.opacity(0.7), lineWidth: 1)
                .background(Circle().fill(theme.surface))
                .frame(width: 150, height: 150)
            Image(systemName: "camera.aperture")
                .font(.system(size: 58, weight: .light))
                .foregroundStyle(theme.ember)
            Circle()
                .fill(theme.ember)
                .frame(width: 12, height: 12)
                .offset(x: 88, y: -88)
                .shadow(color: theme.ember.opacity(0.6), radius: 8)
        }
        .frame(height: 238)
    }

    private func wordmark(_ theme: CarDVTheme) -> AttributedString {
        var text = AttributedString("CarDV")
        if let range = text.range(of: "DV") {
            text[range].foregroundColor = theme.ember
        }
        return text
    }

    private func connectionCard(_ theme: CarDVTheme) -> some View {
        HStack(spacing: 14) {
            ZStack {
                Circle()
                    .fill(failed ? theme.errorFill : theme.surface)
                    .frame(width: 44, height: 44)
                if connecting {
                    ProgressView()
                        .controlSize(.small)
                        .tint(theme.ember)
                } else {
                    Image(systemName: failed ? "exclamationmark.triangle.fill" : "wifi")
                        .font(.system(size: 19, weight: .medium))
                        .foregroundStyle(failed ? theme.error : theme.ember)
                }
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(theme.text)
                Text(subtitle)
                    .font(.system(size: 13))
                    .foregroundStyle(failed ? theme.error : theme.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
        .padding(18)
        .background(
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .fill(theme.raised.opacity(0.62))
                .overlay(
                    RoundedRectangle(cornerRadius: 22, style: .continuous)
                        .strokeBorder(
                            failed ? theme.error.opacity(0.35) : theme.hairline,
                            lineWidth: 1
                        )
                )
        )
    }

    private var title: String {
        if connecting { return "Looking for your dashcam" }
        if failed { return model.hung ? "Camera needs a restart" : "Dashcam not found" }
        return "Ready when you are"
    }

    private var subtitle: String {
        if connecting { return "Keep the camera on and stay on its Wi-Fi." }
        if failed { return model.errorMessage ?? "Try again in a moment." }
        return "Join the dashcam's Wi-Fi, then connect."
    }

    private func step(_ number: String, _ label: String, _ theme: CarDVTheme) -> some View {
        VStack(spacing: 6) {
            Text(number)
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(theme.ember)
                .frame(width: 30, height: 30)
                .background(Circle().fill(theme.raised))
            Text(label)
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(theme.secondaryText)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity)
    }

    private func developerSection(_ theme: CarDVTheme) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Button {
                developerOpen.toggle()
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "slider.horizontal.3")
                    Text(developerOpen ? "Hide developer options" : "Developer options")
                }
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(theme.ember)
            }
            .buttonStyle(.plain)

            if developerOpen {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Test camera")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(theme.text)
                    Text("Point the app at a local fake camera. Release builds ignore this.")
                        .font(.system(size: 12))
                        .foregroundStyle(theme.secondaryText)

                    field("Base URL", text: $debugURL, placeholder: "http://127.0.0.1:8080", theme: theme)
                    field("Live RTSP URL", text: $debugLiveURL, placeholder: "rtsp://192.168.1.20:8554/live", theme: theme)

                    HStack(spacing: 10) {
                        Button("Apply") {
                            CameraPreferences.liveURLOverride =
                                debugLiveURL.isEmpty ? nil : debugLiveURL
                            model.useBaseURL(debugURL.isEmpty ? nil : debugURL)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(theme.ember)
                        .disabled(connecting)

                        Button("Test camera") {
                            debugURL = "http://127.0.0.1:8080"
                            debugLiveURL = ""
                            CameraPreferences.liveURLOverride = nil
                            model.useBaseURL(debugURL)
                        }
                        .buttonStyle(.bordered)
                        .disabled(connecting)

                        Button("Reset") {
                            debugURL = ""
                            debugLiveURL = ""
                            CameraPreferences.liveURLOverride = nil
                            model.useBaseURL(nil)
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(theme.secondaryText)
                        .disabled(connecting)

                        Spacer()
                    }
                    .font(.system(size: 13, weight: .medium))

                    Text("Empty base URL = \(CameraPreferences.cameraBaseURL), the real camera.")
                        .font(.system(size: 11))
                        .foregroundStyle(theme.secondaryText)
                }
                .padding(16)
                .background(
                    RoundedRectangle(cornerRadius: 20, style: .continuous)
                        .fill(theme.raised.opacity(0.55))
                )
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func field(
        _ label: String,
        text: Binding<String>,
        placeholder: String,
        theme: CarDVTheme
    ) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label)
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(theme.secondaryText)
            TextField(placeholder, text: text)
                .textFieldStyle(.plain)
                .font(.system(size: 13, design: .monospaced))
                #if os(iOS)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                #endif
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
                .background(
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(theme.surface)
                        .overlay(
                            RoundedRectangle(cornerRadius: 10, style: .continuous)
                                .strokeBorder(theme.hairline, lineWidth: 1)
                        )
                )
        }
    }
}
