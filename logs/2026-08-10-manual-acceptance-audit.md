# Manual acceptance and rubric evidence audit — 2026-08-10

Bead: `sessionize-sched-killer-o3s`

## Scope and safety boundary

This lane produced the final human-run acceptance instrument while another lane
owned chase/server recovery. It wrote only this captain's log, the acceptance
document in `docs/`, and Beads state. It did not modify `src/`, `test/`, the
server process, browser state, databases, Cloud Run, files in the event store,
or git.

All HTTP checks were GET-only. No persona login was submitted because that
would append an authentication fact and violate the read-only boundary.

## Inputs reconciled

- Current structural route table: organizer, reviewer, speaker, public,
  API/export, schedule, files, integrations, telemetry, replay, and dev surfaces.
- Exact AIE persona and session contracts from the judge-sign-in tests.
- Exact Charlotte fixture facts from `seed_demo.clj`.
- Official-rubric gap analysis and close-to-100/judge-readiness plans.
- Current `docs/rubric-vs-actual-usage.md`, operator protocol, auth, telemetry,
  and deploy documentation.
- Today's live-drive logs for reviewer assignment/work, review plans, named
  scorecards, speaker roster/deliverables, files, and eight-tab SSE behavior.

## GET-only findings

### Localhost `:20500`

- `/login` returned 200 and contained exactly one each:
  `Organizer · swyx`, `Reviewer · Maya Lindholm`,
  `Speaker · Amara Devlin`.
- `/cfp/ai-engineer-code-summit`, `/agenda/ai-engineer-code-summit`, and
  `/api/v1/events/ai-engineer-code-summit` returned 404. The running local store
  does not have the AIE fixture event.
- Charlotte CFP, agenda, API, JSON exports, ICS, and `llms.txt` were reachable.
- Charlotte's public API reported zero published sessions, zero published
  speakers, and zero rooms. Reachability is therefore not filled-state proof.

### Deployed `swyx-cfp-saas-killer`

- `/login` returned 200 but did not contain the three persona labels.
- `/agenda/ai-engineer-code-summit` and the AIE event API returned 200.
- AIE sessions/speakers JSON, ICS, and `llms.txt` returned 200.
- The event API reported zero published sessions, zero published speakers, and
  zero rooms.
- `/agenda/ai-engineer-code-summit/sessions`, `/speakers`, and `/gallery`
  returned 404. Those routes exist in the current shared worktree, proving the
  deployed revision is behind local implementation.

These findings belong to the existing P0 operator/deploy gate
`sessionize-sched-killer-9pq`; no duplicate defect bead was created.

## Shared-worktree coordination

Another active lane expanded `docs/manual-acceptance-tour.md` between this
lane's write and verification. The newer 66-stop, 160-route document was
preserved. This lane added only the missing exact sbek fixture literals, the
GET-only judge-visibility snapshot, and the refreshed source/test inventory.
No whole-file replacement was attempted after the collision was detected.

The merged tour also exposed one independent judge-visible defect: the CFP
success page still says confirmation email and the portal are “not yet wired
up.” No existing Beads issue covered it, so it was captured as
`sessionize-sched-killer-c5x`.

## 2026-08-10 follow-up

The stale-copy finding is resolved by `sessionize-sched-killer-n1b.3`; duplicate
bead `c5x` was superseded into it. The worktree now has 161 routes (65 GET / 96
POST), including an event-scoped organizer portal-invite action. Focused proof
is 43 tests / 682 assertions / 0 failures, both full suite modes are 333 /
3,326 / 0, and the cold JSONL drive is 263 / 263. The deployed-state findings
above remain historical and still require an operator deployment/recheck.

## Inventory evidence

Measured all Clojure-family files under the two trees:

- `src/`: 108 files, 22,418 lines.
- `test/`: 54 files, 12,037 lines.
- Test/source ratio: 0.500 by file count and 0.537 by line count.
- Combined: 162 files, 34,455 lines; tests are 34.9% of combined lines.

Largest namespaces are recorded in `docs/manual-acceptance-tour.md`.

## Result

Documentation outcome: PASS. The new acceptance instrument:

- gives every stop a persona, URL/state, action, visible result, purpose, and
  cleanup/safety posture;
- enumerates all current GET surfaces and their action families;
- distinguishes disposable JSONL, production-shaped read-only, and
  operator-gated deployed work;
- pins exact persona, fixture, content-status, scorecard, speaker, file, and
  chase vocabulary;
- includes a concise judge-ready order and an explicit stop-on-blocker rule;
- separates local implementation evidence from deployed judge visibility; and
- includes the required Clojure source/test inventory.

Application acceptance outcome: NOT RUN by design. The document is ready for a
human/operator tour after P0 fixture/deploy prerequisites are satisfied.
