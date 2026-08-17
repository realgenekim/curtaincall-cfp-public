# Live Scrub exact-prefix acceptance — 2026-08-10

Bead: `sessionize-sched-killer-srh`

## Cause

The slider is event-local and index-shaped, but its selected index was converted
to an `:at` timestamp. `state-as-of` then folded every fact whose timestamp was
less than or equal to that value. That is not equivalent to a serialized prefix:

- concurrent writers could assign default timestamps before acquiring the
  append lock, so timestamp order could disagree with log order; and
- two facts may intentionally or naturally have the same timestamp, so `<=`
  includes both even when the slider selected only the first.

The result was a historical lie: a submission could appear one scrub step
before its serialized creation fact.

## Fix

- `store/indexed-log-for-event` preserves both the event-local story and each
  fact's global append-only index.
- `time-travel-context` carries an exact `{:log-index ... :at ...}` selection.
- `store/state-at-log-index` folds the exact global vector prefix and caches it
  separately from timestamp projections.
- `with-as-of` uses the log index for state while binding the original `:at`
  value for display-time semantics.
- Default timestamp assignment for both `append!` and `append-all!` now occurs
  inside the serialization lock. Explicit operator-staged timestamps remain
  unchanged and may still run backward safely.
- Event-membership logic was extracted once and shared by indexed and ordinary
  event-log reads.

No fact shape, persisted timestamp, route, slider range, or visible timestamp
format changed.

## Deterministic regression

`board-fragment-test` now creates the entire event, both submissions, async sink
facts, and review facts under one identical timestamp. The previous
timestamp-cutoff implementation deterministically showed “Second talk” one step
early; the exact-prefix implementation excludes it.

`log-index-projection-is-independent-of-timestamps-test` separately proves that
both equal timestamps and timestamps running backward preserve serialized
prefix membership.

## Verification

- Hot nREPL load of store, web event seam, board handler, and dashboard handler:
  `:ok`.
- Focused store/polish/replay: 32 tests, 232 assertions, 0 failures.
- Post-`append-all!` lock rerun: 23 tests, 147 assertions, 0 failures.
- `make runtests-once`: 340 tests, 3,352 assertions, 0 failures.
- Full non-fail-fast `bin/kaocha unit`: 340 tests, 3,352 assertions, 0 failures.
- Original intermittent seed `1480203748`: 340 tests, 3,352 assertions, 0
  failures.
- Cold JSONL server plus HTTP drive: 263/263 checks.
- Sandbox stopped; port 20501 has no listener.

No PostgreSQL, production database, browser, cloud, DDL, deployment, or external
state was touched.
