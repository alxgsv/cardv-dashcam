#!/usr/bin/env bash
# Live RTSP test stream for development without a car.
#
# The fake camera implements the HTTP API only; live view needs a real RTSP server.
# This script runs MediaMTX on port 8554 and publishes a 720p H.264 + AAC test
# pattern into it with ffmpeg, matching the camera's liveRTSP/av2 stream.
#
#   tools/fake-camera/start-live-stream.sh
#
# Then in a debug app open Developer options and set the live RTSP URL to
#   rtsp://127.0.0.1:8554/live
# or launch with CARDV_LIVE_URL=rtsp://127.0.0.1:8554/live.
#
# Requires ffmpeg and mediamtx on PATH. Press Ctrl-C to stop both.

set -euo pipefail

PORT="${RTSP_PORT:-8554}"
PATH_NAME="${RTSP_PATH:-live}"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/cardv-live.XXXXXX")"

for tool in ffmpeg mediamtx; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "error: $tool is not installed" >&2
        echo "  macOS: brew install ffmpeg mediamtx" >&2
        exit 1
    fi
done

cat >"$WORK_DIR/mediamtx.yml" <<EOF
logLevel: info
logDestinations: [stdout]
rtsp: yes
rtspAddress: :$PORT
rtspTransports: [tcp]
rtmp: no
hls: no
webrtc: no
srt: no
paths:
  all_others:
EOF

cleanup() {
    trap - EXIT INT TERM
    [[ -n "${FFMPEG_PID:-}" ]] && kill "$FFMPEG_PID" 2>/dev/null || true
    [[ -n "${MEDIAMTX_PID:-}" ]] && kill "$MEDIAMTX_PID" 2>/dev/null || true
    wait 2>/dev/null || true
    rm -rf "$WORK_DIR"
    echo
    echo "live stream stopped"
}
trap cleanup EXIT INT TERM

# Run inside the work directory: MediaMTX writes its MoQ certificates to the current directory.
(cd "$WORK_DIR" && exec mediamtx mediamtx.yml) &
MEDIAMTX_PID=$!

# Give the RTSP listener a moment before publishing.
sleep 1

echo "publishing 1280x720 H.264 + AAC 32 kHz on rtsp://127.0.0.1:$PORT/$PATH_NAME"
ffmpeg -hide_banner -loglevel warning -re \
    -f lavfi -i "testsrc2=size=1280x720:rate=30" \
    -f lavfi -i "sine=frequency=440:sample_rate=32000" \
    -c:v libx264 -profile:v main -preset veryfast -tune zerolatency \
    -b:v 2000k -g 30 -pix_fmt yuv420p \
    -c:a aac -ar 32000 -ac 2 \
    -f rtsp -rtsp_transport tcp "rtsp://127.0.0.1:$PORT/$PATH_NAME" &
FFMPEG_PID=$!

wait "$FFMPEG_PID"
