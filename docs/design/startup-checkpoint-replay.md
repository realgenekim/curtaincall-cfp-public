# Startup checkpoint + tail replay (bead sessionize-sched-killer-w2o4, P0)

*Investigation 2026-08-16, read-only against `~/src.local/curtain-call-gene`
(branch `gene/cfp-tweezers`, 6f6d1af). Nothing changed, nothing deployed. Live
timings belong to a separate subagent; this doc is the architecture.*

## 1. The problem, in numbers

**~17,800** events in `store_events` today; 620 events measured at 671,507 B
(`docs/one-database.md`) → ~1,083 B/event → **~19 MB of log text**. Cold boot is
**~2 min** before `:20500` binds — ~6.7 ms/event, far above a JSON parse plus one
multimethod dispatch. Paid on every Cloud Run scale-to-zero wake, every deploy,
and every localhost restart. The log only grows: a ratchet toward SEV-0.

**The honest caveat that gates everything below.** A boot has three costs and a
checkpoint attacks only the third (and part of the second): (1) **JVM + namespace
load** — a floor no checkpoint can touch; (2) **wire + JDBC materialization** —
17,800 rows / ~19 MB from Cloud SQL; (3) **parse + fold** — 17,800 ×
(`json/read-str` → 95-way multimethod → persistent-map update). If (1) is 90 s of
the 120 s, this project is the wrong lever and §4's Option Ø is the whole answer.
**Do not build before the split is measured.**

## 2. Fold-path findings (file:line, deploy-repo checkout)

- `store/load!` — `store.clj:549-570`. Under the write lock: `read-events` →
  `fold` → `reset!` the atom → `pg-mark` from `max-seq`. The whole boot cost and
  the only from-scratch build. Called from `start-server!` (`server.clj:372`)
  **before** `http/run-server` (`:375`) — the port truly does not bind until the
  fold finishes.
- `store/fold` — `store.clj:223-227`: `(reduce fold-one state events)` from
  `store/empty-state` (`:81-99`). **Pure, left-fold, ordered** — no I/O, no clock,
  no randomness, so checkpointing is sound by construction:
  `fold(fold(empty, prefix), tail) = fold(empty, prefix ++ tail)`. `fold-one`
  (`:217-221`) = `fold-event` **plus** `(update :log conj event)`.
- `folds/fold-event` — `folds.clj:118-121`, `defmulti` on `:type`, **95 defmethods**,
  `:default` (`:123`) ignores unknown types. The only whole-collection scan is
  `event-by-id` (`:94`) over `:events` — the fold is effectively **linear**.
- **Postgres already tail-folds.** `refresh-if-changed!` (`store.clj:572-608`)
  fetches `read-events-since` (`store_pg.clj:181-198`) past `pg-mark` and folds it
  onto live state. **The tail-replay half already exists, in production, trusted;**
  only "start from a sealed prefix instead of `empty-state`" is missing.
- **Prefix identity is free.** `store_events.seq` is `BIGSERIAL PRIMARY KEY`
  (`store_pg.clj:49-52`) and `read-events-since` returns `:max-seq`, so
  `thru_seq` + row count is exact. No hash chain needs inventing.

### The four findings that shape the design

**F1 — the state carries the whole log.** `:log []` (`store.clj:99`,
`folds.clj:39`) accumulates every event, so a naive "snapshot the folded state"
blob is **not smaller than the log** — it is the log plus the projection. The
parse+fold prize is real; the wire-bytes prize is not, unless `:log` is excluded.

**F2 — `:log` has only seven readers**, all funnelable through one accessor:
`store.clj:677` (`state-as-of`, which *already* falls back to `read-events` when
the in-memory log is empty), `:695` (`state-at-log-index`), `:718` (`log-bounds`),
`:845` (`indexed-log-for-event`), `:885` (`log-for-event`), `domain/crm.clj:219`,
`domain/speakers.clj:173`. So "checkpoint without `:log`, hydrate lazily" is a
bounded refactor, not a rewrite.

**F3 — serialization is not JSON.** State holds `LocalDate` / `Instant`
(`parse-event-row`, `store.clj:205-213`), sets (`:agenda-selections`), and
**vectors as map keys** (`[submission-id person-id]`). Codec must be EDN with
tagged literals, transit, or nippy — never JSON.

**F4 — the grants already allow this.** Per `docs/postgres-store.md` the Cloud
Run role is **SELECT + INSERT only**, with `ALTER DEFAULT PRIVILEGES … GRANT
SELECT, INSERT ON TABLES` already in force — a new owner-created table inherits
the grant with no ceremony, checkpoint rows are **immutable by the same lock as
the log**, and no `ON CONFLICT` is ever needed (`DO NOTHING` is forbidden).

## 3. Option comparison

| | **A. GCS sealed prefix** | **B. Postgres checkpoint table** | **C. Write-time / hybrid** |
|---|---|---|---|
| Blob lives | GCS object, content-addressed | `store_checkpoints` row, insert-only | either — this axis is *when*, not *where* |
| Boot dependency | store + **GCS** (2 systems, 2 auths) | store only (existing pool) | n/a |
| Ops surface | bucket, lifecycle, IAM for run SA | one owner-created table | scheduler or request path |
| Localhost dev | needs ADC + bucket | prod DB (already the norm) or file mode | n/a |
| Cloud Run CPU risk | none at boot | none at boot | **the whole risk** |
| Prior art | `sinks.clj:588-668`, idle since the PG cutover | the `store_pg.clj` seam | none |

**A — GCS sealed content-addressed prefix.** *Why right:* matches the zowt
data-checkpoint contract and eval-seal discipline exactly; immutable, cheap
forever; `sinks.clj` machinery exists. *Cost:* boot depends on a **second**
system with its own auth, and `docs/one-database.md` deliberately retired GCS
("merged and tested but idle"); a dead credential becomes a boot cliff,
survivable only by falling back to the full replay we are avoiding.
*Assumption underneath:* a GCS read + auth beats one in-region Cloud SQL row
read. Probably it does not.

**B — Postgres checkpoint table.** *Why right:* one store, one connection, one
failure domain; the pool is already open before `load!` (`core.clj:36-38`); the
SELECT+INSERT grant makes the row immutable and append-only automatic;
`thru_seq` rides the log's own sequence. *Cost:* a few-MB TOASTed `bytea` row per
checkpoint, accumulating because the app cannot DELETE (pruning is an owner/REPL
job, like every other schema act here). *Assumption underneath:* one multi-MB row
beats 17,800 rows — true for JDBC object churn; **true for wire bytes only if
`:log` is excluded (F1).**

**C — write-time / hybrid maintenance.** *Why right:* keeps the tail short.
*Cost:* **Cloud Run allocates CPU during startup and during a request and
throttles otherwise** — anything sealing "in the background, later" can freeze
mid-flight and silently never complete; a timer also violates feed-on-INTEGRATED.
*Assumption underneath:* that a moment with guaranteed CPU exists. **It does, and
it is the best one available: the boot that just paid the full replay** — that
instance holds the exact folded state for `thru_seq` with CPU allocated, so every
deploy and cold start *produces* the checkpoint the next boot consumes.
Supplements: explicit `make checkpoint-db` and an inline lock-guarded
request-path seal (tail > K, > N minutes since the last). No cron, no
post-response future, no correctness dependency on a background cycle.

## 4. Committed recommendation

**Build B as the store, A's contract as the seal, C's boot-trigger as the timing
— and ship Option Ø today as the zero-code stopgap.** These are not competing:
A is *what a checkpoint is*, B is *where it lives*, C is *when it is made*.

1. **Option Ø, now, zero code (rung 4, reversible):** Cloud Run
   `--min-instances=1` + startup CPU boost. Deletes the scale-to-zero cold start
   outright and makes deploy cold-boot invisible (a revision takes traffic only
   when ready). Costs ~$15-30/mo standing spend and fixes **neither localhost
   restarts nor the growth curve** — do it anyway; nothing else helps this hour.
2. **Gate:** measure the boot split; if parse+fold is under ~25 %, stop and go
   attack namespace loading instead.
3. **Phase 1 — whole-state checkpoint including `:log`:** wins cost (3) only;
   smallest diff, no accessor refactor, no behavior change.
4. **Phase 2 — exclude `:log`, hydrate lazily through one accessor** (F2): also
   wins cost (2), ~10× fewer wire bytes. Only if the wire still dominates.

**Honest fallback / where I could be wrong:** if boot is dominated by JVM +
namespace load, none of this moves the number and Option Ø is the entire answer.
And if EDN decode of a ~20 MB blob is slower than 17,800 JSON parses, Phase 1 is
a wash — which is why the codec sits behind a seam and the equivalence test must
pass for both codecs.

## 5. Implementation sketch

**New:** `src/cfp_scheduler_killer/store_checkpoint.clj` (~140 lines)

```clojure
(def fold-contract-version 1)   ; bumped by hand on a deliberate fold change
(defn fold-fingerprint [])      ; sha256 over 3 independent witnesses: (a) classpath
  ;; source bytes of folds.clj (b) (pr-str store/empty-state) (c) fold-contract-version.
  ;; Resource missing (AOT jar) -> version/git-sha: conservative, since every deploy
  ;; then invalidates AND every deploy re-seals.
(defn encode ^bytes [state])    ; codec seam: :edn (tagged literals reusing
(defn decode [codec ^bytes bs]) ;   ->iso-date/<-iso-instant) | :nippy
(defn seal! [state thru-seq])   ; encode -> sha256 -> INSERT
(defn restore [])               ; -> {:state s :thru-seq n} | nil (+ loud refusal)
```

**`store_pg.clj` (+~45):** `ensure-checkpoint-schema!` (probe-and-skip like
`ensure-schema!`, `:86-100` — the app role cannot DDL), `insert-checkpoint!`,
`latest-checkpoint`.

```sql
CREATE TABLE IF NOT EXISTS store_checkpoints (
  id           BIGSERIAL PRIMARY KEY,
  thru_seq     BIGINT NOT NULL,   -- last store_events.seq folded in
  event_count  BIGINT NOT NULL,   -- rows folded (gap cross-check)
  fold_version TEXT   NOT NULL,
  codec        TEXT   NOT NULL,   -- "edn/1" | "nippy/3.4.2"
  state_sha256 TEXT   NOT NULL,   -- content address of `state`
  state        BYTEA  NOT NULL,
  build_sha    TEXT,  created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE INDEX IF NOT EXISTS store_checkpoints_thru ON store_checkpoints (thru_seq DESC, id DESC);
-- owner-created (genek); prevent_update/prevent_delete triggers as on store_events.
-- No ON CONFLICT: a re-seal is a new immutable row, never an overwrite.
```

**`store.clj` (+~25 lines):** add `load-fast!`; leave `load!` untouched as the
always-correct full replay and the fallback target.

```
load-fast!:
  1. latest row WHERE fold_version = (fold-fingerprint) ORDER BY thru_seq DESC, id DESC
  2. sha256(bytes) = state_sha256 ?  3. decode; (count (:log s)) = event_count ?
  4. thru_seq <= (max-seq) ?  (else history moved)
  5. state = fold(s, read-events-since(thru_seq)); pg-mark = max-seq
  ANY failed check or exception -> (load!) full replay + log :checkpoint-refused
  with a NAMED reason + a standing non-zero counter surfaced in telemetry.
```

**`server.clj` (+3 lines):** `load-fast!` at :372; after `run-server` binds and
before `@(promise)`, `(checkpoint/maybe-seal! …)` when the folded tail exceeded K
(suggest K = 2,000). A throttled or crashed seal leaves **no row** and degrades
to full replay. **`admin.clj` (+~20) + Makefile:** `make checkpoint-db`,
count-first, same `clj -X` shape as `backup-db`. **deps.edn:** optionally
`com.taoensso/nippy` — start on EDN to preserve the zero-setup judge path; the
`codec` column makes the swap safe. Total ≈ 250 src lines + ≈ 150 test lines.

## 6. Equivalence-test plan (`test/cfp_scheduler_killer/store_checkpoint_test.clj`)

1. **Prefix equivalence (the core oracle).** Corpus = `store-test/sample-events`
   extended to cover **every `:type` with a defmethod** — an architecture
   assertion drives it, so a new `defmethod fold-event` with no corpus event
   fails the test. For **every** split `k ∈ 0..n` (n≈200, sub-second, so keep it
   exhaustive rather than sampled):
   `(= (fold corpus) (fold (decode (encode (fold (take k corpus)))) (drop k corpus)))`
2. **Codec fidelity.** Round-trip a state holding a `LocalDate`, an `Instant`, a
   set, and a vector map key; assert `=` **and** that types survive
   (`instance? LocalDate`) — `=` alone would pass on ISO strings and reintroduce
   the drift `store/canonicalize` (`store.clj:458-469`) exists to kill.
3. **Refusal suite — the safe fallback ALWAYS works.** (a) `fold_version`
   mismatch; (b) one flipped byte; (c) `thru_seq` > `max(seq)`; (d) `event_count`
   ≠ `(count (:log s))`. Each asserts the booted state equals the full-replay
   state *and* that a refusal was logged with a named reason.
4. **Tail-gap.** Seal at k, append 5, restore: the 5 appear exactly once, proving
   `read-events-since` is `> thru_seq`, not `>=`.
5. **Architecture.** `store_checkpoints` is never `UPDATE`d/`DELETE`d in `src/`,
   no `ON CONFLICT` in the path; (Phase 2) every `(:log ` reader goes through
   `store/event-log` and hydration fires from `state-at-log-index`/`log-for-event`.

## 7. Rollout order (safe fallback first, every step reversible)

1. `--min-instances=1` + startup CPU boost; verify at the meter (cold-hit latency).
2. Measure the boot split. **Gate:** proceed only if parse+fold ≥ ~25 % of boot.
3. Land `store_checkpoint` + tests **with `load-fast!` unwired** — full replay
   still boots. Green suite, adversarial review.
4. Owner (genek, REPL) creates `store_checkpoints` + triggers; verify the run SA
   inherited SELECT+INSERT.
5. Wire `maybe-seal!` only. Boot still full-replays; confirm rows appear.
6. Wire `load-fast!` behind `STORE_CHECKPOINT=1`, default off; verify locally
   `load-fast!` state `=` `load!` state on the **real** log.
7. Default on. Watch `:checkpoint-refused` — non-zero is an alarm, not an
   archive. Phase 2 (`:log` exclusion) only if the wire still dominates.

## 8. EARS intent candidates for `docs/intent/registry.edn`

```clojure
{:id :STORE-CKPT-001
 :ears "When the store boots from a checkpoint, the resulting state shall equal the state produced by folding the entire event log from genesis."
 :status :active :tests [:checkpoint-prefix-equivalence-holds-at-every-split]
 :pins "w2o4 — a checkpoint is an optimization; a checkpoint that changes state is data loss"}

{:id :STORE-CKPT-002
 :ears "While a candidate checkpoint's fold fingerprint, content hash, event count, or thru-seq fails verification, the store shall refuse the checkpoint, boot by full replay, and record one named refusal on a standing non-zero counter."
 :status :active :tests [:checkpoint-refusal-falls-back-to-full-replay-loudly]
 :pins "w2o4 — fail closed is only half the rule; a refusal nobody hears is silent data loss (delivery invariant 17)"}

{:id :STORE-CKPT-003
 :ears "Every checkpoint shall be written as one immutable insert-only row and shall never update or delete a row in store_events or store_checkpoints."
 :status :active :tests [:checkpoint-write-path-is-append-only]
 :pins "w2o4 — snapshots are derived projections; the log is the only source of truth"}

{:id :STORE-CKPT-004
 :ears "When the server binds its port, checkpoint creation shall have no bearing on correctness, such that an incomplete or throttled seal leaves the next boot able to replay the full log."
 :status :active :tests [:incomplete-seal-leaves-no-checkpoint-row]
 :pins "w2o4 — Cloud Run throttles CPU outside startup and requests; no correctness may depend on a background cycle"}
```
