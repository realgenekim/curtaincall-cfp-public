# swyx's rubric vs. our goals and vision

*Written 2026-08-09 overnight, triggered by Gene: "Get swyx rubric for hill climb.
Compare it to our goals and vision."*

## ⚠️ The newest rubric is NOT yet in hand

swyx tweeted **10:31 PM, Aug 8** (screenshot in Gene's hands):

> "i still think @AnthropicAI ultracode is one of the most important coding mode
> innovations ever invented. if you havent understood the potential of dynamic
> workflows you should try to. **just met a Kill My SaaS competitor who did a
> pretty good submission in 3 ultracode prompts**"

and, four minutes later, a reply beginning:

> "**immediately necessitated releasing my evals early** so he can…" *(truncated in
> the screenshot)*

**So swyx is publishing his evals ahead of schedule — that is the hill-climb
target, and we do not have it yet.** Two blockers:

- **Discord is logged out** in the automation's Chrome profile ("Please log in
  again", account gk0563). An agent must never enter credentials, so this needs
  one action from Gene: log in, then the `discord-transcript` skill harvests
  #announcements + #general in minutes.
- **x.com is paywalled to fetch** (HTTP 402), and web search has not indexed the
  post.

Everything below is measured against the rubric **as documented through Aug 8**.
Re-check every line once the new evals land.

---

## The rubric we DO have

From the brief (`docs/brief/brief.md`) and swyx's Discord answers:

| Criterion | Source | Exact wording |
|---|---|---|
| **Independent AIE-team evaluation** | brief | "Pass AIE team (**not swyx**) independent evaluation" |
| **The tiebreaker: product sense** | brief | "Tiebreaker will go to whoever has made **subjective judgment calls for the product that we would actually use/buy**" |
| **A deployed site** | brief | "**Deployed site we can test out** with the walkthrough shown" |
| **Non-technical users** | Discord | "they are **not technical at all**; they are event production professionals that just want to use software to make their lives easier" |
| **The real eval is adoption** | Discord | "the 'eval' is partially that **i will put it in front of them and they will actually use it**" |
| **Not slow** | video ×3 + brief | "we do not want slow SaaS pls" |
| **Scope = the Program module** | brief | CFP forms → speaker portal → evaluation/scoring → schedule → comms |
| **It's a form builder** | video 00:03:21 | "a very fancy form builder — **that's all it is**" |

## Where our vision already matches the rubric

| Their criterion | Our doctrine | Verdict |
|---|---|---|
| "would we actually use/buy" | Ten non-negotiables derived from **15 years of running the real process** — not guesses. Review is a conversation among trusted peers; two sorts ARE the work queues; 7-valued status + separate Notified flag. | **Our strongest suit.** Every one is a subjective judgment call backed by field evidence. |
| Non-technical users | "One page, no wizards, no account walls. Account created on submit." | Aligned |
| Not slow | Server-rendered Datastar SSE, sub-100ms pages, no SPA hydration | Aligned |
| Form builder is the core | The form is **data** (field-def vector, EDN/JSONB), IDs are forever, snapshot-per-submission | Aligned in the model — **UI was missing until tonight** (being built now) |
| Judges must never see an empty screen | Seed EAIS Charlotte form + realistic demo submissions + replay simulator | Aligned |
| Speaker experience | Validation never eats a typed abstract; confirmation repeats the title; inform gate means no one learns their fate by accident | Verified passing tonight |

## Where we are exposed

1. **No deployed site.** The brief names this explicitly — *"Deployed site we can
   test out"*. Everything today runs on `localhost:20500`. This is the single
   largest un-started requirement and it gates the AIE team's ability to evaluate
   us at all. **This should be the next build after the form editor.**
2. **The form builder is hours old.** swyx says the product *is* a form builder;
   ours has been a seeded data model with no editing screen. Being fixed tonight,
   but it needs to be genuinely good, not merely present.
3. **The walkthrough video does not exist.** "Deployed site we can test out **with
   the walkthrough shown**."
4. **Nobody outside the build has driven it.** The "eval" is a production
   coordinator using it unaided. Two uncoached operator rehearsals (Ann first) are
   planned for Tuesday and are the only real evidence we can offer against the
   stated eval method.
5. **A competitor already has "a pretty good submission"** — and swyx met them.
   Field quality is real; ours has to be visibly considered, not merely complete.

## Where our goals differ from the rubric — deliberately

Gene's ranked goals are (1) deepen the friendship with swyx, (2) notoriety for
sharing openly and demonstrating domain mastery, (3) win; the money is
explicitly irrelevant ("I don't care about the money and prize. I'd donate it").

The rubric optimizes for *a product AIE would buy*. Our goals additionally
optimize for *a body of shared work that makes the domain legible to everyone
else* — the field study, the 15-year tool archaeology, the public reference repo.
Those earn nothing on swyx's scorecard and everything on Gene's. **Keep them
separate**: ship the product against the rubric; publish the research because it
is the point, not because it scores.

One genuine tension to hold: the rubric rewards *their* judgment calls, and our
strongest material is *Gene's* judgment calls from running conferences his way.
Where the two differ (blind review vs. open table; evaluation plans vs. no
bureaucracy), we should present ours as a considered position with evidence —
not quietly conform, and not lecture.

## The hill-climb list, in order

1. **Get the new evals** (needs Gene's Discord login) and re-score everything here.
2. **Deploy.** A URL the AIE team can open is a hard requirement.
3. **Make the form builder genuinely good** — it is, in swyx's words, the product.
4. **Record the walkthrough** on the deployed site.
5. **Two uncoached operator rehearsals**, and fix whatever they stumble on.

---

## Progress against this list — overnight, 2026-08-09

*Appended at the end of the drive. Scores are against the Aug-8 rubric; the new
evals may move them.*

| # | Item | Status |
|---|---|---|
| 1 | Get the new evals | **Blocked on Gene.** Discord is logged out in the automation's Chrome profile; an agent may not enter credentials. |
| 2 | Deploy | **Prepared, not executed.** Every prerequisite verified green; three decisions isolated with recommendations in `docs/deploy-runbook.md`. ~15 mechanical minutes once decided. |
| 3 | Form builder | **Built and driven end to end.** Custom question created → appeared on the public CFP → a speaker answered it → stored under a stable id → rendered for the committee. |
| 4 | Walkthrough video | Not started; needs #2 first. |
| 5 | Operator rehearsals | **Protocol written** (`docs/operator-rehearsal-protocol.md`, bd `-nuk`). Needs a human. |

### What changed in our exposure

Two of the five exposures listed above are materially better, and one got worse in
the sense that we now *know* about it:

- **"The form builder is hours old."** It is now real, and the judgment calls in it
  are the kind the tiebreak rewards: retire-never-delete, ids that survive renames,
  a live preview running the actual public renderer, and a banner that tells an
  organizer the thing they are actually afraid of — *"8 submissions already exist.
  Editing the form now is safe: every submission carries its own snapshot…"*
- **We found and closed a defect that would have ended the competition if a judge
  found it first.** Any speaker could accept and decline talks. In a CFP tool that
  is not a bug, it is a disqualification. It is fixed, verified in the deployed-style
  environment, and — the part that matters for a rubric about judgment — fixed by
  *inverting the default* so the next endpoint is protected without anyone
  remembering. That property then demonstrated itself on six routes written hours
  later.
- **Newly known: we publish a schedule we know is impossible** (bd `-31b`). The
  organizer's conflict handling is genuinely good; the public agenda leaks the
  conflict anyway. A judge browsing the demo sees one speaker in two rooms at once.
  Small fix, real credibility cost, decide before deploying.

### The honest read

Against *"would we actually use/buy it"*, our position is stronger tonight than it
was: the product now does the thing swyx says the product **is**, the review flow is
backed by fifteen years of evidence rather than guesses, and the speaker experience
(no account wall, validation that doesn't eat your abstract, a profile import that
deletes an entire category of chasing) is better than the incumbent in ways a
production coordinator would feel in the first ten minutes.

Against *"deployed site we can test out"*, we still score **zero**, and no amount of
product quality substitutes for it. That remains the single highest-value action
available, and it is gated on three decisions rather than on work.

