# Manual acceptance tour and competition rehearsal

**Prepared:** 2026-08-10
**Purpose:** give Gene one repeatable instrument for proving the shipped product
to himself, an operator, and a competition judge. This complements the automated
suite; it does not replace it.
**Verified route snapshot:** 161 routes (65 GET, 96 POST). Human-assisted chase
is green at focused 26 tests / 414 assertions and a cold JSONL HTTP drive at
263 / 263. The portal-invite slice is green at focused 43 tests / 682 assertions
and both full-suite modes at 333 / 3,326 / 0; the cold JSONL drive remains green
at 263 / 263. Detailed chase evidence is in
[`2026-08-10-human-assisted-chase-acceptance.md`](../logs/2026-08-10-human-assisted-chase-acceptance.md).

This document has two paths:

- **Fast smoke tour:** about 25 minutes, enough to catch a broken handoff or an
  empty judge-visible surface.
- **Complete judge rehearsal:** about 75–100 minutes, covering every currently
  built organizer, reviewer, speaker, public, export, and integration family.

For a genuinely uncoached usability study with Ann, use
[`operator-rehearsal-protocol.md`](operator-rehearsal-protocol.md). Do not coach
that protocol with the URLs and labels below; this document is deliberately a
coached verification script.

## Safety lanes

Every stop is labeled with one of these lanes.

| Mark | Lane | Rule |
|---|---|---|
| **L** | Local JSONL | Safe place for mutations, junk fixtures, uploads, invalid inputs, archive/restore, and cross-role probes. Start with `PORT=20501 make server-jsonl`; set `BASE=http://localhost:20501`. |
| **P-RO** | Production read-only | Page loads, search, downloads, and access-denial probes only. Remember that `make server-dev` uses the one production Postgres database. |
| **O** | Operator-gated | Production mutation through named domain verbs, DDL as the table owner, secrets/env configuration, deploy, instance recycle, or production fixture preparation. Never improvise these from a browser tour. |
| **X** | External side effect | SMTP, Slack, Airtable, webhook, or another real recipient. Use a purpose-made test destination and explicit human approval. |

Do not run `make store-reset` in the shared checkout casually. It deletes the
entire local JSONL log. For a clean destructive rehearsal, use a disposable
worktree or first preserve the local sandbox data. Never point a test or agent
at Postgres.

## Fixture and persona prerequisites

Set these notes before starting:

```text
BASE       = http://localhost:20501 for the safe JSONL tour
SLUG       = the filled AI Engineer Code Summit demo event slug
OTHER_SLUG = a different event used only for access-denial checks
NEW_SLUG   = the event created during the ten-minute activation test
```

The deployed judge instance must show exactly these three role buttons on
`/login`, with no second control starting with the same role word:

- `Organizer · swyx` — `swyx@ai.engineer`
- `Reviewer · Maya Lindholm` — `maya.lindholm@example.com`
- `Speaker · Amara Devlin` — `amara.devlin+472@beaconloop.example.com`

All three must be AIE-only identities. In particular, never use Ann Perry, Alex
Brodrick-Forster, or Gene Kim as judge personas; they hold real ITRev event
seats and would pierce the tenancy demonstration.

### Literal judge fixture audit

The sbek scenarios match these strings literally. Verify the exact spelling,
casing, punctuation, and uniqueness on the rendered screen; a semantically
similar label is not a pass.

| Fixture kind | Exact visible value | Acceptance stop |
|---|---|---|
| Event | `DevFlow Conf 2027` | A4 |
| Talk | `Taming 40-Minute CI: Incremental Builds at Monorepo Scale` | B6, C2, G1 |
| Track values | `AI Engineering`; `Platform & Infra`; `Developer Experience` | B1, G1 |
| Required field labels | `Track`; `Format` | B1 and public CFP |
| Format values | `Keynote (45 min)`; `Panel (45 min)` plus the other fixture-supplied values | B1, G1, G4 |
| Rooms | `Main Stage`; `Room 2A`; `Room 2B`; `Workshop Lab` | F1, F5 |
| Organizer edit sentinel | `SBEK-ORG-EDIT-01` | C9 when the organizer edit screen exists |
| Speaker bio sentinel | `SBEK-PORTAL-BIO-01` | E4 |
| Publication | `Draft`; `In review`; `Approved`; `Publish` | C9, F4 |
| Closed-CFP evidence | `The call closed <date> — editing is locked.` | B7 |
| Scorecard | `Scorecard criteria`; `Numeric`; `Dropdown`; `Free text`; `Weight`; `Scorecard` | C7 |
| Speaker status | `Invited`; `Confirmed` | D4 |
| File flow | `Request a file`; `Deliverable type`; `Due date`; `Upload file`; `Upload a new version`; `show history`; `Conversation`; `Download all as ZIP` | E7–E9 |
| Upload constraint | `PDF, PowerPoint, Keynote, PNG, or JPEG · 25 MB maximum` | E7 |
| Chase safety | `This page records human follow-up. It never sends an automatic reminder.` | D6–D8 |

### Current judge-visibility snapshot

GET-only checks on 2026-08-10 found that local and deployed evidence must not be
conflated:

- Local `/login` had exactly the three persona labels, but the local store had
  no `ai-engineer-code-summit` event. Local Charlotte reported zero published
  sessions, zero published speakers, and zero rooms.
- The deployed AIE event and base agenda/API existed, but `/login` had no demo
  persona labels. The event API reported zero published sessions, zero
  published speakers, and zero rooms.
- The deployed `/agenda/ai-engineer-code-summit/sessions`, `/speakers`, and
  `/gallery` paths returned 404 even though they are present in the current
  161-route worktree. Raw JSON, iCalendar, and `llms.txt` URLs returned 200 but
  contained no published program.

These are operator/deploy prerequisites already owned by
`sessionize-sched-killer-9pq`. Mark competition rehearsal stops `BLOCKED` until
the current revision, persona configuration, and filled AIE decision wave are
deployed and independently rechecked.

The filled demo event should contain:

- at least one row in every submission status: Draft, Pending, Accept Queue,
  Accepted, Waitlisted, Decline Queue, Declined, and Withdrawn;
- Accepted submissions that have and have not been informed, so the publication
  gate is visible;
- at least two reviewers, explicit assignments, one incomplete reviewer queue,
  ratings, comments, and one recusal;
- one optional named criterion, and optional review-plan data only if the judge
  will exercise those rubric-shaped controls;
- rooms, placed sessions, a block, an unplaced tray item, and at least one
  deliberate scheduling conflict for the withholding test;
- speakers in Invited and Confirmed states, a CSV-imported speaker, tasks that
  are complete, incomplete, due today, and overdue;
- one file request with no upload, and one file with v1/v2 plus organizer and
  speaker comments;
- enough Accepted + informed + conflict-free sessions to fill every public
  program page.

Prepare these harmless local files outside the repository:

- `speakers.csv`, for example with headers `name,email,title,company,status`;
- two different tiny PDF or PowerPoint files with the same filename, to prove
  immutable versions;
- one PNG or JPEG headshot.

Before a production rehearsal, the operator—not the coding agent—must confirm:

- demo persona flag on the demo service only;
- the allowlisted magic-link emails;
- recycle-safe cookie-session secret;
- production telemetry schema and grants;
- email/blob/integration secrets as applicable;
- the decision-wave facts that make public pages non-empty;
- the single canonical competition URL.

## What counts as a pass

A stop passes only when all applicable evidence agrees:

1. the visible screen says what the user needs to know;
2. a refresh preserves the result;
3. `/events/<slug>/log` shows the expected append-only fact for a mutation;
4. another role sees exactly the projection it should see;
5. a forbidden role or event cannot read or mutate it;
6. email/integration wording is honest about sent, rendered, and failed states.

Write every hands-on result as it happens to
`logs/YYYY-MM-DD-<topic>.md`: flow, pass/fail, exact symptom, fix, and remaining
work. A finding that exists only in chat will be rediscovered.

---

# Fast smoke tour — 25 minutes

Use **L** for mutations. The production version of this tour is read-only except
for steps explicitly owned by the morning operator.

## 1. Public front door and judge access — 2 minutes

**Persona:** signed out
**URL:** `${BASE}/`, then `${BASE}/login`
**Actions:** confirm the public pitch, open login, count the persona buttons,
then open an incognito window and sign in once as each role.
**Expected:** the landing says `How to sign in` when demo mode is enabled;
`/login` has exactly the three labels above. Organizer reaches event work,
Reviewer reaches a review-first surface, and Speaker reaches `/portal`.
An unknown, non-allowlisted email never receives an echoed sign-in URL.
**Rubric:** evaluator coverage; CFP-10; SPK-07; scoping.
**Failure checks:** with demo mode off, the buttons and `/api/demo-login` must
not be usable. No persona may see events outside AIE.

## 2. Mission control and tenancy — 2 minutes

**Persona:** Organizer, then Reviewer
**URL:** `${BASE}/events/${SLUG}`
**Actions:** inspect the dashboard; in the Reviewer session manually request
`${BASE}/events/${OTHER_SLUG}` and its `/board`, `/settings`, and organizer POST
targets without submitting anything.
**Expected:** dashboard shows event health and the viewport-bottom time-travel
strip. The AIE-only reviewer cannot enter the other event. No other-event fact
is appended.
**Rubric:** CFP-10 and cross-event scoping; security gate.

## 3. CFP builder to public form — 3 minutes

**Persona:** Organizer, then signed out
**URLs:** `${BASE}/events/${SLUG}/form` and `${BASE}/cfp/${SLUG}`
**Actions:** verify the real public preview, add or rename one harmless local
question, then view the public page. Retire and restore the question in the full
tour, not production smoke.
**Expected:** preview and public form agree; the stable field ID does not change;
refresh preserves the edit. The log contains `form.updated` (and
`form.reviewed` when finishing setup).
**Rubric:** CFP-01, CFP-03, CFP-06, CFP-09.

## 4. No-account submission handoff — 3 minutes

**Persona:** signed out speaker
**URL:** `${BASE}/cfp/${SLUG}`
**Actions:** type part of a proposal, refresh, confirm the draft is restored,
complete the form, optionally import a Sessionize profile, and submit.
**Expected:** `Thanks — your talk is in.`; the talk appears on the organizer
board with its snapshot intact; the log contains `person.created` as needed and
`submission.created`.
**Portal handoff assertion:** the page says the organizers will email a private
one-time portal link when updates are needed. It must not contain “not yet wired
up.” The organizer creates the link from the canonical roster; do not promise an
automatic email at submission time.
**Rubric:** CFP-05, CFP-06, CFP-08; submission round trip.

## 5. Collaborative review and decision truth — 3 minutes

**Persona:** Reviewer, then Organizer
**URLs:** `${BASE}/events/${SLUG}/review`,
`${BASE}/events/${SLUG}/board`, and one submission detail URL
**Actions:** rate, add a comment, observe another reviewer’s opinion, and have
the Organizer change the submission status and content status.
**Expected:** `Assigned to you` shows completed/assigned counts; the shared
board still exposes the committee conversation. Exact visible literals include
`Approved`, all eight decision statuses, and the separate informed state. The
log contains `rating.set`, `comment.added`, `submission.status-changed`, and
`submission.content-status-changed`.
**Rubric:** CFP-11/12/13; ABS-05/08/10; CNT-12.

## 6. Deliberate informing and truthful mail — 2 minutes

**Persona:** Organizer
**URLs:** `${BASE}/events/${SLUG}/inform` and `/events/${SLUG}/comms`
**Actions:** inspect fully resolved letters, inform one locally, then inspect
Comms.
**Expected:** the page first says nothing has been sent. With SMTP absent it
says `Email is not configured, so nothing is emailed`; Comms records `would
send`, not `sent`. The decision and `submission.notified` are visibly separate.
With real SMTP under **X**, the history says `sent`.
**Rubric:** CFP-14/15; SPK-06/14; side-effect honesty.

## 7. Speaker obligations and human chase — 3 minutes

**Persona:** Organizer
**URL:** `${BASE}/events/${SLUG}/deliverables`
**Actions:** select several speakers owing the same item, click `Draft selected
emails`, edit each individualized draft, and review the explicit human gate.
Only use `Send reviewed messages` with a test mail destination.
**Expected:** no message leaves without that button. A provider-accepted send
shows `Sent and recorded` and resets the obligation clock; local unconfigured
mail shows `Rendered only; not marked contacted`; failure says `Delivery failed;
not marked contacted`. Only an accepted send appends `task.chase-recorded` with
delivery details.
**Verification:** focused 26 tests / 414 assertions / 0; `make runtests-once`
and the non-fail-fast full suite each 331 / 3,303 / 0; cold JSONL HTTP drive
263 / 263.
**Rubric:** CNT-07/08; SPK-09/12/13/14/16.

## 8. Files and versions — 2 minutes

**Persona:** Organizer, then Speaker
**URLs:** `${BASE}/events/${SLUG}/files` and `${BASE}/portal`
**Actions:** inspect a request, upload v1 as the owning speaker, upload changed
bytes as v2, and add one comment from each side.
**Expected:** every file stays attached to one session/task; history shows v1
and v2; comments show their authors; ZIP is available; the log contains
`task.installed`, `file.version-added`, `task.completed`, and
`file.comment-added`.
**Rubric:** CNT-01/02/04/05/06/13/14; SPK-10.

## 9. Schedule and publication — 3 minutes

**Persona:** Organizer, then signed out
**URLs:** `${BASE}/events/${SLUG}/schedule` and `${BASE}/agenda/${SLUG}`
**Actions:** inspect rooms, blocks, tray, placements, conflict warnings, lock
state, and click the literal `Publish` only in **L**. Open the public agenda.
**Expected:** `Published ✓`; log contains `slot.assigned`, `schedule.locked` or
`schedule.unlocked`, and `agenda.published` as exercised. Known-conflict
sessions are loudly withheld from public agenda and exports.
**Rubric:** AIA-01/03/04/05/06/07; EMB-06/07/08/09.

## 10. Public program and exports — 2 minutes

**Persona:** signed out
**URLs:** `${BASE}/agenda/${SLUG}/sessions`, `/speakers`, `/gallery`, one
session detail, one speaker detail, `/events/${SLUG}/exports`,
`/events/${SLUG}/llms.txt`, `/api/v1/events/${SLUG}`, and
`/api/v1/events/${SLUG}/docs`
**Actions:** search sessions by title and speaker; apply Track; search speakers;
follow detail links; download JSON and ICS; open API docs.
**Expected:** populated, mutually consistent pages. Session detail literally
shows `Session Details`, `Subsessions (0)`, complete start–end/room, `Format:`
and `Track:`. Public endpoints work logged out; private fields never appear.
**Rubric:** EMB-01–09, EMB-12–16; API/export bonus.

---

# Complete judge rehearsal

## A. Foundation, event lifecycle, and immutable history

| Stop | Persona | Lane | Starting URL and prerequisite | Exact actions | Expected visible result and fact proof | Rubric / purpose | Cleanup |
|---|---|---:|---|---|---|---|---|
| A1. Landing and login | Signed out | P-RO | `${BASE}/`; demo flag on | Open `/login`; sign in as each fixed persona in separate incognito profiles | Exactly one Organizer, Reviewer, and Speaker button; role-appropriate home; unknown email gets neutral response and no echoed token | Evaluator unlock; tenancy | Log out each profile |
| A2. Cookie recycle | Each demo role | O | Deployed demo; cookie secret provisioned | Save all three authenticated sessions; operator forces a new service instance; refresh | All three sessions remain authenticated | One-hour judge reliability | Operator records revision and result |
| A3. Events list | Organizer | L | `/events` with active and archived fixture | Open active event; archive a disposable event with `x`; expand Archived; Restore | Active row disappears, then reappears; no deletion language; `event.archived`, then `event.unarchived` | Lifecycle safety | Restore the event |
| A4. Ten-minute creation | Organizer | L | `/events/new`; stopwatch ready | Create “DevFlow Conf 2027,” dates, URL, timezone, support email; finish form and committee; obtain public CFP URL | Live marquee updates while typing; event, committee, form exist; `event.created`, `committee.created`, `form.installed`, `member.added`; public URL within 10 minutes | CFP activation doctrine | Keep fixture for remaining local stops |
| A5. Details round trip | Organizer | L | `/events/${NEW_SLUG}/details` | Change pitch/location/date; Cmd/Ctrl-S; refresh | Save toast; all changed values persist; slug is visibly permanent; `event.updated` | CFP setup depth | Restore only if desired through another update |
| A6. Mission control | Organizer | P-RO | `/events/${SLUG}` filled fixture | Inspect stats, what-needs-you queue, recent activity, setup completion, and bottom scrubber | No empty ornamental dashboard; scrubber remains visible and complete contract is present | Operator readiness; historical safety | None |
| A7. Log and scrub | Organizer | P-RO | `/events/${SLUG}/log` and board | Move as-of index backward/forward; compare page and log; return to now | Projection changes coherently and returns to current state; no mutation occurs merely by scrubbing | CNT-11; event-sourcing differentiator | Return scrubber to latest |
| A8. Replay | Organizer | L | `/events/${SLUG}/replay`; scripted corpus installed and event explicitly marked for replay | Play at one speed; watch board in second tab; pause; skip to end | Rows/ratings/coverage arrive over SSE through real verbs; non-replay event refuses corpus | Filled-state/demo apparatus | Use disposable replay event only |

## B. CFP intake and form evolution

| Stop | Persona | Lane | Starting URL and prerequisite | Exact actions | Expected visible result and fact proof | Rubric / purpose | Cleanup |
|---|---|---:|---|---|---|---|---|
| B1. Builder anatomy | Organizer | L | `/events/${NEW_SLUG}/form` | Add short text, long text, single choice, multiple choice, URL, and private fields; rename one; move one | Live public preview matches controls; stable IDs do not change; private badge is visible only to committee; `form.updated` | CFP-01/06/09 | Retire disposable fields |
| B2. Retire/restore | Organizer | L | Same page; one field has answers | Retire with confirmation; verify public absence and historical answer readability; restore | No destructive delete; old submission snapshot stays readable; current public form hides then restores field; `form.updated` | Historical safety | Restore desired seed form |
| B3. Open/close | Organizer, then signed out | L | Dashboard and `/cfp/${NEW_SLUG}` | Close call, attempt public submission, reopen, set close date | Closed page says `The call for speakers has closed`; submit cannot append; reopen restores form; event facts persist | CFP-04 rule | Leave local call open |
| B4. Draft survival | Signed out | L | Open CFP with no account | Type title/abstract/profile; wait for draft save; refresh | `Picked up where you left off`; content survives; no submission exists until Submit | Strong predicted resilience | Complete or clear via normal flow |
| B5. Sessionize import | Signed out | L | CFP and a safe profile import source | Import profile; inspect bio/headshot/links; alter one value before submit | Imported profile is editable and submission uses final visible values | SPK profile reuse | Use fictional person |
| B6. Submit and snapshot | Signed out, Organizer | L | Completed public form | Submit; follow confirmation; open board/detail; then rename a form label | Exact title/abstract round-trip; detail still uses the submission-time snapshot; `submission.created` | CFP-05/06/09 | Keep as review fixture |
| B7. Closed-edit lock | Speaker | L | Existing speaker submission and CFP now closed | Open `/portal?edit=<id>` | Read-only branch says `The call closed <date> — editing is locked.`; direct update is rejected/no fact | CFP-16 rule | Reopen event or use separate fixture |
| B8. Submission cap | Same speaker | L | Demo cap configured deliberately | Submit up to limit, then one more | Last request returns a clear 422 with no partial person/submission facts; demo deployment cap is high enough not to break judge chain | Harness safety | Disposable fixture only |

## C. Committee, review, assignment, and decisions

| Stop | Persona | Lane | Starting URL and prerequisite | Exact actions | Expected visible result and fact proof | Rubric / purpose | Cleanup |
|---|---|---:|---|---|---|---|---|
| C1. Committee roster | Organizer | L | `/events/${NEW_SLUG}/committee` | Add a reviewer, open their person page, remove and re-add if safe | Roster and person/event membership agree; `member.added` / `member.removed`; removal never deletes person history | CFP-10 | End with intended roster |
| C2. Board work queues | Reviewer | L | `/events/${SLUG}/board` filled | Search by person/company; use coverage and top-rated sorts; filter status/track; open permalink | Dense one-page conversation, coverage bar, exact count, stable detail URL | ABS-08/10; flagship use | Clear filters |
| C3. Rating and comment | Two reviewers | L | Same submission in two sessions | Each reviewer rates and comments; refresh both | Named ratings/comments visible to both; one rating per reviewer updates through new `rating.set`; comments append `comment.added` | CFP-11; ABS-10 | Keep evidence |
| C4. Optional assignment | Organizer, Reviewer | L | Board detail; two members | Assign one reviewer; open their `/review`; unassign and reassign | `Assigned to you` contains exactly assigned work and progress counts; shared board remains open; `reviewer.assigned` / `reviewer.unassigned` | ABS-05 scoping | Leave useful assignment |
| C5. Recusal | Reviewer | L | Assigned submission | `Recuse myself`; inspect coverage; undo | Reviewer is removed from effective obligation/controls; `reviewer.recused`, then `reviewer.unrecused` | ABS-12 | Unrecuse unless real conflict |
| C6. Reviewer progress/nudge | Organizer | L | Board with one lagging reviewer | Expand `Reviewer progress`; select reviewer; `Draft nudges`; edit; `Record reviewed nudges` | No email is sent; history records only reviewed draft; `reviewer.nudge-recorded` | ABS-08/09; human gate | None |
| C7. Optional criteria | Organizer, Reviewer | L | Board with optional scorecard | Add Numeric/Dropdown/Free text criterion and Weight; reviewer saves value | `Named scorecard criteria`; value round-trips; `scorecard.criterion-added` and `scorecard.value-set` | ABS-03/04 | Retire rubric-only criterion if not wanted |
| C8. Optional review plan | Organizer, Reviewer | L | Board configuration | Create dated round; set reviewer pool/criteria; activate; toggle blind; inspect reviewer detail; disable blind | Optional/off-by-default wording; `review-round.*` and `review.blind-mode-set`; Organizer retains identity visibility; reviewer blind branch hides it | ABS-01/02/07 | Disable blind; retire disposable round |
| C9. Decision vocabulary | Organizer | L | Submission detail | Cycle only a disposable row through Pending, Accept Queue, Accepted, Waitlisted, Decline Queue, Declined, Withdrawn, Draft; set content `Approved`; toggle priority | Exact literals render; decision and content axes remain separate; `submission.status-changed`, `submission.content-status-changed`, `submission.priority-toggled` | CFP-12; CNT-12 | Restore intended status |
| C10. Scores CSV | Organizer | P-RO | `/events/${SLUG}/board/review-scores.csv` | Download/open CSV | Stable submission/reviewer identity, scores/comments/company/track represented; not HTML/error | ABS-13 | Delete local download if sensitive |

## D. Informing, outbox truth, and speaker operations

| Stop | Persona | Lane | Starting URL and prerequisite | Exact actions | Expected visible result and fact proof | Rubric / purpose | Cleanup |
|---|---|---:|---|---|---|---|---|
| D1. Inform one | Organizer | L | Accepted but uninformed row; `/inform` | Read resolved letter; press `Inform` | Speaker-visible status changes only now; `submission.notified`; `comms.rendered` without SMTP or `comms.sent` with provider | CFP-13/14/15 | Keep informed fixture |
| D2. Inform all | Organizer | L or X | Several same-status rows | Inspect every letter, then `Inform all N` | Per-recipient resolved content; exact truthful delivery mode; Already informed list gains rows | Bulk human approval | Test destinations only under X |
| D3. Comms audit | Organizer | P-RO | `/events/${SLUG}/comms` | Compare submission confirmation, decision, chase history | Columns When/To/Subject/Status; `sent`, `failed`, or `would send` matches reality; ICS indicator where applicable | CFP-08; side-effect evidence | None |
| D4. Roster/search | Organizer | L | `/events/${SLUG}/speakers` | Search name/email/org/session; filter Invited/Confirmed; manually add and edit fictional speaker | One canonical event row; changes stay event-local; `speaker.added-to-event`, `speaker.status-changed`, `speaker.details-updated` | SPK-01/02/04 | Keep or mark fixture clearly |
| D5. CSV preview/import | Organizer | L | Same page; prepared CSV | Paste/upload CSV; preview one valid, one invalid, one duplicate; import | Errors appear before write; no partial invalid rows; re-import is idempotent; imported row searchable | SPK-03 | Fictional addresses only |
| D6. Deliverables ledger | Organizer | P-RO | `/events/${SLUG}/deliverables` mixed tasks | Inspect Open/Overdue/Due today, due dates, timezone, required/status, last touch | “Who owes what” is answerable immediately; completed obligations are absent from open list; task facts agree | CNT-07; SPK-05/09/12 | None |
| D7. Manual touch | Organizer | L | One overdue obligation | Record Email/Text/Call follow-up note without provider send | Human touch appears with medium/time; `task.chase-recorded`; no `comms.sent` claim | Ethnographic core | None |
| D8. Batch chase | Organizer | L or X | Multiple speakers owe same item | Select same item; draft; edit From/To/Subject/Message; send only to test destination | Individual drafts and explicit `Human send gate`; rendered/failed never reset clock; accepted send does | CNT-08; SPK-13/14/16 | No real speaker without approval |
| D9. Portal invite | Organizer, Speaker | L or X | Submitted speaker on canonical roster | Press `Send portal invite`; inspect the resolved letter in Comms; open its one-time link as the speaker | Exactly one event-scoped invite is rendered or sent; the link lands in `/portal`; an invited-only person with no talk has no button; another event cannot issue the invite | SPK-06/07 | Test destination only under X |

## E. Speaker portal, profile, tasks, and files

| Stop | Persona | Lane | Starting URL and prerequisite | Exact actions | Expected visible result and fact proof | Rubric / purpose | Cleanup |
|---|---|---:|---|---|---|---|---|
| E1. Portal scoping | Speaker | P-RO | `/portal`, Amara has at least two submissions | Inspect only own talks; request organizer, board, other submission file, and other event URLs | Own portal works; organizer and foreign resources are 403/redirected; nothing written | SPK-07; CNT-03 | None |
| E2. Decision privacy | Speaker | L | One decided but uninformed submission | Compare before and after organizer informs | Before: `Under review`; after: Accepted/Waitlisted/Declined truthful copy; committee decision does not leak early | CFP-13 doctrine | Keep intended fixture |
| E3. Talk edit | Speaker | L | Editable submission and open call | `Edit this talk`; add sentinel text; save; organizer opens detail | Sentinel round-trips; `submission.answers-updated`; form snapshot labels remain unchanged | CFP-09 | Restore via another fact if needed |
| E4. Profile edit | Speaker | L | Portal profile form | Update bio/title/org/LinkedIn sentinel; save; organizer/public detail inspect | Self-owned profile round-trips; `person.profile-updated`; only public-safe fields publish | SPK-08 | Restore via normal save if desired |
| E5. Headshot upload | Speaker | L | Prepared PNG/JPEG | Upload; refresh portal/public speaker page | New image serves through `/headshots/<file-id>`; immutable version fact; wrong speaker cannot overwrite | SPK-08 | Fictional profile only |
| E6. Task completion | Speaker | L | Accepted + informed submission with open task | Open/check task, complete it, refresh organizer deliverables | Status becomes Complete; organizer ledger updates; `task.completed` | SPK-09 | Use disposable task |
| E7. File upload/version | Speaker | L | Open file-request task | Upload v1; upload exact bytes again; upload changed bytes as v2 | Exact retry is no-op; changed bytes append v2; latest is clear; task completes with upload | CNT-02/04 | Keep small files |
| E8. File dialogue | Speaker, Organizer | L | Existing file | Speaker comment; organizer reply; download from each role; probe foreign user | Both comments visible with identity/time; each owner downloads; foreign access denied; `file.comment-added` | CNT-05; CNT-03 | None |
| E9. ZIP archive | Organizer | P-RO | `/events/${SLUG}/files.zip` | Download and inspect filenames/contents | Valid ZIP contains the event’s latest file artifacts, not foreign-event files | CNT-14 | Delete sensitive local copy |

## F. Schedule, conflicts, publication, and public consistency

| Stop | Persona | Lane | Starting URL and prerequisite | Exact actions | Expected visible result and fact proof | Rubric / purpose | Cleanup |
|---|---|---:|---|---|---|---|---|
| F1. Rooms/blocks/tray | Organizer | L | `/events/${SLUG}/schedule` | Add room; add Lunch/TBD block; place accepted+informed talk; clear it | Partial states remain valid; `room.added`, `block.added`, `slot.assigned`, `slot.cleared` | AIA-01/02/03 | Remove disposable room/block through UI |
| F2. Conflict algebra | Organizer, signed out | L | Two talks share speaker or room/time | Create conflict; inspect warning; public agenda/export; resolve | Warning names speaker or room and both sides; conflicted session is loudly withheld; resolution immediately restores eligibility | AIA-04/05/06; public correctness | Resolve conflict |
| F3. Lock/version | Organizer | L | Draft schedule | Lock; attempt mutation; unlock | `Locked — <version>`; edits unavailable/refused while locked; facts `schedule.locked`, `schedule.unlocked` | Operational safety | Leave intended state |
| F4. Publish | Organizer | L | Conflict-free accepted+informed placements | Press exact `Publish`; open agenda | `Published ✓`; `agenda.published`; agenda uses event timezone | AIA-07 literal | Republish is append-only if exercised |
| F5. Public cross-surface consistency | Signed out | P-RO | Filled published event | Pick one session/speaker and compare agenda, sessions list/detail, speakers list/detail/gallery, JSON, and ICS | Same title, person identity, room, start/end, format, and track everywhere; stable IDs/UIDs | EMB-16; durable publishing | None |

## G. Public widgets, API, and exports

| Stop | Persona | Lane | Starting URL and prerequisite | Exact actions | Expected visible result | Rubric / purpose | Cleanup |
|---|---|---:|---|---|---|---|---|
| G1. Sessions list | Signed out | P-RO | `/agenda/${SLUG}/sessions` | Search exact title, then speaker name; apply Track; clear | Count says `Sessions 1 – N of N`; cards contain abstract/Show more/date/time/room/speaker/title/company/Format/Track | EMB-01/02/03 | Clear query |
| G2. Speakers | Signed out | P-RO | `/agenda/${SLUG}/speakers` | Search name; open detail; inspect sessions | Photo/fallback, name/title/company/bio/Show more; `Sessions (n)` with time/room and working links | EMB-04/05 | None |
| G3. Gallery | Signed out | P-RO | `/agenda/${SLUG}/gallery` | Scan populated grid and missing-photo fallback fixture | Every card renders; no broken image/empty page | EMB-12/13 | None |
| G4. Session detail | Signed out | P-RO | One `/agenda/${SLUG}/sessions/<id>` | Inspect tabs, complete time, room, description, format, track, speaker links | Literal `Session Details / Subsessions (0)` and complete anatomy | EMB-08/09 | None |
| G5. Raw exports | Signed out | P-RO | `/events/${SLUG}/exports/{sessions.json,speakers.json,calendar.ics}` and `/events/${SLUG}/llms.txt` | Download/parse; compare entity IDs and joins | Only published program; private answers absent; ICS stable UID; speaker/session joins carry IDs | EMB/API; doctrine 9 | Secure sensitive downloads |
| G6. Public API | Signed out | P-RO | `/api/v1/`, `/api/v1/events/${SLUG}`, docs, sessions/speakers/schedule/rooms | Open without key; send `If-None-Match` twice | Discovery/docs work; published-only data; second unchanged request may return 304 | API bonus | None |
| G7. Token widening | Organizer | L | Settings has named API key | Create key; copy once; query unpublished submissions; revoke; retry | Key appears once, stored list shows prefix only; token widens rows but never includes private fields; revoked token fails to widen; `api-key.created` / `api-key.revoked` | Security/integration | Revoke test key |
| G8. Embed builder | Organizer → signed-out integrator | L then P-RO | `/events/${SLUG}/embed`; event has published data | Pick Agenda/Sessions/Speakers/Gallery and iframe/Link/JSON/iCal; open generated public URL signed out | `Embed builder`, `Widget type`, `Copyable snippet`, and iframe `Working preview`; generated target works without login | EMB-15 | None |
| G9. Personal schedule | Attendee | L | `/agenda/${SLUG}/my`; at least two placed published sessions | Add one session, reload, open a second browser, download `/agenda/${SLUG}/my.ics`, then unstar | First browser shows `★ In My schedule`; second remains empty; ICS contains only selected title; `agenda.session-starred` then `agenda.session-unstarred` | EMB-10/11 | Unstar test selection |
| G10. Agent interface | Agent / operator | P-RO then L | `/events/${SLUG}/mcp`; `docs/agent-interface.md` | Run `python3 bin/agent_drive.py --base "$BASE"`; compare `clj -X:agent` local and remote `get_event`; signed organizer dry-runs `set_submission_status` before any confirmed call | MCP initializes and discovers 11 bounded tools; public read succeeds; anonymous private read is rejected; CLI/MCP envelopes and schemas match; dry-run appends zero facts | MCP/CLI bonus; bead `y7e` | Do not confirm a mutation unless the selected status change is intended |

## H. Settings, integrations, telemetry, and failure containment

| Stop | Persona | Lane | Starting URL and prerequisite | Exact actions | Expected visible result and fact proof | Rubric / purpose | Cleanup |
|---|---|---:|---|---|---|---|---|
| H1. Exports discovery | Organizer | P-RO | `/events/${SLUG}/exports` | Follow every export and public API reference | One discoverable handoff page; public URLs need no login; private-field promise is explicit | EMB-15 partial/API | None |
| H2. Webhook | Organizer | X | `/events/${SLUG}/settings`; controlled request-bin URL | Add typed webhook; cause harmless local fact; inspect receipt; remove | Sink failure never fails domain mutation; delivery visible; `sink.registered`, then `sink.removed` | Integration resilience | Remove endpoint |
| H3. Slack | Organizer | X | Controlled test channel webhook | Configure only selected event groups; Send test; deliberately use invalid URL locally | Valid test posts once; invalid sink reports failure without breaking app; private answers absent | Selective, default-off integration | Remove webhook |
| H4. Airtable | Organizer | X | Disposable base/table/token | Connect; create fictional submission/decision; inspect row URL; disconnect | One-way mirror only; no private fields; URL is absolute; domain log remains source; disconnect works | Bonus; bead `sjp` guards relative-URL bug | Disconnect and revoke token |
| H5. Mail transport | Organizer | X | Dedicated test inbox, SMTP/provider secret | Send one confirmation/decision/chase; force one provider failure | Success says `sent` and records `comms.sent`; failure says failed and records `comms.failed`; rendered is never called sent | Side-effect truth | Revoke test configuration if temporary |
| H6. Telemetry request pipe | Operator | O | Production telemetry DDL/grants installed | Record count; make N safe GETs across public/organizer pages; wait flush; re-count | Count grows by N-ish according to documented filtering; route/status/duration/person/session hash/event slug/allowlisted params only; no form bodies | Funnel/research program | Record meter in captain’s log |
| H7. Beacon | Signed out, Operator | O | Pages include beacon; Pub/Sub pipeline configured | Load pages and allow beacon; inspect downstream event | Privacy-safe page-use signal arrives; no secrets/form bodies | Funnel analytics | Follow retention policy |
| H8. Funnel analysis | Operator/REPL | O | Enough telemetry traffic | Run visited → started → submitted per event/day; separate agents/bots | Counts are sane and explainable; this is a consumer of telemetry, not a substitute for it | bead `2v2` | Save query/result, not raw private data |

## I. Cross-event Speaker CRM — organization-level operator surface

Run these stops in **L** with two fictional events owned by one organizer and a
third event owned only by another organizer. Use invented contacts only.

| Stop | Persona | Lane | Starting URL and prerequisite | Exact actions | Expected visible result and fact proof | Rubric / purpose | Cleanup |
|---|---|---:|---|---|---|---|---|
| I1. Directory | Organizer | L | `/people`; speaker records exist in two authorized events | Inspect metrics and contact rows | Contacts, organizations, repeat speakers, represented events, roles, talks, and event names are visible | CRM-01/12 | None |
| I2. Filters | Organizer | L | `/people` | Combine text, organization, role, event, and tag filters | Every result satisfies all active filters; clearing restores the tenant-scoped directory | CRM-02 | None |
| I3. Detail/history | Organizer | L | Follow `/people/:person-id` | Inspect talks, roles, events, and activity | Only authorized event history appears; stable canonical identity is reused | CRM-03 | None |
| I4. Notes/tags | Organizer | L | Contact detail | Add an internal note and tag; reload; remove tag | Note persists; tag add/remove are append-only `crm.*` facts | CRM-04 | Remove test tag through UI |
| I5. CSV preview/import | Organizer | L | `/people`; CSV has one existing email and one new fictional email | Preview, inspect errors/actions, then explicitly import into one event | Preview writes nothing; existing email is reused; exactly one new identity joins target event | CRM-05/06 | Keep fictional provenance or archive sandbox event |
| I6. Pipeline | Organizer | L | `/people` with invited/submitted/confirmed/withdrawn examples | Inspect pipeline counts and filter the cohort | Stages derive from canonical relationship and event facts; no second workflow exists | CRM-07 | None |
| I7. Saved segment | Organizer | L | Useful filters active | Name/save segment; reopen it; remove it | `crm.segment-saved`, then `crm.segment-removed`; filters round-trip | CRM-09 | Remove test segment |
| I8. Push into event | Organizer | L | Contact detail; second authorized event | Push person twice | First call reuses ordinary speaker-add verb; second is idempotent; one roster identity | CRM-10 | Sandbox event only |
| I9. Outreach | Organizer | L | `/people/outreach`; select fictional recipients | Resolve template, preview, edit, then choose **Record human-reviewed draft** | Personalization is visible; preview appends nothing; record appends `crm.outreach-drafted`; nothing sends and no `comms.sent` exists | CRM-11; ethnographic safety | None |
| I10. Tenancy wall | Foreign organizer | L | Copy a contact URL and target event ID from organizer one | GET foreign detail; try tag/note/push/import mutations | Detail is 404; mutations refuse with no foreign fact or identity leak | Cross-cutting authz | None |

## J. Negative and resilience matrix

Run this matrix in **L** except the read-only production tenancy checks.

| Probe | Expected outcome |
|---|---|
| Signed-out request to organizer page | Redirect to `/login`; no mutation |
| Speaker requests any organizer event page or POST | 403/redirect; no fact |
| Reviewer for event A requests event B organizer/API surface | 403/redirect; no event-B fact |
| Unknown email on demo login form | Neutral response; never echoes a magic token |
| Demo mode off, POST `/api/demo-login` | Behaves as not found |
| Closed CFP direct submit | Clear refusal/422; no `submission.created` |
| Post-close portal edit | Visible dated lock; no `submission.answers-updated` |
| Invalid or mixed speaker CSV | Preview errors; zero partial invalid facts; retry idempotent |
| Exact same file bytes uploaded twice | No new version; changed bytes create exactly one next version |
| Foreign speaker downloads/comments on another submission’s file | Denied; no blob leak and no comment fact |
| Unconfigured SMTP chase | `Rendered only; not marked contacted`; no reset of last-touch clock |
| Provider failure | `failed`; no false `sent`, and no task clock reset |
| Known schedule conflict | Loud warning; affected session absent from agenda/exports until resolved |
| Private CFP field through public HTML, JSON, API, Slack, Airtable, webhook | Never present, including with API key |
| Replay against an unmarked real event | Refused; no corpus facts appended |
| Archive an event | Reversible shelf state, never deletion |
| Eight board tabs plus two more | All initial resources load; hidden streams do not starve the browser HTTP/1.1 connection pool; returning to a tab repaints current authoritative state |
| SSE reconnect after hiding/showing tab | Safe, idempotent full-state repaint; no duplicate domain mutation |

## K. Planned competition surfaces that must not be claimed yet

These are explicit future acceptance stops. A judge tour should say “not built”
rather than silently substitute a nearby feature.

| Planned surface | Current truth / acceptance trigger |
|---|---|
| Embed builder | **Built and accepted:** organizer picker generates working iframe/link/JSON/iCal handoffs over stable public routes |
| Personal schedule | **Built and accepted:** signed anonymous selection facts, `My schedule`, isolated browser state, per-selection ICS |
| Auto-schedule suggestion | **Built as an isolated rubric-tail action:** explicit organizer click, conservative conflict-free fill, never moves placed work; blocking-sheet workflow remains primary |
| Organizer session/profile editing + per-object restore | **Built and accepted:** canonical session edit + exact-log-index history + restore-as-new-fact; speaker profile edit remains on the roster path |
| Cross-event Speaker CRM | **Built and accepted:** tenant-scoped directory, filters, detail/history, notes, tags, CSV identity reuse, derived pipeline, saved segments, push-to-event, metrics, and human-reviewed outreach; cold HTTP proof is 25/25 |
| Event-scoped MCP server and CLI | **Built and accepted:** one 11-command event-scoped registry powers MCP plus local/remote CLI; read-heavy, tenant-checked, and limited to one dry-run/explicit-confirm named mutation |
| Additional email/blob providers | Provider ports, recording fakes, Resend, Cloudflare REST, SMTP/AWS SES, local blob, and GCS adapters are built under bead `sessionize-sched-killer-973.1`; live credentials and external delivery remain operator-gated |
| Forge mirror / public trajectory | Submission logistics, not product behavior |
| Cloudflare migration | Explicitly skipped; prove speed instead |

---

# Competition-day concise rehearsal — 12 minutes

This is the path to record for the walkthrough video after the full tour is
green:

1. Signed out: landing → `/login`; show the three exact persona buttons.
2. Organizer: dashboard → Review Board; show named scores/comments, coverage,
   eight statuses, assignments, `Approved`, and scores CSV.
3. Organizer: Inform Speakers; show the full resolved letter and the separate
   informed truth; open Comms and point out honest status.
4. Organizer: Speaker deliverables; answer “who owes what?”; select same-item
   rows and show individualized human-gated drafts.
5. Organizer: Files; show request, v2 with v1 beneath, two-sided comments, ZIP.
6. Speaker: portal; show own-only scoping, editable talk/profile, tasks, upload,
   and the visible Accepted/Waitlisted truth only after informing.
7. Organizer: Schedule; show partial blocks/tray, conflict warning, lock, and
   literal `Publish`.
8. Signed out: public agenda → sessions search/Track → session detail → speaker
   detail/gallery.
9. Signed out: public JSON/ICS/API docs; say “no scraper, stable IDs, private
   fields never leave the committee.”
10. Organizer: Log and scrubber; end on append-only history and a current-state
    repaint.

The competition video should use the canonical deployed URL and filled AIE
event. Do not include the private production event, secrets, test inboxes,
personal data, or raw private research.

## Morning operator checklist

- [ ] Back up the production event log before any fixture wave.
- [ ] Verify persona tenancy using store/domain getters.
- [ ] Provision cookie-session secret and telemetry schema/grants.
- [ ] Enable demo personas and allowlist on the demo service only.
- [ ] Keep demo flag absent on `curtaincallcfp` or any non-demo service.
- [ ] Apply the AIE decision wave only through named domain verbs.
- [ ] Configure optional email/blob/integration secrets through the sanctioned
      runtime secret path.
- [ ] Deploy and record revision.
- [ ] Prove all three persona sessions survive an instance recycle.
- [ ] Run the cross-event authz matrix and verify no facts were appended.
- [ ] Verify every public page is filled and logged-out.
- [ ] Run telemetry request-count and funnel meters.
- [ ] Run `bin/e2e_drive.py` against an isolated JSONL server before deploy and
      the safe read-only subset against production after deploy.
- [ ] Run Ann’s uncoached rehearsal first, then a novice rehearsal.
- [ ] Pick one canonical submission URL; rehearse video and form from incognito.

## Measured Clojure source-versus-test inventory

Snapshot taken 2026-08-10 while this tour was written:

| Tree | Clojure files | Lines |
|---|---:|---:|
| `src/` | 108 | 22,418 |
| `test/` | 54 | 12,037 |

The test tree is **53.7% as many lines as the source tree** and has exactly half
as many Clojure files. Put differently, there are about **1.86 source lines per
test line**. The ratio excludes Python/browser drivers, shell probes, fixtures,
and docs, so it understates total verification investment.

Largest source namespaces at the snapshot:

| Lines | Namespace file |
|---:|---|
| 950 | `src/cfp_scheduler_killer/exports.clj` |
| 834 | `src/cfp_scheduler_killer/views/review.clj` |
| 731 | `src/cfp_scheduler_killer/store.clj` |
| 725 | `src/cfp_scheduler_killer/events.clj` |
| 655 | `src/cfp_scheduler_killer/sinks.clj` |
| 553 | `src/cfp_scheduler_killer/handlers/board.clj` |
| 518 | `src/cfp_scheduler_killer/views/integrations.clj` |
| 508 | `src/cfp_scheduler_killer/auth.clj` |
| 479 | `src/cfp_scheduler_killer/folds.clj` |
| 471 | `src/cfp_scheduler_killer/reviews.clj` |

Largest test namespaces:

| Lines | Test file |
|---:|---|
| 828 | `test/cfp_scheduler_killer/exports_test.clj` |
| 681 | `test/cfp_scheduler_killer/server_test.clj` |
| 654 | `test/cfp_scheduler_killer/sinks_test.clj` |
| 579 | `test/cfp_scheduler_killer/schedule_test.clj` |
| 540 | `test/cfp_scheduler_killer/events_test.clj` |
| 503 | `test/cfp_scheduler_killer/forms_test.clj` |
| 503 | `test/cfp_scheduler_killer/authz_event_scope_test.clj` |
| 482 | `test/cfp_scheduler_killer/board_test.clj` |
| 473 | `test/cfp_scheduler_killer/portal_test.clj` |
| 399 | `test/cfp_scheduler_killer/comms_test.clj` |

Re-measure immediately before submission because active feature lanes will move
these totals. This inventory is descriptive, not a coverage percentage; the
manual tour and automated assertions measure behavior, while line counts only
show the relative verification investment.
