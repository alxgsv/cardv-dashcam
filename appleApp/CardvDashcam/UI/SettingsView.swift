import Shared
import SwiftUI

struct SettingsView: View {
    @Environment(\.colorScheme) private var colorScheme
    @EnvironmentObject private var model: AppModel

    @State private var confirming: Setting?
    @State private var confirmation = ""

    private var theme: CarDVTheme { CarDVTheme.of(colorScheme) }

    private var bottomInset: CGFloat {
        #if os(iOS)
        return 86
        #else
        return 28
        #endif
    }

    private var safeSettings: [Setting] {
        SettingsCatalog.shared.visible(includeExperimental: model.experimentalEnabled)
            .filter { $0.name != SettingsCatalog.shared.TIME_SETTINGS.name }
            .filter { $0.name != SettingsCatalog.shared.VIDEO.name }
    }

    private var standbySettings: [Setting] {
        SettingsCatalog.shared.byLevel(level: SafetyLevel.needsStandby)
            .filter { $0.name != SettingsCatalog.shared.SD0.name }
            .filter { $0.name != SettingsCatalog.shared.FACTORY_RESET.name }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                header
                if let feedback = model.feedback {
                    banner(feedback)
                }
                section("Safe settings", settings: safeSettings)
                experimentalToggle
                section("Requires standby", settings: standbySettings)
                destructive
            }
            .padding(.horizontal, 20)
            .padding(.top, 12)
            .padding(.bottom, bottomInset)
            .frame(maxWidth: 720, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .background(theme.background)
        .alert(
            confirming.map { "\($0.name)?" } ?? "Are you sure?",
            isPresented: Binding(
                get: { confirming != nil },
                set: { if !$0 { confirming = nil } }
            ),
            presenting: confirming
        ) { setting in
            TextField("Type \(phrase(for: setting)) to confirm", text: $confirmation)
            Button("Download SOS + LOCK first") {
                model.downloadArchive()
                confirming = nil
            }
            Button("\(actionTitle(for: setting))", role: .destructive) {
                guard confirmation == phrase(for: setting) else {
                    model.feedback = Feedback(
                        message: "Type \(phrase(for: setting)) to confirm.",
                        isError: true
                    )
                    confirming = nil
                    return
                }
                model.setSetting(
                    setting,
                    value: setting.name == SettingsCatalog.shared.SD0.name ? "format" : "Camera"
                )
                confirming = nil
            }
            Button("Cancel", role: .cancel) {
                confirming = nil
            }
        } message: { setting in
            Text(destructiveMessage(for: setting))
        }
        .task {
            await model.refreshStatus()
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("SETTINGS")
                .font(.system(size: 10, weight: .semibold))
                .tracking(2)
                .foregroundStyle(theme.secondaryText)
            Text("Camera settings")
                .font(.system(size: 26, weight: .bold))
                .tracking(-0.6)
                .foregroundStyle(theme.text)
        }
    }

    private func section(_ title: String, settings: [Setting]) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(title.uppercased())
                .font(.system(size: 10, weight: .semibold))
                .tracking(1.6)
                .foregroundStyle(theme.secondaryText)
                .padding(.bottom, 8)

            VStack(spacing: 0) {
                ForEach(Array(settings.enumerated()), id: \.element.name) { index, setting in
                    settingRow(setting)
                    if index < settings.count - 1 {
                        Divider().overlay(theme.hairline)
                    }
                }
            }
            .background(card)
        }
    }

    private func settingRow(_ setting: Setting) -> some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(setting.name)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(theme.text)
                if !setting.description_.isEmpty {
                    Text(setting.description_)
                        .font(.system(size: 11))
                        .foregroundStyle(theme.secondaryText)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if setting.level == SafetyLevel.needsStandby {
                    Text("Stops recording, applies, then restarts it")
                        .font(.system(size: 10))
                        .foregroundStyle(theme.ember.opacity(0.9))
                }
            }
            Spacer(minLength: 8)

            if setting.values.isEmpty {
                Text(currentValue(setting))
                    .font(.system(size: 12, weight: .medium, design: .monospaced))
                    .foregroundStyle(theme.secondaryText)
            } else {
                Menu {
                    ForEach(setting.values, id: \.self) { value in
                        Button {
                            model.setSetting(setting, value: value)
                        } label: {
                            if value == currentValue(setting) {
                                Label(value, systemImage: "checkmark")
                            } else {
                                Text(value)
                            }
                        }
                    }
                } label: {
                    HStack(spacing: 6) {
                        Text(currentValue(setting))
                            .font(.system(size: 12, weight: .semibold))
                            .lineLimit(1)
                        Image(systemName: "chevron.up.chevron.down")
                            .font(.system(size: 9, weight: .bold))
                    }
                    .foregroundStyle(theme.text)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 7)
                    .background(Capsule().fill(theme.raised))
                }
                .disabled(model.busy != nil || model.hung)
                .fixedSize()
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
    }

    private var experimentalToggle: some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text("Experimental settings")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(theme.text)
                Text("Untested properties that may make the camera hang until the car is restarted. Leave off unless you know what you are doing.")
                    .font(.system(size: 11))
                    .foregroundStyle(theme.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer()
            Toggle("", isOn: Binding(
                get: { model.experimentalEnabled },
                set: { model.setExperimentalEnabled($0) }
            ))
            .labelsHidden()
            .tint(theme.ember)
        }
        .padding(14)
        .background(card)
    }

    private var destructive: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("DESTRUCTIVE")
                .font(.system(size: 10, weight: .semibold))
                .tracking(1.6)
                .foregroundStyle(theme.secondaryText)

            ForEach(
                [SettingsCatalog.shared.SD0, SettingsCatalog.shared.FACTORY_RESET],
                id: \.name
            ) { setting in
                Button {
                    confirmation = ""
                    confirming = setting
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: setting.name == SettingsCatalog.shared.SD0.name
                            ? "sdcard"
                            : "arrow.counterclockwise")
                        .font(.system(size: 14, weight: .semibold))
                        VStack(alignment: .leading, spacing: 1) {
                            Text(actionTitle(for: setting))
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundStyle(theme.error)
                            Text(setting.description_)
                                .font(.system(size: 11))
                                .foregroundStyle(theme.secondaryText)
                        }
                        Spacer()
                    }
                    .padding(14)
                    .background(card)
                }
                .buttonStyle(.plain)
                .disabled(model.busy != nil || model.hung)
            }
        }
    }

    private func banner(_ feedback: Feedback) -> some View {
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

    private func currentValue(_ setting: Setting) -> String {
        guard let status = model.status else { return "—" }
        return SettingsValues.shared.currentValue(status: status, setting: setting) ?? "—"
    }

    private func phrase(for setting: Setting) -> String {
        setting.name == SettingsCatalog.shared.SD0.name ? "FORMAT" : "RESET"
    }

    private func actionTitle(for setting: Setting) -> String {
        setting.name == SettingsCatalog.shared.SD0.name ? "Format SD card" : "Factory reset"
    }

    private func destructiveMessage(for setting: Setting) -> String {
        if setting.name == SettingsCatalog.shared.SD0.name {
            return "Every recording on the card is erased, including SOS and locked clips. Recording stops while the card is formatted."
        }
        return "Camera settings and internal state are reset. Recordings on the SD card are not erased."
    }

    private var card: some View {
        RoundedRectangle(cornerRadius: 18, style: .continuous)
            .fill(theme.raised.opacity(0.55))
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .strokeBorder(theme.hairline, lineWidth: 1)
            )
    }
}
