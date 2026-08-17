# Brief screen analysis: all 40 Sessionboard screenshots, verdicts, and the PC-centric storyboard

*2026-08-08. Every screenshot swyx embedded in the brief, viewed and judged
against our target (the smallest deep tool that wins), grounded in the brief's
struck requirements, swyx's Discord Q&A, and our field research.*

## The storyboard: the life of one submission (PC flow as the spine)

```
════════════════════════ THE LIFE OF ONE SUBMISSION ═══════════════════════════
  (three lanes: SPEAKER · PC/Gene · OPS/Ann — swyx's screen-groups mapped on,
   ▓ = the PC flow Gene cares about · ░ = machinery that feeds/consumes it)

 SPEAKER lane          PC lane (▓ THE SPINE ▓)         OPS lane
 ────────────          ───────────────────────         ────────
░①EVENT SETUP░─────────────────────────────────────── create event, form,
 [imgs 28,23,39]                                       dates, tracks (10 MIN,
      │                                                not a wizard-farm)
      ▼
░②PUBLIC CFP░  img 1
 one page, no wall,
 sessionize-URL import ──▶ submission row created
 "Notes to the PC"            │
 (private field)              ▼
      │            ▓③THE PUSH▓  (BusyConf's star — absent from swyx's
      │             screenshots entirely; his brief's blind spot)
      │             FULL proposal → every PC inbox · reply-by-email comments
      │                       │
      │                       ▼
      │            ▓④THE BOARD▓  imgs 18,15,11,6,3 (only 5 of 40 shots —
      │             the brief underweights the heart)
      │             ┌────────────────────────────────────────────┐
      │             │ sort A: # ratings ↑  = "NEEDS REVIEWS"     │
      │             │   (Gallimore's 2-review rule, coverage bar)│
      │             │ sort B: avg stars ↓  = "READY TO DECIDE"   │
      │             │ every score · every comment · inline ·     │
      │             │ pivot-by-reviewer · σ split-flags          │
      │             └────────────────────────────────────────────┘
      │                       │
      │                       ▼
      │            ▓⑤THE CALL▓  (no screen in ANY tool, ever —
      │             comments become the agenda; round-robin lobby)
      │                       │
      │              ┌────────┼──────────┐
      │              ▼        ▼          ▼
      │           Accept   Accept     Decline
      │              │     Queue     (Queue)
      │              │   (+coaching)    │
      │              │        │  batched ▼ NOTIFIED flag
      │              ▼        ▼    ░letters░ (real person's address)
      │       ═══ merged SLOT INVENTORY ═══◀── invited/sponsor speakers
      │        "35 slots · 27 filled"          (bypass CFP — most plenary;
      │              │                          zero swyx screens)
      ▼              │
░⑥PORTAL░ imgs 4,38  │                        ░⑦TASKS + DASHBOARD░
 "You're in" banner  │                         imgs 31,17,24,22,27,32,8 +
 bio·headshot·slides◀┼────────────────────────▶26,13,40,14,29,25,7
 (Sessionize's good  │                         "Also check" nudge rows;
  half, cloned)      │                         announce-permission &
      │              ▼                         employer-approval states
      │    ▓⑧BLOCKING-SHEET SCHEDULER▓  img 21 (swyx's own sandbox:
      │     "Nothing here yet")
      │     tray → grid · TBD blocks · live slot math · conflict
      │     chips · what-if drafts · LOCK
      │              │
      ▼              ▼ harden
░⑨COMMS + .ics░  stable UIDs, room assigned late → invite AMENDS
      ▼              ▼
░⑩PUBLISH░  imgs 36,5 (embeds SKIPPED — struck req 9)
 public agenda + sessions.json/speakers.json/ics/llms.txt + API/webhooks
                     │
                     ▼
           ▓⑪NEXT YEAR▓  ratings attach to speaker record, surface at
            next review · "Copy from…" clones last year's forms/tasks
```

**The inversion:** swyx's brief spends 11 screenshots on the form builder, 7 on
optional dashboards, 6 on portal-forms plumbing — and 5 on the review surface,
his most weakly-specified requirement. The three things our decade says decide
everything (the push ③, the call ⑤, the next-year loop ⑪) appear in ZERO
screenshots, because Sessionboard doesn't have them.

## Verdict counts (per-image inventory in the appendix)

- **KEEP 19**: images 1, 2, 4, 6, 9, 10, 15, 16, 18, 19, 21, 22, 23, 26, 29,
  31, 33, 38, 40
- **SIMPLIFY 16**: images 3, 8, 11, 12, 13, 14, 17, 24, 25, 27, 28, 30, 32,
  34, 36, 37
- **SKIP 5**: images 5 (embed builder), 7 (dashboard framework/AI dashboards),
  20 (payments — swyx defaced it "NOT NEEDED"), 35 (marketing page), 39
  (exhibitors/sponsors)

## The 5 screens the AIE team will actually exercise

1. **image1 — public CFP page.**  First click of any eval. Theirs is a 5-step
   stepper with ACCOUNT CREATION IN THE MIDDLE; ours is one page,
   account-on-submit.

<a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image1.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/previews/image1.png" width="480" style="border:2px solid #000;border-radius:4px;margin:8px 0;display:block;"></a>

2. **image18 + 15 — review board with inline status change.**  The Accept
   Queue → Notified flow is the real decision loop.

<a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image18.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/previews/image18.png" width="480" style="border:2px solid #000;border-radius:4px;margin:8px 0;display:block;"></a>
<a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image15.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/previews/image15.png" width="480" style="border:2px solid #000;border-radius:4px;margin:8px 0;display:block;"></a>

3. **image4 — speaker portal Home**  (status + tasks). Where a non-technical
   speaker judges the product.

<a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image4.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/previews/image4.png" width="480" style="border:2px solid #000;border-radius:4px;margin:8px 0;display:block;"></a>

4. **image40 — dashboard alert rows.**  "3 session submissions are awaiting a
   decision → Review submissions" · "2 accepted speakers are missing a bio or
   headshot (2 bios, 2 headshots) → View speakers." Requirement 6 rendered as
   two deep-linked sentences — highest signal-per-pixel in the whole deck.

<a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image40.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/previews/image40.png" width="480" style="border:2px solid #000;border-radius:4px;margin:8px 0;display:block;"></a>

5. **image21 — Agenda with Conflicts/Rooms views.**  Hardest thing to fake;
   they'll poke it hardest.

<a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image21.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/previews/image21.png" width="480" style="border:2px solid #000;border-radius:4px;margin:8px 0;display:block;"></a>


## Steal list (details the pixels revealed that our research missed)

1. **The "Also check" nudge row** — count-first, deep-linked sentences on the
   dashboard ("1 accepted session still needs a time slot on the agenda").
   Build this FIRST; it satisfies req 6 better than any chart. (= our
   count-first status house rule, in product form.)
2. **7-valued status + Notified flag**: Accepted / Accept Queue / Pending /
   Decline Queue / Declined / Withdrawn / Drafts, with notification as a
   deliberate, tracked SECOND act. Matches Ann's batched-letters reality
   ("accepted last week but letters not out"). Adopt wholesale.
3. **Locked flag on core fields** (Title, Name, Email undeletable) — guardrail
   for non-technical admins editing their own form.
4. **Cross-field character limits** with live combined counter — "cap the
   combined length of several text fields, e.g. a printed program block."
   Genuine event-production need we had zero awareness of.
5. **"Copy from…" on forms AND tasks** — annual events clone last year. Two
   menu items, huge perceived competence. (Pairs with our next-year loop.)
6. **"Back to Admin Mode"** — built-in speaker impersonation. Trivial;
   extremely demo-able; solves "what does the speaker see?"
7. **Download files bundle (zip)** next to CSV/XLSX export — production staff
   will use it more than the API.
8. **Event-level submission cap** printed on the public page ("Submission
   Limit: 3 per user") — anti-spam for swyx's expected unserious submissions.
9. **Per-reviewer ratings as a grid column** ("Ratings: My Evaluation Plan") —
   the incumbent independently validates never-collapse-reviewers.
10. Portal profile fields: Salutation, Honorific, **Pronouns**, Gender +
    LinkedIn/X/Facebook/Website — richer identity than we'd scoped; socials
    feed the public speaker page.

## Avoid list

1. **Two wizards** (7-step admin form builder + 5-step public stepper) — the
   embodiment of the slow-SaaS complaint. One scrollable page each.
2. **Files "stored, not attached"** — Sessionboard warns twice that uploads
   don't attach to the submission/contact. A data-model wart; always attach.
3. **Four parallel "collect something" surfaces** (Program Forms, Portal
   Forms, File Requests, Tasks) — one `task` entity with a type field
   (form | upload | confirm) replaces all four.
4. **Dashboard framework** (Add Dashboard / Add Widget / AI-generated
   dashboards) against a "best efforts" requirement.
5. **Empty demo data** — their own screenshots say "Nothing here yet" and "No
   data." Ship seeded, realistic data; judges see more working product in 5
   seconds than the incumbent shows.

## Appendix: per-image inventory

(Verdicts + one-line rationale; full UI-element detail available in the agent
transcript if ever needed.)

| # | Thumb | Screen | Verdict | Note |
|---|---|---|---|---|
| 28 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image28.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image28.png" style="border:1.5px solid #000;border-radius:3px;"></a> | Settings hub (9 sections) | SIMPLIFY | collapse to one settings page |
| 23 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image23.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image23.png" style="border:1.5px solid #000;border-radius:3px;"></a> | Event details (name/slug/dates/tz) | KEEP | feeds CFP header, .ics, agenda |
| 39 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image39.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image39.png" style="border:1.5px solid #000;border-radius:3px;"></a> | Exhibitors/sponsors + images | SKIP | unused module; keep logo field only |
| 12 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image12.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image12.png" style="border:1.5px solid #000;border-radius:3px;"></a> | Submission forms list | SIMPLIFY | single form + copy-link; no versioning |
| 37 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image37.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image37.png" style="border:1.5px solid #000;border-radius:3px;"></a> | Form wizard step 1 (7-step rail) | SIMPLIFY | one scrollable page |
| 30 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image30.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image30.png" style="border:1.5px solid #000;border-radius:3px;"></a> | Welcome screen (4 name fields + WYSIWYG) | SIMPLIFY | one markdown intro |
| 19 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image19.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image19.png" style="border:1.5px solid #000;border-radius:3px;"></a> | Abstract section + first field | KEEP | the form builder core |
| 2 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image2.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image2.png" style="border:1.5px solid #000;border-radius:3px;"></a> | Abstract field list (6 rows, drag/required/locked) | KEEP | the submission schema + track routing |
| 34 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image34.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image34.png" style="border:1.5px solid #000;border-radius:3px;"></a> | Participant roles + min/max matrix | SIMPLIFY | one Speaker role + co-speaker list |
| 10 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image10.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image10.png" style="border:1.5px solid #000;border-radius:3px;"></a> | Participant fields (name/email/bio) | KEEP | portal identity + profile fields |
| 20 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image20.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image20.png" style="border:1.5px solid #000;border-radius:3px;"></a> | Payments | SKIP | swyx defaced it "NOT NEEDED" |
| 33 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image33.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image33.png" style="border:1.5px solid #000;border-radius:3px;"></a> | Close date + submission limit | KEEP | swyx: "kinda impt"; drives reminders |
| 9 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image9.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image9.png" style="border:1.5px solid #000;border-radius:3px;"></a> | Success page + cross-field limits | KEEP | swyx: "make sure this works" |
| 16 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image16.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image16.png" style="border:1.5px solid #000;border-radius:3px;"></a> | Notifications | KEEP | submitter confirmation = "must have" |
| 1 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image1.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image1.png" style="border:1.5px solid #000;border-radius:3px;"></a> | Public CFP (5-step stepper) | KEEP | job yes; stepper no — one page |
| 4 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image4.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image4.png" style="border:1.5px solid #000;border-radius:3px;"></a> | Portal home (status + tasks) | KEEP | status visibility "a key part" |
| 38 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image38.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image38.png" style="border:1.5px solid #000;border-radius:3px;"></a> | Portal profile (bio/socials/pronouns) | KEEP | "update your own bio data" |
| 18 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image18.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image18.png" style="border:1.5px solid #000;border-radius:3px;"></a> | Abstracts board (status tabs, rating col) | KEEP | the review board |
| 15 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image15.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image15.png" style="border:1.5px solid #000;border-radius:3px;"></a> | Inline status editor | KEEP | decide without leaving the grid |
| 11 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image11.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image11.png" style="border:1.5px solid #000;border-radius:3px;"></a> | Column chooser (39 fields) | SIMPLIFY | fixed dense layout + small toggles |
| 6 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image6.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image6.png" style="border:1.5px solid #000;border-radius:3px;"></a> | Options: import/export/files bundle | KEEP | exports + the zip bundle |
| 3 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image3.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image3.png" style="border:1.5px solid #000;border-radius:3px;"></a> | Add abstract drawer (manual) | SIMPLIFY | invited/sponsor entry; same form |
| 21 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image21.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image21.png" style="border:1.5px solid #000;border-radius:3px;"></a> | Agenda (List/Day/Week/Month/Rooms/Conflicts) | KEEP | blocking-sheet scheduler serves these views |
| 31 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image31.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image31.png" style="border:1.5px solid #000;border-radius:3px;"></a> | Tasks admin (contact/submission types) | KEEP | + "Copy from…" |
| 17 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image17.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image17.png" style="border:1.5px solid #000;border-radius:3px;"></a> | Portal forms list | SIMPLIFY | fold into tasks |
| 24 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image24.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image24.png" style="border:1.5px solid #000;border-radius:3px;"></a> | Create form (type cards) | SIMPLIFY | same builder as CFP |
| 22 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image22.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image22.png" style="border:1.5px solid #000;border-radius:3px;"></a> | Form questions + reusable field library | KEEP | reuse CFP field definitions |
| 27 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image27.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image27.png" style="border:1.5px solid #000;border-radius:3px;"></a> | Form confirmation email | SIMPLIFY | shared comms system |
| 32 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image32.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image32.png" style="border:1.5px solid #000;border-radius:3px;"></a> | File requests list | SIMPLIFY | task type "upload"; ATTACH files |
| 8 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image8.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image8.png" style="border:1.5px solid #000;border-radius:3px;"></a> | Add file request | SIMPLIFY | same |
| 36 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image36.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image36.png" style="border:1.5px solid #000;border-radius:3px;"></a> | CMS embeds list | SIMPLIFY | public agenda URL + JSON feed |
| 5 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image5.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image5.png" style="border:1.5px solid #000;border-radius:3px;"></a> | Embed editor (styled HTML builder) | SKIP | struck req 9 |
| 26 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image26.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image26.png" style="border:1.5px solid #000;border-radius:3px;"></a> | Dashboard Today (KPIs, countdown) | KEEP | count-first landing |
| 13 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image13.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image13.png" style="border:1.5px solid #000;border-radius:3px;"></a> | Pacing chart + recent submissions | SIMPLIFY | keep recents; drop pacing |
| 40 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image40.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image40.png" style="border:1.5px solid #000;border-radius:3px;"></a> | Alert rows (awaiting decision / missing bio) | KEEP | the best screen in the deck |
| 14 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image14.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image14.png" style="border:1.5px solid #000;border-radius:3px;"></a> | Evaluations dashboard (plans) | SIMPLIFY | coverage on the board, not a plan object |
| 29 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image29.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image29.png" style="border:1.5px solid #000;border-radius:3px;"></a> | Speaker tracking dashboard | KEEP | two numbers + overdue list |
| 25 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image25.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image25.png" style="border:1.5px solid #000;border-radius:3px;"></a> | Submissions pipeline dashboard | SIMPLIFY | one chip row above the board |
| 7 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image7.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image7.png" style="border:1.5px solid #000;border-radius:3px;"></a> | New dashboard modal (AI prompts) | SKIP | framework over-scope |
| 35 | <a target="_blank" rel="noopener" href="/projects/sessionize-sched-killer/code/raw/docs/brief/media/image35.png"><img src="/projects/sessionize-sched-killer/code/raw/docs/brief/thumbs/image35.png" style="border:1.5px solid #000;border-radius:3px;"></a> | Marketing mega-menu | SKIP | context only; confirms Program-module scope |
