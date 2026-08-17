# Plan: perfect Step 2 — "Create CFP form" (parallel work stream)

*Handoff for a second coding agent. Target page: `/events/<slug>/form`
(e.g. /events/enterprise-ai-summit-charlotte-2026/form). Written 2026-08-09.
The main session is concurrently in auth-google.clj / login-page / sidebar /
server routes — STAY OUT of those. Your surface is the FORM BUILDER only.*

## Your files (and the coordination boundary)
- `src/cfp_scheduler_killer/views.clj` — **only** these fns:
  `form-builder-page`, `form-edit-panel`, `field-form-fields`, and the
  form-preview render. Do NOT touch `sidebar`, `login-page`, `new-event-page`,
  `event-dashboard-page`, `event-marquee`, `slug-status` (main session's).
  views.clj is SHARED — `git status` before every edit; if a hunk conflicts,
  ping the main session rather than force it.
- `src/cfp_scheduler_killer/forms.clj` — form domain (fields, add/retire/move,
  reviewed?). Safe to extend.
- `src/cfp_scheduler_killer/server.clj` — only the `handle-form-*` handlers and
  `with-form`. Do NOT touch the route table's auth/login/google area or
  `handle-events-preview`.
- `resources/public/css/app.css` — add form-builder rules in @layer page; use
  var(--tokens) ONLY, never hardcode a color. NO CSS in .clj files.
- Tests: `test/cfp_scheduler_killer/forms_test.clj` — extend, never delete.

## Read first
1. `CLAUDE.md` (Datastar lessons, no-JS/CSS-in-clj, token+nesting CSS).
2. The page live: sign in on :20500 (demo mode) → open a fresh event's /form.
   Current state: two-column (Questions | What speakers see), step-2 banner,
   green "Looks right — next: create review committee →", reorder/edit/retire,
   locked spine, live preview = the REAL public renderer.

## Ratified design (Gene chose "Editor + living preview" — implement, don't
## re-litigate)
The two-pane exists. Make it *sing*:

1. **Live preview must actually be live.** Today the preview only re-renders on
   full page loads (add/edit/retire POST → 303 → reload). Make editing a field
   push the updated `#form-preview` fragment over SSE per-viewer, the same
   machinery the create-page marquee uses:
   - The page already mounts SSE (`:sse? true`, `ds/sse-mount (:id event)`).
   - Add a debounced preview POST from the edit panel's inputs (ds/bind +
     `data-star-on:input__debounce.300ms "@post('/api/events/:slug/form/preview')"`).
   - New STATELESS handler `handle-form-preview`: reads the typed field attrs,
     renders the would-be public form (active fields + the in-progress edit
     applied on top), pushes `#form-preview` to THIS viewer
     (`sse/push-to-person!` on `(:id event)` + person-id), returns 204.
   - Auth: the route is `/api/events/:slug/form/preview` — the gate scopes it
     per event automatically (it's spelled like the other event routes). Add a
     gate test only if you find it doesn't resolve.
   - PROVE it with a curl script like `bin/sse_probe.sh` (adapt it): edit →
     assert a `#form-preview` fragment with the new label arrives on the stream.
2. **"Add a question" gets the same living preview** — typing the new label
   shows a ghost row appended to the preview before you submit.
3. **Empty/retired clarity:** a small count line "11 active · N retired · M
   private (committee-only)" under the header; a "Show retired" `<details>` that
   lists retired fields with a Restore button (restore route may already exist —
   check forms.clj/`handle-form-restore`).
4. **Warm Paper the builder cards** (page-scoped class, not global .ui.segment).
5. **Snapshot-safety banner** already exists ("N submissions already exist…") —
   keep it; make sure it shows when submission-count > 0.

## Hard rules
- No client JS beyond the sanctioned browser-owned kind. Preview updates are
  SERVER-pushed HTML, never client DOM edits.
- data-star-init only (never data-star-on-load); ds/bind for every bound input;
  `bin/sse_probe.sh` is truth, a 204 is not.
- Every save must compile (`clojure -M -e "(require 'cfp-scheduler-killer.views :reload)"`).
- Verify each item: curl + Chrome screenshot on :20500, THEN commit
  (author: `Gene Kim <genek@itrevolution.com>` — the repo's normal identity).
- Run the fast suite in the BACKGROUND between batches (`make runtests-once`,
  or `make runtests-fast` once the main session splits it out).
- Report per item: files changed, live evidence, test counts, anything unverified.

## Definition of done
Editing or adding a question updates the "What speakers see" pane within ~50ms
without a page reload, proven by an SSE probe; retired/private counts visible;
cards are Warm Paper; suite green; page Chrome-verified.
