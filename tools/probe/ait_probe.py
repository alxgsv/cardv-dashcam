#!/usr/bin/env python3
"""
Stage 2: deep, read-only probe of an AIT (Alpha Imaging) dashcam Config.cgi API.

    python3 ait_probe.py                 # camera at 192.168.42.1
    python3 ait_probe.py --ip 1.2.3.4

Only action=get / action=dir requests and static pages - never action=set/del.
Output: ait_report_<time>.txt/.json and ait_thumb_<time>.jpg next to this script.
"""
import argparse
import datetime
import json
import os
import re
import socket
import struct
import sys
import time
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dashcam_probe import http_request, preview, raw_exchange, tcp_state  # noqa: E402

LOG, REPORT = [], {}
HERE = os.path.dirname(os.path.abspath(__file__))


def log(m=""):
    print(m, flush=True)
    LOG.append(str(m))


def section(t):
    log("\n" + "=" * 78 + "\n" + t + "\n" + "=" * 78)


def hget(h, k):
    for kk, v in (h or {}).items():
        if kk.lower() == k.lower():
            return v
    return None


def get(ip, path, **kw):
    kw.setdefault("timeout", 6)
    return http_request(ip, 80, path, **kw)


CGI = "/cgi-bin/Config.cgi?"


def static_pages(ip):
    section("1. STATIC WEB UI (reveals the commands the camera's own page uses)")
    todo = ["/index.html", "/settings.html", "/mj4.html", "/setting.html", "/preview.html",
            "/live.html", "/files.html", "/js/", "/main.js", "/ait.js"]
    seen, pages, cmds = set(), {}, set()
    while todo and len(seen) < 40:
        p = todo.pop(0)
        if p in seen:
            continue
        seen.add(p)
        r = get(ip, p, max_bytes=300000)
        st = r.get("status")
        body = r.get("body_bytes", b"")
        if st != 200:
            log(f"[{st}] {p}")
            continue
        txt = body.decode("latin-1")
        is_text = not re.search(r"\.(jpg|jpeg|png|gif|ico|bmp)$", p, re.I)
        pages[p] = txt if is_text else f"<binary {len(body)} bytes>"
        log(f"\n[200] {p} ({len(body)} bytes)")
        if is_text:
            log(txt[:6000])
            for m in re.finditer(r"""(?:src|href)\s*=\s*["']([^"'#]+)["']|self\.location\.href\s*=\s*'([^']+)'""", txt):
                link = m.group(1) or m.group(2)
                if link.startswith("http") or link.startswith("javascript"):
                    continue
                link = link if link.startswith("/") else "/" + link
                if link not in seen:
                    todo.append(link)
            for m in re.finditer(r"""[\w./-]*(?:Config\.cgi|\.cgi)[^"'\s<>]*""", txt):
                cmds.add(m.group(0))
            for m in re.finditer(r"""(?:action|property|value)=[^"'\s&<>]*""", txt):
                cmds.add(m.group(0))
    REPORT["pages"] = pages
    REPORT["page_cmds"] = sorted(cmds)
    log("\nCommand fragments referenced by the web UI:")
    for c in sorted(cmds):
        log("   " + c)


GET_PROPS = [
    "Camera.Menu.*", "Camera.Menu", "Camera.*", "Camera.Preview.*",
    "Camera.Preview.MJPEG.status.*", "Camera.Preview.Source.*", "Camera.Preview.RTSP.*",
    "Camera.Battery.*", "Camera.System.*", "Camera.Record.*", "Camera.Capture.*",
    "Camera.Video.*", "Camera.Photo.*", "Camera.File.*", "Camera.SD.*", "Camera.Info.*",
    "Camera.Sensor.*", "Camera.Rear.*", "Camera.Front.*", "Camera.Time.*", "Camera.GPS.*",
    "Camera.Menu.FWversion", "Camera.Menu.FWVersion", "Camera.Menu.DeviceID",
    "Camera.Menu.VideoRes", "Camera.Menu.ImageRes", "Camera.Menu.VideoClipTime",
    "Camera.Menu.LoopingVideo", "Camera.Menu.MTD", "Camera.Menu.Flicker", "Camera.Menu.AWB",
    "Camera.Menu.EV", "Camera.Menu.SD0", "Camera.Menu.TimeZone", "Camera.Menu.UIMode",
    "Camera.Menu.PowerOffDelay", "Camera.Menu.GSensor", "Camera.Menu.ParkingMonitor",
    "Camera.Menu.RecordWithAudio", "Camera.Menu.CardInfo.*", "Camera.Menu.CardInfo.LifeTimeTotal",
    "Camera.Menu.CardInfo.RemainLifeTime", "Camera.Menu.CardInfo.RemainWrGBNum",
    "Camera.Menu.DefMode", "Camera.Menu.Language", "Camera.Menu.Rear", "Camera.Menu.DateTime",
    "Camera.Menu.IsStreaming", "Camera.Menu.Model", "Camera.Menu.SDInfo",
    "Net.Dev.*", "Net.Dev.1.Type",  # no Net.WIFI_* (would expose the Wi-Fi password)
]


def property_gets(ip):
    section("2. PROPERTY QUERIES (action=get)")
    out = {}
    for p in GET_PROPS:
        r = get(ip, CGI + f"action=get&property={p}", max_bytes=200000)
        st = r.get("status")
        body = r.get("body_bytes", b"").decode("latin-1")
        out[p] = {"status": st, "body": body[:20000], "ms": r.get("ms"), "error": r.get("error")}
        if st == 200:
            log(f"\n[200] {p}  ({len(body)} bytes, {r.get('ms')} ms)\n{body[:5000]}")
        else:
            log(f"[{st}] {p} {r.get('error') or ''}")
        time.sleep(0.05)
    REPORT["props"] = out


DIR_PROPS = ["DCIM", "Normal", "Event", "Emer", "Emergency", "Lock", "Parking", "Park",
             "Photo", "Picture", "Rear", "Front", "Normal_Rear", "Event_Rear", "Video",
             "Movie", "All", "Share", "Manual", "Timelapse"]


def parse_dir(xml_text):
    files = []
    try:
        root = ET.fromstring(xml_text.encode("utf-8") if isinstance(xml_text, str) else xml_text)
    except Exception as e:
        return None, None, str(e)
    for f in root.findall("file"):
        fmt = f.find("format")
        files.append({
            "name": (f.findtext("name") or "").strip(),
            "size": int(f.findtext("size") or 0),
            "time": f.findtext("time"),
            "attr": f.findtext("attr"),
            "format": fmt.text if fmt is not None else None,
            "fmt_attrs": dict(fmt.attrib) if fmt is not None else {},
            "all_tags": [c.tag for c in f],
        })
    return root.tag, files, root.findtext("amount")


def dir_listing(ip):
    section("3. FILE LISTINGS (action=dir)")
    res = {}
    for prop in DIR_PROPS:
        r = get(ip, CGI + f"action=dir&property={prop}&format=all&count=16&from=0", max_bytes=200000)
        body = r.get("body_bytes", b"")
        st = r.get("status")
        if st == 200 and body.strip().startswith(b"<"):
            tag, files, amount = parse_dir(body.decode("utf-8", "replace"))
            names = [f["name"] for f in files or []]
            folders = sorted({n.rsplit("/", 1)[0] for n in names})
            res[prop] = {"status": st, "root": tag, "amount": amount, "count": len(files or []),
                         "folders": folders, "first": (files or [None])[0]}
            log(f"[200] {prop:12} root=<{tag}> amount={amount} folders={folders} ({r.get('ms')} ms)")
        else:
            res[prop] = {"status": st, "body": preview(body, 300), "error": r.get("error")}
            log(f"[{st}] {prop:12} {preview(body, 120)!r}")
    REPORT["dirs"] = res

    # Page size and full pagination of DCIM
    log("\nPage-size test (DCIM, count=100):")
    r = get(ip, CGI + "action=dir&property=DCIM&format=all&count=100&from=0", max_bytes=500000, timeout=15)
    _, files, amount = parse_dir(r.get("body_bytes", b"").decode("utf-8", "replace"))
    log(f"   asked 100 -> got {len(files or [])} (amount={amount}, {r.get('ms')} ms)")
    REPORT["page_size_100"] = {"got": len(files or []), "amount": amount, "ms": r.get("ms")}

    log("\nFull pagination of DCIM (count=16, from=0,16,32,...):")
    allfiles, frm, t0 = [], 0, time.time()
    while frm < 5000:
        r = get(ip, CGI + f"action=dir&property=DCIM&format=all&count=16&from={frm}", max_bytes=200000, timeout=10)
        _, files, amount = parse_dir(r.get("body_bytes", b"").decode("utf-8", "replace"))
        if not files:
            log(f"   from={frm}: status={r.get('status')} empty -> end")
            break
        allfiles += files
        if frm % 160 == 0:
            log(f"   from={frm}: {len(files)} files, last={files[-1]['name']}")
        if len(files) < 16:
            break
        frm += 16
    dt = time.time() - t0
    REPORT["all_files"] = allfiles
    folders = {}
    for f in allfiles:
        d = f["name"].rsplit("/", 1)[0]
        folders.setdefault(d, [0, 0])
        folders[d][0] += 1
        folders[d][1] += f["size"]
    log(f"   total {len(allfiles)} files in {dt:.1f}s")
    for d, (n, sz) in sorted(folders.items()):
        log(f"   {d}: {n} files, {sz / 1e9:.1f} GB")
    if allfiles:
        order = [f["time"] for f in allfiles]
        log(f"   order: first={order[0]} last={order[-1]} "
            f"({'newest first' if order[0] > order[-1] else 'oldest first'})")
        exts = sorted({f['name'].rsplit('.', 1)[-1] for f in allfiles})
        log(f"   extensions: {exts}; name samples: {[f['name'] for f in allfiles[:3]]}")
        dup = len(allfiles) - len({f['name'] for f in allfiles})
        log(f"   duplicate names across pages: {dup}")
        log(f"   format attrs sample: {allfiles[0]['fmt_attrs']}  tags: {allfiles[0]['all_tags']}")
    return allfiles


def mp4_boxes(ip, url, total):
    """Walk top-level MP4 boxes with tiny range reads (where is moov?)."""
    boxes, off = [], 0
    while off < total and len(boxes) < 30:
        r = get(ip, url, headers={"Range": f"bytes={off}-{off + 15}"}, max_bytes=4096)
        b = r.get("body_bytes", b"")
        if len(b) < 8:
            break
        size, typ = struct.unpack(">I4s", b[:8])
        hdr = 8
        if size == 1 and len(b) >= 16:
            size = struct.unpack(">Q", b[8:16])[0]
            hdr = 16
        elif size == 0:
            size = total - off
        boxes.append({"type": typ.decode("latin-1"), "offset": off, "size": size})
        if size < hdr:
            break
        off += size
    return boxes


def media_tests(ip, files):
    section("4. MEDIA: thumbnails, MP4 layout, speed")
    if not files:
        log("no files")
        return
    vids = [f for f in files if f["name"].lower().endswith((".mp4", ".mov", ".ts", ".avi"))]
    sample = vids[0] if vids else files[0]
    url = sample["name"]
    log(f"Sample file: {url}  listed size={sample['size']}")

    r = get(ip, url, method="HEAD", max_bytes=4096)
    log(f"HEAD -> {r.get('status')} {r.get('headers')}")
    REPORT["head"] = {"status": r.get("status"), "headers": r.get("headers")}

    r = get(ip, url, headers={"Range": "bytes=0-0"}, max_bytes=4096)
    cr = hget(r.get("headers"), "Content-Range") or ""
    total = int(cr.rsplit("/", 1)[-1]) if "/" in cr else sample["size"]
    log(f"Real size from Content-Range: {total} (listing said {sample['size']})")

    boxes = mp4_boxes(ip, url, total)
    REPORT["mp4_boxes"] = boxes
    log("Top-level MP4 boxes:")
    for b in boxes:
        log(f"   {b['type']:6} offset={b['offset']:<12} size={b['size']}")
    types = [b["type"] for b in boxes]
    if "moov" in types and "mdat" in types:
        log("   moov BEFORE mdat -> streamable directly" if types.index("moov") < types.index("mdat")
            else "   moov AFTER mdat -> player must range-read the tail first (ExoPlayer handles this)")

    # Embedded thumbnail in 'skip'/'free'/'uuid' box?
    for b in boxes:
        if b["type"] in ("skip", "free", "uuid", "thum", "udta") and b["size"] < 2_000_000:
            r = get(ip, url, headers={"Range": f"bytes={b['offset']}-{b['offset'] + b['size'] - 1}"},
                    max_bytes=b["size"] + 4096, timeout=10)
            data = r.get("body_bytes", b"")
            s = data.find(b"\xff\xd8\xff")
            e = data.rfind(b"\xff\xd9")
            log(f"   box {b['type']} @ {b['offset']}: {len(data)} bytes, JPEG at {s}..{e}; "
                f"header hex: {data[8:40].hex(' ')}")
            if s >= 0 and e > s:
                path = os.path.join(HERE, f"ait_thumb_{STAMP}.jpg")
                with open(path, "wb") as fh:
                    fh.write(data[s:e + 2])
                REPORT["thumb"] = {"box": b["type"], "box_offset": b["offset"], "jpeg_offset_in_box": s,
                                   "jpeg_len": e + 2 - s, "saved": path,
                                   "pre_jpeg_hex": data[8:s].hex(" ")[:300]}
                log(f"   -> saved embedded thumbnail ({e + 2 - s} bytes) to {path}")
                break

    # Separate thumbnail endpoints
    base = url.rsplit(".", 1)[0]
    fname = url.rsplit("/", 1)[-1]
    thumbs = {}
    for t in [f"/thumb{url}", f"/thumbnail{url}", base + ".THM", base + ".JPG", base + ".jpg",
              f"/SD/THUMB/{fname}", url.replace("/MOVIE/", "/THUMB/").rsplit(".", 1)[0] + ".JPG",
              CGI + f"action=thumb&property={url}", CGI + f"action=get&property=Thumbnail&value={url}"]:
        r = get(ip, t, max_bytes=300000)
        b = r.get("body_bytes", b"")
        thumbs[t] = {"status": r.get("status"), "len": len(b), "jpeg": b[:3] == b"\xff\xd8\xff"}
        log(f"   thumb try {t}: {r.get('status')} len={len(b)} jpeg={thumbs[t]['jpeg']}")
    REPORT["thumb_endpoints"] = thumbs

    # Speed test: 24 MB from the middle
    log("\nDownload speed test (24 MB range) ...")
    start = min(50_000_000, max(0, total - 25_000_000))
    n, t0 = 0, time.time()
    try:
        s = socket.create_connection((ip, 80), timeout=10)
        s.sendall(f"GET {url} HTTP/1.1\r\nHost: {ip}\r\nRange: bytes={start}-{start + 24_000_000 - 1}\r\n"
                  f"Connection: close\r\n\r\n".encode())
        s.settimeout(10)
        while n < 24_000_000 and time.time() - t0 < 60:
            c = s.recv(65536)
            if not c:
                break
            n += len(c)
        s.close()
    except Exception as e:
        log(f"   error: {e}")
    dt = time.time() - t0
    mbps = n / dt / 1e6 if dt else 0
    REPORT["speed"] = {"bytes": n, "seconds": round(dt, 2), "MBps": round(mbps, 2)}
    log(f"   {n / 1e6:.1f} MB in {dt:.1f}s = {mbps:.2f} MB/s ({mbps * 8:.1f} Mbit/s). "
        f"A {sample['size'] / 1e6:.0f} MB clip would take ~{sample['size'] / max(mbps, 0.01) / 1e6 / 60:.1f} min")

    # Does a list request still work while a download is open? (app UX)
    log("\nConcurrency test: list while a download connection is open ...")
    try:
        s = socket.create_connection((ip, 80), timeout=10)
        s.sendall(f"GET {url} HTTP/1.1\r\nHost: {ip}\r\nConnection: close\r\n\r\n".encode())
        s.recv(65536)
        r = get(ip, CGI + "action=dir&property=DCIM&format=all&count=16&from=0", timeout=8)
        log(f"   list during download -> {r.get('status')} in {r.get('ms')} ms {r.get('error') or ''}")
        r2 = get(ip, url, headers={"Range": "bytes=0-1023"}, timeout=8)
        log(f"   2nd parallel file range -> {r2.get('status')} in {r2.get('ms')} ms {r2.get('error') or ''}")
        REPORT["concurrency"] = {"list": r.get("status"), "list_ms": r.get("ms"), "second_range": r2.get("status")}
        s.close()
    except Exception as e:
        log(f"   error: {e}")


def live_view(ip):
    section("5. LIVE VIEW")
    out = {}
    for p in ["/cgi-bin/liveMJPEG", "/liveMJPEG", "/cgi-bin/liveMJPEG.cgi", "/mjpeg", "/live.mjpg",
              "/cgi-bin/mjpeg", "/cgi-bin/stream", "/stream", "/video.cgi", "/snapshot.jpg",
              "/cgi-bin/snapshot", "/cgi-bin/Config.cgi?action=get&property=Camera.Preview.MJPEG.*"]:
        r = get(ip, p, max_bytes=120000, timeout=4)
        b = r.get("body_bytes", b"")
        out[p] = {"status": r.get("status"), "headers": r.get("headers"), "len": len(b),
                  "head": preview(b[:400], 400), "jpeg_frames": b.count(b"\xff\xd8\xff")}
        log(f"{p}: {r.get('status')} {hget(r.get('headers'), 'Content-Type')} len={len(b)} "
            f"jpeg_markers={out[p]['jpeg_frames']} {r.get('error') or ''}")
        if b and r.get("status") == 200 and b"\xff\xd8" not in b[:2000]:
            log("    " + (preview(b, 500) or "").replace("\n", "\n    "))

    for port in (554, 8554, 80, 56789):
        st = tcp_state(ip, port, 1)
        log(f"tcp {port}: {st}")
        if st == "open" and port != 80:
            for path in ("/liveRTSP/av4", "/liveRTSP/av2", "/liveRTSP/v1", "/live", "/"):
                d, err = raw_exchange(ip, port, (f"DESCRIBE rtsp://{ip}:{port}{path} RTSP/1.0\r\nCSeq: 2\r\n"
                                                 f"Accept: application/sdp\r\n\r\n").encode(), wait=3)
                out[f"rtsp:{port}{path}"] = preview(d) or err
                log(f"   DESCRIBE {path}: {(preview(d, 600) or err or '').strip()[:600]}")
    REPORT["live"] = out


def port_56789(ip):
    section("6. PORT 56789 (silent TCP port)")
    out = {}
    try:
        s = socket.create_connection((ip, 56789), timeout=5)
        s.settimeout(1)
        got = b""
        t0 = time.time()
        log("Holding connection open 20s to see if the camera pushes anything ...")
        while time.time() - t0 < 20:
            try:
                c = s.recv(4096)
                if not c:
                    log(f"   closed by camera after {time.time() - t0:.1f}s")
                    break
                got += c
                log(f"   +{time.time() - t0:.1f}s: {preview(c, 300)}")
            except socket.timeout:
                pass
        s.close()
        out["passive"] = preview(got)
    except Exception as e:
        out["passive_err"] = str(e)
        log(f"   error: {e}")
    for name, payload in [("http", f"GET / HTTP/1.1\r\nHost: {ip}\r\n\r\n".encode()),
                          ("json", b'{"msg_id":257,"token":0}'), ("zeros", b"\x00" * 16),
                          ("hello", b"hello\n"), ("aitcmd", b"Camera.Preview.*\r\n")]:
        d, err = raw_exchange(ip, 56789, payload, wait=3)
        out[name] = preview(d) or err
        log(f"   {name:6} -> {preview(d, 300)!r} {err or ''}")
    # UDP on the same port / AIT notification ports
    for port in (56789, 49142, 49152):
        try:
            u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            u.settimeout(2)
            u.sendto(b"\x00", (ip, port))
            d, a = u.recvfrom(65535)
            out[f"udp{port}"] = preview(d)
            log(f"   udp {port} reply: {preview(d, 300)}")
        except Exception as e:
            log(f"   udp {port}: {type(e).__name__}")
        finally:
            u.close()
    REPORT["port_56789"] = out


def listen_udp(seconds=12):
    section("7. PASSIVE UDP LISTEN (camera status notifications?)")
    socks = []
    for p in (49142, 49152, 56789, 5555, 8080, 50000):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind(("0.0.0.0", p))
            s.setblocking(False)
            socks.append((p, s))
        except Exception as e:
            log(f"   cannot bind {p}: {e}")
    got, end = [], time.time() + seconds
    while time.time() < end:
        for p, s in socks:
            try:
                d, a = s.recvfrom(65535)
                got.append({"port": p, "from": a[0], "data": preview(d, 500)})
                log(f"   :{p} from {a}: {preview(d, 300)}")
            except (BlockingIOError, OSError):
                pass
        time.sleep(0.05)
    for _, s in socks:
        s.close()
    log(f"   {len(got)} packets in {seconds}s")
    REPORT["udp_listen"] = got


def save():
    base = os.path.join(HERE, f"ait_report_{STAMP}")
    with open(base + ".json", "w") as f:
        json.dump(REPORT, f, indent=1, default=str)
    with open(base + ".txt", "w") as f:
        f.write("\n".join(LOG))
    return base


STAMP = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ip", default="192.168.42.1")
    a = ap.parse_args()
    log(f"AIT probe {STAMP} -> {a.ip}")
    def safe(fn, *args):
        try:
            return fn(*args)
        except KeyboardInterrupt:
            raise
        except Exception as e:
            import traceback
            log(f"!! {fn.__name__} crashed: {e}\n{traceback.format_exc()}")
        finally:
            save()

    try:
        safe(static_pages, a.ip)
        safe(property_gets, a.ip)
        files = safe(dir_listing, a.ip) or []
        safe(media_tests, a.ip, files)
        safe(live_view, a.ip)
        safe(port_56789, a.ip)
        safe(listen_udp)
    except KeyboardInterrupt:
        log("interrupted")
    finally:
        base = save()
        log(f"\nDone. Send back {base}.txt (and the .json; the thumbnail .jpg is just for you to check)")


if __name__ == "__main__":
    main()
