#!/usr/bin/env bash
set -euo pipefail

# Build a Release app for a connected iPhone and install it.
#
# Usage: tools/apple/install-device.sh <device-udid>
#
# The UDID is printed by `xcrun devicectl list devices`. Signing uses the team from
# appleApp/Signing.local.xcconfig (see appleApp/Signing.local.xcconfig.example).
#
# The development profile is valid for about one year. Re-run this script to renew it; the
# app's bundle id stays the same, so its downloads and settings survive the reinstall.

if [[ $# -lt 1 ]]; then
    echo "Usage: $0 <device-udid>"
    echo
    xcrun devicectl list devices
    exit 2
fi

DEVICE="$1"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

if [[ ! -f appleApp/Signing.local.xcconfig ]]; then
    echo "appleApp/Signing.local.xcconfig is missing." >&2
    echo "Copy appleApp/Signing.local.xcconfig.example and set DEVELOPMENT_TEAM." >&2
    exit 2
fi

DERIVED="build/apple-device"
xcodebuild -project appleApp/CardvDashcam.xcodeproj \
    -scheme CardvDashcam \
    -configuration Release \
    -destination "platform=iOS,id=$DEVICE" \
    -derivedDataPath "$DERIVED" \
    -allowProvisioningUpdates \
    build

APP="$DERIVED/Build/Products/Release-iphoneos/CardvDashcam.app"

xcrun devicectl device install app --device "$DEVICE" "$APP"
xcrun devicectl device process launch --device "$DEVICE" com.cardv.dashcam

echo
echo "Installed. The development profile expires in about one year; re-run this script to renew."
