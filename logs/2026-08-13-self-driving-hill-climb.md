# Captain's log — self-driving hill climb

Date: 2026-08-13

Objective: turn the now subscription-funded all-Codex fleet into a closed loop:
successful staging changes automatically produce a fresh score and a ranked
next target without a human kick.

## Build record

- Created Beads issue `sessionize-sched-killer-ifwb` before implementation.
- Reused `bin/reset-anvil-fleet` and `bin/hill-climb-fleet` as the only fleet
  runner. The autoscore command is a control-plane wrapper, not a competing
  implementation.
- Added exact successful-deployment pinning to `bin/reset-anvil-fleet` so a
  deploy arriving during startup cannot silently change the measurement.
- Pinned the proven topology: six isolated stateful area lanes, Codex workers,
  Codex judges, and 240 interactions per scenario.
- Added crash-safe active/completed/failed state, PID-and-host locking, a
  five-minute staging debounce, non-overlap with independently active fleets,
  report validation, idempotent history, and append-only JSONL cycle telemetry.
- Published the ranked target to Anvil `/tmp/fleet/next-targets.md`, where the
  fixer lanes can consume it. An earlier prototype incorrectly wrote only to
  the skiff's `/tmp`; the reviewed implementation corrects that boundary.
- Added a detached `start`/`stop` path so `make hill-climb-autoscore-start` is
  the complete operator experience.

## Fast verification

The following checks passed before attaching to the real proof cycle:

```text
PASS autoscore: Codex-only receipts, isolated SUTs, ranked targets, idempotency, and one-at-a-time locking
PASS fleet topology: provenance, stateful lanes, and live progress are explicit
PASS fleet consolidation: evidence is immutable and scoring is resumable
```

Shell syntax, ShellCheck, and `git diff --check` also pass. The behavioral
autoscore fixture proves that a Claude worker receipt is rejected and that a
second live controller cannot acquire the lock.

## Real proof cycle

Run: `/home/tester/fleet-runs/20260813T215049Z-59b4b6f33886-1500951`

- Product SHA: `59b4b6f33886bfc358f1ba59d0ccb0a30e48c2a5`
- GitHub Actions deployment: `31746783083`
- Manifest: `worker_executor=codex`, `judge_executor=codex`,
  `max_turns_per_scenario=240`, `slots=6`, `evidence_only=false`
- All six SUTs passed initial readiness and persona authentication before the
  fleet release.
- The controller was deliberately stopped and restarted mid-run. Its v1 state
  migrated to v2 and reattached to the same immutable run, proving crash
  recovery without restarting journeys.

Final authority:

- Journeys: 18/18 terminal, `failed_or_missing=0`. `ABS-S1` recorded a valid
  blocked product outcome and `CNT-S2` recorded `feature_not_found`; neither
  was a worker-process failure.
- Judges: six of six fresh Codex processes exited zero and produced the
  schema-valid consolidated report.
- Score: **46.0%**, coverage **94.3%**, `scoreWithheld=false`.
- Lowest required area: **Public Widgets, 25%**.
- The controller appended exactly one history row and published the ranked
  Public Widgets failures to Anvil `/tmp/fleet/next-targets.md`.
- Local and Anvil target SHA-256:
  `5f1df9e659c0264d4c022e14b619ac153f3b9c36243a145bbaead7252c4c8f59`.
- Current immutable run tree SHA-256:
  `4ce249309aa2d4173131d2686fec450f6854639bd5757892af1bc865cda4ab9a`.
- Prior clean run tree SHA-256 before and after this cycle:
  `b89f97df01111c4a407d099dbab114737fe966f1d809d35e0c5b2dd82b0a13cf`;
  zero prior-run files had a modification time after the new run began.

The first observer process was hot-edited while Bash was still lazily reading
the file. It emitted the complete cycle receipt and then encountered a parse
error in a newly added, unrelated daemon function. This did not alter any run
or output artifact. The stable final script then reattached to the already
complete immutable run, revalidated and republished it, exited zero, and left
the history at one row for that run. This is also a direct idempotent crash-
recovery proof; production operation starts only the committed stable file.

The detached daemon start/status/stop smoke passed after exercising a stop in
the middle of a real 60-second debounce. The stop recursively terminates only
the local controller subtree; it never signals the remote fleet.
