# Four evaluator gaps — acceptance record

**Bead:** `sessionize-sched-killer-5ds`
**Scope:** ABS-06, ABS-11, CFP-02, and SPK-15
**Safety lane:** tests and local JSONL only; no PostgreSQL, DDL, cloud, deploy,
production browser, or real email delivery.

## Outcomes

### ABS-06 — distribute reviewers

The organizer can preview and confirm deterministic reviewer distribution. The
assignment respects the configured cap and optional track filter. It reuses the
existing assignment domain verbs and facts instead of creating a second review
ownership model.

Focused proof: 15 tests, 153 assertions, 0 failures.

### ABS-11 — multi-speaker roles

A submission can carry multiple speakers with explicit roles. Parsing,
validation, persistence, reload, organizer rendering, and speaker-facing
rendering share the same representation. The existing submission fact carries
the speaker collection; no parallel co-speaker store was introduced.

Focused proof: 5 tests, 109 assertions, 0 failures.

### CFP-02 — conditional form fields

An organizer can configure `Show when` against an earlier active field and an
exact answer. The relation is deliberately acyclic. Hidden required fields do
not block submission, stale hidden answers are discarded, and chained
conditions see only earlier visible answers. Initial render, live SSE repaint,
progress, notes, parsing, and validation use the same pure visibility fold.

The existing form-field fact records `:show-when`; no browser-only condition or
new mutation path was added.

Focused proof: 2 tests, 20 assertions, 0 failures. The first package-wide full
proof after this slice was 370 tests, 3,742 assertions, 0 failures.

### SPK-15 — event-scoped custom person fields

Organizers define text or textarea fields on an event. Speakers answer those
fields from the portal. Required validation is server-owned, answers survive a
JSONL reload, and the organizer roster renders them. A speaker cannot write a
value for an event in which they do not participate.

New facts:

- `speaker.custom-field-defined`
- `speaker.custom-values-updated`

The fold stores definitions by `[event-id field-id]` and values on the existing
`[event-id person-id]` speaker participation. Read projections normalize nested
map keys so warm state and JSONL-refolded state are observationally equal.

The central auth gate remains default-deny. The new speaker verb is one exact
URI pattern; the handler still proves event participation. The focused HTTP
test caught the missing allowlist entry before release.

Focused proof: 8 tests, 65 assertions, 0 failures.

## Regression found by the cold drive

The CFP conditional-field repaint initially rendered the draft status with
`saved?` hard-coded false. The cold HTTP drive caught the missing visible
`Saved` guarantee. `cfp-session-fields` now receives that state explicitly:
initial page render passes false and a successful draft stash passes true.

Focused conditional-form regression proof: 29 tests, 214 assertions, 0
failures.

## Final gates

- Route topology ratified: 187 routes, 71 GET / 116 POST, SHA-256
  `b14d8deaf5d2cbe08f04b15bb9dac7bfc58053611771538fc903d66533cab06f`.
- `make runtests-once`: 372 tests, 3,767 assertions, 0 failures.
- Full non-fail-fast `bin/kaocha unit`: 372 tests, 3,767 assertions, 0
  failures.
- Fresh JSONL JVM plus `bin/e2e_drive.py`: 263/263 HTTP checks passed.
- The isolated server was stopped after the drive; PostgreSQL was never used.

No commit or push was made.
