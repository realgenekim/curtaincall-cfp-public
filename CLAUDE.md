# cfp-scheduler-killer — CLAUDE.md

**What this is:** ITRev's entry in swyx's Kill My SaaS contest (deadline **Wed
Aug 12 2026, 10PM PT**; $10k; judged by the AIE team on "would we actually
use/buy it") — and, win or lose, the CFP + speaker-management + scheduling tool
ITRev has wanted for a decade (replaces Sessionize + Sched + the scraper glue).
Clojure + http-kit + Datastar SSE + Postgres, scaffolded from mcp-clojure-template
(port **20500**).

## THE DEV ENVIRONMENT (Gene ratified 2026-08-11 — read before starting ANY server)

**Dev runs on the PRODUCTION database. There is no sandbox lane.**
For the shared local app on the standard port, run exactly:
`make server-dev PORT=20500`. Do not invent a longer target name.
`PORT=20501 make server-dev` (in the worktree) is THE dev server: ENV=dev +
STORE_BACKEND=postgres + `-A:dev` hot reload. Dev and prod render the same
data because they read the same store — that is the fast feedback loop, and
it was debated and settled ("because we have backups, let's make dev and
prod the same"). Consequences you must internalize, not re-litigate:

- `make server-jsonl` is NOT the dev environment. An empty sandbox made dev
  lie about what prod shows and burned two hours on 2026-08-11. Use it only
  when someone explicitly asks for an isolated throwaway store.
- NEVER "import" or copy data into a sandbox to simulate prod. If dev needs
  prod data, dev connects to prod — that's `make server-dev`.
- Test drives from dev write REAL facts to the production store, through the
  same domain verbs prod uses. That is accepted (append-only store, archive
  shelf, backups). Be deliberate: throwaway sign-ins and demo events are
  fine; don't spray hundreds of junk drives.
- `make nrepl` is also STORE_BACKEND=postgres. Verify with `(store/postgres?)`
  before trusting any REPL session either way.
- The browser auto-reloads on code changes (ENV=dev watcher) — after an edit,
  the page repaints itself; no restart, no manual refresh needed.

## Makefile is the ONLY mechanism (Gene's standing order, 2026-08-11)

**The Makefile is the single mechanism for starting the dev server, running
tests, building, and deploying — never hand-type `clojure -M`, `gcloud run
deploy`, or jib invocations.** All flags, project IDs, and image paths are baked
into targets so every run is reproducible. Key targets: dev server / tests (see
Development section), `make build`,
`make deploy-staging` (tagged no-traffic staging revision),
`make staging-url` / `make open-staging`, and the guarded
`make promote-staging-to-production EXPECTED_SHA=<sha> PROMOTE=YES` production gate,
`make traffic` (who is serving production). If a needed operation has no target,
ADD the target — don't bypass make.

Operational Clojure entrypoints live under `src/cli/`, expose a public
`clj -X` function that accepts an argument map, and are called only by a
Makefile target. Do not add `deps.edn` aliases merely to shorten an operational
command, and do not introduce new `-M -m` entrypoints. Example:
`src/cli/judge_sandbox.clj` → `clj -X cli.judge-sandbox/generate`, wrapped by
`make regenerate-judge-sandbox`. The Cloud Run uberjar/Jib packaging lane is
the explicit exception: retain the build tool's required invocation instead of
forcing packaging through a `src/cli/` wrapper.

## After ANY dev-server (re)start: prove the hot-reload loop (2026-08-11)

`PORT=20501 make server-dev` is the ONLY way to run the dev server (ENV=dev =
hot code reload + browser auto-reload + dev door). After every (re)start,
PROVE the loop in one command — token must change:
`A=$(curl -s localhost:20501/dev/reload-check); touch resources/public/css/app.css; sleep 1; B=$(curl -s localhost:20501/dev/reload-check); [ "$A" != "$B" ] && echo alive`
Then tell Gene to refresh his tab ONCE — a tab opened before the restart has a
dead polling loop and looks exactly like "hot reload is broken."

## Read these before designing anything

- `docs/design/domain-model.md` — entities; **committee = roster + scope filter,
  never a permission fortress**; build order: event creation → PC → submissions
- `docs/design/form-builder.md` — form = vector of field defs (EDN/JSONB), malli
  validation, snapshot-per-submission, field IDs are forever
- `docs/research/ethnographic-study.md` — the distilled spec + swyx Q&A amendments
- `docs/research/brief-screen-analysis.md` — all 40 incumbent screenshots with
  KEEP/SIMPLIFY/SKIP verdicts + steal/avoid lists
- `to-swyx/cfp-flow-comparison.md` — 15 years of CFP-tool archaeology; the
  review doctrine (two sorts = two work queues; coverage without bureaucracy)
  (moved from docs/research/ 2026-08-09 for sharing with swyx)
- `docs/research/blocking-sheet-scheduler.md` — draft-first scheduling design
- `docs/mockups/index.html` — 9 screen mockups (design review artifact)

## Non-negotiable design doctrine (field-evidence-backed)

1. **Review is a conversation among trusted peers over a shared table.** Every
   score + every comment visible inline (per-event visibility setting exists,
   default :open). No evaluation plans, no rounds, no assignment bureaucracy.
2. **The board's two default sorts ARE the work queues**: # ratings ascending =
   coverage worklist (2-review rule + progress bar); mean stars descending =
   decision queue.
3. **8-valued status + separate Notified flag**: Accepted / Waitlisted /
   Accept Queue / Pending / Decline Queue / Declined / Withdrawn / Draft.
   Waitlisted ratified 2026-08-10 (Gene): it is an EXTERNAL, communicated
   promise ("you're in if someone drops"), distinct from Accept Queue (the
   committee's internal lean) — the field grew it back unprompted, and it
   composes with the Notified flag. Notification is a deliberate, tracked
   second act.
4. **Zero-to-open-CFP in ten minutes** (event create → auto-committee → seed
   form → live public URL). Acceptance test is a timer.
5. **One page, no wizards, no account walls.** Account created on submit.
   Speed is a feature — the incumbent was called slow three times in nine minutes.
6. **Partial states are first-class in the scheduler** (TBD blocks, unassigned
   rooms, live slot math, lock + change feed). See blocking-sheet doc.
7. **Files always attach to the submission/speaker.** Never a detached store.
8. **.ics with stable UIDs** — invites amend, never duplicate; room assigned late.
9. **Emit ai.engineer-compatible exports** (sessions.json/speakers.json/
   calendar.ics/llms.txt) + API shaped like sessionboard.mintlify.app + webhooks.
10. **Seed realistic demo data** (EAIS Charlotte form + plausible submissions).
    Judges must never see an empty screen.
11. **Every EXAMPLE placeholder gets `:data-ghost-fill ""`** (Gene, 2026-08-09
    — forgotten on every new page until this rule). Tab accepts the example
    (ghost-fill.js, inputs AND textareas). Two placeholder kinds exist:
    EXAMPLES ("Ann Perry", a plausible talk title) get ghost-fill; FORMAT
    HINTS ("https://…") never do — tab-filling a format hint submits garbage.
    When you write a `:placeholder`, decide which kind it is, in that moment.

## The database interface rule (Gene, 2026-08-10 — one shared production DB)

**No direct Postgres writes, ever.** Not from handlers, not from the REPL, not
from psql (banned outright), not from scripts. Every mutation is a named domain
verb (`events/archive-event!`, `forms/retire-field!`, …) that APPENDS a fact
through `store/append!`; the DB triggers + SELECT/INSERT-only app role enforce
it. Reads go through the store/domain getters, never ad-hoc SQL in callers.
Deleting is not a thing: record a fact (`event.archived`) and let the fold +
views change what leads. **Operator record updates go through the REPL calling
the domain verbs** (Gene, 2026-08-10 — e.g. the bulk archive: REPL →
`events/archive-event!`, never a script poking SQL); check
`(store/postgres?)` FIRST — `make nrepl` now boots STORE_BACKEND=postgres
(2026-08-11), but verify rather than assume: a REPL on the wrong backend
happily mutates a store you aren't looking at. Then `store/load!` and verify
at an independent meter. Schema/DDL:
REPL as the table owner only (docs/postgres-store.md). Strategy + mitigation
stack: docs/one-database.md.

## Deploying (CI is the lane — 2026-08-11)

**`git push origin staging` = deploy to staging only. It NEVER changes production
traffic.** GitHub Actions
(`build-and-deploy.yml`) runs kaocha, builds the image FROM THE COMMIT
(never the working tree), creates a 0%-traffic tagged revision on BOTH
services, verifies the visible SHA and judge-facing paths, and STOPS. Watch:
`gh run list --workflow build-and-deploy.yml`. Manual trigger:
`gh workflow run build-and-deploy.yml --ref staging` (also stages only).
Skip deploy for docs-only pushes with `[skip ci]` in the commit message.
After Gene approves the exact staged SHA, production moves only with
`make promote-staging-to-production EXPECTED_SHA=<sha> PROMOTE=YES`. That command
verifies both staging URLs again, promotes their exact revisions, records both
rollback points, and rolls back a partial failure. Manual rollback:
`make rollback-production-promotion ROLLBACK=YES` (defaults to the latest receipt).
The promotion command never builds from the working tree; it only moves traffic
to the already-staged artifact. The old `make cloudrundeploy` and
`make deploy-all` names are retired fail-closed targets; they cannot bypass the
staging gate.

## SKIFF OPERATING RULES — post-submit staging-line stage (Gene ratified 2026-08-16 ~23:20 PT)

The goal of this stage is the FASTEST POSSIBLE FEEDBACK LOOP for Gene's
tweezer mode. These rules supersede the fleet-era dispatch ceremony until
Gene declares the stage over.

1. **One integration line: `staging`.** cfp3, cfp4, and cfp5 all make changes
   and all push DIRECTLY to origin/staging in small, intention-revealing
   commits. No permission round-trips, no candidate branches for product
   tweezers. Gene's live acceptance in the browser IS the review gate for
   product deltas.
2. **Notify the Mayor; the Mayor fixes.** After pushing, send the Mayor a
   one-message receipt (what shipped, SHA, what's dirty/known-red). The Mayor
   is the trailing green-keeper: contract/architecture/intent test drift is
   the MAYOR'S job to fix forward, in BACKGROUND agents, never by blocking a
   pushing seat. "CI is trailing; we are not waiting" is the doctrine, not an
   apology.
3. **Everything the Mayor does runs in the background.** Fix-forward sweeps,
   CI watches, merges, evals — background agents and watchers; the Mayor's
   foreground stays free for Gene and for seat coordination. A seat is never
   asked to stop tweezing so a test can pass.
3a. **How to see another seat's screen — ONE way, one command:**
   `~/src.local/code-directory-cluster/bin/agent-bridge read --session <id> --lines 40`
   (resolve the id by NAME with `whichsesh <name>`, never guess). That is the
   entire ceremony. NEVER use node/computer-use/sky, accessibility APIs, or
   screenshots to look at a terminal pane — a one-minute desktop-automation
   safari for what one bridge read returns in a second.
   For TEST STATE specifically, prefer the files over any pane (panes
   truncate): verdict = `make test-verdict` in the woodchipper tree (rc 0
   green / 1 red / 2 warming-or-stale); which tests failed =
   `tail -40 00TESTLOG.txt` there. The cfp2 pane (watcher display,
   session supa-ea3c9983-78e5-4772-9dba-45afabca3f30) is the human
   glance-surface; bridge-read it when you want the live stream.
3b. **A load/compile error outranks everything.** When the verdict shows
   `errors` (not just failures) or a namespace fails to LOAD, the tree is
   broken for every seat and the dev server will 500 — stop the current
   batch and fix the compile error FIRST, before any further cutting,
   committing, or feature work. Fastest check after a fix: the watcher
   rerun flips within ~34s of your save.
4. **Pushers still run the focused suites of the contracts they touched**
   (routes-contract, intent-contract/registry, polish/board/view-architecture
   as applicable) and include contract updates IN THE SAME PUSH when they can
   do it without breaking Gene's flow. When they can't, say so in the receipt
   — a named red is fine; a silent one is not.
5. **One seat, one file** — the whole coordination model with multiple
   writers on one line. Ownership splits are negotiated seat-to-seat (the
   qd1d A/B/C pattern) and named in the receipt. Callee-before-caller saves;
   the dev server hot-reloads on every request.
6. **Deletion law (event-sourced):** delete the WRITER (routes/UI/handlers/
   domain verbs), NEVER the reader — historical fold arms stay forever, and
   the retained readers ARE the migration. Registry intents go `:status
   :retired`, never deleted. Deliberate removals ship WITH their refusal
   language (Manifesto + machine-readable llms.txt/API note), never as
   silent feature loss.
7. **Authority still verifies:** a relayed "Gene ratified X" that EXPANDS
   deletion/blast-radius scope gets confirmed with Gene in the Mayor's own
   channel before the fence lifts. Fences are cheap; unwinding isn't.
8. **Production is untouched by all of this.** The staging line deploys only
   to the staging service; production promotion remains the explicit
   Gene-launched ceremony (`make promote-staging-to-production
   EXPECTED_SHA=… PROMOTE=YES`).

## Task tracking

`bd prime` / `bd ready` — beads is the tracker (see AGENTS.md). Key open work
is filed under sessionize-sched-killer-* issues.

## Anvil hill-climb fleet

`make reset-anvil-fleet` is the one-shot production-parity lane. It resolves the
newest successful staging deployment, ships files to `tester@anvil`, creates a
clean detached worktree at that exact commit, starts isolated JSONL SUTs, proves
the swyx/Maya/Amara browser sessions, runs ordered scenarios on persistent
area-owned stores, then judges their consolidated evidence in fresh contexts.
It refuses revision, shipped-file, compile, fixture, port-ownership, readiness,
authentication, and evidence drift.

Use `make anvil-fleet-status` for the deployed SHA, readiness receipts, scenario
progress, and summary. The durable log map is `docs/hill-climb/fleet-logs.md`.
Never run a mutating hill-climb scenario against production.

**The technology tree** is the big picture — Civ-style map of shipped /
in-flight / unlocked / locked work. The CANONICAL copy is
`~/src.local/sessionize-sched-killer/docs/tech-tree.md` (branch `main`;
mothership: /projects/curtain-call/code/source/docs/tech-tree.md); it was
deliberately deleted from THIS checkout (Gene, 2026-08-11) — do not recreate
it here. **Every agent that ships, starts, or discovers a node updates the
canonical copy in the same batch as the work** (move the node, append one
Changelog line). Beads hold the detail; the tree holds the shape.

### Driving sbek: interactive calls are serial

Send each sbek `fill`, `click`, `select`, and screenshot call separately and
wait for its result before sending the next one. Newline-batching several
JSON-RPC tool calls makes them execute concurrently; Playwright actions can
then race on the same form and report a successful fill whose value is absent
at submit. The CFP-S1 Event name failure on 2026-08-13 was this exact pattern:
six fills plus a screenshot were written in one batch, while the sequential
retry succeeded. Read-only observations may be parallel only when they cannot
overlap a navigation or DOM mutation.

## Manual testing: always write the results down

Any hands-on drive of the running app (browser click-through, curl walk of a
flow, operator rehearsal) gets **written to a captain's log** at
`logs/YYYY-MM-DD-<topic>.md` **as it happens** — not summarized in chat and lost.
Record: what flow was driven, what passed, every defect found (with the exact
symptom), what was fixed, and what is still open. A finding that exists only in a
chat transcript is a finding we will rediscover.

`bin/e2e_drive.py` is the repeatable version of that drive: it exercises the LIVE
server over HTTP as an organizer + a speaker (sign in → create event → add PC →
public CFP → submit → rate/comment → decide → exports) and prints pass/fail per
check. Run it after any change that touches routes, auth, or the store —
in-process tests can't catch wiring defects. Extend it whenever a manual drive
finds something it missed; that is how a one-time finding becomes a permanent one.

## Producing a patch for the merger (foreman/mayor lanes, 2026-08-13)

Field-evidence from an overnight hill-climb run. Each rule below cost something
real to learn.

- **Invariant 19: publish merger jobs only through `bin/dev-lane publish`.**
  The publisher requires a bead, producer model, and producer reasoning effort,
  computes `patch_sha256` from the final patch bytes, and publishes the patch
  before the job. Never hand-assemble or copy a `.job` into the inbox. Missing
  producer identity must fail at publish time instead of becoming a delayed
  `spark-core` hold.

- **Cut patches with `git add -A && git diff --cached`, never bare `git diff`.**
  Bare `git diff` silently omits untracked files. A patch cut that way shipped a
  new feature *without its new test file*, and the merger's verify step would
  then have run a focused suite for a namespace that did not exist. Caught
  pre-release only because the file count looked wrong.
- **A patch is a diff against the base it will be applied to — never a snapshot
  of some other tree.** Two jobs were assembled from a file snapshot taken hours
  earlier. Both would have silently reverted work that had shipped in the
  meantime: one would have removed the root `/llms.txt` agent index from
  production, the other would have undone `a869268` ("/program is canon"). If you
  have files but no diff, re-implement against current `origin/staging`; do not
  reconstruct a patch by copying files forward. Hash-compare before you trust any
  carried-over file.
- **Verify a filed defect against running code before dispatching anyone.** In
  one night, five filed defects did not survive verification: `/api/v1` already
  worked; a defect blamed the dev-strip on a page that does not render it; an
  export "hard-coded timezone" did not exist in the export path; a P0 claimed
  magic-link sign-in blocked an agent when `POST /api/demo-login` already returns
  a session; and one blamed a lifecycle gate that was correct doctrine already
  pinned by a test. Verification costs minutes and has saved hours every time.
- **A retired surface is a decision, not a regression.** Before "restoring"
  anything, check whether a ratified decision removed it. `/agenda/:slug/{sessions,speakers}`
  redirect and the gallery route was deleted outright — `a869268`, ratified
  2026-08-11, pinned by `public_widgets_test.clj`. An evaluation rubric scoring
  those as `not_found` is the rubric disagreeing with the product, not a bug.
- **Do not relax the accepted-AND-informed publication gate.**
  `exports/published?` requires `Accepted` *and* `:notified-at` deliberately —
  notification is a separate, tracked act, and loosening it publishes a speaker
  before telling them. It is pinned by `schedule_test.clj`. If a filing asks you
  to relax it, the bug is almost certainly the surrounding copy instead.
- **Never `pkill -f <pattern>` here.** The pattern matches the invoking shell's
  own command line, so it kills your shell and leaves the target running — which
  reads as "the kill failed" when in fact nothing was killed. Resolve the exact
  PID (`ss -lptn 'sport = :PORT'`) and kill that.
- **When a score and a ratified decision disagree, the decision wins.** Escalate;
  do not let a metric talk you into reverting something someone chose on purpose.

## Tool notes (this repo)

- **Investigate in .clj source, never in a browser** (Gene, 2026-08-17): this
  is a Datastar app — ALL state and rendering live in the backend, so every
  pixel on every page is a Hiccup form under `src/cfp_scheduler_killer/views/`.
  Find copy/UI with grep or clj-surgeon over source. The browser is Gene's
  acceptance surface, not a discovery tool; driving one to "see what renders"
  is a slow detour to information the source states directly.
- **Tweezer-sized edits use NATIVE Edit/apply-patch** (Gene, 2026-08-17, live
  steer to cfp3: "small changes, use native edit / apply patch, whatever is
  easiest"). clj-surgeon is for READS and large structural operations; its
  apply-verifier can stall ~120s (npx) and has left writes without receipts —
  never sit in it for a few-line cut. Read with surgeon, write with Edit.
- **clj-surgeon MCP**: pass `workspace_root: /Users/genekim/src.local/sessionize-sched-killer`
  on every call (server defaults elsewhere). Use it before Read/Edit on any .clj.
  Shared-service lifecycle (installed 2026-08-08): shared servers at
  127.0.0.1:7888/mcp (clj-surgeon) + :7890/mcp (cclsp) — never cached ports
  7896/7897. Fresh session: `~/bin/clj-surgeon up "$PWD"` once from repo root,
  then prove readiness with ONE real inspect_clojure (not /healthz). On
  invalid-mcp-session / server-not-initialized / stale-port: `~/bin/clj-surgeon
  recover "$PWD"` once; :recovered → retry once; :fallback-safe → run the
  supplied report-command once, then the CLI fallback (no restart loops).
  Report (don't hide) any semantic-provider-timeout — anchored route in test.
- **clj-surgeon apply transaction shapes** (learned 2026-08-17):
  `insert_before` and `insert_after` take an array of complete form strings,
  even for one form. A namespace replacement must also remain one complete
  form: replace the whole `(:require ...)` clause when adding a require; do not
  replace one require vector with two sibling vectors. Namespace-owner changes
  use `expect.matches` but not `expect.each_form` (that assertion requires an
  explicit `forms` owner).
- **Keep surgeon intents disjoint.** Inserting helpers relative to a form while
  replacing that same form is rejected as `overlapping-intents`; anchor the
  insertion to the preceding or following top-level form instead. Fast
  verification treats a newly unused private var as blocking, so delete
  retired helper forms in the same failure-atomic transaction. A refused or
  failed transaction reports `source_unchanged`; trust that receipt instead of
  guessing whether a partial write landed.
- **clojure-mcp**: configured via `make mcp-configure` (done); `make nrepl` first,
  port in `.nrepl-port`.
- **NEVER `clojure -M -e` for compile checks** (Gene, 2026-08-09) — it cold-boots
  a JVM (~40s) every time. Use the running nREPL instead (instant):
  `clj-nrepl-eval -p $(cat .nrepl-port) "(require 'the.ns :reload) :ok"`.
  Fastest feedback loop possible is the rule; the JVM boots once, in `make nrepl`.
- **NEVER launch a dev server with raw `clojure -M -m ...`** (Gene, 2026-08-10,
  the "why are we restarting the server?" night) — ONLY `make server-jsonl
  PORT=...` (sandbox) or `make server-dev` (shared-DB). The `-A:dev` alias in
  those targets carries wrap-reload; a raw launch runs a server that LOOKS like
  dev (ENV=dev, dev login) but silently never hot-reloads — every edit then
  "mysteriously" doesn't appear, and you burn an hour restarting per edit and
  chasing reload ghosts that are actually your own launcher.
- **A git worktree does NOT inherit gitignored files** (same night): no
  `secrets/`, no `data/`, no `.nrepl-port`. Symptoms: Google button absent from
  the login page (auth-google/enabled? finds no client secret), empty store.
  First act in any new worktree: `ln -s <main-checkout>/secrets secrets` (and
  decide deliberately about data/). Registered OAuth redirect URIs are
  localhost:20500 + prod only — Google click-through on any OTHER dev port
  needs that port's callback added in the Google console first.
- `make runtests-once` after any src change (house rule) — **always
  `run_in_background`** (Gene, 2026-08-09): the suite takes minutes and the
  main loop must keep editing/steering while it runs. Never block a turn
  waiting on tests; read the result when the notification lands. Note the
  suite is fail-fast with a random seed, so "N tests" varies run to run and
  one failure halts the run wherever it happens to be.
- For the warm full-suite loop, start `make runtests-log` once and read only
  `make test-verdict`—never grep or tail `00TESTLOG.txt`. The verdict command
  exits 0 only for a current green run, 1 for a current red run, and 2 when the
  watcher is absent, warming, or stale after a source edit. Kaocha is capped at
  512 MB by `bin/kaocha` (measured 2026-08-11: 61s cold one-shot vs 34s warm
  watch rerun, both 374 tests / 3,818 assertions).
- **Parallel-stream tree discipline (2026-08-09, learned twice in one day):**
  multiple agents share this working tree. (1) Small ANCHORED edits only —
  never write a full-file copy of a shared file; a stale copy silently reverts
  another lane's work. (2) Save the CALLEE before the CALLER — the dev server
  hot-compiles on every request, so a saved caller referencing an unsaved var
  is a live 500 in Gene's browser. REMEDY when already stuck (a 500 names an unresolved var though both files look right): the server's ns-tracker marked the callee reloaded during a FAILED pass and won't retry it — `touch` the CALLEE file; the next request heals. (2026-08-10, ds/sse-mount-url.) (3) Before `git add` of a shared file, run
  `git diff` on it and confirm every hunk is yours; naming files is NOT enough
  when another lane has uncommitted work in the same file (that is how foreign
  hunks ended up inside 7508ff9). Never `git add -A`.
- Datastar house rules live in the global CLAUDE.md (the 12 NEVERs) — they are
  binding here; joe-payne-app (`~/src.local/joe-payne-app`) is the reference
  implementation for sse.clj / page-shell / postJSON conventions.
- **Datastar hard-won lessons (2026-08-09, the create-page sprint — read before
  ANY live-update work):**
  1. The ONLY SSE mount attribute this vendored build recognizes is
     `data-star-init`. `data-star-on-load` is silently dead (it shipped on every
     page for a day). NEVER write the attribute by hand — use `ds/sse-mount`.
  2. `resources/public/js/datastar-kit.js` must be loaded by every page shell —
     `ds/post-action*` compiles to `postJSON(...)` and toasts need
     `showNotification(...)`; unloaded, every action fails silently.
  3. A form field is INVISIBLE to the live-preview/draft loop unless it carries
     `(ds/bind :signal)` — an unbound input posts nothing (the lost-website bug).
     Signal names are single words (`evname`, `evweb`) — Datastar camelCases hyphens.
  4. The live-preview pattern: form-level `data-star-on:input__debounce.300ms
     @post(...)` → handler reads `datastar-signals`, pushes per-person via
     `sse/push-to-person!` on the `sse/new-event-channel` pseudo-event, stashes
     a per-person draft (`create-drafts`) so refresh repaints typing → 204.
  5. NEVER trust a 204 or green tests for SSE — both lied. Proof is a fragment
     on a real stream: run `bin/sse_probe.sh` (one command), and
     `GET /dev/sse-state` shows who is registered + what would be pushed.
  6. Push handlers must refuse to succeed quietly: check
     `sse/person-connection-count` and log `:*-push-no-subscriber` loudly.
- **No JS or CSS in .clj files** (Gene, 2026-08-09): stylesheets and scripts live
  in `resources/public/` (css/, js/, vendor/) and are referenced from the page
  shell. Hiccup carries structure and Datastar attributes only — no `[:style]`
  blocks, no inline `style=` beyond trivial one-offs, no `[:script]` bodies.
  CSS architecture: `@layer tokens, base, components, page` + `:root` design
  tokens + native CSS nesting (2-3 levels max); rules reference `var(--token)`
  only, so a restyle is a token-block swap.
- **Privacy fence**: docs/slack/, docs/recordings/, docs/discord/, data/, and
  several docs/research/* files contain private material. The PUBLIC competition
  repo will be a fresh allowlist repo — never publish this one.

---

# Clojure Development Guidelines

## Core Philosophy: Fast, Frequent Feedback

The goal of Clojure development is to maintain a **tight feedback loop** between writing code and seeing results. This means:

1. **REPL-Driven Development** - Always have a REPL running and use it constantly
2. **Test-Driven Development** - Write tests first, run them frequently
3. **Incremental Changes** - Make small changes and verify immediately
4. **Live Reload** - Use tools that automatically reload code on save

## Essential Workflow

### 1. Start with nREPL

```bash
make nrepl
```

This starts an nREPL server and writes the port to `.nrepl-port`. Your editor (Emacs, VSCode with Calva, IntelliJ with Cursive) can connect to this port for interactive development.

### 2. Run Tests Automatically

```bash
make runtests
```

This runs tests in watch mode - they re-run automatically whenever you save a file. **Leave this running in a terminal while you code.**

For a single test run:

```bash
make runtests-once
```

### 3. Use the REPL Constantly

The REPL is your primary feedback mechanism. Every function you write should be:
1. Tested in the REPL first
2. Wrapped in a unit test
3. Run through the watcher to ensure it passes

**Anti-pattern**: Writing lots of code without testing it in the REPL
**Good pattern**: Write a function → Test it in REPL → Write a test → Move on

## Project Structure

```
project/
├── src/              # Source code
│   └── your_ns/      # Your namespaces
├── test/             # Test code (mirrors src/)
│   └── your_ns/      # Test namespaces (suffix: _test.clj)
├── dev/              # Development-only code
│   └── user.clj      # REPL utilities and helpers
├── resources/        # Non-code resources (EDN files, etc.)
├── deps.edn          # Dependencies and aliases
├── tests.edn         # Kaocha test configuration
├── Makefile          # Common commands
└── .nrepl-port       # Auto-generated nREPL port
```

## Closed Record Policy
**Use `closed-record` for all structured data maps** that cross function boundaries — API responses, normalized data, config maps. Closed records throw on access to undefined keys, catching typos at the call site instead of silently returning nil.

```clojure
(require '[closed-record.core :as cr])

;; GOOD — typo throws immediately
(def stats (cr/closed-record {:likes 10 :views 100}))
(:lkes stats) ;; => THROWS: INVALID KEY ACCESS: :lkes (valid keys: [:likes :views])

;; BAD — plain map, typo silently returns nil
(def stats {:likes 10 :views 100})
(:lkes stats) ;; => nil (bug hides until production)
```

**When to use:** API response normalization, DB row projections, config/secrets maps.
**When NOT to use:** Internal pipeline maps, maps with dynamic `assoc`, raw JSONB from Postgres.

## Guardrails Policy
**Use `>defn` with positional args** for all public functions that cross boundaries (state mutators, DB operations, handlers). Guardrails validates types at runtime — catch bugs at the call site, not deep in SQL/JDBC.

```clojure
;; GOOD — typed positional args, Guardrails catches bad input immediately
(>defn insert-record!
       [name created-at metadata]
       [string? instant? map? => any?]
       ...)

;; BAD — maps bypass Guardrails, type errors surface as JDBC exceptions
(defn insert-record! [{:keys [name created-at metadata]}]
  ...)
```

**When to use `>defn`:**
- State accessor/mutator functions (catch corruption early)
- Database insert/update functions (validate before hitting SQL)
- Public API functions (validate external input)

**When NOT to use:**
- Internal helpers, pure data functions, query-only functions

**Custom predicates** for Java types:
```clojure
(defn instant? [x] (instance? java.time.Instant x))
```

## Testing Philosophy

### Write Tests First (TDD)

1. Write a failing test
2. Write minimal code to make it pass
3. Refactor
4. Repeat

### Test Organization

- Test files mirror source files: `src/foo/bar.clj` → `test/foo/bar_test.clj`
- Use `deftest` for test functions
- Use descriptive test names that explain what you're testing
- Group related tests with `testing` blocks

Example:

```clojure
(ns your-ns.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [your-ns.core :as core]))

(deftest user-validation-test
  (testing "valid email addresses"
    (is (core/valid-email? "user@example.com"))
    (is (core/valid-email? "name+tag@domain.co.uk")))

  (testing "invalid email addresses"
    (is (not (core/valid-email? "not-an-email")))
    (is (not (core/valid-email? "@example.com")))))
```

## REPL Workflow

### The `user.clj` Pattern

Create `dev/user.clj` for REPL utilities:

```clojure
(ns user
  (:require [clojure.tools.namespace.repl :refer [refresh]]
            ;; Add your project namespaces here
            [your-ns.core :as core]))

(defn reset
  "Reload all changed namespaces"
  []
  (refresh))

;; Add helper functions for common REPL tasks
(comment
  ;; Example: Quick test data
  (def sample-user {:name "Alice" :email "alice@example.com"})

  ;; Example: Reset REPL state
  (reset)
  )
```

### The `comment` Block Pattern

Use `(comment ...)` blocks in your source files for REPL exploration:

```clojure
(ns your-ns.core)

(defn process-data [data]
  (map inc data))

(comment
  ;; REPL experiments - these don't run in production
  (process-data [1 2 3])
  ;=> (2 3 4)

  ;; Try edge cases
  (process-data [])
  ;=> ()

  ;; This code is never executed automatically
  ;; Evaluate it with your editor's "eval form" command
  )
```

## Tweezer change sequence

Every change uses this order: **make the smallest surgical edit, immediately
format every touched source file, verify the focused behavior, then commit.**
Do not carry an unformatted change into the next edit. This keeps each commit
as the smooth, stable base for the next tweezer-sized change.

## Fast Feedback Checklist

Before committing code, ensure:

- [ ] All tests pass (`make runtests-once`)
- [ ] You tested new functions in the REPL
- [ ] You added tests for new functionality
- [ ] Tests run quickly (< 1 second for most tests)
- [ ] Code reloads without errors in the REPL

## Common Commands

```bash
make nrepl           # Start nREPL server
make runtests        # Run tests in watch mode
make runtests-once   # Run tests once
make repl            # Start a basic REPL
make clean           # Clean compiled artifacts
make mcp-configure   # Configure Clojure MCP for Claude Code
make mcp-run         # Run Clojure MCP server
```

## MCP (Model Context Protocol) Integration

This project supports Clojure MCP for enhanced Claude Code integration:

1. Start nREPL: `make nrepl`
2. Configure MCP: `make mcp-configure` (one-time setup)
3. Claude Code can now use Clojure-specific tools (eval, def lookup, etc.)

## Logging

This project uses [taoensso.timbre](https://github.com/taoensso/timbre) v6.3.1 with the [genek/logging](https://github.com/realgenekim/logging) config wrapper.

### Setup

- Always require as: `[taoensso.timbre :as log]`
- Startup config lives in `core.clj` — configures two appenders:
  - **Console**: formatted output via `glog/configure-logging!`
  - **File**: `00SERVER-LOGS.txt` via Timbre's spit-appender

### Structured Keyword Arguments

Always use structured keyword args — never format strings:

```clojure
;; GOOD — structured keyword args
(log/info :server-started :port 9999)
(log/error :loading-error :msg (.getMessage e) :error e)

;; BAD — positional string args
(log/info "Server started on port 9999")
(log/error (str "Error loading: " (.getMessage e)))
```

### Log Levels

- `log/error` — catch blocks and failures (always include `:error e` for stack traces)
- `log/warn` — recoverable issues, degraded operation
- `log/info` — operational events (startup, shutdown, requests)
- `log/debug` — diagnostic output, development-time tracing

### Rules

- **Never use `println` in production code** — all output goes through Timbre
- Every src file that produces output must have `[taoensso.timbre :as log]` in its ns `:require`
- `00SERVER-LOGS.txt` is gitignored — do not commit log files

## Debugging Tips

### Use `tap>`

Instead of `println`, use `tap>` for debugging:

```clojure
(defn my-function [x]
  (tap> {:debug/input x})  ; Send to tap system
  (let [result (* x 2)]
    (tap> {:debug/result result})
    result))
```

Connect a tap listener in your REPL (Portal, Reveal, etc.) to see the data.

### Use `clojure.spec` for Data Validation

Define specs for your data structures:

```clojure
(require '[clojure.spec.alpha :as s])

(s/def ::email (s/and string? #(re-matches #".+@.+" %)))
(s/def ::user (s/keys :req-un [::name ::email]))

;; In REPL:
(s/valid? ::user {:name "Alice" :email "alice@example.com"})
;=> true
```

## Performance

- **Don't optimize prematurely** - Focus on correctness first
- **Measure before optimizing** - Use `time` or profiling tools
- **Prefer simplicity** - Clojure's immutable data structures are fast enough for most use cases

## Route Handler Convention: Named `defn` + `#'var` References

**Always extract route handlers as named `defn`s** and reference them with `#'var` in the route table. This enables REPL-driven development — redefining a handler function takes effect immediately without restarting the server.

```clojure
;; GOOD — named handler + var reference (REPL-reloadable)
(defn handle-home [_]
  (resp/response (str (views/home-page))))

(defn make-routes []
  [["/" {:get {:handler #'handle-home}}]])

;; BAD — inline anonymous fn (requires server restart to change)
(defn make-routes []
  [["/" {:get {:handler (fn [_] (resp/response (str (views/home-page))))}}]])
```

**Why `#'var`?** The `#'` syntax creates a reference to the var itself, not the current value. When you redefine the function in the REPL, the route table automatically uses the new definition because it dereferences the var on each request.

**Convention:**
- Name handlers `handle-<route>` (e.g., `handle-home`, `handle-reload`, `handle-source`)
- Place all handler defns above `make-routes`
- Use `#'ns/handler` for handlers in other namespaces (e.g., `#'track/handle-track`)

### Reitit Route Hot-Reload (No Restart for New Routes)

`#'var`-quoting handles handler *body* changes, but adding a new *route path* normally requires a server restart because reitit compiles routes into an immutable router at startup. Fix: use `reloading-ring-handler` in dev mode to rebuild the router on every request:

```clojure
(require '[reitit.ring :as ring])

(defn- make-ring-handler []
  (ring/ring-handler
   (ring/router (make-routes))
   (ring/routes
    (ring/create-resource-handler {:path "/"})
    (ring/create-default-handler))))

(defn- make-app [dev?]
  (let [handler (if dev?
                  (ring/reloading-ring-handler #'make-ring-handler)
                  (make-ring-handler))]
    (-> handler wrap-params wrap-content-type)))
```

Combined with `wrap-reload` (which recompiles changed `.clj` files), adding a new route is now: save file → browser works. No `make restart`. Only restart needed for middleware stack changes or new deps.

## `ENV=dev` Convention for Web Servers

All our Clojure web servers use `ENV=dev` to control dev vs prod behavior. This single env var gates everything:

```clojure
(let [is-dev? (= "dev" (System/getenv "ENV"))]
  (if is-dev? (create-app-dev) (create-app)))
```

**`ENV=dev` enables:**
- Browser auto-reload (`browser-reload` middleware + file watcher)
- Server-side code reloading (`ring.middleware.reload/wrap-reload`)
- DEV banner in upper-right corner
- Disables basic auth (no login prompt during development)

**Production (no `ENV`) enables:**
- Basic auth via `ring.middleware.basic-authentication`
- No reload middleware, no file watchers

**Makefile convention:**
```makefile
server-dev:
	ENV=dev PORT=8767 clj -X cli.server/start :port 8767

start-prod:
	clj -X cli.server/start
```

**Auth bypass:** Use `wrap-auth-with-bypass` to skip auth on public endpoints (`/api/track`, `/ping`, `/dev/reload-check`).

**Secrets:** Always use Secret Manager (`--set-secrets`), never plaintext `--set-env-vars` for passwords.

## Resources

- [Clojure Style Guide](https://guide.clojure.style/)
- [Kaocha Documentation](https://cljdoc.org/d/lambdaisland/kaocha/)
- [REPL-Driven Development](https://practical.li/clojure/introduction/repl-workflow/)

## Remember

> "The REPL is your friend. Use it constantly. The faster you get feedback, the faster you learn and improve."

**Key principle**: If it takes more than a few seconds to test your code, you're doing it wrong. Fix the feedback loop first, then write code.

## Fast feedback loops: tests run in the BACKGROUND (Gene, 2026-08-11)

Never block a turn waiting on the suite. The warm watcher (`make
runtests-log`, pid-guarded, one instance) reruns on every save; read results
ONLY via `make test-verdict` (exit 0 green / 1 red / 2 warming). One-shot
runs (`make runtests-once`) always launch with run_in_background and get read
when the notification lands. Keep editing while tests run — the watcher
catches cold-load and contract breakage the warm REPL cannot (proven twice
on 2026-08-11).
