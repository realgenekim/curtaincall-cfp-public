# Plan: judge readiness — sign-in, rule conversions, telemetry, public widgets

*Handoff plan for coding agents, written 2026-08-10 by the main session.
Every design point is Gene-ratified today — do not re-litigate, implement.
Deadline context: contest submission Wed Aug 12, 10PM PT. The LLM judge
(`sbek`, 96 rubric items) withholds ALL scoring below 60% scenario coverage,
and today the eval agent cannot obtain a session on the deployed site —
Package A is therefore worth more than everything else combined.*

## Read first (non-negotiable context)

1. `CLAUDE.md` — Datastar 12 NEVERs, no JS/CSS in .clj, nREPL-only compile
   checks (`clj-nrepl-eval -p $(cat .nrepl-port)`), `make runtests-once` in
   background after src changes, the database-interface rule (mutations =
   domain verbs via REPL; psql banned), parallel-tree discipline (anchored
   edits, callee-before-caller, per-hunk `git diff` before `git add`).
2. `docs/research/rubric-gap-analysis-2026-08-09.md` — the 96-item scorecard,
   kill list, and §3 traps. Package specs below cite its item IDs.
3. `docs/one-database.md` — dev and Cloud Run share ONE production Postgres.
   `make server-jsonl` is the agent sandbox; never point tests at prod.
4. Beads: `bd show sessionize-sched-killer-ak6` (telemetry spec),
   `-2v2` (funnel consumer), `-eff` (cross-event authz), `-4rr`, `-lc3`.

Hard rules for every package: extend tests, never delete; suspected src
defects in files another lane owns get REPORTED; agents never commit —
the main session reviews diffs and commits; verify each acceptance check
against the RUNNING server (`bin/e2e_drive.py` + curl), not just the suite.

---

## Package A — Judge sign-in on the deployed demo (P0, ~3.5–4h)

**Goal:** the sbek eval agent and a human AIE judge can obtain Organizer /
Reviewer / Speaker sessions on the deployed demo site, while ITRev's
production conferences stay unreachable.

**Tenancy protection (ratified):** personas must be **AIE-only people**.
swyx (chair), Maya Lindholm or Devon Reyes (reviewer), and a demo speaker.
NEVER Ann Perry, Alex Brodrick-Forster, or Gene Kim — they hold seats on
Enterprise AI Summit (ITRev production data). Committee-as-tenancy then
does the isolation: the judge's events list contains exactly one event.

1. **Env gate `DEMO_PERSONAS=on`** — set ONLY on the `swyx-cfp-saas-killer`
   Cloud Run service. `curtaincallcfp` never gets it. The existing
   `/api/demo-login` handler is ENV=dev-gated today; re-gate it on
   `(or dev? demo-personas?)`.
2. **Three buttons on `/login`**, plain form POSTs to `/api/demo-login`.
   Labels must START with the persona word, exactly one each (the kit's
   `autoClick` throws on ambiguity — gap analysis §3.2):
   `Organizer · swyx` · `Reviewer · Maya Lindholm` · `Speaker · <pick an
   AIE-only demo speaker who has ≥2 submissions, one rated>`.
3. **Magic-link echo, allowlisted.** After `POST /api/login` for an email in
   `DEMO_PERSONA_EMAILS` (env, comma-separated), render the sign-in link on
   the page ("Demo mode — SMTP is off; your link: …"). Any other email gets
   the existing neutral response. This is the hole-proof version: without
   the allowlist, typing annp@itrevolution.net would echo a link into
   ITRev's world.
4. **Seed sbek fixture emails** onto the AIE committee only, via REPL
   `committees/add-member!` (appended facts). Read gap analysis §3.3 for
   the variants (`sbek-organizer@example.com`,
   `jordan.organizer@sbek-test.example.com`, speaker/reviewer variants).
   Add all of them to the allowlist env too.
5. **Cookie session store** (the sneaky must, §3.4): Ring's in-memory store
   logs everyone out on instance recycle mid-eval. Switch `wrap-session` to
   the cookie store; 16-byte secret loaded at boot via the gcp-secrets
   pattern (see mcp-clojure-template secrets.clj — runtime-load, never env),
   random fallback when unconfigured (dev). Requires real server restart;
   invalidates existing sessions once (harmless).
6. **Raise the 3-submissions-per-person cap on demo** (§3.5) — it fires
   mid-scenario and reads as a broken submit flow. Env or event setting.
7. **"How to sign in" block** on the landing page (flag-gated) + README
   section: the three personas, what each sees, the demo URL.
8. **eff verification (folded in, now load-bearing):** with organizer
   sessions handed to strangers, the cross-event authz hole (member of
   event A acting on event B by direct URL) pierces the wall protecting
   EAIS. Drive it: as Maya (AIE-only), curl EAIS organizer/API routes —
   every one must 403/302, nothing written. Fix holes; extend
   `authz_event_scope_test`. Close bead `-eff` if green.

**Acceptance:** fresh incognito session → `/login` shows exactly three
persona buttons → each 303s to a working session scoped to AIE only →
`gcloud run services update` (forces new instance) → the cookie still
authenticates → `jordan.organizer@…` magic-link echo roundtrip works →
Maya cannot reach any `enterprise-ai-summit-charlotte-2026` organizer
surface → `bin/e2e_drive.py` green → deployed with flag on demo service
only, curtaincallcfp verified WITHOUT buttons.

---

## Package B — Rule/handoff conversions: #11 + #14 (P1, ~3h)

Three near-misses become full credit. The judge scores SCREENS and hunts
for literal words.

1. **Explicit "Publish agenda" button (AIA-07).** A control on the schedule
   page labeled `Publish` wired to the EXISTING publication gate — the act
   is already real (accepted+informed feeds the public agenda); what's
   missing is a discoverable button named Publish and a confirmation state.
   Appended fact (e.g. `agenda.published`), shown in the change feed.
2. **Content status `Draft / In review / Approved` (CNT-12, rule, w3).** A
   per-session content-status control on the submission/session screen —
   the literal word `Approved` matters (the scenario tells the agent to
   hunt for it). Appended facts; additive to existing status machinery, do
   NOT entangle with the 8-value submission status. Public surfaces may
   simply display it; gating public exposure on it is optional this pass.
3. **CFP-close edit lock (CFP-16, rule, w2).** `portal/editable?` also
   requires the CFP to be open (or the submission's event to allow edits);
   closed → the portal edit page renders read-only with a visible message
   ("The call closed Sep 15 — editing is locked."). One predicate + one
   render branch + a test that closes the CFP and proves the lock.

**Acceptance:** the three literal words render on screens a crawler finds
(`Publish`, `Approved`, the lock message); facts appear in the event log;
suite green; e2e drive extended to click Publish.

---

## Package C — Production telemetry (ak6) (P1, ~2–3h)

Full spec lives on the bead: `bd show sessionize-sched-killer-ak6`.
Summary: `telemetry_events` pg table (seq/at/line, same append-only
triggers as store_events, INSERT+SELECT to app role — **DDL via REPL as
table owner genek FIRST**, per docs/postgres-store.md), `telemetry.clj`
with an in-memory queue + daemon flusher doing one multi-row INSERT per
~2s (never fails a request; drop-and-count on overflow), `wrap-telemetry`
middleware capturing route/status/duration/person/session-hash/event-slug/
allowlisted params (sort, q, status, track, at-index) — NEVER form bodies —
plus one structured `log/info :req` line for Cloud Logging. Port
social-media-writer `analysis.clj` read/funnel fns as `dev/analysis.clj`.

**Acceptance:** N curls → `SELECT count(*)` grows by N (meter); a kill of
the flusher mid-queue loses at most one batch; deployed to both services;
funnel query from `-2v2` returns sane numbers on real traffic.

---

## Package D — Public widgets (#1) (P1, ~8–12h, 8.8 rubric points)

**Prelude — the decision wave (operator, REPL, ~1h):** public surfaces
show only accepted+informed sessions; the AIE demo is 100% Pending, so
every widget would render EMPTY (worse than absent). Before building:
run an operator decision wave on the AIE event via domain verbs on the
staged clock (`store/*clock*`, early-August dates): ~60 Accepted → informed
→ scheduled into rooms/blocks (rooms: create 3–4), ~25 Waitlisted,
~40 Declined (informed), remainder Pending. Backup before/after. This also
exercises the new 8-status doctrine on judge-visible data.

Then four logged-out pages over data we already fold (EMB item anatomy in
the gap analysis §1.6 — follow the card specs exactly):

1. **Public sessions list** (EMB-01/02/03): header, `Sessions 1 – N of N`,
   search box (GET param, server-side filter, no JS) matching titles AND
   speaker names, Track facet chips, cards: bold title, 2–3 line truncated
   abstract + Show more (`<details>` — no JS), date/time, room, speakers
   with job title + company, `Format:`/`Track:` chips.
2. **Speakers directory + detail** (EMB-04/05): searchable name grid;
   detail = Back link, photo, name, title, company, truncated bio +
   Show more, `Sessions (n)` with title/date-time/room.
3. **Speaker gallery** (EMB-12): headshot card grid. Our pool-face means
   nobody lacks a photo — that satisfies "graceful fallback" by
   construction; note it in the page's test.
4. **Agenda upgrades** (#8, folded in: EMB-08 + completing EMB-06/09):
   clickable session detail (start–end, room, description + Show more,
   Format), track/format chips on schedule blocks, day tabs already exist.

All pages: L0 public chrome (no rail, docs/nav-elements.md), house tokens,
`.ics`/JSON siblings where cheap, EMB-14 logged-out check in tests.

**Acceptance:** every page loads in incognito on the deployed demo; search
and Track facet filter server-side; EMB-01 card anatomy complete; data
consistent across list/detail/agenda (EMB-16); suite + e2e green.

---

## Sequencing and split

A alone first (it unblocks scoring). B, C, D are independent of each other;
if parallel agents: one per package, disjoint file sets — A: auth/login/
server session wiring; B: portal/schedule/views (board region); C:
telemetry.clj + middleware + DDL; D: public views + exports. D's prelude
(decision wave) is main-session operator work, not an agent's.
