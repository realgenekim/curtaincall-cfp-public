# `/cfps` feed redesign — captain's log

Date: 2026-08-11 PDT

Bead: `sessionize-sched-killer-5lq`

Surface: `http://localhost:20501/cfps`

## Drive

- Opened the live dev page before editing. It rendered the old ruled-list treatment and, initially, eight open calls from a stale in-process projection.
- Compared the live surface with the ratified `docs/mockups/cfps-page-redesign.html` in the canonical main checkout.
- Added white event cards, date brackets, bare event titles, the open-call count rule, dot-separated metadata, deadline emphasis, organizer/CTA footers, and a per-event program gallery.
- Reused `public-catalog/speakers` and `views.public-widgets/featured-card`; Charlotte renders 14 real speakers capped to eight cards, while NYC omits the gallery because its roster is empty.
- Verified the desktop page in Chrome after hot reload. The stale eight-call projection cleared without a server restart or data mutation; the final page rendered exactly Charlotte and NYC.
- Tested the 540px breakpoint: the date bracket was hidden, the card became one column, and the gallery occupied column 1.
- Gene visually accepted the wider layout and changed the kicker to “Find Your Next Conference Speaking Engagement!” A later forced 44em/76px cascade correction was rejected visually and reverted to the accepted wider composition.

## Proof

- Red contract: 132 tests, 1,065 assertions, 1 expected failure on the missing open-call rule.
- Final unit suite: 302 tests, 2,961 assertions, 0 failures.
- Browser DOM: 2 event cards, 1 gallery, 8 rendered Charlotte speaker cards, bare event titles, bold NYC deadline, no NYC gallery.
- No client JavaScript added; all presentation changes are scoped in `app.css` under `@layer page`.

## Findings and disposition

- Found: the first live baseline showed eight calls despite the accepted production filter. Disposition: cleared on normal source hot reload; no store write or server restart was required.
- Found: legacy unlayered featured-card CSS overrides the nominal 76px page-layer size. Gene preferred and accepted the resulting wider/larger composition, so the forced override was removed.
- Remaining: production deployment and live-domain verification follow this log.
