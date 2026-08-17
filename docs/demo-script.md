# The five-minute demo (2026-08-09 build)

The path below hits every wow-beat we built, in narrative order. Local dev
server (`make server-dev`, port 20500), signed in as Gene, demo data seeded
(the charlotte-2026 event carries 51 submissions from `seed-flood`).

## Beat 1 — "Zero to open CFP" (the wizard, ~60s)

1. `/events` → **+ New event**. Type a name and watch the marquee write
   itself — the derived display name and slug appear as you type (server-
   rendered, every keystroke).
2. Point at the sidebar: the **event masthead card** names the room you're
   standing in; the pill counts your events; ⇅ switches.
3. Walk the wizard spine: **Create / edit event** (the pitch that sells the
   conference on the public page) → **Create / edit CFP form** → committee.

## Beat 2 — the form builder (the living preview, ~60s)

`/events/<slug>/form` — "Questions" beside "**What speakers see**": the
right column IS the public page, morphed live over SSE as you edit. Edit a
label in the modal; watch the preview repaint without a reload. Green
forward act top-right.

## Beat 3 — the public CFP page (~90s, the speed story)

**Open an incognito window** — no account, no wall (doctrine: account created
on submit).

- The hero sells: pitch (markdown), close date, official site.
- **Tab through the empty form** — every field ghost-fills with a plausible
  example. An empty form becomes a credible submission in ~10 keypresses.
- **The Sessionize import**: type `realgenekim` (just the username), press
  Enter — the About You block fills in place, NO page reload, your half-typed
  abstract untouched. Name, bio, headshot, LinkedIn arrive from the real
  profile.
- Submit → confirmation page + speaker portal link.

## Beat 4 — the review room at volume (~90s)

`/events/enterprise-ai-summit-charlotte-2026/board` — 81 submissions.

- **The momentum sparkline** (top right): green line = submissions arriving
  across the call; gold fill = talks that reached 2 reviews; the wedge
  between them IS the committee's backlog. "81 submissions · 24 fully
  reviewed (30%) · days left" in one glance.
- **THE LEDGER**: one line per talk, every column heading sorts, real
  headshots (photo/name click straight into the talk), a GOLD star
  histogram per row, and every comment in the table — full text, with the
  author's stars riding their first comment.
- **The track tag cloud**: click "AI Models 6" and the table narrows; the
  "no track" bucket is the mid-call-form-edit story — old submissions keep
  their snapshot, honestly.
- **Quick rate ▾ — the money moment**: the card opens IN PLACE (the whole
  submission lifts onto one white envelope), click stars rapid-fire — the
  numbers move via SSE and NOTHING navigates, the scroll never twitches.
  Type a comment, Enter — box clears, quote-line appears. Esc closes.
- **Chairs decide, reviewers argue**: Set submission status exists only for
  chairs — switch identity in the dev strip and watch it disappear; the
  endpoint refuses non-chairs too.
- (The old Submissions page is retired — one table, the board, carries the
  count in the rail.)

## Beat 5 — the time machine (the closer, ~30s)

The dev strip's scrubber, full viewport width. Drag it slowly left —
**the board repaints WHILE you drag** (throttled one-shot SSE, no reload,
no persistent connection): submissions vanish, ratings unwind, the
sparkline redraws. Nothing was snapshotted; the log was always the truth
and the screen was always derived. This is the event-sourcing party trick
no incumbent can follow.

*(Everything above also exists on prod with Google sign-in + Postgres —
minus tonight's commits until the next deploy.)*

## Reset notes

- Wrong identity after the drawer demo → switch back to Gene in the drawer.
- Need a clean flood: `(cfp-scheduler-killer.seed-flood/flood! "<slug>" 50)`
  in the REPL (append-only — floods add, never reset).
- The markdown intro upgrade (markdown-clj) activates on next server restart.
