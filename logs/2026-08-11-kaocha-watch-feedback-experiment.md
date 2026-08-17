# Kaocha watch feedback experiment

**Date:** 2026-08-11
**Bead:** `sessionize-sched-killer-5ka`

## Question

Can a persistent Kaocha JVM shorten the full-suite feedback loop without
reintroducing the old failure mode where agents confuse stale, interleaved, or
voluminous watch output for the current result?

The prototype in `../curtain-call-staging` was inspected read-only. No file
outside this repository was changed.

## Result

Yes, with a bounded reader contract:

| Run | 512 MB wall time | Result |
|---|---:|---|
| Cold one-shot `bin/kaocha unit --fail-fast` | 61s | 374 / 3,818 / 0 |
| Initial `--watch` load | 89s | 374 / 3,818 / 0 |
| Warm watch rerun after a real source edit | 34s | 374 / 3,818 / 0 |

The steady-state full feedback loop is therefore about **1.8× faster** than a
fair, equally capped cold one-shot. The initial warmup is slower and should be
paid once in the background.

## Safety contract

- `bin/kaocha` supplies `-Xms64m -Xmx512m` by default. The live Java process was
  inspected and proved both flags; the maximum is overrideable only through the
  task-specific `KAOCHA_MAX_HEAP` variable.
- `make runtests-log` starts one watcher, truncates the prior session log so run
  numbers cannot collide, removes the old verdict stamp, and refuses a second
  live watcher.
- `bin/testlog-stamp.sh` strips ANSI and stamps every completed summary with one
  numbered verdict.
- `make test-verdict` is the only supported reader. It exits 0 for current
  green, 1 for current red, and 2 when the watcher is absent, warming, dead, or
  stale because source is newer than the last completed verdict.
- The experiment directly proved exit 2 during initial warmup, immediately
  after a source edit, and after watcher death. It then proved exit 0 only after
  the warm rerun completed.
- The compact progress reporter replaced the sibling prototype's documentation
  reporter. The latter emitted tens of thousands of lines and made the first
  experiment needlessly slow and confusing.

## Usage

```bash
make runtests-log    # leave running in one background terminal
make test-verdict    # bounded current truth; never read 00TESTLOG.txt
```

This improves the full-suite loop. Bead `5ka` remains open because its stronger
original acceptance—sub-three-second selected namespace/test-var execution
through the hot nREPL with stale-classpath refusal—is still the next feedback
frontier.
