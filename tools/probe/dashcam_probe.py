#!/usr/bin/env python3
"""
Dashcam Wi-Fi probe - read-only protocol discovery.

Run while your computer is connected to the dashcam's Wi-Fi:

    python3 dashcam_probe.py            # full scan (~2-4 min)
    python3 dashcam_probe.py --quick    # common ports only
    python3 dashcam_probe.py --target 192.168.1.254

Standard library only (no internet needed). It only sends read-only
queries: no settings are changed, nothing is deleted, recording is not
toggled. Results go to dashcam_report_<time>.txt and .json next to this script.
"""
import argparse
import concurrent.futures as cf
import datetime
import errno
import json
import os
import platform
import re
import socket
import ssl
import subprocess
import sys
import time

REPORT = {"meta": {}, "network": {}, "hosts": {}, "udp": [], "files": [], "fingerprint": []}
LOG = []
RESPONSES = []  # raw response text, used only for fingerprinting


def log(msg=""):
    print(msg, flush=True)
    LOG.append(str(msg))


def section(title):
    log("")
    log("=" * 78)
    log(title)
    log("=" * 78)


def run(cmd, timeout=10):
    try:
        p = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
        return (p.stdout + p.stderr).strip()
    except Exception as e:
        return f"<error: {e}>"


def run_for(cmd, seconds):
    """Run a never-ending command (e.g. dns-sd -B) for N seconds, return its output."""
    try:
        p = subprocess.Popen(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        try:
            out, _ = p.communicate(timeout=seconds)
        except subprocess.TimeoutExpired:
            p.kill()
            out, _ = p.communicate()
        return out.strip()
    except Exception as e:
        return f"<error: {e}>"


def preview(data, limit=2000):
    """Text if mostly printable, otherwise hex."""
    if data is None:
        return None
    if not data:
        return ""
    head = data[:limit]
    printable = sum(32 <= b < 127 or b in (9, 10, 13) for b in head)
    if printable / len(head) > 0.85:
        s = head.decode("utf-8", errors="replace")
        if len(data) > limit:
            s += f"\n... [truncated, {len(data)} bytes total]"
        return s
    return "HEX: " + data[:256].hex(" ") + f"  [{len(data)} bytes total]"


# ----------------------------------------------------------------------------
# Network info
# ----------------------------------------------------------------------------

COMMON_CAM_IPS = [
    "192.168.1.254",  # Novatek (VIOFO, many generic cams)
    "192.168.42.1",   # Ambarella (Yi, older 70mai, Mi)
    "192.168.0.1",    # HiSilicon / SigmaStar / many
    "192.168.1.1", "192.168.10.1", "192.168.100.1", "192.168.169.1",
    "192.168.25.1", "192.168.8.1", "192.168.43.1", "193.168.0.1",
    "10.0.0.1", "10.10.10.1", "172.16.0.1",
]


def local_ip_towards(ip):
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect((ip, 9))  # no packet is sent for UDP connect
        r = s.getsockname()[0]
        s.close()
        return r
    except Exception:
        return None


def collect_network():
    section("1. NETWORK INFO")
    info = {"platform": platform.platform(), "python": sys.version.split()[0]}
    gw = iface = dhcp_server = None
    sysname = platform.system()
    if sysname == "Darwin":
        info["route_default"] = run("route -n get default")
        m = re.search(r"gateway:\s*(\S+)", info["route_default"])
        gw = m.group(1) if m else None
        m = re.search(r"interface:\s*(\S+)", info["route_default"])
        iface = m.group(1) if m else None
        if not iface:
            # find Wi-Fi interface
            hw = run("networksetup -listallhardwareports")
            m = re.search(r"Hardware Port: Wi-Fi\s*\nDevice:\s*(\S+)", hw)
            iface = m.group(1) if m else "en0"
        info["interface"] = iface
        info["ifconfig"] = run(f"ifconfig {iface}")
        info["dhcp_packet"] = run(f"ipconfig getpacket {iface}")
        m = re.search(r"server_identifier \(ip\):\s*(\S+)", info["dhcp_packet"])
        dhcp_server = m.group(1) if m else None
        summ = run(f"ipconfig getsummary {iface}")
        info["wifi_summary"] = "\n".join(l for l in summ.splitlines()
                                         if re.search(r"SSID|BSSID|Security|Channel|RSSI|Router", l))
        info["netstat_routes"] = run("netstat -rn -f inet")
        info["arp_before"] = run("arp -an")
    elif sysname == "Linux":
        info["route"] = run("ip route")
        m = re.search(r"default via (\S+) dev (\S+)", info["route"])
        if m:
            gw, iface = m.group(1), m.group(2)
        info["addr"] = run("ip addr")
        info["wifi"] = run("iw dev 2>/dev/null; iwgetid 2>/dev/null; nmcli -t dev wifi 2>/dev/null | head -5")
        info["arp_before"] = run("ip neigh")
    elif sysname == "Windows":
        info["ipconfig"] = run("ipconfig /all")
        info["route"] = run("route print -4")
        info["wifi"] = run("netsh wlan show interfaces")
        m = re.search(r"0\.0\.0\.0\s+0\.0\.0\.0\s+(\d+\.\d+\.\d+\.\d+)", info["route"])
        gw = m.group(1) if m else None
        m = re.search(r"DHCP Server[ .]*:\s*(\d+\.\d+\.\d+\.\d+)", info["ipconfig"])
        dhcp_server = m.group(1) if m else None
        info["arp_before"] = run("arp -a")

    info["gateway"] = gw
    info["dhcp_server"] = dhcp_server
    ref = gw or dhcp_server or "192.168.1.254"
    info["local_ip"] = local_ip_towards(ref)
    REPORT["network"] = info

    for k in ("platform", "interface", "gateway", "dhcp_server", "local_ip"):
        log(f"{k:12}: {info.get(k)}")
    for k in ("wifi_summary", "wifi", "dhcp_packet", "ifconfig", "arp_before"):
        if info.get(k):
            log(f"\n--- {k} ---\n{info[k]}")
    return info


# ----------------------------------------------------------------------------
# TCP helpers
# ----------------------------------------------------------------------------

def tcp_state(host, port, timeout=0.5):
    """'open' | 'closed' (RST -> host alive) | 'filtered'"""
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(timeout)
    try:
        r = s.connect_ex((host, port))
    except Exception:
        return "filtered"
    finally:
        s.close()
    if r == 0:
        return "open"
    if r in (errno.ECONNREFUSED, getattr(errno, "WSAECONNREFUSED", -1), 10061):
        return "closed"
    return "filtered"


def raw_exchange(host, port, payload=None, wait=2.0, max_bytes=16384, tls=False):
    """Connect, optionally send payload, read whatever comes back."""
    data = b""
    try:
        s = socket.create_connection((host, port), timeout=3)
        if tls:
            ctx = ssl._create_unverified_context()
            s = ctx.wrap_socket(s, server_hostname=host)
        if payload:
            s.sendall(payload)
        s.settimeout(wait)
        end = time.time() + wait + 1
        while len(data) < max_bytes and time.time() < end:
            try:
                chunk = s.recv(4096)
            except socket.timeout:
                break
            if not chunk:
                break
            data += chunk
        s.close()
        RESPONSES.append(data[:20000].decode("latin-1").lower())
        return data, None
    except Exception as e:
        return data, f"{type(e).__name__}: {e}"


def dechunk(body):
    out, i = b"", 0
    try:
        while i < len(body):
            j = body.index(b"\r\n", i)
            size = int(body[i:j].split(b";")[0], 16)
            if size == 0:
                break
            out += body[j + 2:j + 2 + size]
            i = j + 2 + size + 2
        return out
    except Exception:
        return body


def http_request(host, port, path, method="GET", headers=None, body=b"",
                 timeout=4, max_bytes=65536, tls=False):
    t0 = time.time()
    data = b""
    try:
        s = socket.create_connection((host, port), timeout=timeout)
        if tls:
            s = ssl._create_unverified_context().wrap_socket(s, server_hostname=host)
        h = {"Host": host if port in (80, 443) else f"{host}:{port}",
             "User-Agent": "Mozilla/5.0 (Linux; Android 13) okhttp/4.9.0",
             "Accept": "*/*", "Connection": "close"}
        if headers:
            h.update(headers)
        if body:
            h["Content-Length"] = str(len(body))
        req = f"{method} {path} HTTP/1.1\r\n" + "".join(f"{k}: {v}\r\n" for k, v in h.items()) + "\r\n"
        s.sendall(req.encode() + body)
        s.settimeout(timeout)
        while len(data) < max_bytes:
            try:
                chunk = s.recv(8192)
            except socket.timeout:
                break
            if not chunk:
                break
            data += chunk
            if b"\r\n\r\n" in data:
                head, _, rest = data.partition(b"\r\n\r\n")
                m = re.search(rb"content-length:\s*(\d+)", head, re.I)
                if m and len(rest) >= int(m.group(1)):
                    break
        s.close()
    except Exception as e:
        if not data:
            return {"error": f"{type(e).__name__}: {e}", "ms": int((time.time() - t0) * 1000)}
    RESPONSES.append(data[:65536].decode("latin-1").lower())
    res = {"ms": int((time.time() - t0) * 1000), "raw_len": len(data)}
    if not data.startswith(b"HTTP/"):
        res["not_http"] = True
        res["raw"] = preview(data, 600)
        return res
    head, _, rest = data.partition(b"\r\n\r\n")
    lines = head.decode("latin-1").split("\r\n")
    res["status_line"] = lines[0]
    try:
        res["status"] = int(lines[0].split()[1])
    except Exception:
        res["status"] = None
    hdrs = {}
    for l in lines[1:]:
        if ":" in l:
            k, v = l.split(":", 1)
            hdrs[k.strip()] = v.strip()
    res["headers"] = hdrs
    if "chunked" in hdrs.get("Transfer-Encoding", hdrs.get("transfer-encoding", "")).lower():
        rest = dechunk(rest)
    res["body_bytes"] = rest
    res["body_len"] = len(rest)
    res["truncated"] = len(data) >= max_bytes
    return res


def http_for_json(r, body_limit=3000):
    r = dict(r)
    b = r.pop("body_bytes", None)
    if b is not None:
        r["body"] = preview(b, body_limit)
    return r


# ----------------------------------------------------------------------------
# Discovery
# ----------------------------------------------------------------------------

def discover_hosts(info, extra_targets):
    section("2. HOST DISCOVERY")
    candidates = []
    for ip in [info.get("gateway"), info.get("dhcp_server")] + list(extra_targets):
        if ip and ip not in candidates:
            candidates.append(ip)
    me = info.get("local_ip")
    alive = set(candidates)

    if me and me.count(".") == 3:
        prefix = me.rsplit(".", 1)[0]
        log(f"Sweeping {prefix}.1-254 (TCP 80/554/7878/8080/21) ...")
        jobs = []
        with cf.ThreadPoolExecutor(128) as ex:
            for i in range(1, 255):
                ip = f"{prefix}.{i}"
                if ip == me:
                    continue
                for port in (80, 554, 7878, 8080, 21):
                    jobs.append((ip, ex.submit(tcp_state, ip, port, 0.4)))
            for ip, fut in jobs:
                if fut.result() in ("open", "closed"):
                    alive.add(ip)
    else:
        log("Could not determine local IP - is Wi-Fi connected to the camera?")

    # ARP table after sweep catches hosts that drop TCP but answer ARP
    arp = run("arp -an") if platform.system() != "Windows" else run("arp -a")
    REPORT["network"]["arp_after"] = arp
    for m in re.finditer(r"\((\d+\.\d+\.\d+\.\d+)\) at ([0-9a-f:]+)", arp):
        ip, mac = m.groups()
        if not ip.endswith(".255") and ip != me and not ip.startswith("224.") and mac != "ff:ff:ff:ff:ff:ff":
            alive.add(ip)

    if not alive:
        log("Nothing found in subnet; trying well-known dashcam IPs ...")
        for ip in COMMON_CAM_IPS:
            if any(tcp_state(ip, p, 0.6) in ("open", "closed") for p in (80, 554, 7878)):
                alive.add(ip)

    alive.discard(me)
    alive = sorted(alive, key=lambda x: tuple(int(p) for p in x.split(".")))
    log(f"Alive hosts: {alive}")
    log(f"\n--- ARP table after sweep ---\n{arp}")
    return alive


COMMON_PORTS = sorted(set([
    20, 21, 22, 23, 25, 53, 69, 80, 81, 82, 88, 443, 445, 554, 555, 1080, 1935,
    2000, 2001, 2121, 2222, 3000, 3333, 3478, 4000, 4444, 5000, 5001, 5050, 5060,
    5353, 5555, 6000, 6001, 6100, 6200, 6666, 6789, 7000, 7070, 7777, 7878, 7879,
    8000, 8001, 8008, 8080, 8081, 8082, 8088, 8090, 8181, 8192, 8200, 8443, 8554,
    8787, 8800, 8880, 8888, 8889, 9000, 9001, 9090, 9876, 9999, 10000, 10080,
    12345, 15740, 20000, 30000, 34567, 35000, 37777, 40000, 49152, 50000, 60000,
]))


def port_scan(host, full):
    ports = range(1, 65536) if full else COMMON_PORTS
    total = 65535 if full else len(COMMON_PORTS)
    log(f"\nScanning {host}: {'ALL 65535' if full else total} TCP ports ...")
    open_ports, states = [], {"closed": 0, "filtered": 0}
    t0 = time.time()
    with cf.ThreadPoolExecutor(200 if full else 64) as ex:
        futs = {ex.submit(tcp_state, host, p, 0.6): p for p in ports}
        done = 0
        for fut in cf.as_completed(futs):
            st = fut.result()
            done += 1
            if st == "open":
                open_ports.append(futs[fut])
            else:
                states[st] += 1
            if full and done % 8000 == 0:
                print(f"  ... {done}/{total}  open so far: {sorted(open_ports)}", flush=True)
    open_ports.sort()
    log(f"  open: {open_ports}   (closed={states['closed']}, filtered={states['filtered']}, "
        f"{time.time() - t0:.0f}s)")
    return open_ports


# ----------------------------------------------------------------------------
# Per-port service identification
# ----------------------------------------------------------------------------

def rtsp_req(method, url, cseq, extra=""):
    return (f"{method} {url} RTSP/1.0\r\nCSeq: {cseq}\r\nUser-Agent: LibVLC/3.0.18\r\n"
            f"{extra}\r\n").encode()


def identify_port(host, port):
    res = {"port": port}
    banner, err = raw_exchange(host, port, None, wait=2)
    res["banner"] = preview(banner, 1000)
    if err:
        res["banner_err"] = err

    root = http_request(host, port, "/", timeout=3, max_bytes=8192)
    res["http_root"] = http_for_json(root, 1500)
    if root.get("status_line"):
        res["kind"] = "http"
        return res

    rt, _ = raw_exchange(host, port, rtsp_req("OPTIONS", f"rtsp://{host}:{port}/", 1), wait=2)
    if rt.startswith(b"RTSP/"):
        res["kind"] = "rtsp"
        res["rtsp_options"] = preview(rt)
        return res

    if port in (443, 8443) or not root.get("raw_len"):
        tl = http_request(host, port, "/", timeout=3, max_bytes=8192, tls=True)
        if tl.get("status_line"):
            res["kind"] = "https"
            res["https_root"] = http_for_json(tl, 1500)
            return res

    # JSON-over-TCP (Ambarella style) - start session is harmless
    js, _ = raw_exchange(host, port, b'{"msg_id":257,"token":0}', wait=2)
    if js:
        res["json_257_reply"] = preview(js)
        if js.lstrip().startswith(b"{"):
            res["kind"] = "json-tcp"
            return res

    nl, _ = raw_exchange(host, port, b"\r\n", wait=1.5)
    res["newline_reply"] = preview(nl)
    if banner.startswith(b"220"):
        res["kind"] = "ftp"
    elif banner.startswith(b"SSH-"):
        res["kind"] = "ssh"
    elif banner and (b"login" in banner.lower() or banner[:1] == b"\xff"):
        res["kind"] = "telnet"
    else:
        res["kind"] = "unknown"
    return res


# Read-only HTTP endpoints from known dashcam firmwares.
HTTP_PATHS = [
    # generic
    "/", "/index.html", "/index.htm", "/robots.txt", "/cgi-bin/", "/api", "/api/v1",
    "/status", "/info", "/device", "/version", "/files", "/sd/", "/SD/", "/mnt/",
    "/record/", "/Record/", "/video/", "/DCIM/", "/tmp/", "/www/", "/data/",
    # Novatek (VIOFO, Azdome, Vantrue, Rexing, generic). 3xxx = queries only.
    "/?custom=1&cmd=3002",   # list of supported commands
    "/?custom=1&cmd=3012",   # firmware version
    "/?custom=1&cmd=3014",   # current status of all settings
    "/?custom=1&cmd=3015",   # file list
    "/?custom=1&cmd=3016",   # heartbeat
    "/?custom=1&cmd=3017",   # free space
    "/?custom=1&cmd=3019",   # battery
    "/?custom=1&cmd=3024",   # card status (some fw)
    "/?custom=1&cmd=3030",   # movie live view url (some fw)
    "/CARDV/", "/CARDV/MOVIE/", "/CARDV/EMR/", "/CARDV/PHOTO/", "/CARDV/RO/",
    "/DCIM/Movie/", "/DCIM/MOVIE/", "/DCIM/EMR/", "/NOVATEK/", "/NOVATEK/MOVIE/",
    # HiSilicon / "hisnet" (Viidure, many Chinese cams)
    "/cgi-bin/hisnet/getdeviceattr.cgi", "/cgi-bin/hisnet/getsdstatus.cgi",
    "/cgi-bin/hisnet/getbatterycapacity.cgi", "/cgi-bin/hisnet/getworkmode.cgi",
    "/cgi-bin/hisnet/getwifi.cgi?", "/cgi-bin/hisnet/getcamchnl.cgi",
    "/cgi-bin/hisnet/getfilecount.cgi?&-dir=norm", "/cgi-bin/hisnet/getfilecount.cgi?&-dir=emr",
    "/cgi-bin/hisnet/getfilelist.cgi?&-dir=norm&-start=0&-end=20",
    "/cgi-bin/hisnet/getdirfilelist.cgi?&-dir=norm&-start=0&-end=20",
    "/cgi-bin/hisnet/getcommparam.cgi?&-type=all", "/cgi-bin/hisnet/getrecstate.cgi",
    # Mstar / SigmaStar "app" API (Viidure, Roadcam, Kacam ...)
    "/app/getproductinfo", "/app/getdeviceattr", "/app/getmediainfo", "/app/getsdinfo",
    "/app/capability", "/app/getparamitems?param=all", "/app/getparamvalue?param=all",
    "/app/getbatteryinfo", "/app/getrecduration",
    "/app/getfilelist?folder=loop&start=0&end=20", "/app/getfilelist?folder=emr&start=0&end=20",
    "/app/getfilelist?folder=event&start=0&end=20", "/app/getfilelist?folder=park&start=0&end=20",
    # Mstar / Sunplus "Config.cgi"
    "/cgi-bin/Config.cgi?action=get&property=Camera.Menu.*",
    "/cgi-bin/Config.cgi?action=get&property=Camera.Preview.*",
    "/cgi-bin/Config.cgi?action=dir&property=DCIM&format=all&count=16&from=0",
    "/cgi-bin/Config.cgi?action=dir&property=Normal&format=all&count=16&from=0",
    # BlackVue
    "/blackvue_vod.cgi", "/Config/version.bin", "/Record/",
    # DDPai
    "/vcam/cmd.cgi?cmd=API_RequestSessionID", "/vcam/cmd.cgi?cmd=API_GetBaseInfo",
    # Allwinner / misc
    "/cgi-bin/get_device_info", "/cgi-bin/status", "/getinfo", "/get_status",
    "/system/info", "/setting.xml", "/settings.xml", "/info.xml", "/version.txt",
]

MEDIA_RE = re.compile(r"[A-Za-z0-9_/\\:.\-]+\.(?:mp4|mov|ts|avi|mkv|jpg|jpeg|h264|h265|265)\b", re.I)
HREF_RE = re.compile(r'href\s*=\s*["\']([^"\'#?]+)["\']', re.I)


def probe_http(host, port, tls=False):
    log(f"\n--- HTTP endpoint probe {host}:{port}{' (TLS)' if tls else ''} ---")
    results = {}
    media_refs, dirs = set(), []

    def do(path, method="GET"):
        r = http_request(host, port, path, method=method, timeout=4, max_bytes=65536, tls=tls)
        return path, r

    seen_bodies = {}
    with cf.ThreadPoolExecutor(4) as ex:  # dashcams are fragile; low concurrency
        for path, r in ex.map(lambda p: do(p), HTTP_PATHS):
            st = r.get("status")
            body = r.get("body_bytes", b"")
            interesting = st is not None and st != 404 and not (st >= 400 and len(body) < 400 and path != "/")
            # Some servers return the same page for any path - don't repeat it
            key = (st, len(body), body[:200])
            dup = seen_bodies.get(key)
            if interesting and dup is not None and len(body) > 0:
                results[path] = {"status": st, "same_as": dup}
                log(f"[{st}] {path}  (identical to {dup})")
                continue
            if interesting:
                seen_bodies[key] = path
            results[path] = http_for_json(r, 4000 if interesting else 300)
            if interesting:
                hdrs = r.get("headers", {})
                ctype = hdrs.get("Content-Type") or hdrs.get("content-type") or "?"
                log(f"[{st}] {path}  ({len(body)} bytes, {r.get('ms')} ms) type={ctype}")
                txt = preview(body, 800)
                if txt:
                    log("    " + txt.replace("\n", "\n    "))
                btxt = body.decode("latin-1")
                media_refs.update(MEDIA_RE.findall(btxt))
                for h in HREF_RE.findall(btxt):
                    if h.endswith("/") and not h.startswith("http") and h not in ("/", "../", "./"):
                        dirs.append(h if h.startswith("/") else path.rstrip("/").split("?")[0] + "/" + h)
            elif st is None:
                log(f"[ERR] {path}: {r.get('error') or r.get('raw')}")

    # POST variant for DDPai-style session request
    _, r = do("/vcam/cmd.cgi?cmd=API_RequestSessionID", "POST")
    results["POST /vcam/cmd.cgi?cmd=API_RequestSessionID"] = http_for_json(r, 1000)
    if r.get("status") and r["status"] != 404:
        log(f"[{r['status']}] POST /vcam/cmd.cgi?cmd=API_RequestSessionID -> {preview(r.get('body_bytes', b''), 500)}")

    # Crawl directory listings (depth-limited)
    seen = set()
    for d in dirs[:30]:
        if d in seen or len(seen) >= 20:
            continue
        seen.add(d)
        _, r = do(d)
        if r.get("status") == 200:
            body = r.get("body_bytes", b"").decode("latin-1")
            refs = MEDIA_RE.findall(body)
            media_refs.update(d + x if not x.startswith("/") and "\\" not in x else x for x in refs)
            log(f"[dir] {d}: {len(refs)} media refs; sample: {refs[:5]}")
            results["crawl " + d] = http_for_json(r, 2000)
            for h in HREF_RE.findall(body):
                if h.endswith("/") and not h.startswith(("http", "..")) and h not in ("/", "./"):
                    dirs.append(h if h.startswith("/") else d.rstrip("/") + "/" + h)
    return results, media_refs


def media_url_candidates(ref):
    r = ref.replace("\\", "/")
    r = re.sub(r"^[A-Za-z]:", "", r)  # Novatek "A:\CARDV\MOVIE\x.MP4"
    if not r.startswith("/"):
        r = "/" + r
    out = [r]
    # HiSilicon lists "sd//norm/x.MP4" but serves "/sd/norm/x.MP4"
    collapsed = re.sub(r"/+", "/", r)
    if collapsed != r:
        out.append(collapsed)
    return out


def test_media_download(host, port, refs, tls=False):
    """Fetch only the first 1 KB of up to 3 files to learn URL scheme, Range support, container."""
    tested = []
    vids = sorted(refs, key=lambda x: (not re.search(r"mp4|mov|ts", x, re.I), x))
    for ref in vids[:6]:
        for url in media_url_candidates(ref):
            r = http_request(host, port, url.replace(" ", "%20"), headers={"Range": "bytes=0-1023"},
                             timeout=5, max_bytes=4096, tls=tls)
            body = r.get("body_bytes", b"")
            entry = {"ref": ref, "url": url, "status": r.get("status"),
                     "headers": r.get("headers"), "first_bytes_hex": body[:64].hex(" "),
                     "error": r.get("error")}
            tested.append(entry)
            log(f"[media] GET {url} Range 0-1023 -> {r.get('status')} "
                f"Content-Range={r.get('headers', {}).get('Content-Range')} "
                f"Len={r.get('headers', {}).get('Content-Length')} first={body[:16].hex(' ')}")
            if r.get("status") in (200, 206):
                break
        if sum(1 for t in tested if t["status"] in (200, 206)) >= 3:
            break
    return tested


def probe_rtsp(host, port):
    log(f"\n--- RTSP probe {host}:{port} ---")
    out = {}
    paths = ["/", "/live", "/xxxx.mov", "/livestream/12", "/livestream/11", "/live/ch0",
             "/liveRTSP/av4", "/stream1", "/stream", "/h264", "/11", "/12", "/ch0", "/ch1",
             "/cam1", "/front", "/rear", "/video", "/av0_0", "/main", "/sub", "/tcp/av0_0",
             "/0", "/1", "/live/0", "/live/1", "/mjpeg"]
    for i, p in enumerate(paths, 2):
        url = f"rtsp://{host}:{port}{p}"
        d, err = raw_exchange(host, port, rtsp_req("DESCRIBE", url, i, "Accept: application/sdp\r\n"), wait=2)
        txt = preview(d, 2000)
        out[p] = txt or err
        first = (txt or err or "").split("\n")[0]
        log(f"DESCRIBE {p}: {first}")
        if d.startswith(b"RTSP/1.0 200"):
            log("    " + txt.replace("\n", "\n    "))
    return out


def probe_json_tcp(host, port):
    """Ambarella-style JSON API: start session, get all settings, stop session."""
    log(f"\n--- JSON/TCP probe {host}:{port} ---")
    out = {}
    try:
        s = socket.create_connection((host, port), timeout=3)
        s.settimeout(3)

        def xfer(obj):
            s.sendall(json.dumps(obj).encode())
            buf = b""
            end = time.time() + 3
            while time.time() < end:
                try:
                    c = s.recv(8192)
                except socket.timeout:
                    break
                if not c:
                    break
                buf += c
                if buf.count(b"{") and buf.count(b"{") == buf.count(b"}"):
                    break
            return buf

        r = xfer({"msg_id": 257, "token": 0})
        out["257_start_session"] = preview(r)
        log(f"257 -> {preview(r, 500)}")
        m = re.search(rb'"param"\s*:\s*(\d+)', r)
        tok = int(m.group(1)) if m else 0
        for mid, name in ((3, "all_settings"), (11, "device_info"), (13, "battery"), (1, "get_setting")):
            r = xfer({"msg_id": mid, "token": tok})
            out[f"{mid}_{name}"] = preview(r, 4000)
            log(f"{mid} ({name}) -> {preview(r, 800)}")
        r = xfer({"msg_id": 258, "token": tok})
        out["258_stop_session"] = preview(r)
        s.close()
    except Exception as e:
        out["error"] = str(e)
        log(f"error: {e}")
    return out


# ----------------------------------------------------------------------------
# UDP
# ----------------------------------------------------------------------------

def udp_probes(hosts, me):
    section("5. UDP / DISCOVERY")
    results = []
    ssdp = (b"M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n"
            b"MX: 2\r\nST: ssdp:all\r\n\r\n")
    dns_q = bytes.fromhex("abcd01000001000000000000") + b"\x06google\x03com\x00\x00\x01\x00\x01"
    tests = [("239.255.255.250", 1900, ssdp, "ssdp")]
    for h in hosts:
        tests += [(h, 53, dns_q, "dns"), (h, 1900, ssdp, "ssdp-unicast"),
                  (h, 7877, b'{"msg_id":257,"token":0}', "ambarella-udp"),
                  (h, 8000, b"\x00", "udp8000"), (h, 9000, b"\x00", "udp9000"),
                  (h, 10000, b"\x00", "udp10000"), (h, 69, b"\x00\x01test\x00octet\x00", "tftp-rrq"),
                  (h, 3702, b"<?xml version=\"1.0\"?>", "ws-discovery")]
    if me:
        bc = me.rsplit(".", 1)[0] + ".255"
        for p in (8000, 9000, 10000, 20000, 30000, 48899, 6666, 5050):
            tests.append((bc, p, b"\x00", f"broadcast-{p}"))
    for ip, port, payload, name in tests:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        s.settimeout(2)
        replies = []
        try:
            s.sendto(payload, (ip, port))
            end = time.time() + 2.5
            while time.time() < end:
                try:
                    d, addr = s.recvfrom(65535)
                    replies.append({"from": f"{addr[0]}:{addr[1]}", "data": preview(d, 1500)})
                except socket.timeout:
                    break
        except Exception as e:
            replies.append({"error": str(e)})
        finally:
            s.close()
        results.append({"to": f"{ip}:{port}", "name": name, "replies": replies})
        good = [r for r in replies if "from" in r]
        log(f"{name:16} {ip}:{port:<6} -> {len(good)} replies" +
            ("".join(f"\n    {r['from']}: {r['data'][:600]}" for r in good)))

    if platform.system() == "Darwin":
        log("\nmDNS services (dns-sd, 6s):")
        out = run_for("dns-sd -B _services._dns-sd._udp local.", 6)
        REPORT["network"]["mdns"] = out
        log(out)
    return results


def listen_broadcasts(seconds=8):
    """Some cams periodically broadcast their presence - listen passively."""
    log(f"\nPassive UDP listen {seconds}s on common ports ...")
    socks = []
    for p in (8000, 9000, 10000, 20000, 30000, 48899, 5050, 6666, 7877, 37020):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind(("0.0.0.0", p))
            s.setblocking(False)
            socks.append((p, s))
        except Exception:
            pass
    got = []
    end = time.time() + seconds
    while time.time() < end:
        for p, s in socks:
            try:
                d, addr = s.recvfrom(65535)
                got.append({"port": p, "from": f"{addr[0]}:{addr[1]}", "data": preview(d, 1000)})
                log(f"  broadcast on :{p} from {addr}: {preview(d, 300)}")
            except (BlockingIOError, OSError):
                pass
        time.sleep(0.05)
    for _, s in socks:
        s.close()
    log(f"  {len(got)} packets received")
    return got


# ----------------------------------------------------------------------------
# Fingerprint
# ----------------------------------------------------------------------------

def fingerprint():
    blob = "\n".join(RESPONSES)
    rules = [
        ("Novatek (HTTP ?custom=1&cmd=NNNN, XML replies)", r"<function>.*?<cmd>"),
        ("HiSilicon 'hisnet' CGI API (var x=...; replies)", r"\bvar \w+\s*=\s*\""),
        ("Mstar/SigmaStar '/app/' JSON API", r"\"result\"\s*:\s*0.*\"info\""),
        ("Mstar/Sunplus Config.cgi", r"camera\.menu\."),
        ("Ambarella JSON over TCP", r"\"rval\"\s*:"),
        ("BlackVue", r"blackvue|n:/record/"),
        ("DDPai", r"\"errcode\"\s*:"),
        ("lighttpd server", r"server: lighttpd"),
        ("GoAhead web server", r"server: goahead"),
        ("Boa web server", r"server: boa"),
        ("hfs (Novatek eCos HTTP server)", r"server: hfs"),
        ("Live555 RTSP", r"live555"),
        ("GStreamer RTSP", r"gstreamer"),
    ]
    return [name for name, rx in rules if re.search(rx, blob, re.S)]


# ----------------------------------------------------------------------------
# Main
# ----------------------------------------------------------------------------

def save(base):
    def strip(o):
        if isinstance(o, dict):
            return {k: strip(v) for k, v in o.items() if k != "body_bytes"}
        if isinstance(o, list):
            return [strip(v) for v in o]
        if isinstance(o, (bytes, bytearray)):
            return preview(bytes(o))
        return o
    with open(base + ".json", "w") as f:
        json.dump(strip(REPORT), f, indent=2, default=str)
    with open(base + ".txt", "w") as f:
        f.write("\n".join(LOG))


def main():
    ap = argparse.ArgumentParser(description="Read-only dashcam Wi-Fi protocol probe")
    ap.add_argument("--target", action="append", default=[], help="camera IP (repeatable)")
    ap.add_argument("--quick", action="store_true", help="scan common ports only")
    ap.add_argument("--no-sweep", action="store_true", help="skip subnet sweep, only probe --target/gateway")
    args = ap.parse_args()

    stamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    base = os.path.join(os.path.dirname(os.path.abspath(__file__)), f"dashcam_report_{stamp}")
    REPORT["meta"] = {"started": stamp, "args": vars(args), "version": 1}
    log(f"Dashcam probe started {stamp}. Report -> {base}.txt / .json")

    try:
        info = collect_network()
        if args.no_sweep:
            hosts = [h for h in args.target + [info.get("gateway"), info.get("dhcp_server")] if h]
            hosts = list(dict.fromkeys(hosts)) or COMMON_CAM_IPS[:3]
        else:
            hosts = discover_hosts(info, args.target)
        if not hosts:
            log("\nNo camera found. Make sure you're connected to the dashcam Wi-Fi "
                "(and that the camera's Wi-Fi/app mode is enabled), then rerun.")
            return

        primary = set(args.target) | {info.get("gateway"), info.get("dhcp_server")}
        primary.discard(None)
        if not primary:
            primary = {hosts[0]}

        section("3. PORT SCAN + SERVICE IDENTIFICATION")
        for h in hosts:
            full = (not args.quick) and h in primary
            hr = {"open_ports": port_scan(h, full), "services": {}}
            REPORT["hosts"][h] = hr
            save(base)
            for p in hr["open_ports"]:
                svc = identify_port(h, p)
                hr["services"][p] = svc
                log(f"\n[{h}:{p}] kind={svc['kind']}")
                if svc.get("banner"):
                    log(f"  banner: {svc['banner'][:500]}")
                hr_root = svc.get("http_root", {})
                if hr_root.get("status_line"):
                    log(f"  HTTP: {hr_root['status_line']}  headers={hr_root.get('headers')}")
                for k in ("rtsp_options", "json_257_reply", "newline_reply"):
                    if svc.get(k):
                        log(f"  {k}: {svc[k][:500]}")

        section("4. PROTOCOL-SPECIFIC PROBES")
        for h, hr in REPORT["hosts"].items():
            for p, svc in hr["services"].items():
                if svc["kind"] in ("http", "https"):
                    tls = svc["kind"] == "https"
                    res, media = probe_http(h, p, tls)
                    svc["endpoints"] = res
                    svc["media_refs"] = sorted(media)[:200]
                    log(f"\nMedia references found on {h}:{p}: {len(media)}  sample: {sorted(media)[:10]}")
                    if media:
                        REPORT["files"] += test_media_download(h, p, media, tls)
                elif svc["kind"] == "rtsp":
                    svc["rtsp"] = probe_rtsp(h, p)
                elif svc["kind"] == "json-tcp" or p in (7878, 7879):
                    svc["json"] = probe_json_tcp(h, p)
                save(base)

        REPORT["udp"] = udp_probes(list(REPORT["hosts"].keys()), info.get("local_ip"))
        REPORT["broadcasts"] = listen_broadcasts(8)

        section("6. SUMMARY")
        REPORT["fingerprint"] = fingerprint()
        for h, hr in REPORT["hosts"].items():
            kinds = {p: s["kind"] for p, s in hr["services"].items()}
            log(f"{h}: {kinds}")
        log(f"Fingerprint hints: {REPORT['fingerprint'] or 'none matched'}")
        ok = [f for f in REPORT["files"] if f["status"] in (200, 206)]
        log(f"Downloadable media URLs confirmed: {len(ok)}")
        for f in ok[:5]:
            log(f"  {f['url']}  ({f['status']}, Content-Range={(f['headers'] or {}).get('Content-Range')})")
    except KeyboardInterrupt:
        log("\nInterrupted - saving partial report.")
    finally:
        save(base)
        log(f"\nDone. Please send back: {base}.txt (and .json if it's not too big)")
        with open(base + ".txt", "w") as f:
            f.write("\n".join(LOG))


if __name__ == "__main__":
    main()
