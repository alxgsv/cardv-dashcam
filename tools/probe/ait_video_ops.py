#!/usr/bin/env python3
"""
Test the low-risk `Video=` operations one by one (each asks y/N first).

    python3 ait_video_ops.py [--ip 192.168.42.1]

These are handled with a 6 s firmware timeout, so they cannot hang the CGI server forever.
  1. mute / unmute   - microphone in recordings; original state is restored
  2. rec_emer        - manual SOS clip; checks a new file appears in the SOS list
  3. capture         - snapshot photo while recording; checks the Photo list
  4. record (x2)     - stop recording, then start again; verifies it is recording at the end
Output: ait_videoops_<time>.txt next to this script.
"""
import argparse
import datetime
import os
import sys
import time
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from dashcam_probe import http_request  # noqa: E402

STAMP = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
LOG = []
IP = "192.168.42.1"


def log(m=""):
    print(m, flush=True)
    LOG.append(str(m))


def cgi(q, timeout=15):
    r = http_request(IP, 80, "/cgi-bin/Config.cgi?" + q, timeout=timeout, max_bytes=300000)
    return r.get("body_bytes", b"").decode("utf-8", "replace").strip(), r.get("ms"), r.get("error")


def get(prop):
    body, _, _ = cgi(f"action=get&property={prop}", timeout=8)
    for line in body.splitlines():
        if line.startswith(prop + "="):
            return line.split("=", 1)[1]
    return None


def healthy():
    return get("Camera.Menu.EV") is not None


def status():
    return get("Camera.Preview.MJPEG.status.record")


def listing(prop):
    body, _, _ = cgi(f"action=dir&property={prop}&format=all&count=100&from=0", timeout=15)
    try:
        root = ET.fromstring(body.encode())
        return [(f.findtext("name"), f.findtext("time"), f.findtext("format")) for f in root.findall("file")]
    except Exception:
        return []


def send(value):
    body, ms, err = cgi(f"action=set&property=Video&value={value}")
    short = body.replace("\n", " | ") or err
    log(f"   SET Video={value:9} -> {short} ({ms} ms)")
    time.sleep(1.5)
    if not healthy():
        time.sleep(5)
        if not healthy():
            raise RuntimeError(f"command API stopped responding after Video={value}")
    return body.split("\n")[0] if body else None


def ask(q):
    try:
        return input(f"\n>>> {q} [y/N] ").strip().lower() == "y"
    except EOFError:
        return False


def test_mute():
    log("\n== 1. mute / unmute ==")
    orig = get("Camera.Menu.SoundRecord")
    log(f"   SoundRecord before: {orig}")
    for v in ("unmute", "mute"):
        code = send(v)
        log(f"   SoundRecord now: {get('Camera.Menu.SoundRecord')}  (code {code}, recording={status()})")
    want = "unmute" if orig == "ON" else "mute"
    if get("Camera.Menu.SoundRecord") != orig:
        log(f"   restoring original ({want})")
        send(want)
    log(f"   SoundRecord final: {get('Camera.Menu.SoundRecord')} (original {orig})")


def test_rec_emer():
    log("\n== 2. rec_emer (manual SOS clip) ==")
    before = {n for n, _, _ in listing("SOS")}
    log(f"   DBFileNum before: {get('Camera.DBFileNum')}, SOS listed: {len(before)}")
    code = send("rec_emer")
    log(f"   code {code}; waiting 45 s for the clip to be written ...")
    for i in range(9):
        time.sleep(5)
        log(f"   +{(i + 1) * 5}s DBFileNum={get('Camera.DBFileNum')} recording={status()}")
    after = listing("SOS")
    new = [f for f in after if f[0] not in before]
    log(f"   new SOS files: {new or 'none'}")


def test_capture():
    log("\n== 3. capture (snapshot) ==")
    before = {n for n, _, _ in listing("Photo")}
    code = send("capture")
    time.sleep(3)
    after = listing("Photo")
    new = [f for f in after if f[0] not in before]
    log(f"   code {code}; new photos: {new or 'none'}; DBFileNum={get('Camera.DBFileNum')}")
    if new:
        url = new[0][0]
        r = http_request(IP, 80, url, headers={"Range": "bytes=0-3"}, timeout=8)
        log(f"   GET {url} -> {r.get('status')} first bytes {r.get('body_bytes', b'')[:4].hex(' ')}")


def test_record():
    log("\n== 4. record toggle (stop, then start) ==")
    log(f"   before: {status()}  file={get('Camera.RecFileName')}")
    send("record")
    time.sleep(2)
    log(f"   after 1st toggle: {status()}")
    send("record")
    time.sleep(3)
    st = status()
    log(f"   after 2nd toggle: {st}  file={get('Camera.RecFileName')}")
    if st != "Recording":
        log("   not recording - sending one more toggle")
        send("record")
        time.sleep(3)
        st = status()
        log(f"   now: {st}")
    log("   RESULT: " + ("recording again ✓" if st == "Recording" else "!! NOT RECORDING - check the camera"))


def main():
    global IP
    ap = argparse.ArgumentParser()
    ap.add_argument("--ip", default=IP)
    IP = ap.parse_args().ip
    log(f"Video ops test {STAMP} -> {IP}")
    try:
        if not healthy():
            log("Command API not healthy - power-cycle the camera first.")
            return
        log(f"recording={status()}  SoundRecord={get('Camera.Menu.SoundRecord')}  DBFileNum={get('Camera.DBFileNum')}")
        for name, fn in (("mute/unmute (restored afterwards)", test_mute),
                         ("rec_emer: create a manual SOS clip", test_rec_emer),
                         ("capture: take a snapshot photo", test_capture),
                         ("record: stop and restart recording", test_record)):
            if ask(f"Test {name}?"):
                fn()
        log(f"\nFinal: recording={status()}  SoundRecord={get('Camera.Menu.SoundRecord')}  "
            f"DBFileNum={get('Camera.DBFileNum')}")
    except RuntimeError as e:
        log(f"\n!! STOPPED: {e}. Power-cycle the camera.")
    except KeyboardInterrupt:
        log("\ninterrupted")
    finally:
        p = os.path.join(HERE, f"ait_videoops_{STAMP}.txt")
        open(p, "w").write("\n".join(LOG))
        log(f"\nDone. Send back {p}")


if __name__ == "__main__":
    main()
