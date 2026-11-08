#!/usr/bin/env bash
set -euo pipefail

# Regenerate appleApp/CardvDashcam/Assets.xcassets/AppIcon.appiconset from the Android
# launcher art. Requires ImageMagick (`magick`); the generated PNGs are committed, so this
# only needs to run when the artwork changes.
#
# The iOS icon is the adaptive foreground over the launcher background colour. The macOS
# icon is the same artwork on a rounded square with the usual Big Sur margin.

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

SOURCE="androidApp/src/main/res/drawable-nodpi/ic_launcher_foreground.png"
ICONSET="appleApp/CardvDashcam/Assets.xcassets/AppIcon.appiconset"
BACKGROUND="#F5F5F3"

if ! command -v magick >/dev/null 2>&1; then
    echo "ImageMagick (magick) is required to regenerate the app icon." >&2
    exit 2
fi

mkdir -p "$ICONSET"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# The launcher foreground has a large transparent safe-zone border. Crop it and fill more of the
# iOS icon, otherwise the camera looks tiny inside the home-screen square.
magick -size 1024x1024 "xc:$BACKGROUND" \
    \( "$SOURCE" -trim +repage -resize 942x942 \) -gravity center -compose over -composite \
    -strip "$TMP/ios-1024.png"
magick "$TMP/ios-1024.png" -resize 824x824 \
    \( -size 824x824 xc:black -fill white -draw 'roundrectangle 0,0 823,823 185,185' \) \
    -alpha off -compose CopyOpacity -composite -strip "$TMP/mac-824.png"
magick -size 1024x1024 xc:none "$TMP/mac-824.png" -geometry +100+100 -composite \
    -strip "$TMP/mac-1024.png"

python3 - "$ICONSET" "$TMP" <<'PY'
import json, os, subprocess, sys
iconset, tmp = sys.argv[1], sys.argv[2]
ios = [
    ("iphone", "20x20", "2x", 40), ("iphone", "20x20", "3x", 60),
    ("iphone", "29x29", "2x", 58), ("iphone", "29x29", "3x", 87),
    ("iphone", "40x40", "2x", 80), ("iphone", "40x40", "3x", 120),
    ("iphone", "60x60", "2x", 120), ("iphone", "60x60", "3x", 180),
    ("ipad", "20x20", "1x", 20), ("ipad", "20x20", "2x", 40),
    ("ipad", "29x29", "1x", 29), ("ipad", "29x29", "2x", 58),
    ("ipad", "40x40", "1x", 40), ("ipad", "40x40", "2x", 80),
    ("ipad", "76x76", "1x", 76), ("ipad", "76x76", "2x", 152),
    ("ipad", "83.5x83.5", "2x", 167),
    ("ios-marketing", "1024x1024", "1x", 1024),
]
mac = [
    ("mac", "16x16", "1x", 16), ("mac", "16x16", "2x", 32),
    ("mac", "32x32", "1x", 32), ("mac", "32x32", "2x", 64),
    ("mac", "128x128", "1x", 128), ("mac", "128x128", "2x", 256),
    ("mac", "256x256", "1x", 256), ("mac", "256x256", "2x", 512),
    ("mac", "512x512", "1x", 512), ("mac", "512x512", "2x", 1024),
]
entries = []
for idiom, size, scale, px in ios:
    name = f"AppIcon-ios-{px}.png"
    subprocess.run(["magick", os.path.join(tmp, "ios-1024.png"), "-resize", f"{px}x{px}",
                    "-strip", os.path.join(iconset, name)], check=True)
    entries.append({"filename": name, "idiom": idiom, "scale": scale, "size": size})
for idiom, size, scale, px in mac:
    name = f"AppIcon-mac-{px}.png"
    subprocess.run(["magick", os.path.join(tmp, "mac-1024.png"), "-resize", f"{px}x{px}",
                    "-strip", os.path.join(iconset, name)], check=True)
    entries.append({"filename": name, "idiom": idiom, "scale": scale, "size": size})
with open(os.path.join(iconset, "Contents.json"), "w") as f:
    json.dump({"images": entries, "info": {"author": "xcode", "version": 1}}, f, indent=2)
    f.write("\n")
print(f"wrote {len(entries)} icon images to {iconset}")
PY

mkdir -p fastlane/metadata/android/en-US/images
magick "$ICONSET/AppIcon-ios-1024.png" -resize 512x512 -strip \
    fastlane/metadata/android/en-US/images/icon.png
