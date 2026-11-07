import Shared
import SwiftUI

struct ContentView: View {
    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "car.side.fill")
                .font(.system(size: 56))
                .foregroundStyle(.tint)

            Text(BuildInfo.shared.APP_NAME)
                .font(.largeTitle.bold())
                .multilineTextAlignment(.center)

            Text("Milestone M0 — scaffolding builds")
                .foregroundStyle(.secondary)

            Text("Shared core \(BuildInfo.shared.VERSION) · \(PlatformInfo.shared.name)")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .padding(32)
        .frame(minWidth: 320, minHeight: 320)
    }
}

#Preview {
    ContentView()
}
