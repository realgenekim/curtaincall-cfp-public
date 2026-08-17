# Captain's Log — Public CFP live-validation acceptance

Date: 2026-08-16 19:15 PDT  
Branch: `woodchipper`  
Product commit: `8b56c13`  
Issue: `sessionize-sched-killer-tqe3`

## Outcome

Gene accepted the reusable server-owned CFP validation loop in the browser.

- An incomplete email (`ddd`) produced the neutral live guidance: “That doesn't look like a complete email address yet.”
- Correcting the email to `genek@itrevolution.net` cleared the guidance without changing or erasing the input.
- Submitting with a blank Organization produced the red server error: “Your organization is required.”
- Correcting the form and submitting completed the full CFP flow and rendered the confirmation page.

Gene's acceptance: “omg, it works, but fucking sublime after waiting for what seemed an eternity.”

## Design that landed

The page uses a scoped Datastar Live local view. The server computes guidance and validation messages. Reusable controls render stable, signal-driven landing pads. The live fragment never owns an `input` or `textarea`, so a validation refresh cannot replace typed values or move the cursor. Empty signals explicitly clear stale messages.

This is recorded as LID intent `CFP-003` in `docs/intent/registry.edn`.

## Verification

- Standard Clojure Style: all six changed Clojure files formatted.
- Public CFP focused suite: 56 tests, 276 assertions, 0 failures.
- Intent contract suite: 10 tests, 148 assertions, 0 failures.
- Browser acceptance: invalid-email guidance appeared and cleared; required Organization error appeared; valid submission reached confirmation.

## Data-authority receipt

`make server-dev` currently uses the same production Cloud SQL database as the deployed services. The successful localhost submission therefore wrote intentional production data. Gene confirmed this was expected and desired: development matches staging, which matches production. The private one-time portal token is deliberately omitted from this log.

No production code deployment was performed by this acceptance test. The product change proceeds through the staging branch and the explicit production-promotion gate.
