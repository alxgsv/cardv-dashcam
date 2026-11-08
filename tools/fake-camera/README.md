# Fake camera

`fake_camera.py` is a standard-library-only Python server that behaves like the Geely / AIT CarDV
dashcam described in [docs/PROTOCOL.md](../../docs/PROTOCOL.md). It lets you develop and test the
shared core without a car.

## Run

```sh
python3 tools/fake-camera/fake_camera.py
# fake camera listening on http://127.0.0.1:8080
```

Then point the app or a test at it:

| Client | Base URL |
|---|---|
| Desktop / iOS simulator | `http://127.0.0.1:8080` |
| Android emulator | `http://10.0.2.2:8080` |
| Physical device on the same LAN | `http://<your-computer-ip>:8080` |

The server binds to `127.0.0.1` by default, so a phone cannot reach it. For a physical device
start it with `--host 0.0.0.0` and allow incoming connections if the macOS firewall asks:

```sh
python3 tools/fake-camera/fake_camera.py --host 0.0.0.0 --port 8080
```

In a debug Android build, open the connect screen, enter that URL under **Debug camera address** and
press **Apply**. Release builds always use `192.168.42.1`; debug builds permit cleartext to the fake
camera while the release policy allows only the camera address.

Options:

```sh
python3 tools/fake-camera/fake_camera.py --port 8081 --loop-files 12 --sos-files 7
python3 tools/fake-camera/fake_camera.py --slow 500          # delay every Config.cgi request
python3 tools/fake-camera/fake_camera.py --drop              # close the connection, no response
python3 tools/fake-camera/fake_camera.py --hang-on Flicker   # block the CGI server forever
```

`--hang-on` can be repeated and accepts any property name sent with `action=set`. It reproduces the
real camera's Flicker failure: after the request arrives, every `Config.cgi` request blocks until
the fake-camera process is killed.

## What it implements

- `GET /cgi-bin/Config.cgi`:
  - `action=get` with exact properties and `.*` wildcards (`Camera.*`, `Camera.Menu.*`, …)
  - `action=dir` with `count` / `from` pagination and `<amount>` per page
  - `action=set` for the documented settings, `Video=` operations and `TimeSettings`
- Folder lists: `DCIM` (loop), `SOS`, `LOCK`, `Photo` — exact case; unknown names such as `Lock`
  fall back to the loop list, as on the real camera.
- Media files under `/SD/MOVIE`, `/SD/SOS`, `/SD/LOCK` and `/SD/PHOTO`, with `Range` and `HEAD`
  support and the camera's `video/mpeg` content type for MP4.
- A small **playable** H.264 sample MP4 shaped like the real recordings:
  `ftyp (36) | skip (JPEG at offset 60) | moov | mdat`, so thumbnails, Range requests and playback
  can all be tested.
- Camera quirks: HTTP 200 with a non-zero result code in the first body line, uppercase response
  headers, `709` while recording for standby-only settings, and a recording restart resetting
  `Camera.Menu.SoundRecord` to `OFF`.

## Media fixture

`media/sample.mp4` is a three-second 640x360 H.264 test pattern with a real JPEG in the `skip` box,
and `media/sample.jpg` is the same frame used for `/SD/PHOTO`. The script rebuilds both, including
the chunk-offset patch that keeps the MP4 decodable after the `skip` box is inserted:

```sh
python3 tools/fake-camera/make_sample_media.py   # requires ffmpeg
```

If the files are missing, the server falls back to its old protocol-only stub (no decodable video).

## Live RTSP stream

The fake camera implements the HTTP API only. Live view needs a real RTSP server, so
`start-live-stream.sh` runs [MediaMTX](https://github.com/bluenviron/mediamtx) on port 8554 and
publishes an ffmpeg test pattern into it:

```sh
tools/fake-camera/start-live-stream.sh
# publishing 1280x720 H.264 + AAC 32 kHz on rtsp://127.0.0.1:8554/live
```

It needs `ffmpeg` and `mediamtx` on `PATH` (`brew install ffmpeg mediamtx`). The stream matches the
camera's `liveRTSP/av2`: H.264 Main 1280x720 at 30 fps, ~2 Mbit/s, and AAC 32 kHz stereo. RTP is
served over TCP only, like the camera. Use the debug **Live RTSP URL** under Developer options, or:

```sh
CARDV_LIVE_URL=rtsp://127.0.0.1:8554/live CARDV_BASE_URL=http://127.0.0.1:8080 CARDV_START_SECTION=live <debug app binary>
```

`RTSP_PORT` and `RTSP_PATH` override the port and path. Ctrl-C stops ffmpeg and MediaMTX.

## Tests

`./gradlew :shared:testDebugUnitTest` starts this server on a free port for the integration tests
(`shared/src/androidUnitTest/.../FakeCameraIntegrationTest.kt`). Python 3 is required; those tests
are skipped when `python3` is not available. The Flicker test uses a 400 ms write timeout and a
300 ms health-check timeout, so a hung server cannot hang CI.

## Limitations

No RTSP/live stream of its own (use `start-live-stream.sh` above), no delete/format behaviour
beyond returning the documented result codes, and no authentication (the real camera has none
either). The video fixture is a short test pattern, not
a real recording. It is a protocol and safety test double, not a production media server.
