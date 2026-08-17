## 1. Strongest attacks

1. **The provenance thesis is wrong.**  
   **Severity: Critical — kills Tier 1 and Tier 2 before judging.**

   Judges are buying software, not adjudicating intellectual priority. A timestamp does not earn points when another entrant turns Gene’s insight into a better implementation. With coding agents, 48 hours is enough to copy a workflow, mockup, schema, or export contract. Publicly releasing the research gives competitors distilled judgment while Gene keeps the cost of producing it.

   “Copying bullets isn’t execution” is outdated game theory here. The contest exists precisely because execution has become cheap.

2. **The campaign can look like an influence operation.**  
   **Severity: Critical — kills deliberate requirements steering and the content blitz.**

   A famous friend of the organizer publicly flooding the field with authoritative research, leading questions, mockups, and a polished video is not neutral generosity. It can read as:

   - lobbying the requirements author;
   - pre-claiming intellectual ownership over obvious product decisions;
   - intimidating less-famous entrants;
   - building a public narrative that makes it awkward not to select Gene.

   The AIE judges may compensate by holding Gene to a higher standard or rejecting him to protect fairness optics. swyx may steer requirements away from Gene’s territory precisely to avoid appearing captured. The strategy invites the counter-move it is supposed to prevent.

3. **The German-tank story is reputationally radioactive and analytically weak.**  
   **Severity: Critical — kill it entirely, including after the contest.**

   This is an irrelevant public attack on Sessionize while the competition targets Sessionboard. It combines credentialed scraping, sequential-ID leakage, uncertain Terms-of-Service exposure, and a revenue estimate whose decisive variable—the paid share—is invented. “$500–700k” sounds precise despite acknowledged 2–3× error bars.

   Publicly declaring a tiny founder-run vendor a “revenue desert” creates enemies, embarrasses a small business, and distracts from Gene’s product judgment. The upside is a clever anecdote. The downside is legal attention, vendor retaliation, security scrutiny, and swyx wondering why his contest became a vehicle for punching down. There is no favorable payoff distribution.

4. **The current research repository appears unsafe to open-source.**  
   **Severity: Critical — kills any plan to publish this repository directly.**

   It contains private Slack exports, real names, candid speaker/vendor material, and Gmail-derived commercial history. The private-data warning is explicit in [team-planning-ethnography.md](</Users/genekim/src.local/sessionize-sched-killer/docs/research/team-planning-ethnography.md>) and [README-PRIVATE.md](</Users/genekim/src.local/sessionize-sched-killer/docs/slack/README-PRIVATE.md>). The research log also records plaintext credentials in related prior art.

   `.gitignore` is not remediation if sensitive material ever entered history. The submission needs a clean, newly initialized release repository populated from an allowlist. Accidentally publishing the current repo would dwarf every contest outcome.

5. **Gene’s experience is evidence, not ground truth about AIE.**  
   **Severity: High — amends the product thesis.**

   The leap from “our nine-person committee loved this” to “swyx’s committee will be the same” is unsupported. Gene’s workflow emphasizes collective conversation, a 400-row review surface, Google Sheets, and trusted repeat collaborators. AIE may use assigned reviews, stronger permission boundaries, sponsor sessions, temporary contractors, or operations staff who prefer guided workflows.

   The field research should generate hypotheses. It must not become doctrine until an AIE-like operator succeeds unassisted. Otherwise “domain mastery” becomes sophisticated overfitting.

6. **The plan spends the scarce resource—time—on signaling instead of completion.**  
   **Severity: Critical — kills the media-production schedule.**

   There are six required systems, each with ugly edge cases. A complete, persisted, deployed golden path beats an ingenious review board surrounded by demos and placeholders. The subjective tiebreaker matters only after the entry survives basic evaluation.

   An 8–10 minute film, multiple posts, mockup notes, and public Q&A consume the exact hours needed for authentication, email delivery, `.ics` updates, scheduling conflicts, uploads, seeded data, deployment recovery, and usability testing.

7. **The video “gift” competes with swyx rather than helping him.**  
   **Severity: High — kill before judging; coordinate afterward.**

   swyx already announced Saturday and Sunday videos. Publishing a grand “field study” during that window fragments attention and subtly casts Gene as co-host, interpreter, or expert above the brief. It may force swyx to respond, incorporate it, distinguish his content from it, or manage community reactions.

   A real gift gives the recipient control. Prepare the video privately, then offer it after judging with no obligation and let swyx choose timing.

8. **The secret export is useful, but it is not a magic ace.**  
   **Severity: High — amend Tier 3.**

   A hidden feature earns zero unless judges see its operational value. “Byte-compatible” is also fragile: undocumented assumptions about IDs, nulls, ordering, image URLs, or caching can break the consuming site. Conversely, if the endpoints are already public, competitors can discover them without Gene’s posts.

   Build it early, test it against the actual consumer contract, and demonstrate AIE’s site consuming the output. Keep implementation private until submission, but do not hide the value during evaluation.

## 2. The amended plan I would run

### Immediately

- Cancel the Tier 1/Tier 2 publishing calendar.
- Never publish the German-tank revenue estimate.
- Create a clean submission repository with no shared Git history. Copy only reviewed application files through an explicit allowlist.
- Run secret, PII, and history scans before making it public.
- Keep the research repository private permanently.

Make at most one short Discord post: Gene has run CFP programs for a decade, is building seriously, and will publish sanitized field notes after the contest. No gallery, mockups, archaeology, or claims of having discovered the winning doctrine.

Ask only genuinely blocking questions. Bundle them, phrase them neutrally, and do not advocate an answer. Seven questions designed partly to steer the freeze is lobbying, not discovery.

### Build one complete evaluation path

Everything should support this unassisted judge journey:

1. Create an event and CFP form with one useful conditional field.
2. Submit as a speaker without account friction.
3. Enter the speaker portal and update bio, headshot, and materials.
4. Review the submission as two evaluators.
5. Accept it and assign onboarding tasks.
6. See the dashboard update immediately.
7. Drag the session onto the agenda.
8. Attempt an invalid placement and receive a clear conflict explanation.
9. Send a real templated email with a valid `.ics` attachment.
10. Export `sessions.json` and `speakers.json`, then render them through an AIE-compatible preview.

Persistence across restart, understandable errors, and obvious role switching are mandatory. Seed the deployment with realistic data so judges never face an empty screen.

### Cut aggressively

Do not build before the golden path works:

- AI review;
- multi-round evaluation;
- generalized workflow engines;
- full two-way Airtable synchronization;
- elaborate CMS/wiki functionality;
- exotic Cloudflare architecture;
- reply-by-email;
- a comprehensive BusyConf recreation;
- decorative analytics;
- extra schedule views that are merely different chrome around identical data.

Use the existing Clojure path only if it is genuinely paved and deployable. Architectural elegance earns nothing when the demo fails.

### After Sunday’s freeze

Turn the frozen requirements into a literal acceptance checklist. No more archaeology and no speculative features.

Have at least two conference-operations people perform the entire journey without coaching. Observe where they hesitate. Fix labels, defaults, navigation, and recovery before adding another differentiator. Gene’s unfair advantage is not possessing old screenshots; it is access to people who have actually done the job.

A sensible sequence is:

- Sunday: complete and deploy the golden path.
- Monday: unassisted operator tests; fix workflow failures.
- Tuesday: email, calendar, export-contract, persistence, and conflict tests; polish.
- Wednesday: fresh-account rehearsal, mobile speaker flow, deployment recovery test, final submission.

### Submission and judge psychology

Use a short product demo, not a documentary. Show the complete workflow first. Then state only three judgment calls, each tied to an observed operational failure:

- one-page comparison instead of review-plan bureaucracy;
- onboarding as visible task state;
- the schedule as the source that publishes everywhere.

Demonstrate the AIE-compatible export live. Do not lobby swyx, tag judges repeatedly, or invoke Gene’s stature. Let the independent evaluation remain visibly independent.

### Publishing and relationship strategy

After judging, privately tell swyx:

> “I prepared a sanitized field study, BusyConf history, diagrams, and a video. I’d love to release them, but I don’t want to step on your writeup or content plan. Use any of it, and tell me what timing helps.”

Then publish in coordination with him. That is more relationship-building than forcing a “gift” into his event while he is still running it.

### Prize money

Accept the prize normally. Pre-announcing refusal or donation would be moral theater and another way to influence judging. If Gene wins and wants the strongest reputational outcome, decide afterward whether to fund maintenance, contributor bounties, or documentation. Make that commitment concrete before announcing it.

**Single highest-EV change:** replace the entire pre-deadline thought-leadership campaign with two unassisted usability rehearsals of a fully deployed end-to-end workflow.

## 3. Verdict

The current strategy is clever but self-sabotaging: it gives competitors Gene’s edge, gives judges a fairness problem, gives swyx extra work, and risks a privacy or vendor-relations disaster; ship quietly, test brutally, win on the product, and learn in public after the game ends.