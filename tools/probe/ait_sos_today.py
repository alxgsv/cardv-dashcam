#!/usr/bin/env python3
"""Read-only: list ALL SOS (and loop) clips recorded today, to find the manual SOS clip from Video=rec_emer."""
import datetime
import os
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dashcam_probe import http_request  # noqa: E402

IP = sys.argv[1] if len(sys.argv) > 1 else "192.168.42.1"
today = datetime.date.today().strftime("%Y-%m-%d")

for prop in ("SOS", "DCIM"):
    files, frm = [], 0
    while True:
        r = http_request(IP, 80, f"/cgi-bin/Config.cgi?action=dir&property={prop}&format=all&count=100&from={frm}",
                         timeout=15, max_bytes=1_000_000)
        page = ET.fromstring(r.get("body_bytes", b"<x/>")).findall("file")
        files += [(f.findtext("time"), f.findtext("name"), f.find("format").get("time") if f.find("format") is not None else "?")
                  for f in page]
        if len(page) < 100:
            break
        frm += 100
    todays = sorted(f for f in files if (f[0] or "").startswith(today))
    print(f"\n{prop}: {len(files)} files total, {len(todays)} from today ({today}):")
    for t, n, d in todays:
        print(f"   {t}  {n}  ({d} s)")
