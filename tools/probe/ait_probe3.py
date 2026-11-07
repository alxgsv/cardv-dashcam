#!/usr/bin/env python3
"""
Stage 3 (read-only): live view check + hunt for the hidden second file list.

    python3 ait_probe3.py [--ip 192.168.42.1]

Needs ffmpeg/ffprobe for the live view part (brew install ffmpeg).
Output: ait3_report_<time>.txt and ait3_live_<name>.jpg next to this script.
"""
import argparse
import datetime
import os
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from dashcam_probe import http_request  # noqa: E402

STAMP = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
LOG = []


def log(m=""):
    print(m, flush=True)
    LOG.append(str(m))


def section(t):
    log("\n" + "=" * 78 + "\n" + t + "\n" + "=" * 78)


def cgi(ip, q, **kw):
    kw.setdefault("timeout", 8)
    kw.setdefault("max_bytes", 400000)
    r = http_request(ip, 80, "/cgi-bin/Config.cgi?" + q, **kw)
    return r.get("status"), r.get("body_bytes", b"").decode("utf-8", "replace"), r.get("ms")


def listing(ip, prop, fmt="all", count=16, frm=0):
    st, body, ms = cgi(ip, f"action=dir&property={prop}&format={fmt}&count={count}&from={frm}")
    if not body.strip().startswith("<"):
        return {"status": st, "raw": body[:200].replace("\n", " | ")}
    try:
        root = ET.fromstring(body.encode())
    except Exception as e:
        return {"status": st, "raw": body[:200], "err": str(e)}
    names = [f.findtext("name") for f in root.findall("file")]
    return {"status": st, "amount": root.findtext("amount"), "names": names, "ms": ms}


def hunt_lists(ip):
    section("1. WHICH dir 'property' / 'format' VALUES GIVE A DIFFERENT LIST?")
    st, body, _ = cgi(ip, "action=get&property=Camera.DBFileNum")
    log(f"Camera.DBFileNum -> {body.strip()!r}")
    base = listing(ip, "DCIM", count=100)
    base_names = set(base.get("names") or [])
    log(f"baseline DCIM: amount={base.get('amount')}, first={(base.get('names') or [None])[0]}")

    props = ["DCIM", "Normal", "NORMAL", "Photo", "PHOTO", "Pic", "Picture", "JPG", "Image",
             "Event", "EVENT", "Emr", "EMR", "Emer", "EMER", "Emergency", "Urgent", "URGENT",
             "Lock", "LOCK", "Locked", "Protect", "PROTECT", "RO", "ReadOnly",
             "Park", "PARK", "Parking", "PARKING", "Parked", "Motion", "MTD",
             "Manual", "MANUAL", "Share", "SHARE", "Capture", "Burst", "Timelapse", "TimeLapse",
             "Rear", "REAR", "Front", "FRONT", "Normal_Rear", "Event_Rear", "Parking_Rear",
             "Loop", "LOOP", "Movie", "MOVIE", "Video", "Rec", "Record", "All", "ALL",
             "Gsensor", "GSensor", "Collision", "Crash", "Sos", "SOS", "Thumb", "Mov", "TS", "Emergency_Rear",
             "DB1", "DB2", "1", "2", "0"]
    interesting = {}
    for p in props:
        r = listing(ip, p, count=100)
        names = set(r.get("names") or [])
        if "names" not in r:
            tag = f"no-xml {r.get('raw')!r}"
        elif names == base_names:
            tag = "same as DCIM"
        else:
            tag = f"DIFFERENT amount={r.get('amount')} sample={(r.get('names') or [])[:3]}"
            interesting[p] = r
        log(f"  {p:16} -> {tag}")

    log("\nformat= variants with property=DCIM:")
    for fmt in ["all", "mov", "mp4", "MP4", "avi", "ts", "jpeg", "jpg", "JPG", "video", "photo"]:
        r = listing(ip, "DCIM", fmt=fmt, count=100)
        names = set(r.get("names") or [])
        tag = ("same" if names == base_names else
               f"DIFFERENT amount={r.get('amount')} sample={(r.get('names') or [])[:3]}"
               if "names" in r else f"no-xml {r.get('raw')!r}")
        log(f"  format={fmt:6} -> {tag}")

    log("\nOther dir-like actions (read-only verbs only):")
    for q in ["action=reardir&property=DCIM&format=all&count=16&from=0",
              "action=dir&property=DCIM&format=all&count=16&from=0&cam=1",
              "action=dir&property=DCIM&format=all&count=16&from=0&cam=2",
              "action=dir&property=DCIM&format=all&count=16&from=0&type=event",
              "action=dir&property=DCIM&format=all&count=16&from=54",
              "action=dir&property=DCIM&format=all&count=16&from=100",
              "action=dir&property=DCIM&format=all&count=16&from=-16",
              "action=get&property=Camera.DBFileNum",
              "action=get&property=Camera.NormalDBSize",
              "action=get&property=Camera.EventDBSize",
              "action=get&property=Camera.ParkDBSize",
              "action=get&property=Camera.PhotoDBSize",
              "action=get&property=Camera.Menu.EmergencyVideo",
              "action=get&property=Camera.RecFileName",
              "action=get&property=Camera.Key",
              "action=get&property=Camera.Gsxyz"]:
        st, body, ms = cgi(ip, q)
        log(f"  {q}\n     -> [{st}] {body[:400].strip()!r}")

    log("\nStatic folder guesses (a 200 or a listing would reveal the layout):")
    for d in ["/SD/", "/SD/MOVIE/", "/SD/EVENT/", "/SD/EMR/", "/SD/EMER/", "/SD/LOCK/", "/SD/PARK/",
              "/SD/PARKING/", "/SD/PHOTO/", "/SD/PICTURE/", "/SD/NORMAL/", "/SD/MOVIE_R/", "/SD/REAR/",
              "/SD/URGENT/", "/SD/SHARE/", "/SD/MANUAL/", "/SD/DCIM/", "/SD/CARDV/", "/SD/THUMB/",
              "/SD/GPS/", "/SD/LOG/"]:
        r = http_request(ip, 80, d, timeout=5, max_bytes=20000)
        b = r.get("body_bytes", b"")
        log(f"  {d:16} -> {r.get('status')} len={len(b)} {b[:80]!r}")
    return interesting


def live(ip):
    section("2. LIVE VIEW (RTSP)")
    ffmpeg, ffprobe = shutil.which("ffmpeg"), shutil.which("ffprobe")
    if not ffmpeg:
        log("ffmpeg not found - skip. Install with: brew install ffmpeg")
        return
    _, b, _ = cgi(ip, "action=get&property=Camera.Menu.IsStreaming")
    log(f"before: {b.strip()!r}")
    for name in ("av4", "av2", "v1"):
        url = f"rtsp://{ip}:554/liveRTSP/{name}"
        for transport in ("tcp", "udp"):
            log(f"\n{url} ({transport})")
            if ffprobe:
                try:
                    p = subprocess.run([ffprobe, "-v", "error", "-rtsp_transport", transport, "-timeout", "8000000",
                                        "-show_entries", "stream=codec_name,codec_type,width,height,r_frame_rate,"
                                        "sample_rate,channels,profile", "-of", "compact", url],
                                       capture_output=True, text=True, timeout=25)
                    log("  ffprobe: " + (p.stdout + p.stderr).strip().replace("\n", "\n  ffprobe: "))
                except subprocess.TimeoutExpired:
                    log("  ffprobe: timeout")
            out = os.path.join(HERE, f"ait3_live_{name}_{transport}.jpg")
            t0 = time.time()
            try:
                p = subprocess.run([ffmpeg, "-v", "error", "-rtsp_transport", transport, "-timeout", "8000000",
                                    "-i", url, "-frames:v", "1", "-y", out],
                                   capture_output=True, text=True, timeout=25)
                ok = os.path.exists(out) and os.path.getsize(out) > 0
                log(f"  first frame: {'OK' if ok else 'FAILED'} in {time.time() - t0:.1f}s "
                    f"{(p.stderr or '').strip()[:300]}")
            except subprocess.TimeoutExpired:
                log(f"  first frame: timeout after {time.time() - t0:.1f}s")
            # measure ~6 s of stream: real fps / bitrate
            try:
                p = subprocess.run([ffmpeg, "-v", "info", "-rtsp_transport", transport, "-timeout", "8000000",
                                    "-i", url, "-t", "6", "-c", "copy", "-f", "null", "-"],
                                   capture_output=True, text=True, timeout=30)
                tail = [l for l in p.stderr.splitlines() if re.search(r"frame=|bitrate=|Stream #|error", l, re.I)]
                log("  6s capture: " + " | ".join(t.strip() for t in tail[-5:])[:600])
            except subprocess.TimeoutExpired:
                log("  6s capture: timeout")
            _, b, _ = cgi(ip, "action=get&property=Camera.Menu.IsStreaming")
            log(f"  IsStreaming now: {b.strip()!r}")
            if transport == "tcp" and name != "av4":
                break  # udp only tested on av4
    _, b, _ = cgi(ip, "action=get&property=Camera.Preview.MJPEG.status.*")
    log(f"\nrecording status after streaming: {b.strip()!r}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ip", default="192.168.42.1")
    a = ap.parse_args()
    log(f"AIT probe stage 3 {STAMP} -> {a.ip}")
    try:
        for fn in (hunt_lists, live):
            try:
                fn(a.ip)
            except Exception as e:
                import traceback
                log(f"!! {fn.__name__} crashed: {e}\n{traceback.format_exc()}")
    except KeyboardInterrupt:
        log("interrupted")
    finally:
        path = os.path.join(HERE, f"ait3_report_{STAMP}.txt")
        with open(path, "w") as f:
            f.write("\n".join(LOG))
        log(f"\nDone. Send back {path}")


if __name__ == "__main__":
    main()
