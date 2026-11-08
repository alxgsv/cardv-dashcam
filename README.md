# CarDV Dashcam

Open-source **Android, iOS and macOS** app for Wi-Fi dashcams built on the **AIT / MStar "CarDV"** firmware —
the **Geely Atlas / Atlas Pro**, **Belgee X70** and other cars fitted with the same camera: browse, play and
download recordings, live view, snapshots, lock clips, clock sync and settings — no cloud, no account.

> Status: protocol fully reverse-engineered, shared core (M1), Android M2–M6 and Apple M3, M5 and M6
> implemented (library, playback, downloads, controls and settings). Apple live view (M4) is the
> remaining milestone.
> See the [plan](docs/PLAN.md).

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
./gradlew :androidApp:testDebugUnitTest # Android URL/download helper tests
./gradlew :androidApp:lintDebug
```

`appleApp/CardvDashcam.xcodeproj` is one multiplatform target with iOS and macOS destinations. Xcode runs
Gradle (`:shared:embedAndSignAppleFrameworkForXcode`) to build the Kotlin framework before compiling Swift,
so there is no XCFramework to build or carry around first:

```sh
open appleApp/CardvDashcam.xcodeproj
```

CI (`.github/workflows/ci.yml`) runs the shared and Android tests, builds and lints the Android app,
and builds both Apple destinations on every push and pull request.

No camera at hand? The shared tests start the [fake camera](tools/fake-camera/README.md) themselves;
you can also run it for the apps:

```sh
python3 tools/fake-camera/fake_camera.py --port 8080          # simulator / Mac: 127.0.0.1
python3 tools/fake-camera/fake_camera.py --host 0.0.0.0       # physical phone on the same Wi-Fi
```

Debug builds show a **Debug camera address** field on the connect screen (release builds always use
`192.168.42.1`). On the Android emulator point it at `http://10.0.2.2:8080`; on a device use your
computer's LAN address. Debug builds allow cleartext to that address; the release policy only allows
cleartext to `192.168.42.1`.

## Android MVP (M2)

- **Connect** — join the dashcam's Wi-Fi in Android settings. The app finds the `192.168.42.0/24`
  network, binds the process to it and gives Ktor, ExoPlayer and downloads OkHttp clients bound to
  that network's socket factory, so traffic never falls back to mobile data. The camera clock is
  synced on every connect (`TimeSettings` through the shared core).
- **Library** — DCIM (loop), SOS, LOCK and Photo folders; MP4 thumbnails from the embedded `skip`
  box, photo thumbnails from the JPEG; sort newest/oldest/name and group by day.
- **Playback** — Media3 ExoPlayer streams MP4 directly from the camera over HTTP; `moov` is before
  `mdat` on this camera, so seeking works without downloading the clip first. The player opens in
  landscape with a custom seek bar and turns the system bars off for an immersive view.
- **Download** — WorkManager downloads with `Range` resume and a partial file that survives process
  death, then exports through MediaStore to `Movies/CarDV` (photos: `Pictures/CarDV`). Android 8–9
  asks for `WRITE_EXTERNAL_STORAGE`; Android 10+ needs no storage permission.
- **Not in M2** — live view (M4), camera controls (M5) and settings screens (M6).

## Android live view, controls and settings (M4–M6)

- **Live view** — Media3 `RtspMediaSource` on `liveRTSP/av2` (720p H.264 + AAC), RTP forced over
  TCP and the RTSP socket bound to the camera Wi-Fi. It has connecting/error/stopped states and a
  start/stop control. This is a separate stream from the M2 HTTP playback URL.
- **Camera** — status card (recording, SD state, free space, firmware, current file, camera clock)
  plus mic mute/unmute, snapshot (`Video=capture`), lock current clip (`Video=rec_emer`, then a
  LOCK re-poll because the folder catches up late) and record stop/start. Success, refusal and
  hung-camera outcomes are shown in place; a hung camera is never retried.
- **Settings** — rows and values come from the shared `SettingsCatalog`. `SAFE` settings are shown
  by default; `EXPERIMENTAL` settings require an explicit, persisted opt-in; `NEEDS_STANDBY`
  settings stop recording, confirm Standby, apply, verify (SD format) and restart recording,
  re-applying the microphone. SD format and factory reset need a typed confirmation, warn about
  data loss and offer "Download SOS + LOCK first". `Flicker`, `Net`, `Wi-Fi` changes and
  `action=flash` are not reachable.
- **Media3 1.11.1 or newer is required.** Media3 1.9.x has an RTSP H.264 regression for frames
  split across several RTP packets (androidx/media#3121), which makes the camera live stream stall.

Debug builds keep the test camera override out of the way: tap the version pill five times on the
connect screen to reveal **Developer options**. The same card has a debug live RTSP URL for a local
MediaMTX/ffmpeg test stream.

## Apple apps (iOS and macOS)

One SwiftUI codebase and one Xcode target serve both Apple platforms; only three things are
per-platform: the shell (`TabView` on iOS, `NavigationSplitView` on macOS), the export destination
(Photos on iOS, a save panel on macOS) and the `AVPlayerLayer` host view. Everything else is shared
with the Android app through the Kotlin core:

- **Connect** — health check, clock sync on connect, and a warm connect screen with the same copy as
  Android. The Local Network prompt is explained before the first request.
- **Library** — the four folders, day grouping, sorting, thumbnails (video `skip` box and photo
  JPEGs) and a recording badge for the clip currently being written.
- **Playback** — `AVPlayer` streams the MP4 directly from the camera, with a custom seek bar,
  10-second skips and a black immersive player. The clip being recorded is not playable.
- **Downloads** — resumable `Range` downloads with a `.part` file, progress on the tile, and
  "Save to Photos" (iOS) or "Save As…" (macOS).
- **Camera** — status, mic, snapshot, lock, record toggle and clock sync.
- **Settings** — the shared `SettingsCatalog`, the experimental opt-in, standby settings and the
  typed-confirmation SD format / factory reset flows, including "Download SOS + LOCK first".

`appleApp/CardvDashcam/Model/AppModel.swift` is the session and screen model;
`shared/src/appleMain/.../AppleCamera.kt` is the only Swift-facing Kotlin facade. Kotlin suspend
functions import into Swift as `async throws`, so no SKIE plugin is involved.

Apple live view is still open: `AVPlayer` has no RTSP. The VLCKit-versus-own-client decision is the
same M4 spike described in [docs/PLAN.md](docs/PLAN.md).

### Installing on an iPhone (one-year signature)

Device builds need a team from the paid Apple Developer Program. The team ID lives in an untracked
`appleApp/Signing.local.xcconfig`, so the repository stays usable by contributors and CI:

```sh
cp appleApp/Signing.local.xcconfig.example appleApp/Signing.local.xcconfig
# ten-character team id from: security find-identity -v -p codesigning
```

Then connect the iPhone, unlock it and trust the Mac; on iOS 16+ enable **Settings → Privacy &
Security → Developer Mode**. Press **Run** in Xcode, or use the script:

```sh
xcrun devicectl list devices
tools/apple/install-device.sh <device-udid>
```

The script builds **Release** (always `192.168.42.1`, no test override), signs with automatic
provisioning, installs and launches. A development profile is valid for about one year: when it
expires the app stops launching, and re-running the script renews it. The bundle id stays the same,
so downloads and settings survive the reinstall.

If Xcode reports `PLA Update available`, accept the current Program License Agreement in your
developer account and try again.

For the simulator or a headless run, a debug build reads `CARDV_BASE_URL` (and `CARDV_LIVE_URL`,
`CARDV_START_SECTION=library|camera|settings`) from the environment, connects on launch, and shows
the address under Developer options. `-openFirstClip` and `-openFirstPhoto` launch arguments open a
clip or snapshot directly for smoke tests:

```sh
xcodebuild -project appleApp/CardvDashcam.xcodeproj -scheme CardvDashcam \
  -sdk macosx CODE_SIGNING_ALLOWED=NO build
CARDV_BASE_URL=http://127.0.0.1:8080 \
  ~/Library/Developer/Xcode/DerivedData/*/Build/Products/Debug/CardvDashcam.app/Contents/MacOS/CardvDashcam
```

## Documentation

- [docs/PROTOCOL.md](docs/PROTOCOL.md) — the camera's HTTP/RTSP protocol, with every quirk we found
- [docs/PLAN.md](docs/PLAN.md) — architecture (Kotlin Multiplatform + native UI) and milestones
- [tools/probe/](tools/probe/README.md) — Python scripts to probe a camera
- [tools/fake-camera/](tools/fake-camera/README.md) — fake camera for development and tests

## Disclaimer

Not affiliated with Geely or any camera manufacturer. Some camera settings can make the dashcam's Wi-Fi
interface hang until the car is restarted; the app only exposes settings verified to be safe unless you enable
experimental settings. Use at your own risk.

## License

[MIT](LICENSE)
