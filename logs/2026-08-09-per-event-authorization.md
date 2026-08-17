# 2026-08-09 — Per-event authorization, named API keys, and the middleware that lied

**Asked for** (bd sessionize-sched-killer-x9j, -bdm, plus two ratified amendments
mid-flight): scope authorization to the conference the URL is about; tighten the
public-path allowlist; add named API keys to Settings; verify the Sessionize
import's URL allowlist; narrow the bootstrap window; rename the committee role
to *reviewer*; guard the last reviewer.

## The hole

`auth/organizer?` asked one GLOBAL question — "is this person on ANY committee of
ANY event?" — and every organizer route was satisfied by a yes. One reviewer on
one event's committee could therefore open every other event's **Settings page
(which prints its API token)**, repoint its Slack webhook, register a webhook,
lock its schedule, flip acceptances, and — the part that makes it permanent —
add themselves to its committee via `/api/committees/:cid/members/add`, whose
handler resolved the committee from the path with no caller check at all.

## What was built

- **`auth/event-id-for-uri`** resolves the conference a request acts on: by slug
  (`/events/:slug`, `/api/events/:slug`, `/agenda`, `/cfp`, `/api/v1/...`), or
  through a submission, a committee, or a membership id. It returns `nil` for
  the handful of genuinely unscoped routes (an explicit set, because
  `/api/events/create` is spelled exactly like `/api/events/:slug/…`), and
  `:unknown` for a URL that names a conference we don't have.
- **The gate is now four tiers**: public → session → the speaker's own surfaces →
  organizer-somewhere → **reviewer OF THIS EVENT**. The speaker tier moved ABOVE
  the organizer tier on purpose: Gene chairs one conference and submits to
  another, and his portal must not depend on which committees he sits on.
  `:unknown` + POST is 403 (a 404-vs-403 split on a guessed id is how you
  enumerate ids); `:unknown` + GET falls through so the router 404s honestly.
- **Prefix matching at segment boundaries.** `"/cfp"` matched `"/cfpanything"`,
  and on an allowlist too generous means open.
- **Named API keys** per event (`api-key.created` / `api-key.revoked` folded into
  event settings), 32 url-safe chars from SecureRandom, constant-time compare,
  full material shown exactly once at creation and never again — the list shows
  label + 8-char prefix. The legacy `[:settings :api-token]` still validates.
- **Narrowed bootstrap**: the first-run window is now "no events exist", not "no
  memberships exist". The old rule meant removing the last reviewer promoted
  every signed-in person to organizer, and a speaker could create events.
- **Last-reviewer guard**: refusing the removal that would seal a conference shut
  forever (422, plain English).
- **Role vocabulary**: `reviewer` | `chair`. The log is append-only and full of
  `"member"`, so it is accepted on read and normalised in the `member.added`
  fold. Not one historical event was rewritten.
- **Sessionize import**: verified, not changed. `normalize-profile-url` already
  refuses anything but a sessionize.com profile and returns a canonical
  `https://sessionize.com/<handle>/` — which is what actually gets fetched, so
  even the `http://` spelling can't redirect the server elsewhere. Added the
  SSRF cases (169.254.169.254, localhost, `file://`, userinfo@) to its test.

## The drive, and the instrument that lied (again)

Ran `bin/e2e_drive.py` against the already-running dev server on :20500. Six
checks in the new per-event section FAILED — settings 200, Slack 303, log grew by
three lines. The code was correct; **the server was not running it**.
`create-app` composes `auth/wrap-require-login` by VALUE at boot, so the returned
closure froze the old `cond`. `wrap-reload` recompiles namespaces but cannot
replace a closure that was already built — the fns that closure *calls* reload,
its own body never does.

Booted a second server on :20501 from the same tree: **144/144**, including all
six. Then split the decision out into `auth/gate` (a var the closure calls per
request), rebooted, and drove it again: 144/144. A security rule you cannot
observe changing is a security rule you cannot verify.

Extended `bin/e2e_drive.py` permanently with §7d (cross-event refusals + zero log
growth) and §7e (API key create → use → 2-step revoke → 401), so this finding
can never be a one-time observation.

## Evidence

- `make runtests-once`: **198 tests, 1849 assertions, 0 failures** (baseline
  before this work: 186 / 1697 / 0).
- `bin/e2e_drive.py --base http://localhost:20501`: **144/144**, twice — once
  before the `gate` split and once after.
- Gene's local store re-folds clean: one event, three reviewers, two of them
  carrying the retired `"member"` role in the log.

## Test setup adapted (old bootstrap dependence)

`forms_test` and `replay_test` drove events created straight through
`events/create-event!` — no HTTP handler to auto-chair the creator, so an empty
roster and (under the new rule) an unreachable event. Both now add the reviewer
they drive as, which is exactly what the real create handler does.
`validation_test`'s "unknown submission 404s" became "unknown submission is
refused (403)" — see the `:unknown` rule above.

## Still open

- `/events` lists every conference on the instance to any organizer. The links
  now 403, but the NAMES leak across events. Cheap to scope; deliberately not
  done here (it changes what several tests expect about that page).
- Legacy `:api-token` is still generated per event at creation. It is the one
  credential with no label, no prefix display, and no revoke button.
