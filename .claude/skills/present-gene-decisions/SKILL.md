---
name: present-gene-decisions
description: Present open decisions to Gene multiple-choice style — each one stating what is blocked, why it matters, and 2-4 concrete options with a recommendation. Fire whenever an agent has accumulated decisions it cannot make itself (product/design/go-no-go), when Gene asks "what decisions need to be made" / "what needs my call" / "what's blocked on me", or before going idle with unresolved escalations.
---

# Present Gene Decisions

Gene's time is the scarce resource. A decision presented badly costs him more
than the work it unblocks. This skill is how an agent hands back a decision so it
can be answered in seconds rather than reconstructed from scratch.

## Use the AskUserQuestion tool

Present decisions with `AskUserQuestion`, not as prose. It renders selectable
options and Gene can answer several at once. Limits: **max 4 questions per call,
2-4 options each.** If more than four decisions are open, ask the four with the
highest cost-of-delay and say in your text that the rest are queued.

## The shape of one decision

Every decision must carry four things. If you cannot supply all four, you are not
ready to ask — go find out first.

1. **What is blocked right now.** Name the artifact and its state: "the exports
   patch, tested and applying clean, held in `~/held/`". Not "some work".
2. **Why it is Gene's call and not yours.** Usually one of: it reverses a stated
   instruction; it changes an external contract; it trades off product thesis
   against score; it is irreversible (ships to prod, sends mail, deletes data).
   If it is none of those, **decide it yourself and do not ask.**
3. **What it costs to wait.** Rubric weight, blocked downstream work, or a
   deadline. "Weight 31 sitting at zero" beats "this is important."
4. **2-4 concrete options**, each with its consequence. Include the do-nothing
   option when it is genuinely viable — and say plainly when it is not.

## Rules that make the answers trustworthy

- **Recommend.** Put your recommendation first and mark it `(Recommended)`. An
  agent that presents four options with no view is offloading its judgement.
- **Say when your recommendation is self-serving.** If you are the party the
  decision constrains, name that. It is the single most useful sentence you can
  add.
- **Never present a phantom.** Verify the decision is real against running code
  or git before asking. In one overnight run, five "defects" evaporated on
  verification and one P0 was already implemented. Asking Gene to rule on a
  problem that does not exist is worse than staying silent.
- **Separate what you already did from what you are asking.** Gene should never
  have to work out which parts are done. Report those in prose, then ask.
- **One question per decision.** Do not bundle two rulings into one option list;
  the answer becomes ambiguous and you will guess wrong.
- **Options must be mutually exclusive** and must not overlap in effect.

## Header and option wording

- `header`: ≤12 chars, names the subject — `Exports`, `Rubric`, `Ship auth`.
- Option labels: 1-5 words, the *action*, not the rationale — "Ship as-is",
  "Split the patch", "Amend the rubric".
- Descriptions: the consequence in one sentence. "Ships the reversal of your
  2026-08-11 instruction along with the id fixes" tells Gene what he is buying.

## After the answer

Act immediately, record the ruling verbatim with its date in the durable log
(`~/foreman-log.txt` or the relevant bead), and cite it in any job or patch that
depends on it. A ruling that lives only in a chat transcript will be
re-litigated. Approvals that authorize a release should bind to a specific patch
or commit SHA — "Gene approved this earlier" is not a control.

## When NOT to use this

- Routine triage, dispatch, merge and ship — those leave the human loop.
- Anything you can settle by reading the code, the git history, or by running it.
  Check first; most "decisions" are questions with findable answers.
