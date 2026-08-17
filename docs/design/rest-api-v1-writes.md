# REST API v1 — the write surface an agent would love (Mayor spec, 2026-08-17)

Gene's charge: "design the REST interface — propose one that you'd love to use,
given our data — and we publish it in API."

## What already exists (do not rebuild)

`/api/v1` reads: index, event, sessions, speakers(+detail), schedule, rooms,
submissions(+detail), review-policy, per-event docs — plus `changes?since=N`,
the append-only log as a cursor feed. Bearer tokens are minted on Settings and
*widen* reads (public stays public; token unlocks submissions, non-program
speakers, status filters). This is the right foundation: **the event-sourced
store is the API's native shape.**

## The thesis

An agent doesn't want a CRUD mirror; it wants the same three verbs the product
itself lives on: **read the world, watch the log, append a fact.** So v1's
completion is small:

```
READ    GET  /api/v1/events/{slug}/…            (exists)
WATCH   GET  /api/v1/events/{slug}/changes?since=N   (exists; add filters)
ACT     POST …/reviews · …/tasks/{key}/complete · …/speakers/{id}  (NEW)
NOTIFY  POST-back webhooks on change classes          (NEW)
```

## New endpoints (all token-scoped, all append facts through domain verbs)

### 1. Reviews — the 6fb6 BYO-agent lane
```
POST /api/v1/events/{slug}/submissions/{id}/reviews
Authorization: Bearer cc_rk_…          (scope: review-bot | reviewer)
{"stars": 4, "comment": "matched love-pole: war story w/ prod numbers",
 "idempotency-key": "bot-run-7/sub-142"}
→ 201 {"review-id":…, "actor":"api:review-bot:key7"}
```
Rules by construction: a `review-bot`-scoped key appends the `ai-review.recorded`
fact type (renders with the 🤖 badge, structurally excluded from human mean and
2-review coverage — the fold never sees the type); a `reviewer`-scoped key
appends the normal human rating fact. **The scope selects the fact type.**
Idempotency-key dedupes at the fact layer (append-only ledger pattern) —
retrying is always safe.

### 2. Speaker tasks — close the onboarding loop from anywhere
```
POST /api/v1/events/{slug}/submissions/{id}/tasks/{task-key}/complete
POST /api/v1/events/{slug}/submissions/{id}/tasks/{task-key}/chase
{"medium":"email","note":"pinged via assistant"}
```
The /deliverables dashboard SSE-repaints when these land — an external agent
completing a task lights up the organizer's screen live. Demo gold.

### 3. Speaker profile writes (scope: organizer)
```
PATCH /api/v1/events/{slug}/speakers/{person-id}
{"title":"VP Platform","organization":"Wayfound"}
```
Editable-fields whitelist only (`domain.speakers/editable-fields`); canonical
identity stays speaker-owned — the API refuses what the product refuses,
with the same refusal copy.

### 4. Changes feed, completed
```
GET /api/v1/events/{slug}/changes?since=N&types=submission.*,task.*&limit=200
→ {"changes":[{"seq":N,"type":…,"at":…,"payload":…}], "next-since":M}
```
Add `types` glob filter + `limit` + explicit `next-since` so a webhook-less
agent can long-poll politely. This is the endpoint to build bots on:
resumable, ordered, loss-free — because it IS the store.

### 5. Webhooks (brief bonus, minimal honest version)
```
POST /api/v1/events/{slug}/webhooks   {"url":…, "types":["submission.created","task.completed"]}
```
Delivery = POST the same change objects; at-least-once with `seq` for dedupe;
failures visible on the Settings page (fail loud to a named owner, rule 17).
Registration is Settings/API-page visible and revocable.

### 6. Keys & scopes (Settings → API)
Scopes: `read` (today's widening) · `reviewer` · `review-bot` · `organizer`.
Key display: prefix + created + last-used + scope + [revoke]. Every write fact
records `actor = api:{scope}:{key-prefix}` — the log stays evidence-grade.

### 7. Analytics — what I'd love Google Analytics to give me, but joined to the domain

The telemetry beacon (`telemetry-beacon.js` → `/api/telemetry/beacon` →
`telemetry.clj`) already collects page traffic. GA gives you hits; we can give
the organizer **traffic joined to outcomes**, because the same store holds both:

```
GET /api/v1/events/{slug}/analytics          (scope: organizer)
{
 "cfp-funnel":   {"views": 412, "drafts": 63, "submitted": 41,
                  "view-to-submit-pct": 10.0},        ← GA can't do this join
 "traffic":      [{"path":"/cfp/…","views":412,"uniques":268,"by-day":[…]},
                  {"path":"/agenda/…","views":1893,…}],
 "speakers":     [{"person-id":…,"name":"Mik Kersten","page-views":214}, …],
 "referrers":    [{"host":"twitter.com","views":122}, …],
 "since-launch": {"cfp-opened-at":…, "days-open": 9}
}
```

The questions this answers in one call — the ones GA makes you build dashboards
for: *is my CFP converting? which speakers draw traffic? where is my audience
coming from? is interest rising as the deadline nears?* Per-speaker page views
also feed the announce story ("your speakers drew N views this week" — future
comms material). Same data on an ANALYTICS surface later; API first, per the
no-extra-reporting doctrine — the API is the reporting.

### 8. Sidebar: Admin → "API documentation"

A full first-class docs page (not just the JSON index): every endpoint, curl
examples with the demo key, the changes-feed cookbook, webhook setup, key
scopes table. Sidebar link under **Admin**, label "API documentation". The
page renders from the same endpoint-contract data that serves `/api/v1/` —
docs and index can never drift (tabs-are-data precedent, applied to docs).
The docs and the AI Reviewers page both mention Open Graph / Twitter-card
unfurl fields on public pages (bead qbxj).

## Explicitly not in v1

GraphQL, OAuth, rate-limit machinery beyond a fixed per-key ceiling, admin/CRM
writes, form mutation (form builder is frozen), anything that bypasses a domain
verb. No write ever touches the store except through `store/append!`.

## Build order (cfp4, bead vbo7)

1. Key scopes + POST reviews (unblocks 6fb6 BYO lane; coordinate the
   `ai-review.recorded` fact shape with cfp3 seat-to-seat).
2. Task complete/chase POSTs.
3. Changes-feed filters (`types`, `limit`, `next-since`).
4. Analytics endpoint.
5. Webhooks.
6. API documentation page (sidebar Admin hunk goes to the Mayor — sidebar is
   Mayor-owned).
