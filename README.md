# CarDV Dashcam

Open-source **Android, iOS and macOS** app for the **Geely Atlas / Atlas Pro factory dashcam (DVR)** and other
**AIT / MStar "CarDV" Wi-Fi dashcams**: browse, play and download recordings, live view, snapshots, lock clips,
clock sync and settings — no cloud, no account.

> Status: protocol fully reverse-engineered, shared core (M1) and the Android MVP (M2) implemented;
> Apple apps in development. See the [plan](docs/PLAN.md).

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
python3 tools/fake-camera/fake_camera.py --port 8080
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
  `mdat` on this camera, so seeking works without downloading the clip first.
- **Download** — WorkManager downloads with `Range` resume and a partial file that survives process
  death, then exports through MediaStore to `Movies/CarDV` (photos: `Pictures/CarDV`). Android 8–9
  asks for `WRITE_EXTERNAL_STORAGE`; Android 10+ needs no storage permission.
- **Not in M2** — live view (M4), camera controls (M5) and settings screens (M6).

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
