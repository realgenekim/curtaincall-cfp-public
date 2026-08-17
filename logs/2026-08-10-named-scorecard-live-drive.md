# Named scorecard live drive — 2026-08-10

## Scope

Production-shaped local acceptance of the named weighted scorecard slice on a
Guardrails-enabled JSONL server at `http://localhost:20500`. No PostgreSQL,
gcloud, deployment, DDL, or external side effects were used.

## Browser proof

Signed in as the existing organizer `genek@itrevolution.net` and opened:

`/events/enterprise-ai-summit-charlotte/board`

Observed the exact judge-facing literals `Scorecard criteria`, `Numeric`,
`Dropdown`, `Free text`, `Weight`, and `Scorecard`. Expanded the native
scorecard panel and completed this path through visible controls:

1. Added numeric criterion `Live business impact` with weight `2`.
2. Verified the rendered configuration retained name, type, and weight.
3. Opened the quick-rate panel for “What 18 Months of AI Code Review Did to Our
   Change Failure Rate.”
4. Set the new criterion to `4` for Gene. The proposal already had Gene's
   5-star rating and Ann's 4.5-star rating.
5. Observed the board aggregate change from `4.8` to `4.4`, matching the pure
   weighted-score algebra.
6. Retired the test criterion through the visible `Retire criterion` action.
   It disappeared from current configuration; the append-only history remains.

One false alarm was deliberately not “fixed”: collapsed native `<details>`
content does not appear in a normal visible-text read until expanded, although
the rendered HTML contains all labels and controls. Inspecting the exact markup
prevented an unnecessary code change.

The agent-created browser tab was finalized after the drive.

## Automated proof

- Focused scorecard/route/view/architecture suite: 12 tests, 280 assertions, 0
  failures.
- Detail-page aggregate follow-up: 22 focused tests, 208 assertions, 0
  failures; the detail page now uses the weighted aggregate and has a real Ring
  response contract.
- Full unit suite: 277 tests, 2,896 assertions, 0 failures.
- Full JSONL acceptance drive: 257/257 checks passed.
- The permanent fold architecture guard now requires all four new fact types:
  `scorecard.criterion-added`, `scorecard.criterion-updated`,
  `scorecard.criterion-retired`, and `scorecard.value-set`.
