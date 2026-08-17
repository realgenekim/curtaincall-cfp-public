# Reviewer work live drive — 2026-08-10

Production-shaped local acceptance of reviewer progress, human-drafted nudges,
and reversible conflict recusal. The server ran on `localhost:20500` with the
JSONL backend; no PostgreSQL, Cloud Run, DDL, or external delivery service was
used.

## Automated proof

- Focused assignment/review/scorecard suite: 20 tests, 176 assertions, 0
  failures.
- Route, fold, and view architecture suite: 7 tests, 294 assertions, 0
  failures.
- Full unit suite: 283 tests, 2,942 assertions, 0 failures.
- Production-shaped JSONL drive: 257/257 checks passed.
- Route topology ratchet: 133 routes, 58 GET and 75 POST, SHA-256
  `aa759aa04b29080dd8151b3629c786b7f9bf97329722f23f5d2d6974616787b4`.

## Browser drive

On the Enterprise AI Summit Charlotte review board, the organizer assigned Ann
Perry to “Reading 40,000 Bills of Lading a Day.” The chair progress panel moved
to Assigned 1, Complete 0, Remaining 1. Selecting Ann opened an editable,
resolved draft that explicitly said recording creates history and does not send
email. After editing and recording, the board showed a timestamped last human
follow-up.

As Ann, the reviewer queue showed 0 of 1 complete and 1 remaining. Recusing with
a reason appended `reviewer.recused`, hid rating/comment controls, and changed
the queue to 0 of 0 complete and 0 remaining. Restoring appended
`reviewer.unrecused`, restored the controls, and returned the queue to 0 of 1
complete and 1 remaining. The organizer then removed the temporary assignment
through the existing inverse verb. The recorded nudge remains append-only
history by design; nothing was delivered.

One apparent restore failure was a browser-controller mouse-click that never
dispatched a request. Submitting the same visible button with Enter exercised
the real form and restored state immediately. Server telemetry showed no POST
for the failed controller click and the expected unrecuse POST for the keyboard
submission; no application defect or patch was required.

## Algebra and safety boundary

The pure decisions emit `reviewer.recused`, `reviewer.unrecused`, and
`reviewer.nudge-recorded`. Rejections, tenancy, required reason/message,
idempotence, and projection behavior are tested without a store. The shell only
appends/logs accepted facts. There is no email dependency in this slice, so the
human-send gate is structural rather than advisory.
