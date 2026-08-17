# Event-name drop investigation — 2026-08-12

## Local setup

- Started the app with `PORT=20500 make server-jsonl` so all reproduction writes stay in the local JSONL sandbox.
- Started a separate analysis nREPL with `make nrepl`; it is listening on `localhost:50813` and is being treated as read-only because it uses the PostgreSQL backend.
- Opened `http://localhost:20500/events/new` in a fresh Chrome tab and signed in as the seeded `Organizer · swyx` user.

## Initial evidence

- The rendered Event name input has both native `name="name"` submission and `data-star-bind:evname` binding.
- Start date and end date likewise have native names and Datastar bindings.
- The form submits natively to `/api/events/create` and sends debounced preview requests to `/api/events/preview` on input.
- `handle-create-event` validates `(:params req)` through `events/parse-form`; it does not read the preview signal atom.
- Therefore the initial “unbound input posts nothing” hypothesis is falsified for the current local code. The remaining likely boundary is browser input/submit timing or a subsequent morph/action clearing the native value.

## Manual reproduction

- A sequential local fill and submit created `devflow-conf-2027-2027` successfully.
- A second immediate sequential fill and submit created `devflow-immediate-2027-2027` successfully.
- Concurrent Playwright fills reproduced the failure family: calls reported completion while the DOM retained stale or blank values.
- The original anvil transcript confirms the evaluator wrote six `fill` calls plus a screenshot to sbek in one newline batch. The calls executed concurrently; the first create POST was rejected with exactly `[:name]`. The sequential retry succeeded.
- A separate server race was reproduced: a debounced preview arriving after a successful create repopulated `create-drafts`, so a later `/events/new` showed the prior event name and dates.

## Fix in progress

- Fence the ephemeral create draft with a per-render token.
- Recover blank native fields only from the matching current-page draft.
- Ignore late preview and details-toggle requests carrying a completed page's token.
- Log field presence and character counts, token match, validation fields, and recovered field names without logging submitted content.
- Move the DEV diagnostic strip into normal document flow and remove its in-session persona switcher (`sessionize-sched-killer-5qps`).

## Verification

- Focused regression gate: 2 tests, 15 assertions, 0 failures.
- Scoped clj-kondo: 0 errors (existing unresolved-var/unused-binding warnings remain).
- Live long-form geometry: final judged button bottom `3991.97px`; DEV strip top `4063.97px`; computed `position: static`; overlap `false`; persona switch `false`.
- Live post-create isolation: the next `/events/new` had a new draft token, an empty Event name, and house-default dates. The old late preview did not repopulate it.
- Full `make runtests-once` reached 216 assertions before stopping on the unrelated pre-existing `review_plan.clj` blank presenter-visibility case. That namespace was already modified outside this task and was not changed here.
