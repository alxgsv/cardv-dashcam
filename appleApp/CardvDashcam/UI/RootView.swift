import SwiftUI

struct RootView: View {
    @Environment(\.colorScheme) private var colorScheme
    @StateObject private var model = AppModel()

    var body: some View {
        let theme = CarDVTheme.of(colorScheme)
        ZStack {
            theme.background.ignoresSafeArea()
            if model.isConnected {
                MainShell()
            } else {
                ConnectView()
            }
        }
        .environmentObject(model)
        .environmentObject(model.downloads)
        .tint(theme.ember)
        #if os(iOS)
        .fullScreenCover(item: $model.openClip) { item in
            PlaybackView(item: item)
        }
        #else
        .sheet(item: $model.openClip) { item in
            PlaybackView(item: item)
                .frame(minWidth: 880, minHeight: 580)
        }
        #endif
    }
}

enum ShellSection: Hashable {
    case library
    case live
    case camera
    case settings
}

struct MainShell: View {
    @Environment(\.colorScheme) private var colorScheme
    @EnvironmentObject private var model: AppModel

    /// Debug-only start tab, so screenshots and headless runs can open any screen.
    private static var debugStartSection: ShellSection? {
        #if DEBUG
        switch ProcessInfo.processInfo.environment["CARDV_START_SECTION"]?.lowercased() {
        case "library": return .library
        case "live": return .live
        case "camera": return .camera
        case "settings": return .settings
        default: return nil
        }
        #else
        return nil
        #endif
    }

    #if os(iOS)
    @State private var tab: ShellSection = .library

    var body: some View {
        let theme = CarDVTheme.of(colorScheme)
        TabView(selection: $tab) {
            LibraryView()
                .tabItem { Label("Library", systemImage: "square.grid.2x2.fill") }
                .tag(ShellSection.library)
            LiveView()
                .tabItem { Label("Live", systemImage: "dot.radiowaves.left.and.right") }
                .tag(ShellSection.live)
            ControlsView()
                .tabItem { Label("Camera", systemImage: "video.fill") }
                .tag(ShellSection.camera)
            SettingsView()
                .tabItem { Label("Settings", systemImage: "slider.horizontal.3") }
                .tag(ShellSection.settings)
        }
        .tint(theme.ember)
        .onAppear {
            if let start = Self.debugStartSection { tab = start }
        }
    }
    #else
    @State private var section: ShellSection? = .library

    var body: some View {
        NavigationSplitView {
            List(selection: $section) {
                Label("Library", systemImage: "square.grid.2x2.fill")
                    .tag(ShellSection.library)
                Label("Live", systemImage: "dot.radiowaves.left.and.right")
                    .tag(ShellSection.live)
                Label("Camera", systemImage: "video.fill")
                    .tag(ShellSection.camera)
                Label("Settings", systemImage: "slider.horizontal.3")
                    .tag(ShellSection.settings)
            }
            .listStyle(.sidebar)
            .navigationTitle("CarDV")
            .navigationSplitViewColumnWidth(min: 180, ideal: 210, max: 260)
        } detail: {
            switch section ?? .library {
            case .library: LibraryView()
            case .live: LiveView()
            case .camera: ControlsView()
            case .settings: SettingsView()
            }
        }
        .frame(minWidth: 900, minHeight: 620)
        .onAppear {
            if let start = Self.debugStartSection { section = start }
        }
    }
    #endif
}
