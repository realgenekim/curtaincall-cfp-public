# Good morning — overnight brief (Sun Aug 9)

**Deadline: Wed Aug 12, 10PM PT.** Read the first two sections; the rest is detail.

---

## 0. 🎯 MID-MORNING UPDATE: THE RUBRIC IS OUT (supersedes section 1's ask — Discord is harvested)

swyx released the official eval kit: **https://forge.smol.ai/swyx/killmysaas-evals**
(cloned locally; full harvest in `docs/discord/2026-08-09-general.md`). It is an
executable LLM-as-judge harness: a Claude-driven Playwright agent clicks through the
**deployed** app running 20 scripted scenarios, a separate judge scores **96 rubric
items** with cited screenshot evidence. His caveat: "NOT the final list… the real
final judge (the tools buyer human)" — but it's the only executable target, and we
can run it on ourselves (`npm run eval -- --url … `, ~$2–10, needs ANTHROPIC_API_KEY).

**Area weights (required sums to 100):**

| Area | Weight | Our est. today |
|---|--:|--:|
| Call for Papers | 20 | ~60% |
| Abstract Management (reviewer assignment, rounds, blind review) | 20 | **~13%** |
| Public Widgets (sessions/speakers/agenda/itinerary/gallery) | 20 | **~26%** |
| Speaker Management (portal + tasks) | 15 | ~21% |
| Content Management (files, versions, approvals) | 15 | **~18%** |
| AI Agenda Builder | 10 | ~83% |
| Speaker CRM (extra credit, only with `--include-optional`) | +10 | skip |

**Item types that discriminate** (his own calibration notes): `rule`/`scoping`
("deadlines, conflicts, **authz isolation**… the strongest signal in the whole
rubric") and `roundtrip`/`handoff` (two-sided flows). `exists`/`crud` pass everywhere.

**The two findings that matter most** (full analysis:
`docs/research/rubric-gap-analysis-2026-08-09.md`):

1. **Judge access is a score-withholding bug, not a feature gap** (bd `-o42`, P0):
   the eval agent cannot sign in at all — no signup, magic link only shown under
   `ENV=dev`, fixture personas not on any roster. Under the kit's 60%-coverage floor
   the report says "insufficient coverage — **score withheld**." Fix = seeded demo
   accounts + a non-dev sign-in path, documented on the login page.
2. **Functional ceiling behind the wall: ~34%**, concentrated in the two heaviest
   areas. The report's **12-hour path**: demo login → rename inform gate to
   "Approved" + explicit "Publish agenda" button (converts 3 near-misses) → CFP
   edit-lock after close → auto-schedule button → agenda detail view → public
   sessions/speakers widgets (8.8 pts over data we already fold).

**Deploy urgency doubled**: swyx is running his eval on any MVP URL DM'd to him
(URL + credentials) — **three competitors already sent theirs** (first public one:
board-to-death.vercel.app). Also: Cloudflare is explicitly optional ("propose other
services, i don't care") — GCP is fine. Deploy traps found: in-memory sessions die
on a Cloud Run instance recycle mid-eval; the 3-submission cap fires on their
fixture speaker mid-run; seed form needs a field literally labeled "Track".

**Also landed since the overnight brief**: swyx's 9 numbered spec answers (tracks,
minimum review workflow, speaker task examples, "emails should work on an MVP
basis", "admin ui is the priority") and the SessionBoard role-view walkthroughs —
all in the Discord transcript. Per-event authz + Settings API keys are being
implemented now (bd `-x9j`, `-bdm`, `-vi9`; role names ratified:
reviewer/chair/submitter).

---

## 1. ⚡ THE THING ONLY YOU CAN DO — fetch Discord (10 minutes) — ✅ DONE 9:00 AM (see §0)

Discord is logged out in the Chrome profile the automation can reach ("Please log
in again", account **gk0563**). Your logged-in session is in a different browser
or profile, and an agent must never type credentials. **Three separate asks are
blocked on this**, and one of them is time-critical because requirements freeze
after swyx's Sunday video.

Please log in at `https://discord.com/channels/1535542355728408629/1535542356655214604`
**in the same Chrome window the Claude extension is attached to**, then say "discord
is up" and I will harvest everything in one pass with the `discord-transcript` skill.

If it's easier, paste raw text into `docs/discord/2026-08-09-RAW-paste.md` and I'll
structure it. What I need, in priority order:

| # | What to grab | Why it matters |
|---|---|---|
| **1** | **swyx's EVALS / rubric** — he tweeted 10:31 PM Aug 8 that a competitor's strong submission "immediately necessitated releasing my evals early". Check **#announcements** first, then #general. | This is the hill-climb target. Everything we build Sun–Tue should be scored against it. Right now we are optimizing against the Aug-8 rubric only. |
| **2** | **The links swyx posted showing "views for all roles"** of the SaaS tool | You flagged these. Our current app has an organizer view and a speaker portal — if he has posted role views we haven't modelled (sponsor? track chair? AV/production?), that is a scope gap we still have time to close. |
| **3** | **How ultracode was used** — the competitor "did a pretty good submission in 3 ultracode prompts" | Competitive intel, and possibly a note for your own writeup. |
| **4** | Anything else since the Aug-8 harvest | swyx's answers are spec amendments. |

Our last harvest (`docs/discord/2026-08-08-general.md`) contains **zero** mentions of
ultracode and only two links — both competitor X posts. So all of the above is new
material we simply do not have.

**Also awake-only:** the Airtable sync needs a Personal Access Token pasted into
Settings → Airtable (Base ID / Table / PAT). I can't create tokens. The sink itself
is built and verified; it just has no credentials.

---

## 2. 🔴 What I found overnight — one of them is serious

### P0 SECURITY: a speaker could accept and decline talks — FIXED and verified live

`bd sessionize-sched-killer-53i`. Driving the live app as a real speaker account,
I found that **anyone who submits a talk could then rate proposals, flip a talk from
Accepted to DECLINED, flag talks, and lock the schedule.** Confirmed in the event
log with the speaker as `actor`.

The cause: the gate matched organizer *pages* by URL prefix (`/events`), but every
*mutation* lives under `/api/` — so those endpoints required only "is signed in",
and every speaker is signed in. Each request answered **303**, which is exactly what
success looks like, so nothing ever complained.

**Fixed in `auth.clj` by inverting the default.** The gate now names what is OPEN
(public paths, and the speaker's own portal/answers/tasks/profile) and requires
committee membership for **everything else, including routes written after today**.
An allowlist of *protected* paths is precisely how this hole appeared; naming what is
open cannot fail the same way. Denied writes now return 403 with a plain-English
reason instead of a fake redirect.

New test file `test/cfp_scheduler_killer/authz_security_test.clj` — **5 tests, 37
assertions, passing.** It asserts the append-only log, not just status codes,
because status codes are what lied. It also asserts that an endpoint *nobody has
written yet* is refused, which is the property that keeps this fixed.

**Still open (follow-up, not yet done):** authorization is not scoped *per event* —
a committee member of event A can still act on event B. Fine for you today; wrong
before this is multi-tenant.

### P1: judging endpoints silently swallow bad input

`bd sessionize-sched-killer-xmf`. `POST .../status`, `/rate`, `/comment` return 303
**as if they worked** while doing nothing, when the value is unrecognized:
`status=accepted` (lowercase) vanishes, `status=Accepted` works; `stars=99`, `stars=abc`,
empty comments — all silently dropped. This matters because the Sessionboard-shaped
API is an advertised feature, and an integrator would lose writes and be told they
succeeded. It also produced a **false pass in my own test harness**, which is the
best argument against silent no-ops I can offer.

### P1: a known-impossible schedule is published to the public (needs your call)

`bd -31b`. Our public agenda and `sessions.json` both show **Marcus Devlin speaking
in two sessions at 10:30am**. The organizer side handles this *well* — the schedule
page says "1 conflicts" and shows a named, non-blocking chip, *"⚡ Marcus Devlin is
also in no room yet at 10:30am"*, offering to move either talk. That's doctrine #6
done right, and I'd change nothing about it.

The problem is that the same conflict reaches the **public** surfaces silently. A
judge opening our demo agenda sees one person in two places at once and reads it as
a data bug, not a draft state.

The distinction I think we should draw: a **partial** state (room TBD) is legitimate
to publish — that's exactly what #6 and the stable-ICS-UID design are for. A
**conflict** is different: it isn't undecided, it's known to be wrong. Your call
whether to withhold conflicted sessions from public surfaces or mark them
provisional. `schedule/conflicted-submission-ids` already exists, so either is
cheap. Worth deciding before we deploy, since this is in the seeded demo a judge
will open.

### P0 REQUIREMENT: we have no deployed site

`bd sessionize-sched-killer-ghz`. The brief says the winning submission needs a
*"Deployed site we can test out with the walkthrough shown."* Everything runs on
localhost. **This is the largest un-started hard requirement.** I need three
decisions from you before I can finish it:

1. **Which GCP project?**
2. **SMTP yes or no?** (magic-link emails on the deployed site, versus the current
   honest "SMTP not configured — letters are recorded, send by hand")
3. **Storage on Cloud Run**: single instance + GCS snapshot (a judge's session
   survives), or accept resets between deploys? I recommend the former.

---

## 3. ✅ What's working — the whole flow, verified

I built `bin/e2e_drive.py`, which walks the **live server** as three separate people
(you, Ann, and an anonymous speaker) and asserts what each can see. Final state:
**127/127 on the driver, and `make runtests-once` at 186 tests / 1697 assertions / 0
failures.** Run the drive any time with `python3 bin/e2e_drive.py` — it is the thing
that found the security hole, and it now guards against its return.

One note on trust: when I ran the suite myself it came back **182/1662/1**, not the
green the builder reported. The failure was a genuinely flaky test — the `pc-push`
sink fires asynchronously and appends its own event, which landed between a baseline
and its assertion, so the test blamed the write we had just *refused* for a line it
never wrote. Fixed by draining sinks before counting. Worth knowing that the
re-verification earned its keep.

**I also reset and reseeded the demo store.** My testing had polluted it with cap
tests and an XSS probe whose talk title was literally `<script>alert(1)</script>` —
not what you or a judge should open onto. It is back to the clean eight-submission
Charlotte world (`make store-reset && make seed-demo` if it ever needs it again).

Verified end to end: sign in → create event (blank slug auto-derives) → every field
persists in the log → auto-committee + seed form → add Ann → Ann signs in
independently → public CFP with no account wall → speaker submits → organizer sees it
on the board → two reviewers rate and comment, both visible → accept → **speaker is
not told until you deliberately inform** → informing publishes it to sessions.json →
exports and .ics valid → all 10 organizer pages render.

Things that are genuinely better than the incumbent, now pinned by tests:

- **A validation error does not eat a typed abstract.** 422 with per-field messages
  inline and everything still in the boxes.
- **Submission cap works and explains itself**: "You've reached this event's limit of
  3 submissions. Contact the organizers if you need an exception."
- **XSS is properly escaped** — I submitted `<script>` and `onerror=` payloads; they
  render as inert text on the board.
- **The inform letters are good.** Warm, specific, and honest that SMTP is off.
- **The speaker portal** shows status, a task checklist, and profile that "prefills
  your next submission, so you never type your bio twice."
- **Sink fan-out fires on every submission, rating and comment** — verified by
  registering a live webhook and catching the deliveries (`bin/sink_probe.py`).
- Pages render in ~130ms. "We do not want slow SaaS" is satisfied.

---

## 4. 🔨 Landed overnight

### The CFP form builder is real — go look at it

`http://localhost:20500/events/enterprise-ai-summit-charlotte/form`

This was the big gap: `Form` in the left nav was greyed out, there was no way to
create a custom form, and swyx says the product **is** *"a very fancy form builder
— that's all it is."*

It now lists every question (label, stable id, type, required / private / locked /
retired), and lets you add one (6 types, required and private flags, help text,
character limit, select options, radio-vs-dropdown), edit, reorder, retire and
restore — beside a **live preview that runs the actual public renderer**, so what
you see is literally what a speaker gets.

Two things in it I think you'll like. First, the banner it shows when a CFP is
already live:

> **8 submissions already exist.** Editing the form now is safe: every submission
> carries its own snapshot of the questions it was answered against, so nothing you
> do here rewrites what a speaker already said.

Second, deleting is **retiring** — the question leaves the public form and its
answers stay readable — and the locked spine (title, abstract, speakers) refuses
outright. Field ids never move when you rename a label, so answers keep their
meaning forever.

**I drove it end to end**: added a radio question ("Which AI coding agents are in
daily use on your team?"), confirmed it appeared on the public CFP, submitted a
talk answering it, and confirmed the answer stored under its own stable id and
rendered for the committee.

### The security fix is verified against the running server

After restarting to pick up the middleware, all five escalation attempts return
**403 with nothing written to the log**, and — the part worth noting — **the
default-deny gate automatically protected all six brand-new form-builder
endpoints**, routes that did not exist when the fix was written. That is exactly
the property it was built for, now demonstrated rather than claimed.

### Two ethnographies, both with teeth

**`docs/research/speaker-wrangling-ethnography.md`** — 13,488 Slack messages, all
1,540 of Ann's read end to end. Slides are the #1 chase (median **T-8 days**);
co-presenter details have the highest chase density (~90%); escalation runs by
**medium, not by strike count** (tool email → Ann's personal email → cc you → Ann
texts → you call). The finding that should shape the product: **in 13,488 messages
there is zero evidence of any tool ever successfully sending a reminder on the
organizer's behalf**, and Ann says the identical "Sessionize got stuck in spam, I'm
sending a personal email" in **2023 and again in 2025**. Any feature that
auto-emails speakers will be switched off within one event cycle. Filed as bd
`-uxw` (materials ledger), `-kzt` (assisted chasing), `-r6c` (employer-approval and
announce-permission as first-class states — employer approval is the #1 withdrawal
cause and arrives late and negative).

**`docs/research/post-conference-corpus-survey.md`** — your video-library point,
evidenced. Ask three of our live systems how many talks Jason Cox has given and you
get **6, 9 and 12**, because there is no stable speaker, talk or event id anywhere;
every join is a fuzzy string match. 26 real conferences appear as 51 event rows.
The Sessionize→Sched handoff carried six speaker fields and nothing else — **the
committee's entire work product has never once left a CFP tool.**

The punchline is now in the vision doc: the ten-year **career progression** on
`videos.itrevolution.com/speakers/jason-cox` is reconstructed from the **job-title
field on each CFP submission** — a field every tool treats as throwaway display
text. Our most valuable longitudinal dataset is an accident. Hence bd `-ifs`:
freeze `title_at_time` / `org_at_time` and never back-fill them.

### Slack notifications — your ask, done

Settings → Slack: paste an incoming-webhook URL and tick which moments matter.
They're grouped the way you'd think about them, not as raw event types: **A talk
arrives · The committee acts · A decision is made · A speaker is told**, with
arrival and decisions on by default. There's a "Send a test message" button that
reports Slack's own error translated into English.

Messages carry the title, speaker, org and a link straight to the submission — and
for a rating, the new average. They're built from named fields rather than dumped
answers, so **a private note cannot reach a channel by construction**. The page says
why: *"a channel is a wider room than the programming committee."*

The one thing you'll need to do: paste a real webhook URL. It validates that the URL
starts with `https://hooks.slack.com/services/`, which is why I couldn't fully test
delivery locally — correct behaviour beating my convenience.

### Validation the modern way — your "avoid js boofarama" note

The endpoints that used to answer **303 (success) while writing nothing** now refuse
honestly. `status=accepted` resolves case-insensitively and stores canonically as
`Accepted`; `Bogus`, `stars=99/abc/-3/4.3`, empty stars and whitespace comments each
return 422 naming what's allowed. Verified live: four refusals, and the log grew by
exactly 1 — the single legitimate write.

Errors surface as **server-rendered fragments pushed over SSE**, per-viewer so nobody
sees someone else's mistake. State lives in an atom keyed `[event-id person-id]` —
deliberately *not* the event log, because a refusal isn't history. A browser gets its
page back with every row intact; an API caller gets JSON with the allowed values.
**Zero client JS.**

One judgment call worth your review: **empty stars means refuse, not clear.**
Clearing a rating would need its own event, its own fold, and its own effect on every
mean and the coverage denominator — and treating a missing parameter as "withdraw my
opinion" is a destructive write triggered by a typo.

Also new: `docs/research/rubric-vs-our-vision.md` — swyx's known rubric line-by-line
against our doctrine, with where we're strong (product-sense judgment calls backed by
15 years of evidence) and where we're exposed (no deployed site, form builder hours
old, no walkthrough video, nobody outside the build has driven it).

---

## 5. Sessionize auto-populate: tested, and it had a real bug

You asked me to test it today. **It works** — paste a profile URL, click Import, and
it fills name, tagline, headshot, LinkedIn and bio (correctly leaving email alone,
since Sessionize doesn't publish it). Verified live against a real profile, and the
"imported values are defaults, typed values win" rule holds under test.

But it **failed the one time it mattered**. The first call after boot timed out.
Measured: a cold fetch takes ~5.5s (JVM TLS/DNS warm-up), every one after it 0.7–1.5s
— against a **5-second timeout**. So the import failed exactly once: on first use,
which is the only time a given speaker ever tries it. They'd get "we couldn't reach
Sessionize", shrug, and type their bio by hand.

Fixed (timeout → 12s plus one retry; a warm connection answers in about a second, so
the retry costs a real speaker almost nothing). Now covered in the e2e driver,
including the bad-URL path.

This matters more than it looks: the ethnography found that **headshots and bios are
nearly absent from the chase archive** — 6 and 10 mentions across 13,488 messages —
precisely because a profile import deletes that whole category of chasing. It is the
cheapest win in the corpus.

## 6. Two things I wrote for you to *run*, not read

### `docs/deploy-runbook.md` — deploy is ~15 mechanical minutes

I verified every prerequisite overnight and they are all green: project
`EXAMPLE-GCP-PROJECT-B` is ACTIVE, all three APIs (run, containerregistry,
artifactregistry) are enabled, Cloud Run is already serving six services in
us-west1, the `:jib-deploy` alias exists, gcloud is authed as you. **And the
Makefile already passes `--max-instances 1`** — exactly what a single-writer JSONL
store needs, so decision 3 is half-made already.

The runbook isolates your three decisions with a recommendation for each, then
gives the exact commands. It includes the step most likely to bite us — **booting
the production thin jar locally before pushing a container** — and a post-deploy
checklist that runs the e2e driver *against the deployed URL* (`--base https://…`)
rather than trusting localhost.

**I did not deploy.** Publishing a public URL is your call, and per-event auth
scoping is still open.

### `docs/operator-rehearsal-protocol.md` — bd `-nuk`, and I'd rank it P0

Eight tasks to put in front of Ann, phrased as *outcomes* rather than UI
instructions, with a stopwatch on task 1 (the ten-minute claim). The governing rule
is **do not help** — the entire value is watching her get stuck, and a hint destroys
the data.

My honest opinion on priorities: **this is worth more than any feature we could add
in the same hour.** We have 171 unit tests and a 107-check driver, and every one of
them verifies the software *works*. Not one verifies a person can *use* it — which
is the only thing swyx said he'd actually measure.

## 7. Queued, not yet done
- **Per-event authorization scoping** — a committee member of event A can still act
  on event B. Fine for you today, wrong before this is multi-tenant.
- **`bd -lc3`** — ~186 invalid React-habit `key=` attributes on the board (135 on the
  form page). Harmless, but a judge who views source sees SPA habits in a
  server-rendered app.
- **The walkthrough video**, which needs the deployed site first.
