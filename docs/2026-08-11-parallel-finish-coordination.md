# Parallel finish coordination — local Sol/cfp3 + Buster

**Objective:** reach the highest honest Kill My SaaS score without mixing two
dirty worktrees or accepting a merge that has not passed both test lanes.

## Ownership

### A — local Sol/cfp3 (Codex owns)

Finish bead `sessionize-sched-killer-u9p` in the current local worktree:

1. Finish the default-off, human-gated reminder schedule.
2. Ratify the expanded route topology.
3. Run focused tests, the full non-fail-fast unit suite, and the cold JSONL
   HTTP drive.
4. Write the acceptance receipt and close `u9p` only when every gate is green.
5. Create a checkpoint only after Gene explicitly authorizes a commit.

Current completed slices in this lane:

- CFP saved-draft reset: focused proof green, 1 test / 16 assertions.
- Canonical schedule-side track CRUD and speaker-portal day/time/room: focused
  proof green, 2 tests / 59 assertions.

### B — Buster worktree (Gene owns)

Run bead `sessionize-sched-killer-czd` in a separate worktree. Recover the exact
CRM-08 evaluator requirement from the authoritative evaluator revision, record
that source and revision, then implement only if the current product does not
already satisfy it.

The Buster lane must:

- claim/update `czd` in Beads;
- avoid files currently changed by `u9p` unless the requirement makes that
  impossible;
- run its own focused and full tests;
- record its branch, commit SHA, changed-file list, and test receipt in `czd`;
- never merge, force-push, deploy, run DDL, or touch the production database.

### C — integration (Codex owns after A and B are green)

Integration begins only when both lanes provide green receipts and B provides
an immutable commit SHA.

1. Confirm A is clean/checkpointed and B's commit contains only `czd` work.
2. Inspect every overlapping file before merging; do not accept conflicts by
   choosing an entire side.
3. Merge or cherry-pick the Buster commit into the local integration branch.
4. Format every changed Clojure file.
5. Compile changed production namespaces through the running nREPL.
6. Run the focused tests from both lanes.
7. Run the full non-fail-fast unit suite and cold JSONL HTTP drive.
8. Recompute route topology if either lane changes routes.
9. Update the final rubric audit and operator handoff with the merged evidence.

## Coordination contract

```text
Local Sol/cfp3  ── u9p green ── checkpoint ───────┐
                                                   ├── deliberate integration
Buster worktree ── czd green ── immutable SHA ────┘        │
                                                            ▼
                                               full + cold gates green
```

Beads is the durable coordination channel. Transcript claims are not merge
receipts. A lane is merge-ready only when its issue contains the exact commit
SHA, changed files, and test evidence.

## Separate operator item

Cloud Scheduler keepalives are tracked independently as
`sessionize-sched-killer-ccn`: add a lightweight public `GET /ping`, then create
five-minute jobs for `swyx-cfp-killer` and `curtaincallcfp`. That work is not
part of A, B, or C and must not enter either feature worktree accidentally.
