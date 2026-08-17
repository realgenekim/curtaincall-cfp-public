# Captain's log: toward a sublime Clojure environment

**2026-08-10 14:22 PDT · CFP Scheduler Killer**

## Assessment

The development environment is approximately **8/10 today**, with a credible
path to 9.5. The strongest parts are not merely pleasant syntax. Immutable data,
append-only domain facts, pure folds, a persistent nREPL, and Datastar's
server-visible interactions reinforce one another. The result is unusually
good leverage: the new speaker-obligation slice gained five law-like tests and
22 assertions, then reloaded and ran in about a second.

The important property is algebraic change. An obligation stores a signed
offset from the event date; changing the event date recomputes every deadline
without rewriting historical task facts. Idempotent decisions turn retries into
successful no-ops. The tests describe these laws rather than mirroring private
implementation steps.

## Compared with TypeScript and Rust

| Environment | Where it wins | Where this Clojure stack wins |
|---|---|---|
| TypeScript | Mainstream editor refactors, references, web libraries, and familiar deployment ergonomics | One immutable model from domain to rendered HTML; no client-state/API/schema synchronization layer; the REPL can interrogate the living model directly |
| Rust | Much stronger compiler-enforced boundary and memory guarantees | Far faster model exploration; data and event vocabularies can evolve without fighting ownership or rebuilding a large type graph; the REPL loop is dramatically more conversational |
| This stack | Pure folds, event history, Datastar server visibility, structural tools with receipts, fast semantic probes | Still needs stronger static boundary checks, more reliable semantic refactors, and a single actual-live-server loop |

TypeScript makes ordinary rename-and-navigate work smoother. Rust makes many
illegal states uncompilable. Clojure currently wins at changing the *model* and
testing its laws, especially when the event log is the source of truth. The
stack is at its best when Guardrails/Malli protect the edges and pure Clojure
owns the center.

## Friction observed in this slice

1. Clj Surgeon safely resolved and read dispatch-specific `fold-event` methods,
   but its writer could not name one `defmethod` owner among 61 top-level forms
   sharing `fold-event`. Three attempted writes refused without changing source.
2. A correct atomic keyword rewrite was rolled back because the verification
   gate detected that the *proposed* result needed formatting. Because the
   transaction never committed, the formatter had no result to fix. The single
   documented retry behaved identically; a bounded native patch was required.
3. The nREPL JVM and browser-serving JVM are different processes. Reload proof
   is excellent compile and semantic evidence, but it is not proof that the
   exact browser process has loaded the same code.
4. The fast nREPL law suite takes about a second. Cold Kaocha verification still
   pays roughly 20–30 seconds of JVM startup. It is safe, but not flow-state fast.

### Addendum: the verifier wrapper, measured

The next structural transaction made the formatter-path problem quantitative.
Twice, the MCP verifier's `/opt/homebrew/opt/node@20/bin/npx` process timed out
after approximately 121–122 seconds with no output. Running the installed
`standard-clj` executable directly over the same files completed in 0.68 seconds.
The paved road should call the installed binary first and use `npx` only as a
fallback.

One later transaction was manually terminated after the same stall. A mandatory
exact-form read afterward showed that the intended edit had reached disk even
though the caller never received verification success or an undo receipt. Normal
verification failure had rolled back correctly; process cancellation did not.
Until cancellation is made atomic, an interrupted write must be treated as
unknown and followed by an exact owner/hash read before any further edit.

All Surgeon failures were failure-atomic. That distinction matters: these are
flow defects, not trust defects—except the newly observed cancellation window,
which is now a trust defect with a safe read-back workaround.

### Addendum: memory pressure was masquerading as tool latency

After Chrome was stopped and roughly 6 GB of RAM was released, the same class
of Surgeon write transaction (owner-scoped replacement plus format, lint, and
read-back) fell from roughly 83 seconds to 9 seconds. A focused cold Kaocha run
that had taken several minutes completed in about 10 seconds. The tools were
CPU-active before, but the order-of-magnitude step change strongly implicates
memory pressure and GC/paging as the dominant latency source.

The environment needs an explicit preflight meter: available memory, swap/page
pressure, and concurrent cold JVM count. Starting another verifier or test JVM
when the machine is already constrained is not a neutral choice; it destroys
the feedback loop. The default workflow should keep one hot nREPL foreground
gate and at most one cold/background verification JVM.

### Addendum: recovery must be a first-class operation

A multi-file native patch followed by formatting left `server.clj` truncated at
line 222. Because the file was not hashed between those two actions, attribution
to the patch or formatter is unresolved. The next hot compile caught the EOF
immediately. Recovery did not use hand reconstruction: a known pre-edit outline
hash and Surgeon telemetry supplied the last complete route owner and lifecycle
owners. The current 221-line prefix was preserved, only the missing suffix was
restored, the post-refactor middleware seam was reconstructed, and structural
inspection plus the hot JVM proved 160 routes—the former 158 plus exactly two.

The missing paved road is `recover-file`: given a path and a previously observed
SHA/owner snapshot, restore exact bytes or a selected owner suffix, parse before
formatting, show the semantic delta, and emit an undo receipt. Every formatter
should also refuse a file that does not parse unless explicitly invoked in a
repair mode; “format” must never be allowed to widen a syntax error into data
loss.

## The sublime target

One command should accept a semantic refactoring plan and then:

1. resolve exact definitions and every caller;
2. support dispatch-qualified owners such as `[fold-event "task.completed"]`;
3. create namespaces, derive minimal requires, rewrite callers, and delete old
   owners in one reversible transaction;
4. format *inside* the transaction before lint verification;
5. reload the actual browser server JVM;
6. run focused algebraic laws immediately; and
7. start cold unit and JSONL e2e verification in parallel.

That would combine Rust-like refusal, TypeScript-like navigation, and Lisp's
unique ability to reshape and interrogate a running program. Most of the pieces
already exist. The remaining work is to pave them into one boring, reliable
road so that “compile the refactoring plan; bang, done” becomes literal.

## Current proof

The organizer portal-invite slice brought the full suite to **333 tests, 3,326
assertions, 0 failures** in both fail-fast and non-fail-fast modes. Its focused
algebra has 43 tests and 682 assertions spanning event isolation, mail effect
modes, truthful public handoff copy, and the exact 161-route topology.

## A concrete MCP boundary discovered during the portal-invite tests

The structural MCP remains excellent at exact owner replacement and safe
refusal. It correctly rolled back a namespace-only require insertion when
`clj-kondo` saw that the alias was not yet used. But the direct transaction API
refused insertion of a new top-level `deftest` sibling with
`unsupported-insertion-parent`; it can insert inside an owner, not create the
next owner. A subsequent combined namespace-plus-owner transaction spent about
90 seconds in verification and returned `invalid-transaction-receipt`. Exact
structural inspection proved that it had restored the original file hash.

That sharpens the sublime target: semantic-plan compilation needs first-class
**create top-level owner** (and create namespace) operations in the same atomic
transaction as caller/require rewrites. Until then, MCP owns existing forms;
one bounded native patch owns new top-level siblings; structural parse happens
before formatting; the hot JVM and algebraic tests remain the final authority.

## I/O ports: the algebra paid immediately

The email and blob extraction produced the exact testing shape we wanted:
three small operations, recording fakes, and provider contract tests. The fast
suite grew from 333 tests / 3,326 assertions to 339 / 3,348 while remaining
network-free; the complete suite and the 263-check cold HTTP drive stayed
green. GCS mechanics disappeared from the event sink, and email delivery still
ends as append-only `comms.sent` or `comms.failed` facts.

Clj Surgeon was excellent for exact owner reads and independent whole-form
deletion. It still cannot compile a multi-owner exact-source contraction while
the semantic index is unavailable. That is the next sublime-tooling target:
one transaction containing target owners, caller rewrites, and clustered
deletions, with parse/read-back/compile proof and undo.
