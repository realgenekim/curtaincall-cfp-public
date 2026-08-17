# Captain's log — house reconstruction and winning deploy lane

Date: 2026-08-12 (America/Los_Angeles)

## Ruling

- Sole integration trunk: `/Users/genekim/src.local/cc-home-story.fte5BF`
- Branch: `codex/589-homepage-tool-archaeology`
- Production/deploy target: `origin/staging` through `build-and-deploy.yml`
- Do not integrate through the dirty shared checkout at
  `/Users/genekim/src.local/sessionize-sched-killer`.

## The merge disaster we inherited

This was not a normal feature-branch merge. The repository had become several
simultaneously plausible houses:

- `origin/staging` was the deployed product and contained the newest public
  story, social cards, blind-review safety chain, announcement/inform flow, and
  the handcrafted swyx judge entrance.
- `origin/main` contained a wide set of judge-rubric capabilities that had not
  reached staging: review plans and assignments, scorecards, speaker tasks and
  custom fields, content/files, embeds, personal schedules, CRM, agent/API
  surfaces, and the current tech tree.
- Four orphan feature branches contained completed Mention, Tracks, Edit
  Speaker, and Notify work that was based on older ancestry and had never been
  folded into either canonical line.
- CFP4/Buster had completed and accepted the Amara speaker-persona door in a
  separate worktree. CFP3 had completed the API-key masking/copy-on-demand
  security work in another.
- The shared `main` checkout was dirty with user and agent files, including
  files touched by candidate branches. It could not safely serve as an
  integration surface.
- Multiple local servers were simultaneously showing different generations of
  the product: dirty main on `20500`, a dedicated staging checkout on `20501`,
  CFP4 on `20503`, and earlier reconstruction work on `25000`. A visual pass on
  one port did not prove what production, staging, or a candidate commit held.
- Runtime `00SERVER-LOGS.txt`, formatter churn, and unexpected commits appearing
  in shared worktrees made `git status` an unreliable handoff unless every
  commit and path was inspected explicitly.

The dangerous shape was that neither `main` nor `staging` was a strict superset
of the other. A naive “merge whichever branch is newest” could compile while
silently throwing away the deployed public experience, the newer blind-review
doctrine, or main-only judge capabilities. Whole-branch merges of the old blind
and policy branches would also have reintroduced superseded implementations.

## Reconstruction method

1. Declared `cc-home-story.fte5BF` the sole integration trunk because it began
   at the known deployed `origin/staging` commit and contained the most advanced
   accepted public/judge entrance.
2. Preserved CFP4 as immutable commits and integrated only its verified Amara
   speaker shell/door. Stopped its local server and prohibited further pushes.
3. Merged `origin/main` once into that trunk. The merge produced roughly two
   dozen conflicts concentrated in the exact files where staging had newer
   behavior. Conflict resolution deliberately kept the advanced staging side
   while accepting all non-conflicting, additive main modules and tests.
4. Formatted only the reconstructed Clojure surfaces and committed that as a
   separate receipt, making semantic reconstruction distinguishable from
   mechanical formatting.
5. Re-ran the merged test corpus fail-fast. Each failure is treated as a
   compatibility seam created when an additive main caller met a newer staging
   owner. The fix is the smallest compatibility function or addressable UI
   frame that preserves the newer implementation; no rollback to an older
   namespace wholesale.
6. Classified orphan branches before integration. Newer blind/policy/refactor
   branches that duplicate or supersede deployed work are excluded. Only the
   four additive accepted feature commits are candidates for cherry-pick.
7. Required CFP3 and CFP4 to produce immutable, allowlisted commits with green
   receipts, excluding secrets, runtime logs, captain logs, and unrelated dirty
   work. The reconstruction trunk alone owns deployment.
8. Started the reconstructed trunk through the Makefile on isolated port
   `20505`, so every further change is visible with hot reload without confusing
   it with dirty main or the old staging checkout.

## Why the intermediate failures are useful

The merged tests are doing exactly what we need: exposing missing contracts
instead of allowing a superficially successful Git merge. So far they caught:

- a missing event-scoped indexed-log projection used by main-only content
  history;
- a missing exact log-index time-travel projection used by replay/audit views;
- a public-CFP SSE handler whose target frame had disappeared from the newer
  initial page, which would have made live conditional questions fail even if
  the namespace compiled;
- a missing committee-role compatibility query needed by the merged review
  board and dashboard;
- and now an architecture test whose clj-kondo input set is being excluded by
  inherited ignore configuration, rather than an application behavior failure.

This is the point of reconstruction: Git answers whether text can be combined;
the expanded test corpus answers whether all generations of the house still
agree on their load-bearing contracts.

## Preserved work

- Production baseline before reconstruction: `5354200` on `origin/staging`.
- Main history merged at `6ff141c`; reconstructed Clojure formatting at
  `cc7480b`.
- Amara judge sandbox shell and enabled door: `ed9df52` + `c5cda9e`.
- CFP4 acceptance receipt: swyx, Maya, and Amara browser flows passed; 323
  tests, 3,396 assertions, zero failures before main reconciliation.
- CFP3 immutable Settings/API-key commit: `ad7a114`; focused 60 tests / 936
  assertions and full 322 tests / 3,356 assertions green. It is not yet
  integrated.

## Live local topology

- `20500`: dirty shared main checkout.
- `20501`: `/Users/genekim/src.local/curtain-call-staging`.
- `20505`: reconstructed integration trunk, started with
  `make judge-sandbox PORT=20505`; hot reload enabled.
- Review URL: http://127.0.0.1:20505/login

## Compatibility work in progress

- Restored `store/indexed-log-for-event` in `6c713d6`.
- Restored the addressable public-CFP session-fields frame.
- Restored exact log-index time-travel projection.
- Restored committee role query compatibility.
- Current test frontier: the architecture test needs fresh clj-kondo
  dependency evidence after the main/staging reconstruction.

## Judge leverage

The first evidence-only score was 83.3% over the judged CFP slice. The largest
risks are gates, not absent product capability:

1. Any demo-created speaker/reviewer must be able to obtain a magic link.
2. Accepted-but-uninformed talks must lead the user to Inform Speakers rather
   than claiming that everything is scheduled.
3. Embeds/widgets and review-plan/rounds must be discoverable from navigation.
4. The deployed sandbox must contain working swyx, Maya, and Amara fixtures and
   a populated accepted → informed → scheduled → published decision wave.
5. CFP submit must land on a friendly confirmation URL; an untouched visitor
   must not see a false saved-draft banner.

## Exit criteria

Integrate accepted feature commits, close the highest-leverage gates, format,
pass the full suite, browser-accept all three personas locally, push the exact
commit to `origin/staging`, watch CI deploy both services, smoke-test production,
then update and prune Gene's tech-tree cockpit to production truth.

## 03:17 PT — hill-climb wire and current merge frontier

The hill-climb and integration loops now have an explicit one-way contract.
`20505` is volatile integration. A full-green code SHA is started on a separate
candidate port and must pass the login/demo-login/session-cookie door test
before it can advance `20506`. Every passing code SHA is preserved as
`handoff/reconstruction-<short-sha>`; old handoff refs never move. The exact
code SHA, UTC time, local door result, production door result, changes, and
integrated Beads are published in `docs/hill-climb/CHECKPOINT.md` on
`origin/main` from a dedicated clean worktree. CFP1 polls that file and bases
new fix lanes only on PASS checkpoints.

CFP1 and the integration lane ratified production as a green-batch operation,
not a focused-test operation: full suite, candidate door, and swyx/Maya/Amara
smoke first. Production has its own outside-in door proof because fixture
people and persona environment flags are runtime state, not image state. A
local PASS is therefore never reported as production judge-ready. Lane A's
email queue/approve work is isolated as its own batch. Browser asset changes
must retain fresh query-string cache busts. The 21:00 PT freeze is P0-only, and
deploys stop during CFP1's named final 30-minute production sweep.

The current local hill climb is proven isolated. PID 29995 on port 20506 runs
from the detached `cd175a4` worktree through `make judge-sandbox`, which sets
`STORE_PATH=data/store/judge-sandbox/events.jsonl` and calls `server-jsonl`.
The runtime log grew from 3,063 to 3,075 facts during CFP-S3; the 12 appended
facts are sandbox sessions, the sbek reviewer fixture, membership, ratings, and
a comment. Restarting the target restores the committed golden log. Test
accounts alone do not provide isolation; the `20506` target does. Generic
`server-dev` still points at production Postgres.

The reconstructed route contract is now 217 distinct entries (87 GET, 129
POST, one PUT) with hash
`07f14b1fc1dd40b6d6f37c46cc4a3b2c02fc0798eecddfde31d29e26493aacac`.
The first full-suite run exposed one real split-brain fold: the portable
`task.installed` fact carries an ISO date string, `folds.clj` rehydrates it, but
the retained monolithic `store.clj` fold did not. The store fold now restores
`:due-on` as `LocalDate` too. Its focused route test then reached the next
frontier: speaker file routes are present but the retained auth/portal wiring
still prevents the modular file surface from being exercised. That wiring is
the active repair before the next fail-fast suite run.

## 04:15 PT — split-brain seams consolidated

The repair pass is now through every failing seam exposed by the expanded test
corpus. The final route contract intentionally excludes the retired Gallery:
216 distinct entries (86 GET, 129 POST, one PUT), hash
`72ccdf7515238eab2504a30dfe3ef056e4c2576924ef8c81d5918962f1e7c5a8`.

The important repairs were consolidation, not parallel reimplementation:

- canonical middleware once again owns multipart uploads;
- canonical portal/file handlers own speaker tasks, files, custom profile
  values, and headshots;
- the portable store fold and retained compatibility fold agree on task due
  dates;
- schedule rendering retains Tracks and the explicit conservative
  "Suggest schedule" action;
- the review detail composes the canonical chair-edit/history controls and the
  canonical presenter-visibility policy pill;
- public CFP page, draft, confirmation, submit, import, reset, and stream
  routes share one draft store and one canonical handler family;
- the roster speaker edit and announcement speaker edit share a single public
  route with explicit compatibility dispatch by form shape;
- mail delivery goes through `io.email/send-with-config!`, preserving replay,
  mute, and SES safety while recording `:from` and `:task-key`; and
- the route contract records the real binary content types for ZIP and uploaded
  file downloads rather than pretending every GET is HTML.

Focused receipts are green across portal/files, widgets/topology, schedule,
review, drafts, speaker editing, and mail. The full-suite receipt and immutable
checkpoint SHA are appended only after the complete fail-fast suite and clean
candidate door test both pass.
## 05:41 PT — reconstructed house is green

The merge was not a normal branch join. Multiple branches had independently
grown the same composition root, fold authority, and large view namespaces;
Git could preserve text but could not preserve behavior. The first combined
tree had duplicate route owners, superseded handlers winning by order,
projection methods split between two namespaces, and extracted handlers that
retained only one half of composed authorization or rendering behavior.

The repair established one authority at each seam: a 400-line server
composition root, additive routes merged by method/path, folds owned by
`folds.clj`, extracted handler namespaces, and explicit current-policy context
crossing the historical replay boundary. Fail-fast tests then exposed the
remaining lost halves: speaker-safe overview plus reviewer redirect, chair
distribution plus table visibility, durable blind policy plus legacy flags,
public featured speakers plus CFP form, read-only details plus organizer edit,
and stable anonymous labels plus submission pseudonyms.

Final receipt: `461 tests, 4689 assertions, 0 failures.` The reconstructed
branch is now smaller by roughly 1,900 net lines while carrying the union of
accepted capabilities. This is the checkpoint from which judge fixes can be
cheap, isolated, and reversible again.

## 07:17 PT — Lane A completed the hill-climb circuit

The approval-gated email outbox landed as two additive commits: `874584d`
integrated every send point behind queued/approve/discard facts, minted a real
one-time portal token on CFP submit, and preserved the safe development provider;
`b1c516e` repaired the last browser truth defect by folding each email lifecycle
to one latest Send History row. A real sandbox click changed the queued decision
letter to `sent`; the first returned page showed one outbox record and one sent
history row, with no blank approval record and no network mail.

Local verification was `462 tests, 4705 assertions, 0 failures`. Two GitHub CI
seeds then exposed pre-existing fact-count races in `agent_test.clj`: async
acceptance-notify work could append after tests captured their baseline. The
application behavior was correct; `25eac96` and `80f0091` added the existing
`store/await-sinks!` barrier to the only two tests that require stable log
cardinality. CI run `31605537239` passed, built from `80f0091`, promoted both
Cloud Run services, and passed its live verification.

Independent production proof: `/login` 200; Organizer·swyx, Reviewer·Maya, and
Speaker·Amara each POST 303, mint exactly one `ring-session`, redirect to
`/welcome`, and receive 200 there. `/cfps` and the canonical Charlotte
`/program/enterprise-ai-summit-charlotte-2026` both return 200. Stable port
20506 follows the same immutable lineage; its golden fixture was reset before
the batch and its runtime was then preserved for CFP-S2 continuity.

CFP-S2 closed `95cx` and `wmw3` at the meter. The confirmation page displayed
Priya Raman's private one-time link; clicking it authenticated her, opened her
speaker portal, showed the submitted talk, and an edit survived hard reload.
The old “Not yet wired up” copy was absent. Separately, title, full abstract,
and Track survived a required-field validation re-render and the next radio
click. This is the first complete loop: eval finding → Bead → isolated fix →
trunk integration → green checkpoint → production deploy → same-scenario proof.

Checkpoint wire: code `80f0091c11370487f699326305658eb286f615ab`,
`origin/handoff/checkpoint-log` at `b68e970`. Next intake is reviewer v2
`e2c0171200b09414f12494b4141d419721648a3b`; `s8ar` remains the separate P0
because the chair is currently blinded while the policy card claims otherwise.

## 07:44 PT — reviewer truth and the lost doors

Reviewer v2 arrived with a dangerous immutable-ref near miss: its first
published SHA predated the combined rate+comment amendment, and the worker was
stopped at a force-push prompt. The corrected work landed on a new `-v2` ref,
was independently diffed, and was cherry-picked as `4989177`. It gives a newly
created committee reviewer a real onboarding link, preserves the organizer's
session when a persona switch fails, removes chair-only controls from the
reviewer surface, and makes one comment action carry the selected rating into
the first live response.

The live `s8ar` failure exposed the larger design defect: legacy event settings
and the durable review-plan policy were competing authorities. The card read
VISIBLE from the durable policy while rows were blinded from legacy settings;
the view then ignored the domain projection's explicit chair exception. Commit
`b9fed25` made the durable policy canonical and made chair visibility explicit
on board and detail. A new adversarial test proves reviewer-hidden and
chair-visible behavior together, including historical scrub projections. The
full gate passed at 464 tests / 4,790 assertions; candidate 20508 proved the
real hidden-policy endpoint produced zero anonymous organizer rows and blind
reviewer board/detail. CI `31607224417` passed, both Cloud Run services were
promoted, production persona doors passed, and preserved-runtime 20506 advanced
to the same immutable SHA for ABS-S3/CFP-S3 reruns.

The next discovery was not missing capability but missing doors. Git history
proved `b4b2103` once linked Speakers, Speaker deliverables, and Files; later
sidebar reconstruction lost those links while their routes and views survived.
Speaker CSV import was therefore already complete but invisible inside the
orphaned Speakers page. Embed was different: its route and builder were built
without any historical sidebar link. A structural two-form edit restored all
four doors and gave Embed its own active state. Candidate acceptance returned
200 for speakers/deliverables/files/embed, displayed `Import speakers.csv`, and
displayed the copyable embed snippet. A flaky reviewer SSE test was made honest
with a promise waiting for the actual row patch rather than racing the push
agent. The resulting `13f8f67` gate was 464 tests / 4,791 assertions. Its first
CI run exposed another asynchronous acceptance-notify boundary in the export
version fixture; `store/await-sinks!` made setup completion explicit. Immutable
v2 `99b1751` passed CI `31608635683`, deployed both services, and passed
outside-in production proof on the sandbox event: all four sidebar links and
destinations returned 200, `Import speakers.csv` rendered, and the copyable
embed snippet rendered.

## Throughput countermeasures — trunk owner's independent ten

1. **One immutable wire.** A published SHA never moves; amendments publish a
   new `-v2` ref. Remote policy blocks force-push and deletion.
2. **PASS-base lease.** Every lane records its exact parent PASS SHA; intake
   rejects the wrong ancestry before inspecting the patch.
3. **Machine-generated handoff.** One repo command derives bead, SHA, parent,
   stat, paths, focused/full receipts, and route/browser evidence from tools.
   A missing field makes the lane non-intakeable.
4. **Green-base certificate.** A checkpoint publishes suite count, fixture
   hash, and toolchain hash, so branch/config drift cannot masquerade as a
   product regression.
5. **One-shot lane runner.** Create the worktree, install guards, launch Codex
   with `approval_policy=never` plus `workspace-write`, verify, commit, push
   only its owned ref, and print the receipt—no parked composer.
6. **Parallel meters, single trunk.** Run four scenarios on four isolated
   golden stores against one candidate SHA; retain one integration authority
   and 15–30 minute green batches.
7. **Finding-scenario closure.** Source-green means integrated; the bead closes
   only when the scenario that found it re-passes.
8. **Outside-in deploy gate.** CI success is followed by persona-cookie,
   `/welcome`, `/cfps`, canonical `/program`, and changed-surface probes.
9. **Discoverability contract.** Every organizer GET route must have a
   role-appropriate rendered anchor or an explicit internal-route exemption.
10. **Single-authority and idempotency laws.** Each projection names one
    canonical source; tests forbid duplicate lifecycle rows and duplicate task
    installation under replay/retry.

Two subordinate laws: a message is not delivered until the receiver reports a
concrete action, and measurements are quoted from tools rather than inferred.
The Buster proof confirmed that `codex exec -s workspace-write -c
approval_policy="never"` runs unattended while an outside-workspace write is
blocked. `--dangerously-bypass-approvals-and-sandbox` remains prohibited. A
local pre-push hook is defense-in-depth only; the authoritative remaining guard
is a GitHub ruleset blocking force-push/deletion and protecting main/staging.
The authenticated owner token had admin permission, so this was not a human
blocker after all: active ruleset `20751015` now rejects deletion and
non-fast-forward updates across every branch while preserving ordinary staging
deploy pushes and new lane refs.
