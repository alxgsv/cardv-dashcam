#!/usr/bin/env bash
set -euo pipefail

# Archive the iOS app for the App Store / TestFlight.
#
# Usage: tools/apple/archive-ios.sh [--export]
#
# Signing uses appleApp/Signing.local.xcconfig (gitignored) or a DEVELOPMENT_TEAM environment
# variable. --export also writes an IPA with tools/apple/ExportOptions-appstore.plist; the
# upload itself is documented in docs/RELEASE.md (Xcode Organizer or Transporter).

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

EXPORT=0
if [[ "${1:-}" == "--export" ]]; then
    EXPORT=1
elif [[ $# -gt 0 ]]; then
    echo "Usage: $0 [--export]" >&2
    exit 2
fi

if [[ ! -f appleApp/Signing.local.xcconfig && -z "${DEVELOPMENT_TEAM:-}" ]]; then
    echo "appleApp/Signing.local.xcconfig is missing and DEVELOPMENT_TEAM is not set." >&2
    echo "Copy appleApp/Signing.local.xcconfig.example and set DEVELOPMENT_TEAM." >&2
    exit 2
fi

VERSION="$(tools/release/check-version.sh --print)"
ARCHIVE="build/apple/ios/CardvDashcam.xcarchive"
EXPORT_DIR="build/apple/ios/export"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

rm -rf "$ARCHIVE" "$EXPORT_DIR"
mkdir -p "$(dirname "$ARCHIVE")"

TEAM_ID="${DEVELOPMENT_TEAM:-}"
if [[ -z "$TEAM_ID" && -f appleApp/Signing.local.xcconfig ]]; then
    TEAM_ID="$(sed -nE 's/^[[:space:]]*DEVELOPMENT_TEAM[[:space:]]*=[[:space:]]*//p' \
        appleApp/Signing.local.xcconfig | head -n 1 | tr -d '[:space:]')"
fi

if [[ -n "${DEVELOPMENT_TEAM:-}" ]]; then
    xcodebuild -project appleApp/CardvDashcam.xcodeproj \
        -scheme CardvDashcam \
        -configuration Release \
        -destination 'generic/platform=iOS' \
        -archivePath "$ARCHIVE" \
        -allowProvisioningUpdates \
        DEVELOPMENT_TEAM="$DEVELOPMENT_TEAM" \
        archive
else
    xcodebuild -project appleApp/CardvDashcam.xcodeproj \
        -scheme CardvDashcam \
        -configuration Release \
        -destination 'generic/platform=iOS' \
        -archivePath "$ARCHIVE" \
        -allowProvisioningUpdates \
        archive
fi

APP="$ARCHIVE/Products/Applications/CardvDashcam.app"
ACTUAL="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$APP/Info.plist")"
if [[ "$ACTUAL" != "$VERSION" ]]; then
    echo "Archived version $ACTUAL does not match version.xcconfig ($VERSION)." >&2
    exit 1
fi

echo "Archived $VERSION to $ARCHIVE"

if (( EXPORT == 1 )); then
    EXPORT_OPTIONS="tools/apple/ExportOptions-appstore.plist"
    if [[ -n "$TEAM_ID" ]]; then
        EXPORT_OPTIONS="$TMP_DIR/ExportOptions-appstore.plist"
        cp tools/apple/ExportOptions-appstore.plist "$EXPORT_OPTIONS"
        /usr/libexec/PlistBuddy -c "Add :teamID string $TEAM_ID" "$EXPORT_OPTIONS"
    fi

    xcodebuild -exportArchive \
        -archivePath "$ARCHIVE" \
        -exportOptionsPlist "$EXPORT_OPTIONS" \
        -exportPath "$EXPORT_DIR" \
        -allowProvisioningUpdates
    echo "Exported IPA to $EXPORT_DIR"
fi
