# Implementation plan

Apps for the Geely Atlas / Atlas Pro factory dashcam (and other AIT / MStar "CarDV" Wi-Fi dashcams)
on **Android, iOS and macOS**, from one repository.

Everything the apps send to the camera is specified in [PROTOCOL.md](PROTOCOL.md). If the plan and the
protocol disagree, the protocol wins.

## 1. Goals

| | v1 (MVP) | later |
|---|---|---|
| Connect | Find camera at `192.168.42.1`, health check, clock sync | Join the camera Wi-Fi from inside the app |
| Library | Loop / SOS / LOCK / Photo lists, thumbnails, sort by date | Map view from the G-sensor/GPS boxes (`gsea`/`gsen`) |
| Playback | Stream MP4 straight from the camera with seeking | Frame-by-frame, export a trimmed clip |
| Download | Resumable, background, save to gallery / Photos / Finder | Auto-download new SOS/LOCK clips on connect |
| Live view | RTSP `liveRTSP/av2` (720p + audio) | Snapshot from live view |
| Controls | Mic on/off, snapshot, lock current clip, record stop/start | |
| Settings | Status, SD card space, firmware; `AWB`, `EV`, `DateLogoStamp` | Other settings once verified one by one; SD format; factory reset |

**Non-goals:** firmware upload (`action=flash`), Wi-Fi name/password changes, rear camera (not present).

## 2. Architecture

Kotlin Multiplatform: one shared core, native UI per platform.

```
            ┌──────────────── shared/ (Kotlin Multiplatform) ────────────────┐
            │ protocol   CgiClient · KvParser · DirXmlParser · ResultCode    │
            │ domain     Camera · Clip · Library · Settings · Status         │
            │ features   ClockSync · Thumbnails · DownloadQueue · Controls   │
            │ safety     CommandQueue (1 in flight) · SettingsCatalog        │
            │ platform   expect: NetworkBinder, FileSink, Clock, Logger      │
            └─────────────────────────────────────────────────────────────────┘
                  │ Kotlin/JVM                        │ Kotlin/Native framework (XCFramework)
     ┌────────────┴───────────┐             ┌─────────┴──────────────────────────┐
     │ androidApp/            │             │ appleApp/ (one Xcode project)      │
     │ Jetpack Compose        │             │ SwiftUI, multiplatform target      │
     │ Media3 ExoPlayer       │             │   iOS + macOS share ~90% of views  │
     │   HTTP MP4 + RTSP      │             │ AVPlayer (HTTP MP4)                │
     │ WorkManager downloads  │             │ own RTSP client (live, shared)     │
     │ MediaStore export      │             │ URLSession background downloads    │
     └────────────────────────┘             └────────────────────────────────────┘
```

### What is shared vs native

| Concern | Where | Notes |
|---|---|---|
| HTTP commands, XML/kv parsing, result codes | shared | Ktor client (OkHttp engine on Android, Darwin engine on Apple) |
| Command serialization, timeouts, retry | shared | **Critical** – see §4 |
| Clip model, library merge/sort, today/older grouping | shared | |
| Thumbnail extraction (range `0-65535`, JPEG at offset 60) | shared | Returns `ByteArray`; platform decodes and caches |
| Settings catalog with safety levels | shared | Single source for all three UIs |
| Download queue state (what, progress, resume offset) | shared | Byte transfer done by platform for background support |
| Network binding to the no-internet Wi-Fi | platform (`expect/actual`) | Android needs explicit binding; Apple mostly not |
| Video playback, live decoding | platform | AVPlayer / Media3; the RTSP protocol is shared |
| UI, navigation, notifications, file export | platform | |

### Shared API sketch

```kotlin
class CameraClient(baseUrl: String = "http://192.168.42.1", engine: HttpClientEngine) {
    suspend fun health(): Health                         // get Camera.Menu.EV, Camera.Preview.MJPEG.status.*
    suspend fun status(): CameraStatus                   // get Camera.* → typed
    suspend fun list(folder: Folder): List<Clip>         // DCIM | SOS | LOCK | Photo, paginated count=100
    suspend fun thumbnail(clip: Clip): ByteArray?        // Range 0-65535, slice skip box
    suspend fun syncClock(now: LocalDateTime)            // TimeSettings
    suspend fun video(op: VideoOp)                       // record | capture | rec_emer | mute | unmute
    suspend fun setSetting(s: Setting, v: String)        // only if SettingsCatalog allows it
    fun clipUrl(clip: Clip): String
    fun liveUrl(stream: LiveStream = AV2): String
}
```

Exposed to Swift with [SKIE](https://skie.touchlab.co) so `suspend` → `async` and `Flow` → `AsyncSequence`.

## 3. Repository layout

```
cardv-dashcam/
├── README.md · LICENSE (MIT) · .gitignore · .editorconfig
├── docs/
│   ├── PROTOCOL.md            camera protocol (source of truth)
│   ├── PLAN.md                this file
│   ├── COMPATIBILITY.md       cameras/firmware strings reported to work
│   ├── RELEASE.md             version bump, signing, store/notarization checklists
│   └── releases/              hand-written release notes per tag
├── fastlane/metadata/android/ F-Droid/store description, icon and changelog
├── shared/                    Kotlin Multiplatform module
│   └── src/{commonMain,commonTest,androidMain,appleMain}/kotlin/…
├── androidApp/                Android app (Compose)
├── appleApp/
│   ├── CardvDashcam.xcodeproj one project, iOS + macOS destinations
│   └── CardvDashcam/          SwiftUI sources (Shared/, iOS/, macOS/)
├── tools/
│   ├── probe/                 Python scripts used to reverse-engineer the camera
│   └── fake-camera/           Python fake camera for development without a car (§6)
├── gradle/ · build.gradle.kts · settings.gradle.kts · gradlew
└── .github/workflows/         CI (§8)
```

## 4. Camera safety rules (implemented in `shared`, enforced for every platform)

The camera's CGI server is single-threaded and can hang until power-cycle (see PROTOCOL.md → *Why Flicker killed
the camera*). The shared core must make it impossible for a UI to do something dangerous by accident.

1. **One command in flight.** A `CommandQueue` (Mutex) serializes all `Config.cgi` requests. File downloads and
   RTSP are separate and may run in parallel (verified).
2. **Generous timeouts, no early abort.** `set` requests: 15 s timeout (firmware uses up to 6 s); never cancel a
   `set` mid-flight because the user navigated away.
3. **Health check after every write.** If `get Camera.Menu.EV` fails afterwards, the camera is marked *hung* and the
   UI shows "Restart the car to reset the dashcam" instead of retrying.
4. **Settings catalog with safety levels:**
   - `SAFE` – verified on this camera: `TimeSettings`, `Video=*`, `AWB`, `EV`, `DateLogoStamp`.
   - `NEEDS_STANDBY` – refused while recording (`709`): `SD0`, `FactoryReset`, `VideoClipTime`, … → flow stops
     recording first, then restores it.
   - `EXPERIMENTAL` – untested, can hang: hidden unless the user enables "Experimental settings".
   - `FORBIDDEN` – never sent: `Flicker`, `Net`, `WiFi`, `action=flash`.
5. **Destructive actions** (format, factory reset, delete) require a typed confirmation and warn that SOS/LOCK
   clips will be lost; offer "Download SOS + LOCK first".
6. **Re-apply mic state** after any record restart (camera resets `SoundRecord` to OFF).
7. **Case-exact folder names** (`LOCK`, not `Lock`): unknown names silently return the loop list.

## 5. Platform specifics

### Android (minSdk 26, target latest)
- **No-internet Wi-Fi:** `ConnectivityManager.requestNetwork(TRANSPORT_WIFI)` → use `network.socketFactory` for
  OkHttp and ExoPlayer, or `bindProcessToNetwork()` while the camera screen is open. Otherwise traffic goes to mobile data.
- Optional in-app join: `WifiNetworkSpecifier` (Android 10+).
- Cleartext HTTP: `network_security_config.xml` allowing `192.168.42.1` only.
- Playback: Media3 `ProgressiveMediaSource` (HTTP, moov-first → instant seek); live: `RtspMediaSource` with
  `setForceUseRtpTcp(true)`.
- Downloads: WorkManager + `Range` resume; save via MediaStore (`Movies/CarDV`).

### iOS (16+) and macOS (13+) — one SwiftUI codebase
- `NSLocalNetworkUsageDescription` (local network permission prompt); macOS sandbox
  `com.apple.security.network.client`.
- iOS routes `192.168.42.0/24` to Wi-Fi even when Wi-Fi has no internet; verify cellular fallback doesn't interfere.
- Optional in-app join on iOS: `NEHotspotConfiguration` (needs Hotspot Configuration entitlement).
- **Playback verified (M3.0):** AVPlayer opens a real H.264 MP4 served as `CONTENT-TYPE: video/mpeg`
  and seeks over `Range`, so the resource-loader fallback is not needed. The fake camera serves the
  same kind of playable fixture, so playback can be tested without a car.
- **Swift interop:** Kotlin suspend functions import as `async throws`, so no SKIE plugin is used.
  Kotlin default arguments are not exported, so `shared/src/appleMain/.../AppleCamera.kt` holds the
  only factory facade (client, controls, settings, clock, LOCK re-poll).
- **Live view (M4):** AVPlayer has no RTSP, so live view uses the own RTSP/RTP-over-TCP client in
  `shared` (ported from `tools/probe/ait_probe4.py`). H.264 access units go to
  `AVSampleBufferDisplayLayer` (VideoToolbox) and AAC to `AVSampleBufferAudioRenderer`; a render
  synchronizer keeps the tracks together. There is no VLCKit dependency. Spike numbers are under
  *M4 result* below.
- Downloads: resumable `URLSession` `Range` transfers into `Documents/CarDV`; export to Photos (iOS)
  or a save panel (macOS). Background `URLSession` is still a follow-up.
- **Signing:** a paid Developer Program team signs development builds for about one year. The team
  ID stays in `appleApp/Signing.local.xcconfig` (gitignored); `tools/apple/install-device.sh` builds,
  installs and launches a Release app on a connected iPhone. Re-run it yearly to renew; the App
  Store remains the no-expiry distribution route for later.
- Shell: `TabView` (iOS) and `NavigationSplitView` (macOS); export and the two layer hosts
  (`AVPlayerLayer` for playback, `AVSampleBufferDisplayLayer` for live view) are the only
  per-platform views.

## 6. Fake camera (development without a car)

`tools/fake-camera/fake_camera.py` – standard-library Python server that mimics the camera:
- `Config.cgi` `get` / `dir` / `set` with responses copied from real probe output, including quirks
  (uppercase headers, `703` with HTTP 200, unknown folder → loop list, LOCK list delay).
- Serves a small playable H.264 fixture with the real box layout
  (`ftyp | skip(JPEG at 60) | moov | mdat`) and Range support. `make_sample_media.py` rebuilds it
  with ffmpeg and patches the chunk offsets after the skip box is inserted.
- Fault injection: `--hang-on Flicker` (blocks forever), `--slow`, `--drop`, to test the safety rules.
- RTSP: the fake camera has no live stream. `start-live-stream.sh` runs MediaMTX on port 8554 and
  publishes an ffmpeg test pattern (H.264 720p + AAC 32 kHz), which the debug live URL points at.
- Used by shared tests (JVM), Android emulator (`10.0.2.2`), iOS simulator / macOS (`localhost`).

## 7. Milestones

| # | Milestone | Done when |
|---|---|---|
| M0 | Repo scaffolding | Gradle KMP + Android app + Xcode project build empty apps; CI green; README, LICENSE |
| M1 | Shared core | Parsers, `CameraClient`, `CommandQueue`, `SettingsCatalog`, thumbnails; unit tests on recorded fixtures; fake camera v1 |
| M2 | Android MVP | Connect + network binding, library (4 folders), thumbnails, playback with seek, resumable download, clock sync |
| M3.0 | Apple playback spike | AVPlayer plays a camera MP4 (or fallback chosen) on iOS and macOS |
| M3 | iOS + macOS MVP | Same features as M2 in SwiftUI |
| M4 | Live view | RTSP spike decided (VLCKit vs own client); live view on all three |
| M5 | Controls & status | Mic, snapshot, lock (with LOCK re-poll), record toggle, status / SD card screen |
| M6 | Settings | `SAFE` settings UI; experimental toggle; format SD flow (stop recording → format → verify → resume) |
| M7 | Release | One version source, signed APK/AAB on GitHub Releases with checksums, app icon, COMPATIBILITY.md + issue template; iOS cable install and macOS source build documented |

M2 and M3 can run in parallel once M1 is done.

**Status:** M0–M6 are done on Android and Apple. Apple live view (M4) uses the own shared RTSP
client, not VLCKit. The Apple implementation lives in `appleApp/CardvDashcam/` (`Model/` for the
session, downloads and live model, `Live/` for the AVFoundation decoder, `UI/` for the shared
SwiftUI screens). It is verified against the fake camera and a MediaMTX stream on macOS and in the
iOS simulator.

M7 is complete for the chosen distribution. Android **1.0.0** is published on GitHub Releases as a
signed APK (and AAB) with `SHA256SUMS`; `version.xcconfig` is the single version source and R8/resource
shrinking is on. iOS and macOS are built from source by users over the cable/local Xcode; TestFlight,
a notarized macOS DMG, F-Droid and Google Play are intentionally not part of the current plan. The app
icon, privacy manifest, [RELEASE.md](RELEASE.md), [COMPATIBILITY.md](COMPATIBILITY.md) and camera
report template are in place. The manual real-car test passed. Apple live view (M4) is merged on
`master` and will ship in the next release; `v1.0.0` predates it.

**M3.0 result:** a real H.264 MP4 served with `Content-Type: video/mpeg` plays in AVPlayer on macOS
and in the iOS simulator, so the camera's MIME type is not a blocker. The fake camera's fixture is
now playable, including its `skip` thumbnail box.

**M4 result (live view):** decision — own client. VLCKit 4.0 (unstable build 2026-09-29) was measured
on macOS against a local MediaMTX/ffmpeg stream: 115 ms from play to the `playing` state, one video
and one audio track, 1280x720 video output and a decoded snapshot, and 52 ms to stop. Against the
same stream the own client reached the first frame in 216 ms in the iOS simulator and 220 ms–1.8 s on
macOS (first-frame time is GOP-bound: the test encoder sends an IDR every 1–2 s; the camera sends one
about every 0.3 s). It streams 30 fps, ~2 Mbit/s video, AAC 32 kHz stereo at ~64 kbit/s, 0 lost RTP
packets, and stops in 24–27 ms. VLCKit would add an 880 MB source archive / 2.8 GB XCFramework and an LGPL dynamic
dependency; the own client is ~600 lines in `shared`, has unit and integration tests, and can later
replace Media3 RTSP on Android too. Full method and logs: [M4-SPIKE.md](M4-SPIKE.md).

## 8. Testing & CI

- **Unit (shared, commonTest):** parsers against real responses from `tools/probe` reports (sanitized fixtures);
  `CommandQueue` ordering and timeout behaviour; settings safety rules.
- **Integration:** shared tests against the fake camera (JVM), including `--hang-on Flicker`.
- **UI smoke:** Android instrumented test on emulator + fake camera; XCUITest on iOS simulator.
- **On the real car (manual checklist per release):** connect, lists, play, download, live, clock, mic, lock.
- **GitHub Actions:** `ubuntu` – shared tests, Android build + lint; `macos` – shared Apple tests,
  `xcodebuild` iOS Simulator + macOS. The Android release workflow runs on `v*` tags or manual dispatch
  and builds a signed APK/AAB plus a GitHub Release with `SHA256SUMS`. The optional Apple workflow is
  manual-only and is not part of the current distribution. Pull requests and forks never receive release
  secrets.

## 9. Open questions / risks

| Risk | Mitigation |
|---|---|
| AVPlayer rejects `video/mpeg` MP4 | **Resolved in M3.0:** a real H.264 MP4 plays fine, in AVFoundation and in the simulator |
| RTSP on Apple needs VLCKit or own decoder | **Resolved in M4:** own shared RTSP client + AVSampleBufferDisplayLayer, no VLCKit |
| Other CarDV firmware differs (paths, folders, IP) | Make base URL and folder list configurable; COMPATIBILITY.md; probe scripts for users |
| Untested settings can hang the camera | Safety levels, experimental toggle, health check, clear recovery message |
| Brand names in store listings | Repo/app name stays generic ("CarDV Dashcam"); Geely only as "works with" |
| Release secrets leak into a build or fork | Release workflows are tag/manual only; PR CI uses no secrets; signing files are gitignored and decoded into `$RUNNER_TEMP` |
| Release notes claim Apple live view before M4 | **Resolved:** `v1.0.0` notes exclude it; M4 is merged and later releases can include it |

Decisions taken: display name "CarDV Dashcam", Android minSdk 26, iOS 16+, macOS 13+, signed Android APK
on GitHub Releases, iOS installed from Xcode over the cable, macOS built from source. TestFlight, a
notarized macOS DMG, F-Droid and Google Play are intentionally not part of the current plan.
