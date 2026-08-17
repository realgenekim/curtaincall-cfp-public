# Brief: split `views.clj` by product surface

**Status:** implemented as 20 coherent namespaces; the façade is removed. The
eight-real-browser-tab acceptance remains open because local navigation was
blocked by the available browser tooling.
**Source snapshot:** 2026-08-10, 4,590 lines, 144 top-level forms (141
definitions plus three forward `declare` forms).
**Tracking:** `sessionize-sched-killer-0i1`.

## Outcome

Replace the 4,590-line `cfp-scheduler-killer.views` namespace with small view
namespaces that own one product surface each. Dependencies point from product
surfaces toward a small set of rendering foundations. Do not create a permanent
`views` facade or a generic `utils` drawer: callers should name the surface they
render, and each helper should live with the concept that gives it meaning.

This is an extraction, not a redesign. Routes, HTML markers, Datastar attributes,
form actions, status codes, and SSE fragment IDs must remain byte-for-byte or
semantically equivalent throughout the move.

## Design test: cohesion without convenience coupling

The boundary rule is not file size. It is reason to change:

- A namespace owns a product surface or a deliberately small rendering
  mechanism. Forms that users experience and change together stay together.
- Generic document/runtime machinery (`shell`), organizer navigation policy
  (`organizer-layout`), and stable live fragment values (`live-drafts`) are
  separate because they change for different reasons. The original proposed
  `layout` namespace still complected all three.
- Public surfaces may reuse the document shell but cannot acquire organizer
  chrome transitively. Dependency direction makes that promise structural.
- A helper with many unrelated consumers belongs in a true foundation only when
  it expresses one stable concept. Breadth alone does not justify a `utils`
  drawer.
- Mechanical movement and semantic redesign happen in different change sets.
  The first makes structure truthful; later behavior improvements can then be
  reviewed against a smaller owner.

This applies Kent Beck's “things that change together live together” pressure
and Rich Hickey's separation of policy, mechanism, and values. The plan should
be rejected if a proposed namespace cannot state one reason to change or if a
public surface reaches organizer chrome through an apparently convenient
foundation.

## Before and after tree

```text
BEFORE

src/cfp_scheduler_killer/
├── server.clj
└── views.clj                         4,590 lines / 144 top-level forms
    ├── foundations + browser shell + organizer chrome
    ├── eleven organizer product surfaces
    ├── three public product surfaces
    └── one dead legacy renderer
```

```text
AFTER

src/cfp_scheduler_killer/
├── server.clj
└── views/
    ├── format.clj                    presentation normalization
    ├── form_controls.clj             reusable form widgets
    ├── avatar.clj                    person-image presentation
    ├── shell.clj                     generic document/runtime mechanism
    ├── organizer_layout.clj          organizer navigation + DEV chrome
    ├── live_drafts.clj               stable public live-fragment values
    ├── event_setup.clj               ┐
    ├── dashboard.clj                 │
    ├── committee.clj                 │
    ├── review.clj                    │
    ├── log.clj                       │ eleven organizer surfaces
    ├── integrations.clj              │
    ├── schedule.clj                  │
    ├── communications.clj            │
    ├── replay.clj                    │
    ├── form_builder.clj              │
    ├── people.clj                    ┘
    ├── portal.clj                    ┐
    ├── public_cfp.clj                │ three public surfaces
    └── auth.clj                      ┘
```

The target is deliberately flat on disk because the namespaces are already
scoped by `cfp-scheduler-killer.views.*`. The conceptual groups are enforced by
dependency rules, not by adding extra package nesting.

## Evidence from CLJ Surgeon

The current namespace contains 144 forms and five forward references:
`time-travel-bar`, `portal-draft-status`, `cfp-note`, `datastar-script`, and
`row-controls*`. `ls-deps` and `ls-extract` were run from the public roots for
layout, event creation, committee, board, submission detail, settings, schedule,
portal, form builder, public CFP, dashboard, people, communications, capture,
replay, landing, and login.

Non-mutating `extract` previews succeeded for every target below. Representative
results:

- `format`: 15 forms after assigning the broadly used `not-blank` presentation
  normalization to it.
- `form-controls`: 4 forms after removing that unrelated text-normalization
  concern.
- `shell`: 4 forms and 40 source lines; it owns generic document/runtime
  machinery, including `datastar-script`.
- `organizer-layout`: 12 forms, including `time-travel-bar`; this removes the
  1,187-line forward-reference gap without making public pages depend on
  organizer chrome.
- `live-drafts`: 3 pure, stable fragments shared by public CFP and portal.
- `schedule`: 10 forms; its domain closure is self-contained apart from layout.
- `review`: 23 tightly connected board/detail forms before adding `capture-page`.
- `portal`: 6 portal-owned forms after moving shared draft fragments out.
- `form-builder`: 11 forms.
- `public-cfp`: 10 public-CFP-owned forms after moving shared draft fragments out.
- Dry-run extraction also returned success for event setup, dashboard, avatar,
  committee, log, integrations, communications, capture, replay, auth, people,
  and live drafts.

Surgeon exposed two boundaries that a line-range split would get wrong:

1. `time-travel-bar` is owned by organizer chrome because `dev-strip` is its
   architectural caller. Leaving it in board views would create a shell/board
   dependency cycle.
2. `answer-input` is shared by form builder, public CFP, and portal. It belongs in
   form controls, not in any one of those surfaces.

It also exposed a potential `review <-> people` cycle: board rows use pooled
avatars, while `person-page` uses review score formatters. A leaf `avatar`
namespace breaks that cycle cleanly.

The refreshed dashboard preview also found the newly added
`event-dashboard-region`. It belongs beside `event-dashboard-page`; keeping the
stable `#dashboard-region` fragment boundary with its owning page is part of the
contract.

The generated previews copy the monolith's entire require list into each new
namespace. That is scaffolding, not the desired result. Each extraction must
prune requires immediately and pass `clj-kondo` before proceeding.

## Target ownership: every form

The lists below account for all 141 definitions exactly once: 140 migrate and
the dead `submissions-page` is deleted. The three `declare` forms are not
migrated; correct extraction order makes them unnecessary.

### Rendering foundations

`views.format` — time/date and URL presentation:

`date-fmt`, `datetime-fmt`, `->local-date`, `fmt-date`, `fmt-date-range`,
`fmt-instant`, `->instant`, `when-fmt`, `relative-when`, `fmt-when`,
`fmt-cfp-window`, `iso-date-fmt`, `fmt-close-date`, `cfp-public-url`,
`not-blank`.

`not-blank` has 41 resolved call sites across 15 owning forms. That breadth is
evidence that it is presentation normalization, not a form-widget concern.

`views.form-controls` — shared server-rendered form controls:

`field-errors`, `req-mark`, `field-error`, `answer-input`.

`field-errors` and `field-error` currently render the same structure. Preserve
both names during extraction; consolidate them in a later, separately tested
cleanup so movement and behavior change are not mixed.

`views.avatar` — leaf person-image presentation:

`initials`, `face-pool-size`, `pool-face`.

`views.shell` — generic document and browser-runtime mechanism:

`versioned`, `favicon-data-uri`, `page-shell`, `datastar-script`.

`views.organizer-layout` — authenticated organizer navigation and development
chrome:

`event-resume-path`, `event-setup-done?`, `sb-link`, `sb-out`, `sb-group`,
`time-travel-bar`, `sidebar`, `dev-strip`, `breadcrumb`, `whoami-strip`,
`organizer-shell`, `header`.

`views.live-drafts` — stable live-draft fragment values shared by CFP and
portal:

`cfp-note`, `cfp-draft-status`, `portal-draft-status`.

### Organizer product surfaces

`views.event-setup` — event list, creation, and editing the facts supplied at
creation:

`events-list-page`, `default-start-date`, `default-end-date`, `example-name`,
`example-location`, `example-website`, `event-marquee`, `slug-status`,
`new-event-page`, `event-details-page`.

`views.dashboard` — event mission control:

`alert-rows-partial`, `checklist-item`, `dash-days-left`, `dash-recent-line`,
`dash-feed-talk`, `dash-rel-time`, `event-dashboard-region`,
`event-dashboard-page`.

`views.committee` — committee roster and membership controls:

`member-row`, `committee-card`, `committee-page`.

`views.review` — proposal review, the board, board fragments, proposal detail,
and organizer capture of a proposal on somebody's behalf:

`inform-banner`, `fmt-mean`, `fmt-stars`, `notice-region`, `coverage-bar`,
`star-form`, `star-histogram`, `opinions-block`, `private-note-block`,
`chair-on-event?`, `row-controls`, `row-controls*`, `board-row`, `sort-chip`,
`status-chip`, `submissions-sparkline`, `board-qs`, `sort-click`, `sort-th`,
`track-chip`, `board-region`, `board-page`, `submission-detail-page`,
`capture-page`.

`views.log` — event history and time-travel log presentation:

`log-summary`, `log-region`, `log-page`.

`views.integrations` — exports, API docs, webhooks, API keys, Slack, and event
integration settings:

`mask-webhook-url`, `slack-form`, `exports-page`, `api-docs-page`,
`settings-page`.

`views.schedule` — draft schedule plus attendee agenda:

`day-tab`, `schedule-status-bar`, `conflict-chips`, `room-options`,
`placed-card`, `block-card`, `schedule-grid`, `place-form`, `schedule-page`,
`agenda-page`.

`views.communications` — decision letters and communication history:

`letter-block`, `inform-page`, `comms-page`.

`views.replay` — demo replay controls and progress fragment:

`replay-progress-bar`, `replay-page`.

`views.form-builder` — CFP schema builder and all pushable builder fragments:

`type-label`, `fb-tags`, `fb-post`, `field-row`, `form-grid-region`,
`form-fields-region`, `form-preview-region`, `field-form-fields`,
`form-edit-panel`, `finish-cfp-bar`, `form-builder-page`.

`views.people` — the organizer-facing person detail surface:

`profile-links`, `person-page`.

### Speaker/public surfaces

`views.portal` — speaker portal, talk editing, profile editing, and tasks:

`status-pill`, `task-row`, `portal-submission`, `edit-form`, `profile-form`,
`portal-page`.

`views.public-cfp` — public CFP, speaker inputs, safe mini-Markdown, and success:

`speaker-input`, `speaker-inputs`, `cfp-closed-notice`, `md-token`, `md-inline`,
`md-lite`, `render-markdown`, `cfp-about-you`, `cfp-page`, `cfp-success-page`.

`views.auth` — unauthenticated front doors:

`landing-page`, `login-page`.

### Delete instead of migrate

`submissions-page` is dead. The old `/events/:slug/submissions` route already
303s to the review board and no production caller renders this form. Delete it
in a separate guarded cleanup before extraction begins. Prove the 303 redirect
first; do not mix dead-code deletion with a namespace move.

## Dependency direction

The allowed direction is:

```text
format   form-controls   avatar       shell
   \          |           /            |
    \         |          /      organizer-layout
     \        |         /              |
        product-surface namespaces ----+
                     |
                  server
```

`live-drafts` is a leaf fragment-value namespace consumed by public CFP and
portal. Public pages use `shell` directly and never pass through
`organizer-layout`.

More precisely:

- `shell` owns document/runtime mechanism and depends on no product view.
- `organizer-layout` may depend on `shell`, application data namespaces
  (`events`, `forms`, `committees`, `submissions`), and Datastar helpers, but
  never on a product view.
- `live-drafts` contains fragment values only and depends on no shell or product
  view.
- `review` depends on organizer-layout, form-controls, avatar, and format.
- Dashboard, people, integrations, and replay may depend on exported review
  fragments/formatters; review must not depend back on them.
- Public CFP and portal both depend on shell, form-controls, format, and
  live-drafts; neither depends on organizer-layout or on the other.
- Domain view namespaces never call `server` handlers or read request state.
- No namespace named `common`, `misc`, or `utils` is introduced.

## Caller migration

Do not keep `cfp-scheduler-killer.views` as a permanent alias facade. A facade
would hide ownership, preserve the monolith's accidental API, and make resolved
reference searches lie. During each extraction:

1. Build a resolved-reference migration manifest containing the definitions to
   move, every same-namespace user left behind, and every external caller.
   Text search alone is not evidence: `reviews/foo`, CSS classes, and prose all
   produced false `views/foo` matches during reconnaissance.
2. Add the owning namespace to `server.clj` and update only that slice's
   `views/foo` calls to its explicit alias (`review/board-page`,
   `schedule/schedule-page`, and so on). Qualify every internal user in the old
   namespace in the same transaction.
3. Move direct tests to the owning namespace in the same transaction. This
   includes tests that currently dereference private vars such as
   `views/opinions-block` and `views/log-summary`.
4. Keep not-yet-extracted forms in the old namespace. Remove the old namespace
   only when resolved-reference enumeration finds no callers.

The current direct consumer set is `server.clj`, `views_test.clj`,
`polish_test.clj`, and `forms_test.clj`. Re-enumerate before every move because
the repository is active. Surgeon's generated `callers-to-review` list is
conservative; confirm every caller with resolved symbol analysis rather than
copying all generated requires.

## Safe extraction sequence

One namespace per hash-bound transaction/change set; never move several product
surfaces in one batch. When commits are authorized, preserve one extraction per
commit. Do not execute an extraction while another agent is editing
`views.clj`; inventory and source hash must still match immediately before
apply.

1. **Freeze contracts.** Establish a green, timed baseline and add/confirm the
   auto-discovered route, content-type, fragment, and architecture guards below.
2. **Remove proven dead code.** Delete only `submissions-page` after its 303
   route contract is green. Re-run the baseline. This is its own change set.
3. **Pilot leaf:** `format`, including `not-blank`. Its broad internal caller
   set exercises the migration-manifest workflow while its behavior is simple.
4. **Remaining foundations:** `shell`, `avatar`, then `form-controls`.
5. **Organizer chrome:** `organizer-layout`, including `time-travel-bar`; run
   `fix-declares` and remove the now-unnecessary declaration.
6. **Shared fragment values:** `live-drafts`; this removes three long forward
   references from portal to the public-CFP section.
7. **Review core:** `review`, then run `fix-declares` for `row-controls*`.
8. **Low-coupling surfaces:** `committee`, `log`, `schedule`,
   `communications`, `auth`.
9. **Review consumers:** `dashboard`, `integrations`, `replay`, `people`.
10. **Event and form surfaces:** `event-setup`, `form-builder`.
11. **Speaker surfaces:** `public-cfp`, then `portal`.
12. Remove the empty old namespace and run resolved-reference enumeration
    across `src` and `test`.

The order is intentional: every step depends only on namespaces already moved,
so no temporary circular dependency or compatibility shim is required.

## Permanent guard tests

Before the first extraction, establish tests at the contract level rather than
full-page snapshots:

- Auto-discover routes from `server/make-routes`; do not maintain a second
  hand-written route inventory that can silently go stale. Supply canonical
  fixtures for path parameters such as `:slug` and `:submission-id`, then assert
  expected status, redirect target when applicable, content type, and one
  stable semantic marker. Distinguish HTML pages, JSON endpoints, and SSE
  fragments explicitly.
- For handlers whose asynchronous transport makes a full request awkward,
  assert route resolution plus the pure rendered-region contract and stable
  target ID. An omitted route must fail the test rather than disappear from the
  matrix.
- Public pages (`landing`, login, CFP, success, agenda, portal) must not contain
  organizer chrome.
- Organizer pages retain sidebar/breadcrumb behavior and active navigation.
- SSE pages retain the exact mount attribute and stable fragment IDs:
  `board-region`, `coverage-bar`, `validation-notice`, `form-grid`/`fb-grid`,
  `schedule-status`, `replay-progress`, `cfp-note-*`, and draft-status targets.
- Board fragment tests cover board row, coverage, notice, time travel, sorting,
  and per-viewer controls.
- CFP/portal rejected submits preserve typed values and field errors.
- The legacy submissions URL remains a 303 to `/board`.
- Namespace architecture test, driven by resolved clj-kondo dependencies:
  product view namespaces may require foundations and approved lower-level
  surfaces only; public views cannot require organizer-layout; no cycles and no
  dependency on `server`.

Avoid whole-HTML golden snapshots. They make harmless markup edits expensive and
would recreate the stale-test problem just repaired. Assert stable IDs,
actions, links, status codes, and user-visible facts.

## Gate for every extraction

For each namespace, in order:

1. Confirm exclusive ownership of the monolith for the transaction and record a
   fresh `:ls` form count plus source hash.
2. Run `clj-surgeon :op :ls-deps` and `:ls-extract` on its public roots. Produce
   the migration manifest of moved definitions, internal users, and external
   callers.
3. Review a fresh non-mutating `:extract` plan against that source hash. If the
   hash or inventory changes, discard and regenerate the plan.
4. Apply only that saved extraction plan; update all manifest callers and prune
   copied requires immediately. Do not rename Vars or change behavior in the
   extraction transaction.
5. Run `fix-declares` analysis whenever a declaration is moved or made obsolete.
6. Format only changed Clojure files with Standard Clojure Style.
7. Run `clj-kondo` on changed source and tests, including the dependency-policy
   assertion.
8. Use the hot nREPL loop to reload the new namespace, the shrinking monolith,
   and changed callers, then run the owning focused tests. Follow it with a cold
   server/compile check and `make runtests-once`; record timings so the loop can
   be improved rather than guessed at.
9. Enumerate old and new resolved references. The old symbol must have zero
   callers before it is removed. Verify the live route/fragment when the moved
   surface participates in Datastar.

Stop and revert that one extraction if behavior changes or a cycle appears. The
small-batch sequence keeps every rollback mechanical and leaves the application
runnable after every step.

## Definition of done

- Every form above lives in its owning namespace, and `submissions-page` is gone.
- The final graph has 20 namespaces: six rendering foundations, eleven
  organizer surfaces, and three public surfaces.
- `cfp-scheduler-killer.views` is gone; there is no permanent forwarding facade.
- No `(declare ...)` remains solely because of the old monolithic ordering.
- The architecture dependency test is green and the namespace graph is acyclic.
- Format, lint, focused route/fragment tests, and `make runtests-once` are green.
- The eight-tab SSE acceptance test remains green; moving render functions must
  not alter stream lifecycle or fragment identity.
