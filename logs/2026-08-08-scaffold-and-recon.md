# 2026-08-08 (afternoon) — Scaffold, Sessionize recon, dev environment, build start

**What was asked.** Create the project from mcp-clojure-template; get clj-surgeon
running; recon Gene's logged-in Sessionize organizer (create-event, archived
events, existing-event innards) to mirror event creation → PC → submissions;
good CLAUDE.md/AGENTS.md; killer dev env (hot reload, clj-surgeon, browser);
build the no-degrees-of-freedom screens (create event) now.

**What happened.**
1. `bin/scaffold cfp-scheduler-killer <repo> --merge --port auto` → port 20500;
   tests green. clojure-mcp configured (`make mcp-configure`; loads next
   session). clj-surgeon verified — MUST pass
   `workspace_root: /Users/genekim/src.local/sessionize-sched-killer`.
2. CLAUDE.md rewritten: project doctrine (10 non-negotiables) + research-doc map
   + template's 387-line dev guidelines. AGENTS.md = pointer + beads block.
3. Sessionize recon (docs/sessionize-recon/, 5 screenshots + notes):
   - Create event = SIX fields; Create Demo Event seeds 10 fake submissions;
     Duplicate existing event. Mirror 1:1 + auto-spawn committee/form/URL.
   - Dashboard "Smart Task List" ("may be the best part of Sessionize") — keep
     the concept. CfS Secret Page (always open) is a product feature.
   - Sessions list: add Waitlisted + Nominated to status vocabulary; the
     "speakers have not been informed" banner = Notified second-act, theirs too.
   - Evaluation Plans: ETLS PC plan progress **17%** — Sessionize's own number
     proving the committee never reviewed in it (fulcro was the real surface).
4. Dev env: brew postgresql@15 started, `cfp_dev` created; nREPL :57797;
   ENV=dev server on :20500 (wrap-reload + browser-reload + reloading-ring-
   handler). Fixed template gap: core/-main now requires server ns, calls
   `(server/start-server!)` and parks on `@(promise)` (applied via clj-surgeon
   transactional edit — its first production use here; insert_after actions,
   receipt retained).
5. Basecamp recon (earlier today, docs/research/basecamp-era-capture.md): the
   entire 2026 EAIS CFP = 15 threads; quick-capture ingest filed (bd d9o).

**Decisions.** Domain model v1 (committee = roster + scope filter); build order
event→PC→submissions; statuses now 9-valued (+ Waitlist, Nominated); mirror
Sessionize create screen; EAIS Charlotte seed form = demo data.

**Next.** Event-creation implementation (delegated build), ASCII wireframes for
the PC board + submission page (degrees-of-freedom screens needing Gene's eyes),
then PC invites + board skeleton.

## Evening addendum: the whole build sequence shipped in one day

Slices landed (all verified, committed): event creation → PC roster (Open/
Remove, person pages) → JSONL event-store pivot (zero-setup, sink registry) →
public CFP + Sessionize import → left sidebar + Log page → REVIEW BOARD with
magic-link auth + live SSE. 74 tests / 636 assertions / 0 failures.

The day's defining moment: the builder found event.updated silently wiping
unmentioned dates (update vs update-present) — Charlotte had already lost four
fields — and the fix required NO migration: the log had the truth; re-folding
restored the data. The event-sourcing bet paid out under fire, same-day.

Also today: public gift repo published (github.com/realgenekim/kill-my-saas-
reference — brief, 40 screenshots organized, transcript, swyx clarifications,
transfer/takedown offer). Sharing strategy adversarially reviewed twice (Sol
rounds 1-2), final timeline adopted. Ann interview (two-axis verdict). Basecamp
live capture. German-tank analysis (private forever).
