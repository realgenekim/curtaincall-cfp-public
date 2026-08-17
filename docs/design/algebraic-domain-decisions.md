# Algebraic domain decisions

## The rule

Every new domain mutation should have a pure decision at its center:

```text
state + command -> {:facts [...] :result ...}
state + command -> {:rejection {...}}
```

The decision function performs no I/O. It does not read the global store,
generate a UUID, read the clock, send telemetry, log, or append a fact. All
nondeterministic inputs belong in the command. A thin runtime shell loads the
current projection, supplies those inputs, invokes the decision, appends the
facts, and performs structured logging.

```text
HTTP handler
    |
    v
effectful command shell ── snapshot, clock, UUID, append!, logging
    |
    v
pure decision ──────────── state + explicit command -> facts or rejection
    |
    v
pure fold ──────────────── state + fact -> next state
```

This is the default architecture for criteria, rounds, assignments, recusals,
speaker obligations, files, communications, and every later domain verb.

## Decision result contract

A successful decision returns:

```clojure
{:facts  [{:type "reviewer.assigned"
           :actor "chair@example.com"
           :event-id "event-1"
           :payload {...}}]
 :result {...}}
```

An idempotent no-op is also a success. It returns no facts and the current
domain result:

```clojure
{:facts [] :result existing-assignment}
```

A refused command returns structured data rather than throwing inside the pure
function:

```clojure
{:rejection {:type :not-event-reviewer
             :message "The assignee must be a committee reviewer for this event."
             :event-id "event-1"
             :submission-id "submission-1"
             :person-id "person-2"}}
```

The effectful shell translates a rejection to `ex-info` for the existing HTTP
boundary. This keeps public behavior compatible while making rejection rules
directly testable as values.

## Command design

Commands are complete descriptions of intent. Supply all nondeterminism before
calling the decision:

```clojure
{:submission-id "submission-1"
 :person-id "person-2"
 :actor "chair@example.com"
 :at "2026-08-10T12:00:00Z"}
```

Use `:at` for the time chosen by the shell and explicit IDs for newly created
entities. Do not hide a clock, random UUID, request, database handle, or dynamic
runtime state behind a helper called by the decision.

## Tests that follow from the algebra

The first test layer calls the decision directly. It needs no fixture, file,
database, server, or mock:

- exact valid command -> exact facts;
- invalid command -> exact rejection;
- repeated command -> no new facts;
- tenant mismatch -> rejection and no facts;
- emitted facts folded into the input state -> the expected next state;
- inverse command facts folded afterward -> the original relationship is gone;
- replaying the same ordered facts from `empty-state` -> identical projection.

The second layer tests the thin shell against the temporary JSONL store. It
proves that facts are appended and public return/exception behavior is
preserved. Handler and browser tests remain valuable, but they no longer carry
the burden of proving domain rules.

## Purity safety rails

Automated tests use `with-temp-store`, which calls `reset-for-test!`. That reset
forcibly selects the JSONL backend even when an environment variable requests
PostgreSQL. Pure decision tests should not use this fixture at all.

The desired testing pyramid is:

```text
many     pure decision + fold tests       milliseconds, no effects
some     temporary JSONL shell tests       durable append contracts
few      Ring route and browser drives     wiring and user journeys
```

A test that needs production secrets, PostgreSQL, Cloud Run, Pub/Sub, email,
external network access, or a non-temporary event log is not a unit test and
must not run in the unit suite.

## Incremental migration

Do not rewrite every existing verb at once. Use the rule whenever a mutation is
created or materially changed:

1. Characterize the existing public behavior.
2. Extract one pure decision without changing the public shell.
3. Add exact decision, rejection, idempotence, and replay tests.
4. Keep the shell small: gather inputs, decide, append, log, return.
5. Move to the next verb only after formatter, nREPL reload, focused tests, and
   the full unit suite are green.

Reviewer assignment/unassignment and named scorecards are the reference
implementations. New work should copy their shape rather than inventing another
mutation pattern. Scorecards prove that the pattern scales past a binary
relationship: criterion lifecycle, three answer types, weighted aggregation,
idempotence, retirement, and cross-event refusal all remain pure decisions and
folds, while the runtime shell only appends and logs.

The scorecard slice added seven tests and 57 assertions without enlarging the
effectful test surface. Its final proof was 277 tests / 2,896 assertions / 0
failures plus 257/257 production-shaped JSONL checks. A live chair drive added a
weight-2 criterion, rated a proposal, observed the aggregate move from 4.8 to
the algebraically correct 4.4, and retired the criterion. The same aggregate is
contract-tested on both the board and proposal detail. This is the desired
feedback ladder: most rules prove themselves as values; a small number of
wiring and browser checks prove that users can reach them.

Reviewer work is the next reference implementation. Three decisions own the
complete recusal and follow-up policy: `decide-recuse`, `decide-unrecuse`, and
`decide-record-nudge`. They return `reviewer.recused`, `reviewer.unrecused`, and
`reviewer.nudge-recorded` facts, or structured rejections. The projection makes
recusal algebraic too: a recused assignment is absent from assigned/remaining
progress and that reviewer's rating, comment, and criterion values are absent
from coverage and aggregate scores. The facts preserve history, while an
unrecusal restores the relationship without deletion.

The shell deliberately stops before delivery. It validates every selected
draft before appending the batch and records the chair-reviewed message, but it
cannot send email. This makes the human-send gate a property of the dependency
graph, not explanatory copy that a future caller might bypass. Ring and Hiccup
only translate requests and render the projection; they do not decide who is
eligible, what counts as progress, or whether a mutation is accepted.

Optional review plans are the third reference implementation. Eight facts own
their complete lifecycle: `review-round.created`, `review-round.updated`,
`review-round.pool-set`, `review-round.scorecard-set`,
`review-round.activated`, `review-round.advanced`, `review-round.retired`, and
`review.blind-mode-set`. The absence of an active round is the old open-review
workflow—not a synthetic “default round.” Activating the first round and
advancing to a later round are different facts, so history says what happened
rather than requiring an inference from overwritten state.

Blind review is a read algebra, not a destructive mutation. The authoritative
submission retains every speaker and answer. A pure projection removes
identity-bearing speaker fields and answers only for non-chair viewers, before
search, sorting, board rendering, or detail rendering. This ordering is a
security property: searching for a hidden company cannot reveal whether a
matching submission exists. Chair views apply the identity projection and get
the original value unchanged.

An active round scopes only the explicit “Assigned to you” queue and its
progress denominator. The shared board remains available, and with no active
round assignment behavior is byte-for-byte the prior default. This is how a
rubric-only capability stays optional without infecting the evidence-grounded
product model.

## Why this pays immediately

The competition plan is mutation-heavy. Abstract Management alone adds
criteria, weights, rounds, assignments, recusals, and nudges. Speaker operations
then adds obligations, completion, files, versions, invitations, and message
records. A pure decision seam turns each item into a small table of inputs and
facts before Ring, Datastar, JSONL, or PostgreSQL enters the picture.

This makes changes faster because feedback is local, safer because tenancy and
idempotence are explicit, and easier to reverse because every accepted change
is still an append-only fact.
