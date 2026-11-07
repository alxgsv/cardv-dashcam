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
     │ WorkManager downloads  │             │ VLCKit or own RTSP client (live)   │
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
| Video playback, live view | platform | Native players |
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
│   └── COMPATIBILITY.md       cameras/firmware strings reported to work
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
- **Playback risk:** camera sends `CONTENT-TYPE: video/mpeg` for MP4 files; AVPlayer may refuse it.
  Spike first (M3.0). Fallbacks: `AVAssetResourceLoaderDelegate` with a custom scheme that rewrites the type,
  or a tiny local HTTP proxy.
- **Live view:** AVPlayer has no RTSP. Options, decided in the M4 spike:
  1. VLCKit (MobileVLCKit / VLCKit) – fastest; LGPL, license-compatible with MIT when dynamically linked.
  2. Own RTSP/RTP-over-TCP client in `shared` (protocol already proven in `tools/probe/ait_probe4.py`) feeding
     H.264 to VideoToolbox (`AVSampleBufferDisplayLayer`). More work, no dependency, could also replace Media3 RTSP.
- Downloads: background `URLSession`; export to Photos (iOS) / `~/Movies/CarDV` (macOS).

## 6. Fake camera (development without a car)

`tools/fake-camera/fake_camera.py` – standard-library Python server that mimics the camera:
- `Config.cgi` `get` / `dir` / `set` with responses copied from real probe output, including quirks
  (uppercase headers, `703` with HTTP 200, unknown folder → loop list, LOCK list delay).
- Serves small sample MP4s with the same box layout (`ftyp | skip(JPEG at 60) | moov | mdat`) and Range support.
- Fault injection: `--hang-on Flicker` (blocks forever), `--slow`, `--drop`, to test the safety rules.
- RTSP: proxy a local test stream via `ffmpeg -re … -f rtsp` (MediaMTX) on port 8554.
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
| M7 | Release | Signed APK on GitHub Releases (+ F-Droid metadata), TestFlight, notarized macOS DMG; COMPATIBILITY.md + issue template |

M2 and M3 can run in parallel once M1 is done.

## 8. Testing & CI

- **Unit (shared, commonTest):** parsers against real responses from `tools/probe` reports (sanitized fixtures);
  `CommandQueue` ordering and timeout behaviour; settings safety rules.
- **Integration:** shared tests against the fake camera (JVM), including `--hang-on Flicker`.
- **UI smoke:** Android instrumented test on emulator + fake camera; XCUITest on iOS simulator.
- **On the real car (manual checklist per release):** connect, lists, play, download, live, clock, mic, lock.
- **GitHub Actions:** `ubuntu` – shared tests, Android build + lint; `macos` – shared Apple tests,
  `xcodebuild` iOS + macOS.

## 9. Open questions / risks

| Risk | Mitigation |
|---|---|
| AVPlayer rejects `video/mpeg` MP4 | M3.0 spike with resource-loader/proxy fallback |
| RTSP on Apple needs VLCKit or own decoder | M4 spike; own client is already prototyped in Python |
| Other CarDV firmware differs (paths, folders, IP) | Make base URL and folder list configurable; COMPATIBILITY.md; probe scripts for users |
| Untested settings can hang the camera | Safety levels, experimental toggle, health check, clear recovery message |
| Brand names in store listings | Repo/app name stays generic ("CarDV Dashcam"); Geely only as "works with" |

Decisions still open (defaults assumed above): app display name, minimum OS versions, whether to publish to
Google Play / App Store or GitHub-only at first.
