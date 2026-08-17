# Captain's Log — the Publish and the Answer Key (2026-08-17, ~09:00–10:15 PT)

*Mayor seat. Gene woke to the overnight receipts, called the deadline, and
within an hour the repo was public on two hosts and the whole field's feature
grid had become our hill-climbing answer key.*

## 1. The publish (deadline passed → public in ~20 minutes)

Gene: "Deadline has passed. We need to publish a repo… Make sure no secrets.
I think we rehearsed this once before." We had — `bin/export_public.sh` +
`docs/allowlist-manifest.md`, ratified 2026-08-11. The real run taught three
lessons the rehearsal missed, each now baked into the script:

- **A dangling symlink broke the export** (`bin/mothership-open` → kiloclaw):
  laptop tooling, now vetoed by a symlink filter.
- **The publish machinery leaked what it redacted**: the export script and
  the manifest both named the withheld third party and described the withheld
  log. The scrubber no longer ships inside the scrubbed artifact.
- **Two more docs carried the trace** (provisioning-google-auth, the export
  rehearsal log) — anonymized, permanently in the script's sed block.

Scrub meters: zero credential patterns (only `T000/B000` test fixtures), zero
trace markers, every fenced path absent. 624 files, single commit, snapshot
of staging `6e5a65d` — which was also exactly what production served, so the
public code equals the live product.

Published: **github.com/realgenekim/curtaincall-cfp-public**. Minutes later
GitHub declared a major outage; Gene called the mirror and glab was already
authenticated: **gitlab.com/realgenekim/curtaincall-cfp-public**, same
commit, two hosts. `make export-public` now exists (export → scrub meters →
commit; push stays human).

Logs audit on request: **55 of 56 captain's logs are public** — the sole veto
is the third-party-trace log, per the ratified manifest.

## 2. The answer key (cicero-field-survey)

Gene dropped https://cicero-field-survey.elehche.workers.dev/ — a feature
grid of all 41 Kill My SaaS submissions (32 with analyzable source, graded at
pinned commits, 2026-08-16). Explicitly "not a scoreboard… the spread of
choices." For a hill-climber it is better than a scoreboard: it is the
field's revealed definition of DONE.

Our row (graded against the 1-commit rehearsal export): **13/15 baseline**,
3 beyond-brief credits (revision-history-with-restore, mixed-type rubric
criteria, cross-event CRM), 44K LOC vs 65K median, the only 1-commit repo in
the field (an allowlist-publishing artifact — everyone else shipped their
history; our logs ARE the history, on purpose).

The distribution killed the "did we rank last?" scare in one table: nobody
at 15/15, eleven at 14/15, **ten at 13/15 including us**, thirteen below.
Tied-12th of 32, one feature from the top band, and the top slot is EMPTY.

The two gaps, both already in motion before the survey confirmed them:

- **AI features: absent** (14/32 shipped some) → `6fb6` AI Reviewers, specced
  end-to-end overnight, claimed by cfp3 within minutes of the survey landing;
  the review-bot POST API is already in production.
- **Speaker CRM: "~ schema without queries"** (19/32 ✓) → the screens all
  existed with NO nav entry and no demo seeds — the same
  exists-but-unevidenceable disease the eval receipts found. Bead `ce5x`
  (cfp6): seeds, reachability, end-to-end walks. The Mayor cut the rail link
  himself (`45c9276`, sidebar fence) with the full intent triangle: NAV-006
  code tag + registry EARS + rail test pin + nav-oracle href — the
  traceability contract refused anything less, twice, which is exactly what
  it is for.
- **Sponsors: absent** — and only 3/32 built it. The field ratified our skip.

## 3. The doctrine note

An external artifact graded 32 parallel attempts at the same spec and handed
us: our exact position, the two cheapest climbs to the top, confirmation of
every deliberate refusal, and evidence that 34 of 48 innovations were
built by exactly one team each (differentiation is real). When a score and a
ratified decision disagree, the decision wins — but when a score and the
roadmap AGREE, you climb with conviction. 15/15 is one AI feature and one
nav link away, and both were dispatched before this log was written.
