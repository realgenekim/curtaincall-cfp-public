# Open sign-up: anyone creates an account, runs the whole experience

*Gene's ask (2026-08-10): let anyone sign in and go through the real
experience — create their own conference, open a CFP, review — without
touching ITRev's production events. This doc is the concrete plan: what
already works, the one click that opens the door, the walls, and the
accepted risks.*

## RATIFIED account model (Gene, 2026-08-10 ~22:00 PT — supersedes any
## narrower reading below)

1. **Anyone with a Google account can create an account.** No invitation,
   no roster precondition.
2. **A new account can immediately create an event.** Creating an event
   makes that person the event's organizer.
3. **One account, any hat, any order.** The same person can be an organizer
   of their own event, a speaker on another, and an invited committee
   member on a third. Roles attach to events, not to accounts.
4. **Organizers can grant admin powers on their event.** Two roles need
   names (bead filed): the **chair** (decision owner — Gene's seat) and the
   **admin** (operations: notifications, comms, tasks — Ann's seat). Titles
   TBD; the distinction is decision authority vs operational authority.

Motivating case: swyx or anyone on his team creates an account, becomes an
organizer, creates reviewers. Field evidence the same day: an ai.engineer team member
tried exactly this the same day and was refused by the roster gate
(a prod log audit (log withheld: contains a third party’s access trace)).

## CORRECTION to "the product already does this" (found 2026-08-10 night)

The claim below that sign-in IS sign-up was wrong for the deployed build:
`auth/may-sign-in?` gates the Google callback by ROSTER membership ("Google
proved WHO — the roster decides WHETHER"), so unrostered strangers are
refused before `find-or-create-person!` runs. Opening the doors therefore
requires a small deliberate policy change (allow any verified Google
identity through to find-or-create) shipped through the normal deploy
train, in addition to Gene's OAuth "Publish app" click.

## The punchline: the product already does this

Verified in code, nothing to build:

1. `auth-google/find-or-create-person!` — ANY Google sign-in creates a
   person record on first arrival. Sign-in IS sign-up.
2. `handlers.events/handle-create-event` — any signed-in person may create
   an event; creation auto-seats them as chair of its committee.
3. Tenancy (committee = roster + scope filter) means their events list
   contains exactly the events they hold seats on: theirs. Yours are
   invisible to them; theirs are invisible to you.

So a stranger's experience, end to end, zero code changes: sign in with
Google → account exists → "+ New event" → wizard (details → form →
committee) → live public CFP URL in minutes → invite co-reviewers → run a
real review on their own board. The product's whole pitch, self-served.

## The one gate: publish the OAuth app (Gene, ~2 minutes, Console)

Console → project `swyx-cfp-saas-killer` → APIs & Services → OAuth
consent screen:

1. Confirm **User type: External** (Internal = itrevolution.net only,
   forever, regardless of anything else).
2. Click **Publish app**.

Our scopes are exactly `openid email profile` (verified in
auth_google.clj) — non-sensitive, so publishing needs **no verification
review, no waiting, no "unverified app" warning**. Until this click,
Google 403s everyone not on the test-user list before our code runs.

Note the swyx mismatch this also fixes: his SEAT is `swyx@ai.engineer`,
but the test-user entry is his personal address — today he is blocked
with either identity. Published, his ai.engineer sign-in lands directly
in his chair.

## The walls (what "can't touch production data" actually rests on)

There is ONE database (docs/one-database.md). Isolation is logical, and
it is three-layered:

1. **Tenancy**: every organizer/reviewer surface filters by committee
   membership. A stranger holds seats only on events they created.
2. **Per-event authorization** (bead eff, folded into judge-readiness
   Package A): direct-URL access to another event's organizer routes and
   APIs must 403 with nothing written. `authz_event_scope_test` is the
   proof; this MUST be green before publishing the app.
3. **Append-only physics**: no UPDATE, no DELETE — enforced by triggers
   and the INSERT-only app role. The worst any visitor can do is append
   facts. Nothing they do can destroy, alter, or delete ITRev data; the
   worst case is junk to archive, and `backups/` holds full replayable
   snapshots bracketing every day.

## Accepted risks (eyes open, contest-window scale)

- **Public CFP submissions are open by design** — including to any ITRev
  event whose call is open. A stranger CAN submit junk to EAIS; that is
  the product working. Mitigation if unwanted during judging: close the
  EAIS call (one button, reversible fact) or simply archive junk
  submissions (facts, reversible).
- **Junk events / spam accounts**: possible; cleanup is the j/k/x archive
  sweep. If volume ever warrants, a per-person event cap is a five-line
  guard — deliberately NOT built yet (no evidence it's needed, and the
  contest window is days).
- **The Danas**: e2e drives against the shared DB already create
  disposable speakers (16 today). Same cleanup, same lesson — point
  drives at the jsonl sandbox.
- **Monitoring**: `auth.session-started` facts + Cloud Logging
  `:google-signed-in` lines answer "who arrived" today; the telemetry
  table (bead ak6) answers "what did they do" once deployed. The
  watch-query lives in the captain's log 2026-08-10.

## Order of operations (so the walls are up before the doors open)

1. Merge + deploy the judge-readiness work (Package A: authz proof,
   personas, cookie sessions) — Phase 0 of
   plans/2026-08-10-close-to-100-plan.md.
2. Gene clicks Publish app.
3. Verify as a stranger: incognito, personal Gmail → sign in → empty
   events list → create event → public CFP live → cannot reach any
   ITRev organizer URL (403), cannot see ITRev events in any list.
4. Then invite the world — and watch them arrive in the session facts.
