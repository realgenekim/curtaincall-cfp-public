# Plan: speaker submission + profile flow at create-page grade (parallel stream)

*Handoff for a coding agent. Bead: sessionize-sched-killer-td8 (Gene's dictated
spec lives there — read it verbatim with `bd show sessionize-sched-killer-td8`).
Target pages: `/cfp/<slug>` (public submission) and `/portal` (speaker home).
Benchmark for flow quality: `/events/new` — snappy, server-side everything,
live server-pushed feedback. Written 2026-08-09.*

## Coordination boundary (three streams are live — stay in your lane)

**Yours:**
- `views.clj` — ONLY: `cfp-page`, `cfp-closed-notice`, `cfp-success-page`,
  `speaker-input`, `speaker-inputs`, `profile-links`, `status-pill`,
  `task-row`, `portal-submission`, `edit-form`, `profile-form`, `portal-page`,
  and `answer-input` (**shared**: it also renders the organizer form-builder's
  live preview — that is BY DESIGN, "the preview cannot lie" — so restyle
  freely but never change its signature or make it organizer-aware).
- `server.clj` — ONLY the cfp/portal handlers (`render-cfp`,
  `handle-public-cfp`, `handle-cfp-import`, `handle-cfp-submit`,
  `handle-cfp-submitted`, `cfp-refusal-message`, `handle-portal*`,
  `own-submission`) + your NEW draft/preview handlers, with route lines added
  beside the existing `/cfp` + `/portal` routes.
- `sse.clj` — **additive only.** You need anonymous viewer keys (below); do
  NOT change the signatures of `push-to-person!` / `push-fragment!` /
  `push-personal-fragment!` — the create-page marquee, the form-builder
  living preview, and the board all depend on them as-is.
- `portal.clj`, `submissions.clj`, `sessionize_import.clj` — extend.
- `resources/public/css/app.css` — @layer page additions, `var(--token)` only.
- Tests: `submissions_test.clj`, `portal_test.clj` (extend, never delete) and
  **extend `bin/e2e_drive.py`** with the new speaker interactions.

**NOT yours (other live streams; `git status` before every views/server edit,
and if a hunk conflicts, report — never force):**
- `sidebar` / `organizer-shell` / `events-list-page` / `event-resume-path`,
  the form-builder fns (`form-*`, `field-*`, `fb-*`) and `handle-form-*`
  (this session's lane).
- `auth.clj`, `auth_google.clj`, `login-page`, the route table's auth/login
  area (the auth stream's lane). Anonymous sessions do NOT need auth.clj —
  mint via the ring session on your own handlers' responses.
- `store.clj` / `store_pg.clj` (P0 persistence stream may start).

## Read first
1. `CLAUDE.md` — the Datastar hard-won lessons (data-star-init only, ds/bind
   or the input is invisible, probe the stream — a 204 is not proof) and the
   nREPL rule (never `clojure -M`; `clj-nrepl-eval -p $(cat .nrepl-port)`).
2. `bd show sessionize-sched-killer-td8` — Gene's dictated requirements.
3. The pages live on :20500 (`bin/e2e_drive.py` shows the login + submit walk;
   dev magic link comes back in the `/api/login` response body).
4. Reference implementations in-repo: `handle-events-preview` (the marquee
   loop) and `handle-form-preview` (stateless per-viewer preview push) — your
   work generalizes exactly these patterns.

## The design (from the bead — implement, don't re-litigate)

1. **Anonymous viewer identity.** Speakers are anonymous until submit
   (account-on-submit doctrine). Key EVERYTHING per-viewer on the RING
   SESSION id: on first GET of `/cfp/:slug`, if the session has no
   `:viewer-id`, mint one (uuid) into the session. That id is the draft key
   AND the SSE key.
2. **Session-bound draft stash.** Debounced form-level POST (the marquee
   pattern: `data-star-on:input__debounce.300ms` + ds/bind on every answer
   field) → handler stashes typed values per viewer-id (defonce atom map,
   like `create-drafts`) → GET prefills from the stash. A refresh or network
   blip never eats a typed abstract. Clear the stash on successful submit.
3. **Per-viewer SSE pushes for validation/ghost feedback.** Generalize the
   `[event-id person-id]` push pattern to viewer-key: `handle-sse` currently
   registers the signed-in person — add an ADDITIVE path so an anonymous
   session registers under its `:viewer-id` (e.g. the handler falls back to
   session viewer-id when there is no person). 50 simultaneous speakers must
   never cross streams — prove it with two concurrent probe sessions.
4. **Live field validation, server-pushed.** The 422 machinery exists
   (`handle-cfp-submit` re-renders with errors + values). Add the live lane:
   the debounced draft POST also pushes per-field error/ok fragments to THIS
   viewer (char limits, required-on-blur, URL shape). Never a JS validator.
5. **Never type your bio twice.** Profile section prefills from the person's
   prior submissions and/or Sessionize import (`handle-cfp-import` exists —
   polish it into the flow). On `/portal`, profile edits round-trip.
6. **Portal at the same grade.** Status visibility per the 7-status +
   Notified doctrine (a speaker sees only what they've been TOLD), edit
   window while the call is open, tasks.
7. **Multi-user is the default assumption.** Any per-viewer UI state binds to
   viewer-id or person-id, never a global slot. (The two-step confirm and
   focus patterns in the codebase show the shape.)

## Hard rules
- Datastar 12 NEVERs are binding; no JS beyond browser-owned ops; no CSS/JS
  in .clj files.
- Compile checks via nREPL only. Fast suite in background between batches
  (`make runtests-once`). `bin/e2e_drive.py` after ANY route/handler change.
- SSE proof = fragments observed on a real stream (`bin/form_preview_probe.sh`
  is your template — write `bin/cfp_draft_probe.sh`). A 204 or green tests
  prove nothing about SSE (both have lied in this repo).
- Small batches; verify live (curl + screenshot) THEN commit per item, author
  `Gene Kim <genek@itrevolution.com>`. Never `git add -A` — name your files;
  three streams share this tree.
- Manual drives get written to `logs/2026-08-09-<topic>.md` as they happen.
- Track in beads: claim td8, `bd close` when done.

## Definition of done
Typing an abstract on `/cfp/<slug>`, killing the tab, and reopening restores
every keystroke (proven over HTTP with a cookie jar); two concurrent anonymous
sessions get their own drafts and their own SSE fragments (probe-proven);
submit still creates account + submission and clears the draft; the portal
round-trips profile + answer edits; e2e_drive.py extended and green end to
end; fast suite green; captain's log written.
