# The anti-Sessionize API — stable IDs, a full read API, and docs a scraper-author would love

*bead `sessionize-sched-killer-vi9` · 2026-08-09 · one of four parallel streams on this tree*

## What was asked

Gene wrote a Sessionize scraper for years. The evidence for why it hurt is in
`docs/research/post-conference-corpus-survey.md`: ask three ITRev systems how many talks
Jason Cox has given and you get **6 / 9 / 12**, all live, all defensible — because *there is
no stable speaker id and no stable talk id anywhere in the estate*. Every join is a fuzzy
match on a display-name string.

So: close the gap between what we already emit (sessions.json, speakers.json, calendar.ics,
llms.txt, a sessionboard-shaped `/api/v1`, webhooks, named API keys) and **"I never need to
scrape again"**:

1. stable IDs on every entity in every payload, on both sides of every join;
2. a full read API — event, submissions, speakers, schedule, change feed;
3. status + notified visibility behind a token;
4. a public docs page with curl for every endpoint.

## Starting state (read before touching anything)

- `exports/publishable-sessions` (landed today, commit 7508ff9) withholds known-conflicted
  sessions from every PUBLIC surface; the organizer pools and the authed API deliberately
  still see everything. Invariant to preserve on both sides.
- `exports/public-answers` strips every `:private true` field per the submission's own
  form snapshot. Private fields never leave, token or not.
- `auth/open-data-pattern` = `^/(events/[^/]+/(exports/|llms\.txt)|api/v1/)` — anything under
  `/api/v1/` is public by construction, which is why the docs page lives at
  `/api/v1/events/:slug/docs` (an HTML page under a public prefix) rather than at
  `/events/:slug/api-docs`, which would have needed an edit to `auth.clj` — another stream's file.

## What shipped

### 1. Stable ids on every entity, on both sides of every join

| Payload | Added |
|---|---|
| `sessions.json` session | `id`, `speakerIds` |
| `speakers.json` speaker | `id` (PERSON id), `sessionIds` |
| `/api/v1` session | `eventId`, `speakerIds`, `speakers[].id`, `notifiedAt`, `schedule{}` |
| `/api/v1` schedule item | `sessionId`, `blockId`, `roomId` |
| `/api/v1` room | `id` |
| `llms.txt` | a Notes line telling an agent never to match on a name |

`speakers.json` is now built by a shared `exports/aggregate-speakers`, which merges on
the **person id** and keeps insertion order via an explicit order vector (a Clojure map
past eight keys is unordered, so a naive reduce would have produced a payload that
reshuffled between two identical calls).

The additive keys are pinned, not just added: `sessions-json-shape-test` still asserts
every ai.engineer probe key is present and now asserts the *difference* is exactly
`#{"id" "speakerIds"}` — so a third key cannot drift into someone else's published site.

### 2. The full read API

New, all under `/api/v1` (public prefix by construction):

```
GET /api/v1/                              service index — generated from exports/api-endpoints
GET /api/v1/events/{slug}                 discovery document: ids, dates, tz, CFP state, links
GET /api/v1/events/{slug}/docs            the HTML reference (public)
GET /api/v1/events/{slug}/submissions     the FUNNEL — token; every status + notified/notifiedAt
GET /api/v1/events/{slug}/speakers/{pid}  one human, by the id that survives a job change
GET /api/v1/events/{slug}/schedule        days, rooms, placements, blocks, unscheduled
GET /api/v1/events/{slug}/rooms           rooms with stable ids
GET /api/v1/events/{slug}/changes?since=  incremental change feed — IDS ONLY, monotonic seq
```

Design calls worth recording:

- **`/sessions` is the program; `/submissions` is the funnel.** They can return the same
  rows and still be two different questions. A consumer who has to remember `?status=all`
  to ask the second one will forget.
- **The change feed carries ids only** — no payload bodies, no actor emails, no answers.
  That is partly hygiene (a change feed says *what* moved, the caller re-reads the entity)
  and partly a structural guarantee: this endpoint can never become a second, unreviewed
  way for a private field to leave the building. `seq` equals `scheduleVersion`, so
  `?since=<last seq>` is a complete sync protocol in one query parameter.
- **`counts.submissions` is token-gated.** How many proposals an event received is the
  organizer's business until the organizer says otherwise.
- **An unannounced speaker 404s, not 401s.** A 401 would let anyone probe for speakers
  who have not been told yet.
- **`api-endpoints` is data.** The service index, the docs page, the Settings endpoint
  list and the tests all render from the same vector, so an endpoint cannot ship
  undocumented.

### 3. Conditional GET — polling without being rude

Every open-data response now carries an `ETag`, and `If-None-Match` gets a bodiless 304.
The tag is a content hash, except for `calendar.ics`, which is version-based
(`W/"ics-<scheduleVersion>"`): RFC 5545 requires a fresh `DTSTAMP` in every VEVENT, so
its bytes differ on every fetch and a content hash would be technically correct and
practically useless — a calendar client polling hourly would never once get a 304.

### 4. The public docs page

`views/api-docs-page`, served at `/api/v1/events/:slug/docs`. Public **because of where it
lives**: `auth/open-data-pattern` makes everything under `/api/v1/` reachable without a
login, so the reference needed no change to `auth.clj` (another stream's file). Linked from
the organizer's Exports & API page, from `llms.txt`, and from the service index.

Generated from `exports/api-endpoints`, with a runnable curl line per endpoint. It opens
with the sentence the whole bead is about: **"You do not need a scraper."**

## Evidence

Live curl against `:20500` (seeded Charlotte event):

- `sessions.json` session `decea5b3…` carries `speakerIds ["d2fba20e…"]`; `speakers.json`
  speaker `d2fba20e…` carries `sessionIds ["decea5b3…"]`. The join closes with no string
  comparison anywhere.
- `/api/v1/` → 200 with 15 endpoints; `/api/v1/events/<slug>` → 200 with a links map.
- `/api/v1/events/<slug>/schedule` → 3 rooms, day-1 items each naming a `roomId` that
  resolves against the rooms in the same payload.
- `/api/v1/events/<slug>/submissions` and `/changes` → 401 without a key, 200 with one;
  8 submissions with `status` / `notified` / `notifiedAt` visible.
- `/changes?since=90` → 4 rows, `slot.assigned` with `submissionId` + `roomId` and nothing
  else. No payload bodies.
- ETag round trip: `W/"73e94461"` → 304; stale tag → 200; `calendar.ics` `W/"ics-94"` → 304.
- `notes-to-committee` sweep across 7 endpoints (authed and not): **0 hits everywhere.**

Tests: `bin/kaocha unit` — **224 tests, 2199 assertions, 0 failures** (was 217 / 2019).
Five new deftests: stable-ids-close-every-join, api-read-surface, private-fields-on-the-
new-surfaces, conflicted-sessions-stay-off-the-new-public-surfaces, conditional-get,
api-docs-page, plus a server-test for anonymous reachability of the whole `/api/v1` surface.

`python3 bin/e2e_drive.py` — **253/253 checks passed**, including a new section 7f that
drives the API as a scraper-author would: a cookie-less client, then the same client
holding a minted key.

## Two things found on the way

1. **The unauthenticated `/api/v1/.../sessions` was leaking conflicted sessions.**
   Commit 7508ff9 withheld known-conflicted sessions from sessions.json, speakers.json,
   the ics feed, llms.txt and the public agenda — but the REST default path still used
   `published-sessions`, so the one surface an integrator is most likely to consume was
   the one that still published a talk we knew was double-booked. `api-sessions` now takes
   an `authed?` flag: anonymous callers get `publishable-sessions` (the public gate),
   token holders keep seeing everything, because you cannot fix a conflict you cannot see.
   Pinned by `conflicted-sessions-stay-off-the-new-public-surfaces-test`, which asserts
   both halves and the republish-on-resolve.

2. **The `Etag` header case trap in the drive.** `http.client` title-cases what it parses,
   so the server's `ETag` arrives as `Etag` and a literal `.get("ETag")` returns None —
   which reads exactly like "the server never sent one". It cost one false failure before
   `header_of()` went in. Same class of bug as the `&apos;` lesson already recorded in
   `text_of`: an instrument that lies about the app is worse than no instrument.

## Open / handed off

- **`/api/v1` (no trailing slash) is not public.** `auth/open-data-pattern` matches the
  prefix `api/v1/` *with* the slash. Both spellings are routed and the slashed one is what
  the docs, llms.txt and the service index print, so nothing is broken — but the fix is one
  character (`api/v1/?`) in `auth.clj`, which belongs to the auth stream. Asserted as a
  known gap in `api-v1-read-surface-is-reachable-without-login-test` so it cannot rot
  silently.
- **In `ENV=dev` the browser-reload middleware rewrites `Cache-Control` to
  `no-store, must-revalidate` on every response**, overriding the `public, max-age=60` the
  export handlers set. Dev-only; the ETag half of the contract is still observable live and
  the max-age half is covered in-process. Noted so nobody debugs it twice.
- **The seeded demo carries a `Waitlisted` status**, which is not one of the seven values in
  doctrine #3 (Accepted / Accept Queue / Pending / Decline Queue / Declined / Withdrawn /
  Draft). Not touched — it belongs to whoever owns the seed.
- The dev server on :20500 died mid-drive (no `cfp-scheduler-killer.core` process, port
  empty, not a compile error) and was restarted from this seat so Gene's session kept
  working. Cause unknown.
