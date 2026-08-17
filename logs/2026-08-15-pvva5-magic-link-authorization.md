# pvva.5 magic-link authorization drive

Date: 2026-08-15 UTC

## Current behavior reproduced before the fix

I started the application through the production-mode Makefile target against
an isolated JSONL store, then drove `/api/login` and `/auth/:token` over real
HTTP. `DEMO_PERSONAS=on` throughout.

- `DEMO_MODE=false`: a whitespace-padded, uppercase
  `SBEK-ORGANIZER@EXAMPLE.COM` received an echoed `/auth/:token` credential and
  redeemed it successfully (HTTP 303).
- A real, signable `ordinary-reviewer@example.com` committee member did not
  receive an echoed credential because it was outside the SBEK allowlist.
- An unknown identity did not receive an echoed credential.
- With `DEMO_MODE` absent, the allowlisted SBEK organizer still received an
  echoed credential.
- With `DEMO_MODE=definitely-not-a-boolean`, the allowlisted SBEK organizer
  still received an echoed credential.

The defect was therefore exact: the hardcoded persona allowlist worked, but the
credential echo never consulted `DEMO_MODE`; false, missing, and malformed mode
configuration all behaved as allowed.

## Correction and ratchet

- Deployed SBEK link construction is now a single authorization boundary. It
  returns a credential only when `DEMO_MODE` is exactly `true` and the
  normalized address belongs to the immutable four-address SBEK allowlist.
- The separate local `ENV=dev` inbox-free affordance remains available.
- Active intent `AUTH-001` records the authorization rule in
  `docs/intent/registry.edn`, linked by `INTENT:` and `INTENT-TEST:` tags.
- The behavior matrix covers demo/non-demo, allowlisted/non-allowlisted,
  missing mode configuration, malformed mode configuration, normalized
  identity, and missing identity.

Focused verification: 18 tests, 291 assertions, 0 failures. The allowed side
explicitly covers organizer, reviewer, speaker, and second-speaker SBEK
addresses.

## Post-fix HTTP drive

I restarted the same isolated production-mode server for each configuration and
posted real login requests for the signable SBEK organizer and ordinary
reviewer:

- `DEMO_MODE=true`: normalized allowlisted SBEK echo **yes**; valid
  non-allowlisted committee echo **no**.
- `DEMO_MODE=false`: allowlisted SBEK echo **no**.
- `DEMO_MODE` absent: allowlisted SBEK echo **no**.
- `DEMO_MODE=definitely-not-a-boolean`: allowlisted SBEK echo **no**.

The allowed demo path and all fail-closed paths therefore pass through the real
HTTP handler and rendered response, not only the pure authorization predicate.

Final full verification: 714 tests, 7,438 assertions, 0 failures.
