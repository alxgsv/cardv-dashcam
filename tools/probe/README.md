# Probe tools

Python 3 scripts (standard library only) used to reverse-engineer the camera. Run them on a computer
connected to the dashcam's Wi-Fi. Each writes a report next to itself.

| Script | Changes the camera? | What it does |
|---|---|---|
| `dashcam_probe.py` | no | Generic first scan: network, all ports, known dashcam APIs, file download test |
| `ait_probe.py` | no | AIT/CarDV deep scan: web UI, `get` properties, file lists, MP4 layout, speed, RTSP |
| `ait_probe3.py` | no | Folder/list variants; live view via ffmpeg |
| `ait_probe4.py` | no | SOS folder + live view via a pure-Python RTSP client |
| `ait_health.py` | no | Is the command API alive? Recording state, key settings |
| `ait_sos_today.py` | no | Lists today's SOS and loop clips |
| `ait_set_clock.py` | **yes** – clock | Sets the camera clock to the computer's time |
| `ait_video_ops.py` | **yes** – asks first | mute/unmute, lock clip, snapshot, record stop/start |
| `ait_settings_probe.py` | **yes** – asks first | Tries settings; can hang the camera until restart |

## Sharing results

Reports contain your camera's MAC addresses, Wi-Fi name and clip names/times. Remove those before posting
them in an issue.

## macOS note

Homebrew binaries (ffmpeg/ffplay) get `No route to host` until allowed in
System Settings → Privacy & Security → Local Network.
