#!/bin/bash
# SSE truth probe for the form-builder living preview (batch B+C).
# Proves: a debounced preview POST produces a #form-preview fragment on THIS
# viewer's real SSE stream — edit mode and add-ghost mode.
set -uo pipefail
BASE="${1:-http://localhost:20500}"
EMAIL="${2:-genek@itrevolution.net}"
SLUG="enterprise-ai-summit-charlotte-2026"
D="$(mktemp -d)"; trap 'rm -rf "$D"' EXIT
J="$D/cookies"; S="$D/stream"

TOKEN=$(curl -s -c "$J" -X POST -d "email=$EMAIL" "$BASE/api/login" \
        | grep -oE '/auth/[A-Za-z0-9-]+' | head -1)
[ -n "$TOKEN" ] || { echo "FAIL: no magic link"; exit 1; }
curl -s -b "$J" -c "$J" -o /dev/null "$BASE$TOKEN"

EV=$(curl -s -b "$J" "$BASE/events/$SLUG/form" \
     | grep -oE "event-id=[a-f0-9-]+" | head -1 | cut -d= -f2)
[ -n "$EV" ] || { echo "FAIL: no event id on form page"; exit 1; }
echo "event-id: $EV"

curl -s -N -b "$J" "$BASE/api/sse?event-id=$EV" > "$S" 2>&1 &
SSE_PID=$!; sleep 1

echo -n "edit POST: "
curl -s -b "$J" -o /dev/null -w "%{http_code}\n" -X POST \
  -H "Content-Type: application/json" \
  -d '{"fbelabel":"LIVE PREVIEW PROBE","fbereq":true,"fbehelp":"typed live over SSE"}' \
  "$BASE/api/events/$SLUG/form/preview?field-id=advice-to-peer"
echo -n "add POST:  "
curl -s -b "$J" -o /dev/null -w "%{http_code}\n" -X POST \
  -H "Content-Type: application/json" \
  -d '{"fbalabel":"GHOST QUESTION PROBE","fbatype":"text"}' \
  "$BASE/api/events/$SLUG/form/preview?mode=add"
sleep 1; kill "$SSE_PID" 2>/dev/null || true

PATCHES=$(grep -c "datastar-patch-elements" "$S" || true)
echo "patch events on stream: $PATCHES"
ok=0
grep -q "LIVE PREVIEW PROBE" "$S"    && echo "OK: edited label arrived"      || { echo "FAIL: edited label missing"; ok=1; }
grep -q "typed live over SSE" "$S"   && echo "OK: edited help arrived"       || { echo "FAIL: edited help missing"; ok=1; }
grep -q "GHOST QUESTION PROBE" "$S"  && echo "OK: ghost label arrived"       || { echo "FAIL: ghost label missing"; ok=1; }
grep -q "fb-ghost" "$S"              && echo "OK: ghost styling arrived"     || { echo "FAIL: fb-ghost missing"; ok=1; }
grep -q 'form-preview' "$S"          && echo "OK: #form-preview targeted"    || { echo "FAIL: no form-preview selector"; ok=1; }
[ "$ok" = 0 ] && echo "PROBE PASS" || { echo "PROBE FAIL — stream tail:"; tail -5 "$S"; }
exit $ok
