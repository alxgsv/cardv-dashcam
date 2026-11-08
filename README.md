# CarDV Dashcam

Open-source **Android, iOS and macOS** app for Wi-Fi dashcams built on the **AIT / MStar "CarDV"** firmware —
the **Geely Atlas / Atlas Pro**, **Belgee X70** and other cars fitted with the same camera: browse, play and
download recordings, live view, snapshots, lock clips, clock sync and settings — no cloud, no account.

## Supported cars and cameras

The app talks to the dashcam, not to the car: any DVR running the same AIT / MStar **CarDV** firmware works,
whichever badge it was fitted under. Compatibility depends on the DVR fitted to that particular car, not 
on the model name.

| Car | Chinese name |
| --- | --- |
| Geely Atlas / Atlas Pro, Belgee X70 | 博越 / 博越Pro |
| Geely Atlas 2024 (FX11) / Starray | 博越L |
| Geely Cityray | 博越COOL |
| Geely Coolray | 缤越 |
| Geely Preface | 星瑞 |
| Geely Galaxy L7 | 银河L7 |
| Ford Mustang Mach-E (China) | 福特电马 |

### Is my camera supported?

Your camera probably works if:
- its Wi-Fi gives you an address like `192.168.42.x` and the camera is at `192.168.42.1`, and
- `http://192.168.42.1/cgi-bin/Config.cgi?action=get&property=Camera.Menu.FWversion` returns something like
  `0 OK Camera.Menu.FWversion=MS;ms8339DEW;…;GEELY_Z3152_V1.2;…`

Got a different camera or car? Run the [probe tools](tools/probe/README.md) and open an issue with the report.
Standalone dashcams on the same firmware family (for example MStar MSC8339 models that take an `SD_CarDV.bin`
update) may also work, but often use a different IP or `/SD/...` layout and need configuration first.

## Features

- Recordings: loop, SOS (G-sensor events), locked clips and photos, with thumbnails
- Play directly from the camera with seeking, or download (resumable) to your phone / Mac
- Live view (720p with sound) on Android, iOS and macOS
- Lock the current clip, take a snapshot, mic on/off, start/stop recording
- Automatic clock sync, SD card status, safe settings

## Run it

### Android — install the APK

The signed APK from [GitHub Releases](https://github.com/alxgsv/cardv-dashcam/releases/latest) is the
supported Android artifact.

1. Download `androidApp-release.apk` and `SHA256SUMS` from the latest release.
2. Verify the download (optional but recommended):

   ```sh
   shasum -a 256 -c SHA256SUMS    # macOS
   sha256sum -c SHA256SUMS        # Linux
   ```

3. Install it on the phone:

   ```sh
   adb install -r androidApp-release.apk
   ```

   Or copy the APK to the phone and open it; allow **Install unknown apps** for the browser/file
   manager when Android asks.

4. Join the dashcam's Wi-Fi, open **CarDV Dashcam** and press **Connect to dashcam**.

Requires Android 8.0 or newer (minSdk 26). The release build always uses the camera at
`192.168.42.1`.

### iPhone / iPad — build and install over the cable

This is the supported iOS route. You need macOS, Xcode 26+
(for the macOS 26 SDK used by Kotlin/Native), a paid Apple Developer Program team and iOS 16+.

```sh
git clone https://github.com/alxgsv/cardv-dashcam.git
cd cardv-dashcam
cp appleApp/Signing.local.xcconfig.example appleApp/Signing.local.xcconfig
# Put your ten-character team id in appleApp/Signing.local.xcconfig
open appleApp/CardvDashcam.xcodeproj
```

Connect the iPhone, unlock it and tap **Trust This Computer**. On iOS 16+ enable
**Settings → Privacy & Security → Developer Mode** and restart the phone. Select the iPhone in Xcode
and press **Run**, or install from the command line:

```sh
xcrun devicectl list devices
tools/apple/install-device.sh <device-udid>
```

The script builds Release, registers a new device in your developer account, signs, installs and
launches. The development profile lasts about one year; re-run the script when it expires. A free
Apple ID also works but expires after 7 days.

### macOS — build from source

macOS users build the app locally. You need macOS 13+ and Xcode 26+.

```sh
git clone https://github.com/alxgsv/cardv-dashcam.git
cd cardv-dashcam
open appleApp/CardvDashcam.xcodeproj
```

Select **My Mac** and press **Run**. Or build from the command line:

```sh
xcodebuild -project appleApp/CardvDashcam.xcodeproj \
  -scheme CardvDashcam -configuration Release -sdk macosx \
  -derivedDataPath build/macos CODE_SIGNING_ALLOWED=NO build
open build/macos/Build/Products/Release/CardvDashcam.app
```

### First run on every platform

- Join the dashcam's Wi-Fi in system settings. The camera has no internet, so the phone or Mac may warn
  about that; keep the Wi-Fi joined.
- The camera is at `192.168.42.1`. On iOS and macOS allow **Local Network** when prompted.
- Release builds always use the real camera address; debug builds can point at a fake camera instead.
- The camera clock is synced when the app connects.

## Building

Requirements: JDK 17+, Android SDK platform 36 and build-tools 36 for the Android app; Xcode 26+ for the
Apple apps. Point `ANDROID_HOME` (or `sdk.dir` in `local.properties`) at your Android SDK.

```sh
./gradlew :androidApp:assembleDebug     # APK in androidApp/build/outputs/apk/debug/
./gradlew :shared:testDebugUnitTest     # shared-core tests on the JVM
./gradlew :androidApp:testDebugUnitTest # Android URL/download helper tests
./gradlew :androidApp:lintDebug
```

Release builds enable R8 and resource shrinking. Signing material is read from the gitignored
`local.properties` or `CARDV_RELEASE_*` environment variables; without it the release APK is unsigned:

```sh
./gradlew :androidApp:assembleRelease    # APK in androidApp/build/outputs/apk/release/
./gradlew :androidApp:bundleRelease      # AAB in androidApp/build/outputs/bundle/release/
```

See [docs/RELEASE.md](docs/RELEASE.md) for the version bump and GitHub Release workflow.

`appleApp/CardvDashcam.xcodeproj` is one multiplatform target with iOS and macOS destinations. Xcode runs
Gradle (`:shared:embedAndSignAppleFrameworkForXcode`) to build the Kotlin framework before compiling Swift,
so there is no XCFramework to build or carry around first:

```sh
open appleApp/CardvDashcam.xcodeproj
```

CI (`.github/workflows/ci.yml`) runs the shared and Android tests, builds and lints the Android app,
and builds both Apple destinations on every push and pull request.

### Versioning and release channels

The root [`version.xcconfig`](version.xcconfig) is the only version source. It feeds the Android
`versionName`/`versionCode`, the shared `BuildInfo.VERSION`, and Xcode's `MARKETING_VERSION` /
`CURRENT_PROJECT_VERSION`. `tools/release/check-version.sh` validates the file and the tag. The bump
procedure is in [docs/RELEASE.md](docs/RELEASE.md).

| Channel | Artifact | Status |
|---|---|---|
| GitHub Releases | signed APK + AAB + `SHA256SUMS` | Supported; the APK is the supported Android install |
| iOS | Xcode cable install | Supported; one-year development profile |
| macOS | local Xcode build | Supported |

Only the Android release workflow is part of the distribution; it never runs on pull requests or forks.
Pull-request CI uses no secrets.

No camera at hand? The shared tests start the [fake camera](tools/fake-camera/README.md) themselves;
you can also run it for the apps:

```sh
python3 tools/fake-camera/fake_camera.py --port 8080          # simulator / Mac: 127.0.0.1
python3 tools/fake-camera/fake_camera.py --host 0.0.0.0       # physical phone on the same Wi-Fi

tools/fake-camera/start-live-stream.sh                        # MediaMTX live stream on :8554
```

The fake camera has no RTSP. `start-live-stream.sh` starts MediaMTX and publishes a 720p H.264 + AAC
test pattern with ffmpeg; point the debug live URL at `rtsp://127.0.0.1:8554/live`.

Debug builds show a **Debug camera address** field on the connect screen (release builds always use
`192.168.42.1`). On the Android emulator point it at `http://10.0.2.2:8080`; on a device use your
computer's LAN address. Debug builds allow cleartext to that address; the release policy only allows
cleartext to `192.168.42.1`.

## Documentation

- [docs/PROTOCOL.md](docs/PROTOCOL.md) — the camera's HTTP/RTSP protocol, with every quirk we found
- [docs/DESIGN.md](docs/DESIGN.md) — architecture, safety rules and platform notes
- [docs/RELEASE.md](docs/RELEASE.md) — version bump, signing and the GitHub Release workflow
- [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md) — supported cars/firmware and the tested-on table
- [docs/M4-SPIKE.md](docs/M4-SPIKE.md) — the VLCKit vs own-RTSP-client live-view spike and its numbers
- [.github/ISSUE_TEMPLATE/camera-report.yml](.github/ISSUE_TEMPLATE/camera-report.yml) — report a camera
- [tools/probe/](tools/probe/README.md) — Python scripts to probe a camera
- [tools/fake-camera/](tools/fake-camera/README.md) — fake camera for development and tests

## Disclaimer

Not affiliated with Geely or any camera manufacturer. Some camera settings can make the dashcam's Wi-Fi
interface hang until the car is restarted; the app only exposes settings verified to be safe unless you enable
experimental settings. Use at your own risk.

## License

[MIT](LICENSE)
