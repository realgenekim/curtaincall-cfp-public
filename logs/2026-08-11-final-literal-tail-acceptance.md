# Final literal-tail acceptance — `u9p`

**Date:** 2026-08-11
**Outcome:** the four remaining required-rubric details are implemented and
verified locally. The evidence-backed required local ceiling is now 100/100;
deployment and competition scoring remain separate operator gates.

## What changed

- **CFP draft reset:** the public CFP names the restored state `Editing a saved
  draft` and offers `Reset saved data`. Reset clears only the current viewer's
  ephemeral draft; it does not invent a domain fact for private browser-draft
  state.
- **Canonical track management:** the schedule page exposes `Track management`
  with `Add track`, `Rename`, and `Retire`. The verbs update the canonical CFP
  Track field options through the existing `form.updated` fact and fold. Guards
  reject blank names, duplicates, missing tracks, retirement of the last track,
  and schedule-locked mutation.
- **Speaker portal schedule:** an informed accepted speaker sees `Your schedule`
  with assigned day, start/end, and room projected from canonical slots and
  rooms. This is a read projection and introduces no mutation or duplicate
  schedule state.
- **Reminder schedule:** organizers can configure the visible `Automated
  reminder schedule`. The new
  `speaker.reminder-schedule-configured` fact folds into event settings at
  `:speaker-reminder-schedule`. It defaults off and preselects due obligations
  for human review; configuration never sends email or writes a delivery.

## Route contract

The route table is characterized at **192 routes: 71 GET / 121 POST**, hash
`a3126b3c600255f2fe131212fd582eaaa5dd819d914a4a4da67fe67e88d2b7e0`.
The five new POST routes are CFP draft reset, track add/rename/retire, and
reminder-schedule configuration.

## Verification receipts

- CFP reset focused proof: **1 test / 16 assertions / 0 failures**.
- Track management plus portal schedule focused proof: **2 tests / 59
  assertions / 0 failures**.
- Both full-suite modes: **373 tests / 3,804 assertions / 0 failures**.
- Fresh-process JSONL HTTP drive: **263/263 checks passed**.
- The isolated cold server was stopped after the drive; port 20501 was proven
  free.

One test-purity improvement was required: the reminder proof no longer assumes
the global delivery sink is empty after randomized tests. It asserts the
algebraic invariant that no delivery corresponds to the reminder-configuration
event type. This is order-independent and tests the actual safety property.

## Remaining external work

- Bead `czd`: recover and satisfy the exact CRM-08 evaluator requirement in the
  isolated Buster lane.
- Merge only an immutable green Buster checkpoint after local and Buster suites
  are both green.
- Operator-only deployment, credentials, DDL, fixture wave, deployed persona,
  multi-tab, upload, email, telemetry, and final submission acceptance remain
  intentionally unclaimed.
