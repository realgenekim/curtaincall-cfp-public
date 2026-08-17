# Authentication & Authorization

*Written 2026-08-09, after the per-event scoping work landed. Describes the model
as implemented in `src/cfp_scheduler_killer/auth.clj` (the gate) with support in
`committees.clj`, `exports.clj` (API keys), and `server.clj` (handlers). History
and evidence: `logs/2026-08-09-per-event-authorization.md` and bd issues
`-53i`, `-x9j`, `-bdm`, `-o42`.*

## Design stance (read this before changing anything)

1. **Default-deny, twice over.** The gate names what is OPEN; everything else is
   refused — including routes that don't exist yet. Then, within the organizer
   side, every conference-scoped URL additionally requires membership **on that
   conference**. Both properties are pinned by tests that probe endpoints nobody
   has written.
   *Why: the original P0 (bd `-53i`) happened because an allowlist of PROTECTED
   paths gated organizer pages under `/events` while the mutations lived under
   `/api/` behind a mere "is signed in". Naming what is open cannot fail that
   way.*
2. **The roster is the permission** (`docs/design/domain-model.md`): a committee
   is a roster + a scope filter, never a permission fortress. Every reviewer on
   an event has the same reach over that event. The fence runs **between
   conferences and between role classes** (reviewer / submitter / public), not
   between peers on a committee.
3. **Refusals are honest.** Denied writes return 403 with a plain-English reason
   — never a 303 that looks like success (status codes are what lied in the
   original incident). Cross-event refusals get their own message and a
   structured log line (`:authz-cross-event-refused`). A typo'd slug 404s
   instead of 403ing, so a mistyped URL doesn't masquerade as a permissions
   problem.

## Roles

| Role | What it means | Where it lives |
|---|---|---|
| **submitter** (speaker) | Signed-in person who submits talks; owns their portal, profile, and their own submissions' answers/tasks | derived via `portal/speaker?`; ownership checked per-handler (`own-submission`, server.clj) |
| **reviewer** | Program-committee member of a specific event. Grants the full organizer side *of that event* | membership row `{:person-id :committee-id :role "reviewer"}` |
| **chair** | A reviewer with a title. **Semantics are deliberately inert today** — display only. If chair-only powers ever land, they land as a fifth gate tier, not scattered per-handler checks | membership `:role "chair"`; creator of an event is auto-added as chair |

Legacy note: memberships written before 2026-08-09 carry `:role "member"`. The
store normalizes it to `"reviewer"` **on fold** — historical events are never
rewritten (append-only doctrine). `committees/roles` accepts the alias.

A person can hold roles on different events simultaneously (chair event A,
submit to event B). The gate is ordered so their portal never depends on their
committee seats.

## The gate — `auth/wrap-require-login` → `auth/gate`

Sits **above routing** in `create-app` (server.clj), so unknown URLs and static
resources are gated too. Four tiers, evaluated in order per request:

```
1. public-path? uri            → through (no session needed)
2. no session                  → 302 /login?next=<uri>
3. speaker-path? uri           → through (session only; ownership per-handler)
4. organizer? person           → else: GET → 302 /portal, write → 403
   └─ event-id-for-uri:
        nil (unscoped)         → through (any reviewer anywhere)
        :unknown (bad slug/id) → GET passes (router 404s), write → 403
        <event-id>             → member-of-event? → through, else 403 wrong-event
```

Implementation notes that are load-bearing:

- **The decision lives in the `gate` var, called per request.** `create-app`
  composes middleware once at boot, so a closure there is frozen — `wrap-reload`
  can never replace it. We learned this the expensive way: a live drive
  "verified" a security change against a dev server still running the old cond
  and reported six false failures. If you refactor auth, keep the per-request
  var indirection, and **restart the server before believing any live drive**.
- **Speaker tier before organizer tier** — a chair of event A who submits to
  event B must keep their portal regardless of committee seats.
- **Cross-event refusal applies to GETs too.** A page that renders another
  event's API token (settings) leaks as much as a write.

### Scope resolution — `event-id-for-uri`

One resolver maps a URI to the conference it acts on, so **new routes inherit
scoping instead of remembering to add it**: by slug (`/events/:slug/…`,
`/api/events/:slug/…`, …), via submission (`/api/submissions/:id/…` →
`:event-id`), via committee (`/api/committees/:cid/…`), via membership
(`/api/memberships/:mid/…` → committee → event). `unscoped-paths` is an explicit
set (`/events`, `/events/new`, `/api/events/create`, `/api/events/demo`,
`/api/sse`, …) because `/api/events/create` is spelled like
`/api/events/:slug/…` and must not resolve to a conference named "create".

Returns three states — `nil` (genuinely unscoped), an event id, or `:unknown`
(names a conference we don't have). `:unknown` is deliberately not collapsed
into either neighbor; see the honesty rule above.

### What is public (no session)

- `/cfp/:slug` + `/api/cfp/:slug/submit` + `/api/cfp/:slug/import-sessionize` —
  the public CFP; the account wall at submission time is incumbent behavior we
  reject. The Sessionize import only ever fetches
  `https://sessionize.com/<handle>/` (URL is normalized+validated in
  `sessionize_import.clj`; SSRF cases pinned in its test).
- `/agenda/:slug`, `/events/:slug/exports/*` (sessions.json, speakers.json,
  calendar.ics), `/events/:slug/llms.txt`, `/api/v1/**` — published data only
  (Accepted AND notified); a valid API token widens (unpublished rows,
  `?status=` filter).
- `/login`, `/api/login`, `/auth/:token`, `/logout`, static assets,
  `/dev/reload-check`.

Prefix matching is **segment-boundary** (`prefix-match?`): `/cfp` covers `/cfp`
and `/cfp/…` but not `/cfpanything`. Asset roots written with a trailing slash
keep plain prefix semantics.

### What a signed-in submitter can reach

`/`, `/portal`, `/api/profile`, `/logout`, and
`/api/submissions/:id/(answers|task)` — the last only for submissions where the
person is listed as a speaker (`own-submission`, checked in the handler; others
get not-found, not 403).

## Sign-in — magic links

Passwordless: `POST /api/login` with an email → `issue-token!` mints a one-time
token → `/auth/:token` redeems into a session. **Eligibility**
(`may-sign-in?`): the email is on some committee roster, or has submitted a
talk. There is no signup.

Dev (`ENV=dev`): the magic link is rendered on the page (SMTP honesty rule —
letters are recorded, not silently dropped). **Known P0 for deploy (bd
`-o42`)**: on a deployed site with SMTP off, nobody can complete sign-in and the
eval judge scores nothing. The judge path (seeded demo accounts + a non-dev
sign-in mechanism) must land before the URL goes to swyx.

## Committee lifecycle

- **Creating an event auto-adds the creator as chair** — the only implicit
  membership in the system. Everyone else is explicitly added, per event
  (Gene's ratified "start with no reviewers" model, 2026-08-09).
- **Member management is self-scoped**: adding/removing members of event E
  requires being a reviewer of event E.
- **The last reviewer of an event cannot be removed** (422 with a plain-English
  reason) — otherwise one misclick orphans the conference forever.
- **First-run bootstrap** (`first-run?`): when the store has **no events at
  all**, any signed-in person may create the first event (and `issue-token!`
  will admit an unknown email, loudly logged). The moment one event exists the
  window closes — permanently, since events can't be deleted from an
  append-only log. *This replaced the old "no memberships anywhere" rule, under
  which deleting the last membership silently promoted every signed-in person
  to organizer, and speakers could create events.*
- Consequence to know: a hand-seeded log containing events but zero memberships
  is locked shut for everyone. The guards above make that state unreachable
  through the UI; if you manufacture it in a test fixture, add a membership.

## API keys (Settings → API keys)

Per-event, event-sourced (`api-key.created` / `api-key.revoked` folds in
store.clj; helpers in exports.clj):

- **Create**: label + button → 32-char url-safe `SecureRandom` key, shown
  **exactly once** in the re-rendered page. Stored plaintext in event settings
  (accepted for this single-tenant tool), never logged.
- **List**: label, 8-char prefix + "…", created-at. Full key never re-shown.
- **Revoke**: server-rendered two-step confirm (no `confirm()` dialogs —
  Datastar house rule). Revoked keys drop out of the projection; the log keeps
  history.
- **Check**: `exports/valid-token?` accepts the legacy `[:settings :api-token]`
  OR any live key, compared with `MessageDigest/isEqual` (constant-time).
  Tokens arrive via `Authorization: Bearer` or `?token=` (query form kept for
  export links; nothing logs it).

Open item: the legacy `:api-token` is still minted per event and is invisible
to the new UI (no label, no revoke). Fold it in or retire it before this is
multi-tenant.

## Tests that pin all of this

- `test/cfp_scheduler_killer/authz_security_test.clj` — the original speaker/
  organizer separation, asserting the **append-only log**, not just status
  codes (status codes are what lied), including a not-yet-written endpoint.
- `test/cfp_scheduler_killer/authz_event_scope_test.clj` — two events with
  disjoint committees: cross-event reads and writes refused with zero log
  growth, same-event access intact, speaker surfaces intact, unknown endpoints
  under another event refused, API-key round trip (create → export with
  `?token=` → revoke → 401 → legacy token still honored), narrowed bootstrap,
  `/cfpanything` is not public, last-reviewer guard.
- `bin/e2e_drive.py` §7d/§7e — the same properties against the LIVE server
  (144/144 as of 2026-08-09). Run it after any auth change; in-process tests
  cannot catch wiring defects, and remember the restart rule above.

## Known gaps (honest list, 2026-08-09)

1. `/events` still lists every conference's name/slug to any reviewer of any
   event (links 403 on click). Metadata leak, cheap to scope, deliberately
   deferred because several tests assert that page's contents.
2. Legacy `:api-token` unmanaged (above).
3. Judge/deploy access — bd `-o42` (above), plus in-memory Ring sessions die on
   a Cloud Run instance recycle.
4. `/cfp/:slug/submitted/:submission-id` is a public confirmation page guarded
   only by the submission id being unguessable.
5. Chair is display-only; no admin tier exists (deliberate — see design stance).
