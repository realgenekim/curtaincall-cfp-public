# Speaker roster — acceptance drive

**2026-08-10 · bead `sessionize-sched-killer-n1b.2`**

## Outcome

The application now has an event-scoped speaker participation model instead of
forcing three different ideas into a submission:

- a global person remains the stable identity;
- a submission keeps the exact immutable speaker snapshot received with that
  talk; and
- an event-local participation owns organizer status and event details.

Existing submission speakers appear in the roster by projection, without a
migration. Organizer actions append `speaker.added-to-event`,
`speaker.status-changed`, and `speaker.details-updated` facts. Normalized email
reuses an existing person, event-local changes do not leak across events, and
identical retries append nothing.

## Organizer surface

`GET /events/:slug/speakers` renders one searchable/filterable row per speaker,
including sessions, profile completeness, event status, and event-local edit
fields. The literal statuses **Invited** and **Confirmed** appear in the UI.
Named POST handlers cover manual add, status, details, CSV preview, and CSV
confirmation. The organizer navigation now links **Speakers** immediately before
**Speaker deliverables**.

The CSV path accepts pasted text or a real multipart `.csv` upload. It supports
common header aliases, quoted commas/newlines/doubled quotes, normalized email,
first-name + last-name composition, row-level validation, within-file duplicate
detection, a read-only preview, and one append batch on confirmation. A retry is
an explicit no-op.

## Proof

- Pure decisions/projections: 6 tests / 22 assertions / 0 failures.
- New speaker-domain, CSV, shell, view, and route coverage contributed 15 tests
  and 76 assertions over the previous slice.
- Final full unit suite: **318 tests / 3,200 assertions / 0 failures**, run
  non-fail-fast after the final casing fix below.
- Cold JSONL HTTP drive: **261/261 checks passed**, including the new organizer
  Speakers page; sandbox server stopped afterward.
- Complete route topology: **148 routes** — 60 GET / 88 POST — fingerprint
  `2604e86a1fdff65d3bc31affa9ddcd22a7cb754751d7e5f1e21123c87c343ee9`.

## Live read-only finding

The signed-in dev server rendered seven real Charlotte speakers, their emails,
session joins, search/filter controls, manual-add form, and file/paste import
controls. No live control was submitted because localhost shares the production
database.

That drive caught a historical-shape bug: accepted/informed speakers displayed
as Submitted because the initial pure projection matched only lowercase
`"accepted"`. The projection now normalizes string/keyword status casing, and
the test fixture deliberately uses `"Accepted"`. The focused regression is
green. A subsequent visual refresh timed out during hot reload and the browser
became unavailable; there was no mutation and no repeated recovery loop.
