# Dashcam Wi-Fi protocol — Geely Atlas / Atlas Pro factory DVR (AIT / MStar "CarDV" firmware)

Status: reverse-engineered on 2026-11-07 (probe scripts in `tools/probe/`). ✅ = confirmed, ❓ = still unknown.

## Device
- Firmware string: `MS;ms8339DEW;LS01;JJ20302;00;Z3152;V1.0;GEELY_Z3152_V1.2;...` (Mstar MSC8339 SoC, supplier `JJ_DVR`).
- HTTP server: `AIT Multimedia Network Solution, UPnP/1.0 devices/1.6.19`.
- Wi-Fi AP, camera at **192.168.42.1**, DHCP /24, no internet route. ✅
- Open ports: **80** (HTTP API + files), **554** (RTSP live), 56789 (unknown; closes idle connections after 5 s). ✅
- No authentication, no session, no heartbeat required for reads. ✅
- Camera clock drifts/resets (was at 2022) → file names/times are camera-local. Fix: `TimeSettings` on every connect ✅.

## Command API — `GET /cgi-bin/Config.cgi?...` ✅
Response is `text/plain`, **HTTP status is always 200**; the first line is the real result code:
```
0
OK
Key=Value
Key=Value
```
```
703
Unknown CGI-PARAM: Property
```
- `action=get&property=<Key>` or wildcard `<Prefix>.*` (wildcard needs the `.*`; `Camera.Menu` without it → 703).
- Useful reads:
  | property | meaning |
  |---|---|
  | `Camera.*` | everything below in one call |
  | `Camera.Menu.*` | settings (VideoClipTime=5MIN, GSensor, ParkingMonitor, MTD, EV, AWB, Flicker, SD0=READY, IsStreaming…) |
  | `Camera.Menu.CardInfo.*` | `Free` / `Total` in MB |
  | `Camera.Preview.MJPEG.status.*` | `mode=Videomode`, `record=Recording` |
  | `Camera.TimeDate` | `2022/7/20 21/12/49` (camera clock) |
  | `Camera.RecFileName` | file being recorded now (`SD:\MOVIE\…MP4`) — exclude from download list |
  | `Camera.File.Total`, `Camera.DBFileNum` (`54+125` ❓) | counts |
  | `Camera.Menu.FWversion` | firmware |
### Writes — `action=set&property=<Name>&value=<V>`
Source of truth: MStar Mercury5 CarDV SDK (same firmware family, `ms8339`), public at
github.com/longyanjun2020/SDK_pulbic → `Mercury5/proj/sc/core/system/net/api/src/netapp.c` (`cgi_handler[]`),
values in `project/Dvr_CarDV_SDK/UI/src/ahc_wireless.c` (`CGI_MENU_*`). Geely's build (`JJ_DVR`) is customized,
so treat this as a map, not a guarantee.

**Why `Flicker` killed the camera:** most settings are forwarded to the UI task and the CGI server waits for an
answer with timeout `0` = forever. If Geely's UI doesn't handle that message, the single-threaded CGI server
blocks until power-cycle. `EV`, `AWB`, `DateLogoStamp` are handled; `Flicker` is not.
→ Every untested "UI-msg ∞" property can brick the session until reboot. Test one at a time, never in the app blindly.

Result codes: `0` OK · `701` illegal param · `702` unknown action · `703` unknown property · `704` unknown param ·
`705` missing param · `709` refused/invalid state (e.g. while recording) · `710–712` auth.

| property | values | handler | status on this camera |
|---|---|---|---|
| `TimeSettings` | `YYYY$MM$DD$hh$mm$ss` (local time, validated) | direct (no wait) | ✅ **works** (2026-11-07): `0 OK` in 0.8 s, clock correct, recording continued. App: sync on every connect |
| `SD0` | `format` | direct, synchronous; **709 while recording** → stop first with `Video=record` | ❓ destructive |
| `FactoryReset` | `Camera` | direct; 709 while recording; SDK code looks buggy (may be a no-op) | ❓ |
| `Video=mute` / `unmute` | mic in recordings → `Camera.Menu.SoundRecord` OFF/ON | UI-msg, 6 s | ✅ instant. ⚠️ restarting recording resets mic to OFF → re-apply |
| `Video=capture` | snapshot while recording → `/SD/PHOTO/YYYYMMDD_HHMMSS_NNNNNN.JPG`, listed by `dir property=Photo` | UI-msg, 6 s | ✅ |
| `Video=record` | toggle Recording ↔ Standby (new clip starts on resume) | UI-msg, 6 s | ✅ |
| `Video=rec_emer` | manual lock: the clip being recorded is **moved to `/SD/LOCK/<same name>`** (verified 415 MB clip). Not `/SD/SOS/`. | UI-msg, 6 s | ✅ listed by `dir property=LOCK` (exact uppercase) once the camera catches up — empty right after the lock, so the app should re-poll |
| `Video=rec_short` | ? | UI-msg, 6 s | untested |
| `Net` | `reset`, `findme` | direct | not needed |
| `AWB` | `AUTO DAYLIGHT CLOUDY FLUORESCENT1-3 INCANDESCENT` | UI-msg ∞ | ✅ accepted |
| `EV` | `EVN200 EVN167 EVN150 EVN133 EVN100 EVN067 EVN050 EVN033 EV0 EVP033 … EVP200` | UI-msg ∞ | ✅ accepted |
| `DateLogoStamp` | `ON OFF` (Geely-specific) | ? | ✅ accepted |
| `Flicker` | `50Hz 60Hz` | UI-msg ∞ | ❌ **hangs CGI – never send** |
| `GSensor` | `OFF LEVEL0…LEVEL4` (camera reports `MIDDLE` → Geely values differ) | UI-msg ∞ | ❓ risky |
| `MTD` | `OFF LOW MIDDLE HIGH` | UI-msg ∞ | ❓ risky |
| `VideoClipTime` / `LoopingVideo` | `OFF 6SEC 1MIN 2MIN 3MIN 5MIN 10MIN 25MIN 30MIN` | UI-msg ∞; 709 while recording | ❓ risky |
| `PowerOffDelay` | `OFF 5SEC 10SEC 15SEC 30SEC 60SEC 2MIN 3MIN 5MIN 15MIN 30MIN 60MIN` | UI-msg ∞ | ❓ risky |
| `TVSystem` | `NTSC PAL` | UI-msg ∞ | ❓ risky |
| `UpsideDown` | `Normal Upsidedown Mirror Colrow` | UI-msg ∞ | ❓ risky |
| `HDR`, `Q-SHOT` | `ON OFF`; 709 while recording | UI-msg ∞ | ❓ risky |
| `SpotMeter`, `StatusLights`, `SoundIndicator` (`Beep`) | `ON OFF` | UI-msg ∞ | ❓ risky |
| `PowerSaving` | `OFF 1MIN 2MIN 5MIN` | UI-msg ∞ | ❓ risky |
| `Videores` | `1080P30 1080P30fps 720P60 720P30 …`; 709 while recording | UI-msg ∞ | ❓ risky |
| `ParkingMonitor`, `SoundRecord` | Geely-specific, not in SDK (`SoundRecord` maybe via `Video=mute/unmute`) | ? | ❓ |

Other actions: `action=del&property=<path>` (delete file), `action=setcamid&property=…&value=front|rear`,
`action=flash` (firmware upload — never), `/thumb/<path>` (thumbnail; 404 on this camera).
- Several `property=…&value=…` pairs may be chained, but do **one per request**, sequentially (SDK FAQ).

## File list ✅
`GET /cgi-bin/Config.cgi?action=dir&property=DCIM&format=all&count=<n>&from=<offset>`
```xml
<DCIM>
<file>
  <name>/SD/MOVIE/20220707_162655_002088.MP4</name>
  <format size="1920x1080" fps="30" time="300.2">MP4</format>
  <size>489291776</size>      <!-- rounded up to 32 KB clusters; real size via Content-Range -->
  <attr>RW</attr>
  <time>2022-07-07 16:26:55</time>
</file>
...
<amount>16</amount>          <!-- number returned in THIS page -->
</DCIM>
```
- Oldest first. Accepts `count=100` (≈0.7 s for 54 files). Stop paginating when `amount < count`.
- Root tag echoes `property`. Unknown names silently fall back to the normal list (case-insensitive):
  | property | contents |
  |---|---|
  | `DCIM` (default) | loop recordings, `/SD/MOVIE/` (54 files = first number of `Camera.DBFileNum=54+125`) |
  | `SOS` | emergency clips, `/SD/SOS/`, **125 files** (= second number), 30 s each (~49 MB), 6.1 GB ✅ |
  | `Photo` | snapshots, `/SD/PHOTO/*.JPG` (empty until `Video=capture`) |
  | `LOCK` | manually locked clips, `/SD/LOCK/` ✅ (`Lock` in other case → falls back to DCIM!) |
- SOS list order is **not chronological** (2026 entries first, then 2022) — sort by name/time in the app.
- `format=all|mp4` → videos; `mov|avi|jpeg` → empty; others → empty body.
- `action=reardir` → `721 Device hardware error` (rear camera API exists but no rear camera attached?).
- Folders: `/SD/MOVIE` (loop), `/SD/SOS` (automatic events, own counter `_0000NN`), `/SD/LOCK` (manual lock via `Video=rec_emer`, keeps loop name), `/SD/PHOTO`. Directory URLs themselves return 404 (no HTTP index). Name: `YYYYMMDD_HHMMSS_<seq>.MP4`. 1080p30, 5-min clips ≈ 490 MB.

## File download ✅
`GET http://192.168.42.1/SD/MOVIE/<name>.MP4` — the `<name>` path directly.
- `Range` supported (`206`, `Content-Range: bytes 0-1023/<total>`), `HEAD` supported.
- Speed ≈ **2.2 MB/s (17.5 Mbit/s)**; a 5-min clip takes ~3.7 min. Video bitrate ≈ 13 Mbit/s, so direct streaming playback is feasible but with little margin.
- Parallel connections OK (list + 2 downloads at once). Works while camera is recording.
- Headers are UPPERCASE (`CONTENT-RANGE`) — parse case-insensitively.

## MP4 layout ✅
```
ftyp (36) | skip (~137 KB, thumbnail) | moov (~150 KB) | mdat | gsea (28) | gsen (8)
```
- `moov` before `mdat` → ExoPlayer/Media3 can stream & seek directly from the HTTP URL.
- **Thumbnail**: inside the `skip` box at file offset 36. Skip payload starts at 44:
  `e0 46 d8 01 | e0 23 ca 01 | 04 00 | ff ff | <u32 BE jpeg_len> | JPEG…`
  → JPEG begins at offset **60**, length = BE u32 at offset **56** (480×272, with date stamp).
  App: `Range: bytes=0-65535`, slice `[60, 60+len)`.
- `gsea`/`gsen` trailing boxes: G-sensor data ❓ format.

## Live view ✅
RTSP on port 554, **RTP over TCP (interleaved) works**, no auth:
```
OPTIONS  rtsp://192.168.42.1:554/liveRTSP/v1        -> Public: DESCRIBE, SETUP, TEARDOWN, PLAY, PAUSE, GET_PARAMETER
DESCRIBE …/liveRTSP/v1                              -> SDP, Content-Base: …/liveRTSP/v1/
SETUP    …/liveRTSP/v1/track0  Transport: RTP/AVP/TCP;unicast;interleaved=0-1
                                                    -> Session: <id>; timeout = 60
PLAY     …/liveRTSP/v1/        Range: npt=0.000-
TEARDOWN …/liveRTSP/v1/
```
| stream | content |
|---|---|
| `liveRTSP/v1` | video only |
| `liveRTSP/av2` | video + AAC 32 kHz stereo (track1, ~127 kbit/s) — best for the app |
| `liveRTSP/av4` | video + L16 PCM 8 kHz |

- Video: H.264 **Main** 1280×720 (SDP says Baseline 42401F — ignore; in-band SPS/PPS sent with every IDR), ~29 fps, ~2 Mbit/s, IDR every ~8 frames → first picture in 0.3–0.5 s. 0 RTP loss over Wi-Fi.
- Session timeout 60 s → keep-alive (`GET_PARAMETER`/`OPTIONS`) needed; Media3 RTSP does this automatically.
- Streaming does not stop recording (`record=Recording` stays). `Camera.Menu.IsStreaming` stays `NO`.
- Media3: `RtspMediaSource.Factory().setForceUseRtpTcp(true)`.
- `/cgi-bin/liveMJPEG` (old web UI) → 404, not needed.
- Rear camera: `Source.Totals=2` but `reardir` → `721 Device hardware error`; no rear camera attached.

## macOS testing gotcha
Homebrew binaries (ffmpeg/ffplay) get `No route to host` until allowed in
System Settings → Privacy & Security → Local Network.
