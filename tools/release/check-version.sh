#!/usr/bin/env bash
set -euo pipefail

# Read and validate the single version source, version.xcconfig.
#
# Usage:
#   tools/release/check-version.sh              validate the file
#   tools/release/check-version.sh v1.0.0       validate that a release tag matches it
#   tools/release/check-version.sh --print      print MARKETING_VERSION
#   tools/release/check-version.sh --build      print CURRENT_PROJECT_VERSION
#   tools/release/check-version.sh --code       print ANDROID_VERSION_CODE

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
VERSION_FILE="$ROOT/version.xcconfig"

if [[ ! -f "$VERSION_FILE" ]]; then
    echo "Missing $VERSION_FILE" >&2
    exit 2
fi

value() {
    sed -nE "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "$VERSION_FILE" \
        | head -n 1 | sed -E 's/[[:space:]]*$//'
}

VERSION="$(value MARKETING_VERSION)"
BUILD="$(value CURRENT_PROJECT_VERSION)"
CODE="$(value ANDROID_VERSION_CODE)"

case "${1:-}" in
    --print) echo "$VERSION"; exit 0 ;;
    --build) echo "$BUILD"; exit 0 ;;
    --code) echo "$CODE"; exit 0 ;;
esac

if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "MARKETING_VERSION must be MAJOR.MINOR.PATCH, got: '$VERSION'" >&2
    exit 1
fi
if [[ ! "$BUILD" =~ ^[0-9]+$ ]] || (( BUILD < 1 )); then
    echo "CURRENT_PROJECT_VERSION must be a positive integer, got: '$BUILD'" >&2
    exit 1
fi
if [[ ! "$CODE" =~ ^[0-9]+$ ]] || (( CODE < 1 )); then
    echo "ANDROID_VERSION_CODE must be a positive integer, got: '$CODE'" >&2
    exit 1
fi

if [[ $# -gt 0 ]]; then
    TAG="$1"
    if [[ "$TAG" != "v$VERSION" ]]; then
        echo "Release tag '$TAG' does not match version.xcconfig (expected 'v$VERSION')." >&2
        exit 1
    fi
fi

echo "version $VERSION, build $BUILD, Android versionCode $CODE"
