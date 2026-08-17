# Organizer-triggered speaker portal invitation — acceptance evidence

## Outcome

A submitted speaker now has one explicit **Send portal invite** action on the
event's canonical speaker roster. The organizer controls when the private link
is created. An invited-only person with no submitted talk has no action and a
person cannot be invited through another event's URL.

The resolved letter names the event, comes from and replies to the organizer,
contains an absolute one-time `/auth/:token` link, and says the token expires in
24 hours. Redeeming the existing token lifecycle signs the speaker in and sends
them to `/portal`.

The public CFP confirmation is deliberately truthful: the submission is saved,
and organizers will email a private one-time portal link when updates are
needed. It does not promise an automatic portal email at submission time.

## Durable facts and side effects

The mail boundary records the invitation under kind `portal-invite` with
`event-id`, organizer `actor`, and `person-id`. With no provider configuration it
appends `comms.rendered`; provider acceptance appends `comms.sent` and the
message ID. The same letter and context travel through both modes.

Token minting continues to use the existing one-time, 24-hour auth lifecycle.
Repeated organizer action creates a fresh usable token without mutating the
speaker, submission, or another event.

## Verification

- Hot nREPL compile: `auth`, speaker view/handler, public CFP view, and `server`
  reloaded; `server/make-routes` returned 161.
- Route characterization: 161 routes, 65 GET / 96 POST, SHA-256
  `116b31e262d4fc1bc8280b11be5ba68fe5189f6ab56e8a5922086fd1e1926cbf`.
- Focused handlers/comms/flow/routes/server: 43 tests, 682 assertions, 0
  failures.
- `make runtests-once`: 333 tests, 3,326 assertions, 0 failures.
- Full non-fail-fast `bin/kaocha unit`: 333 tests, 3,326 assertions, 0
  failures.
- Cold JSONL HTTP drive on port 20501: 263/263 checks passed; the sandbox
  server then stopped cleanly.

No PostgreSQL, DDL, cloud, deploy, production browser mutation, or real email
delivery was used.
