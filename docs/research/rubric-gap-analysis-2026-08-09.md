# Gap analysis vs. swyx's official LLM-judge eval kit (`sbek`)

**Written:** Sun 2026-08-09 · **Deadline:** Wed 2026-08-12 22:00 PT
**Source of truth:** `killmysaas-evals` — `README.md` + all 7 `specs/*.yaml` (96 rubric
items, 20 scenarios). **Supersedes** `docs/research/rubric-vs-our-vision.md`, which was
written against the older, pre-eval-kit rubric.

**Corroborating (not scoring) source:** swyx's linked SessionBoard role walkthroughs —
`learn.sessionboard.com` `/videos/overview`, `/participants/overview`,
`/get-started/overview`. The eval kit ships full scrapes of all three at
`docs/research/learn-{videos,participant,organizer}.json`, and I read those rather than
re-fetch. They do not change any weight; they pin down the **filled-state** each rubric
item expects, and I have folded that detail into the evidence column below where it
sharpens a verdict. The `specs/*.yaml` remain the only scoring authority.

**How to read this:** every verdict below is grounded in a route, a function, or an
`e2e_drive.py` check that I actually read today. Where I could not find evidence, the
verdict is **MISSING** — not "probably fine." That is the same standard the judge uses:
the harness distinguishes `not_found` ("I searched and it isn't there", counts against
us) from `cannot_judge` ("I couldn't get there", routed to a manual queue).

---

## 0. The one-paragraph version

Two facts dominate everything else in this document.

1. **The eval agent cannot sign in to our app at all.** There is no signup, and
   `may-sign-in?` (`auth.clj`) admits only (a) an email already on a committee roster or
   (b) an email that has already submitted a talk. The magic link is printed on the page
   **only when `ENV=dev`** (`server.clj:224`, `views.clj:2232`); in production without
   SMTP it is deliberately printed nowhere. So the fixture organizer *Jordan Alvarez*
   cannot get in, and neither can the reviewer *Sam Whitfield*. **~78 of 84 required
   rubric items live behind an organizer or portal login.** Coverage would land far below
   the kit's 60% floor and the report would read *"insufficient coverage — score
   withheld."* Not a bad score. **No score.**
2. Setting that aside, our functional ceiling today is roughly **34%**, and it is
   lopsided: **AI Agenda ~83%**, **CFP ~60%**, then a cliff — **Public Widgets ~26%**,
   **Speaker Management ~21%**, **Content Management ~18%**, **Abstract Management ~13%**.
   Abstract Management and Public Widgets are **20 points of area weight each** — 40% of
   the whole score — and they are our two worst areas.

---

## 1. Scoreboard

Weights: `CFP 20 · ABS 20 · SPK 15 · CNT 15 · AIA 10 · EMB 20 · CRM +10`.
Item weight (1/2/3) ranks items *within* an area. "Pts" = the item's share of the
**overall 100**, i.e. `area_weight × item_weight ÷ area_item_total`. Verdict → judge
points: STRONG=1.0, PARTIAL=0.5, WEAK/MISSING=0.

### 1.1 Call for Papers — area weight 20 · 16 items · 34 item-pts → **≈60%**

| id | w | type | Pts | Verdict | Evidence |
|---|--:|---|--:|---|---|
| CFP-01 | 3 | crud | 1.76 | **STRONG** | `/events/:slug/form` builder: 6 field types incl. short text / long text / choose-one, required + private flags, live preview running the real public renderer; `e2e_drive` §4b asserts 422 + per-field messages |
| CFP-02 | 1 | depth | 0.59 | **MISSING** | No `show-when` / conditional anywhere in `src/` (grep: 0 hits) |
| CFP-03 | 3 | exists | 1.76 | **PARTIAL** | `/cfp/:slug` is public, no account wall (`auth/public-prefixes`); masthead shows event name + close date (`views.clj:2596`). But the seed form has no field literally named **Track**, and formats are our vocabulary, not the fixture's |
| CFP-04 | 2 | **rule** | 1.18 | **STRONG** | `cfp-closed-notice` (`views.clj:2566`) renders a real closed state; `create-submission!` throws `{:type :cfp-closed}` (`submissions.clj:242`) |
| CFP-05 | 3 | crud | 1.76 | **PARTIAL** | Submit + addressable confirmation page work (`e2e_drive` §5). "Their own dashboard" = `/portal`, reachable only by magic link → agent can't get there |
| CFP-06 | 3 | roundtrip | 1.76 | **STRONG** | `e2e_drive` §5b: submission appears on organizer list, board and detail with abstract intact |
| CFP-07 | 1 | depth | 0.59 | **MISSING** | `"Draft"` exists as a *status* only; no save-as-draft on the public form. Target shape is precise and small: title-only save, an info banner saying you are editing a draft, a resume prompt on return, a "Reset saved data" control |
| CFP-08 | 1 | side-effect | 0.59 | **PARTIAL** | `submissions.clj:272` sends a real confirmation naming the event + talk title, and `mail/history` surfaces it on the **Comms** page — the spec explicitly accepts an in-app outbox. Fails only on actual delivery (SMTP off) |
| CFP-09 | 2 | roundtrip | 1.18 | **PARTIAL** | `POST /api/submissions/:id/answers` + `portal/update-answers!` validate against the submission's form **snapshot** (excellent), but the edit UI is portal-only |
| CFP-10 | 2 | **scoping** | 1.18 | **WEAK** | We can add committee members, but `auth/organizer?` = *any* membership → a "reviewer" sees the full organizer nav. There is **no reviewer-facing dashboard distinct from admin** |
| CFP-11 | 2 | roundtrip | 1.18 | **STRONG** | `e2e_drive` §6: two reviewers rate + comment, both visible inline to each other and to the organizer |
| CFP-12 | 3 | crud | 1.76 | **STRONG** | 7-valued status control; `e2e_drive` §6b asserts the log, not just the page |
| CFP-13 | 2 | roundtrip | 1.18 | **PARTIAL** | `portal/visible-status` does exactly this — behind the login wall. Also by design a speaker sees "Under review" until *informed*; judge expects Accepted/Rejected right after the decision |
| CFP-14 | 2 | side-effect | 1.18 | **PARTIAL** | Inform page shows every letter in full, `inform-all` per status, Comms history. But our UI **truthfully says nothing was emailed**; pass criteria wants "reports as sent/queued" |
| CFP-15 | 2 | handoff | 1.18 | **PARTIAL** | Accepted talks reach the schedule tray (`schedule/schedulable`); exports/agenda gate on accepted **AND informed**, so the handoff needs an extra deliberate act the agent may not find |
| CFP-16 | 2 | **rule** | 1.18 | **WEAK** | `portal/editable?` gates on *status*, never on `cfp-closes-at` → a Pending submission stays editable after the CFP closes. The incumbent's behaviour is exactly what the judge will look for: the same multi-page form, rendered **read-only with a message that editing is no longer available**. A one-predicate fix |

### 1.2 Abstract Management — area weight 20 · 14 items · 28 item-pts → **≈13%**

Our worst area, and tied for the heaviest. Our doctrine ("no evaluation plans, no rounds,
no assignment bureaucracy") is the *exact inverse* of this spec. That is a defensible
product position and an indefensible score.

| id | w | type | Pts | Verdict | Evidence |
|---|--:|---|--:|---|---|
| ABS-01 | 3 | crud | 2.14 | **MISSING** | No review-round / evaluation-plan concept anywhere |
| ABS-02 | 2 | **scoping** | 1.43 | **MISSING** | No per-round reviewer pools |
| ABS-03 | 3 | crud | 2.14 | **MISSING** | Scorecard = one `stars` integer + one comment. No criteria editor, no numeric/dropdown/free-text criteria |
| ABS-04 | 1 | depth | 0.71 | **MISSING** | No weights |
| ABS-05 | 3 | **scoping** | 2.14 | **MISSING** | **No per-submission reviewer assignment.** `default-committee` is `:scope {:all true}`; every member sees every submission. This is the single highest-value item we fail, and the README names `scoping` the strongest discriminator in the rubric |
| ABS-06 | 2 | bulk | 1.43 | **MISSING** | No caps, auto-distribute, or track-filtered bulk assign |
| ABS-07 | 2 | **scoping** | 1.43 | **MISSING** | No blind/anonymised review; reviewer identity is *deliberately* named in our doctrine, but author anonymity is a separate, absent thing |
| ABS-08 | 2 | roundtrip | 1.43 | **PARTIAL** | `reviews/coverage` gives a per-**submission** progress bar against a 2-review target. Spec wants per-**reviewer** completion counts (Sam: 2 assigned / 0 done → 2/2) |
| ABS-09 | 1 | bulk | 0.71 | **MISSING** | No reviewer reminders |
| ABS-10 | 3 | roundtrip | 2.14 | **PARTIAL** | Board shows mean + n per submission and has two preset sorts (`reviews/sort-presets`). Missing: sorting by score in **both** directions (`top-rated` is descending only) |
| ABS-11 | 2 | crud | 1.43 | **PARTIAL** | Seed form has a `:speakers` group with `:repeatable true :min-count 1` (`seed.clj:100`), but I found no evidence the public renderer emits an "add another speaker" control, and no role labels |
| ABS-12 | 1 | depth | 0.71 | **MISSING** | No conflict-of-interest / recusal control |
| ABS-13 | 2 | side-effect | 1.43 | **MISSING** | Exports are sessions.json / speakers.json / .ics / llms.txt. **No review-scores CSV.** |
| ABS-14 | 1 | depth | — | **n/a** | We claim no AI review → spec says judge as not-applicable. Neutral, not a loss |

### 1.3 Speaker Management — area weight 15 · 16 items · 33 item-pts → **≈21%**

| id | w | type | Pts | Verdict | Evidence |
|---|--:|---|--:|---|---|
| SPK-01 | 3 | exists | 1.36 | **WEAK** | No speaker **roster page**. `/events/:slug/people/:person-id` is a detail page; the searchable list is the *submissions board*, not a people directory |
| SPK-02 | 3 | crud | 1.36 | **MISSING** | Organizers cannot add or edit a speaker. People exist only as a side effect of a submission (`people/find-or-new`) |
| SPK-03 | 2 | bulk | 0.91 | **MISSING** | No CSV import anywhere |
| SPK-04 | 2 | crud | 0.91 | **WEAK** | Status is on the *submission*, not the *speaker*; no Invited/Confirmed vocabulary, no speaker-status filter |
| SPK-05 | 2 | crud | 0.91 | **WEAK** | Tasks are event-level defaults (`seed.clj:133`) auto-installed on inform. **No creation UI, no due dates, no multi-speaker assignment** |
| SPK-06 | 2 | side-effect | 0.91 | **PARTIAL** | Inform letters + magic link exist and are logged to Comms; no explicit "send portal invite" control, and the UI says nothing was sent |
| SPK-07 | 3 | **scoping** | 1.36 | **PARTIAL (blocked)** | The portal *is* correctly scoped — `portal/my-submissions` is keyed to the person, and `e2e_drive` §7c proves a speaker gets 403 + redirect off organizer surfaces. But **the agent cannot reach it**, so this drops to the manual half. Filled-state the judge expects: event logo + accent colour + welcome message, and widgets named *My Sessions · Invited Sessions · Confirmed Participation · Tasks · Files · Resources* with top-nav Submissions/Files/Resources. Ours is a single status-and-checklist column — scoped correctly, but visibly thinner |
| SPK-08 | 3 | roundtrip | 1.36 | **PARTIAL** | Portal profile edits bio/title/org/LinkedIn and round-trip to the organizer view. **Headshot is a URL field, not an upload** — the fixture hands the agent `headshot.png` |
| SPK-09 | 2 | crud | 0.91 | **PARTIAL** | Tasks appear in the portal and `complete-task!` persists; **no due dates**. Target task row is concrete: name + required asterisk + description + due date *with timezone* + Incomplete/Complete chip, and a detail view with `Open Link` and `Mark as Complete` |
| SPK-10 | 2 | roundtrip | 0.91 | **MISSING** | No file store at all, so nothing to list with metadata or download |
| SPK-11 | 2 | roundtrip | 0.91 | **PARTIAL** | Speaker↔session link exists both sides; room/time not shown in the portal |
| SPK-12 | 2 | roundtrip | 0.91 | **WEAK** | `inform/missing-speaker-details` gives a dashboard alert for missing bio/headshot — that is *an* organizer-side list-level signal, but not per-speaker per-task completion |
| SPK-13 | 2 | bulk | 0.91 | **WEAK** | `inform-all` sends decision letters by status. There is **no compose-to-a-selected-group** flow; Comms is a history, not a composer |
| SPK-14 | 1 | depth | 0.45 | **STRONG** | `inform/render-template` + `merge-values`: `{speaker_name}` / `{talk_title}`, and every letter is rendered **fully resolved, per recipient**, before anyone presses send. This is exactly the "tokenised body + resolved preview" the item asks for |
| SPK-15 | 1 | depth | 0.45 | **MISSING** | `portal/profile-fields` is a fixed list; no travel/logistics or custom field on a person |
| SPK-16 | 1 | side-effect | 0.45 | **MISSING** | `:reminder-email-enabled true` is a setting with no sender behind it |

### 1.4 Content Management — area weight 15 · 14 items · 31 item-pts → **≈18%**

Confirmed: **we have file-attachment doctrine and no file pipeline.** `grep -ri upload src/`
returns URL fields (`headshot-url`, `slides-url`) and a `:file` field type that the form
builder explicitly excludes (`forms/editable-types`: *"`:file` … deliberately absent:
uploads arrive with the uploads slice"*). The uploads slice was never built.

| id | w | type | Pts | Verdict | Evidence |
|---|--:|---|--:|---|---|
| CNT-01 | 3 | crud | 1.45 | **MISSING** | No file-request task type, no instructions field, no due date, no organizer task creation |
| CNT-02 | 3 | crud | 1.45 | **MISSING** | Portal accepts a **URL** for slides, never a file; no deadlines shown. Target: drag-drop/browse upload, file **typed** (Presentation / Poster / Handout), listed against the session |
| CNT-03 | 3 | **scoping** | 1.45 | **STRONG (blocked)** | `auth/wrap-require-login` is default-deny; `e2e_drive` §7c: five escalation attempts → 403, nothing written, board→/portal redirect. Our strongest item in the area — if the agent can get an account |
| CNT-04 | 2 | **rule** | 0.97 | **MISSING** | No versions (the URL task upserts in place, `store.clj:338`). Target row: `keynote-slides.pptx — Presentation — v2` with an expandable history showing v1 and v2 |
| CNT-05 | 2 | roundtrip | 0.97 | **MISSING** | Comments exist on **submissions**, not on files. Target: a two-sided thread on the file itself — admin note, speaker reply — visible from both roles |
| CNT-06 | 1 | depth | 0.48 | **MISSING** | No upload UI → no constraints to state |
| CNT-07 | 3 | roundtrip | 1.45 | **WEAK** | No deliverables dashboard; task state is per-submission inside the portal |
| CNT-08 | 2 | bulk | 0.97 | **MISSING** | No bulk reminders |
| CNT-09 | 2 | crud | 0.97 | **WEAK** | No organizer-side edit UI for a session's title/abstract. (An organizer can technically POST `/answers`, but there is no screen, and the judge scores screens) |
| CNT-10 | 2 | crud | 0.97 | **MISSING** | No organizer-side speaker profile edit |
| CNT-11 | 2 | depth | 0.97 | **PARTIAL** | Genuine strength half-shown: the append-only log (`/events/:slug/log`), per-event **replay/time-travel**, every event carries `actor` + timestamp. Missing: a per-object history panel and a **restore** action |
| CNT-12 | 3 | **rule** | 1.45 | **PARTIAL** | We do have a real publication gate — public surfaces show only *accepted **and** informed* (`views.clj:1341`, `exports.clj`). It is a stronger gate than the spec asks for, but it is **not named "approved"** and lives on a page called *Inform Speakers*, so an agent hunting a "content status" control may not find it |
| CNT-13 | 1 | exists | 0.48 | **MISSING** | No files library |
| CNT-14 | 2 | bulk | 0.97 | **MISSING** | No ZIP export |

### 1.5 AI Agenda Builder — area weight 10 · 8 items · 18 item-pts → **≈83%**

Our best area by a distance, and it carries the rubric's densest cluster of `rule` items
(AIA-04/05/06 = 7 of 18 item-pts) — the type the README calls the strongest signal.

| id | w | type | Pts | Verdict | Evidence |
|---|--:|---|--:|---|---|
| AIA-01 | 3 | exists | 1.67 | **STRONG** | `/events/:slug/schedule`: day columns, time gutter, rooms, unplaced tray (`schedule/tray`), blocks |
| AIA-02 | 2 | crud | 1.11 | **PARTIAL** | Rooms are fully CRUD (`room-add` / `rename-room!` / `room-remove`) and immediately schedulable. **Tracks are not a first-class entity** — "track" is a form answer (`exports.clj:150` maps it from `:industry`) |
| AIA-03 | 3 | crud | 1.67 | **STRONG** | `place!` writes `slot.assigned` to the log; survives reload by construction (event-sourced fold) |
| AIA-04 | 3 | **rule** | 1.67 | **STRONG** | `schedule/conflicts` speaker branch: *"⚡ {name} is also in {room} at {time}"* — names the person and offers both sides. Exactly the item's wording requirement |
| AIA-05 | 2 | **rule** | 1.11 | **STRONG** | Room branch: *"Room double-booked: {room} at {time}"*. Flagged not blocked — the item explicitly allows either |
| AIA-06 | 2 | **rule** | 1.11 | **STRONG** | Conflicts are computed pure over current placements, so a move clears them on the next render; placements persist |
| AIA-07 | 2 | handoff | 1.11 | **PARTIAL** | Public `/agenda/:slug` exists, and `schedule/lock!` carries a version label — but **there is no button that says "Publish"**, and publication actually keys off *informing speakers*, two pages away |
| AIA-08 | 1 | depth | 0.56 | **MISSING** | grep for auto-place / auto-schedule / suggest: **zero hits**. The area is literally named "AI Agenda Builder" and we have no assist |

### 1.6 Public Widgets — area weight 20 · 16 items · 34 item-pts → **≈26%**

Tied-heaviest area. We ship **one** of the five widget surfaces (agenda) plus data
exports. The other four — sessions list, speakers list, itinerary, speaker gallery —
do not exist as screens.

The judge's filled-state target here is unusually concrete, and worth quoting because it
doubles as the build spec for kill-list #1:

- **Sessions list:** event-title header, `Sessions 1 – 22 of 22` count, search box +
  Filters button, vertical cards each carrying bold title, 2–3 line truncated abstract +
  **Show more**, `Friday, December 15: 04:00 PM - 05:00 PM`, `Room 305`, a Speakers block
  (*name / job title / company*), and chips `Format: …` and `Track: …`.
- **Speakers list / gallery:** name search; grid of cards with headshot, name, job title,
  company — **including one speaker deliberately missing a photo to prove graceful
  fallback**; detail shows Back, photo, name, title, truncated bio + Show more, company,
  and `Sessions (2)` listing each session's title, date/time and room.
- **Agenda:** day header, **room columns**, a time gutter, blocks at their slot showing a
  track label + title + room; detail shows Back, full `start - end`, room, tabs
  `Session Details / Subsessions (0)`, description + Show more, `Format: …`.
- **Search scope rule** (matters for EMB-02): sessions list and itinerary match **session
  titles *and* speaker names**; the speaker gallery/list matches speaker names only.
- **Embeds:** the snippet is a single `<script src=…sessionboard-session-embed.js>` plus a
  `<sessionboard-embed embed-id widget-type>` element, generated from a builder with a
  format picker, colour/CSS inputs, filter selectors and field checkboxes.

| id | w | type | Pts | Verdict | Evidence |
|---|--:|---|--:|---|---|
| EMB-01 | 3 | exists | 1.76 | **MISSING** | No public sessions-list page. `/agenda/:slug` items carry time/title/speakers/room and **no description, no Show-more, no format/track tags** |
| EMB-02 | 2 | **rule** | 1.18 | **MISSING** | No public search (the board's search is organizer-side) |
| EMB-03 | 2 | **rule** | 1.18 | **MISSING** | No public facets |
| EMB-04 | 3 | exists | 1.76 | **MISSING** | No public speakers directory. `speakers.json` is data, not a widget |
| EMB-05 | 2 | roundtrip | 1.18 | **MISSING** | No speaker detail page |
| EMB-06 | 3 | exists | 1.76 | **PARTIAL** | `agenda-page` is a genuine per-day, time-ordered list with room labels — the spec accepts "a clearly time-slotted list". Missing the track/format label on the block |
| EMB-07 | 2 | **rule** | 1.18 | **STRONG** | Day tabs, `?day=` re-renders that day (`views.clj:1783`) |
| EMB-08 | 2 | exists | 1.18 | **MISSING** | Agenda items are not clickable; no detail view |
| EMB-09 | 2 | exists | 1.18 | **PARTIAL** | Structurally the agenda *is* the itinerary (day tabs + ascending time). Card anatomy fails: no description, no speaker job title/company, no track row |
| EMB-10 | 1 | depth | 0.59 | **MISSING** | No star / add-to-my-schedule |
| EMB-11 | 1 | depth | 0.59 | **PARTIAL** | Event-wide `calendar.ics` with stable UIDs is linked from the agenda footer; there is no *personal* selection to persist or export |
| EMB-12 | 2 | exists | 1.18 | **MISSING** | No speaker gallery |
| EMB-13 | 1 | exists | 0.59 | **MISSING** | No gallery detail |
| EMB-14 | 3 | **scoping** | 1.76 | **PARTIAL** | Pass criteria is generous — *"every widget surface **the clone implements**"* loads logged-out — and ours do (`auth/public-prefixes` covers `/agenda`, `/cfp`; `open-data-pattern` covers exports + `/api/v1`). But a judge staring at 1 of 5 surfaces will not give full credit |
| EMB-15 | 2 | handoff | 1.18 | **PARTIAL** | We have the *formats* (JSON, iCal, llms.txt, versioned REST) and the Settings page documents them — but **no embed builder, no snippet, no copy-link, no branding/field/filter options** |
| EMB-16 | 3 | roundtrip | 1.76 | **PARTIAL** | Single source of truth (one fold) means consistency is structural. Risk: only one public surface to be consistent *with*, and the known published-conflict defect (see §3) reads as a data bug |

### 1.7 Speaker CRM — extra credit +10 · 12 items · 19 item-pts → **≈5%**

| id | w | Verdict | Evidence |
|---|--:|---|---|
| CRM-01 | 3 | **MISSING** | People are per-event (`/events/:slug/people/:id`). No org-level directory |
| CRM-02 | 2 | **MISSING** | No attribute filters on people |
| CRM-03 | 2 | **PARTIAL** | A person detail page exists and the event log gives a real activity history; no internal notes, no cross-event connections view |
| CRM-04 | 1 | **MISSING** | No tags / custom fields on a person |
| CRM-05 | 2 | **MISSING** | No CSV import |
| CRM-06 | 1 | **MISSING** | No duplicate detection / merge |
| CRM-07 | 2 | **MISSING** | No sourcing pipeline / kanban |
| CRM-08 | 1 | **MISSING** | — |
| CRM-09 | 1 | **MISSING** | No saved segments |
| CRM-10 | 2 | **MISSING** | No push-contact-into-event |
| CRM-11 | 1 | **MISSING** | No bulk outreach composer |
| CRM-12 | 1 | **MISSING** | No CRM dashboard |

### 1.8 Rollup

| Area | Weight | Est. area score | Contribution |
|---|--:|--:|--:|
| Call for Papers | 20 | ~60% | 12.0 |
| Abstract Management | 20 | ~13% | 2.6 |
| Speaker Management | 15 | ~21% | 3.2 |
| Content Management | 15 | ~18% | 2.7 |
| AI Agenda | 10 | ~83% | 8.3 |
| Public Widgets | 20 | ~26% | 5.2 |
| **Required total** | **100** | | **≈34%** |
| Speaker CRM (extra) | +10 | ~5% | +0.5 |

By rubric **type** — the cut the README calls the most useful line in the report:

| Type | Required wt | Our standing |
|---|--:|---|
| `rule` | 22 | **Split.** We own the agenda's three (AIA-04/05/06) and CFP-04; we fail EMB-02/03, CNT-04, CFP-16 |
| `scoping` | 18 | **Bad.** ABS-05/02/07 all zero, CFP-10 zero. Our two genuinely good ones (CNT-03, SPK-07) are unreachable without login |
| `roundtrip` | 33 | **Mixed.** CFP round-trips are solid; every speaker-portal round-trip is login-blocked |
| `handoff` | 6 | **Weak.** CFP-15 and AIA-07 both work but require an act the agent won't find ("Inform"); EMB-15 has no embed UI |
| `bulk` | 11 | **Near-zero.** No CSV, no bulk email composer, no ZIP, no auto-distribute |
| `side-effect` | 8 | **Partial by design** — SMTP off, but the Comms outbox is an accepted substitute for two of the four |

---

## 2. The kill list

Ranked by `area_weight × item_weight`, with `rule`/`scoping` and `roundtrip`/`handoff`
weighted up per the README's calibration notes. Hours are build-and-verify, assuming
delegation to subagents against a tight spec.

| # | What to build | Items bought | Overall pts | Hours |
|--:|---|---|--:|--:|
| **0** | **Demo persona sign-in on `/login`** — three buttons whose labels *start with* `Organizer`, `Speaker`, `Reviewer`, each starting a session directly. Plus: seed the fixture emails onto a committee so magic-link works, echo the link on-screen when SMTP is unconfigured, and put a "How to sign in" block on the home page + README so a human judge (or `sbek auth`) has a documented path. | **Unblocks ~78 of 84 items**; without it the report says "insufficient coverage" | **the whole score** | **1–2** |
| 1 | **Public sessions list + speakers list + speaker gallery** (three pages over data we already fold), each with keyword search, a Track facet, cards carrying title/description+Show-more/date/time/room/speaker-with-title-and-company/format+track, and a speaker detail with their sessions | EMB-01 w3, EMB-02 w2, EMB-03 w2, EMB-04 w3, EMB-05 w2, EMB-12 w2, EMB-13 w1 | **8.8** | 8–12 |
| 2 | **Reviewer assignment + a reviewer-only dashboard.** Assign submissions to a named committee member; their queue shows exactly those; strip organizer nav for non-chair members | ABS-05 w3 **scoping**, CFP-10 w2 **scoping**, part of ABS-08 | **3.3** | 5–7 |
| 3 | **A scorecard with named criteria** — numeric + dropdown + free-text, configured per event, rendered to the reviewer, stored per criterion; aggregate shown on the board | ABS-03 w3, ABS-04 w1, feeds ABS-10 | **2.9** | 5–7 |
| 4 | **Review rounds** — two named rounds, each with dates, its own scorecard, its own pool | ABS-01 w3, ABS-02 w2 **scoping** | **3.6** | 6–9 |
| 5 | **File uploads against a task** — multipart to disk/GCS, versions on re-upload with latest flagged, comments with author+timestamp, per-session Files tab + a central library | CNT-01 w3, CNT-02 w3, CNT-04 w2 **rule**, CNT-05 w2, CNT-06 w1, CNT-13 w1, SPK-10 w2 | **6.6** | 10–14 |
| 6 | **Speaker roster CRUD + CSV import** — an actual Speakers page with search/filter/status, manual add/edit, and `speakers.csv` import | SPK-01 w3, SPK-02 w3, SPK-03 w2, SPK-04 w2 | **4.5** | 5–7 |
| 7 | **Tasks with due dates, assignable to speakers, on a deliverables dashboard** with a complete/incomplete filter and a bulk reminder button | CNT-07 w3, CNT-08 w2, SPK-05 w2, SPK-09 w2, SPK-12 w2 | **4.6** | 5–7 |
| 8 | **Agenda upgrades:** clickable session detail (start–end, room, description, format, track), track/format chips on blocks, keyword search | EMB-08 w2, completes EMB-06 w3 + EMB-09 w2 | **2.9** | 3–4 |
| 9 | **Blind review toggle** — hide author/co-author/company in the reviewer view, visible to the organizer | ABS-07 w2 **scoping** | **1.4** | 2–3 |
| 10 | **Per-reviewer progress dashboard + bulk nudge** (assigned / complete counts, select laggards, send) | ABS-08 w2, ABS-09 w1 | **1.5** | 2–3 |
| 11 | **Rename/surface the approval gate** — put an explicit `Content status: Draft / In review / Approved` control on the session, wired to the existing publication gate, and an explicit **Publish agenda** button | CNT-12 w3 → full, AIA-07 w2 → full, CFP-15 w2 → full | **2.4** | 2–3 |
| 12 | **Review-scores CSV export** from the board | ABS-13 w2 | **1.4** | 1–2 |
| 13 | **Embed/share page** — pick a widget type + format (script tag / HTML / JSON / iCal), generate a copyable snippet and URL | EMB-15 w2 **handoff** | **1.2** | 2–3 |
| 14 | **Lock editing when the CFP closes** — one predicate in `portal/editable?` plus a read-only render | CFP-16 w2 **rule** | **1.2** | 1 |
| 15 | **Auto-schedule button** — greedy pack of the unplaced tray into free room/time slots, conflict-aware. Cheapest possible answer to "AI Agenda Builder" | AIA-08 w1 | **0.6** | 2 |
| 16 | **Conditional field** (`show only when {field} = {value}`) in the form builder + public renderer | CFP-02 w1 | **0.6** | 2–3 |
| 17 | **Personal schedule** — star sessions, "My schedule" view, persists, per-selection .ics | EMB-10 w1, EMB-11 w1 | **1.2** | 3 |
| 18 | **Organizer-side session + speaker content editing** with a per-object history panel and restore | CNT-09 w2, CNT-10 w2, CNT-11 w2 → full | **2.9** | 4–5 |

**If you only get twelve hours:** #0, #11, #14, #15, #8, then as much of #1 as fits. That
sequence turns "no score" into a real score, converts three near-misses into full credit
for a few hours' work, and starts on the heaviest unbuilt area.

---

## 3. Traps — where the harness beats us even when the feature works

1. **The login wall is a total loss, not a partial one — and we owe the judge a
   documented sign-in path.** Three compounding facts: `may-sign-in?` requires an existing
   roster membership or a prior submission; there is no signup path; and the magic link
   renders **only under `ENV=dev`** (`server.clj:224`).

   The kit's designed escape hatch is `npm run sbek -- auth --persona speaker`, which
   opens a **real browser window for a human** to complete the login by hand; the kit then
   saves the session to `.auth/<host>.<persona>.json` and every scenario for that persona
   starts pre-authenticated. swyx separately accepts a DM'd URL + credentials. **Both of
   those paths assume a credential exists that a human can use.** Ours does not: with SMTP
   off, the link is emailed nowhere and printed nowhere, so there is nothing for the judge
   to type into that window and nothing for us to DM. This is the trap — not that login is
   hard, but that the *manual* fallback the kit and swyx both rely on is also closed.

   **Mitigation, in priority order:** (a) demo persona buttons on `/login` — works for the
   fully-automated path *and* the `sbek auth` path, and needs no secret to travel; (b)
   echo the magic link on-screen whenever SMTP is unconfigured, gated to seeded demo
   identities so it is not a production credential leak — this gives us something concrete
   to DM; (c) a short "How to sign in" block on the deployed site's home page and in the
   README, naming each persona and its one-click button, so a judge who reads nothing else
   still gets in. Do (a) and (c) at minimum.

   Precedent that (a) is acceptable rather than gaming: the kit's own `src/auth.ts`
   supports an `autoClick` label, and its code comment references a role picker with
   labels like *"Speaker"* and *"Event admin — forms, …, speaker status"* — a prior
   submission shipped exactly this and the harness was built to accommodate it. Note also
   that the incumbent solves this with a **password**: SessionBoard's confirmation email
   carries a portal link, the new user *sets a password*, then clicks "Continue to portal".
   A password path for demo personas would be equally legible to a judge.
2. **`autoClick` matching is picky.** It prefers a label that **starts with** the persona
   word and **throws on ambiguity** (>1 match). So label the buttons so exactly one
   starts with each of `Organizer`, `Speaker`, `Reviewer` — and do not put a second
   control whose label starts with "Speaker" (e.g. "Speaker portal") on the same page.
3. **Fixture email addresses are inconsistent across the kit — seed *all* variants.**
   `fixtures/sample-data.json` uses `sbek-organizer@example.com` / `sbek-speaker@…`;
   the scenario prose in `01`–`07` uses `jordan.organizer@sbek-test.example.com`,
   `priya.speaker@…`, `marcus.speaker@…`, `sam.reviewer@…`; `fixtures/speakers.csv` uses
   the `sbek-test.example.com` forms; and `personaEmails` in the judge's `evalconfig.json`
   can override all of them at run time. Put every variant on a committee/allow-list, and
   make sure an *unknown* email can still get in (that is what `personaEmails` will be).
4. **Sessions die on restart.** `wrap-session` uses the default in-memory store and
   magic-link tokens live in an atom (`auth/tokens`, deliberately not in the log). A
   Cloud Run instance recycle mid-run — the eval takes ~1 hour — silently signs out every
   persona and invalidates the kit's saved `.auth/*.json` cookies. Use a cookie-backed
   session store (or a persistent store) before deploying.
5. **The submission cap of 3 will fire mid-run.** `:submissions-per-person-cap 3`, and
   Priya alone is asked to submit 3 in ABS-S1 after possibly 2 in CFP-S2 — plus AIA-S1
   adds her as speaker on a second session. The 4th attempt returns a *correct* 422 that
   the judge will read as a broken submit flow. Raise or disable the cap on the demo
   deployment.
6. **Our publication gate hides the demo.** Public agenda and exports show only
   *accepted **and** informed*. EMB-S1 opens the site logged-out expecting populated
   widgets; its written precondition is *"if the widgets appear empty because of the
   content-approval gate, sign in as organizer and set every scheduled session's content
   status to Approved."* The agent will hunt for a control named **Approve**, not a page
   named **Inform Speakers**. Either rename/mirror the control (kill-list #11) or ensure
   the seeded demo event ships fully informed.
7. **Fixture strings are matched literally.** The judge looks for `DevFlow Conf 2027`,
   `Taming 40-Minute CI: Incremental Builds at Monorepo Scale`, tracks `AI Engineering` /
   `Platform & Infra` / `Developer Experience`, formats `Keynote (45 min)` … `Panel (45
   min)`, rooms `Main Stage` / `Room 2A` / `Room 2B` / `Workshop Lab`, and the sentinels
   `SBEK-ORG-EDIT-01` / `SBEK-PORTAL-BIO-01`. Our seed world is EAIS Charlotte. That is
   fine — the agent *creates* DevFlow itself — provided (a) event creation is reachable,
   (b) the seed form exposes a field literally labelled **Track** and one labelled
   **Format** with editable options, and (c) rooms are creatable by those names. Today
   our vocabulary is `session-format` and `industry`; **add a Track field to the seed form.**
8. **Areas chain 01→07 against one deployment.** Area 01 deliberately *closes* the CFP at
   its end and ABS-S1 reopens it, so `cfp-closes-at` must stay editable post-creation
   (it is — `events.clj:282`). But it also means any state we corrupt early poisons every
   later area. A store reset + reseed before the judge's run is not optional.
9. **The judge independently reports defects it notices, even where no rubric item
   covers them.** Two known ones are currently visible: the published schedule shows one
   speaker in two rooms at 10:30 (bd `-31b`), and ~186 stray React-habit `key=`
   attributes in the server-rendered HTML (bd `-lc3`). Both will be read as sloppiness.
10. **Our honesty about SMTP costs points but should stay.** CFP-14 and SPK-06/13 want
    "the UI reports the messages as sent/queued". We say, correctly, that nothing was
    emailed. Keep it — but make the **Comms** outbox unmistakable: name it something the
    agent's search will hit (`Email log` / `Outbox`), show recipient + subject + timestamp
    per message, and link to it from the inform confirmation. The specs explicitly accept
    an in-app email log as evidence for CFP-08, ABS-09 and CNT-08.
11. **`cannot_judge` is *not* a penalty — `not_found` is.** When a capability genuinely
    doesn't exist, we lose the point. When the agent can't reach it, the item goes to the
    manual queue instead. That is another argument for #0: our best `scoping` work
    (CNT-03, SPK-07) currently earns neither credit nor a clean deferral, because the
    agent will conclude the portal doesn't exist.
12. **Below 60% coverage the headline score is withheld entirely.** A withheld score is
    strictly worse than a mediocre one for a "would we actually use/buy it" judgement.
13. **The incumbent's own docs set the "filled state" bar, and empty screens read as
    broken.** The walkthroughs the judge's rubric was written from show every screen
    populated — 22 sessions with a result count, 14 speaker cards, a room-column agenda,
    a task list with due dates and mixed statuses, a file with a v1/v2 history and a
    comment thread. Doctrine #10 already says judges must never see an empty screen; the
    corollary from these scrapes is sharper — **the demo must be populated on every
    surface the eval visits**, including ones the agent reaches only after signing in as
    a speaker.

---

## 4. Extra credit: is Speaker CRM (+10) worth any hours?

**No. Spend zero hours on it.** Three reasons:

- It is 12 items and ~19 item-pts of which we currently earn ~1. Getting to even 50% of
  the area means building an org-level directory, CSV import, filters, saved segments,
  notes, custom fields, duplicate merge, a kanban pipeline, cross-event push, bulk email
  and a dashboard — realistically 20–30 hours — for at most **+5 overall points**.
- The same hours spent on kill-list #1 + #2 + #11 buy roughly **+14 overall points** in
  *required* areas, which are also the areas whose weakness currently caps our score.
- The kit only runs area 07 with `--include-optional`. There is no guarantee the judge
  passes that flag; the required 100 is the guaranteed denominator.

**The one exception worth ~1 hour:** CRM-01's pass criteria is "a cross-event contacts
area reachable at organisation level, listing name + email, with working search." If
kill-list #6 (speaker roster) ships, promoting it to a top-level `/people` page that spans
events is nearly free and picks up the area's single heaviest item. Do that only *after*
#6, and only if it costs under an hour.

---

## 5. Executive verdict

1. **Where we'd score today: nowhere — the report would read "insufficient coverage,
   score withheld,"** because the eval agent cannot obtain an organizer, reviewer or
   speaker session; behind that wall our real functional ceiling is ~34%, carried almost
   entirely by AI Agenda (~83%) and CFP (~60%).
2. **Biggest lever: one to two hours of demo persona sign-in plus a documented judge
   path** (`Organizer / Speaker / Reviewer` buttons on `/login`, fixture emails seeded
   onto a committee, magic link echoed when SMTP is off, and a "How to sign in" block on
   the deployed home page and README). It changes nothing about the product and changes
   everything about the score, converting ~78 items from unreachable into judgeable —
   including the two `scoping` items we already do better than the incumbent. Note that
   the kit's manual fallback (`sbek auth`) and swyx's DM'd-credentials path *both* need a
   credential we currently cannot produce, so this is not optional polish.
3. **Second biggest: the four missing public widget surfaces** (sessions list, speakers
   list, gallery, plus search/facets/detail) — 8.8 overall points in the joint-heaviest
   area, built entirely over data we already fold, and the surface a judge asking "would
   we actually use this?" will look at first.

---

## Addendum 2026-08-10: standing after the overnight build

The overnight agent landed judge sign-in, the rule conversions, telemetry,
and partial public widgets (review: `logs/2026-08-10-overnight-agent-review.md`).
Scoring is UNLOCKED (kill-list #0 built, pending morning deploy); estimated
standing moved from "withheld / ~34% ceiling" to **~45–50 of 100**. The
remaining gap to 100% — Abstract Management, speaker ops, files, widget
completion, bonuses, CRM — is enumerated as an executable program in
**`plans/2026-08-10-close-to-100-plan.md`**.
