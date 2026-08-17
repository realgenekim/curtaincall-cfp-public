# 2026-08-08 — Research phase: ethnography of the Kill My SaaS competition

**What was asked.** Gene: enter swyx's "Kill My SaaS" contest ($10k, replace
Sessionboard's Program module, due Wed Aug 12 10PM PT). Ingest the brief (Downloads
docx), download the walkthrough video + grab frames + transcript, harvest the Discord
Q&A, conduct an ethnographic study of what swyx wants, produce an HTML mockup file.
Basis: mcp-clojure-template + Datastar, look like VL-admin/joe-payne. Postgres now,
Airtable sync later. Mid-session additions: git repo here; a repo skill for repeatable
Discord harvests; "sessionize-fulcro was the ideal reviewing mechanism — preserve it."

**What happened.**
1. Converted the brief (pandoc), extracted its 40 embedded Sessionboard screenshots →
   docs/brief/. Key facts: only the Program module matters; 6 required features; CF/
   Airtable/speed/API bonuses; requirements FREEZE after Sunday's video.
2. yt-dlp'd the walkthrough (33MB webm) + auto-captions → cleaned timecoded
   transcript.md; ffmpeg fps=1/5 → 119 frames. swyx's tells: "just a very fancy form
   builder", the submissions→sessions lifecycle, and THREE complaints about
   Sessionboard being slow (our Datastar angle).
3. Discord harvest via claude-in-chrome: screenshots + a DOM-walking JS collector
   (window.__msgs accumulator over virtualized scroll; CDP 45s timeout forces bounded
   loops; output pulled in ~950-char slices via browser_batch). All 51 #general
   messages captured incl. every swyx answer → docs/discord/2026-08-08-general.md.
   Discord logged out before #announcements — deferred. Method canonized as the
   repo skill .claude/skills/discord-transcript/.
4. Three Explore/architecture agents in parallel mapped our prior art:
   - sessionize-scraper (gaiwan/does): CDP headless scrape, reverse-engineered
     Sessionize data model in workshop.edn; ⚠️ live plaintext creds committed at
     sessionize_scraper.clj:387 → bd issue to rotate.
   - sessionize-fulcro: the review-UX doctrine (dense table, never collapse reviewers
     into a number, by-reviewer pivot, read-only overlay + deep links) + its gaps.
   - joe-payne-app = the fork basis (sse.clj push-agent design, hybrid-JSONB +
     events_log, tab dashboard); mcp-clojure-template scaffolds via make scaffold.
5. Wrote docs/research/ethnographic-study.md (the distilled spec + our unfair
   advantages + proposed architecture) and pricing.md (Sessionboard >$40k/yr;
   our Sessionize $499/event + Sched $50-188/mo).
6. Mockups: delegated to an Opus agent (frontend-design skill) → docs/mockups/
   index.html, 9 screens, review board as the crown jewel.
7. bd: 11 issues filed (scaffold, schema, form builder, review board, portal,
   schedule builder, comms, Airtable sync, API, Discord re-harvest, cred rotation).

**Why it matters.** This is a LIVE-edge flywheel project with a SELL kicker: winning =
$10k + a latent.space writeup; even losing = the CFP tool of our dreams (Sessionize +
Sched replacement, ~$1-3k/yr saved per event cycle, and we stop scraping). The
judgment-call tiebreaker favors us: we run the same lifecycle as AIE and have already
field-tested the review UX that Sessionize got wrong.

**Key spec amendments from Discord (verbatim swyx):** ics good enough · Airtable
read-only fine, automations fire on new rows · users "not technical at all; event
production professionals" · single form w/ track options · accepted speakers can edit ·
co-speaker accounts nice-to-have · no video link in invites, room assigned later ·
expect unserious submissions.
