# Plan: perfect the event Dashboard as the wizard hub ("page 2")

*Handoff plan for a coding agent. Target page:
`/events/<slug>` (e.g. /events/enterprise-ai-summit-charlotte-2026).
Written 2026-08-09 by the main session; every design point below is
Gene-ratified today — do not re-litigate, implement.*

## Read first (non-negotiable context)
1. `CLAUDE.md` — especially: Datastar hard-won lessons (data-star-init only,
   datastar-kit.js, ds/bind visibility, sse_probe), NO JS/CSS in .clj files,
   CSS = @layer + tokens + native nesting (change tokens, never hardcode).
2. `docs/auth.md` — the gate; anything you add under /events/:slug or
   /api/events/:slug is auto-scoped per event.
3. `src/cfp_scheduler_killer/views.clj` — `event-dashboard-page`, `sidebar`
   (the CREATE CFP wizard), `slug-status`, the launch-strip block. The step
   vocabulary is LAW: **Create event / Create CFP form / Create review
   committee** — one vocabulary everywhere (checklist already matches).
4. Verify tooling: `make runtests-once` IN THE BACKGROUND (fail-fast is on),
   `bin/sse_probe.sh`, `bin/e2e_drive.py`, and eyeball via Chrome on :20500.
   The dev server hot-reloads every save — EVERY save must compile
   (`clojure -M -e "(require 'cfp-scheduler-killer.views :reload)"`).

## Current state (verified today)
Launch strip (1-2-3 + green Open) when :not-open-yet; What's-next checklist
speaks step names and tracks real state; copy-URL button with toast; roster
card with add-reviewer; sidebar wizard with live ✓s. Warm Paper tokens loaded
but the CARDS are still default Fomantic segments.

## Work items, in order (small batches — commit-sized each)
1. **Warm Paper the cards**: `.ui.segment` on this page → paper cards
   (var(--card), 1px var(--line), var(--radius-lg), soft shadow on hover
   only). Do it via a page-scoped class (e.g. .dash-card added in Hiccup)
   in @layer page — do NOT restyle .ui.segment globally.
2. **Card order tells the story**: left column = Event card, then Program
   Committee (it is step 3 — give its header the step number chip "3" while
   setup is incomplete, same .sb-step-n visual); right column = What's next,
   then Submissions.
3. **Launch strip ↔ wizard consistency**: strip shows when cfp-state is
   :not-open-yet OR setup incomplete (mirror the sidebar's `launching?` rule
   — extract ONE fn, e.g. events/setup-incomplete?, used by both; DRY).
   Note current mismatch: sidebar uses setup-completeness, strip still uses
   :not-open-yet — this item FIXES that divergence.
4. **Submissions card empty state**: when call open + 0 submissions, show the
   public URL + copy button inline ("share it — this is the link speakers
   need") instead of the bare sentence.
5. **Second reviewer nudge**: while roster == 1, the add-reviewer form gets
   one muted line: "A call reviewed by one person is a monologue — the board
   is built for peers." (tone: docs/design/domain-model.md).
6. **Header dedupe**: the "All events" basic button in the page header
   duplicates the sidebar's — remove the button.

## Hard rules
- No client JS. All state server-side. POST → mutate → (SSE or redirect).
- Never write data-star-on-load; use (ds/sse-mount ...) if you need a stream
  (this page currently doesn't — don't add one without need).
- Tests: extend, never delete. The suite guards TODAY's ratified design.
- views.clj is SHARED — check `git status` before editing; if another agent
  has it mid-flight, coordinate through the main session.
- Verify each item live (curl + Chrome screenshot), THEN commit
  (author: forge-skiff <forge-skiff@skiff>), suite in background between
  batches. Report per item: what changed, evidence, test counts.
