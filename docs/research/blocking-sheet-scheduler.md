# The blocking-sheet scheduler: be the spreadsheet, with superpowers

*2026-08-08. The design insight from the 10-year Slack archive: ITRev had
BusyConf's drag-and-drop grid for three years and STILL ran scheduling in a Google
Sheet named "Schedule Blocking" — pinned every year, 2016 through 2022+. The
scheduling never happened in the CFP tool or in Sched; it happened in Sheets.
Sessionboard's own sandbox Agenda in swyx's demo is EMPTY ("Nothing here yet").*

## Why the spreadsheet always wins

Schedule **builders** demand complete data: a session cannot exist on the grid
without a room and a time. Schedule **building** is a negotiation full of partial
states:

- "maybe Tuesday" / "keynote TBD" / "one of these three, pending confirm"
- color codes carrying side-channel meaning ("ignore some of the color coding…
  it has to do with timezones" — jessmeyer, 2020)
- slot arithmetic in the margins ("We've filled 27 slots and we have 30
  remaining… accept 15 this week and 15 next" — 2018; "Total Spots: 63 • 15
  Plenary • 45 Breakouts • 3 DevOps Confessions" — 2021)
- notes-in-cells, parallel what-if drafts, placeholder blocks

Spreadsheets tolerate ambiguity; grids force premature commitment. Every
incumbent's scheduler is a *publishing* surface pretending to be a *thinking*
surface. Corroborating spec detail from swyx's Discord: "usually initial cal
invite has no details then we assign room later" — **rooms are assigned late and
incrementally** in his workflow too.

## The design: draft-first scheduling

1. **Partial states are first-class.** A session can be scheduled with no room,
   no time, or as a "block" (Keynote TBD, Lunch, one-of-N group). Unassigned
   sessions live in a tray, not an error state.
2. **Live slot arithmetic** in a persistent status bar: total slots by type
   (plenary/breakout/workshop), filled, remaining, per-day and per-track — the
   thing Jess and Ann computed by hand in Slack every single August.
3. **Continuous conflict detection over the partial schedule**: speaker
   double-booked, room overlap, speaker-unavailable-that-day, track collision —
   surfaced as chips/tooltips naming the person and both sessions, with offered
   fixes, never as validation errors that block a save.
4. **What-if drafts**: named schedule drafts (branch/merge-lite), so "what if we
   move the SRE track to Wednesday" is a copy, not a destructive edit.
5. **Notes and color live on the cell**: annotations on any placement (the
   timezone hack, the "pending co-presenter" flag) — the side-channel the sheet
   gave them, kept.
6. **The harden step**: one action publishes the grid (public agenda + embed),
   generates/re-sends the .ics invites with late-assigned room details (stable
   UIDs so updates amend, not duplicate), and locks a snapshot. Draft → published
   is a deliberate transition, not a continuous leak.
7. **Sheet-shaped escape hatch**: one-click export (and re-import) of the
   blocking sheet as CSV/Sheets, because trust is earned — let the team keep
   their old tool alongside until the new one wins on merit.

## Fit with the rest of the design

The tray + grid was already in the mockups; this doc upgrades it from "drag-drop
grid with conflict detection" (the incumbent's framing) to "the blocking sheet
with superpowers" (the field-evidence framing). Slot arithmetic, blocks, drafts,
cell notes, and the harden step are the additions. This is the second
differentiator alongside the review board — both sourced from a decade of watching
what the team actually did instead of what the tools wanted.
