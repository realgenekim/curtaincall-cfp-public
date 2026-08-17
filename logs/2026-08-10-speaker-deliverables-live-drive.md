# Speaker deliverables — acceptance drive

**2026-08-10 · bead `sessionize-sched-killer-n1b.1`**

## Scope and safety boundary

The browser-facing dev server on `localhost:20500` shares the production
database. The live drive therefore remained read-only after authentication.
All mutation and re-fold proof ran against the JSONL sandbox and pure fold
tests. No DDL, direct database write, deploy, or production obligation fact was
created.

## Live browser proof

Signed in through the allowlisted **Organizer · swyx** persona, then opened:

`/events/enterprise-ai-summit-charlotte/deliverables`

The browser-serving JVM rendered:

- the new **Speaker deliverables** sidebar link under **Decide & tell**;
- 11 real open obligations for Dana Whitfield, Marcus Devlin, and Priya
  Raghavan;
- speaker name, session title, deliverable, derived due date, last touch, and a
  human follow-up control on every row;
- bio/headshot deadlines of 2026-09-14 and slides deadlines of 2026-09-23,
  derived from the event's current 2026-10-14 start date;
- explicit **Record follow-up** buttons; and
- the literal safety promise: **“This page records human follow-up. It never
  sends an automatic reminder.”**

The other Charlotte demo event, which has no informed accepted speaker tasks,
rendered the empty state instead of an error. No live follow-up was submitted.

## Algebraic and unit proof

The pure obligation suite proves:

- moving an event recomputes deadlines without rewriting task facts;
- legacy task facts receive evidence-based offsets without migration;
- outstanding rows sort most overdue first;
- completion removes a task from the outstanding projection;
- missing event dates yield `:unscheduled`, not an exception;
- `:due-soon` is distinct from `:upcoming` and `:due-today`;
- completion retries are successful no-ops;
- chase IDs make human follow-up retries idempotent; and
- blank actors cannot manufacture a human touch.

Compatibility tests also preserve the old public adapter contract: unknown
tasks and blank URL submissions remain harmless no-ops, while the pure decision
layer returns explicit rejection values.

Latest fast proof: **303 tests, 3,124 assertions, 0 failures**.

## JSONL HTTP proof

The e2e organizer-page loop now includes `/events/<slug>/deliverables`, so this
route cannot disappear without failing the drive. The sandbox drive passed
**259/259 checks**, including the new page's 200 response and absence of error
text. The sandbox server ran on port 20501 and was stopped after the drive.
