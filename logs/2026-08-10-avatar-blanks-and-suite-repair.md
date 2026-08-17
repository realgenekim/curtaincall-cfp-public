# 2026-08-10 — The blank avatars that weren't, and the 35-failure suite repair

**Asked:** Gene (screenshot of the review board, ~22:22): find the records with
no avatar and update them via the nREPL.

**Answer: zero records needed updating.** The two blank circles (Tomás
Lindqvist, Chioma Chen) were browser-side. Diagnosis chain, with the wrong
turns kept honest:

1. First hypothesis — pool short (face-pool-size 48, "only 30 jpgs") — was a
   MISREAD of a truncated `ls | head -60`; the count said 96 files (48 jpg +
   48 png) all along. Regeneration correctly no-op'd on every file.
2. Second hypothesis — broken `:headshot-url` on those speakers — refuted at
   the meter: across all 103 submissions exactly ONE speaker entry carries a
   headshot-url (Gene's real Sessionize one). Everyone else is `nil` → falls
   through to `pool-face`.
3. Ground truth via nREPL against prod Postgres (105 people): Tomás →
   `/images/people/p45.jpg`, Chioma → `p32.jpg`. Both files healthy 256×256
   JPEGs, on the running server's classpath, HTTP-identical to the faces that
   DO render. 35 of 105 people hash into 31–48; all had been blank until the
   full pool landed on disk at 21:03 (commit 29edb2d).
4. Verdict: Chrome held the in-tab image-load failures from before 21:03.
   Remedy: hard refresh. No data change, none wanted — the deterministic
   pool is the design.

**Collateral defects found and fixed (88f31ef, 63f3501):**

- `db.clj` logged the FULL JDBC URL — password included — on pool start,
  straight into 00SERVER-LOGS.txt. Now redacts password/user params.
- `.gitignore`'s `people/*.png` was anchored to repo root, so all 48 full-res
  AI headshot PNGs sat untracked (one `git add -A` from an ~80MB commit).
  Anchored to the real path.
- The suite was red in layers, none of them avatar work:
  - `server.clj` cold-load failure: `handle-board-sort` called `with-event`
    460 lines before its defn. Hot-reload masked it; a fresh JVM died.
    (Another live lane moved the helpers up as the proper fix mid-diagnosis;
    my interim `declare` was removed in favor of theirs.)
  - 35 stale-test failures, root cause the Tracks field (edff0bc: required
    select) landing with no fixture updated — every test-built submission
    failed re-validation, and `portal/update-answers!` REFUSES silently
    (`{:ok false}` discarded by callers). Swept via delegated agent
    (test/ only, src untouched, suspected-defect-report mandate): fixtures
    gained `:answer-track`; board/sidebar/scrubber assertions caught up to
    the shipped design. Zero real src defects.
  - Doctrine hardening: exports ics-test now asserts `(:ok res)` and
    `(not (:unchanged? res))` — the silent-refusal shape can never again
    impersonate a SEQUENCE bug.

**Still open:** bead 5nr — spark-label shows "26441 days left" when an event
has no CFP close date. Agent's cosmetic observations: seed.clj docstring says
"11 session fields" (now 12); `log-page` renders the time-travel bar
unconditionally while the board's rides the ENV=dev dev-strip — worth one
deliberate call on whether the scrubber is dev-only everywhere.

**Suite at close: 231 tests, 2235 assertions, 0 failures — two independent
full runs.**

---

## Addendum: demo submissions enriched to real-CFP length (append-only)

Gene: abstracts should be ~3x (they were one paragraph), transformation
history three paragraphs, measurable outcomes two. Done via
`dev/enrich_demo_submissions.clj` loaded into the prod REPL — every change a
`submission.answers-updated` fact through `portal/update-answers!`, actor
`operator:demo-enrich-2026-08-10`, all `:before` values captured.

- Backup before: `backups/2026-08-10-055038` (1,044 events); after:
  `backups/2026-08-10-055608` (1,126).
- **80 targets** (Enterprise AI Summit, Gene's own submission excluded, all
  Pending), **79 updated + 1 via the microscope row, 0 failed, 82 facts.**
- Abstract mean 319 → **1,085** chars (3 paragraphs); history 65–110 →
  **~1,100** (3 paragraphs); outcomes 41–73 → **~700** (2). Gene's own
  submission verified untouched (245 chars).
- Prose is composed from each submission's own facts (org, industry, year
  parsed from the old answer, org-size), 3 hash-picked skeleton families per
  slot; generated numbers reuse the $ figure and cycle-time % the seed text
  already claimed, so no submission contradicts itself.
- **Lesson, learned on the microscope row:** determinism is not idempotency —
  enriching an enriched submission compounds (the grown abstract becomes
  "paragraph one" and grows again). My recheck double-grew row `01e720c4`;
  repaired by recomputing from the first fact's `:before` (a third appended
  fact — the log tells the whole story). `run-one!` now guards on
  `already-enriched?`.

---

## Addendum 2: the review cycle, populated (Gene, Ann, Alex)

Gene: populate ratings/comments from the three real committee members so the
scrubber shows ratings piling up. `dev/populate_demo_reviews.clj` — domain
verbs only (`reviews/set-rating!` / `add-comment!`), actor = reviewer email,
reviewer personalities (Gene generous / Ann calibrated / Alex spiky),
burst-interleaved chronology so the scrub reads as three overlapping review
sessions.

- **Meter after:** 181 ratings (Gene 61, Ann 59, Alex 61), 80 comments,
  coverage 3 unrated / 8 single / 37 double / 33 triple, **17 SPLIT rows**
  with paired argument comments. Events 1,141 → 1,296.
- The batch appended 98 ratings + 58 comments and **skipped 51 pairs that
  already had stars** — my recon had said "0 ratings exist," which was the
  raw-vs-enriched-projection trap again (`(:ratings submission)` is always
  nil; `reviews/enrich` is the accessor). ~83 seed-era ratings pre-existed;
  the already-rated? guard preserved every one.
- Backups: before `2026-08-10-061649` (1,138), after `2026-08-10-062035`.

---

## Addendum 3: AI Engineer Code Summit — the full population + the 15-second fire

**The swyx event, complete** (all via REPL domain verbs, actor-tagged):
- Event + committee of six (swyx chair — placeholder swyx@ai.engineer until
  Gene supplies the real Google sign-in address — Ann, Alex, new reviewers
  Maya Lindholm + Devon Reyes, Gene), tracks retuned to AIE lanes.
- **500 submissions** in 5 waves via `dev/generate_aie_submissions.clj` —
  every one through `sub/create-submission!` against the live form; AI-eng
  voice, fictional companies only, 3-paragraph abstracts from birth.
  (Wave 1 taught the lesson: 100 creates outrun a 2-minute client timeout —
  the eval kept running server-side and completed; `resume!` recovers exact
  missing indexes from the +k@ email marker if ever needed.)
- **933 ratings + 419 comments** via `dev/populate_aie_reviews.clj`:
  personalities Ann calibrated / Alex spiky / Maya thorough (70% comments) /
  Devon terse (15%, one sentence). Coverage 51/97/254/64/34 (0→4 raters),
  84 splits. **swyx's column: zero ratings, on purpose** — the judge's
  first act in the product is his own.

**The 15-second page (Gene's emergency interrupt), root-caused + fixed
(16fbb5e):** every foreign append made refresh-if-changed! refetch the
ENTIRE store_events table (7k rows, 17MB, WAN) and refold from genesis, on
the request thread, queued on the write-lock — during the review batch that
meant every page load, stacked. Fix: `read-events-since` (indexed
`WHERE seq > mark`) + `(fold state tail)` under the same lock as append!.
Proven byte-identical vs full fold over all 7,185 prod events; live 5-event
tail-fold 71ms. O(total history) → O(new events), ~45x today, widening
forever. Deployed to both Cloud Run services.

**Dashboard**: mission-control rebuild ratified over ASCII mockups
(c046820) — tiles + needs-queue + humanized feed with board-row identity
chips; splits tile cut (Gene: board vocabulary, not glance vocabulary);
histogram bars / comment bullets un-swapped; cfp-url de-monospaced.

Meter at close: 7,185 events, backups bracketing every batch. Suite is the
test-agent's lane per Gene; one known stale expectation ("Open until"
wording) handed to it.

---

## Addendum 4: the second fire — pool exhaustion, the 390 ghost, three cures

Gene's report: 390 submissions where 500 should be, ratings invisible,
pg-pool exhausted (total=5 active=5, waiters dead at 30s), slow again.

**The database was innocent** — meter showed 7,185 events / 500 subs /
933 ratings intact throughout. The 390-vs-500-vs-5,865 on ONE page was a
single dev-server JVM holding mixed hot-reload generations of the store,
with its 5-connection pool wedged.

**The real connection hog: the time-travel scrubber.** state-as-of on
cache miss did a FULL read-events — 17MB over the WAN per scrub tick at
7k events. A few ticks in flight = pool gone = everything queues.

Cures (ca651d2, b7038df):
1. state-as-of folds from the state atom's own :log — a scrub tick can
   never touch the database again (fallback to read-events only when
   state is empty). Dashboard 15s → 0.42s measured; scrub ticks 0.4–1.7s,
   all CPU.
2. Hikari pools 5 → 10, both modes.
3. Clean dev-server restart cleared the mixed-generation state.

Plus: dashboard ratings tile (933 · 419 comments), and the sparkline's
third series — cumulative ratings in amber, own-max scaled; the wedge
between amber and red IS the review backlog. (First cut fed strings to
core inst-ms — Instants only; Gene pasted the exact error mid-fix.)
