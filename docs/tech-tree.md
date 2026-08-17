# Curtain Call — The Technology Tree

*Civ-style map of everything built, buildable, and blocked — read it like the
tech tree: eras gate eras, arrows are unlocks, ⭐ marks the current research.*

**⏰ THE CLOCK: contest deadline Wed Aug 12, 10:00 PM PT.** Every node is
judged against: does this help win, or does it help ITRev run real events?

## 🎯 GENE'S COCKPIT (updated 2026-08-12 ~07:44 — everything only YOU can do)

### A0. Acceptance review queue

Nothing is waiting on Gene right now. Reviewer v2 and the `s8ar` chair-visibility
repair are deployed at `b9fed25`; the same-scenario ABS-S3/CFP-S3 meter reruns
are underway against the stable sandbox at http://localhost:20506/login.

### A. Acceptance drive — localhost, signed in as yourself (log findings → logs/)

**Public brag arc** (the 15-step checklist from chat, condensed):
1. https://curtaincallcfp.com/agenda/enterprise-ai-summit-charlotte-2026/speakers/mik-kersten/announce
   — order: hero → LINEUP → EVENT → CFP line → AGENDA+pitch → footer; event name said once;
   share strip: Copy link / Copy post text paste correctly; LinkedIn/X composers prefill.
2. Same URL with the UUID (…/cc325460-0751-4a01-a10f-5494b65cf165/announce) → lands on /mik-kersten/.
3. ✅ https://curtaincallcfp.com/program/enterprise-ai-summit-charlotte-2026 — Gene-accepted
   2026-08-11: 14 speakers, Mik once, organizer-only.
4. Unfurl: paste URL 1 into https://www.linkedin.com/post-inspector/ → title/desc/square-thumb
   (big card arrives with `cc8`); paste into a Slack DM → compact unfurl.

**Organizer surfaces (NEW on prod tonight):**
5. ✅ Organizer Announce wall — Gene-accepted locally at localhost:20501 on
   2026-08-11. "Light all" completed successfully: marquee 14/14, mutating the
   shared production Postgres database through the local `server-dev` process.
6. ✅ Inform Speakers — Gene-accepted locally at localhost:20501 on 2026-08-11:
   the Decide → Tell handoff works; mail remained deliberately muted.
7. https://curtaincallcfp.com/events/enterprise-ai-summit-charlotte-2026/speakers/new — Create
   Speaker (⚠ creates a real prod record — use an obviously-test name or skip until demo-event).
8. https://curtaincallcfp.com/admin/zoo/social-sharing — unfurl lab: preview URL 1, dimension verdict.
9. /welcome as a stranger (incognito) — the judge's first 60 seconds.

`b0d` 404 + `/cfps` are production-accepted (CI 31561332626, staging
`cb3e5de`, 2026-08-12); no expected-fail remains on that path.

### B. Actions only you can take
- **Schedule Ann's uncoached rehearsals (`nuk`)** — THE deadline long pole; everything else is polish.
- **crf.2 (`nuc`) — push the public repo** (claimed: you). Submission gate.
- **LinkedIn post** — draft: main repo docs/drafts/2026-08-11-linkedin-brag-sheet.md (Mothership).
  Decision inside: post now (B+ square card) or after `cc8` deploys (A+ big card); Mik's-nod caveat noted in file.

### C. Decisions pending (one-worders)
- ✅ **Reconstruction and Lane A deploy completed**: Tracks, Mention, Edit
  Speaker, Notify, Amara, blind policy, masked API keys, approval-gated email
  outbox, and the working CFP portal link are in the single production lineage.
  Latest checkpoint `b9fed25`; CI `31607224417`; outside-in persona door PASS.
  Lost Speakers/CSV import, deliverables, Files, and Embeds & widgets doors are
  restored in production (`99b1751`, CI `31608635683`, outside-in PASS).
- ✅ **Immutable remote branch history:** GitHub ruleset `20751015` actively
  rejects branch deletion and non-fast-forward updates across all refs. Buster
  lanes run unattended with `workspace-write`, never naked sandbox bypass.
- **`x8d` Discord re-harvest / requirements freeze** — 🔒 your call on scope freeze.
- **Acceptance-email unmute** for the live rehearsal — event mail is muted everywhere; the accept-demo
  beat needs one real letter, once, deliberately.

### D. Blockers held by others
- `nuk` 🔒 Ann's calendar · `3ma` 🔒 replay corpus first · Sol merge 🔒 post-horn.

---

## How agents maintain this (the update protocol)

- **Every agent that ships, starts, or discovers a node updates this file in
  the same batch as the work.** Move nodes between eras; don't duplicate.
- Statuses: `◌ proposed (dark; ratification pending)` · `✅ shipped` · `🧪 in
  flight (named lane)` · `🔓 unlocked (start anytime)` · `🔒 locked (edge
  names the prerequisite)`.
- **A node is ✅ only when verified at the meter** (live URL, green CI run,
  real rehearsal) — not when code exists.
- **Gene's cockpit is decision-only.** Remove an item from the cockpit/A0 queue
  in the same edit that records its production shipment. Preserve its history
  in the era node, changelog, Beads, and deploy receipt; keep local/staging work
  in the cockpit only while Gene still owns a concrete decision or review.
- Node format: `**Name** (`bead-id`) — one-line what · *unlocks: what it opens*`
- One line per change, appended to the Changelog at the bottom:
  `YYYY-MM-DD HH:MM <seat> — moved X to ✅ / added Y (unlocked by Z)`.
- Never delete a node; strike it through with the reason.

---

## ERA 0 — THE BUILT CITY (foundations already standing) ✅

**Bedrock**
- Event-sourced store (append-only facts, fold, Postgres prod + jsonl sandbox); domain-verb-only writes; time-machine replay (`?at-index`)
- Magic-link auth + open signup; account-on-submit
- CI pipeline: push to staging → kaocha → keyless WIF → Jib from the COMMIT → both services deployed + promoted + smoke-checked *(2026-08-11 — "commit this one fix" now works from any seat)*
- Dev=prod single-database doctrine + REPL operator lane

**The public face (the T3 editorial identity)**
- CFP page: two-col masthead, organizer chip, serif intro, ghost-fill, live preview, draft persistence
- `/program` canon (everything redirects); speakers grid + agenda + day-hours frame (9–5 default); OG/Twitter unfurls; A0.1 Gene-accepted 2026-08-11
- Alum tenure lines from the ITRev ledger corpus ("2019 alum", year pills)
- Editorial 404 "Not on the program"; unlisted events (checkbox → public 404, committee proof-view)
- Exports: sessions.json (real + tba placeholders = the whole 14-speaker story), speakers.json (announced merge), calendar.ics (hold-the-date VEVENT, stable UIDs), llms.txt, API v1 + webhooks

**The committee room**
- Review board: two sorts = two work queues, inline conversation, coverage bar, 8-status + Notified flag
- Blind review as config data (hide-presenter / reveal-after-vote) — *seam verified; adversarial live check still open, see ERA III*
- Inform flow: deliberate second act; acceptance letter links the portal
- Speaker portal: status truth + task checklist installed on inform
- Committee acceptance-notify emails [Buster cc-notify] — merged + no-op-re-accept-safe, but **MUTED with all event mail**; unmuting is a decision + rehearsal (rides the ERA I acceptance rehearsal)

**The front door**
- Welcome/login surface: /welcome doors, role cards, W2 treatment live
- ◌ **RESTORE HOMEPAGE TOOL-ARCHAEOLOGY WONDER GRAPHIC** (`589`) — production
  is missing the visual fifteen-years/five-tools story naming EventPower,
  Cvent, BusyConf, Sessionize, and the Trello/Zapier/Sheet workarounds. Start
  from the existing `main` landing-ledger/HISTORY.LOG treatment, reconcile it
  into the deployed staging homepage, and preserve current CTA, editorial,
  responsive, and unfurl behavior. Lights when Gene accepts the restored
  visual impact on desktop and mobile—not merely the presence of the words.
- Judge demo personas (Organizer·swyx / Reviewer·Maya / Speaker·Amara) — demo-gated, allowlisted, dev-switcher blocked when deployed; *the first thing the judges touch*

## THE FLOWS — persona journeys, done vs not (audited 2026-08-12 ~02:00)

*Evidence: final-rubric-audit-2026-08-11 (all 96 eval items) + a full docs/logs/
e2e/route sweep. ✅ = proven locally (named test/log); 🌐 = ALSO verified at a
live URL; 🟡 = partial (gap named); ❌ = no surface. **Read the deployment
caveat at the bottom — most ✅s are local proofs.***

**ORGANIZER / ADMIN**
- ✅ Zero→open CFP in 10 min (create → marquee → form → committee → public URL) — e2e §2
- ✅ Form builder: 6 shapes, show-when, retire/restore, live SSE preview — forms_test
- ✅ Open/close/reopen the call, domain-enforced — e2e §2c
- ✅ Speaker roster: add/edit, Invited/Confirmed, CSV import, custom fields
- ✅ Deliverables ledger → chase drafts → human send gate — 26/414 acceptance
- ✅ Files: request → v1/v2 immutable → two-sided thread → ZIP
- ✅ Manual capture of emailed/DM submissions → board as Pending
- ✅🌐 Announce speakers wall + Create Speaker (a3b, prod CI 31552709117)
- ✅ Cross-event Speaker CRM /people (CRM-08 text unrecovered, `czd`)
- ✅ Time-travel scrubber / as-of replay
- 🟡 Mission control dashboard (works; newcomer arrival sterile — `d6l`)
- 🟡 Committee roster (no in-place role change; no admin role)
- ✅🌐 Inform/comms outbox truth: every letter queues for human approval; one
  latest lifecycle row answers queued/sent/failed without duplicate authority
  (`80f0091`, prod CI 31605537239). All live event mail remains muted, so no
  external letter was sent during acceptance.
- 🟡 Schedule: blocking sheet + conflicts + Publish (no drag-drop; day tabs only)
- 🟡 Integrations (API keys/webhooks/Slack/Airtable code-complete; zero live external proof)
- ✅ Edit accepted talk / correct a created speaker (reconstructed baseline)

**CHAIR**
- ✅ Decide: 8 statuses, chair-gated in view AND at the endpoint — e2e §6b
- ✅ Assignment: assign/unassign + capped track-filtered bulk distribute
- ✅ Reviewer progress → laggard nudges (never auto-send)
- ✅ Named scorecard: criteria + weights → aggregate
- ✅ Review-scores CSV; publish gate (accepted∧informed∧conflict-free)
- 🟡 Decide→inform→letter→portal flips (proven locally; NEVER live — rehearsal 🔓)
- 🧪 Review plan + blind mode: `s8ar` is repaired and deployed at `b9fed25`;
  chair stays unredacted, reviewer stays blind, and the durable policy is the
  sole authority. Same-scenario ABS-S3 meter rerun owns final closure.
- ❌ Chair vs admin split (`qfp` ERA V; ratified in open-signup.md, unbuilt)

**REVIEWER**
- ✅ Reviewer-first landing: Assigned-to-you N-of-M, clean rail
- ✅ Board: two sorts = two work queues, filters, search, permalink
- 🧪 Quick-rate in place: one action persists stars + comment and its first SSE
  response shows both (`b9fed25`); CFP-S3 meter rerun owns final closure.
- ✅ Every reviewer's stars + full comments inline, named
- ✅ Recuse/unrecuse (denominator moves); scorecard entry; cross-event refusal
- 🟡 Blind mode (seam ✅; 77s.4 live meter unrun)
- ✅ Track-scoped default board + @mention — reconstructed and deployed

**SPEAKER**
- ✅ No-account submit: ghost-fill, draft stash, validation never eats work — e2e §4-5
- ✅ Sessionize import fills About You in place
- ✅ Portal: one-time link, own talks only, truthful status, edit-until-close
- ✅ Profile + versioned headshot; task checklist; file upload v1/v2 + thread
- ✅ Sees own day/start-end/room in portal
- 🟡 Brag/announce page (prod-verified for a cast of ONE — Mik; cc8 card deployed + accepted)
- 🟡 Co-speaker (data model yes; no invite/account flow)
- 🟡→❌ Calendar invite: ICS rides acceptance letter once; NO re-send/update path
  (a direct swyx amendment — named gap)
- ❌ Employer-approval / permission-to-announce (`r6c`; "proven; not built")

**PUBLIC / API / LLM JUDGE**
- ✅🌐 Published program: agenda/sessions/speakers/gallery (curtaincallcfp.com/program → 200)
- ✅ Star/My-schedule/my.ics; raw exports (sessions/speakers/ics/llms.txt)
- ✅ API v1 + ETag/304 + changes feed; named keys; embed builder
- ✅ Event-scoped MCP (11 tools) + CLI parity — the agent-consumer flow
- 🟡 Webhooks/Slack/Airtable sinks (never live-fired)
- 🧪 Review-policy API and chair-visible rendering are in production at
  `b9fed25`; same-scenario ABS-S3 verification is running.
- 🟡 Landing unfurl (xy4 🧪) · ✅🌐 `/cfps` + 404 doors (`b0d`, prod CI
  31561332626)
- ✅🌐 **JUDGE PERSONA SIGN-IN AT THE METER:** production `/login` renders
  Organizer·swyx, Reviewer·Maya, and Speaker·Amara; each POST returns 303 + one
  `ring-session` and `/welcome` 200 (`b9fed25`, 2026-08-12 07:41 PT).
- ❌ Filled prod state (AIE decision wave unstaged; deployed AIE: 0 sessions/0 speakers)

**TRUE GAPS (no surface at all):** chair/admin roles · employer-approval ·
re-sendable .ics · week/track/room schedule views · scheduler
constraint help · co-speaker invite · any real outbound
email ever · CRM-08 text (`czd`) · uncoached rehearsal `nuk` + evals walk `20d` unrun.

**RUBRIC VERDICT (final-rubric-audit-2026-08-11):** local ceiling ≈109.5/110
(all six areas 100%; CRM +9.5). **The codebase is not the blocker.** Score
risk has shifted from a locked door to meter closure and feature discovery:
the chair, combined rate+comment, and committee-reviewer onboarding repairs are
live at `b9fed25`; lost organizer doors are the next green batch. The stable
20506 sandbox and production share one checkpoint lineage. Discord intel: a competitor is already at
100% incl. CRM — the human "would we buy it" pass decides; judges stumble
first on loading blockers + timeouts (→ `ccn` keepalive).

## ERA I — THE SUBMISSION (P0 — nothing else matters if these miss)

```
┌────────────────────────┐  ┌──────────────────────────────┐  ┌─────────────────────────┐
│ crf  ALLOWLIST REPO 🧪 │  │ nuk  OPERATOR REHEARSALS 🔒  │  │ 20d  SWYX EVALS STUDY 🔓│
│ skiff-main lane; spec  │  │ uncoached, Ann first — the   │  │ LLM judge walks our     │
│ ratified: EXPORT model │  │ ONLY test matching the eval  │  │ flows; walk them first  │
│ → curtain-call-cfp;    │  │ 🔒 Ann's calendar            │  │ unlocks: fix what the   │
│ crf.1=`5a6` crf.2=`nuc`│  │ unlocks: the defect list     │  │ judge actually sees     │
└───────────┬────────────┘  └──────────────┬───────────────┘  └────────────┬────────────┘
            └───────────────────┬──────────┴───────────────────────────────┘
                                ▼
                    ██ SUBMIT — Wed 10PM PT ██
```
- **Acceptance-notification rehearsal** 🔓 — the Decide & Tell edge live-proven end to end (letter → portal → task list). Never exercised for real. *unlocks: the accept demo beat*
- 🧪 **GITHUB REPO PUSH (crf.2, `nuc`) — CLAIMED: Gene** — export assembled +
  meters green (crf.1 ✅: 419 files, scrub zero-hit, fresh-dir suite
  378/3839/0); staged commands in bead `nuc`. Lights when
  github.com/realgenekim/curtain-call-cfp answers 200 public. Forge mirror
  rides after.

## ERA II — THE BRAG SHEET ⭐ (`5z0` — the current research branch)

*The flip: the event's speaker wall, recomposed per-speaker, becomes an artifact
the speaker owns and shares. The acquisition loop runs both directions.*

```
✅ announce page /agenda/:slug/speakers/:id/announce   [Buster cc-announce-hero]
✅ og/twitter unfurl (headshot large-card)             [with it]
✅ speaker history badges ("2018 alum")                [Buster charlotte-speaker-vl]
✅ DRY tile fold: peers reuse featured-card            [c59ab14, 2026-08-11]
✅ program fold-in + register CTA + footer             [753aeff, 2026-08-11]
✅ Mik Kersten lit end-to-end on prod                  [operator REPL; letter rendered NOT sent]
✅ friendly slugs: /speakers/mik-kersten live,         [2026-08-11; UUID 301s to slug;
     minted at inform!, collision walk, og:url slug     og:url emits slug]
     │
     ▼
✅ RESTRUCTURE [2026-08-11, skiff-brag]: hero → LINEUP → EVENT →
     CFP-line-under-proposition → AGENDA+pitch → footer · one type scale per
     semantic level (same text, restyled — DRY) · hero-copy stutter fix ·
     SHARE STRIP (LinkedIn · X · copy link · copy post text)
✅ DEPLOYED TO PROD + meter-verified [300eb59 via workflow_dispatch, 2026-08-11
     ~16:00 — lineup/share-strip/slug URL all probed live on curtaincallcfp.com;
     CI lesson: a [skip ci] HEAD commit skips the WHOLE push event]
     │
     ▼
🧪 PROD ACCEPTANCE DRIVE — Gene at the wheel, 15-step manual checklist
     (2026-08-11 chat); findings → logs/ per house rule; `b0d` pre-noted
     as expected-fail
     │
     ▼ unlocks
✅ /admin/zoo/social-sharing (`92x`; Buster batch + skiff realtime lane) — first zoo room, reopened after the
     live visual meter exposed a broken 64×64 Slack crop and no persistent
     comparison set. Completion now requires four visible specimens — main
     homepage, CFP, speaker brag, event — with readable wide-card rendering and
     real production metadata projections; a top Jump-to TOC anchors all four
     specimens plus the trailing custom URL inspector. Buster branch
     `buster/92x-unfurl-zoo` at `17c1942` now passes browser acceptance in the
     isolated skiff review server: all eight LinkedIn/Slack images load at
     1200×630; focused 6/314/0 and full 307/3020/0. Merge `0fe6876` shipped this
     baseline to both Cloud Run services via green CI run `31563242234`; live
     metadata and all three public PNG routes passed production smoke. Child
     `92x.2` adds four X-style specimens on Buster SHA `4af4431`; Gene accepted
     the skiff browser review (four X cards, all 12 images at 1200×630), and
     merge `f12c268` shipped through green CI run `31563975729` to both services.
     Durable production artifacts are
     split into child `92x.1`:
     generate once when publication inputs change, persist content-hashed PNGs
     through the existing blob/GCS port, and serve immutable public URLs. The
     original route was verified in the
     curtain-call-staging worktree: anon GET → 302 (default-deny gate, no
     public-prefixes/scope-patterns entry needed); signed-in handler call
     (nREPL, real person) → 200 rendering LinkedIn-style + Slack-style unfurl
     cards, raw og:*/twitter:* tag table, and the og:image dimension verdict
     (500×500 Mik Kersten headshot → correctly "small square thumb") off the
     live announce page; routes-contract-test topology + view-architecture-test
     updated for the new route/namespace; full suite 580 tests/5714
     assertions/0 failures. Found + fixed a real bug en route: the host
     allowlist used a `#{}` set literal with a runtime-duplicate value,
     which throws `IllegalArgumentException` and was silently caught,
     refusing even same-host requests — replaced with `hash-set`.
✅ ANNOUNCE SPEAKERS + CREATE SPEAKER (`a3b`) — Codex-built, skiff-landed;
     Organizer Announce A0.1 Gene-accepted locally 2026-08-11, including a
     successful "Light all" mutation to 14/14 in shared production Postgres.
     2026-08-12 00:20 (commit 7d61f04-era, full suite 594/5794/0): marquee at
     14/14 on dev, adopt/create verbs live. Was:
     /events/:slug/announce (door-1 queue on top → marquee bulb wall with
     readiness glyphs told·headshot·bio → [✦ Light their page] adoption) +
     /events/:slug/speakers/new (invited-keynote path, born lit) + sidebar
     rows in all 4 derivations ("Announce Speakers (lit/total)", "Create
     Speaker" under Decide & tell). Doctrine: inform = the hard gate (our
     act); announce-readiness = per-speaker checklist; prepare lives INSIDE
     the second tell. Subsumes ANNOUNCED-SPEAKER IDENTITY (the 14 wall names).
✅ **INLINE EDIT SPEAKER DETAILS** (`1dv`; realtime Codex lane) — native
     "Edit details" disclosure directly beneath each Announce row, matching
     Review Board Quick rate—not a modal. Name, job title/tagline,
     organization, headshot URL, and bio persist through one append-only
     event.program-speaker-updated fact and project consistently across the
     public program and speaker pages. Gene production-accepted the flow by
     changing Hendrickson through localhost and observing the result on
     production. Shipped as `1d2e1bf`; CI `31564655024` tested, deployed,
     promoted both services, and passed live verification.
◌ **ENABLE CANONICAL TALK-TITLE EDITING FROM ANNOUNCE** (`1dv.1`) — follow-up
     split from the accepted speaker-details slice. Replace the currently
     disabled Save title control with an event-scoped organizer command that
     appends the existing submission.answers-updated fact, keeping Board,
     detail, program, exports, and public announce DRY.
✅ GENERATED OG CARD 1200×630 (`cc8`) — production-accepted 2026-08-12.
     Graphics2D + bundled Fraunces/SourceSans3; publishable-gated PNG route;
     adaptive word-safe title layout preserves Mik's full title; content-keyed
     server/ETag cache; announce metadata uses `?ts=<epoch-ms>` so browser and
     social-unfurl caches receive a fresh URL. Gene verdict: “works!” Local
     302/2961/0; staging `7917bbf`; CI 31562622418 tested, deployed/promoted
     both Cloud Run services, and live-verified production.
✅ RICH SPEAKER ARCHIVE SIGNAL (`3g9`) — production-accepted 2026-08-12.
     The shared featured-card treatment is “(YEAR alum - see archive)”; all seven
     Charlotte alumni deep-link to their exact IT Revolution speaker profiles in
     a new tab (`target="_blank"`, `rel="noopener"`). JD Black is correctly 2023;
     Elisabeth Hendrickson begins in 2014 with four archived talks. Curly-quoted
     names normalize safely across every surface. Live cards render 112×112
     headshots. Local 311/3068/0; combined staging `c67bd75`, CI 31565139934
     tested, deployed/promoted both Cloud Run services, and live-verified; direct
     production probe confirmed all seven anchors. Unlocks: archive discovery
     from the public program without duplicating the video-library profiles.
🧪 AGENT SPEAKER ENRICHMENT (`jif`) — ratified, staged for next free codex lane. Was: 🔓 — find headshot/title/org/bio: ITRev corpus
     → CRM projections → linkedin-intelligence lane (budgeted, agent-side ONLY,
     never in-app scraping); headshots → curtaincallcfp-public GCS; prefills
     Create Speaker, Ann approves
✅ 404 TWO DOORS + CLEAN CFP DISCOVERY (`b0d`) — production-accepted
     2026-08-12 (CI 31561332626, staging `cb3e5de`): primary "talks that made
     it" → THAT event's `/program`; secondary "find your next stage: CFPs open
     now →" → `/cfps`. Discovery excludes archived/unlisted/closed calls; six
     active test/replay events were reversibly unlisted through the domain verb;
     the two surviving real calls carry organizer pills. Prod meters: clean
     `/cfps` + Charlotte two-door 404.
✅ CFP DISCOVERY CARDS + PROGRAM GALLERY (`5lq`) — production-accepted
     2026-08-11 (feature commit `732e265`; final staging head `78a9549`): event
     cards + date brackets + deadline emphasis;
     each event reuses the `/program` public roster and shared `featured-card`
     renderer, capped at eight, with the gallery omitted when empty. CI
     31564748815 tested, promoted both Cloud Run services, and passed the live
     meter; Gene accepted the rendered production composition.
```

## ERA III — THE SHOW (what the judges actually see; quick wins < 1hr each)

- 🧪 **EXPLAINABLE REVIEW POLICY** (`77s`; Buster `cfp3-surgeon`, `.1` active) — ratified
  event-scoped policy algebra + always-visible Review Board policy pane/state
  pill for all three modes (`VISIBLE`, `HIDDEN`, `HIDDEN UNTIL VOTE`) +
  per-reviewer vote→identity reveal + chair before/after preview + public CFP
  promise. One canonical projection powers public policy collection/detail GET,
  chair-authorized version-fenced PUT, complete allowed-mode definitions and
  idiosyncrasies, deterministic audience explanations, API docs, `llms.txt`,
  and read-only `get_event_policies` MCP. Existing `review.blind-mode-set`
  remains readable through a compatibility fold; no new sidebar destination.
  **Bulbs:** ◌ policy algebra/version · ◌ Board/CFP experience · ◌ API/LLM
  surfaces · ◌ adversarial two-reviewer live meter. *unlocks: a judge-visible
  trust demo and an agent that can explain exactly how this event reviews.*
- 🧪 **LANDING + MANIFESTO OG UNFURL** (`xy4`; Codex lane) — the front door
  (curtaincallcfp.com) currently unfurls as a bare text link: zero og:/twitter:
  meta. Adds og card (title/description/hero image/summary_large_image) to
  landing + /manifesto on STAGING, reusing `event-og-meta`; zoo verifies the
  card + image-dimension verdict. *unlocks: every share of the root URL in the
  next 48h renders a first impression instead of forfeiting it.* The 🔓
  GENERATED OG CARD node (ERA II) later upgrades the image to A+.
- **Events home warm arrival** (`d6l`) 🔓 — newcomer's first screen is blank and sterile
- **AI headshots for demo personas** (`9ot`) 🔓 — never a broken-image square
- ~~**Blind-review adversarial check**~~ — subsumed by `77s.4`, the full
  board/search/sort/comments/exports/API/time-travel two-reviewer meter.
- **Purge e2e junk events from prod log** (`3as`) 🔓; auto-archive stale replay demos (`a8u`) 🔓
- **Replay at scale**: corrected spread corpus (`53x`) 🔓 → many-events simulation (`3ma`) 🔒 corpus first
- **More lit-up demo speakers** 🔓 — announce pages need a cast beyond Mik
- ~~**Pipeline stage strip** (`4d3`)~~ ✅ shipped+deployed 2026-08-12 (see ERA II) — was listed 🔓 here in error after shipping — ONE shared component on Board → Inform →
  Announce: same counts, current stage hot ("81 pending → 3 to tell / 12 told →
  1 of 14 lit"); ratified 2026-08-11 (inform-screen riff). Queues stay put —
  each screen keeps its verb (the Submissions-page lesson); r6c later adds
  "awaiting approval" to the Announce stage · *unlocks: whole-arc orientation*

## ERA IV — PLATFORM HEALTH (protects the demo under a judge's hands)

- **e2e lane fails closed + reads Postgres** (`hy5`, `3y3`) 🔓 — 6 checks known-false-negative
- ~~**Keep services warm** (`ccn`)~~ ✅ 2026-08-12: Cloud Scheduler warm-* jobs ENABLED */5min via make keepalive-create (status/run-now/delete targets); meter: first attempt stamped, landing 200; ping path upgrades to /ping on next deploy
- **SSE safe across tabs + Cloud Run instances** (`367`) 🔓
- **Test-loop one-shot + stale-safe** (`5ka`) 🔓; replay-test seed flake (`azm`) 🔓; retired-field flake (`7k4`) 🔓
- **Unlisted sweep: API v1 + raw exports** (`10e`) 🔓 — gates only cover HTML today
- **secrets: does2020 fallback fails loud** (`0a1`) 🔓; secrets audit (`ouz`) 🔓; rotate leaked Sessionize creds (`401`) 🔓
- ✅ **datastar-helpers published + SHA-pinned** (`yoe`) — public Git dependency
  pinned to `78d6a58a043febf47dda980c030f9f82c4f281d3`; final staging commit
  `78a9549`, 311 tests / 3061 assertions / 0 failures, CI 31564748815 green.
- **Discord re-harvest / requirements freeze** (`x8d`) 🔒 Gene's call on scope freeze

## ERA V — AFTER THE HORN (post-deadline: the ITRev-runs-real-events branch)

- **Scheduler v2**: blocking-sheet draft-first (doc exists) + bounds warnings (`bme`) → *unlocks* durable program + permanent identity URLs (`ifs` — the slug work is its down-payment) → *unlocks* .ics room-late amendments at scale
- **Speaker materials ledger — the chase list** (`uxw`) → *unlocks* PC coverage prodding (`czg`)
- **Press-kit download** (brag sheet as media kit) + missing-headshot chase prompt ("your card is 80% gorgeous")
- **Claim-your-account loop** (`ahp`); Google sign-in beside magic link (`pkx`)
- **Roles: chair vs admin** (`qfp`); employer-approval + announce-permission (`r6c`)
- **CFP funnel analytics** (`2v2`); ProgramKit polish steals (`2rj`)
- **Calendar branch**: personal speaker feed (`c2u`) → 🔒 milestone VEVENTs (`2pg`)
- **Gallery branch**: past-event photo galleries (`5yz`); hero images to GCS (`4v9`)
- **Living-event selection encapsulation** (`w5b`); API/exports carry `:slug`
- **Sol refactor merge** (`codex/sol-replay` + `bridge/sol-reconcile`) — 🔒 deadline passes → one review session: merge-or-kill
- ◌ **REPO FLIP: public repo becomes the canonical home** (ratification pending) — invert the privacy fence (private material moves to a sidecar repo; agents + CI re-anchor to `curtain-call-cfp`). Until then the public repo is an EXPORT-ONLY artifact of `crf`; nobody develops there. *unlocks: real open-source life after the contest*

---

## Changelog

- 2026-08-11 22:00 Codex — production-completed CFP cards/gallery (`5lq`),
  inline Save Speaker (`1dv`), and immutable public datastar-helpers pin (`yoe`)
  at final staging head `78a9549`; CI 31564748815 promoted both services and
  passed live verification.
- 2026-08-11 22:05 Codex — added dark homepage tool-archaeology restoration
  node `589`: bring the existing EventPower → Cvent → BusyConf → Sessionize
  wonder/history treatment from `main` back to the deployed homepage, with
  visual browser acceptance required.
- 2026-08-11 21:16 Codex — added CFP DISCOVERY CARDS + PROGRAM GALLERY
  (`5lq`) as 🧪 after Gene accepted the wider staging composition; pushed
  `732e265`, CI 31562733312 queued behind the active staging deploy.
- 2026-08-11 15:15 skiff-main — created the tree; CI pipeline, unlisted+404,
  exports story, acceptance letter all moved to Shipped same day.
- 2026-08-11 15:30 skiff-brag — merged the duplicate main-branch tree into this
  one (this file is CANONICAL; main's docs/tech-tree.md is now a pointer).
  Brag-sheet lane detailed: slugs ✅ + mik-kersten live, restructure 🧪, og-card
  + announced-speaker-identity unlocks named; /zoo (`92x`) added.
- 2026-08-11 15:40 skiff-brag — Gene ratified the ERA treatment; tree
  restructured into ERA 0–V (built city → submission → brag sheet ⭐ → the
  show → platform health → after the horn). All nodes and bead ids preserved.
- 2026-08-11 skiff-brag — RESTRUCTURE moved 🧪 → ✅ (section order
  hero→LINEUP→EVENT→agenda, hero-copy stutter fix, typography unification,
  share strip live at /agenda/enterprise-ai-summit-charlotte-2026/speakers/
  mik-kersten/announce; verified via kaocha announce-test + speaker-slug-test,
  0 failures). FRIENDLY SLUGS already ✅ from the parallel lane.
- 2026-08-11 16:05 skiff-brag — ERA II RESTRUCTURE meter-verified ON PROD
  (deploy 300eb59 via workflow_dispatch; lineup + share strip + slug URL live;
  learned: a [skip ci] HEAD commit skips the WHOLE push event — dispatch or a
  code commit must ride last).
- 2026-08-11 16:25 skiff-brag — ERA II: zoo (`92x`) 🔓→🧪 (zoo lane, verify
  phase); ANNOUNCE SPEAKERS + CREATE SPEAKER (`a3b`) added 🧪 (Codex lane,
  subsumes announced-speaker identity); agent enrichment (`jif`) added 🔓;
  404 two-door design ratified on `b0d`. light-up-tech-tree skill authored.
- 2026-08-11 zoo agent (sonnet) — zoo (`92x`) 🧪→✅: /admin/zoo/social-sharing
  built + verified in curtain-call-staging (LinkedIn/Slack unfurl previews,
  raw og:*/twitter:* table, og:image dimension verdict, sharing tips); fixed
  a `#{}` duplicate-key host-allowlist bug found during verify; routes-
  contract-test + view-architecture-test updated; full suite green
  (580/5714/0).
- 2026-08-11 Codex — planted BLIND REVIEW TRUST DEMO + POLICY API as a dark
  `◌` node before ratification: existing event-sourced blind-mode seam recorded;
  reveal-after-vote, policy GET/set, LLM explanation, and adversarial meters
  remain proposed. No implementation lane or bead authorized yet.
- 2026-08-11 16:45 skiff-main — Buster-inventory audit closed two gaps: added
  committee acceptance-notify emails [cc-notify, merged-but-MUTED] to the
  committee room, and THE FRONT DOOR block (welcome/login surface + judge demo
  personas) to ERA 0. Every forge-bridge/Sol/codex workstream now has a node.
- 2026-08-11 16:13 Codex — Gene ratified EXPLAINABLE REVIEW POLICY; planted
  outcome `77s` with four vertical slices (`.1` algebra → `.2` Board/CFP + `.3`
  API/LLM → `.4` adversarial live meter), moved `◌`→`🔓`→`🧪`, claimed `.1`,
  and subsumed the smaller blind-review adversarial node into `77s.4`.
- 2026-08-11 16:30 Codex — migrated `77s` execution to Buster: published the
  narrow `.1` checkpoint on `feature/cc-explainable-policy` at `a7c1c41`, put
  the continuation contract and test receipt in Beads, and stopped local JVM
  work. Integration remains Codex-owned after Buster returns a green SHA.
- 2026-08-11 16:36 Codex — activated Buster tmux `cfp3-surgeon` in isolated
  checkout `~/src/cc-explainable-policy-buster`; its independent `.1` focused
  proof is green (10 tests, 79 assertions) and its full suite is running.
- 2026-08-11 17:15 skiff-main — Gene ratified the `crf` allowlist-repo spec
  (light-up-tech-tree walk): EXPORT model — public repo `curtain-call-cfp` is
  a manifest-driven artifact, fresh history, no shared git plumbing with the
  private repo; NO ethnographies; gists/published links KEEP. crf 🔓→🧪
  (skiff-main lane; children `5a6` assemble+scrub, `nuc` Gene push+Forge).
  Planted ERA V ◌ REPO FLIP dark node. Discord re-harvest `x8d` unlocked by
  Gene → 🧪 (background browser agent).
- 2026-08-11 17:05 skiff-main — crf.1 (`5a6`) CLOSED, meters green: export at
  ~/src.local/curtain-call-cfp-public (419 files, fresh history, 0 remotes);
  scrub zero-hit; fresh-dir suite 378/3839/0. Found: clj-kondo lints 0 files
  silently without a .clj-kondo dir; CI parked in export. crf stays 🧪 until
  Gene runs crf.2 (`nuc`: gh repo create + push + Forge). x8d BLOCKED: Discord
  session expired — needs Gene's re-login in Chrome.
- 2026-08-11 17:25 skiff-main — planted + claimed LANDING/MANIFESTO OG UNFURL
  (`xy4`) 🧪 in ERA III (Codex lane, staging worktree): root URL has zero og
  meta today; spec reuses event-og-meta, zoo is the meter.
- 2026-08-11 18:05 skiff-main — Discord harvest (`x8d`) 🧪→✅ (309 msgs + 6
  threads → docs/discord/2026-08-11-general.md + intel-summary; verified on
  disk). Intel: submission form NOT YET POSTED (watch #announcements Wed AM);
  StageStack at 100% evals incl optional CRM; OpenSession = closest feature
  rival; 3 swyx amendments (emails optional, agenda = tracks/rooms/slots,
  Airtable bonus-only); eval judges hit loading blockers/timeouts first.
- 2026-08-11 16:55 skiff-brag — added Pipeline stage strip (`4d3`) 🔓 to ERA III
  (ratified in the inform-screen riff; queues stay put, strip orients).
- 2026-08-12 00:25 skiff-brag — landed the announce lane + pipeline cascade
  (4d3: strip on Board, cascade as Inform's empty-state with ball-ownership
  chips, native <details> expand, 4tv polish); portal test migrated to the
  ratified surface; pushed 0224305 → CI deploy in flight; prod meter check
  chained. Lesson recorded: `| tail` masks kaocha's exit code — read the
  failure COUNT line, never the pipe's exit status.
- 2026-08-12 00:40 skiff-brag — DEPLOYED TO PROD + meter-verified (CI
  31552709117 test✅ deploy✅, 0224305): announce+create routes live (302
  gated), cascade on inform, public brag page intact. Beads 4d3/4tv/a3b closed.
- 2026-08-11 18:15 skiff-main — GITHUB REPO PUSH (crf.2/`nuc`) claimed by GENE
  as its own ERA I node 🧪; meter probed: 404 (not yet pushed). Lights at 200.
- 2026-08-12 00:55 skiff-brag — packet 1 ratified → cc8 (+.1/.2/.3 chain)
  filed, fonts staged in resources/fonts, Codex lane launched. Packets 2
  (jif enrichment) + 3 (b0d two doors) awaiting Gene's opens ②③④.
- 2026-08-12 01:20 skiff-brag — b0d 🧪 (Buster cfp3-surgeon), jif 🧪 (staged),
  4d3 ERA-III listing corrected to ✅. NOTE: cfp3-surgeon pane now queues for
  TWO lanes (77s + b0d) — coordinate before sending.
- 2026-08-12 01:35 skiff-brag — ccn ✅ (Makefile keepalive lane, jobs live +
  meter-verified). 20d claimed next by skiff main loop.
- 2026-08-12 01:45 skiff-brag — GENE'S COCKPIT section added at top: acceptance
  test plan with URLs, Gene-only actions, pending decisions, blockers.
- 2026-08-12 02:55 skiff-brag — cc8 ✅(dev) + b0d ✅(dev) merged on staging
  (928d732; suite 602/5878/0); live-meter required a dev-server restart
  (auth.clj middleware change — the documented hot-reload boundary) + strict
  reload order. 77s.2 pill finisher dispatched to Buster (main lineage).
  Deploy + main/staging reconciliation now Gene decisions in the cockpit.
- 2026-08-12 03:05 skiff-brag — 77s.2 pill grammar delivered on
  buster/77s2-pill-grammar @16ea0fa (4/54/0 focused, 383/3897/0 full);
  awaits main-line integration + Gene's visual acceptance.
- 2026-08-11 20:27 Codex — planted EDIT SPEAKER + TALK DETAILS as a dark ERA II
  node after inspecting Mick's accepted record, Announce/Create/Capture, the
  hidden speakers table, and orphan cc-editspeaker; realtime browser-open
  ratification pending.
- 2026-08-11 20:34 Codex — Gene ratified `1dv`: Announce-native inline edit
  disclosure like Quick rate, separate speaker/title forms, no modal; node
  moved ◌→🧪 on the browser-serving staging lane.
- 2026-08-12 02:05 skiff-main — added THE FLOWS section (persona journeys ×
  done/not-done, audited from final-rubric-audit + full docs/logs/e2e sweep)
  + rubric verdict: local ceiling ≈109.5/110, risk is ALL operational (stale
  judge personas at the live meter, empty prod fixtures, main/staging/deployed
  divergence). True-gaps list named (chair/admin, re-sendable .ics,
  employer-approval, edit-accepted-talk, cc-tracks/cc-mention orphans…).
- 2026-08-11 20:52 Codex — live Chrome review regressed social zoo `92x` ✅→🧪:
  speaker brag Slack specimen crushed 1200×630 art into 64×64; homepage had no
  social tags; CFP/event shared 1080×422 hero failed big-card eligibility.
  Four-specimen repair dispatched through `run-on-buster` at immutable
  928d732 while the skiff retains the realtime browser acceptance lane.
- 2026-08-11 21:17 Codex — `92x` browser acceptance green on Buster SHA
  `17c1942`: TOC + four permanent specimen types, eight LinkedIn/Slack images
  all complete at 1200×630. Live drive caught and repaired the Reitit
  `:kind.png` parameter bug with a real-router regression test. Added `92x.1`
  for generate-on-change, content-hashed GCS social-card artifacts; canonical
  integration still gates 🧪→✅.
- 2026-08-11 21:30 Codex — LinkedIn/Slack baseline shipped as merge `0fe6876`;
  CI `31563242234` tested, deployed, promoted both services, and verified live.
  Production smoke proved root/CFP/program/Mik metadata and homepage/event/
  speaker PNG routes. X follow-up `92x.2` delivered by Buster at `4af4431`
  (focused 4/72/0; full 307/3032/0); skiff browser found four X cards and 12/12
  images at 1200×630. Awaiting Gene's visual acceptance before shipping X.
- 2026-08-11 21:45 Codex — Gene accepted X; merge `f12c268` shipped to both
  services through CI `31563975729`, then live verify passed. The identical CI
  commit hit `store-test/sink-filtering-test`'s known ordering race twice before
  passing unchanged on the third run; durable bug `0e4` records the required
  synchronization fix. Zoo `92x` and X child `92x.2` 🧪→✅.
- 2026-08-11 21:58 Codex — `1dv` 🧪→✅ after Gene changed Hendrickson through
  the Announce inline editor and saw the shared result on production. Shipped
  as `1d2e1bf`; CI `31564655024` passed tests, both deploys/promotions, and live
  verification. Split the still-disabled canonical talk-title save into
  durable child `1dv.1` rather than overstating the accepted slice.
- 2026-08-12 03:54 Codex — `b0d` 🧪→✅ on production: `/cfps` now excludes
  archived/unlisted/closed calls, six active test/replay events reversibly
  unlisted, organizer pills added; prod shows only Enterprise AI Summit + AI
  Engineer Code Summit. Charlotte unpublished-speaker 404 exposes both correct
  doors. Local suite 301/2949/0; CI 31561332626 test+both deploys+promotions+
  live verify green at staging `cb3e5de`. Ordered cockpit review URLs added;
  social zoo gallery queued next as `1no`.
- 2026-08-12 04:18 Codex — `cc8` 🧪→✅ on production after Gene rejected the
  mid-word `Ente…` truncation: adaptive layout now renders the complete Mik
  title on three word-safe lines. Unfurl image URLs use `?ts=<epoch-ms>` while
  server bytes remain content-cached. Gene accepted the corrected localhost
  card (“works!”); commit `7917bbf`; local 302/2961/0; CI 31562622418 green
  through tests, both deploys/promotions, and live verification. Production
  PNG + timestamped metadata rechecked; project-wide cache-bust invariant added
  to `CLAUDE.md`.
- 2026-08-12 04:23 Codex — made A0 a localhost-only, decision-only queue:
  removed the production-shipped CFP discovery, two-door 404, and generated-card
  reviews; corrected every remaining acceptance link to localhost:20501. Added
  the same cockpit-pruning invariant to the `light-up-tech-tree` skill and the
  tree maintenance protocol.
- 2026-08-12 04:27 Codex — Gene accepted the Mik speaker brag page; confirmed
  it was already in the `7917bbf` production image and survived the green
  `732e265` redeploy (CI 31562733312). Production returned 200 with hero,
  lineup, event, agenda, share strip, and `card.png?ts=` metadata. Removed the
  completed review from A0 immediately.
- 2026-08-12 04:31 Codex — planted dark RICH SPEAKER ARCHIVE SIGNAL proposal
  beside the existing alumni enrichment. Filed/claimed `3g9` for the separately
  ratified deep-link slice; verified all ten curated speaker archive URLs return
  200. Visual treatment remains ratification-pending.
- 2026-08-12 04:44 Codex — Gene accepted A0.1, the Charlotte public-program
  story. Removed it from the decision-only cockpit queue, marked the matching
  production acceptance-drive step complete, and stamped the ERA 0 `/program`
  node.
- 2026-08-12 04:46 Codex — Gene accepted the renumbered A0.1 Organizer
  Announce wall locally and used "Light all" successfully. Port 20501 was
  verified as `make server-dev` from `curtain-call-staging`, so the action
  mutated shared production Postgres; the marquee reached 14/14. Removed the
  completed review from the cockpit without claiming production-URL acceptance.
- 2026-08-12 04:48 Codex — Gene accepted the renumbered A0.1 Inform Speakers
  surface locally: Decide → Tell works and mail remained muted. Removed it from
  the cockpit. Rewrote the Review Board item as explicitly blocked: the `77s.2`
  pill is only on Buster's remote branch, so it must integrate into `main` and
  then reconcile into `staging` before localhost:20501 can be reviewed.
- 2026-08-12 05:07 Codex — closed `3g9`: Gene ratified the shared “(YEAR alum -
  see archive)” treatment and accepted Topo + Yegge links; JD corrected to 2023,
  Elisabeth to 2014/four talks, and all seven Charlotte alumni now open their
  exact speaker archive in a new tab. Local 311/3068/0; the first CI attempt hit
  unrelated async-order flake `bzc`, then combined staging `c67bd75` passed CI
  31565139934, deployed/promoted both services, and production-probed seven of
  seven exact anchors. Node lit ✅; no completed item remains in Gene's cockpit.
- 2026-08-12 07:17 Codex — shipped the approval-gated email outbox and working
  CFP portal link at `80f0091` via CI `31605537239`; browser acceptance proved
  one truthful lifecycle row and safe dev-log dispatch, and production
  outside-in doors passed for swyx/Maya/Amara. CFP-S2 then closed `95cx` and
  `wmw3` at the meter: Priya's generated link opened her portal and edit
  survived reload; title, abstract, and Track survived validation + next click.
  Removed shipped work from Gene's cockpit; `s8ar` and reviewer v2 `e2c0171`
  are the next agent-owned P0s.
- 2026-08-12 07:44 Codex — deployed reviewer v2 plus `s8ar` at `b9fed25` via
  CI `31607224417`: committee reviewer onboarding, fail-closed persona switch,
  atomic rate+comment live response, truthful reviewer controls, one canonical
  presenter policy, and chair-unredacted board/detail. Full suite 464/4,790;
  candidate and production persona doors passed. Published the preserved-runtime
  20506 checkpoint and dispatched ABS-S3/CFP-S3 meter reruns. Historical audit
  then proved Speakers/CSV import, deliverables, and Files were lost nav from
  `b4b2103`, while Embed was never linked; restoration batch `13f8f67` is green
  locally at 464/4,791. CI exposed and v2 fixed one asynchronous export-version
  test boundary; `99b1751` passed CI `31608635683`, deployed, and production
  proved all four nav links/routes plus CSV import and the copyable snippet.
