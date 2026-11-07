#!/usr/bin/env python3
"""Read-only health check of the AIT dashcam: is the command API alive, is it recording, current settings.

    python3 ait_health.py [--ip 192.168.42.1]
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dashcam_probe import http_request, tcp_state  # noqa: E402

EXPECTED = {  # values seen before the settings probe
    "Camera.Menu.AWB": "AUTO", "Camera.Menu.DateLogoStamp": "ON", "Camera.Menu.EV": "EV0",
    "Camera.Menu.Flicker": "50Hz", "Camera.Menu.GSensor": "MIDDLE", "Camera.Menu.MTD": "LOW",
    "Camera.Menu.ParkingMonitor": "ON", "Camera.Menu.VideoClipTime": "5MIN",
    "Camera.Menu.SoundRecord": None, "Camera.Menu.PowerOffDelay": "5SEC",
}

ap = argparse.ArgumentParser()
ap.add_argument("--ip", default="192.168.42.1")
ip = ap.parse_args().ip

print(f"port 80: {tcp_state(ip, 80, 2)}   port 554: {tcp_state(ip, 554, 2)}")
r = http_request(ip, 80, "/index.html", timeout=6)
print(f"static page: {r.get('status')} {r.get('error') or ''}")
r = http_request(ip, 80, "/cgi-bin/Config.cgi?action=get&property=Camera.*", timeout=10, max_bytes=100000)
body = r.get("body_bytes", b"").decode("utf-8", "replace")
print(f"command API: HTTP {r.get('status')} first line {body.splitlines()[0] if body else r.get('error')!r}")
kv = dict(l.split("=", 1) for l in body.splitlines()[2:] if "=" in l)
if not kv:
    print("\n!! Command API is NOT working. Power-cycle the camera (car ignition off, wait 30 s, on) and rerun.")
    sys.exit(1)
for k in ("Camera.Preview.MJPEG.status.record", "Camera.Preview.MJPEG.status.mode", "Camera.RecFileName",
          "Camera.TimeDate", "Camera.Menu.SD0", "Camera.Menu.CardInfo.Free", "Camera.File.Total"):
    print(f"{k:40} {kv.get(k)}")
print("\nSettings vs. before the probe:")
for k, v in EXPECTED.items():
    now = kv.get(k)
    flag = "" if v is None or now == v else f"   <-- CHANGED (was {v})"
    print(f"   {k:32} {now}{flag}")
