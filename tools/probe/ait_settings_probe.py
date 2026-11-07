#!/usr/bin/env python3
"""
Discover which settings can be changed on the AIT dashcam, and with which values.

    python3 ait_settings_probe.py [--ip 192.168.42.1]

Tiers (each later tier asks y/N before it runs):
  A. No-op writes: set every setting to the value it ALREADY has. Learns which
     property names the camera accepts for writing. Nothing changes.
  B. Value discovery: for each setting, try candidate values one by one, read
     back what the camera stored, then restore the original value.
     Recording may restart briefly. The script checks that everything is restored.
  C. Set the camera clock to this computer's time (tries known AIT formats).

NEVER sent: SD format, factory reset, file delete, Wi-Fi name/password, network reset.
Output: ait_settings_<time>.txt/.json next to this script.
"""
import argparse
import datetime
import json
import os
import sys
import time
import urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from dashcam_probe import http_request  # noqa: E402

STAMP = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
LOG, REPORT = [], {"writes": []}
IP = "192.168.42.1"
ONLY = None


def log(m=""):
    print(m, flush=True)
    LOG.append(str(m))


def section(t):
    log("\n" + "=" * 78 + "\n" + t + "\n" + "=" * 78)


def cgi(query):
    r = http_request(IP, 80, "/cgi-bin/Config.cgi?" + query, timeout=8, max_bytes=200000)
    body = r.get("body_bytes", b"").decode("utf-8", "replace")
    lines = body.split("\n")
    code = lines[0].strip() if lines else ""
    return code, body, r.get("ms"), r.get("error")


def get_all():
    code, body, _, err = cgi("action=get&property=Camera.*")
    kv = {}
    for line in body.splitlines()[2:]:
        if "=" in line:
            k, v = line.split("=", 1)
            kv[k.strip()] = v.strip()
    return kv


def read(key):
    return get_all().get(key)


class CameraBroken(Exception):
    pass


def healthy():
    code, body, _, err = cgi("action=get&property=Camera.Menu.EV")
    return code == "0"


def set_(prop, value):
    q = f"action=set&property={urllib.parse.quote(prop, safe='.$-_')}&value={urllib.parse.quote(value, safe='.$-_/:')}"
    code, body, ms, err = cgi(q)
    short = body.strip().replace("\n", " | ")[:120]
    REPORT["writes"].append({"query": q, "code": code, "body": short, "ms": ms, "error": err})
    log(f"   SET {prop}={value!r:24} -> {short or err} ({ms} ms)")
    time.sleep(2)  # let the camera persist the setting; it is fragile
    if code not in ("0", "703") or not healthy():
        time.sleep(5)
        if not healthy():
            raise CameraBroken(f"command API stopped responding after SET {prop}={value}")
    return code, short


def ask(question):
    try:
        return input(f"\n>>> {question} [y/N] ").strip().lower() == "y"
    except EOFError:
        return False


VOLATILE = ("TimeStamp", "TimeDate", "CardInfo.Free", "Record.", "RecFileName", "DBFileNum",
            "File.Total", "NormalDBSize", "HeartBeat", "Gsxyz", "Battery", "Capture.Remaining")


def diff(a, b):
    return {k: (a.get(k), b.get(k)) for k in set(a) | set(b)
            if a.get(k) != b.get(k) and not any(v in k for v in VOLATILE)}


SKIP = {"Flicker", "FWversion", "HeartBeat", "IsStreaming", "Property", "Supplier", "SD0", "EmergencyVideo",
        "UIMode", "DefMode", "TV", "Videores", "VideoQuality", "PhotoBurst", "Timelapse"}


def menu_items(snapshot):
    items = {}
    for k, v in snapshot.items():
        if k.startswith("Camera.Menu.") and k.count(".") == 2:
            name = k.split(".", 2)[2]
            if name not in SKIP and v and v != "UNKNOW" and (ONLY is None or name in ONLY):
                items[name] = v
    return items


# ---------------------------------------------------------------------------- A

def tier_a(snap):
    section("A. NO-OP WRITES (set each setting to its current value)")
    log("Reference responses:")
    set_("NoSuchProperty", "1")
    accepted = {}
    for name, cur in sorted(menu_items(snap).items()):
        log(f"\n{name} (current {cur!r})")
        variants = dict.fromkeys([name, name[0] + name[1:].lower(), "Camera.Menu." + name])
        for prop in variants:  # e.g. ImageRes, Imageres (used by the web UI), Camera.Menu.ImageRes
            code, _ = set_(prop, cur)
            if code == "0":
                accepted[name] = prop
                break
    after = get_all()
    d = diff(snap, after)
    log(f"\nAccepted for writing: {accepted}")
    log(f"Not accepted: {sorted(set(menu_items(snap)) - set(accepted))}")
    log(f"Changes after no-op writes (should be none): {d or 'none'}")
    REPORT["tier_a"] = {"accepted": accepted, "diff": d}
    return accepted


# ---------------------------------------------------------------------------- B

ONOFF = ["ON", "OFF"]
LEVELS = ["OFF", "LOW", "MIDDLE", "HIGH", "LEVEL0", "LEVEL1", "LEVEL2", "LEVEL3", "LEVEL4"]
CANDIDATES = {
    "EV": ["EVN200", "EVN167", "EVN133", "EVN100", "EVN067", "EVN033", "EV0",
           "EVP033", "EVP067", "EVP100", "EVP133", "EVP167", "EVP200", "-1", "+1"],
    "AWB": ["AUTO", "DAYLIGHT", "CLOUDY", "FLUORESCENT", "FLUORESCENT1", "FLUORESCENT2",
            "FLUORESCENT3", "INCANDESCENT", "TUNGSTEN", "daylight", "fluorescentlight"],
    "MTD": LEVELS,
    "GSensor": LEVELS,
    "Flicker": ["50Hz", "60Hz", "AUTO"],
    "PowerOffDelay": ["OFF", "0SEC", "5SEC", "10SEC", "15SEC", "30SEC", "1MIN", "2MIN", "3MIN", "5MIN"],
    "VideoClipTime": ["OFF", "1MIN", "2MIN", "3MIN", "5MIN", "10MIN"],
    "LoopingVideo": ["OFF", "1MIN", "2MIN", "3MIN", "5MIN", "10MIN"],
    "ImageRes": ["2MP", "3MP", "5MP", "8MP", "12MP", "14MP", "2M", "3M", "5M", "8M", "12M", "14M", "VGA"],
    "TVSystem": ["NTSC", "PAL"],
    "UpsideDown": ["Normal", "Upsidedown", "UpsideDown", "Mirror", "Flip", "ON", "OFF"],
    "StatusLights": ["ON", "OFF", "OF"],
    "ParkingMonitor": ["ON", "OFF", "LOW", "MIDDLE", "HIGH"],
}


def tier_b(accepted):
    section("B. VALUE DISCOVERY (try candidates, read back, restore)")
    results = {}
    for name, prop in sorted(accepted.items()):
        key = "Camera.Menu." + name
        orig = read(key)
        cands = CANDIDATES.get(name, ONOFF)
        log(f"\n{name}: original={orig!r}")
        valid, silently_ignored, rejected = [], [], {}
        for v in cands:
            if v == orig:
                valid.append(v)
                continue
            code, body = set_(prop, v)
            now = read(key)
            if code != "0":
                rejected[v] = body
            elif now == v:
                valid.append(v)
            else:
                silently_ignored.append(f"{v}->{now}")
            if now != orig:
                set_(prop, orig)
        final = read(key)
        ok = final == orig
        log(f"   valid={valid}  ignored={silently_ignored}  rejected={list(rejected)}  "
            f"restored={'OK' if ok else 'NO! now ' + repr(final)}")
        if not ok:
            set_(prop, orig)
        results[name] = {"prop": prop, "original": orig, "valid": valid,
                         "ignored": silently_ignored, "rejected": rejected, "restored": ok}
    REPORT["tier_b"] = results


# ---------------------------------------------------------------------------- C

def parse_cam_time(s):
    # "2022/7/20 21/12/49"
    try:
        d, t = s.split(" ")
        y, mo, da = (int(x) for x in d.split("/"))
        h, mi, se = (int(x) for x in t.split("/"))
        return datetime.datetime(y, mo, da, h, mi, se)
    except Exception:
        return None


def tier_c():
    section("C. SET CAMERA CLOCK")
    before = read("Camera.TimeDate")
    log(f"camera clock before: {before!r}   computer: {datetime.datetime.now():%Y-%m-%d %H:%M:%S}")
    attempts = [
        ("TimeSettings", "%Y$%m$%d$%H$%M$%S$"),
        ("TimeSettings", "%Y$%m$%d$%H$%M$%S"),
        ("TimeSettings", "%Y$%-m$%-d$%-H$%-M$%-S$"),
        ("TimeSetting", "%Y$%m$%d$%H$%M$%S$"),
        ("Camera.TimeDate", "%Y/%-m/%-d %-H/%-M/%-S"),
        ("DateTime", "%Y%m%d%H%M%S"),
        ("Time", "%Y-%m-%d %H:%M:%S"),
    ]
    result = None
    for prop, fmt in attempts:
        now = datetime.datetime.now()
        code, body = set_(prop, now.strftime(fmt))
        time.sleep(1)
        cam = parse_cam_time(read("Camera.TimeDate") or "")
        delta = abs((cam - datetime.datetime.now()).total_seconds()) if cam else None
        log(f"   camera clock now: {cam}  (diff {delta if delta is None else round(delta)} s)")
        if delta is not None and delta < 120:
            result = {"prop": prop, "format": fmt, "code": code}
            log(f"   ==> WORKS: property={prop} value format {fmt!r}")
            break
    if not result:
        log("   no format worked; camera clock unchanged")
    REPORT["tier_c"] = {"before": before, "result": result, "after": read("Camera.TimeDate"),
                        "recfile_after": read("Camera.RecFileName")}


def save():
    base = os.path.join(HERE, f"ait_settings_{STAMP}")
    with open(base + ".json", "w") as f:
        json.dump(REPORT, f, indent=1, default=str)
    with open(base + ".txt", "w") as f:
        f.write("\n".join(LOG))
    return base


def main():
    global IP
    ap = argparse.ArgumentParser()
    ap.add_argument("--ip", default=IP)
    ap.add_argument("--only", help="comma-separated settings to test, e.g. EV,AWB")
    args = ap.parse_args()
    IP = args.ip
    global ONLY
    ONLY = set(args.only.split(",")) if args.only else None
    log(f"AIT settings probe {STAMP} -> {IP}")
    try:
        snap = get_all()
        if not snap:
            log("Camera not reachable.")
            return
        REPORT["before"] = snap
        log(f"Recording: {snap.get('Camera.Preview.MJPEG.status.record')}, file {snap.get('Camera.RecFileName')}")
        log("Current menu values:")
        for k, v in sorted(menu_items(snap).items()):
            log(f"   {k} = {v}")
        if not ask("Tier A: send no-op writes (each setting set to its current value)?"):
            return
        accepted = tier_a(snap)
        save()
        if accepted and ask(f"Tier B: try other values for {len(accepted)} settings and restore them afterwards?"):
            tier_b(accepted)
            save()
        if ask("Tier C: set the camera clock to this computer's time?"):
            tier_c()
        section("FINAL CHECK")
        end = get_all()
        d = diff(snap, end)
        log(f"Settings that differ from the start: {d or 'none'}")
        REPORT["after"] = end
        REPORT["final_diff"] = d
    except CameraBroken as e:
        log(f"\n!! STOPPED: {e}. Power-cycle the camera, then run ait_health.py.")
        REPORT["stopped"] = str(e)
    except KeyboardInterrupt:
        log("\ninterrupted - check FINAL values with: action=get&property=Camera.Menu.*")
    finally:
        base = save()
        log(f"\nDone. Send back {base}.txt")


if __name__ == "__main__":
    main()
