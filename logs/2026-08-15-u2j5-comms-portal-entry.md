# u2j5 deployment-aware decision comms drive

Date: 2026-08-15 UTC

## Current behavior reproduced before the fix

I signed into the scrub server at `http://127.0.0.1:20615` as the judge-sandbox
organizer and requested
`/events/enterprise-ai-summit-charlotte-2026/comms` over real HTTP.

- Login returned HTTP 303 and the organizer comms route returned HTTP 200.
- The outbox contained 30 sent acceptance messages.
- All 30 linked to `https://curtaincallcfp.com/portal`; none linked back to the
  deployment at `http://127.0.0.1:20615`.
- All 30 promised that a one-time link would arrive by email even though that
  sign-in method was disabled on the deployment.

The scrub verdict therefore reproduced exactly.

## Correction and ratchet

- Decision-letter rendering now accepts the request-derived public origin.
  Both single and batch inform handlers pass that origin into the deliberate
  notification act, so newly delivered acceptance mail links to the deployment
  that produced it.
- The comms read model upgrades legacy decision previews to the current
  deployment URL and removes the stale inbox-link promise. This repairs existing
  judge-sandbox outbox rows without mutating their event facts.
- The acceptance template directs speakers through `/portal` and tells them to
  use an available sign-in method; it no longer claims disabled magic-link mail
  is available.
- Status remains independent of notification. The family ratchet verifies that
  Accepted, Waitlisted, and Declined status changes produce no decision mail
  until the explicit inform action, after which each appears on comms.

Focused verification: 15 tests, 184 assertions, 0 failures. The new family
ratchet itself has 28 assertions across the three communicable decisions,
including sent-state projection after approval.

## Post-fix HTTP drive

I restarted the isolated judge-sandbox server at `http://127.0.0.1:20575`,
signed in as organizer, and requested the same comms route over real HTTP.
The clean fixture had no retained outbox (`Outbox (0)`), so I also loaded the
acceptance preview through the page's real `?template=accepted` control.

- Login returned HTTP 303; both comms requests returned HTTP 200.
- The page contained `http://127.0.0.1:20575/portal` and contained no production
  portal URL.
- The acceptance preview contained the deployment-aware sign-in guidance and
  no promise that a one-time link would arrive by email.

Runtime evidence is retained in `/tmp/u2j5-postfix.nqZhjh`.

Final full verification: 719 tests, 7,666 assertions, 0 failures.
