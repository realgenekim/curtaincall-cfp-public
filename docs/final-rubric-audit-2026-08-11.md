# Final close-to-100 rubric and deployment-readiness audit

**Audit date:** 2026-08-11
**Authority:** `plans/2026-08-10-close-to-100-plan.md` and the 96 items
transcribed from `killmysaas-evals/specs/*.yaml` into
`docs/research/rubric-gap-analysis-2026-08-09.md`
**Current local proof:** 373 tests / 3,804 assertions / 0 failures in both
full-suite modes; fresh JSONL JVM and 263/263 HTTP checks; 192 characterized
routes (71 GET / 121 POST).

## Executive verdict

The original 34/100 implementation has become a nearly complete local clone.
The evidence-backed local ceiling is approximately:

| Area | Weight | Current local item weight | Projected contribution |
|---|---:|---:|---:|
| Call for Papers | 20 | 34 / 34 | 20.0 |
| Abstract Management | 20 | all applicable items | 20.0 |
| Speaker Management | 15 | 33 / 33 | 15.0 |
| Content Management | 15 | 31 / 31 | 15.0 |
| AI Agenda Builder | 10 | 18 / 18 | 10.0 |
| Public Widgets | 20 | 34 / 34 | 20.0 |
| **Required total** | **100** | | **100.0** |
| Speaker CRM extra credit | +10 | 18 / 19 | **≈+9.5** |
| **Local rubric ceiling** | **110** | | **≈109.5** |

This is a functional ceiling, not a competition score. The current deployed
judge service is stale. A read-only fetch on 2026-08-11 returned the old demo
buttons `Organizer (Gene, chair)`, `Reviewer (Ann)`, and `Speaker (Priya)`.
Current source and tests require `Organizer · swyx`, `Reviewer · Maya
Lindholm`, and `Speaker · Amara Devlin`. Until the operator deploys and proves
the current build, the evaluator may still withhold scoring for insufficient
authenticated coverage.

Status vocabulary below:

- **PROVEN** — direct current source plus focused/runtime acceptance.
- **PARTIAL** — useful implementation exists, but one literal requirement is
  absent.
- **UNKNOWN** — the authoritative local rubric copy omitted the requirement;
  no score is claimed.
- **N/A** — evaluator specification says neutral when AI review is not claimed.

## Call for Papers — projected 20 / 20

| ID | W | Status | Current evidence |
|---|---:|---|---|
| CFP-01 | 3 | PROVEN | Six-shape data-driven builder, required/private flags, live real-renderer preview, 422 field validation; `forms_test.clj`. |
| CFP-02 | 1 | PROVEN | Acyclic `:show-when`, hidden-required law, chained visibility, atomic SSE repaint; `forms_test.clj`, `submissions_test.clj`, four-gap receipt. |
| CFP-03 | 3 | PROVEN | Public no-account `/cfp/:slug`, literal Track select, format and speaker fields. |
| CFP-04 | 2 | PROVEN | Open/close/reopen state enforced by the submission domain, not only hidden controls; e2e §2c. |
| CFP-05 | 3 | PROVEN | Submission confirmation plus magic-link speaker portal; portal invite acceptance. |
| CFP-06 | 3 | PROVEN | Submitted answers round-trip to list, board, and detail; e2e §5b. |
| CFP-07 | 1 | PROVEN | Per-viewer automatic draft stash, visible `Editing a saved draft`, explicit `Reset saved data`, refresh restore, and cross-viewer isolation; focused and cold HTTP proof under `u9p`. |
| CFP-08 | 1 | PROVEN locally | Confirmation is rendered/recorded and provider ports support SMTP, Resend, SES, and Cloudflare. Live credentials remain operator-gated. |
| CFP-09 | 2 | PROVEN | Speaker edit validates against the immutable per-submission form snapshot. |
| CFP-10 | 2 | PROVEN | Reviewer-only queue/rail distinct from chair controls; default-deny role and event scope. |
| CFP-11 | 2 | PROVEN | Multiple named reviewers rate/comment and see the shared conversation. |
| CFP-12 | 3 | PROVEN | Eight first-class decision statuses, typed refusals, append-only facts. |
| CFP-13 | 2 | PROVEN | Speaker portal shows the truthful informed outcome while decision and notification remain separate. |
| CFP-14 | 2 | PROVEN locally | Individual/bulk resolved previews and truthful sent/rendered/failed facts; provider acceptance controls the sent claim. |
| CFP-15 | 2 | PROVEN | Accepted/informed sessions enter scheduling and explicit Publish controls the public handoff. |
| CFP-16 | 2 | PROVEN | Closed CFP renders the portal read-only and refuses direct answer POSTs; `portal_test.clj`. |

## Abstract Management — projected 20 / 20

| ID | W | Status | Current evidence |
|---|---:|---|---|
| ABS-01 | 3 | PROVEN | Optional named review rounds with dates and lifecycle facts; `7wy.4`. |
| ABS-02 | 2 | PROVEN | Per-round reviewer pools and event-scoped authorization. |
| ABS-03 | 3 | PROVEN | Numeric, dropdown, and free-text criteria configured through visible controls. |
| ABS-04 | 1 | PROVEN | Criterion weights round-trip and affect aggregate algebra; named scorecard live drive. |
| ABS-05 | 3 | PROVEN | Optional per-submission assignments plus Assigned-to-you queue; chairs retain global visibility. |
| ABS-06 | 2 | PROVEN | Deterministic capped and track-filtered preview/confirm distribution; 15/153/0 focused proof. |
| ABS-07 | 2 | PROVEN | Optional blind-review mode hides author identity only in reviewer surfaces; chair view remains complete. |
| ABS-08 | 2 | PROVEN | Per-reviewer assigned/done/gap progress. |
| ABS-09 | 1 | PROVEN | Selected laggard nudges are resolved, reviewed, and recorded without autonomous sending. |
| ABS-10 | 3 | PROVEN | Weighted aggregate, count, comments, and clickable ascending/descending column sorting. |
| ABS-11 | 2 | PROVEN | Repeatable speakers with explicit role labels survive parse, fact, fold, reload, and rendering; 5/109/0 focused proof. |
| ABS-12 | 1 | PROVEN | Reversible conflict recusal changes the applicable assignment/coverage denominator. |
| ABS-13 | 2 | PROVEN | Board-linked review-scores CSV with stable identities and review data. |
| ABS-14 | 1 | N/A | No AI review claim; the evaluator specification marks this neutral. |

## Speaker Management — projected 15 / 15

| ID | W | Status | Current evidence |
|---|---:|---|---|
| SPK-01 | 3 | PROVEN | Searchable/filterable canonical event speaker roster. |
| SPK-02 | 3 | PROVEN | Organizer add/edit with global identity plus event-local participation overlay. |
| SPK-03 | 2 | PROVEN | Multipart/pasted CSV preview, row errors, email reuse, and idempotent confirm. |
| SPK-04 | 2 | PROVEN | Literal Invited/Confirmed status and filtering. |
| SPK-05 | 2 | PROVEN | Organizer-created typed tasks with instructions, required flag, due date, and speaker completion. |
| SPK-06 | 2 | PROVEN locally | Explicit truthful portal invite action, reusable one-time link, communication record. |
| SPK-07 | 3 | PROVEN locally | Speaker portal is identity- and event-scoped; escalation attempts fail closed. Deployed persona acceptance is pending. |
| SPK-08 | 3 | PROVEN | Profile editing plus immutable versioned headshot upload. |
| SPK-09 | 2 | PROVEN | Task rows show due date/timezone, required state, completion chip, instructions, and action. |
| SPK-10 | 2 | PROVEN | Typed versioned files list metadata and authenticated download. |
| SPK-11 | 2 | PROVEN | The informed accepted speaker portal projects the assigned day, exact start/end, and room from the canonical schedule; focused route/view proof under `u9p`. |
| SPK-12 | 2 | PROVEN | Deliverables ledger shows per-speaker/per-task completion and overdue state. |
| SPK-13 | 2 | PROVEN | Selected-group compose and human-reviewed send gate. |
| SPK-14 | 1 | PROVEN | Tokenized subject/body with fully resolved recipient previews. |
| SPK-15 | 1 | PROVEN | Event-scoped custom person fields with required validation, reload equality, and tenant refusal. |
| SPK-16 | 1 | PROVEN | Discoverable `Automated reminder schedule` config persists as a fact, defaults off, selects due obligations for organizer review, and never bypasses the human send gate; focused fold/reload/no-delivery proof under `u9p`. |

## Content Management — projected 15 / 15

| ID | W | Status | Current evidence |
|---|---:|---|---|
| CNT-01 | 3 | PROVEN | Organizer file-request task with kind, instructions, and due date. |
| CNT-02 | 3 | PROVEN | Multipart typed upload attached to the session/task. |
| CNT-03 | 3 | PROVEN | Speaker ownership plus event scope enforced in auth gate and handler. |
| CNT-04 | 2 | PROVEN | Immutable v1/v2/... history and latest projection; identical retry is a no-op. |
| CNT-05 | 2 | PROVEN | One two-sided append-only file conversation visible to both roles. |
| CNT-06 | 1 | PROVEN | Visible accepted-format and 25 MB constraints with server enforcement. |
| CNT-07 | 3 | PROVEN | Organizer deliverables dashboard answers who owes what. |
| CNT-08 | 2 | PROVEN | Bulk selected reminder preview/send with truthful effect outcomes. |
| CNT-09 | 2 | PROVEN | Chair-only session title/abstract editor using the canonical snapshot-aware verb. |
| CNT-10 | 2 | PROVEN | Organizer speaker profile/event-detail editor. |
| CNT-11 | 2 | PROVEN | Per-object exact-log-index history and restore as a new fact. |
| CNT-12 | 3 | PROVEN | Literal Draft / In review / Approved content axis plus literal Publish handoff. |
| CNT-13 | 1 | PROVEN | Central event file library over canonical aggregates. |
| CNT-14 | 2 | PROVEN | Latest files ZIP export. |

## AI Agenda Builder — projected 10 / 10

| ID | W | Status | Current evidence |
|---|---:|---|---|
| AIA-01 | 3 | PROVEN | Blocking-sheet grid, day columns, time gutter, rooms, tray, and blocks. |
| AIA-02 | 2 | PROVEN | Room CRUD is first-class; schedule-side `Track management` adds, renames, and retires the canonical CFP Track options through ordinary event-sourced form updates, with locked/duplicate/last-track guards. |
| AIA-03 | 3 | PROVEN | Placement appends `slot.assigned` and survives reload. |
| AIA-04 | 3 | PROVEN | Speaker conflicts name the person and both placements. |
| AIA-05 | 2 | PROVEN | Room double-booking is explicitly named. |
| AIA-06 | 2 | PROVEN | Pure recomputation clears resolved conflicts while placements persist. |
| AIA-07 | 2 | PROVEN | Literal Publish action appends a handoff fact and renders Published. |
| AIA-08 | 1 | PROVEN | Conservative Suggest schedule fills only legal unplaced sessions through the ordinary placement verb. |

## Public Widgets — projected 20 / 20

| ID | W | Status | Current evidence |
|---|---:|---|---|
| EMB-01 | 3 | PROVEN | Public sessions list with count, card anatomy, description, format, track, room, and speakers. |
| EMB-02 | 2 | PROVEN | Session/itinerary search matches titles and speakers; speaker search stays person-scoped. |
| EMB-03 | 2 | PROVEN | Track facets. |
| EMB-04 | 3 | PROVEN | Public speaker directory with search and photo fallback. |
| EMB-05 | 2 | PROVEN | Speaker detail with sessions. |
| EMB-06 | 3 | PROVEN | Public day/time/room agenda. |
| EMB-07 | 2 | PROVEN | Day switching changes the projected agenda. |
| EMB-08 | 2 | PROVEN | Session detail includes full time/room, Session Details, and Subsessions (0). |
| EMB-09 | 2 | PROVEN | Itinerary cards carry required title/speaker/time/room/track anatomy. |
| EMB-10 | 1 | PROVEN | Anonymous signed-viewer star/unstar and My schedule. |
| EMB-11 | 1 | PROVEN | Per-selection personal iCalendar download. |
| EMB-12 | 2 | PROVEN | Public speaker gallery grid. |
| EMB-13 | 1 | PROVEN | Gallery cards reach speaker detail. |
| EMB-14 | 3 | PROVEN locally | Every widget surface is logged-out/public; production filled-state still needs the decision wave. |
| EMB-15 | 2 | PROVEN | Organizer embed builder emits working iframe/link/JSON/iCal handoffs and preview. |
| EMB-16 | 3 | PROVEN | Cross-surface tests project the same canonical sessions/speakers/schedule. |

## Speaker CRM — projected +9.5 / +10

| ID | W | Status | Current evidence |
|---|---:|---|---|
| CRM-01 | 3 | PROVEN | Tenant-scoped cross-event `/people` directory. |
| CRM-02 | 2 | PROVEN | Composable text, organization, role, event, and tag filters. |
| CRM-03 | 2 | PROVEN | Person detail with authorized talks/events/roles/activity and internal notes. |
| CRM-04 | 1 | PROVEN | Append-only tags and notes. |
| CRM-05 | 2 | PROVEN | CSV preview/import into one authorized target event. |
| CRM-06 | 1 | PROVEN | Case-insensitive email identity reuse prevents duplicates. |
| CRM-07 | 2 | PROVEN | Derived Relationship → Invited → Submitted → Confirmed/Withdrawn pipeline. |
| CRM-08 | 1 | UNKNOWN | The local rubric authority contains no requirement text. Bead `czd` requires recovery from the exact evaluator spec before any claim. |
| CRM-09 | 1 | PROVEN | Named saved/removable segments. |
| CRM-10 | 2 | PROVEN | Idempotent push of an existing person into an authorized event. |
| CRM-11 | 1 | PROVEN | Multi-recipient resolved outreach composer, human-reviewed record, never auto-send. |
| CRM-12 | 1 | PROVEN | Tenant-scoped contact/organization/repeat-speaker/event dashboard metrics. |

## Brief bonuses and non-rubric differentiators

| Capability | Code status | External/operator status |
|---|---|---|
| Airtable one-way sink | Complete, absolute URLs and event-sourced integration | Base/token and live mirror proof pending |
| Resend email | Complete provider adapter and normalized contract | Secret, sender/domain, and real delivery proof pending |
| Cloudflare Email / AWS SES / SMTP | Complete interchangeable adapters | Chosen provider configuration pending |
| `/api/v1`, docs, keys, webhooks | Complete and cold-driven | Deploy current build |
| Event-scoped MCP + CLI | Complete, 11 tools, parity and agent drive green | External client/live config optional |
| Forge mirror | No product code needed | Privacy-fenced public repo and mirror pending under `crf` |
| Public trajectory artifacts | Plans/logs exist | Include only approved artifacts in allowlist repo |
| Cloudflare hosting migration | Intentionally skipped | Speed proof substitutes; no migration planned |
| Telemetry/beacon/research analysis | Code and local tests complete | DDL, grants, retention, flag, and multi-instance proof pending |

## Exact remaining work

### Agent-executable score tail

1. **`sessionize-sched-killer-czd`** — recover authoritative CRM-08 and prove or
   implement it.

The required local rubric ceiling is now 100/100. Closing `czd` can raise the
extra-credit CRM ceiling from approximately 9.5/10 to 10/10.

### Morning operator — score visibility and real effects

1. Deploy the current build to the one judge service. The live login page is
   presently stale.
2. Provision the stable session-cookie key and exact demo flags/allowlists;
   keep every non-demo service free of persona controls.
3. Seed/verify swyx, Maya, and Amara as AIE-only identities through domain
   verbs. Add the required Amara submission and any fixture identities.
4. Set the demo submission cap.
5. Stage the AIE decision wave through domain verbs so public widgets are
   filled: accepted → informed → scheduled, plus waitlisted and declined rows.
6. Configure a durable GCS upload bucket and one real email provider; prove
   upload across recycle and one provider-accepted message.
7. Apply telemetry DDL/grants/retention, enable collection, and prove N requests
   produce N durable rows without blocking requests.
8. Incognito acceptance: exact three persona labels, 303 destinations, magic
   link echo allowlist, Maya cross-event refusal, session survival across
   service update, and eight browser tabs without pool starvation.
9. Verify every public/widget/API route against the selected deployed URL.
10. Choose the single service URL and retire the redundant service (`3as`).

### Gene-only submission lane

1. Run two uncoached rehearsals, Ann first (`nuk`), and fix every P0 finding.
2. Privacy-review and publish the allowlist repository, then mirror to Forge
   (`crf`). Never publish this working repository.
3. Record the walkthrough video against the deployed service.
4. Submit the form with the one selected URL and persona instructions.
5. Ask swyx for the offered calibration run and apply only evidence-backed P0s.

## Final claim discipline

The codebase is not the blocker now. The dominant risk is that excellent local
coverage is invisible behind a stale deployed login and empty production
fixtures. Do not describe the competition entry as “100%” until `czd`, the
morning operator checklist, and Gene's submission lane are all closed with
current evidence.
