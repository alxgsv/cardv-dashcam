import SwiftUI

/// The warm gallery palette from the Android app, so both platforms feel like one product.
///
/// Colours are plain values rather than an Asset Catalog: the app is dark-first, and the light
/// variant is a deliberate warm paper tone instead of the system grey.
struct CarDVTheme {
    let dark: Bool

    var ember: Color { Color(hex: dark ? 0xFF6B3D : 0xD94A1B) }
    var onEmber: Color { dark ? Color(hex: 0x200E06) : .white }
    var background: Color { Color(hex: dark ? 0x0C0D0F : 0xF7F2E9) }
    var surface: Color { Color(hex: dark ? 0x15171B : 0xFFFBF5) }
    var raised: Color { Color(hex: dark ? 0x1E2126 : 0xEDE4D7) }
    var text: Color { Color(hex: dark ? 0xF6F1E9 : 0x231F1A) }
    var secondaryText: Color { Color(hex: dark ? 0xB7ADA1 : 0x6E6357) }
    var outline: Color { Color(hex: dark ? 0x3A3D44 : 0xD4C7B7) }
    var hairline: Color { Color(hex: dark ? 0x26292F : 0xE8DED0) }
    var error: Color { Color(hex: dark ? 0xFFB4A8 : 0xB3261E) }
    var errorFill: Color { Color(hex: dark ? 0x5C1A12 : 0xF9DEDC) }

    static func of(_ scheme: ColorScheme) -> CarDVTheme {
        CarDVTheme(dark: scheme == .dark)
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: 1
        )
    }
}

/// A soft capsule used for folder pills and toolbar actions.
struct PillButtonStyle: ButtonStyle {
    let theme: CarDVTheme
    var selected = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 12, weight: .semibold))
            .foregroundStyle(selected ? theme.onEmber : theme.secondaryText)
            .padding(.horizontal, 11)
            .padding(.vertical, 8)
            .background(
                Capsule(style: .continuous)
                    .fill(selected ? theme.ember : theme.raised.opacity(0.75))
            )
            .opacity(configuration.isPressed ? 0.72 : 1)
    }
}

/// The large primary action, matching the Android connect button.
struct PrimaryButtonStyle: ButtonStyle {
    let theme: CarDVTheme
    var enabled = true

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 15, weight: .semibold))
            .foregroundStyle(enabled ? theme.onEmber : theme.secondaryText)
            .frame(maxWidth: .infinity, minHeight: 54)
            .background(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(enabled ? theme.ember : theme.raised)
            )
            .opacity(configuration.isPressed ? 0.85 : 1)
    }
}
