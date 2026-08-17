# Phase 3 agenda extras — acceptance

Date: 2026-08-10 PDT
Bead: `sessionize-sched-killer-nvc`

## Outcome

The four remaining Phase 3 surfaces are implemented without adding a second
state model or weakening the organizer-controlled workflow:

1. Organizer session editing, complete per-object revision history, and
   restore-as-a-new-fact.
2. An organizer-gated embed builder producing working iframe, link, JSON, and
   iCal handoffs.
3. An anonymous attendee `My schedule`, persisted by signed session-cookie
   viewer ID, with per-selection iCal.
4. An explicit, conservative `Suggest schedule` action that fills only
   unplaced sessions through `schedule/place!`.

## Event algebra

- Existing canonical fact reused: `submission.answers-updated`.
  - Organizer and speaker portal changes now share
    `submission-content/update-answers!`.
  - A restore carries `:restored-from-log-index` and appends another
    `submission.answers-updated`; no state is rewound or deleted.
- New fact: `agenda.session-starred`.
  - Fold adds the submission ID to
    `[:agenda-selections [event-id anonymous-viewer-id]]`.
- New fact: `agenda.session-unstarred`.
  - Fold removes the submission ID from the same event-scoped projection.
- Schedule suggestions add no special fact. Every applied suggestion uses the
  existing `schedule/place!` verb and therefore emits `slot.assigned`.

## Permanent contracts

- Content edits validate against the submission's form snapshot and preserve
  fields absent from a partial request.
- Revision history is reconstructed from exact log indices, not timestamps.
- Only a chair sees or invokes organizer content controls; mismatched event
  routes are refused.
- Personal schedules contain only placed, publishable sessions and are isolated
  by signed anonymous session ID. No PII is stored in their facts.
- The suggestion plan never moves existing placements, respects global and
  room blocks, prevents room/speaker overlap, and is idempotently empty after
  all schedulable sessions have been placed.
- Route topology is characterized at 169 routes: 68 GET and 101 POST, SHA-256
  `3f1ce4df9e26d7f2d1cbaa1ee2eeedaae11d398918c22b6a81f9a4de3b81952f`.

## Exact visible strings

- `Session editing`
- `Save session changes`
- `History`
- `Restore this version`
- `Embed builder`
- `Widget type`
- `Copyable snippet`
- `Working preview`
- `My schedule`
- `☆ Add to My schedule`
- `★ In My schedule`
- `Download my schedule (.ics)`
- `Suggest schedule`
- `without moving anything already placed`

## Verification evidence

- Organizer content focused gate: 13 tests / 146 assertions / 0 failures.
- Embed + content focused gate: 15 / 161 / 0.
- Widgets/exports/embed/content/board gate: 36 / 547 / 0.
- Schedule focused gate: 18 / 170 / 0.
- Architecture/route gate: 7 / 348 / 0.
- Full non-fail-fast suite: **347 tests / 3,438 assertions / 0 failures**.
- Fresh JSONL JVM on port 20501 loaded 1,482 existing facts and registered the
  new folds without warm-REPL assistance.
- Cold HTTP drive: **263 / 263 checks passed**.
- The isolated server was stopped after the drive; port 20501 had no listener.

## Still operator-gated

This acceptance does not claim deployment, production persona controls,
production AIE seed state, or incognito/8-tab production verification. Those
remain under `sessionize-sched-killer-9pq` and require the morning operator.
