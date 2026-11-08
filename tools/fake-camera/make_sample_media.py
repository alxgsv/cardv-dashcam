#!/usr/bin/env python3
"""Regenerate the fake camera's media fixture.

The real camera puts a JPEG thumbnail in a `skip` box between `ftyp` and `moov`, so this
script takes a normal faststart MP4, prepends that box, and shifts the `stco`/`co64` chunk
offsets by the number of inserted bytes. The result plays in AVPlayer and ExoPlayer and
still answers the app's thumbnail range request.

Requires ffmpeg (`brew install ffmpeg`).

    python3 tools/fake-camera/make_sample_media.py
"""

from __future__ import annotations

import struct
import subprocess
import tempfile
from pathlib import Path

MEDIA = Path(__file__).resolve().parent / "media"
DURATION = 3
SIZE = "640x360"
CONTAINERS = {b"moov", b"trak", b"mdia", b"minf", b"stbl", b"edts", b"dinf"}


def run(*args: str) -> None:
    subprocess.run(args, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def boxes(data: bytes, start: int, end: int):
    offset = start
    while offset + 8 <= end:
        size, kind = struct.unpack(">I4s", data[offset : offset + 8])
        if size == 1:
            size = struct.unpack(">Q", data[offset + 8 : offset + 16])[0]
        if size < 8:
            return
        yield offset, size, kind
        offset += size


def patch_chunk_offsets(data: bytearray, start: int, end: int, delta: int) -> None:
    for offset, size, kind in boxes(data, start, end):
        if kind == b"stco":
            count = struct.unpack(">I", data[offset + 12 : offset + 16])[0]
            for index in range(count):
                at = offset + 16 + index * 4
                value = struct.unpack(">I", data[at : at + 4])[0]
                struct.pack_into(">I", data, at, value + delta)
        elif kind == b"co64":
            count = struct.unpack(">I", data[offset + 12 : offset + 16])[0]
            for index in range(count):
                at = offset + 16 + index * 8
                value = struct.unpack(">Q", data[at : at + 8])[0]
                struct.pack_into(">Q", data, at, value + delta)
        elif kind in CONTAINERS:
            patch_chunk_offsets(data, offset + 8, offset + size, delta)


def first_box(data: bytes, kind: bytes):
    for offset, size, box_kind in boxes(data, 0, len(data)):
        if box_kind == kind:
            return offset, size
    return None


def main() -> None:
    MEDIA.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        tmp_dir = Path(tmp)
        real_mp4 = tmp_dir / "real.mp4"
        jpeg = tmp_dir / "thumb.jpg"

        run(
            "ffmpeg", "-y",
            "-f", "lavfi", "-i", f"testsrc=size={SIZE}:rate=30",
            "-t", str(DURATION),
            "-c:v", "libx264", "-pix_fmt", "yuv420p",
            "-movflags", "+faststart",
            str(real_mp4),
        )
        run("ffmpeg", "-y", "-i", str(real_mp4), "-vframes", "1", "-vf", "scale=160:-1", str(jpeg))

        original = real_mp4.read_bytes()
        thumbnail = jpeg.read_bytes()

        found = first_box(original, b"ftyp")
        assert found is not None
        first_offset, first_size = found
        assert first_offset == 0

        brands = b"isom" + b"iso2" + b"avc1" + b"mp41" + b"mp42"
        ftyp = struct.pack(">I4s", 36, b"ftyp") + b"isom" + struct.pack(">I", 0x200) + brands
        assert len(ftyp) == 36

        prelude = bytes.fromhex("e046d801e023ca010400ffff") + struct.pack(">I", len(thumbnail))
        assert len(prelude) == 16
        skip = struct.pack(">I4s", 8 + len(prelude) + len(thumbnail), b"skip") + prelude + thumbnail
        assert ftyp[0:4] == b"\x00\x00\x00$"
        assert len(ftyp) + 8 + 16 == 60

        prefix = ftyp + skip
        body = original[first_size:]
        shift = len(prefix) - first_size

        patched = bytearray(prefix + body)
        moov = first_box(patched, b"moov")
        assert moov is not None
        patch_chunk_offsets(patched, moov[0] + 8, moov[0] + moov[1], shift)

        # The fixture must still decode; a bad patch shows up as invalid NAL units.
        check = tmp_dir / "patched.mp4"
        check.write_bytes(bytes(patched))
        subprocess.run(
            ["ffmpeg", "-v", "error", "-i", str(check), "-f", "null", "-"],
            check=True,
        )

        (MEDIA / "sample.mp4").write_bytes(bytes(patched))
        (MEDIA / "sample.jpg").write_bytes(thumbnail)
        print(f"wrote {MEDIA / 'sample.mp4'} ({len(patched)} bytes)")
        print(f"wrote {MEDIA / 'sample.jpg'} ({len(thumbnail)} bytes)")


if __name__ == "__main__":
    main()
