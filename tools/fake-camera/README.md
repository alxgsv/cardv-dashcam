# Fake camera

`fake_camera.py` is a standard-library-only Python server that behaves like the Geely / AIT CarDV
dashcam described in [docs/PROTOCOL.md](../../docs/PROTOCOL.md). It lets you develop and test the
shared core without a car.

## Run

```sh
python3 tools/fake-camera/fake_camera.py
# fake camera listening on http://127.0.0.1:8080
```

Then point a test, probe or future app build at it:

| Client | Base URL |
|---|---|
| Desktop / iOS simulator | `http://127.0.0.1:8080` |
| Android emulator | `http://10.0.2.2:8080` |
| Physical device on the same LAN | `http://<your-computer-ip>:8080` |

The M1 apps are still scaffolds; these URLs are for the shared tests and probes. The Android app's
cleartext policy currently allows only `192.168.42.1`, so using the fake camera from the app will
need a debug override in M2.

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
- A small sample MP4 shaped like the real recordings:
  `ftyp (36) | skip (JPEG at offset 60) | moov | mdat`, so thumbnail extraction can be tested.
- Camera quirks: HTTP 200 with a non-zero result code in the first body line, uppercase response
  headers, `709` while recording for standby-only settings, and a recording restart resetting
  `Camera.Menu.SoundRecord` to `OFF`.

## Tests

`./gradlew :shared:testDebugUnitTest` starts this server on a free port for the integration tests
(`shared/src/androidUnitTest/.../FakeCameraIntegrationTest.kt`). Python 3 is required; those tests
are skipped when `python3` is not available. The Flicker test uses a 400 ms write timeout and a
300 ms health-check timeout, so a hung server cannot hang CI.

## Limitations

No RTSP/live stream, no real H.264 video, no delete/format behaviour beyond returning the documented
result codes, and no authentication (the real camera has none either). It is a protocol and safety
test double, not a media server.
