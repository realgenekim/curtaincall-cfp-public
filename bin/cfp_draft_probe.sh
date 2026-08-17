#!/bin/bash
# SSE truth probe for the PUBLIC CFP page's live lane (bd …-td8).
#
# Proves three things a 204 cannot:
#   1. A debounced draft POST produces fragments on THIS viewer's real stream.
#   2. TWO CONCURRENT ANONYMOUS sessions get their OWN fragments and never see
#      each other's — the whole point of keying on the ring session's viewer-id
#      instead of a global slot (50 simultaneous speakers must not cross).
#   3. The draft survives the tab: a fresh GET with the same cookie jar repaints
#      what was typed.
#
# Nobody signs in anywhere in this script. That is deliberate: the speaker side
# is anonymous until submit, so the probe must be too.
set -uo pipefail
BASE="${1:-http://localhost:20500}"
SLUG="${2:-enterprise-ai-summit-charlotte-2026}"
D="$(mktemp -d)"; trap 'rm -rf "$D"' EXIT
JA="$D/jarA"; JB="$D/jarB"; SA="$D/streamA"; SB="$D/streamB"

# Distinct over-cap titles: the server's live note quotes the exact character
# count, so each viewer's fragment carries a number the other one cannot have.
TITLE_A=$(python3 -c "print('A'*400)")
TITLE_B=$(python3 -c "print('B'*301)")

# 1. Two strangers open the page. Each GET mints a viewer-id into its session.
curl -s -c "$JA" -o "$D/pageA.html" "$BASE/cfp/$SLUG"
curl -s -c "$JB" -o "$D/pageB.html" "$BASE/cfp/$SLUG"
grep -q "cfp-live" "$D/pageA.html" || { echo "FAIL: no SSE mount on the page"; exit 1; }
grep -q "ring-session" "$JA" || { echo "FAIL: no session cookie was minted"; exit 1; }
[ "$(grep ring-session "$JA" | awk '{print $NF}')" != "$(grep ring-session "$JB" | awk '{print $NF}')" ] \
  || { echo "FAIL: both anonymous visitors share one session cookie"; exit 1; }
echo "OK: two distinct anonymous sessions"

# 2. Both open their own stream.
curl -s -N -b "$JA" "$BASE/api/cfp/$SLUG/stream" > "$SA" 2>&1 &
PA=$!
curl -s -N -b "$JB" "$BASE/api/cfp/$SLUG/stream" > "$SB" 2>&1 &
PB=$!
sleep 1

# 3. Each types. This is exactly what Datastar's contentType:'form' POST sends.
echo -n "draft POST A: "
curl -s -b "$JA" -o /dev/null -w "%{http_code}\n" -X POST \
  --data-urlencode "answer-talk-title=$TITLE_A" \
  --data-urlencode "answer-prior-talk-video=notaurl" \
  "$BASE/api/cfp/$SLUG/draft"
echo -n "draft POST B: "
curl -s -b "$JB" -o /dev/null -w "%{http_code}\n" -X POST \
  --data-urlencode "answer-talk-title=$TITLE_B" \
  "$BASE/api/cfp/$SLUG/draft"
sleep 1; kill "$PA" "$PB" 2>/dev/null || true

echo "patch events — A: $(grep -c datastar-patch-elements "$SA" || true)  B: $(grep -c datastar-patch-elements "$SB" || true)"

ok=0
chk() { if [ "$2" = 0 ]; then echo "OK: $1"; else echo "FAIL: $1"; ok=1; fi; }

grep -q "400 characters" "$SA"; chk "A's own over-cap note arrived on A's stream" $?
grep -q "301 characters" "$SB"; chk "B's own over-cap note arrived on B's stream" $?
grep -q "301 characters" "$SA"; [ $? != 0 ]; chk "A never saw B's note (no cross-talk)" $?
grep -q "400 characters" "$SB"; [ $? != 0 ]; chk "B never saw A's note (no cross-talk)" $?
grep -q "cfp-draft-status" "$SA"; chk "A got a draft-status fragment" $?
grep -q "cfp-draft-status" "$SB"; chk "B got a draft-status fragment" $?
grep -q "full link" "$SA"; chk "A got the bad-URL note" $?
grep -q "full link" "$SB"; [ $? != 0 ]; chk "B got no bad-URL note (B typed no URL)" $?

# 4. The tab dies. Reopen with the same cookie jar — the typing must come back.
curl -s -b "$JA" -o "$D/pageA2.html" "$BASE/cfp/$SLUG"
grep -q "$TITLE_A" "$D/pageA2.html"; chk "A's draft survived a fresh page load" $?
grep -q "$TITLE_A" "$D/pageB.html"; [ $? != 0 ]; chk "B's page never carried A's draft" $?
grep -q "Picked up where you left off" "$D/pageA2.html"; chk "A is told the draft was restored" $?

[ "$ok" = 0 ] && echo "PROBE PASS" || { echo "PROBE FAIL — A stream tail:"; tail -6 "$SA"; }
exit $ok
