#!/bin/bash
# SSE truth probe — proves the full live-update loop against a RUNNING server.
# A 204 response and green tests CANNOT prove SSE works (both lied on
# 2026-08-09); only a fragment arriving on a real stream can. Usage:
#   bin/sse_probe.sh [base-url] [email]
# Exit 0 = a marquee fragment with the typed name arrived on the stream.
set -euo pipefail
BASE="${1:-http://localhost:20500}"
EMAIL="${2:-genek@itrevolution.net}"
D="$(mktemp -d)"; trap 'rm -rf "$D"' EXIT
J="$D/cookies"; S="$D/stream"

# Dev sign-in: the magic link is printed in the login response body.
TOKEN=$(curl -s -c "$J" -X POST -d "email=$EMAIL" "$BASE/api/login" \
        | grep -oE '/auth/[A-Za-z0-9-]+' | head -1)
[ -n "$TOKEN" ] || { echo "FAIL: no magic link (is ENV=dev? is $EMAIL a reviewer?)"; exit 1; }
curl -s -b "$J" -c "$J" -o /dev/null "$BASE$TOKEN"

curl -s -N -b "$J" "$BASE/api/sse?event-id=new-event" > "$S" 2>&1 &
SSE_PID=$!; sleep 1

curl -s -b "$J" -o /dev/null -X POST -H "Content-Type: application/json" \
  -d '{"evname":"SSE Probe Event","evstarts":"2026-10-07","evends":"2026-10-08"}' \
  "$BASE/api/events/preview"
sleep 1; kill "$SSE_PID" 2>/dev/null || true

if grep -q "SSE Probe Event" "$S"; then
  echo "OK: marquee fragment arrived on stream ($(grep -c 'datastar-patch-elements' "$S") patch event(s))"
else
  echo "FAIL: preview POST accepted but nothing arrived on the stream."
  echo "  Check: GET $BASE/dev/sse-state · server log for :preview-push-no-subscriber"
  exit 1
fi
