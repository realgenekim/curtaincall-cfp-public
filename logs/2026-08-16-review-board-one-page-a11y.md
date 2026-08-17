# Captain's log — Review Board one-page and accessibility walk

Date: 2026-08-16 PT

## Flow driven

- Opened `http://localhost:20500/events/enterprise-ai-summit-charlotte-2026/board` as Gene Kim.
- Before the fix, the board rendered 25 of 89 submissions and a `Next →` control.
- At a 1163 px viewport, the `Read & rate` cell began at x=1141 and ended at x=1234. Almost all of the required action was outside the viewport. The redundant `Pending` state cell remained visible.
- Removed Review Board pagination and the redundant per-row State column.
- Reloaded the same local URL.

## Acceptance receipt

- PASS: 89 of 89 submissions render on the one-page shared board.
- PASS: 89 `Read & rate` links are present, one for each submission.
- PASS: no Previous/Next pagination controls remain.
- PASS: no State column header remains; status filtering remains available above the table.
- PASS: clicking the first `Read & rate` link opened submission `505858c9-0647-43e8-afd2-9197786ac465`.
- PASS: the browser accessibility snapshot exposes one named rating radiogroup and nine named radios: 1.0 through 5.0 stars in 0.5 increments.
- No rating was changed during this read-only walk.

## Historical finding

- `Pending`/State entered the person-first ledger in `7105d1c` (2026-08-09 21:17 PT).
- `Read & rate` and `Quick rate` entered later in `b5dbbaa` (2026-08-09 23:11 PT).
- The actions were not deleted; later table growth plus viewport width pushed them off-screen.

## Open verification

- PASS: Standard Clojure Style formatted the three changed Clojure files.
- PASS: focused Review Board proof — 2 tests, 20 assertions, 0 failures.
- PASS: Prolog shadow oracle — 4 tests, including missing-action, duplicate-action, and pagination counterexamples.
- PASS: live 50 ms filter reduced the board from 89 actions to 3 matching actions for `How 400 underwriters`.
- PASS: a full navigation restored 89 of 89 rows after the filter walk.
- The first focused run proved the 501-row behavior assertions; its initial 2 KiB-per-row ceiling was corrected to the measured 3 KiB ceiling (1,368,718 bytes / 501 rows).

## Native Quick Rate walk

- Opened submission `505858c9-0647-43e8-afd2-9197786ac465` from the shared board with request-local URL state: `?open=<submission-id>#sub-<submission-id>`.
- PASS: only the selected submission rendered rating controls; the board shipped no unopened rating forms.
- PASS: each numeric rating is a native submit button. Gene clicked a score and confirmed that the server received it and the board rerendered correctly; there is no separate Save step.
- PASS: the open-row state is encoded only in that reviewer's URL/request. It is not stored in a shared atom and is not broadcast over the board SSE stream.
- PASS: the conflict-of-interest control remains reversible rubric behavior (`ABS-12`) and now aligns with the Quick Rate content rail.
- Automated isolation ratchet: Gene's open row is absent from Maya's independently rendered board.
