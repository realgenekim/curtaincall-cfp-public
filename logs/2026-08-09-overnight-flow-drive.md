# 2026-08-09 (overnight) — Driving the whole flow, hunting for what isn't perfect

**What was asked.** Gene, heading to bed: *"drive the application, create a new
event, make sure it persists. create PC user. create CFP. create submission.
judge it. Make sure the flow is perfect. keep going for 2 full hours! The bar is
sessionize."* Later: *"you have 5 hours!!!"*, plus two feature asks — Airtable
sync on each CFP submission, and a place to configure Slack channel
notifications (submission arrival, judging actions) — and a standing instruction
to write manual-testing results into a captain's log.

**How it was driven.** Two instruments, deliberately:
1. `bin/e2e_drive.py` (new) — walks the LIVE server over HTTP with three separate
   cookie jars (Gene, Ann, an anonymous speaker) and asserts on what each one can
   see. This is what catches *wiring* defects: routes, redirects, sessions,
   persistence, sink fan-out. In-process tests can't see any of that.
2. The real browser, for the things only eyes catch: layout, empty states,
   whether a screen reads like a tool a conference organizer would trust.

---

## Baseline: the happy path holds

`python3 bin/e2e_drive.py` → **89/89 checks passed** on the first clean run.
Covered, end to end, against a freshly created event:

- Magic-link sign-in; `/events` renders with the signed-in identity.
- **Event creation with the slug left blank** → slug auto-derived from the name;
  redirect to the dashboard; `event.created` in the append-only log with every
  field preserved (name, dates, tz, CFP window, support email, location, website).
- Auto-committee spawned; seed form installed; event appears in the list.
- **PC member added** (Ann Perry) → visible on the roster, `member.added` logged,
  and Ann can independently sign in and open the review board.
- **Public CFP** loads with no sign-in, renders 20 fields, asks for no password.
- **Submission** by an anonymous speaker → confirmation page repeating the title,
  `submission.created` logged, and the talk immediately visible to the organizer
  on both the submissions list and the review board.
- **Judging**: Gene rates 4 + comments, Ann rates 5; the board counts both reviews
  and shows the comment inline; Ann can see Gene's rating (open visibility).
- **Decision**: status → Accepted; the detail page reflects it **and the speaker
  has not been told** (inform gate intact).
- **Exports**: sessions.json / speakers.json valid, calendar.ics is real
  iCalendar, llms.txt renders — and the accepted-but-uninformed talk is correctly
  **absent** from sessions.json (publish gate holds).
- All 10 organizer pages return 200 with no exception text.

### Two things that looked like bugs and were not

- **"Timezone silently reverted to New York."** The driver posted `timezone=`;
  the form field is `tz`. The app was right, the driver was wrong. Worth naming
  because the *shape* of the failure — an unrecognized field falling back to a
  default, silently — is the one the house rules warn about. The form is the only
  writer today, so this is a note, not a defect.
- **"Public CFP renders no form."** The event's CFP opened *tomorrow*, and the
  page correctly said so: *"The call for speakers hasn't opened yet. It opens
  Aug 10, 2026 9:00 AM."* Correct behavior, well worded.

### One thing that is better than the incumbent

A validation failure **does not eat what the speaker typed**. Posting a
half-filled form returns 422 with *"Almost there — a few fields need attention"*,
per-field messages inline, and the title, abstract and name still in the boxes.
That is now a permanent check in the driver (`4b`), because losing a
600-character abstract to a validation error is exactly the thing that makes a
speaker hate a CFP tool.

---

## Findings from the visual pass

(appended as the drive continues)

### Dashboard (fresh event, 1 submission) — strong

Reads like a tool that knows what state the event is in: a
"1 submission awaiting a decision → Review Board" banner across the top, the
public CFP URL as a real clickable link, and a **"What's next" checklist that
tracks real state** (Event created ✓, Invite your Program Committee ✓, Call for
speakers opens ✓, First submission arrives ✓). This is the Sessionize "Smart Task
List" idea — the one part of Sessionize worth stealing — and ours is honest about
what it can't yet track.

**Gap: the Form editor does not exist.** "Form" in the left nav is greyed out,
and the checklist item "Review the seed form (11 questions + speaker block)"
carries the note *"reviewing the form starts tracking once the form editor
exists."* The form is data (`form.installed`), and the CFP renders from it, but
there is no screen to edit it. Filed.

---

## 🔴 The serious one: a speaker could accept and decline talks

Driving the app as a **real speaker session** (an account created merely by
submitting a talk) rather than as an organizer is what found this. The speaker
successfully appended, as themselves:

```
rating.set                   actor: priya…@example.com   (rated a proposal 1 star)
submission.status-changed    actor: priya…@example.com   (Accepted -> DECLINED)
submission.priority-toggled  actor: priya…@example.com
schedule.locked              actor: priya…@example.com
```

**Root cause.** `auth/wrap-require-login` matched organizer *pages* by URL prefix
(`organizer-prefixes ["/events"]`). Every *mutation* lives under `/api/…`, which
does not match that prefix — so those endpoints required only "is signed in", and
every speaker is signed in. `/api/events/:slug/…` failed the check too, because
`"/api/events/x"` does not start with `"/events"`.

**Why nothing ever complained:** each of those requests answered **303**. A
redirect is what success looks like. The system was cheerfully reporting that it
had done the thing it refused to do — except it wasn't refusing.

**Fix (auth.clj): invert the default.** The gate now names what is OPEN — public
paths, and the speaker's own portal / answers / tasks / profile — and requires
committee membership for **everything else, including routes that don't exist
yet**. An allowlist of *protected* paths is exactly how this hole appeared; naming
what is open cannot fail the same way. Denied writes return **403 with a
plain-English reason**; a speaker who merely wanders onto an organizer page is
still redirected to their portal rather than scolded.

`test/cfp_scheduler_killer/authz_security_test.clj` — **5 tests, 37 assertions,
passing.** Two deliberate choices in it:
1. It asserts the **append-only log**, not just status codes. Status codes are
   what lied.
2. It asserts that a POST to an endpoint **nobody has written yet** is refused.
   That is the property that keeps this fixed, rather than fixing it once.

**Still open:** authorization is not scoped *per event* — a committee member of
event A can act on event B. Filed, not yet done.

## The same defect class, one tier down (bd xmf)

`POST .../status`, `/rate`, `/comment` return **303 as if they worked** while doing
nothing, whenever the *value* is unrecognized:

| posted | result |
|---|---|
| `status=accepted` (lowercase) | 303, **nothing appended** |
| `status=Accepted` (exact) | 303, works |
| `status=Bogus` | 303, nothing |
| `stars=99` / `abc` / `''` / `-3` | 303, nothing |
| `body=''` / whitespace | 303, nothing |

Unknown submission **ids** correctly 404 — it is only bad **values** that vanish.

The strongest argument against this is what it did to my own instrument: the
driver posted lowercase `accepted`, the app ignored it, and the assertion
"detail reflects Accepted" **passed anyway** — because the word "Accepted" appears
in the status dropdown. A silent no-op manufactured a false pass in the test
written to catch it.

## Sessionize import: worked, but failed the only time it mattered

The killer speaker-side feature (paste a profile URL, click Import) **is built and
does work** — it fills name, tagline, headshot URL, LinkedIn and bio, and correctly
leaves email alone. Verified against a live profile: `Tessa Kriesel`, and the
"imported values are DEFAULTS, typed values win" doctrine holds under test.

But the **first** call after boot failed: `:sessionize-fetch-failed :msg Read
timeout`. Measured: cold fetch ~5.5s (JVM TLS/DNS warm-up), every subsequent fetch
0.7–1.5s, against a 5s timeout. So the import failed exactly once — on first use,
which is the only time a given speaker ever tries it. They'd get "we couldn't reach
Sessionize", shrug, and type their bio by hand.

Ruled out along the way: our polite User-Agent is **not** being blocked (curl with
the identical UA gets 200 in 2.5s).

**Fix:** timeout 5s → 12s, plus one retry. A warm connection answers in about a
second, so the retry costs a real speaker almost nothing and converts the one
failure that actually happens into a success.

The error path, for the record, was already good: *"We couldn't reach
https://… just now. You can fill the fields in by hand."* with every typed field
preserved.

## Robustness sweep — all clean

- **XSS**: submitted `<script>alert(1)</script>` and `<img src=x onerror=…>` in the
  title, bio and abstract. Rendered as inert escaped text on the board; no live
  attribute anywhere. (An `onerror=` grep hit turns out to be *inside*
  `&lt;img …&gt;` — text, not markup.)
- **Unicode/emoji**: Swedish characters and emoji round-trip intact.
- **Submission cap**: three succeed, the fourth is refused with *"You've reached
  this event's limit of 3 submissions. Contact the organizers if you need an
  exception."*
- **Sink fan-out** (`bin/sink_probe.py`): registered a live webhook listener and
  confirmed real deliveries for `submission.created`, `rating.set` and
  `comment.added` — so "Airtable syncs on each CFP" is true of the path, and only
  needs credentials.
- **Speed**: pages render in ~130ms. (One 120s hang during testing was my own
  runaway regex, not the app.)
- **Speaker privacy**: committee comments, ratings and the private
  notes-to-committee field are absent from the portal HTML.

## Still open at the end of the drive

1. **No deployed site** — the brief requires one (bd ghz). Blocked on three
   decisions from Gene: GCP project, SMTP or not, storage on ephemeral disk.
2. **Per-event authorization scoping.**
3. **bd xmf** — the silent no-ops above.
4. **Slack notification settings** — the sink registry supports Slack; Settings has
   webhooks, Airtable and push email, and no Slack section.
5. **Datastar-native inline validation** — Gene's "avoid js boofarama" ask.
6. **Discord** — three asks (swyx's newly-released evals, his role-view links, how
   ultracode was used) are blocked on a login only Gene can perform.

---

## Second half of the drive — what landed and what else turned up

**Everything green at the end:** `make runtests-once` → **171 tests, 1544
assertions, 0 failures**. `python3 bin/e2e_drive.py` → **107/107** against the live
server.

### The form builder landed and I drove it

`/events/:slug/form` now lists every question with its stable id, type and
required/private/locked/retired flags; adds (6 types, flags, help, max-length,
select options, radio-vs-dropdown); edits, reorders, retires and restores — beside
a live preview running the **actual public renderer**.

Driven end to end: added a radio question *"Which AI coding agents are in daily use
on your team?"* → confirmed on the public CFP → a speaker submitted answering it →
stored under `which-ai-coding-agents-are-in-daily-use-on-your-team` and rendered for
the committee. That is Gene's "test creating custom forms for CFP", satisfied.

Best thing on the page is a sentence:

> **8 submissions already exist.** Editing the form now is safe: every submission
> carries its own snapshot of the questions it was answered against, so nothing you
> do here rewrites what a speaker already said.

### The security fix, verified in the environment that had the bug

After restarting to pick up the middleware (it is composed at startup, so hot reload
could not activate it), all five escalation attempts return **403 with nothing
appended**. And the property the inversion was built for showed up on its own: the
gate **automatically protected all six brand-new form-builder endpoints** —
`/api/events/:slug/form/{add,update,move,retire,retire-ask,restore}` — routes that
did not exist when the fix was written. Demonstrated, not claimed.

### A new finding: a known-impossible schedule reaches the public

The organizer's schedule page handles conflicts *well* — "1 conflicts" plus a named,
non-blocking chip: *"⚡ Marcus Devlin is also in no room yet at 10:30am"* offering to
move either talk. That is doctrine #6 done right.

But the **same conflict is published**: `/agenda/…` and `sessions.json` both show
Marcus Devlin in two sessions at 10:30am. A judge reads that as a data bug, not a
draft state. The distinction worth drawing: a *partial* state (room TBD) is
legitimate to publish; a *conflict* is not undecided, it is known to be wrong.
Filed as bd `-31b` — it is a doctrine call, not a mechanical fix.

### A mistake I made, recorded because it is the lesson of the night twice over

I first concluded the schedule page did **not** flag conflicts at all. It does. I
had been reading the body of a **302 redirect** — my organizer cookie had expired —
and an empty body contains no conflict language, so the grep "proved" a defect that
did not exist.

That is the same failure mode as the 303s that hid the security hole: **a response
that means "no" being read as data.** The fix in both cases is the same discipline —
check the status before you believe the body, and assert on the durable record
rather than on the response.

### Housekeeping

- **Demo store reset and reseeded.** Testing had polluted it with cap tests and an
  XSS probe whose talk title was literally `<script>alert(1)</script>`. Back to the
  clean eight-submission Charlotte world.
- Cleared a stale `.git/index.lock` (0 bytes, no git process running) and killed two
  runaway `ugrep` processes left over from my own catastrophic regex.
- Wrote `docs/deploy-runbook.md` (prerequisites all verified green; three decisions
  isolated for Gene) and `docs/operator-rehearsal-protocol.md` (bd `-nuk`).

### Filed overnight

`-53i` (closed, fixed+verified) · `-lpm` (closed, verified+fixed) · `-ghz` deploy ·
`-nuk` operator rehearsals · `-xmf` silent no-ops · `-eff` per-event scoping ·
`-31b` published conflict · `-uxw` materials ledger · `-kzt` assisted chasing ·
`-r6c` employer-approval states · `-ifs` durable program emission · `-lc3` stray
`key=` attributes · `-pkx` Google auth.

### Privacy guarantee re-verified after the form builder changed the field pipeline

The private `notes-to-committee` answer (*"Happy to be scheduled opposite anything —
I know I'm not the headliner"*) is **absent from every public surface**: the agenda,
sessions.json, speakers.json, calendar.ics, llms.txt, the public REST API, the CFP
page itself — **and from the token-authenticated API**, which widens what you can
read but must never widen it to this. A token buys you unpublished rows, not the
committee's private channel.

Worth noting *how* this was checked, because the first attempt lied. A shell
one-liner using `grep -c … || echo 0` emitted `0\n0`, which failed a `= "0"` test
and reported **every surface as LEAKED**. I nearly wrote up a privacy breach that
did not exist. Redone in Python, where the comparison is trustworthy, all eight
surfaces pass.

Third time tonight the same lesson: **the instrument lied before the app did.**
303s that looked like success, a 302 body read as page content, and now a broken
shell comparison. Assert on the durable record, and distrust a result you did not
watch the harness produce.

### Doctrine #8 (stable ICS UIDs) verified

`calendar.ics` emits `UID:<submission-uuid>@cfp-scheduler-killer.local` — a stable
opaque id, not a timestamp — identical across repeated fetches, with a `SEQUENCE`
that increments (13, 13, 9 on the seeded talks). So a calendar client treats a room
or time change as an **amendment to the existing event, not a second invitation**.
That is the answer to "the room gets assigned late", and it works today.

### README quickstart re-checked against reality

All four promised targets exist (`server-dev`, `seed-demo`, `runtests-once`,
`store-reset`), `data/` is gitignored so a clone genuinely starts empty, and nothing
in the boot path references a database. The zero-setup claim a judge will test in
their first two minutes is literally true.

---

## Final batch: Slack notifications and honest refusals

Both of Gene's remaining feature asks landed. **186 tests, 1697 assertions, 0
failures. `bin/e2e_drive.py` 127/127** (up from 107 — the new checks read the
*log*, not the page).

### Slack (Gene: "a place for Slack channel notification on CFP arrival, judging action, etc.")

Configured from Settings. Moments are grouped in organizer language rather than
raw event types — *A talk arrives · The committee acts · A decision is made · A
speaker is told* — with arrival and decisions on by default. Copy worth keeping:
*"pick which moments are worth interrupting people for"*, and *"Private answers are
never posted — a channel is a wider room than the programming committee."*

Messages are built from **named fields** (title, speaker line, the new average),
never from a dump of answers, so a private note cannot reach a channel by
construction. Verified by reading the builder, not just by trusting the tests.

The webhook URL must start with `https://hooks.slack.com/services/` — which is why
my attempt to point it at a local listener was refused, with a clear message
delivered through the new notice surface. Correct behaviour beating my
convenience.

### Refusals that look like refusals (bd -xmf, closed)

Verified live: the four inputs that used to vanish now each return 422 naming what
is allowed, and the log grew by **exactly 1** — the single legitimate write.
`accepted` now resolves case-insensitively and stores canonically as `Accepted`.

The design note I'd keep: **empty stars means refuse, not clear.** Clearing would
need its own event, its own fold, and its own effect on every mean and the coverage
denominator — and treating a missing parameter as "withdraw my opinion" would be a
destructive write triggered by a typo.

Errors surface the Datastar way: `notices.clj` holds them in an atom keyed
`[event-id person-id]`, deliberately **not** the event log, because a refusal is not
history. Pushed per-viewer so nobody sees someone else's mistake. A browser gets its
page back with every row intact; an API caller gets JSON with the allowed values.
Zero client JS.

### A flaky test I had to catch myself

My run of the full suite came back **182/1662/1** where the builder had seen green.
The `pc-push` sink fires asynchronously off `submission.created` and appends its own
`comms.rendered`; land that between a baseline and its assertion and the test reports
10 where it expected 9 — **indicting the write we had just refused for a line it
never wrote.** The refusal was correct every time; the ruler was moving.

`log-size` now drains sinks first, so every "nothing was appended" assertion in that
namespace measures a settled log. This is the fourth time tonight the instrument was
wrong before the app was.

### Closing state

- Demo store **reset and reseeded** one final time: one event, eight realistic
  submissions, no test debris.
- Server running clean on :20500.
- Filed on the way out: `-sjp` (the Airtable sink writes a RELATIVE submission URL
  into the base, so the link is dead from Airtable — reported by the builder,
  deliberately not fixed hastily because it would move existing assertions).
