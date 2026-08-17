# 2026-08-17 — Eval fuel allocation (codex pools), ratified by Gene ~05:15 PT

*(Recreated ~09:15 PT after an untracked-file wipe in the shared tree — original
was written pre-commit and deleted by a clean. Lesson applied: Mayor docs commit
immediately on write.)*

## What was asked

Codex tokens read 40% remaining mid-eval-watch. Gene asked for a proposal to
move eval participants to other models, then ratified a final allocation after
correcting the Mayor's account-topology misread.

## The topology fact that settled it (Gene, verbatim intent)

> "they are all on my genekkanban codex account; same pool; codex spark in
> different pool"

**One ChatGPT account (genekkanban) fuels everything** — skiff coding seats
(cfp3/4/6 on sol, cfp5), and tester@anvil's eval workers AND judges. Quotas are
**per-model pools** inside that account: sol / spark / terra / luna are
separate wells. The "40%" was the **sol pool**, drained by two consumers at
once: the three sol-high coding seats and the eval workers
(`bin/hill-climb-fleet` codex default was `gpt-5.6-sol`).

## Ratified allocation

| Consumer | Model / pool | Rationale |
|---|---|---|
| Judges | `gpt-5.3-codex-spark` high × **5 samples** | Spark pool healthy; 5 samples matches the series baseline. **Never change judge model or sample count mid-series** — rows become incomparable and the 90% submission gate silently breaks. A 5→3 economy cut was made and REVERTED same hour (Gene: "no need"). |
| Workers (from run 20260817T123311Z on) | `gpt-5.6-terra` | Moves 18 journey-drives off the strained sol pool; separates worker burn from judge burn so neither starves the other mid-run. Edit: `bin/hill-climb-fleet` codex worker default sol→terra. |
| Coding seats cfp3/4/6 | `gpt-5.6-sol` high | The 40% pool is now theirs alone. |
| cfp5 seat | luna high (Gene flipped) | Its spark seat hit "model at capacity" — OpenAI service congestion, not quota. |

## Verification discipline

- The RUNNING judge pass on anvil was confirmed **at the meter** by reading
  `/proc/<pid>/environ`: spark high, sample 5 — env is captured at launch, so
  default edits never disturb a live run.
- Pool balances: `/status` in any codex TUI on the account. "Model at
  capacity" ≠ quota exhaustion.
- Judge-model migration (terra/luna calibration legs vs sol baseline) stays a
  DELIBERATE future move: bead 6l9s, with a labeled series break — never an
  under-pressure swap.

## Where the edits live

`bin/hill-climb-fleet` (worker default terra) shipped with cfp5's Phase-2
fail-forward bundle (b9e7e82/e1ce37c). `bin/hill-climb-autoscore` sample
default back at 5 (net zero change).
