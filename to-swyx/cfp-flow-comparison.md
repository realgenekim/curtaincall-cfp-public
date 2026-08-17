# CFP flow comparison: BusyConf vs. sessionize-fulcro vs. Sessionboard vs. Sessionize

## Why I care this much (Gene)

First off, Swyx, congrats on all your fantastic achievements — it was great to see you in NYC at the Code Summit, and your latest World Fair was amazing!!!

Last week, I was so excited to see this contest. I remember poking around last week on Twitter trying to figure out which tool you wanted to replace — and I was delighted to learn this morning that it's Sessionboard, a CFP management tool, which is a category of tools I've loved to hate for almost fifteen years.

Over that time, we've used five of the worst tools on the planet to run our conferences, and we were held hostage to every one of them. This is something I have a tremendous amount of passion for. 

I've literally written workarounds for this terrible category of tools since 2014 -- Trello, Zapier, Google Sheets, and an entire app to rewrite the submission review process.

On Saturday, after I read your "Kill My SaaS" posts, I did some digging.  I used Claude Code with Fable to go through more than 20,000 Slack messages from the past 12–13 years of running Enterprise AI Summit (and its predecessors: DevOps Enterprise Summit, Enterprise Technology Leadership Summit).

I wanted to put this together for you to:

- show you how important this is to me
- my goal is to get a mini-CFP launched today for the Enterprise AI Summit in Charlotte, NC (Oct 7-8) -- our existing process and tools were just heavy enough of a lift to make me, until yesterday, say "screw it."
- Swyx, I have some questions for you and your team — maybe this is a tool that you'll want to use.  If it differs sufficiently from my process, maybe it won't
- in the case that it works for you, I'm all in on this competition — even though I'm starting one day late — and if we win, I'll donate the $10K prize to STEM charity
- and I wanted to share the 12+ year history of the tools (almost all terrible, one was amazing but went out of business, and one is tolerable, but I ended up writing a whole new front-end by scraping its contents) — and I wanted to share some of the interesting economics of this software category


# Review flows, visualized

*Each diagram traces one proposal from submission to decision — where the opinions live, and what a reviewer actually has to do. Talk titles, speakers, and comment authors are fictional; the snark is real.*

## First, the full lineage (per the 10-year PC Slack archive)

Not three tools — **five**, plus satellites, plus two total collapses back into general-purpose software (Google Sheets, Trello):

```
2016 ──── 2017 ──── 2018 ──── 2019 ──── 2020 ──── 2021 ──── 2022 ─── ⋯ ── 2025 ──── 2026
  eventPower          Cvent      ╔═══ BusyConf ═══╗   Sessionize + scraper +      Basecamp
  (+Basecamp PC       (+Trello   ║   the beloved  ║   fulcro front-end           (submissions
   comms, +Trello      bolt-on,  ║      era       ║   ("because Sessionize        arriving by
   for ranking)        mid-year  ╚════════════════╝    review is so #@$#")       DM + email)
      │                rescue)        │  died 2023:        │                          │
      ▼                   ▼           ▼  Heroku repricing  ▼                          ▼
   "rank-ordered      "refer to    vendor patched      committee: "Gene's         "Hello,
    list of           them by      the app WHILE       magic sessionize           2000s!! 😂"
    recommend-        Google       ON THE CALL         link"
    approvals"        Sheets row"
────────────────────────────────────────────────────────────────────────────────────────────
constants across ALL eras: Google Sheet "Schedule Blocking" (the real schedule builder,
10 straight years) · slot arithmetic done live in Slack · weekly call = the decision
instrument · Ann/Jess re-provisioning access every single year
```


## eventPower (2016–2017) — review as tally

```
   speaker submits ──▶ eventPower pool
                            │
        committee clicks "recommend approval" per submission (a thumbs-tally,
        not a score) ──▶ rank-ordered list by # of recommend-approvals
                            │
                            ▼
   the REAL work happens elsewhere:
   Basecamp thread (PC discussion) + Trello board (ranking the accepted)
   "We will select 15 talks, of which 9 get immediate acceptance letters —
    the rest will be waitlisted"
```
- Shape: a turnstile counter.** The tool could count hands but not host an argument — so the argument moved to Basecamp on day one. Foreshadowing.

## Cvent (2018) — review as archaeology

```
   speaker submits ──▶ Cvent (slow) ──▶ committee attempts to review…
        ✗ NO PERMALINKS: "to talk about submissions, refer to them
          by Google Sheets row"                    ← actual instruction
        ✗ reviewers needed SECOND EMAIL ACCOUNTS just to log in
        ✗ fields the CFP asked for were INVISIBLE to reviewers
          ("it is crazy that we request that information but that
           none of you can view it")
        ✗ sheet export truncated abstracts
              │
              ▼ mid-season rescue:
   ┌─────────────────────────────────────────────┐
   │ TRELLO BOLT-ON: one card per shortlisted    │
   │ talk; committee "voted by putting color     │
   │ labels on cards"                            │
   └─────────────────────────────────────────────┘
              │
              ▼ (the Cvent→BusyConf migration then lost the attached PDFs)
```
- Shape: a wall with a hole chewed through it.** The committee tunneled out to Trello + a spreadsheet within months. Lesson carved in stone: **the permalink is the atomic unit of committee discourse** — a proposal you can't link to is a proposal you can't discuss.

## BusyConf — review as broadcast conversation

```
                              ┌─────────────────────┐
   speaker submits ──────────▶│  proposal created    │
   (1 page, no account)       └──────────┬──────────┘
                                         │ instantly, in full
              ┌──────────┬───────────┬───┴──────┬───────────┬──────────┐
              ▼          ▼           ▼          ▼           ▼          ▼
           ┌─────┐    ┌─────┐    ┌─────┐    ┌─────┐    ┌─────┐    ┌─────┐
           │ PC1 │    │ PC2 │    │ PC3 │    │ PC4 │    │ PC5 │    │ ... │   9 inboxes
           │ 📧  │    │ 📧  │    │ 📧  │    │ 📧  │    │ 📧  │    │ 📧  │   EMAIL = the app
           └──┬──┘    └──┬──┘    └──┬──┘    └──┬──┘    └──┬──┘    └──┬──┘
              │  reads over coffee: title, abstract, ALL fields,      │
              │  bio, headshot, "Notes to the Planning Committee"     │
              │          [ View, Comment, and Rate » ]  ← one button  │
              ▼                                                       ▼
        ┌───────────────────────────────────────────────────────────────┐
        │  THE PROPOSAL'S COMMENT THREAD (shared, append-only)          │
        │  PC member: "Vendor pitch IMHO"                               │
        │  PC member: "I spoke with the speaker just now — leaning no"  │
        │  PC member: "[pc] Agree — but could we land their CTO?"       │
        │      ▲ every comment re-emails everyone · reply-by-email      │
        └──────┴──────────────────────────────┬────────────────────────┘
              private ratings (per organizer) │ consensus emerges IN the thread
                                              ▼
                                     accept ──▶ drag onto schedule grid
```
- Shape: a star that converges.** Zero navigation cost to start reviewing; the discussion IS the record.

In fact, at one point there was a proposal for us to *buy* one of the few tools we ever loved — BusyConf — because it was going out of business, a casualty of Heroku sunsetting the platform it ran on. We passed, it died, and we've been paying for that decision ever since. What follows is the archaeology.

## Sessionize — review as isolation booths

```
   speaker submits (account wall, multi-step)
        │
        ▼
   sessions list ── paginated, ~20/page, click… click… click…
        │
        ▼
   ┌────────────────── "EVALUATION PLAN" (create one first!) ─────────────────┐
   │                                                                          │
   │   ┌────────┐      ┌────────┐      ┌────────┐      ┌────────┐            │
   │   │ booth  │      │ booth  │      │ booth  │      │ booth  │            │
   │   │ PC 1   │      │ PC 2   │      │ PC 3   │      │ PC 4   │  each rates│
   │   │ ★★★☆☆ │      │ ★★★★☆ │      │ ★★☆☆☆ │      │ ★★★★★ │  ALONE     │
   │   └───┬────┘      └───┬────┘      └───┬────┘      └───┬────┘            │
   │       └───────────────┴───────┬───────┴───────────────┘                 │
   │                               ▼                                          │
   │                ┌───────────────────────────────┐                        │
   │                │  aggregate stats… rendered in  │  ← click a button      │
   │                │  a POPUP IFRAME per session    │    per session         │
   │                └───────────────────────────────┘    to see them         │
   └──────────────────────────────────────────────────────────────────────────┘
        │                                    ▲
        ▼                                    │ so ITRev built…
   status: Nominated → Accept Queue     ┌────┴─────────────────────────────┐
                                        │ 654-line headless-Chrome scraper │
                                        │ ×12h cron, 1,700 Actions runs,   │
                                        │ creds in source, selector drift  │
                                        └──────────────────────────────────┘
```
- Shape: parallel silos + a stats afterthought.** Comparison and conversation both require leaving the tool. Terminal symptom: Spring EAIS 2026 ran its CFP in
- Basecamp** instead.

## sessionize-fulcro — review as shared spreadsheet-of-truth

```
   [Sessionize] ──scrape (cron ×12h, "I'll kick off a run at 10am")──▶ workshop.edn
                                                                          │ GCS
                                                                          ▼
   ONE PAGE, 400 rows — built to the no-click doctrine, coined on-mic while
   pair-building it: "I want it all to show up WITHOUT ANY CLICKS"
   ┌──────────────────────────────────────────────────────────────────────┐
   │ ⬦ Nominated │ From Ticket Queue   │ track │ R. Chen  │ n=5 │ x̄ 3.8 │ │
   │             │ to Platform Team    │       │          │     │        │ │
   │   Gene 4.5 (Rank 3) · Dana 2.0 · Priya 4.0 · Sam 5.0 · Ann 3.5      │
   │   Dana: "Pitch used a lot of the right words…"         [View] [Rate] │
   ├──────────────────────────────────────────────────────────────────────┤
   │ ⬦ Accepted  │ Shipping the Un-    │ …    every score · every comment │
   │             │ shippable Monolith  │      preloaded inline · sortable │
   └──────────────────────────────────────────────────────────────────────┘
        + pivot: BY REVIEWER (coverage 23/47, personal ranked list)
        ✎ writes still happen in Sessionize (deep links) — read-only lens

   THE USAGE MODEL (the two sorts ARE the two work queues):
                                          weekly PC call
   sort by # RATINGS ascending ──▶ "who still needs reviews?"      ┐
     (Gallimore's rule: every           = the COVERAGE worklist    │ round-robin
      submission deserves 2 reviews;                               ├─ lobbying →
      "all 342 have at least two!")                                │ consensus →
   sort by AVG STARS descending ──▶ "who do we accept next?"       │ Ann marks
     (secondary sort: n, via juxt)      = the DECISION queue       ┘ accepts
   ...then reload after the 10am scrape, [Rate] deep-link to enter stars,
   comments visible inline so the call spends zero time reciting them
```
- Shape: a table you scan, not a queue you process.** The mean AND the disagreement AND the why, simultaneously — and coverage-without-bureaucracy (deliberately NOT the NeurIPS machine: no assignments, no rounds; the lightest mechanism that guarantees every submission got eyes). Its flaw: it could only *watch* Sessionize, never replace it.

## Basecamp (Spring EAIS 2026 — the revealed preference) — review as pure conversation

```
   committee posts ──▶ one thread per speaker candidate
                       "(2026 Spring EAIS) J. Rivera: ObservCo"
                            │
                            ▼
                       replies accrete: "I am unsurprisingly very interested
                       in this" · "I want to read this book today" — everyone
                       sees everything, zero structure
                            ✗ no fields  ✗ no scores  ✗ no statuses
                            ✗ no schedule  ✗ no speaker portal
```
- Shape: BusyConf minus the software.** Gene's correction (2026-08-08) on what this actually reveals: it was **activation energy, not preference** — "I just didn't want to spin up a full CFP in Sessionize, because of the awfulness and heavier lift. It's possible that if we had this tool, and flipping it on in 10m was possible, we'd have gone that route!" And the crispest JTBD statement in the whole archive: "**I just wanted opinions from PC!**" The datum: for a small event, the cost of standing up a CFP in Sessionize exceeded the value of having one, so the CFP *didn't happen at all* — submissions leaked in through DMs and email. Product requirement, first-class: **zero-to-open-CFP in ten minutes** (create event → default form → public URL live). The tool that's cheap enough to flip on for a one-day summit never loses to Basecamp.

## Sessionboard — review as org chart

```
   speaker submits (5-step wizard, signup wall at step 2)
        │
        ▼
   Program ▸ Abstracts (the pool)
        │
        ▼
   ╔══════════════ EVALUATION ADMINISTRATION ══════════════╗
   ║  create Evaluation Plan(s)                            ║
   ║       │                                               ║
   ║       ├──▶ define Committee A ──▶ assign subset ────┐ ║
   ║       ├──▶ define Committee B ──▶ assign subset ──┐ │ ║
   ║       └──▶ configure Rounds (R1 → R2 → …)         │ │ ║
   ╚═══════════════════════════════════════════════════│═│═╝
                                                       ▼ ▼
                                    evaluator logs in, sees MY QUEUE (23 of 118)
                                    scores each in sequence, one at a time
                                                       │
                                                       ▼
                                    admin views rollup dashboard → accepts
                                                       │
                                                       ▼
                              speaker portal flips to "Accepted" + task checklist ✓
```
- Shape: a pyramid.** Built for 50 reviewers who don't know each other. swyx's actual committee is a handful of trusted people — the machinery is pure overhead (and slow: three complaints in a nine-minute video).




What that administration actually looks like (screenshots from swyx's walkthrough,
published in [kill-my-saas-reference](https://github.com/realgenekim/kill-my-saas-reference)):

![Sessionboard form builder — the multi-step setup](https://raw.githubusercontent.com/realgenekim/kill-my-saas-reference/main/screenshots/02-form-builder-setup-steps.png)

![Sessionboard abstracts review board](https://raw.githubusercontent.com/realgenekim/kill-my-saas-reference/main/screenshots/05-abstracts-review-board.png)

![Sessionboard evaluation dashboards](https://raw.githubusercontent.com/realgenekim/kill-my-saas-reference/main/screenshots/10-dashboard-evaluations.png)

## The folk tool everyone starts with — Google Form → Spreadsheet

*Verdict at the end.*

*Note: talk titles, speaker names, and comment attributions below are fictionalized — the snark is verbatim-grade, the targets are invented. Raw sources stay in docs/ (private).*

## The four flows


## Academic tier — HotCRP / EasyChair / OpenReview (the far pole)

```
   paper ──▶ bidding ──▶ assignment (3 reviewers/paper, conflicts checked)
          ──▶ independent double-blind reviews (structured scores + text)
          ──▶ author rebuttal ──▶ reviewer discussion (per-paper, gated)
          ──▶ PC meeting / meta-review ──▶ decision
```
- Shape: due process.** Justified when reviewers number in the hundreds and fairness-at-scale is the product. This is the tradition Sessionize/Sessionboard unconsciously imitate — for committees of nine who all read everything anyway.

### 1. BusyConf (2010–2023) — "review comes to you"

```
SPEAKER                    THE SYSTEM                       COMMITTEE (all 9, no assignments)
───────                    ──────────                       ─────────────────────────────────
one-page /proposals/new    New Proposal email ──────────▶   FULL proposal lands in inbox:
no account required   ──▶  (everything inline)              title, abstract, every custom
markdown abstract                                           field, speaker bio + headshot,
+ "Notes to the Planning                                    prior-talk video link, comments
  Committee" (private) ──▶ one orange button:               so far. Read it over coffee.
+ co-speakers (repeatable  [View, Comment, and Rate »]           │
  block, avatar upload)                                          ▼
                           comment ⇄ email loop:            rate privately (per-organizer),
speaker can comment   ◀──▶ every comment re-emails all;     comment publicly-to-committee
on own proposal            reply-by-email works;            ("[pc]" convention for
                           "Click here to reply"            committee-only remarks)
                                 │
                                 ▼
                           accepted → activity → drag-n-drop onto proportional
                           schedule grid (pixels_per_minute) → published at
                           <event>.busyconf.com/schedule + /mobile (offline-capable
                           HTML5) + embed — "hosted forever"
```

Everything in ONE Rails app, one event record, three surfaces (manage console / public event subdomain / marketing). Speaker self-service since 2011: "Speakers can keep their bios, avatars, and other info up to date... so you don't have to." Business model: tools free, fee on tickets (3%/2.5%/1.5% tiers) — which is why a CFP-only customer like late-era ITRev was economically unsustainable, and why it died.

### 2. sessionize-fulcro (Gene's 2022 corrective) — "all opinions on one page"

```
Sessionize (system of record, no API)
   │  headless-Chrome scraper, GitHub Actions cron ×12h, checkpointed batches
   ▼
workshop.edn → GCS → Fulcro RAD app (read-only lens):
   ONE dense page, 400 rows: two <tr> per submission —
   row 1: status │ title │ org-size │ track │ submitter │ role │ n │ MEAN │ n-comments │ [View][Rate]
   row 2: "Gene Kim 4.5 (Rank 3)" · "Dana 2.0" · every reviewer's comment, inline
   + pivot-by-reviewer view (coverage, personal ranked list)
   deep links BACK into Sessionize to actually rate/change status
```

Never collapses reviewers into a number; one page beats a wizard; roster derived from data. Its costs: the scrape (creds in source, selector drift, iframe-scraped ratings, "Still scraping. Three minutes in."), no writes, no speaker profile shown inline, no per-reviewer identity in-app.

### 3. Sessionize ($499/event) — "the org-chart in software"

```
speaker: account wall → multi-step submission → per-event profile
organizer: sessions list (paginated ~20/page) → EVALUATION PLANS (create plan,
  add evaluators, rate in isolation, stats live in a POPUP IFRAME) → statuses
  (Nominated / Accept Queue / Accepted / Declined) → agenda builder → embeds
API: attendee-facing embed JSON only — NO organizer/evaluation API
```

What it cost us in practice: the entire scraper toolchain (654-line CDP scraper, 7 GitHub Actions workflows, "1,700 GitHub action jobs that ran of me scraping"), generic category slots repurposed (track≈org-size shuffle hardcoded downstream), and enough accumulated friction that **for Spring EAIS 2026 Gene ran the mini-CFP in Basecamp** — a project-management tool with zero CFP features — rather than open Sessionize again. (Per Gene's correction: activation energy, not preference — spinning up a Sessionize CFP was too heavy a lift for a small event, so the CFP didn't happen at all. See the Basecamp diagram below.)

### 4. Sessionboard (swyx's incumbent, >$40k/yr) — "enterprise everything"

```
form factory (multi-form, conditional logic, welcome screens, payments)
  → 5-step public submission wizard with SIGNUP WALL at step 2
  → speaker portal (status, tasks, forms-in-tasks, resource pages)
  → evaluation PLANS: create plan → assign sessions to committees → rounds
  → agenda builder → CMS embeds → dashboards
plus CRM + marketing modules nobody at AIE uses
```

Comprehensive and slow ("part of this I also don't love is that it's kind of slow" — swyx, three times in nine minutes). The evaluation-plan indirection is the same concept ITRev found "so stupid" in Sessionize. Its genuinely good ideas: speaker task checklists + the onboarding dashboard (the connective tissue neither of our old tools had), and forms-inside-tasks.


```
   Google Form ──▶ rows land in Sheet ──▶ committee adds columns:
   ┌────────────────────────────────────────────────────────┐
   │ title │ abstract │ PC1? │ PC2? │ PC3? │ notes │ ✓/✗ │
   └────────────────────────────────────────────────────────┘
   dense ✓  shared ✓  comparable ✓ … then acceptance emails by hand,
   schedule in another tab, speaker updates by email forever ✗✗✗
```
- Shape: the fulcro table, hand-carved.** Proof the dense-shared-table is the natural review artifact — it's what people build unprompted. It just has no downstream (comms, portal, schedule).



![Sessionboard public CFP submission page](https://raw.githubusercontent.com/realgenekim/kill-my-saas-reference/main/screenshots/03-public-cfp-submission-page.png)

![Sessionboard speaker portal — home](https://raw.githubusercontent.com/realgenekim/kill-my-saas-reference/main/screenshots/04-speaker-portal-home.png)

![Sessionboard portal tasks admin](https://raw.githubusercontent.com/realgenekim/kill-my-saas-reference/main/screenshots/07-portal-tasks-admin.png)

## cfp-scheduler-killer (the synthesis) — push to start, pull to decide

```
        submission (1 page, no wall)
              │
              ▼
   ┌─ PUSH ───────────────────────────┐      ┌─ PULL ──────────────────────────┐
   │ full proposal → every inbox      │      │ THE BOARD: dense two-row table,  │
   │ comment threads, reply-by-email  │ ◀──▶ │ every score + comment inline,    │
   │ (BusyConf's star)                │ sync │ σ chip flags splits, j/k keys,   │
   │ review starts over coffee ☕     │      │ pivot-by-reviewer (fulcro's lens)│
   └──────────────────────────────────┘      └────────────────┬────────────────┘
     no plans · no rounds · no assignments · roster = whoever participates
                                                              │ accept
                                                              ▼
                                         task checklist + dashboard (Sessionboard's
                                         one good idea) → schedule grid → publish/.ics
```
- Shape: the star and the table, joined.** Conversation to form opinions, comparison to make decisions, structure only after "yes."



## Side-by-side

| Dimension | BusyConf | fulcro app | Sessionboard | Sessionize | |---|---|---|---|---| | Speaker submission | 1 page, no account | (n/a — read-only) | 5-step wizard, signup wall | account wall, multi-step | | Review model | whole committee, conversation-first, push (email) | whole committee, comparison-first, pull (table) | plans → committees → rounds | plans → evaluators → popup stats | | All opinions visible at once | in each proposal's thread | ★ on every row, no click | no | no (iframe per session) | | Private-vs-public fields | ★ first-class ("Notes to the Planning Committee") | n/a | partial | team comments only | | Reply-by-email | ★ yes | no | no | no | | Co-speakers | ★ nested block at submit | shown, first only | portal accounts | per-speaker profiles | | Schedule | drag-drop grid → published/mobile/embed, "hosted forever" | n/a | builder + embeds | builder + embeds | | Speaker tasks dashboard | no | no | ★ yes (the one good new idea) | no | | Organizer API | webhooks (tickets) | — | partial (mintlify) | ★ none — we scraped | | Fate at ITRev | loved; died 2023 (business model, not product) | loved; per-event env-var redeploy | never used ($40k) | fled to Basecamp |

## What the dream tool (cfp-scheduler-killer) takes from each

1. **From BusyConf** — the submission and the conversation:
- One-page CFP, no account wall (account created on submit — already in mockups).
- Public/private field split** at submission ("Notes to the Planning Committee").
- Push channel**: full-content proposal emails to the committee + comment threads with reply-by-email. The review starts in the inbox.
- Co-speakers as a repeatable block; speaker self-service profiles.
- Published schedule: proportional grid, mobile, embed, offline-tolerant, stable URLs "forever."
2. **From the fulcro app** — the judgment surface:
- The dense two-row review board (already the mockups' crown jewel), mean + every score + every comment; pivot-by-reviewer; derived roster.
3. **From Sessionboard** — only the connective tissue:
- Speaker task checklists + the outstanding-tasks dashboard; forms-in-tasks.
4. **From Sessionize** — only lessons:
- Statuses that mirror real triage (Nominated/Accept Queue/Accepted/Declined).
- First-class category labels (never repurpose generic slots).
- A real read API — so nobody ever has to build what we built.
- The Ann amendment (2026-08-08, live interview — see ann-interview doc): the verdict runs on TWO AXES.** Tool quality is per-persona: on the PC/review axis (Gene), BusyConf and the fulcro app won and Sessionize was the trough farm; on the ops/speaker-management axis (Ann), **Sessionize was great** — "speaker profiles (bios, headshots, links) and ease of use… speaker stuff, comms, organization…it was great. Plus, widely used so many speakers already had profiles built so it was easy" — while BusyConf was "never that user-friendly from an ops view." **No tool in fifteen years won both axes. The dream tool must: clone Sessionize's speaker side (profiles, comms, onboarding ease + the already-have-a-profile network effect via URL import) and BusyConf/fulcro's review side, on top of the blocking-sheet scheduler.**
- The synthesis in one sentence:** BusyConf's push (proposals and conversation come to the committee by email) + the fulcro app's pull (one dense comparable page of every opinion) + Sessionboard's task checklist — with none of the plans/rounds/ assignment machinery, because a nine-person PC reviewing together was never an org chart.
- And the moral of the whole archive:** ITRev's revealed preferences, 2016→2026 — PC comms in Basecamp while eventPower counted votes (2016), a Trello bolt-on chewed through Cvent's wall (2018), loved BusyConf precisely because conversation was built in (2019–21), built a custom app to escape Sessionize's review plans (2022), fled back to Basecamp entirely (2026) — draw one straight line: **CFP review is a conversation among trusted peers about a shared table of proposals.** In every era, the review escaped whatever tool couldn't host the conversation. Every tool that modeled it as isolated scoring inside an evaluation bureaucracy lost. swyx's committee will be the same kind of small trusted group; build for that.
- 

# The narrative arc: I lived both prices

*(The economics thread that runs under everything above — for the video and the writeup.)*

- 2019–2021.** BusyConf is the best CFP tool we've ever used — built by one person, Ryan McGeary, who once patched the app *while we were on a call with him*. Its business model is a fee on ticket sales; we never sold tickets through it. We are a CFP-only customer of a ticketing business — off-model from day one.
- January 2022.** BusyConf is winding down. I tried to put together a rescue from other conference organizers — a coalition of people who could keep it alive. The economics say no: the Heroku bill plus the cost of upgrading off the deprecated stack is too high for what a handful of conferences can pay. But it would have ended up as thousands of dollars per month for each of us! We passed. It dies in 2023, one of a generation of indie tools killed by the same Heroku repricing.
- 2023.** SendYourSlides — another one-person tool I depend on, built by Branden Williams — hits the same wall. This one I *can* afford to rescue: I take over his Heroku bill and rehost the Postgres myself. Not a business decision; I just wanted to keep using it.
- 2026.** Branden and I modernize SlideZip with AI-assisted PRs — Rails 8.3, Ruby 4, MFA, a Cloud Run proof-of-concept — part-time, in the margins. The migration that was uneconomic for BusyConf in 2022 is becoming a weekend-scale job. Same rescue, four years apart, opposite answer.
- August 2026.** swyx announces a contest whose entire premise is that AI repriced build-vs-buy for exactly this category of tool. I have receipts from both sides of the price collapse. That's why I'm entering.
- 
---

# The sixth tool nobody sells you: what happens AFTER

*Added 2026-08-09, at Gene's direction. Evidence: `docs/research/post-conference-corpus-survey.md`,
which surveyed the systems IT Revolution actually built for this problem.*

Everything above is about getting to a schedule. But the schedule is not the
artifact — it is the *scaffolding*. You spend four months building something
genuinely beautiful: a hundred proposals, each with a title, an abstract, an
industry, an org size, a speaker's job title, a committee's arguments about why
this one and not that one, a room, a time, a track. Then the conference happens,
and **all of it evaporates.**

A few talks make it to YouTube. What does not survive the trip: the track, the
format, the industry, the org size, the committee's reasoning, the accept/decline
record, the speaker's socials — and above all **the taxonomy that made the program
a program rather than a list of videos.** Every CFP tool in this document treats
its own database as a staging area for an event that has already happened. None of
them has an opinion about the decade.

We have spent years building tooling to claw that back, and the survey is blunt
about how hard it is once the ids are gone:

- **The same question gets three different answers.** "How many talks has Jason Cox
  given?" returns **6**, **9**, or **12** depending on which of our systems you ask —
  all three live today. They disagree because **there is no stable speaker id, talk
  id, or event id anywhere in the estate**; every join is a fuzzy match on a display
  name or a title string. So "Brian Wald" and "Bryan Wald" are two people, a
  co-presented talk becomes one speaker named "Ben Grinnell & Tom Kilcommons", and
  26 real conferences appear as 51 event rows, 21 of them em-dash-versus-hyphen
  duplicates.
- **Video is joined to session by scoring title strings** — exact, prefix-strip,
  contains, speaker-name — a heuristic standing in for a foreign key.
- **The handoff between tools threw away the work.** The Sessionize → Sched
  conversion carried six speaker fields and nothing else: no talk fields, no
  committee comments, no rankings or scores, no accept/decline status. The
  committee's entire work product — the most expensive thing the process produces —
  has never once left a CFP tool.
- **And then the vendor turns off the lights.** Our own archive README records
  talks being rescued *"before Sched takes the sites down"* — twelve events pulled
  down as iCal because the API keys were already gone. **DOES London Virtual 2020
  is simply unrecoverable.**

### What it looks like when it works — and where it came from

Point a browser at **https://videos.itrevolution.com/speakers/jason-cox** (public,
no login). You get his talks, his org, his LinkedIn — and a **Career progression**
section: 2017 *Director, Platform & Systems Reliability Engineering* → 2022–23
*Director, Global SRE* → 2024 *Director, Platform & SRE*, each with the talks he
gave while holding that title. Alongside it: **1,217 speakers · 1,882 talks · 574
organizations**, back to 2014.

Here is the part that should change how we build the form. The page notes that each
role is drawn from a Summit appearance. **That ten-year career timeline is
reconstructed from the job-title field on each CFP submission** — a field every tool
in this document treats as throwaway display text, printed once on a badge and
forgotten. The single most valuable longitudinal dataset we own is an accident.

It is an accident we should stop relying on. The lesson is not "capture more" — it
is that **a submission is a dated observation about a person and their organization,
and it must be frozen as such.** `title_at_time` and `org_at_time` belong on the
speaker-on-session record and must never be back-filled, because the whole value is
that they were true *then*.

### What this means for cfp-scheduler-killer

The tool is not finished when the schedule is published. It is finished when the
program is a durable, queryable asset. Concretely, ours must emit:

1. **Stable opaque ids** — `person_id`, `submission_id`, `session_id`, `event_id`,
   plus an integer `year` and an `external_ids` map (`{vimeo, youtube, sched,
   sessionize}`) — so downstream pipelines stamp an identifier instead of guessing
   at titles.
2. **A `program.json` snapshot plus an append-only `decisions.jsonl`** — the
   submitted record verbatim, taxonomy as `{id, label}` pairs from a controlled
   vocabulary rather than a freeform tag array, frozen `title_at_time` /
   `org_at_time`, and the committee's decisions exported rather than entombed.
3. **Permanent public identity URLs** (`/people/{id}`, `/sessions/{id}`, with `.json`
   and `llms.txt` siblings) and `.ics` whose `UID` **is** the `session_id`, carrying
   track, format and person ids — so that when the next vendor turns the lights off,
   the calendar file alone is enough to rebuild the graph.

That is the real argument for owning this software. Not that Sessionize is slow, or
that Sessionboard costs $40k — but that **a decade of the field's institutional
memory currently survives by accident, in a job-title field, joined by string
matching, on somebody else's server.**

---

## Open questions for swyx (as of Sun Aug 9)

Things the videos and Discord haven't settled, in the order they'd change what we build:

What is your ideal review process?  let everyone see everything?  Let everyone see nothing?

1. **Publishing a known-impossible draft schedule.** Partial states (room TBD) feel
   legitimate to publish — that's what draft-first scheduling and stable .ics UIDs
   are for. But a detected *conflict* (one speaker in two sessions at 10:30) isn't
   undecided, it's known-wrong. Should the public agenda withhold conflicted
   sessions, mark them provisional, or publish them as-is until resolved?
2. **Accept Queue / Decline Queue semantics.** We've modeled them as "decision made,
   speaker not yet told" — status changes are silent, and notification is a
   deliberate, tracked second act (so the committee can flip decisions freely until
   the letters go out). Is that the actual workflow, or does queueing trigger
   something else in your world?
3. **Review visibility.** Our default is every score and comment visible to the whole
   committee inline — review as a conversation among trusted peers — with a per-event
   setting to hide others' scores until you've rated. Does your committee ever
   actually need blind review, or is open-by-default correct?
4. **Media/file uploads.** For slides, headshots, contracts: is attach-to-the-
   submission/speaker-record (with download-all per request) the right shape, or do
   you need a central "file requests" pipeline? And is video ever collected through
   this tool, or does that always live on YouTube/production's side?
5. **Drafts and the submission cap.** Should unfinished drafts count toward a
   max-submissions-per-person limit, or only submitted talks?
6. **Sponsor/exhibitor groups.** Do Groups matter on the Program side for this
   contest's scope, or is speakers-and-sessions the whole game?
7. **The export contract.** If we emit `sessions.json` / `speakers.json` /
   `calendar.ics` (stable UIDs) / `llms.txt` plus an authenticated read API with
   stable person/session ids — is there anything else ai.engineer's pipeline needs
   that we should ship before Wednesday?

---

## Appendix: the scale — ours, and the whole platform's

**Our aggregate**: across nine events run on one incumbent platform (2022–2025) we put roughly
**~1,200 submissions** through the incumbent's CFP pipeline — reviewed, decided,
scheduled, and chased — on top of the longer archaeology above (26 conferences
over 15 years, across four generations of tools). Per-event breakdowns withheld
here; **swyx: I'll share the full dataset with you privately** — per-event names,
dates, submission/acceptance/withdrawal counts, review activity, and the
speaker-chasing archive stats behind the ethnography. Just not in a public repo — including which platform "CFP Platform" is.

**One incumbent's throughput, via the German tank problem** (the WWII
estimator: sequential serial numbers observed in the field are enough to bound
how many tanks — or events — the factory is producing). One incumbent platform's event IDs and session
IDs are both globally sequential (our 2024 events got IDs 13507/13508 — created in
the same sitting, adjacent on a platform-wide counter). Seven of our own event IDs
plus dated CFP windows are enough to estimate the whole platform's throughput,
WWII-tank-statistics style:

```
CFP Platform EVENT ids (platform-wide counter) at our creation dates:

Feb 2022  ██████████████████    7,171
Feb 2022  ███████████████████   7,424
Apr 2022  ████████████████████  8,020
Jan 2023  ███████████████████████████   10,643      ~3,500 events/yr
Mar 2023  ████████████████████████████  11,363
Feb 2024  ██████████████████████████████████  13,507    ~2,600/yr (dip)
Mar 2025  ██████████████████████████████████████████████  18,388  ~4,500/yr
```

```
CFP Platform SESSION ids (platform-wide submissions) across our CFP windows:

Feb 2022  ████████████████     ~317k
Aug 2022  ████████████████████     ~402k
Mar 2023  ████████████████████████     ~475k    ~140k submissions/yr
Jun 2023  ██████████████████████████     ~517k
Mar 2024  ████████████████████████████████     ~639k
Jun 2024  ██████████████████████████████████     ~688k
Jun 2025  █████████████████████████████████████████████  ~895k   ~265k/yr
```

The submission rate **roughly doubled into 2025** (~400/day → ~700+/day) — the
AI-era conference boom is visible in the platform's own ID sequence. And the
business read: ~3–4.5k events created per year, mostly free community events;
even at a generous 30% paid × $499 that is well under $1M/yr for the whole
category leader — which is exactly why these products stagnate, why there is no
organizer API, and why "clone it in a weekend" is plausible at all.

(Method caveats: assumes dense IDs — the 13507/13508 adjacency and smooth
per-year progression support it; creation dates anchored by our own records,
±1 month.)
