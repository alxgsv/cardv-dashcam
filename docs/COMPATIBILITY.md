# Compatibility

CarDV Dashcam talks to the **dashcam**, not to the car. A car badge does not identify the DVR: the same
AIT / MStar "CarDV" firmware is fitted under several brands, and the same car model can appear with a
different camera. What matters is the firmware and the network/filesystem layout below.

## Confirmed baseline

The protocol was reverse-engineered on the factory DVR of a **Geely Atlas / Atlas Pro** (also sold as
**Belgee X70**):

| Item | Value |
|---|---|
| SoC / firmware family | MStar MSC8339, AIT / CarDV, supplier `JJ_DVR` |
| Firmware string | `MS;ms8339DEW;LS01;JJ20302;00;Z3152;V1.0;GEELY_Z3152_V1.2;...` |
| Camera address | `http://192.168.42.1` (DHCP `/24`, no internet route) |
| Ports | 80 HTTP API + files, 554 RTSP live view |
| Folders | `DCIM` (loop), `SOS`, `LOCK`, `Photo` (exact case) |
| Live stream | RTSP `liveRTSP/av2`, 720p H.264 + AAC |
| Command API | `GET /cgi-bin/Config.cgi?...`, result code in the first line, HTTP always 200 |

The exact protocol and its quirks are in [PROTOCOL.md](PROTOCOL.md).

## Cars reported to use the same firmware family

This list comes from the firmware/model mapping in the project notes. The DVR fitted to a particular car
is the thing to verify; the model name alone is not proof.

| Car | Chinese name | Notes |
|---|---|---|
| Geely Atlas / Atlas Pro, Belgee X70 | 博越 / 博越Pro | Baseline camera; protocol reverse-engineered on this car |
| Geely Atlas 2024 (FX11) / Starray | 博越L | Same CarDV family; verify the firmware string |
| Geely Cityray | 博越COOL | Same CarDV family; verify the firmware string |
| Geely Coolray | 缤越 | Same CarDV family; verify the firmware string |
| Geely Preface | 星瑞 | Same CarDV family; verify the firmware string |
| Geely Galaxy L7 | 银河L7 | Same CarDV family; verify the firmware string |
| Ford Mustang Mach-E (China) | 福特电马 | Same CarDV family; verify the firmware string |

Standalone dashcams on the same firmware family (for example MStar MSC8339 models that take an
`SD_CarDV.bin` update) may work too, but often use a different IP or `/SD/...` layout and are not
supported by the 1.1.0 app without configuration.

## Tested on

This table records reproducible evidence, not marketing claims. Add a row only after running the app on
that configuration.

| Area | Platform / build | Camera | What was verified |
|---|---|---|---|
| Protocol | Python probe tools | Geely Atlas / Atlas Pro factory DVR, `MS;ms8339DEW;…;GEELY_Z3152_V1.2` | Reads, lists, MP4 layout, Range downloads, RTSP, safe settings (see `docs/PROTOCOL.md`) |
| Shared core | JVM `commonTest` | `tools/fake-camera` fixture | Parsers, command queue, safety rules, library merge/sort, clock, controls |
| Android app | Android emulator, fake camera | Fake camera | Connect/network binding, library, playback with seek, downloads, live view, controls, settings |
| iOS app | iOS Simulator, fake camera | Fake camera | Connect, library, playback, downloads, camera controls, settings |
| macOS app | macOS (arm64), fake camera | Fake camera | Connect, library, playback, downloads, camera controls, settings |
| Real-car app install | Installed app (owner report) | Geely Atlas / Atlas Pro baseline, `MS;ms8339DEW;…;GEELY_Z3152_V1.2` | Connect, lists, playback, downloads, live view, controls and settings reported working |

The owner reported a successful manual run on the baseline car. The exact phone platform was not
recorded; add a more specific row through the camera report template when available.

## Is my camera supported?

Your camera is likely compatible if:

- its Wi-Fi gives you an address like `192.168.42.x` and the camera is at `192.168.42.1`;
- `http://192.168.42.1/cgi-bin/Config.cgi?action=get&property=Camera.Menu.FWversion` returns a CarDV
  firmware string such as `0 OK Camera.Menu.FWversion=MS;ms8339DEW;…;GEELY_Z3152_V1.2;…`;
- `dir` lists `DCIM`, `SOS`, `LOCK` and `Photo`.

If any of those differ, the app will probably not find the camera in 1.1.0. Run the
[probe tools](../tools/probe/README.md) and open a
[camera report](https://github.com/alxgsv/cardv-dashcam/issues/new?template=camera-report.yml) with the
sanitized output.

## Different firmware

The app assumes the baseline IP, paths and result codes. Firmware that differs in IP, folder names, RTSP
path or CGI parameters is not supported until a compatible mode exists. Rear camera, firmware upload
(`action=flash`) and Wi-Fi name/password changes are explicit non-goals. See
[DESIGN.md §8](DESIGN.md) for the risk list and mitigations.
