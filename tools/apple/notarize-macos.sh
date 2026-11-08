#!/usr/bin/env bash
set -euo pipefail

# Build, sign, package, notarize and staple a macOS DMG with a Developer ID certificate.
#
# Required:
#   DEVELOPMENT_TEAM       ten-character Apple team ID
#   MACOS_SIGN_IDENTITY    e.g. "Developer ID Application: Your Name (TEAMID)"
#                          (defaults to "Developer ID Application")
#
# Notary credentials, one of:
#   NOTARY_PROFILE         a keychain profile created with
#                          xcrun notarytool store-credentials <profile> --apple-id ... --team-id ...
#   ASC_API_KEY_PATH       path to AuthKey_<KEYID>.p8
#   ASC_API_KEY_ID         App Store Connect API key ID (with ASC_API_KEY_PATH)
#   ASC_API_ISSUER         App Store Connect issuer UUID (with ASC_API_KEY_PATH)
#
# Usage: tools/apple/notarize-macos.sh
#
# The DMG and its .sha256 file land in build/apple/macos/.

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

if [[ -z "${DEVELOPMENT_TEAM:-}" ]]; then
    echo "DEVELOPMENT_TEAM is required." >&2
    exit 2
fi

IDENTITY="${MACOS_SIGN_IDENTITY:-Developer ID Application}"
VERSION="$(tools/release/check-version.sh --print)"
ARCHIVE="build/apple/macos/CardvDashcam.xcarchive"
OUT_DIR="build/apple/macos"
STAGING="$(mktemp -d)"
trap 'rm -rf "$STAGING"' EXIT

rm -rf "$ARCHIVE" "$OUT_DIR/CardvDashcam.dmg" "$OUT_DIR/CardvDashcam.dmg.sha256"
mkdir -p "$OUT_DIR"

xcodebuild -project appleApp/CardvDashcam.xcodeproj \
    -scheme CardvDashcam \
    -configuration Release \
    -destination 'generic/platform=macOS' \
    -archivePath "$ARCHIVE" \
    -allowProvisioningUpdates \
    DEVELOPMENT_TEAM="$DEVELOPMENT_TEAM" \
    CARDV_MACOS_SIGN_IDENTITY="$IDENTITY" \
    archive

APP="$ARCHIVE/Products/Applications/CardvDashcam.app"
if [[ ! -d "$APP" ]]; then
    echo "No app at $APP" >&2
    exit 1
fi

ACTUAL="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$APP/Contents/Info.plist")"
if [[ "$ACTUAL" != "$VERSION" ]]; then
    echo "Archived version $ACTUAL does not match version.xcconfig ($VERSION)." >&2
    exit 1
fi

codesign --verify --deep --strict --verbose=2 "$APP"
codesign -dv --verbose=4 "$APP" 2>&1 | grep -E 'Authority|TeamIdentifier|Runtime Version' || true

cp -R "$APP" "$STAGING/CardvDashcam.app"
ln -s /Applications "$STAGING/Applications"

DMG="$OUT_DIR/CardvDashcam.dmg"
hdiutil create \
    -volname "CarDV Dashcam" \
    -srcfolder "$STAGING" \
    -ov -format UDZO \
    "$DMG"
codesign --force --sign "$IDENTITY" --timestamp "$DMG"

if [[ -n "${NOTARY_PROFILE:-}" ]]; then
    xcrun notarytool submit "$DMG" --keychain-profile "$NOTARY_PROFILE" --wait
elif [[ -n "${ASC_API_KEY_PATH:-}" && -n "${ASC_API_KEY_ID:-}" && -n "${ASC_API_ISSUER:-}" ]]; then
    xcrun notarytool submit "$DMG" \
        --key "$ASC_API_KEY_PATH" \
        --key-id "$ASC_API_KEY_ID" \
        --issuer "$ASC_API_ISSUER" \
        --wait
else
    echo "No notary credentials. Set NOTARY_PROFILE or" >&2
    echo "ASC_API_KEY_PATH + ASC_API_KEY_ID + ASC_API_ISSUER." >&2
    exit 2
fi

xcrun stapler staple "$DMG"
xcrun stapler validate "$DMG"
spctl -a -vv -t open --context context:primary-signature "$DMG"

shasum -a 256 "$DMG" > "$DMG.sha256"

echo "Notarized and stapled: $DMG"
echo "Checksum: $DMG.sha256"
