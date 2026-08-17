# Sharing strategy: Fable's view, Sol's adversarial view, and the committed plan

*2026-08-08. Question: should Gene share the research publicly (Discord/X/video)
during the Kill My SaaS contest? Gene's ranked goals: (1) deepen the friendship
with swyx, (2) notoriety for sharing openly / demonstrate domain mastery,
(3) win. Money explicitly irrelevant (would donate). Deadline Wed Aug 12 10PM PT;
swyx records clarifying videos Sat + Sun, then requirements FREEZE.*

---

## Fable's original view: share aggressively, withhold two cards

**For withholding:** the real edge is knowing *what* to build (conversation-first
review, coverage queues, blocking-sheet scheduler, invited-speaker lane, the
ai.engineer export seam), and those compress into bullets a competitor can paste
into an agent prompt in five minutes. Tiebreaker = "judgment calls"; publish them
Friday and by Monday they're everyone's.

**For sharing (the winning side of the argument):**
1. Copying bullets ≠ executing them — skin-deep copies read skin-deep to judges
   who actually use the tool.
2. Public sharing timestamps provenance — it *notarizes* the judgment-call
   credit rather than diluting it.
3. The $10k is the smallest prize; notoriety, mastery-display, and the
   friendship are all maximized only by sharing (the Prime Directive flywheel:
   LIVE → CHRONICLE).
4. The swyx factor: he wrote "Learn in Public." Gifting the field a decade of
   domain research about his own problem is the most swyx-resonant move
   available; it elevates his contest and pre-writes his latent.space story.

**Original tiers:** T1 now (flow gallery, BusyConf archaeology, German-tank
analysis, 7 questions before the freeze); T2 post-freeze (mockups + design
notes); T3 withheld to submission (byte-compatible ai.engineer exports, the
deployment); plus an 8-10 min public "Ten Years of CFP Tools" video during the
contest. Risk named: celebrity-intimidation, mitigated by generous tone.

---

## Sol's adversarial view (gpt-5.6-sol, full text: sharing-strategy-sol-review.md)

**Verdict: "clever but self-sabotaging… ship quietly, test brutally, win on the
product, and learn in public after the game ends."**

Eight attacks, four rated Critical:

1. **Provenance thesis is wrong** — judges buy software, not intellectual
   priority; with coding agents, 48h suffices to copy a workflow. "The contest
   exists precisely because execution has become cheap."
2. **The campaign reads as an influence operation** — famous friend of the
   organizer flooding the field: lobbying the requirements author, intimidating
   entrants, making it awkward not to pick Gene. Judges may compensate *against*
   him; swyx may steer requirements away to avoid appearing captured.
3. **German-tank analysis is radioactive** — public revenue estimate of a tiny
   founder-run vendor (not even the contest target), from credentialed scraping,
   decisive variable invented, precision theater over 2-3× error bars. "No
   favorable payoff distribution." Kill entirely.
4. **The research repo is unsafe to open-source** — private Slack/Gmail material
   is in git history; .gitignore is not remediation. Submission needs a clean,
   newly initialized allowlist repo + secret/PII scans.
5. **Gene's experience is evidence, not ground truth about AIE** — nine-person
   trusted committee may not generalize; doctrine before validation =
   "sophisticated overfitting."
6. **Time spent signaling is time not spent completing** — six required systems;
   a complete, persisted, deployed golden path beats an ingenious review board
   surrounded by placeholders. Media production consumes exactly the hours the
   ugly edge cases need.
7. **The video "gift" competes with swyx** — publishing a grand field study
   during his own Sat/Sun video window fragments attention and casts Gene as
   co-host. "A real gift gives the recipient control": prepare privately, offer
   after judging, let swyx choose timing.
8. **The export ace isn't magic** — hidden features score zero; byte-compat is
   fragile; endpoints are publicly discoverable anyway. Build early, test
   against the real consumer contract, demonstrate at evaluation.

Also: **accept the prize normally** — pre-announcing donation is moral theater
and another way to influence judging; decide concretely afterward. Highest-EV
change: "replace the entire pre-deadline thought-leadership campaign with two
unassisted usability rehearsals of a fully deployed end-to-end workflow."

---

## The adjudication

**Where Sol wins outright (conceded):**
- **German-tank stays private, full stop.** Punching down at a non-target small
  vendor with scraped-data provenance: zero upside. Post-contest, anonymized, at
  most.
- **Clean allowlist submission repo**, newly initialized, secret-scanned. This
  research repo is private permanently.
- **No steering framing on the questions.** Trim to the genuinely blocking 4-5,
  phrase neutrally, advocate nothing. Asking is invited; designing questions to
  bend the freeze is lobbying.
- **Don't pre-announce the donation.** Decide now, announce after, concretely
  (e.g., contributor bounties on the open-source repo).
- **Golden-path discipline governs the build**: the 10-step unassisted judge
  journey; aggressive cuts; **two conference-ops people run the whole flow
  uncoached before submission** (Sol's best original idea — the unfair advantage
  is access to people who've done the job, not the archive).
- **Export ace: build early, demo openly at submission**, implementation private
  until then.

**Where Sol's frame is wrong (held):**
- Sol optimized for **winning** — but Gene ranked friendship first, notoriety
  second, winning third. Attacks #1 and #6 are only "Critical" against the
  wrong objective function. Sharing transfers some edge; Gene is explicitly
  willing to pay that price for goals 1-2.
- The "influence operation" read over-weights optics: the Discord's explicit
  norm IS public building — swyx told entrants to call dibs by posting on X and
  said "share your stuff." One well-made artifact from a fellow entrant is
  participation, not capture. Volume is the variable: one artifact = generosity;
  six = a siege.
- Attack #5 (overfitting) argues FOR the interview questions, not for silence —
  the questions exist to test whether AIE's process matches ours.
- Media production costing build time: mitigated by the constellation — Fable
  drafts everything; Gene's hours go to the build and the two usability
  rehearsals.

---

## The committed plan

1. **Share once, not a campaign.** One Discord post + one X thread: the
   sanitized flow-diagram gallery with Gene's personal intro ("five of the worst
   tools on the planet… held hostage") + the BusyConf story — explicitly gifted:
   "use any of this, all of you." No mockups, no German tank, no drumbeat.
2. **Questions: 4-5, neutral, posted this weekend before the freeze** — review
   model reality, invited-speaker share, schedule-drafting reality, export
   pipeline shape, eval scale. Discovery, not steering.
3. **The documentary is made now, shipped after judging** — offered to swyx
   privately first: "use any of it; what timing helps you?" It becomes
   latent.space material instead of contest-weekend competition.
4. **Build to Sol's golden path**, cut everything not on it, seed realistic
   data, and run two uncoached operator rehearsals before submitting.
5. **Clean allowlist submission repo**; research repo private forever.
6. **Exports built early, demonstrated live at submission.**
7. **Prize: accept normally; announce the concrete donation after.**

**One line:** share once with warmth, ask neutrally, build the golden path,
save the film for after — nearly all of the friendship and notoriety, at almost
no cost to the win.

---

## Appendix: the questions for swyx (trimmed + neutralized per the committed plan)

Ready to paste into Discord before Sunday's freeze.

**Doctrine revision (Gene, 2026-08-08): "neutrality" was an over-correction.**
Two things got conflated: (a) engineering questions to steer the frozen spec
toward our build — gaming, stays dead; (b) proximity to the customer — the
Spear method, the actually-scored skill, and explicitly invited by swyx ("pls
ask for specific flows"). Hypothesis-scrubbed questions are WORSE discovery:
they hide the credential, offload the taxonomy onto the customer, and get mushy
answers. Revised rules: **public not private** (equal access is the real
fairness mechanism — answers land where every competitor sees them),
**hypothesis-forward and honestly open** (state the twelve years; be ready for
either answer), and **peer-to-peer, no pitch** (Gene, 2026-08-08: "I run
conferences my way. He runs them his way. I'm not pitching anything." The
questions are one practitioner asking another how HIS shop actually works, so
the tool gets built for AIE's practice — not advocacy for ours). (Original seven drafted 2026-08-08; two folded/dropped in the
neutral trim, noted below.)

1. **THE MAIN QUESTION — the evaluation ethos.** (Hypothesis-forward per the
   revised doctrine — state the twelve years, be open to either answer.)
   Public phrasing: "I've run conference CFPs for twelve years, and the deepest
   design fork I know is the review *culture* the tool embodies. Two poles:" 
   - **Blind/solo (NeurIPS-style):** reviewers work independently, can't see
     each other's scores or comments (sometimes can't see who the speaker is),
     and the tool aggregates at the end. Most CFP tools default to this —
     every evaluator scores in their own private queue.
   - **Collective responsibility:** a program committee is jointly responsible
     for the whole program; nothing is private — everyone sees everyone's
     scores and comments as they land, and the comments effectively become the
     agenda for the committee's calls.
   "Our committee happens to run the second way — nothing private, comments
   drive the calls — but that's just our shop; you run yours your way, and I
   want to build for YOUR practice. Which pole is closer to how AIE actually
   decides? And if reviewers can see each other's opinions, is that a feature
   or a bias risk for your committee?" 

   *(Gene's private framing, for our own record: 12 years of running it the
   second way — "I expect a program committee to be responsible for all the
   talks; nothing is done in private; comments become the agenda for our PC
   calls." Sessionize enforces the first way — "everyone has their own trough
   that they eat from, and they're not allowed to see what anyone else does" —
   and BusyConf's shared-everything model is what we've spent years trying to
   replicate. The answer to this one question determines more of the review
   board's design than everything else combined.)*
2. **Invited speakers:** Roughly what fraction of the program never comes
   through the CFP (invited keynotes, sponsor sessions), and how do those enter
   the system today? Should they share the same portal/tasks as CFP acceptances?
3. **Schedule drafting:** When you built the World's Fair schedule, where did
   the draft work happen (Sessionboard's agenda? a spreadsheet/doc first?), and
   what constraints bite hardest — speaker travel windows, room capacity, track
   curation, late drops?
4. **Publishing pipeline:** What produces sessions.json / speakers.json /
   calendar.ics for ai.engineer today, and what would the replacement tool need
   to emit for your publishing layer to keep working unchanged?
5. **Evaluation scale & shape:** For the judging, will the AIE team load real
   data — roughly how many submissions does a Summit/World's Fair CFP pull, how
   many tracks and rooms does a typical event run (the World's Fair schedule
   shows 9+ tracks over 4 days — is that the size we should design for?), how
   many people review, and is it one event at a time or several concurrently
   (World's Fair + Europe + Summits on one instance)?

Dropped/folded in the trim:
- *Speaker-afterlife tasks* (headshot/slides/AV/travel chasing; announce-
  permission; employer-approval tracking) — folded into Q2's portal/tasks clause;
  ask as a follow-up if swyx engages.
- *Comms deliverability* (whose address, spam experience) — dropped as
  leading/steering; the .ics answer is already settled and we'll build
  personal-sender comms on our own field evidence regardless.

---

## Sol round 2 (goals made binding) — revised verdict and FINAL plan

Sol retracts "ship quietly": **"share real work before the freeze, compete
without exploiting the friendship, and publish the full story after judging."**
(Full text: sharing-strategy-sol-review.md + sharing-strategy-sol-round2.md.)

Retracted: cancel-all-sharing; the one-modest-paragraph idea ("far too timid —
neither demonstrates mastery nor helps the community"); provenance-is-worthless;
media-steals-build-time (agent-produced media doesn't compete with Gene's hours).

Held (now joint doctrine): German-tank private forever · clean allowlist repo ·
4-5 neutral questions · no donation talk pre-result · golden path + two uncoached
rehearsals · exports demonstrated at submission · documentary after judging ·
**no private call/preview/feedback ask to swyx during the contest** · no volume
campaign.

Goes FURTHER than the counter-review: the pre-freeze artifact should be MORE
substantive — gallery + BusyConf story + **the sanitized field-study synthesis
AND the design principles derived from it** ("otherwise it demonstrates research
activity, not mastery"). And the post-judging release should be a full research
package (method, observations vs interpretations, failed hypotheses, limitations,
what to test next) alongside the documentary: "reach comes from the film;
durable authority comes from the written package."

### FINAL timeline (adopted)

| When | What |
|---|---|
| Before Sat video | Dibs + one-line personal problem statement only (X, mirrored in Discord). |
| **After Sat video, before Sun freeze** | **THE artifact**: flow gallery + BusyConf archaeology + Gene's intro + field-study synthesis + design principles, on one canonical page; one Discord post + one X post pointing at it: "made for the contest community — use any of it." Neutral questions posted alongside. |
| Mon–Tue | No new broadcasts. Helpful replies only. Documentary produced privately. |
| Wed | Submit (clean repo, golden path, live export demo, concise demo video). THEN one understated "shipped" note. |
| After judging | swyx gets a private first look at documentary + full research package (no response expected); public the next day. Personal thank-you note — private, asks for nothing. |

**Highest-friendship-yield action:** the pre-freeze artifact, gifted with no
private ask. **Likeliest well-intentioned friendship damage:** asking swyx for a
call, preview, or feedback while the entry is being built or judged — "a call
transfers Gene's ambiguity onto swyx. Don't ask."
