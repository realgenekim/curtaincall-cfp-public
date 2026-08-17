# Rubric coverage versus actual usage

**Last updated:** 2026-08-10
**Question:** Of everything already built and everything in the
close-to-100 plan, what will Gene, Ann, the program committee, speakers, and
attendees actually use?

## Executive answer

Roughly **60–65%** of the planned product surface is directly supported by the
ethnography and likely to be used repeatedly. Another **~20%** becomes real if
we reshape it around observed work. The remaining **15–20%** is primarily
competition access, literal rubric coverage, or bonus theater.

That does not mean the rubric-only work is valueless before the deadline. It
means we should isolate it, timebox it, and never let it distort the product's
center of gravity.

The proven product spine is:

1. dense, collaborative review;
2. a blocking-sheet scheduler;
3. decision versus informed truth;
4. the speaker chase ledger — who owes what, how late, and last contact;
5. human-assisted communication;
6. files and versions attached to the speaker/session/obligation;
7. reusable speaker profiles and canonical public publishing; and
8. append-only history that survives vendors and operator mistakes.

The largest contradictions between the rubric and observed use are formal
review rounds, blind review, autonomous reminders, greedy auto-scheduling,
personal attendee schedules, generic CRM breadth, and several agent/platform
bonuses.

## How to read the verdicts

| Verdict | Meaning |
|---|---|
| **Proven** | Direct testimony, repeated longitudinal behavior, or the predecessor workflow was repeatedly used. |
| **Strong predicted** | A close translation of work repeatedly done today in Sessionize, Sched, Sheets, email, Slack, Basecamp, or Dropbox. |
| **Hypothesis** | Plausible, but the research does not yet show target users reaching for it. |
| **Rubric-only** | Mainly unlocks the evaluator, a literal fixture, demo state, or a bonus. |
| **Do not build** | Contradicts observed practice or was explicitly cut. If score forces it, isolate a minimal optional path last. |
| **Engineering-proven** | Not a user feature, but has already made delivery or reliability materially safer/faster. |

Primary evidence: [ethnographic study](research/ethnographic-study.md),
[team-planning ethnography](research/team-planning-ethnography.md),
[speaker-wrangling ethnography](research/speaker-wrangling-ethnography.md),
[Ann interview](research/ann-interview-2026-08-08.md),
[rubric gap analysis](research/rubric-gap-analysis-2026-08-09.md), and the
[close-to-100 plan](../plans/2026-08-10-close-to-100-plan.md).

## Stage 0 — foundations and the operating model: built

| Capability | Competition purpose | Evidence of actual use | Verdict | Guidance |
|---|---|---|---|---|
| Create, edit, list, archive, and restore events | Foundational setup/scoping | Overlapping annual events are real; BusyConf's disappearance made durable history explicit pain | **Proven** | Keep archive/restore; never add deletion |
| Multi-event tenancy and default-deny authorization | Scoping and judge safety | Europe/Vegas overlap; annual access reprovisioning is real | **Proven** | Permanent invariant |
| Working-event sidebar and setup resume | Evaluator/operator navigation | Low-activation operator flow is important, but needs Ann rehearsal | **Strong predicted** | Keep; measure uncoached completion |
| CFP setup wizard/checklist | Fast setup rubric | “CFP in ten minutes” is a research-derived activation target | **Strong predicted** | Keep simple |
| Mission-control dashboard | Dashboard points | Operators ask “what remains?” repeatedly | **Strong predicted** | Make obligations/coverage its center, not ornamental charts |
| Manual capture of emailed/DM submissions | Operational completeness | Real 2026 proposals arrived through Basecamp, DM, and email | **Proven** | Elevate; more authentic than several rubric features |
| Append-only event log and restore-as-fact doctrine | Audit/history | Accidental accept/reject and tool/reality drift recur | **Proven** | Core differentiator |
| As-of time travel/live scrubber | History/demo | Historical safety is useful; daily scrubber use is unobserved | **Hypothesis** | Keep stable; do not over-polish |
| Replay engine and staged demo clock | Filled-state trajectory | Excellent demo/research apparatus, not operator work | **Rubric-only** | Keep stable only |
| Cookie sessions and recycle-safe auth | Hour-long evaluator reliability | Everyone needs login to survive a process recycle | **Proven reliability** | Must ship |
| Demo persona buttons and allowlisted magic-link echo | Unlocks most evaluator paths | No normal organizer asks for impersonation | **Rubric-only** | Strict demo-only/allowlist gate |
| AIE-only persona tenancy | Prevents cross-event leakage | Per-event silos are real; fixture identities are eval-shaped | **Proven safety** | Preserve negative tests |
| Server/store/views decomposition and dependency guards | Safe parallel delivery | Measurably reduced collisions, reload blast radius, and agent context | **Engineering-proven** | Preserve dependency direction, not just file counts |
| Algebraic pure domain decisions + append-only shells | Fast/safe implementation | Immediately caught storage-shape, casing, retry, and event-isolation bugs | **Engineering-proven** | Default shape for new mutations |
| Hidden-tab SSE hardening and multi-tab acceptance | Browser reliability | Connection starvation was directly reproduced across apps | **Engineering-proven** | Keep in shared helpers |

## Stage 1 — CFP intake: built

| Capability | Competition purpose | Evidence of actual use | Verdict | Guidance |
|---|---|---|---|---|
| Data-driven CFP form builder with six answer shapes | CFP-01 | Every incumbent uses CFP forms; swyx calls this core | **Proven** | Keep central |
| Live “what speakers see” preview | Form usability | Organizers need launch confidence | **Strong predicted** | Validate with Ann |
| Stable field IDs across rename | Round-trip correctness | Prevents silent corruption/re-keying | **Strong predicted** | Permanent invariant |
| Per-submission form snapshots | CFP-09/history | Live forms change; old answers must remain interpretable | **Strong predicted** | Preserve |
| Retire/restore fields, never delete | Safety/history | Aligns with durable audit and prevents data loss | **Strong predicted** | Keep |
| Public CFP and no-account submission flow | CFP-03/05/06 | Used for a decade | **Proven** | Essential |
| CFP open/close, secret extension, reopen | CFP-04 | Annual ritual | **Proven** | Make extensions explicit |
| Post-close edit lock | CFP-16 literal | swyx says they rarely lock accepted speakers from editing | **Rubric-only** | Configurable; real-use default should remain permissive |
| Per-person submission cap | Harness/brief protection | Little ethnographic evidence beyond spam/unserious submissions | **Hypothesis** | Configurable; do not foreground |
| Track question and facets | Filtering fixture | Track assignment/filtering is recurring PC work | **Proven** | Keep first-class |
| Multi-speaker data shape | ABS-11 | Co-speaker recruitment is among the densest chase work | **Proven need; partial UI** | Finish add-another + role/condition flow |
| Sessionize profile import | Speaker onboarding | Ann explicitly values reusable profiles; it removes bio/headshot chasing | **Proven** | Deepen carefully |
| Per-viewer draft stash | Resilience | Long form content must not disappear | **Strong predicted** | Keep |
| Confirmation page/message and communication record | CFP-08 | Speakers repeatedly ask whether material arrived | **Proven need** | Actual delivery configuration matters |
| Private fields excluded from public/export surfaces | Security | A leak would be disqualifying | **Proven safety** | Permanent invariant |

## Stage 2 — collaborative review and decisions: built

| Capability | Competition purpose | Evidence of actual use | Verdict | Guidance |
|---|---|---|---|---|
| Dense one-page review board | CFP/ABS core | Predecessor Fulcro view was repeatedly requested and called a game changer | **Proven** | Flagship surface |
| Person-first rows with company/bio/headshot/social context | Review quality | PC explicitly uses identity, seniority, company, and history | **Proven** | Never blind by default |
| Name/company/work-queue search and sorts | ABS usability | Actual review queues and company scans | **Proven** | Keep |
| Named ratings/comments inline | ABS-10 | Deliberation is peer conversation, not an anonymous aggregate | **Proven** | Never collapse into one number |
| Star-rating upsert with immutable fact history | Review CRUD | Stable ten-year workflow | **Proven** | Default scorecard |
| Coverage bars/two-review target | ABS-08 | Recurring committee ritual | **Proven** | Drive chase from this |
| Reviewer summary and reviewer-only queue | CFP-10/ABS progress | Sessionize's flow confuses new PC members; progress/calibration is requested | **Proven** | Keep as projection of the same board |
| Status/track filtering | Review triage | Repeated committee behavior | **Proven** | Keep |
| Eight first-class submission statuses including Waitlisted | CFP-12 | Accept queue/waitlist drives weekly calls | **Proven** | Keep actual committee language |
| Priority flag | Triage | Committees flag talks to lobby/rescue | **Proven** | Present as “bring to call” when useful |
| Submission permalink | Committee discourse | Atomic unit of Slack/Basecamp discussion | **Proven** | Essential |
| Committee roster and scoped roles | CFP-10 | Annual access work is real | **Proven** | Keep; role semantics matter |
| Review-scores CSV | ABS-13 | Sheets export/scan/share is repeated practice | **Proven** | Include stable IDs, people, comments, company, track |
| Optional assignments and “Assigned to you” queue | ABS-05 | Track division and explicit review targets are observed; AIE often rates everything | **Proven, optional** | Default `:all`; chairs retain global view |
| Per-reviewer progress and drafted nudge | ABS-08/09 | Reviewer chasing is recurring and deadline-driven | **Proven** | Human drafts/sends; no auto-fire |
| Optional criterion scorecards/weights | ABS-03/04 | No evidence PC wants per-criterion entry; risks slowing review | **Hypothesis/rubric-shaped** | Stars+comment default; criteria optional |
| Formal review rounds and per-round pools | ABS-01/02 | swyx explicitly struck formal rounds | **Do not build** | Only a minimal optional evaluator path if required for score |
| Blind-review toggle | ABS-07 | Contradicts actual use of speaker/company/history | **Do not build** | Never default; isolate if score forces it |
| Conflict recusal | ABS-12 | Normal governance need, but absent from corpus | **Hypothesis** | Cheap/optional after proven queues |
| Content state Draft/In review/Approved | CNT-12 | Publishing gate is real; exact second axis is rubric-shaped | **Strong predicted need** | Keep distinct from acceptance/notified truth |
| Decision separate from informed/notified | CFP-13/14/15 | One of the strongest repeated failure modes | **Proven** | Core doctrine |
| Individual/bulk inform with resolved previews | CFP-14/SPK-14 | Mail merge happens every cycle; Ann batches then personally sweeps | **Proven** | Human approval remains load-bearing |

## Stage 3 — speaker operations: built through the current milestone

| Capability | Competition purpose | Evidence of actual use | Verdict | Guidance |
|---|---|---|---|---|
| Magic-link speaker portal and speaker-scoped auth | SPK-07 | Appropriate low-friction access; security is non-negotiable | **Strong predicted** | Keep |
| Speaker status with the inform gate | CFP-13 | Speakers repeatedly need truthful status | **Proven** | Preserve notified-state honesty |
| Post-submission talk editing | CFP-09 | swyx says accepted speakers do edit | **Proven** | Default editable |
| Self-owned profile edits, Sessionize reuse, and versioned headshot upload | SPK-08 | Ann praises profile self-service | **Proven** | Keep import and upload as complementary paths |
| Derived obligations ledger: who owes what, due, overdue, last touch | CNT-07/SPK-09/12 | “Who owes what?” is the core speaker-wrangling job | **Proven** | Highest-value speaker-ops surface |
| Event-relative due-date algebra | Task rubric | Event moves must automatically move chase deadlines | **Strong predicted** | Keep derived; never copy calendar dates into every task |
| Human follow-up facts and idempotent retries | Chase history | Ann personally escalates; delivery history matters | **Proven** | Record human touch; never pretend it sent itself |
| Canonical event speaker roster | SPK-01 | Roster/search is repeatedly needed across 6+ systems | **Proven** | One row per person, joined to talks/tasks/files |
| Manual speaker add/edit with event-local overlay | SPK-02 | Invited speakers bypass CFP; one event must not rewrite another | **Proven** | Preserve person/submission/participation separation |
| Invited/Confirmed status | SPK-04 | Confirmation is the #2 chased artifact | **Proven** | Expand later with employer/announce/unreachable/withdrawn states |
| Idempotent speakers.csv preview/import | SPK-03 | Imports and repeated re-keying are chronic | **Proven, episodic** | Preview/errors/provenance; never destructive merge |
| Literal task list and speaker completion | SPK-05/09 | Onboarding work is large and recurring | **Proven** | Continue toward concrete obligation types |
| Portal invite action | SPK-06/07 | Multiple account/invite steps are real; platform notices can hit spam | **Strong predicted; built** | Human-triggered, event-scoped, visible delivery record; validate resend need |
| Employer approval / announce permission states | Operational depth | Directly observed and high consequence | **Proven; not built** | Concrete fields before generic custom-field builder |

## Stage 4 — schedule, publication, and integrations: mostly built

| Capability | Competition purpose | Evidence of actual use | Verdict | Guidance |
|---|---|---|---|---|
| Blocking-sheet schedule grid | AIA-01 | The real scheduler for a decade; Ann asks for this interaction | **Proven** | Second flagship |
| Rooms, placements, blocks, tray | AIA-02/03 | Concrete schedule inventory/work | **Proven** | Keep |
| Speaker/room/bounds conflicts | AIA-04/05/06 | Travel and late-change conflicts recur | **Proven** | Advisory, not coercive |
| Lock/unlock/version | Operational safety | “Can we lock the schedule?” is explicit | **Proven** | Keep |
| Explicit Publish fact | AIA-07 | Agenda release is a real milestone | **Proven** | Keep |
| Public agenda/session/speaker list/detail/gallery | EMB | Public program and profiles are used; gallery was optional | **Proven core; gallery rubric-shaped** | Verify filled-state quality; do not over-polish gallery |
| Public search/track facets | EMB-02/03 | Plausible attendee discovery; PC filtering is more strongly proven | **Strong predicted** | Keep thin |
| JSON speakers/sessions + ICS exports | EMB/API | Re-keyed into Sched, sales site, slides, scripts, posts | **Proven** | Treat as “publish everywhere” seam |
| Embed builder/stable iframe/link | EMB-15 | Could eliminate sales-site re-keying | **Proven need; built** | Keep the simple stable handoff; do not grow a configurator without evidence |
| Personal attendee schedule/per-selection ICS | EMB-10/11 | Outside observed organizer/speaker job | **Rubric-only; built and isolated** | Keep small; do not let attendee scope displace operations |
| Greedy “Suggest schedule” | AIA-08 | Contradicts requested blocking-sheet control/constraints | **Rubric-tail; conservatively built** | Keep explicit and subordinate; invest next in duplicate-day, availability, inventory, conflict help |
| Airtable one-way sink | Bonus | AIE uses Airtable read-mostly; ITRev corpus does not | **Strong predicted for AIE** | Configure/verify; never two-way source of truth |
| SMTP + ICS mail, communication history | Side-effect points | Delivery is real; platform email distrust is also real | **Proven need** | Human from/reply-to and honest outbox |
| Selective Slack sink | Integration | Slack is central, but notification bot noise was hated | **Hypothesis with negative evidence** | Default off; actionable milestones only |
| API v1/docs/keys/webhooks | Bonus/integration | Decade of hand-written glue and re-keying | **Strong predicted** | Stable read-first API; mutation later |
| GCS snapshots/restore and PostgreSQL event backend | Production durability | Vendor/data loss is real; multi-instance durability necessary | **Proven need** | Preserve and cold-boot test |
| Telemetry queue, beacon, funnel/session analysis | Funnel/research | Gene explicitly wants the ethnographic research loop | **Proven internal use** | Privacy-safe; use evidence to arbitrate hypotheses |

## Stage 5 — files and versions: built

| Capability | Competition purpose | Evidence of actual use | Verdict | Guidance |
|---|---|---|---|---|
| File-request obligation with instructions/due date | CNT-01 | Slides are the #1 chased artifact; median T-8 | **Proven** | Built as a task attached to one session; explicit due date folds back to domain time |
| Multipart upload and typed file | CNT-02/06, SPK-08/10 | Ann is currently a human file-transfer protocol | **Proven** | Built with explicit PDF/PowerPoint/Keynote/PNG/JPEG and 25 MB constraints |
| Immutable versions/history/latest flag | CNT-04 | Revised-deck chasing is a second full cycle | **Proven** | Built; identical retries are no-ops and changed bytes append v2, v3, … |
| Two-sided file comments | CNT-05 | Ann relays Gene's feedback and chases revisions | **Strong predicted** | Built as one thread visible to organizer and speaker; validate that it replaces relay email |
| Central file library | CNT-13 | Folders lack speaker linkage and fail across handoffs | **Proven** | Built as a projection over canonical event/session/file records |
| ZIP export | CNT-14 | Archive/handoff plausible, little direct evidence | **Hypothesis** | Built cheaply after the core flow; keep only if operators actually use it |
| Headshot upload | SPK-08 | Profile/headshot ease is a praised incumbent strength | **Strong predicted** | Built with immutable versions and a public image route; profile import remains equally important |

## Built stage A — human-assisted chase (verified 2026-08-10)

| Planned capability | Competition purpose | Evidence of actual use | Verdict | Recommendation |
|---|---|---|---|---|
| Select obligations and draft resolved recipient-specific nudges | CNT-08/SPK-13/14 | Ann batches under pressure, then personally sweeps | **Proven** | Built: select one obligation kind, generate individualized drafts, and preflight the entire batch before effects |
| Edit before sending and record last touch | Side-effect proof | Tone is irreducibly human; tool/reality drift is costly | **Proven** | Built: editable From/To/Subject/Body; only provider-confirmed sends append the obligation chase and reset its clock |
| Chase cadence / organizer reminders | SPK-16 | Calendar-driven escalation is real | **Proven if detection only** | Detect/draft; never autonomous send |
| Automated speaker reminder schedule | SPK-16 literal | Zero evidence across 13,488 messages; platform bot was disliked | **Do not build as auto-send** | Minimal rubric fact only if needed; no autonomous delivery |

## Built stage C — bonus and extra credit

| Planned capability | Competition purpose | Evidence of actual use | Verdict | Recommendation |
|---|---|---|---|---|
| Cross-event people directory/history | CRM-01/03 | Repeat speakers and prior ratings inform future review | **Proven; built** | Tenant-scoped projection over canonical people and event activity |
| Organization/role/event/tag/text filters | CRM-02 | Actual diligence dimensions | **Strong predicted; built** | Concrete filters only; no custom-schema machinery |
| Notes and tags | CRM-04 | Internal diligence and relationship memory recur across years | **Strong predicted; built** | Append-only facts; authorized activity only |
| CSV identity reuse/push person into event | CRM-05/06/10 | Chronic repeat/migration problem | **Proven, episodic; built** | Preview first, reuse email identity, preserve target-event provenance |
| Derived sourcing pipeline | CRM-07 | Invited candidates and submitted speakers share one operational arc | **Strong predicted; built** | Derive stages from facts; no parallel sales workflow |
| Saved segments | CRM-09 | Cohort reuse is plausible but unobserved | **Hypothesis; built minimally** | Saved concrete filters, not a segment automation product |
| Invited-lane outreach composer | CRM-11 | Gene/Ann personally email/DM candidates | **Proven if human-assisted; built** | Preview/edit/record only; never autonomous send |
| Tenant-scoped dashboard metrics | CRM-12 | Repeat-speaker and organization coverage are used in program diligence | **Strong predicted; built** | Metrics over the same filtered directory |
| Generic CRM breadth/custom schema | +10 area | No evidence for a generic CRM product | **Do not build** | The implementation stops at the grounded directory/history/outreach spine |
| Per-event MCP server and CLI | Bonus/agent access | Gene explicitly wants agents/CLI; Ann/PC will not call MCP | **Hypothesis for product; built for real developer intent** | One shared tenant-checked registry; one explicit-confirm named mutation |
| Forge mirror | Tiny bonus | No user evidence | **Rubric-only** | Submission task only |
| Public trajectory files | Speculative bonus | No user evidence | **Rubric-only** | Privacy-scrubbed artifacts only |
| Cloudflare migration | Mild bonus | Users want speed, not a provider | **Do not build** | Keep skipped; prove speed instead |

## Remaining stage D — operator and submission work

| Capability | Competition purpose | Evidence of actual use | Verdict | Recommendation |
|---|---|---|---|---|
| DDL/secrets/env flags/deploy/production verification | Makes built work judge-visible | Production necessity | **Required operation** | Morning operator lane; not agent-sandbox work |
| Decision-wave seed through real verbs | Prevents empty public screens | Demo only | **Rubric-only** | One-time real facts, no product machinery |
| Two uncoached rehearsals, Ann first | Matches stated evaluation | Best usability evidence available | **Proven research need** | Protect time for it |
| Allowlist repo, video, form, final URL, swyx calibration | Submission success | Competition-specific | **Rubric-only but mandatory** | Protect private research sources |

## What we expect not to use

These should not quietly become the product merely because they appear in a
100-point checklist:

| Capability | Why it is unlikely to be used | Decision |
|---|---|---|
| Formal review rounds and per-round pools | Explicitly struck by swyx; weekly calls are not system rounds | Do not build, or hide a minimal optional rubric path |
| Blind review | The real PC uses identity, employer, history, reputation, and attendee feedback | Never default; isolate if score forces it |
| Mandatory multi-criterion weighted scoring | Slows a 20–30 minute deep review without corpus support | Stars+comment default; optional criteria only |
| Autonomous reminders | Ann distrusts platform mail and escalates personally by medium/deadline | Never auto-send |
| Greedy/“AI” auto-schedule | Conflicts with blocking-sheet control, duplicate-day, travel, and availability needs | Build constraint help, not magic packing |
| Personal attendee schedule | Outside the observed Program module job | Last or skip |
| Generic CRM breadth | High cost and weaker evidence than directory/history/invited lane | Strictly last |
| MCP as an Ann/PC feature | Useful to Gene/agents, invisible to production staff | Treat as developer/integration surface |
| Forge, trajectory files, `llms.txt` polish | Competition/agent theater | Timebox after core acceptance |
| Cloudflare migration | Provider change does not solve an observed workflow | Skip |

## Priority rule for the rest of the deadline

When score and evidence conflict, sequence work this way:

1. production correctness and evaluator access;
2. dense review, progress, CSV, and co-speaker truth;
3. speaker obligations, files, versions, and human-assisted chase;
4. canonical schedule/speaker publishing, editing, and history;
5. thin integration seams that eliminate re-keying; then
6. isolated rubric-only and bonus work.

The score is still the deadline meter. The ethnography decides what is allowed
to become the architecture.
