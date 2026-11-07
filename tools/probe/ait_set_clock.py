#!/usr/bin/env python3
"""
Set the dashcam clock to this computer's local time (one write: property=TimeSettings).

    python3 ait_set_clock.py [--ip 192.168.42.1] [--dry-run]

TimeSettings is handled directly by the firmware (no UI-task wait), so it cannot
hang the command server the way Flicker did. The current clip may be split in two.
"""
import argparse
import datetime
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dashcam_probe import http_request  # noqa: E402


def cgi(ip, q, timeout=10):
    r = http_request(ip, 80, "/cgi-bin/Config.cgi?" + q, timeout=timeout, max_bytes=20000)
    return r.get("status"), r.get("body_bytes", b"").decode("utf-8", "replace").strip(), r.get("ms"), r.get("error")


def cam_time(ip):
    _, body, _, _ = cgi(ip, "action=get&property=Camera.TimeDate")
    for line in body.splitlines():
        if line.startswith("Camera.TimeDate="):
            v = line.split("=", 1)[1]  # 2022/7/20 21/35/18
            try:
                d, t = v.split(" ")
                return datetime.datetime(*map(int, d.split("/")), *map(int, t.split("/"))), v
            except Exception:
                return None, v
    return None, body


ap = argparse.ArgumentParser()
ap.add_argument("--ip", default="192.168.42.1")
ap.add_argument("--dry-run", action="store_true")
a = ap.parse_args()

_, body, _, err = cgi(a.ip, "action=get&property=Camera.Preview.MJPEG.status.record")
if not body.startswith("0"):
    sys.exit(f"Command API not healthy ({body or err!r}). Power-cycle the camera and run ait_health.py first.")
print("recording state:", body.splitlines()[-1])

before, raw = cam_time(a.ip)
now = datetime.datetime.now()
print(f"camera clock : {raw}")
print(f"this computer: {now:%Y/%m/%d %H:%M:%S}")

value = now.strftime("%Y$%m$%d$%H$%M$%S")
q = f"action=set&property=TimeSettings&value={value}"
print(f"\nrequest: GET /cgi-bin/Config.cgi?{q}")
if a.dry_run:
    sys.exit("dry run - nothing sent")

status, body, ms, err = cgi(a.ip, q, timeout=15)
print(f"response ({ms} ms): {body!r} {err or ''}")
time.sleep(2)
after, raw = cam_time(a.ip)
print(f"camera clock now: {raw}")
if after:
    diff = (after - datetime.datetime.now()).total_seconds()
    print("RESULT:", "OK - clock set" if abs(diff) < 60 else f"clock NOT set (off by {diff / 86400:.1f} days)")
_, body, _, _ = cgi(a.ip, "action=get&property=Camera.Preview.MJPEG.status.record")
print("recording state after:", body.splitlines()[-1] if body else "?")
