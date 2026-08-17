# Captain's log — first Woodchipper tweezer release

Date: 2026-08-16 PT  
Operator: GENEDEV with Gene  
Target: staging only; production promotion is explicitly out of scope

## Intent

Carry `Thanks for the dare, swyx!` into the shared footer on every full-page
shell, place it immediately above the shared navigation links, and make the
homepage speaker CTA name the canonical Enterprise AI Summit instead of the
first open event in the store.

## Decisions and commits

- `1df96db` centered the thank-you and added the exclamation point.
- `9109c4f` established Gene's accepted 75%-width paper-banner treatment.
- `e904b47` moved the accepted words and links into the shared footer, and
  pinned the homepage CTA to `enterprise-ai-summit-charlotte-2026` while open.
- `d42737a` records the formatter receipt: Standard Clojure Style completed on
  all four touched namespaces with no remaining byte changes.
- New standing sequence: surgical edit, format touched files immediately,
  focused verification, then commit. Never carry an unformatted edit forward.

## Local acceptance receipts

The PostgreSQL-backed hot-reload server was already running through
`make server-dev` at `http://localhost:20500`.

| URL | HTTP | Shared thank-you count |
|---|---:|---:|
| `/` | 200 | 1 |
| `/login` | 200 | 1 |
| `/program/enterprise-ai-summit-charlotte-2026` | 200 | 1 |
| `/agenda/enterprise-ai-summit-charlotte-2026/gallery` | 200 | 1 |

Homepage CTA observed:
`See a live call — Enterprise AI Summit →`.

Gene visually accepted the localhost homepage: “OMG, perfect.” Mayor had
previously accepted the homepage delta as-is. No production data mutation was
performed.

## Automated verification

- `clj-surgeon` parsed and read back the four changed Clojure namespaces.
- `standard-clj` 0.24.0 formatted the four namespaces successfully.
- Focused `cfp-scheduler-killer.events-test`: 16 tests, 176 assertions,
  0 failures.
- Focused `cfp-scheduler-killer.homepage-copy-test`: 1 test, 7 assertions,
  0 failures.
- `make runtests-once`: 156 tests, 1,579 assertions, 1 failure. The single
  failure was the unrelated test-suite architecture inventory guard:
  `every-test-file-has-one-path-matching-namespace-test` expected 150 analyzer
  results and received 0. Follow-up bead `sessionize-sched-killer-fhr7` keeps
  this exhaustive guard in CI while removing it from the local hot loop.

## Deployment ceremony

Planned Git operation: atomically advance both `origin/woodchipper` and
`origin/staging` to the same reviewed release commit. The staging push triggers
GitHub Actions. The workflow must deploy tagged 0%-traffic staging revisions
and stop before the separate production-promotion Makefile ceremony.

## Staging CD receipts

- Atomic push advanced `woodchipper` and `staging` to `bc36af4`.
- GHA run `31983641651` stopped before deploy: the judge-signin test prohibited
  the words `Gene Kim` anywhere in login HTML. The new shared footer made that
  obsolete blanket assertion visible.
- `9da7231` narrowed the guard to its real contract: the sandbox must not expose
  `Organizer · Gene Kim` or `genek@itrevolution.net`; public footer attribution
  remains allowed. Focused receipt: 1 test, 25 assertions, 0 failures.
- Atomic retry advanced both branches to `9da7231`; GHA run `31983824074` is the
  authoritative clean-checkout retry. It passed tests and deployed staging.
  No production promotion was invoked.

## Next tweezer: compact judge-demo door

Gene ratified FP2-A. Commit `45c96e6` removes the full-width homepage demo
instruction slab and places `Judge demo: choose a persona →` beside the normal
Sign in link. The link remains above the fold and goes to the existing `/login`
page; the Organizer, Reviewer, and Speaker sandbox cards were not moved or
removed.

Local receipts: the compact link appears exactly once before the hero; the old
`demo-how-to-sign-in` block is absent; `/login` still renders Organizer · swyx,
Reviewer · Maya Lindholm, and Speaker · Amara Devlin. Focused test result:
2 tests, 12 assertions, 0 failures. Gene inspected the rendered localhost
homepage screenshot. FP2 remains local pending the review-and-stage ceremony.
