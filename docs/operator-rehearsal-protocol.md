# Operator rehearsal protocol — the only test that matches swyx's actual eval

*Written 2026-08-09 overnight. Run this before Tuesday.*

## Why this, and not more features

swyx said what the evaluation actually is, in his own words:

> "they are **not technical at all**; they are event production professionals that
> just want to use software to make their lives easier, the **'eval' is partially
> that i will put it in front of them and they will actually use it**"

Every test we have written so far — 171 unit tests, a 107-check end-to-end driver —
verifies that the software *works*. **None of them verifies that a person can use
it.** Those are different properties, and only one of them is being judged.

This is the highest-value hour left before the deadline. A feature nobody can find
scores zero.

## The rule that makes it worth doing: do not help

The entire value is in watching someone get stuck. If you explain, hint, point at
the screen, or say "just click the—", **the data is gone.** Say only:

> "Talk me through what you're trying to do."

Write down where they pause. A pause is a finding. Ten seconds of hunting for a
button is a defect even if the button works perfectly.

**Do not demo first.** Do not open the app for them. Hand over a URL and the task
list, and be quiet.

## Who

- **Rehearsal 1 — Ann.** She does this work for real. She is the closest person on
  earth to the AIE production staff who will judge this, and she has never seen
  this app. Her instincts are calibrated on Sessionize, Sched and Basecamp, which
  is exactly the comparison being made.
- **Rehearsal 2 — someone who has never run a conference.** A blank slate catches
  the things Ann's expertise papers over. Ann knows what a "CFP" is; a judge's
  colleague might not.

Two people is enough. The first three tasks will produce more findings than we can
fix by Tuesday.

## Setup

- Use the **deployed URL** if it exists by then; otherwise `localhost:20500` with
  the seeded Charlotte world (`make store-reset && make seed-demo`).
- Have a **second event** ready to create from scratch — task 1 is the ten-minute
  timer and it needs a clean slate.
- Record the screen if they consent. If not, take notes; do not rely on memory.
- **Start a stopwatch on task 1.** Non-negotiable #4 is "zero-to-open-CFP in ten
  minutes" and the acceptance test is literally a timer. Now we find out.

## The tasks — read them aloud verbatim, then stop talking

Each is phrased as an outcome, never as a UI instruction. That's deliberate: naming
a screen is helping.

1. **"You're running a new conference in Denver next spring. Get to the point where
   you could send speakers a link to submit a talk."**
   *(Start the timer. This is the ten-minute claim.)*

2. **"Add me to the programming committee, and check that I'd actually be able to
   get in."**
   *(Watches: does the invite path make sense? Do they trust that it worked?)*

3. **"Add a question to the form asking which AI tools the speaker's team uses
   today, with a few choices to pick from."**
   *(The form builder is, in swyx's words, the whole product. Can she find it? Does
   she understand "retire" vs "delete"? Does the live preview help or confuse?)*

4. **"Pretend you're a speaker. Submit a talk. You have a Sessionize profile —
   use it."**
   *(The import is our killer speaker-side feature. Does she discover it without
   being told? Does she trust it? Watch what she does with the pre-filled bio.)*

5. **"Two of you have read that talk. Decide whether it's in."**
   *(The review board. Do the two sorts read as the two work queues? Is it obvious
   how to rate, and that others can see it?)*

6. **"Tell that speaker they're in."**
   *(The inform gate. Does the separation between deciding and telling make sense
   to her, or feel like an extra step? This is one of our strongest judgment calls
   and it needs to survive contact with a real operator.)*

7. **"A speaker emails to say their co-presenter changed. Fix it."**
   *(Deliberately something we may not support well. Co-presenter changes have the
   highest chase density in the Slack archive — ~90% — so this is real work, and
   whatever she does here tells us what the materials ledger must handle.)*

8. **"Where would you go to find out who still owes you slides?"**
   *(We know the honest answer today is "nowhere" — bd `-uxw`. Ask anyway. Where
   she LOOKS tells us where the feature belongs.)*

## What to write down

For every task, three things and nothing else:

| | |
|---|---|
| **Time** | Seconds from reading the task to the outcome being achieved. |
| **Stuck points** | Every pause over ~5s, and what she was hunting for. Verbatim. |
| **Words** | The words she used for things. If she says "the CFP" and we say "call for speakers", **she is right and we are wrong.** Vocabulary mismatches are cheap to fix and expensive to leave. |

Plus, at the end, exactly two questions:

- *"What surprised you?"*
- *"What would stop you using this for Charlotte?"*

The second one is the whole competition in a sentence — it is swyx's "would we
actually use/buy it" asked of the person who would have to.

## What to do with the findings

Triage the same night, into three buckets:

1. **Could not complete the task** → P0, fix before submitting. A blocked task is a
   lost judge.
2. **Completed, but hunted for it** → P1, usually a label, a button position, or a
   missing link. These are cheap and they are most of what separates a tool that
   feels considered from one that feels assembled.
3. **Completed fine, but she wanted something we don't have** → bd issue, and a
   candidate line for the writeup. Do NOT build these before Tuesday.

File everything in bd with the verbatim quote attached. A finding without the words
she used loses the thing that made it convincing.

## The bar

If Ann can get from nothing to a live public CFP link in under ten minutes without
help, we can say so in the submission and mean it — and that single sentence is
worth more to the AIE team than any feature we could add in the same hour.
