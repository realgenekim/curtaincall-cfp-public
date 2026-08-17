# Review-plan live drive — 2026-08-10

## Scope

Live acceptance of `sessionize-sched-killer-7wy.4` against the sanctioned
JSONL server at `http://localhost:20500`. No PostgreSQL, DDL, gcloud, or deploy
operations were used.

Disposable event:
`enterprise-tech-leadership-summit-vegas-394202-las-vegas-2027`.

## Automated gates

- Focused route integration: 3 tests, 38 assertions, 0 failures.
- Full unit suite: 296 tests, 3,082 assertions, 0 failures.
- `make runtests-once`: 296 tests, 3,082 assertions, 0 failures.
- Full JSONL drive: 257/257 checks passed, including persistence after re-fold.

## Browser drive

1. Signed in as Gene Kim, the event chair.
2. Confirmed the chair board rendered `Review plan`, `Blind review · Disabled`,
   and `Create review round`.
3. Created `Live verification`, dated 2026-08-01 through 2026-08-31.
4. Created `Final review`, dated 2026-09-01 through 2026-09-15.
5. Added Ann Perry to the `Live verification` reviewer pool.
6. Activated `Live verification`; the board rendered both
   `Live verification · Active round` and `Active round: Live verification`.
7. Enabled blind review.
8. Switched the dev identity to Ann Perry. On both the board and submission
   detail, the UI rendered `Anonymous speaker`; `Dana Whitfield`,
   `Northwind Freight`, and co-speaker labels were absent. The search prompt
   changed to `Search title, track, format…`.
9. Switched back to Gene Kim. The chair detail again rendered Dana Whitfield
   and Northwind Freight, proving that blind projection does not destroy or
   hide chair context.
10. Activated `Final review`. The board rendered `Final review · Active round`
    and no longer marked `Live verification` active, exercising the advance
    fact rather than a second independent flag.
11. Disabled blind review. The board returned to `Blind review · Disabled`.

## Observation

Datastar streaming mutations can make a conventional browser driver wait for
request completion even after the DOM patch has landed. A fresh DOM snapshot
is the correct acceptance signal for these interactions; `networkidle` is not.
One already-open streamed tab retained an older DOM after the second round was
created, while a fresh tab rendered both authoritative folded rounds. No server
or projection defect was present.

## Result

PASS. The optional plan remains absent by default; the live configured path
supports two rounds, reviewer pools, activation/advance, blind on/off, and
role-safe board/detail projections.
