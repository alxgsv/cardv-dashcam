# Live view decision — own RTSP client over VLCKit

Decision: **own RTSP/RTP-over-TCP client in `shared`**, not VLCKit.

This note records the evidence behind the choice, so the numbers can be re-checked later. The
implementation is in `shared/src/commonMain/kotlin/com/cardv/shared/live/` and
`appleApp/CardvDashcam/Live/`.

## Test stream

Both candidates were measured against the same local stream, because the fake camera has no RTSP:

```sh
tools/fake-camera/start-live-stream.sh
# MediaMTX 1.21.3 on :8554
# ffmpeg: H.264 Main 1280x720 30 fps ~2 Mbit/s, IDR every 30 frames (1 s),
#         AAC-LC 32 kHz stereo ~64 kbit/s, RTP over TCP only
```

The camera itself sends `liveRTSP/av2` as H.264 720p with AAC 32 kHz stereo, IDR about every
8 frames, and only accepts interleaved RTP. The test stream is the same shape but with a slower
keyframe interval, which makes first-frame numbers here pessimistic for the real camera.

Environment: Apple Silicon, Xcode 27.2, iOS 27.2 simulator, macOS 27.2.

## VLCKit

Version: VLCKit 4.0 unstable, build `VLCKit-4.0-20260929-1631` (the current SPM binary from
VideoLAN). The archive is 880 MB; the extracted `VLCKit.xcframework` is 2.8 GB and has slices for
iOS device, iOS simulator (arm64/x86_64), macOS (arm64/x86_64), tvOS, watchOS and visionOS.

Measured with a small macOS harness (`VLCMediaPlayer`, `:rtsp-tcp`, `:network-caching=300`,
`:clock-jitter=0`, `:clock-synchro=0`, video view attached, snapshot at 4 s):

| Metric | Result |
|---|---|
| play → `playing` state | **115 ms** |
| video track / audio track | 1 / 1 |
| video output size | **1280x720**, `hasVideoOut = yes` |
| decoded frame | snapshot 169,923 bytes |
| stop → `stopped` | **52 ms** |

Not measured on iOS: the framework's iOS and simulator slices exist, but the evaluation ran on macOS.
VLCKit is LGPL; shipping it means a dynamic framework, its license notices and an App Store review
of the bundled libVLC modules, plus a large binary. VLCKit 4 is still a pre-release.

## Own client

The shared client implements OPTIONS → DESCRIBE → SETUP (one per track, `RTP/AVP/TCP;unicast;
interleaved=…`) → PLAY, parses SDP, depacketises H.264 (single NAL, STAP-A, FU-A) and AAC
(RFC 3640 AAC-hbr), sends keep-alives and TEARDOWN. Swift builds a `CMVideoFormatDescription` from
SPS/PPS and feeds `AVSampleBufferDisplayLayer` (VideoToolbox); AAC goes to
`AVSampleBufferAudioRenderer`; an `AVSampleBufferRenderSynchronizer` keeps audio and video together.

Measured in the app with a MediaMTX stream:

| Metric | iOS simulator | macOS |
|---|---|---|
| start → first frame | **216 ms – 1.9 s** (GOP-bound) | **220 ms – 1.8 s** (GOP-bound) |
| video | 1280x720, **30 fps**, ~2.0–2.1 Mbit/s | 1280x720, **30 fps**, ~2.0 Mbit/s |
| audio | AAC 32 kHz stereo, **~62–65 kbit/s** | AAC 32 kHz stereo, **~61–66 kbit/s** |
| lost RTP packets | 0 | 0 |
| stop → stopped | **27 ms** | **24–26 ms** |
| reconnect/start | works after stop | works after stop |

First-frame time is dominated by waiting for the test encoder's next IDR (1 s interval); the
216/220 ms runs joined just before one, the 1.8–1.9 s runs just after one. On the camera an IDR is
about every 0.3 s, so the expected start is a few hundred milliseconds.

Audio was verified by the renderer accepting the AAC format (`format=ok`, config `0x12 0x90` =
AAC-LC 32 kHz stereo), by audio frames flowing in the stats (e.g. 63 AAC access units in 2 s) and by
CoreMedia's AudioQueue enqueue logs. The simulator and the Mac both played the stream.

The shared module has 113 JVM tests, including an in-process RTSP server that exercises
video/audio depacketisation, keep-alives, TEARDOWN and a refused DESCRIBE.

## Why the own client

- **No dependency**: no 880 MB archive / 2.8 GB XCFramework, no LGPL dynamic framework, no VLCKit
  release risk. The app keeps building for macOS and iOS from one codebase.
- **Comparable latency**: 216 ms to first frame in the simulator and 24–27 ms to stop are as good as
  VLCKit's 115 ms / 52 ms on macOS, before accounting for the camera's shorter GOP.
- **Protocol stays shared**: SDP, RTP, H.264/AAC framing and the session state machine live in
  `commonMain`; only decoding is platform code behind `LiveDecoding`. This is the boundary
  [DESIGN.md](DESIGN.md) describes, and the client can replace Media3 RTSP on Android later.
- **Testable**: protocol pieces are common unit tests; a JVM integration test runs a fake RTSP server
  in-process. VLCKit can only be tested through its player.
- VLCKit is the lower-risk choice for exotic streams (B-frames, trick modes), but the camera's stream
  is a fixed H.264 Main live feed with in-band SPS/PPS and no trick play, which the evaluation covered.

## Reproduce

```sh
tools/fake-camera/start-live-stream.sh

# Apple app (macOS or iOS simulator), fake camera for connect + MediaMTX for live:
CARDV_BASE_URL=http://127.0.0.1:8080 \
CARDV_LIVE_URL=rtsp://127.0.0.1:8554/live \
CARDV_START_SECTION=live \
CARDV_LIVE_STOP_AFTER=8 <debug app binary>

# then in Console/log:
log show --last 30s --info --debug --predicate 'subsystem == "com.cardv.dashcam"'
```
