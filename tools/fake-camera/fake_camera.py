#!/usr/bin/env python3
"""
Fake AIT / MStar "CarDV" dashcam for development and tests.

Standard library only. Mimics the parts of docs/PROTOCOL.md that the shared core uses:

    python3 tools/fake-camera/fake_camera.py
    python3 tools/fake-camera/fake_camera.py --port 8081 --slow 300
    python3 tools/fake-camera/fake_camera.py --hang-on Flicker

Quirks reproduced on purpose:
- HTTP status is 200 even for errors; the first body line is the result code.
- Response headers use UPPERCASE names (CONTENT-TYPE, CONTENT-LENGTH, ...).
- `action=dir` root tag echoes the requested property; an unknown name (including `Lock`)
  silently returns the loop list.
- `LOCK` and `Photo` are case-exact.
- A recording restart resets `Camera.Menu.SoundRecord` to OFF.
- MP4 files are `ftyp | skip (JPEG at offset 60) | moov | mdat`, with Range and HEAD support.
- `--hang-on Flicker` blocks the command server until the process is killed.

Fault injection for the safety tests:
    --hang-on Flicker   block every Config.cgi request after Flicker was sent
    --slow 500          delay every Config.cgi request by 500 ms
    --drop              close the connection on every Config.cgi request without a response
"""

from __future__ import annotations

import argparse
import datetime as dt
import re
import struct
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

CGI_PATH = "/cgi-bin/Config.cgi"
HTTP_DATE = "Mon, 01 Jan 2024 00:00:00 GMT"


# ---------------------------------------------------------------------------
# Sample media
# ---------------------------------------------------------------------------


def _box(box_type: bytes, payload: bytes) -> bytes:
    return struct.pack(">I", 8 + len(payload)) + box_type + payload


def _make_jpeg() -> bytes:
    """A small, valid-enough JPEG with no 0xFFD9 inside the payload."""
    soi = b"\xff\xd8"
    app0_payload = b"JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00"
    app0 = b"\xff\xe0" + struct.pack(">H", len(app0_payload) + 2) + app0_payload
    comment_payload = b"fake-camera-thumbnail".ljust(700, b"\x20")
    comment = b"\xff\xfe" + struct.pack(">H", len(comment_payload) + 2) + comment_payload
    return soi + app0 + comment + b"\xff\xd9"


def _make_mp4() -> bytes:
    # ftyp must be 36 bytes so the skip payload starts at 44 and the JPEG at 60.
    brands = b"isom" + b"iso2" + b"avc1" + b"mp41" + b"mp42"
    ftyp = _box(b"ftyp", b"isom" + struct.pack(">I", 0x200) + brands)
    assert len(ftyp) == 36, len(ftyp)

    jpeg = _make_jpeg()
    prelude = bytes.fromhex("e046d801e023ca010400ffff") + struct.pack(">I", len(jpeg))
    assert len(prelude) == 16
    skip_payload = prelude + jpeg
    skip = _box(b"skip", skip_payload)
    assert ftyp[0:4] == b"\x00\x00\x00$"  # 36
    assert skip[8 + 12:8 + 16] == struct.pack(">I", len(jpeg))
    assert len(ftyp) + 8 + 16 == 60

    moov = _box(b"moov", b"\x00" * 100)
    mdat = _box(b"mdat", bytes(range(256)) * 4)
    gsea = _box(b"gsea", b"\x00" * 20)
    gsen = _box(b"gsen", b"")
    return ftyp + skip + moov + mdat + gsea + gsen


def _make_photo() -> bytes:
    return _make_jpeg()


# ---------------------------------------------------------------------------
# Camera state
# ---------------------------------------------------------------------------


class Clip:
    def __init__(self, path: str, when: dt.datetime, size: int, duration: float, fmt: str = "MP4"):
        self.path = path
        self.when = when
        self.size = size
        self.duration = duration
        self.format = fmt

    def xml(self) -> str:
        if self.format == "JPG":
            fmt = '<format size="480x272" fps="0" time="0">JPG</format>'
            attr = "RW"
        else:
            fmt = f'<format size="1920x1080" fps="30" time="{self.duration}">MP4</format>'
            attr = "RW"
        return (
            "<file>"
            f"<name>{self.path}</name>"
            f"{fmt}"
            f"<size>{self.size}</size>"
            f"<attr>{attr}</attr>"
            f"<time>{self.when:%Y-%m-%d %H:%M:%S}</time>"
            "</file>"
        )


class CameraState:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.lock = threading.Lock()
        self.recording = True
        self.sound_record = "ON"
        self.ev = "EV0"
        self.awb = "AUTO"
        self.date_logo_stamp = "ON"
        self.flicker = "50Hz"
        self.video_clip_time = "5MIN"
        self.looping_video = "5MIN"
        self.videores = "1080P30"
        self.hdr = "ON"
        self.q_shot = "ON"
        self.sd_status = "READY"
        self.camera_time = dt.datetime(2022, 7, 20, 21, 12, 49)
        self.free_mb = 12_345
        self.total_mb = 61_000
        self.mp4 = _make_mp4()
        self.photo = _make_photo()

        self.files: dict[str, dict[str, Clip]] = {
            "DCIM": {},
            "SOS": {},
            "LOCK": {},
            "Photo": {},
        }
        self._build_files()

    def _build_files(self) -> None:
        base = dt.datetime(2022, 7, 7, 16, 26, 55)

        for i in range(self.args.loop_files):
            when = base + dt.timedelta(minutes=5 * i)
            name = f"{when:%Y%m%d_%H%M%S}_{2088 + i:06d}.MP4"
            path = f"/SD/MOVIE/{name}"
            self.files["DCIM"][path] = Clip(path, when, 5_000_000, 300.2)

        sos_base = dt.datetime(2022, 7, 7, 16, 31, 55)
        for i in range(self.args.sos_files):
            when = sos_base + dt.timedelta(seconds=30 * i)
            name = f"{when:%Y%m%d_%H%M%S}_{i:06d}.MP4"
            path = f"/SD/SOS/{name}"
            self.files["SOS"][path] = Clip(path, when, 3_000_000, 30.0)

        lock_base = dt.datetime(2022, 7, 7, 18, 0, 0)
        for i in range(self.args.lock_files):
            when = lock_base + dt.timedelta(minutes=5 * i)
            name = f"{when:%Y%m%d_%H%M%S}_{2088 + i:06d}.MP4"
            path = f"/SD/LOCK/{name}"
            self.files["LOCK"][path] = Clip(path, when, 5_000_000, 300.2)

        photo_base = dt.datetime(2022, 7, 7, 19, 0, 0)
        for i in range(self.args.photo_files):
            when = photo_base + dt.timedelta(seconds=5 * i)
            name = f"{when:%Y%m%d_%H%M%S}_{i:06d}.JPG"
            path = f"/SD/PHOTO/{name}"
            self.files["Photo"][path] = Clip(path, when, 120_000, 0.0, "JPG")

    def add_photo(self) -> None:
        when = dt.datetime.now()
        name = f"{when:%Y%m%d_%H%M%S}_999999.JPG"
        path = f"/SD/PHOTO/{name}"
        self.files["Photo"][path] = Clip(path, when, 120_000, 0.0, "JPG")

    def add_locked_clip(self) -> None:
        when = dt.datetime.now()
        name = f"{when:%Y%m%d_%H%M%S}_{2088:06d}.MP4"
        path = f"/SD/LOCK/{name}"
        self.files["LOCK"][path] = Clip(path, when, 5_000_000, 300.2)

    def current_video_name(self) -> str:
        if not self.recording or not self.files["DCIM"]:
            return ""
        latest = list(self.files["DCIM"].values())[-1]
        return latest.path

    def values(self) -> dict[str, str]:
        with self.lock:
            record = "Recording" if self.recording else "Standby"
            current = self.current_video_name().replace("/SD/", "SD:\\").replace("/", "\\")
            return {
                "Camera.Menu.FWversion": "MS;ms8339DEW;LS01;JJ20302;00;Z3152;V1.0;GEELY_Z3152_V1.2;FAKE",
                "Camera.Menu.Model": "FAKE-CAR-DV",
                "Camera.Menu.SD0": self.sd_status,
                "Camera.Menu.CardInfo.Free": str(self.free_mb),
                "Camera.Menu.CardInfo.Total": str(self.total_mb),
                "Camera.Menu.EV": self.ev,
                "Camera.Menu.AWB": self.awb,
                "Camera.Menu.DateLogoStamp": self.date_logo_stamp,
                "Camera.Menu.Flicker": self.flicker,
                "Camera.Menu.GSensor": "MIDDLE",
                "Camera.Menu.MTD": "LOW",
                "Camera.Menu.VideoClipTime": self.video_clip_time,
                "Camera.Menu.LoopingVideo": self.looping_video,
                "Camera.Menu.Videores": self.videores,
                "Camera.Menu.HDR": self.hdr,
                "Camera.Menu.Q-SHOT": self.q_shot,
                "Camera.Menu.PowerOffDelay": "5SEC",
                "Camera.Menu.TVSystem": "PAL",
                "Camera.Menu.UpsideDown": "Normal",
                "Camera.Menu.SpotMeter": "OFF",
                "Camera.Menu.StatusLights": "ON",
                "Camera.Menu.SoundIndicator": "ON",
                "Camera.Menu.PowerSaving": "OFF",
                "Camera.Menu.ParkingMonitor": "ON",
                "Camera.Menu.SoundRecord": self.sound_record,
                "Camera.Menu.IsStreaming": "NO",
                "Camera.Preview.MJPEG.status.mode": "Videomode",
                "Camera.Preview.MJPEG.status.record": record,
                "Camera.TimeDate": self.camera_time.strftime("%Y/%-m/%-d %-H/%-M/%-S"),
                "Camera.RecFileName": current,
                "Camera.DBFileNum": f"{len(self.files['DCIM'])}+{len(self.files['SOS'])}",
                "Camera.File.Total": str(sum(len(v) for v in self.files.values())),
                "Camera.Menu.CardInfo.RemainLifeTime": "99",
            }


# ---------------------------------------------------------------------------
# HTTP handler
# ---------------------------------------------------------------------------


class FakeCameraHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "AIT Multimedia Network Solution, UPnP/1.0 devices/1.6.19"
    sys_version = ""

    @property
    def state(self) -> CameraState:
        return self.server.state  # type: ignore[attr-defined]

    @property
    def args(self) -> argparse.Namespace:
        return self.server.args  # type: ignore[attr-defined]

    def log_message(self, fmt: str, *args: Any) -> None:
        if self.args.verbose:
            sys.stderr.write("fake-camera: " + (fmt % args) + "\n")

    # -- entry points -------------------------------------------------------

    def do_GET(self) -> None:
        self._handle(send_body=True)

    def do_HEAD(self) -> None:
        self._handle(send_body=False)

    def _handle(self, send_body: bool) -> None:
        parsed = urllib.parse.urlsplit(self.path)
        path = urllib.parse.unquote(parsed.path)

        if path == CGI_PATH:
            self._handle_cgi(parsed.query, send_body)
            return
        self._serve_file(path, send_body)

    # -- Config.cgi ---------------------------------------------------------

    def _handle_cgi(self, query: str, send_body: bool) -> None:
        if self.args.slow > 0:
            time.sleep(self.args.slow / 1000.0)

        if self.args.drop:
            self.close_connection = True
            return

        if self.server.hang_event.is_set():  # type: ignore[attr-defined]
            self._block_forever()

        params = urllib.parse.parse_qs(query, keep_blank_values=True)

        def one(name: str) -> str:
            values = params.get(name)
            return values[0] if values else ""

        action = one("action")
        property_name = one("property")
        value = one("value")

        if action == "get":
            self._text(200, self._get_response(property_name), send_body)
        elif action == "dir":
            count = _positive_int(one("count"), 100)
            start = _non_negative_int(one("from"), 0)
            self._text(200, self._dir_response(property_name, count, start), send_body)
        elif action == "set":
            self._set_response(property_name, value, send_body)
        else:
            self._text(200, "702\nUnknown action\n", send_body)

    def _get_response(self, property_name: str) -> str:
        values = self.state.values()
        if property_name in values:
            selected = {property_name: values[property_name]}
        elif property_name.endswith(".*"):
            prefix = property_name[:-2]
            selected = {k: v for k, v in values.items() if k == prefix or k.startswith(prefix + ".")}
        elif property_name == "Camera.Menu":
            # The real camera needs the `.*` wildcard for prefixes.
            return "703\nUnknown CGI-PARAM: Property\n"
        else:
            return "703\nUnknown CGI-PARAM: Property\n"

        if not selected:
            return "703\nUnknown CGI-PARAM: Property\n"
        return "0\nOK\n" + "".join(f"{key}={value}\n" for key, value in selected.items())

    def _dir_response(self, requested: str, count: int, start: int) -> str:
        # Unknown names (including wrongly-cased `Lock`) fall back to the loop list.
        folder = requested if requested in self.state.files else "DCIM"
        with self.state.lock:
            files = list(self.state.files[folder].values())
        page = files[start:start + count]
        body = [f"<{requested}>"]
        body.extend(clip.xml() for clip in page)
        body.append(f"<amount>{len(page)}</amount>")
        body.append(f"</{requested}>")
        return "\n".join(body) + "\n"

    def _set_response(self, property_name: str, value: str, send_body: bool) -> None:
        if property_name in self.server.hang_on:  # type: ignore[attr-defined]
            self.server.hang_event.set()  # type: ignore[attr-defined]
            self._block_forever()

        with self.state.lock:
            result = self._apply_set(property_name, value)
        self._text(200, result, send_body)

    def _apply_set(self, name: str, value: str) -> str:
        if name == "TimeSettings":
            if not re.fullmatch(r"\d{4}\$\d{2}\$\d{2}\$\d{2}\$\d{2}\$\d{2}", value):
                return "701\nIllegal param\n"
            try:
                parts = value.split("$")
                self.state.camera_time = dt.datetime(*(int(p) for p in parts))
            except ValueError:
                return "701\nIllegal param\n"
            return "0\nOK\n"

        if name == "Video":
            if value == "record":
                was_recording = self.state.recording
                self.state.recording = not self.state.recording
                if self.state.recording and not was_recording:
                    # The camera resets the microphone when a new recording starts.
                    self.state.sound_record = "OFF"
            elif value == "mute":
                self.state.sound_record = "OFF"
            elif value == "unmute":
                self.state.sound_record = "ON"
            elif value == "capture":
                self.state.add_photo()
            elif value == "rec_emer":
                self.state.add_locked_clip()
            elif value == "rec_short":
                pass
            else:
                return "703\nUnknown property value\n"
            return "0\nOK\n"

        if name in ("VideoClipTime", "LoopingVideo", "FactoryReset"):
            if self.state.recording:
                return "709\nRefused while recording\n"
            if name == "VideoClipTime":
                self.state.video_clip_time = value
            elif name == "LoopingVideo":
                self.state.looping_video = value
            return "0\nOK\n"

        if name in ("HDR", "Q-SHOT"):
            if self.state.recording:
                return "709\nRefused while recording\n"
            if name == "HDR":
                self.state.hdr = value
            else:
                self.state.q_shot = value
            return "0\nOK\n"

        if name == "Videores":
            if self.state.recording:
                return "709\nRefused while recording\n"
            self.state.videores = value
            return "0\nOK\n"

        if name == "SD0":
            if value != "format":
                return "703\nUnknown property value\n"
            if self.state.recording:
                return "709\nRefused while recording\n"
            self.state.sd_status = "READY"
            return "0\nOK\n"

        if name == "EV":
            self.state.ev = value
            return "0\nOK\n"
        if name == "AWB":
            self.state.awb = value
            return "0\nOK\n"
        if name == "DateLogoStamp":
            if value not in ("ON", "OFF"):
                return "701\nIllegal param\n"
            self.state.date_logo_stamp = value
            return "0\nOK\n"
        if name == "SoundRecord":
            if value not in ("ON", "OFF"):
                return "701\nIllegal param\n"
            self.state.sound_record = value
            return "0\nOK\n"
        if name == "Flicker":
            self.state.flicker = value
            return "0\nOK\n"
        if name == "Net":
            return "0\nOK\n"

        return "703\nUnknown CGI-PARAM: Property\n"

    # -- files --------------------------------------------------------------

    def _serve_file(self, path: str, send_body: bool) -> None:
        if path.startswith("/SD/PHOTO/") and path.lower().endswith(".jpg"):
            body = self.state.photo
            content_type = "image/jpeg"
        elif path.startswith(("/SD/MOVIE/", "/SD/SOS/", "/SD/LOCK/")) and path.lower().endswith(".mp4"):
            body = self.state.mp4
            # The real camera sends this wrong type for MP4 files.
            content_type = "video/mpeg"
        else:
            self._not_found(send_body)
            return

        # Only serve files that exist in the listing.
        folder = {
            "/SD/MOVIE/": "DCIM",
            "/SD/SOS/": "SOS",
            "/SD/LOCK/": "LOCK",
            "/SD/PHOTO/": "Photo",
        }.get(path.rsplit("/", 1)[0] + "/")
        with self.state.lock:
            known = folder is not None and path in self.state.files.get(folder, {})
        if not known:
            self._not_found(send_body)
            return

        total = len(body)
        range_header = self.headers.get("Range")
        if range_header:
            parsed = _parse_range(range_header, total)
            if parsed is None:
                self.send_response(416)
                self.send_header("CONTENT-TYPE", "text/plain")
                self.send_header("CONTENT-RANGE", f"bytes */{total}")
                self.send_header("CONTENT-LENGTH", "0")
                self.end_headers()
                return
            start, end = parsed
            chunk = body[start:end + 1]
            self.send_response(206)
            self.send_header("CONTENT-TYPE", content_type)
            self.send_header("CONTENT-RANGE", f"bytes {start}-{end}/{total}")
            self.send_header("CONTENT-LENGTH", str(len(chunk)))
            self.send_header("ACCEPT-RANGES", "bytes")
            self.end_headers()
            if send_body:
                self.wfile.write(chunk)
            return

        self.send_response(200)
        self.send_header("CONTENT-TYPE", content_type)
        self.send_header("CONTENT-LENGTH", str(total))
        self.send_header("ACCEPT-RANGES", "bytes")
        self.end_headers()
        if send_body:
            self.wfile.write(body)

    def _not_found(self, send_body: bool) -> None:
        body = b"Not Found"
        self.send_response(404)
        self.send_header("CONTENT-TYPE", "text/plain")
        self.send_header("CONTENT-LENGTH", str(len(body)))
        self.end_headers()
        if send_body:
            self.wfile.write(body)

    # -- helpers ------------------------------------------------------------

    def _text(self, status: int, body: str, send_body: bool) -> None:
        data = body.encode("utf-8")
        self.send_response(status)
        self.send_header("CONTENT-TYPE", "text/plain")
        self.send_header("CONTENT-LENGTH", str(len(data)))
        self.end_headers()
        if send_body:
            self.wfile.write(data)

    def _block_forever(self) -> None:
        # Keep the request open, like the real camera's CGI server after Flicker.
        while True:
            time.sleep(1)


class FakeCameraServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address: tuple[str, int], state: CameraState, args: argparse.Namespace):
        self.state = state
        self.args = args
        self.hang_on = set(args.hang_on or [])
        self.hang_event = threading.Event()
        super().__init__(address, FakeCameraHandler)


def _positive_int(value: str, default: int) -> int:
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return default
    return parsed if parsed > 0 else default


def _non_negative_int(value: str, default: int) -> int:
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return default
    return parsed if parsed >= 0 else default


def _parse_range(header: str, total: int) -> tuple[int, int] | None:
    match = re.fullmatch(r"bytes=(\d*)-(\d*)", header.strip())
    if not match:
        return None
    first, last = match.groups()
    if not first and not last:
        return None
    if not first:
        # suffix range: bytes=-N
        length = int(last)
        if length <= 0:
            return None
        start = max(0, total - length)
        return start, total - 1
    start = int(first)
    end = int(last) if last else total - 1
    if start >= total or start > end:
        return None
    return start, min(end, total - 1)


def main() -> None:
    parser = argparse.ArgumentParser(description="Fake CarDV/AIT dashcam")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--loop-files", type=int, default=54)
    parser.add_argument("--sos-files", type=int, default=125)
    parser.add_argument("--lock-files", type=int, default=2)
    parser.add_argument("--photo-files", type=int, default=1)
    parser.add_argument(
        "--hang-on",
        action="append",
        default=[],
        metavar="PROPERTY",
        help="block forever after this property is sent with action=set (repeatable)",
    )
    parser.add_argument("--slow", type=int, default=0, metavar="MS", help="delay every Config.cgi request")
    parser.add_argument("--drop", action="store_true", help="close the connection on every Config.cgi request")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    state = CameraState(args)
    server = FakeCameraServer((args.host, args.port), state, args)
    host, port = server.server_address[0], server.server_address[1]
    print(f"fake camera listening on http://{host}:{port}", flush=True)
    if args.hang_on:
        print(f"will hang on: {', '.join(args.hang_on)}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
