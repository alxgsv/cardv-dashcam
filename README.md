# CarDV Dashcam

Open-source **Android, iOS and macOS** app for the **Geely Atlas / Atlas Pro factory dashcam (DVR)** and other
**AIT / MStar "CarDV" Wi-Fi dashcams**: browse, play and download recordings, live view, snapshots, lock clips,
clock sync and settings — no cloud, no account.

> Status: protocol fully reverse-engineered, repository scaffolding in place (milestone M0), apps in
> development. See the [plan](docs/PLAN.md).

## Is my dashcam supported?

Your camera probably works if:
- its Wi-Fi gives you an address like `192.168.42.x` and the camera is at `192.168.42.1`, and
- `http://192.168.42.1/cgi-bin/Config.cgi?action=get&property=Camera.Menu.FWversion` returns something like
  `0 OK Camera.Menu.FWversion=MS;ms8339DEW;…;GEELY_Z3152_V1.2;…`

Verified: Geely Atlas / Atlas Pro factory DVR, firmware `GEELY_Z3152_V1.2` (MStar MSC8339).
Got a different camera? Run the [probe tools](tools/probe/README.md) and open an issue with the report.

## Features (planned v1)

- Recordings: loop, SOS (G-sensor events), locked clips and photos, with thumbnails
- Play directly from the camera with seeking, or download (resumable) to your phone / Mac
- Live view (720p with sound)
- Lock the current clip, take a snapshot, mic on/off, start/stop recording
- Automatic clock sync, SD card status, safe settings

## Building

Requirements: JDK 17+, Android SDK platform 36 and build-tools 36 for the Android app; Xcode 16+ for the
Apple apps. Point `ANDROID_HOME` (or `sdk.dir` in `local.properties`) at your Android SDK.

```sh
./gradlew :androidApp:assembleDebug     # APK in androidApp/build/outputs/apk/debug/
./gradlew :shared:testDebugUnitTest     # shared-core tests on the JVM
./gradlew :androidApp:lintDebug
```

`appleApp/CardvDashcam.xcodeproj` is one multiplatform target with iOS and macOS destinations. Xcode runs
Gradle (`:shared:embedAndSignAppleFrameworkForXcode`) to build the Kotlin framework before compiling Swift,
so there is no XCFramework to build or carry around first:

```sh
open appleApp/CardvDashcam.xcodeproj
```

CI (`.github/workflows/ci.yml`) runs the shared tests, builds and lints the Android app, and builds both
Apple destinations on every push and pull request.

## Documentation

- [docs/PROTOCOL.md](docs/PROTOCOL.md) — the camera's HTTP/RTSP protocol, with every quirk we found
- [docs/PLAN.md](docs/PLAN.md) — architecture (Kotlin Multiplatform + native UI) and milestones
- [tools/probe/](tools/probe/README.md) — Python scripts to probe a camera

## Disclaimer

Not affiliated with Geely or any camera manufacturer. Some camera settings can make the dashcam's Wi-Fi
interface hang until the car is restarted; the app only exposes settings verified to be safe unless you enable
experimental settings. Use at your own risk.

## License

[MIT](LICENSE)
