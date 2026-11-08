# Design

CarDV Dashcam is an app for the Geely Atlas / Atlas Pro factory dashcam (and other AIT / MStar "CarDV"
Wi-Fi dashcams) on **Android, iOS and macOS**, built from one repository: a shared Kotlin
Multiplatform core with native UI per platform.

Everything the apps send to the camera is specified in [PROTOCOL.md](PROTOCOL.md). If this design and
the protocol disagree, the protocol wins.

## 1. Goals

| | In the app | Later |
|---|---|---|
| Connect | Find camera at `192.168.42.1`, health check, clock sync | Join the camera Wi-Fi from inside the app |
| Library | Loop / SOS / LOCK / Photo lists, thumbnails, sort by date | Map view from the G-sensor/GPS boxes (`gsea`/`gsen`) |
| Playback | Stream MP4 straight from the camera with seeking | Frame-by-frame, export a trimmed clip |
| Download | Resumable, background, save to gallery / Photos / Finder | Auto-download new SOS/LOCK clips on connect |
| Live view | RTSP `liveRTSP/av2` (720p + audio) | Snapshot from live view |
| Controls | Mic on/off, snapshot, lock current clip, record stop/start | |
| Settings | Status, SD card space, firmware; `AWB`, `EV`, `DateLogoStamp`; every catalog setting with its safety level; SD format and factory reset | Other settings once verified one by one |

**Non-goals:** firmware upload (`action=flash`), Wi-Fi name/password changes, rear camera (not present).

## 2. Architecture

Kotlin Multiplatform: one shared core, native UI per platform.

```
            ┌──────────────── shared/ (Kotlin Multiplatform) ────────────────┐
            │ protocol   CgiClient · KvParser · DirXmlParser · ResultCode    │
            │ domain     Camera · Clip · Library · Settings · Status         │
            │ features   ClockSync · Thumbnails · DownloadQueue · Controls   │
            │ live       RTSP/RTP-over-TCP client · H.264/AAC depacketizer   │
            │ safety     CommandQueue (1 in flight) · SettingsCatalog        │
            │ platform   expect: NetworkBinder, FileSink, Clock, Logger      │
            └────────────────────────────────────────────────────────────────┘
                  │ Kotlin/JVM                    │ Kotlin/Native framework
     ┌────────────┴───────────┐         ┌─────────┴──────────────────────────┐
     │ androidApp/            │         │ appleApp/ (one Xcode project)      │
     │ Jetpack Compose        │         │ SwiftUI, multiplatform target      │
     │ Media3 ExoPlayer       │         │   iOS + macOS share ~90% of views  │
     │   HTTP MP4 + RTSP      │         │ AVPlayer (HTTP MP4)                │
     │ WorkManager downloads  │         │ own RTSP client (live, shared)     │
     │ MediaStore export      │         │ URLSession background downloads    │
     └────────────────────────┘         └────────────────────────────────────┘
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
| RTSP/RTP-over-TCP live client | shared | Feeds `AVSampleBufferDisplayLayer` on Apple; Android uses Media3 RTSP for now |
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

Kotlin suspend functions import into Swift as `async throws`, so no SKIE plugin is involved. Kotlin
default arguments are not exported, so `shared/src/appleMain/.../AppleCamera.kt` holds the only factory
facade for Swift (client, controls, settings, clock, LOCK re-poll).

## 3. Repository layout

```
cardv-dashcam/
├── README.md · LICENSE (MIT) · .gitignore · .editorconfig
├── version.xcconfig           the only version source (see RELEASE.md)
├── docs/
│   ├── PROTOCOL.md            camera protocol (source of truth)
│   ├── DESIGN.md              this file
│   ├── COMPATIBILITY.md       cameras/firmware strings reported to work
│   ├── RELEASE.md             version bump, signing, release workflow
│   ├── M4-SPIKE.md            live-view spike (VLCKit vs own client) and its numbers
│   └── releases/              hand-written release notes per tag
├── shared/                    Kotlin Multiplatform module
│   └── src/{commonMain,commonTest,androidMain,appleMain}/kotlin/…
├── androidApp/                Android app (Compose)
├── appleApp/
│   ├── CardvDashcam.xcodeproj one project, iOS + macOS destinations
│   └── CardvDashcam/          SwiftUI sources (Model/, Live/, UI/)
├── tools/
│   ├── probe/                 Python scripts used to reverse-engineer the camera
│   ├── fake-camera/           Python fake camera for development without a car (§6)
│   ├── apple/                 install and icon scripts
│   └── release/               version check script
├── gradle/ · build.gradle.kts · settings.gradle.kts · gradlew
└── .github/workflows/         CI and release workflows (§7)
```

## 4. Camera safety rules (implemented in `shared`, enforced for every platform)

The camera's CGI server is single-threaded and can hang until power-cycle (see PROTOCOL.md → *Why Flicker
killed the camera*). The shared core must make it impossible for a UI to do something dangerous by
accident.

1. **One command in flight.** A `CommandQueue` (Mutex) serializes all `Config.cgi` requests. File downloads and
   RTSP are separate and may run in parallel (verified).
2. **Generous timeouts, no early abort.** `set` requests: 15 s timeout (firmware uses up to 6 s); never cancel a
   `set` mid-flight because the user navigated away.
3. **Health check after every write.** If `get Camera.Menu.EV` fails afterwards, the camera is marked *hung* and
   the UI shows "Restart the car to reset the dashcam" instead of retrying.
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
  OkHttp and ExoPlayer, or `bindProcessToNetwork()` while the camera screen is open. Without it, traffic goes
  to mobile data. Ktor, ExoPlayer and the download OkHttp client are all bound to that network, and the
  camera clock is synced on every connect (`TimeSettings` through the shared core).
- **Cleartext HTTP:** `network_security_config.xml` allowing `192.168.42.1` only.
- **Library:** DCIM (loop), SOS, LOCK and Photo folders; MP4 thumbnails from the embedded `skip` box, photo
  thumbnails from the JPEG; sort newest/oldest/name and group by day.
- **Playback:** Media3 `ProgressiveMediaSource` over HTTP; `moov` is before `mdat` on this camera, so seeking
  works without downloading the clip first. The player opens in landscape with a custom seek bar and turns
  the system bars off.
- **Live view:** Media3 `RtspMediaSource` on `liveRTSP/av2` (720p H.264 + AAC), RTP forced over TCP with
  the RTSP socket bound to the camera Wi-Fi. Connecting/live/stopped/error states and a start/stop control.
  It is a separate stream from HTTP playback and does not stop the camera recording. Media3 1.11.1 or newer
  is required: 1.9.x has an RTSP H.264 regression for frames split across several RTP packets
  (androidx/media#3121), which makes the camera live stream stall.
- **Downloads:** WorkManager + `Range` resume with a partial file that survives process death; export through
  MediaStore to `Movies/CarDV` (photos: `Pictures/CarDV`). Android 8–9 asks for `WRITE_EXTERNAL_STORAGE`;
  Android 10+ needs no storage permission.
- **Camera controls:** status card (recording, SD state, free space, firmware, current file, camera clock)
  plus mic mute/unmute, snapshot (`Video=capture`), lock current clip (`Video=rec_emer`, then a LOCK re-poll
  because the folder catches up late) and record stop/start. Success, refusal and hung-camera outcomes are
  shown in place; a hung camera is never retried.
- **Settings:** rows and values come from the shared `SettingsCatalog`. `SAFE` settings are shown by default;
  `EXPERIMENTAL` settings require an explicit, persisted opt-in; `NEEDS_STANDBY` settings stop recording,
  confirm Standby, apply, verify (SD format) and restart recording, re-applying the microphone. SD format
  and factory reset need a typed confirmation, warn about data loss and offer "Download SOS + LOCK first".
  `Flicker`, `Net`, `Wi-Fi` changes and `action=flash` are not reachable.
- **Debug aids:** the connect screen has a **Debug camera address** field; tap the version pill five times
  to reveal **Developer options** with the test address and a debug live RTSP URL (release builds always use
  `192.168.42.1`). Debug builds allow cleartext to the test address; the release policy only allows cleartext
  to `192.168.42.1`.

### iOS (16+) and macOS (13+) — one SwiftUI codebase

One SwiftUI codebase and one Xcode target serve both Apple platforms; only a few things are per-platform:
the shell (`TabView` on iOS, `NavigationSplitView` on macOS), the export destination (Photos on iOS, a save
panel on macOS) and the two AVFoundation layer hosts (`AVPlayerLayer` for playback,
`AVSampleBufferDisplayLayer` for live view). Everything else is shared with the Android app through the
Kotlin core.

- **Project:** `appleApp/CardvDashcam.xcodeproj` builds the Kotlin framework through Gradle
  (`:shared:embedAndSignAppleFrameworkForXcode`) before compiling Swift, so there is no XCFramework to build
  or carry around first. `Model/AppModel.swift` is the session and screen model, `Model/LiveModel.swift`
  owns a live session, `Live/` holds the AVFoundation decoder, `UI/` the shared SwiftUI screens.
  `Assets.xcassets` carries both app icons (generated by `tools/apple/make-app-icon.sh`) and
  `PrivacyInfo.xcprivacy` declares no tracking or collected data.
- **Network:** `NSLocalNetworkUsageDescription` prompt explained before the first request; iOS routes
  `192.168.42.0/24` to Wi-Fi even when Wi-Fi has no internet; the macOS target is sandboxed with
  `com.apple.security.network.client`. Optional in-app join on iOS: `NEHotspotConfiguration` (needs the
  Hotspot Configuration entitlement).
- **Connect:** health check, clock sync on connect, and a warm connect screen with the same copy as
  Android.
- **Library:** the four folders, day grouping, sorting, thumbnails (video `skip` box and photo JPEGs) and a
  recording badge for the clip currently being written.
- **Playback:** `AVPlayer` streams the MP4 directly from the camera, with a custom seek bar, 10-second skips
  and a black immersive player. The clip being recorded is not playable. Verified: AVPlayer opens a real
  H.264 MP4 served as `Content-Type: video/mpeg` and seeks over `Range`, so no resource-loader fallback is
  needed.
- **Live view:** AVPlayer has no RTSP, so live view uses the shared RTSP/RTP-over-TCP client (ported from
  `tools/probe/ait_probe4.py`). H.264 access units go to `AVSampleBufferDisplayLayer` (VideoToolbox) and AAC
  to `AVSampleBufferAudioRenderer`; a render synchronizer keeps the tracks together. Connecting/live/stopped/
  error states, a start/stop control and stream statistics. No VLCKit dependency. Decision and numbers:
  [M4-SPIKE.md](M4-SPIKE.md).
- **Downloads:** resumable `URLSession` `Range` transfers into `Documents/CarDV`, progress on the tile, and
  "Save to Photos" (iOS) or "Save As…" (macOS). Background `URLSession` is still a follow-up.
- **Camera and settings:** status, mic, snapshot, lock, record toggle and clock sync; the shared
  `SettingsCatalog` with the experimental opt-in, standby settings and the typed-confirmation SD format /
  factory reset flows, including "Download SOS + LOCK first".
- **Signing:** a paid Developer Program team signs development builds for about one year. The team id stays
  in the gitignored `appleApp/Signing.local.xcconfig`; `tools/apple/install-device.sh` builds, installs and
  launches a Release app on a connected iPhone. Re-run it yearly to renew. If Xcode reports
  `PLA Update available`, accept the current Program License Agreement and retry.
- **Headless smoke runs:** debug builds read `CARDV_BASE_URL`, `CARDV_LIVE_URL`,
  `CARDV_START_SECTION=library|live|camera|settings` and `CARDV_LIVE_STOP_AFTER` from the environment,
  connect on launch, and show the address under Developer options. `-openFirstClip` and `-openFirstPhoto`
  launch arguments open a clip or snapshot directly.

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

## 7. Testing and CI

- **Unit (shared, commonTest):** parsers against real responses from `tools/probe` reports (sanitized
  fixtures); `CommandQueue` ordering and timeout behaviour; settings safety rules.
- **Integration:** shared tests against the fake camera (JVM), including `--hang-on Flicker`.
- **UI smoke:** Android instrumented test on emulator + fake camera; XCUITest on iOS simulator.
- **On the real car (manual checklist per release):** connect, lists, play, download, live, clock, mic, lock.
- **GitHub Actions:** `ubuntu` – shared tests, Android build + lint; `macos` – shared Apple tests,
  `xcodebuild` iOS Simulator + macOS. The Android release workflow runs on `v*` tags or manual dispatch and
  builds a signed APK/AAB plus a GitHub Release with `SHA256SUMS`. Pull requests and forks never receive
  release secrets.

## 8. Risks and decisions

| Risk | Mitigation |
|---|---|
| AVPlayer rejects `video/mpeg` MP4 | **Resolved:** a real H.264 MP4 plays fine, in AVFoundation and in the simulator |
| RTSP on Apple needs VLCKit or own decoder | **Resolved:** own shared RTSP client + AVSampleBufferDisplayLayer, no VLCKit |
| Other CarDV firmware differs (paths, folders, IP) | Make base URL and folder list configurable; COMPATIBILITY.md; probe scripts for users |
| Untested settings can hang the camera | Safety levels, experimental toggle, health check, clear recovery message |
| Release secrets leak into a build or fork | Release workflows are tag/manual only; PR CI uses no secrets; signing files are gitignored and decoded into `$RUNNER_TEMP` |

**Live view decision:** own client, not VLCKit. VLCKit 4.0 (unstable build 2026-09-29) was measured on
macOS against a local MediaMTX/ffmpeg stream: 115 ms from play to the `playing` state, one video and one
audio track, 1280x720 video output and a decoded snapshot, and 52 ms to stop. Against the same stream the
own client reached the first frame in 216 ms in the iOS simulator and 220 ms–1.8 s on macOS (first-frame
time is GOP-bound: the test encoder sends an IDR every 1–2 s; the camera sends one about every 0.3 s). It
streams 30 fps, ~2 Mbit/s video, AAC 32 kHz stereo at ~64 kbit/s, 0 lost RTP packets, and stops in 24–27 ms.
VLCKit would add an 880 MB source archive / 2.8 GB XCFramework and an LGPL dynamic dependency; the own
client is ~600 lines in `shared`, has unit and integration tests, and can later replace Media3 RTSP on
Android too. Full method and logs: [M4-SPIKE.md](M4-SPIKE.md).

Decisions taken: display name "CarDV Dashcam", Android minSdk 26, iOS 16+, macOS 13+, signed Android APK
on GitHub Releases, iOS installed from Xcode over the cable, macOS built from source.
