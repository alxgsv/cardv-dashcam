#!/usr/bin/env python3
"""
Stage 4 (read-only): SOS folder + live view via a pure-Python RTSP client.

    python3 ait_probe4.py [--ip 192.168.42.1] [--seconds 6]

macOS blocks Homebrew ffmpeg from the local network ("No route to host"), so the
RTSP session is done here in Python (RTP over TCP, like the Android app will do)
and the captured H.264 is decoded offline with ffmpeg (no network needed).
Output: ait4_report_<time>.txt, ait4_<stream>.h264 / .jpg next to this script.
"""
import argparse
import base64
import datetime
import os
import re
import shutil
import socket
import struct
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


def hget(h, k):
    for kk, v in (h or {}).items():
        if kk.lower() == k.lower():
            return v


# ---------------------------------------------------------------------------- SOS

def sos(ip):
    section("1. SOS FOLDER")
    files, frm = [], 0
    while frm < 3000:
        r = http_request(ip, 80, f"/cgi-bin/Config.cgi?action=dir&property=SOS&format=all&count=100&from={frm}",
                         timeout=10, max_bytes=1_000_000)
        root = ET.fromstring(r.get("body_bytes", b"<x/>"))
        page = [{"name": f.findtext("name"), "size": int(f.findtext("size") or 0), "time": f.findtext("time"),
                 "attr": f.findtext("attr"), "fmt": (f.find("format").attrib if f.find("format") is not None else {})}
                for f in root.findall("file")]
        log(f"from={frm}: {len(page)} files ({r.get('ms')} ms)")
        files += page
        if len(page) < 100:
            break
        frm += 100
    log(f"total SOS files: {len(files)}")
    if not files:
        return
    folders = sorted({f["name"].rsplit("/", 1)[0] for f in files})
    log(f"folders: {folders}")
    log(f"first: {files[0]}")
    log(f"last:  {files[-1]}")
    log(f"attrs: {sorted({f['attr'] for f in files})}   "
        f"durations: {sorted({f['fmt'].get('time') for f in files})[:10]}")
    log(f"total size: {sum(f['size'] for f in files) / 1e9:.2f} GB")
    years = {}
    for f in files:
        years[(f["time"] or "?")[:7]] = years.get((f["time"] or "?")[:7], 0) + 1
    log(f"by month: {years}")
    url = files[-1]["name"]
    r = http_request(ip, 80, url, headers={"Range": "bytes=0-65535"}, timeout=8, max_bytes=70000)
    b = r.get("body_bytes", b"")
    log(f"GET {url} Range 0-65535 -> {r.get('status')} {hget(r.get('headers'), 'Content-Range')}")
    if len(b) > 64 and b[40:44] == b"skip":
        n = struct.unpack(">I", b[56:60])[0]
        jpg = b[60:60 + n]
        ok = jpg[:3] == b"\xff\xd8\xff"
        log(f"   embedded thumbnail len={n} valid_jpeg={ok}")
        if ok and len(jpg) == n:
            p = os.path.join(HERE, "ait4_sos_thumb.jpg")
            open(p, "wb").write(jpg)
            log(f"   saved {p}")
    else:
        log(f"   box layout differs: {b[:64].hex(' ')}")


# ---------------------------------------------------------------------------- RTSP

class Rtsp:
    def __init__(self, ip, port=554):
        self.ip, self.cseq, self.session = ip, 0, None
        self.s = socket.create_connection((ip, port), timeout=8)
        self.buf = b""

    def _read_response(self):
        while b"\r\n\r\n" not in self.buf:
            self.buf += self.s.recv(65536)
        head, _, rest = self.buf.partition(b"\r\n\r\n")
        m = re.search(rb"content-length:\s*(\d+)", head, re.I)
        n = int(m.group(1)) if m else 0
        while len(rest) < n:
            rest += self.s.recv(65536)
        body, self.buf = rest[:n], rest[n:]
        return head.decode("latin-1"), body.decode("latin-1")

    def req(self, method, url, extra=""):
        self.cseq += 1
        sess = f"Session: {self.session}\r\n" if self.session else ""
        msg = f"{method} {url} RTSP/1.0\r\nCSeq: {self.cseq}\r\nUser-Agent: ExoPlayerLib/2.x\r\n{sess}{extra}\r\n"
        self.s.sendall(msg.encode())
        head, body = self._read_response()
        m = re.search(r"Session:\s*([^;\r\n]+)", head)
        if m:
            self.session = m.group(1).strip()
        log(f">>> {method} {url}\n<<< " + head.replace("\r\n", "\n<<< ") + (f"\n{body}" if body else ""))
        return head, body

    def read_frame(self):
        """Return (channel, payload) for '$' interleaved frames; skips stray RTSP messages."""
        while True:
            while len(self.buf) < 4:
                c = self.s.recv(65536)
                if not c:
                    raise EOFError
                self.buf += c
            if self.buf[0:1] == b"$":
                ch, n = self.buf[1], struct.unpack(">H", self.buf[2:4])[0]
                while len(self.buf) < 4 + n:
                    c = self.s.recv(65536)
                    if not c:
                        raise EOFError
                    self.buf += c
                pkt, self.buf = self.buf[4:4 + n], self.buf[4 + n:]
                return ch, pkt
            if self.buf.startswith(b"RTSP/"):
                head, body = self._read_response()
                log(f"<<< (async) {head.splitlines()[0]}")
                continue
            self.buf = self.buf[1:]  # resync


def rtp_to_annexb(pkt, out, stats):
    if len(pkt) < 12:
        return
    cc = pkt[0] & 0x0F
    ext = pkt[0] & 0x10
    seq, ts = struct.unpack(">HI", pkt[2:8])
    i = 12 + 4 * cc
    if ext:
        i += 4 + 4 * struct.unpack(">H", pkt[i + 2:i + 4])[0]
    p = pkt[i:]
    if pkt[0] & 0x20:  # padding
        p = p[:-p[-1]]
    if not p:
        return
    if stats["last_seq"] is not None and seq != (stats["last_seq"] + 1) & 0xFFFF:
        stats["lost"] += (seq - stats["last_seq"] - 1) & 0xFFFF
    stats["last_seq"] = seq
    stats["ts"].add(ts)
    t = p[0] & 0x1F
    sc = b"\x00\x00\x00\x01"
    if 1 <= t <= 23:
        out += sc + p
        stats["nal"][t] = stats["nal"].get(t, 0) + 1
    elif t == 24:  # STAP-A
        j = 1
        while j + 2 <= len(p):
            n = struct.unpack(">H", p[j:j + 2])[0]
            nal = p[j + 2:j + 2 + n]
            if nal:
                out += sc + nal
                stats["nal"][nal[0] & 0x1F] = stats["nal"].get(nal[0] & 0x1F, 0) + 1
            j += 2 + n
    elif t == 28:  # FU-A
        if len(p) < 2:
            return
        if p[1] & 0x80:
            nt = p[1] & 0x1F
            out += sc + bytes([(p[0] & 0xE0) | nt])
            stats["nal"][nt] = stats["nal"].get(nt, 0) + 1
        out += p[2:]
    else:
        stats["other"] = stats.get("other", 0) + 1


def live(ip, name, seconds):
    section(f"2. RTSP liveRTSP/{name} (RTP over TCP, {seconds}s)")
    base = f"rtsp://{ip}:554/liveRTSP/{name}"
    try:
        c = Rtsp(ip)
    except Exception as e:
        log(f"connect failed: {e}")
        return
    try:
        c.req("OPTIONS", base)
        head, sdp = c.req("DESCRIBE", base, "Accept: application/sdp\r\n")
        cb = re.search(r"Content-Base:\s*(\S+)", head)
        cbase = cb.group(1) if cb else base + "/"
        sps_pps = b""
        m = re.search(r"sprop-parameter-sets=([^;\r\n]+)", sdp)
        if m:
            for part in m.group(1).split(","):
                sps_pps += b"\x00\x00\x00\x01" + base64.b64decode(part)
        tracks = re.findall(r"a=control:(\S+)", sdp)
        vtrack = tracks[0] if tracks else "track0"
        vurl = vtrack if vtrack.startswith("rtsp") else cbase.rstrip("/") + "/" + vtrack
        h, _ = c.req("SETUP", vurl, "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n")
        if " 200 " not in h.splitlines()[0]:
            log("SETUP over TCP refused; trying UDP-style SETUP just to record the reply")
            c.req("SETUP", vurl, "Transport: RTP/AVP;unicast;client_port=50000-50001\r\n")
            return
        aurl = None
        if len(tracks) > 1:
            aurl = tracks[1] if tracks[1].startswith("rtsp") else cbase.rstrip("/") + "/" + tracks[1]
            c.req("SETUP", aurl, "Transport: RTP/AVP/TCP;unicast;interleaved=2-3\r\n")
        t_play = time.time()
        c.req("PLAY", cbase, "Range: npt=0.000-\r\n")
        out = bytearray(sps_pps)
        stats = {"nal": {}, "lost": 0, "last_seq": None, "ts": set()}
        chans, first_idr = {}, None
        c.s.settimeout(5)
        while time.time() - t_play < seconds:
            ch, pkt = c.read_frame()
            chans[ch] = chans.get(ch, 0) + len(pkt)
            if ch == 0:
                rtp_to_annexb(pkt, out, stats)
                if first_idr is None and stats["nal"].get(5):
                    first_idr = time.time() - t_play
        dt = time.time() - t_play
        try:
            c.req("TEARDOWN", cbase)
        except Exception:
            pass
        log(f"\nreceived per channel (bytes): {chans}")
        log(f"video: {len(out)} bytes in {dt:.1f}s = {len(out) * 8 / dt / 1e6:.2f} Mbit/s, "
            f"frames(timestamps)={len(stats['ts'])} -> ~{len(stats['ts']) / dt:.1f} fps, "
            f"lost RTP={stats['lost']}, NAL types={stats['nal']}, first IDR after "
            f"{first_idr if first_idr is None else round(first_idr, 2)}s")
        if 2 in chans:
            log(f"audio: {chans[2]} bytes ({chans[2] * 8 / dt / 1000:.0f} kbit/s)")
        path = os.path.join(HERE, f"ait4_{name}.h264")
        open(path, "wb").write(out)
        ff, fp = shutil.which("ffmpeg"), shutil.which("ffprobe")
        if fp:
            p = subprocess.run([fp, "-v", "error", "-show_entries",
                                "stream=codec_name,profile,width,height,pix_fmt", "-of", "compact", path],
                               capture_output=True, text=True, timeout=30)
            log("ffprobe (offline): " + (p.stdout + p.stderr).strip())
        if ff:
            jpg = os.path.join(HERE, f"ait4_{name}.jpg")
            p = subprocess.run([ff, "-v", "error", "-y", "-i", path, "-frames:v", "1", "-update", "1", jpg],
                               capture_output=True, text=True, timeout=30)
            ok = os.path.exists(jpg) and os.path.getsize(jpg) > 0
            log(f"frame decode: {'OK -> ' + jpg if ok else 'FAILED ' + p.stderr[:300]}")
    except Exception as e:
        import traceback
        log(f"!! {type(e).__name__}: {e}\n{traceback.format_exc()}")
    finally:
        c.s.close()
    r = http_request(ip, 80, "/cgi-bin/Config.cgi?action=get&property=Camera.Preview.MJPEG.status.*", timeout=5)
    log(f"recording status after: {r.get('body_bytes', b'').decode().strip()!r}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ip", default="192.168.42.1")
    ap.add_argument("--seconds", type=int, default=6)
    a = ap.parse_args()
    log(f"AIT probe stage 4 {STAMP} -> {a.ip}")
    try:
        for fn, args in ((sos, (a.ip,)), (live, (a.ip, "v1", a.seconds)), (live, (a.ip, "av2", a.seconds))):
            try:
                fn(*args)
            except Exception as e:
                import traceback
                log(f"!! {fn.__name__} crashed: {e}\n{traceback.format_exc()}")
    except KeyboardInterrupt:
        log("interrupted")
    finally:
        p = os.path.join(HERE, f"ait4_report_{STAMP}.txt")
        open(p, "w").write("\n".join(LOG))
        log(f"\nDone. Send back {p}")


if __name__ == "__main__":
    main()
