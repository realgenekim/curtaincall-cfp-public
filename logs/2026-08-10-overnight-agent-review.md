# 2026-08-10 — Overnight agent review: four packages landed, one latent bug caught

**Asked:** Gene: review the overnight coding agent's diffs; assess what was
built and how far the needle moved on the metrics that matter (the sbek
rubric + bonuses).

**Scope reviewed:** five commits, `5ef1766..19c7c20`, 62 files,
+8,845/−5,889 — executing plans/2026-08-10-judge-readiness-plan.md.

## What was built

1. **`c429961` — judge sign-in (Package A) + views decomposition.** The
   three persona buttons to exact spec ("Organizer · swyx", "Reviewer ·
   Maya Lindholm", "Speaker · Amara Devlin"), `DEMO_PERSONAS` env gate,
   allowlisted magic-link echo, cookie session store with runtime-loaded
   secret, authz hardening, 8 sign-in tests + a new routes-contract test.
   Unrequested but sound: split the 4,000-line views.clj into a `views/`
   family (shell, review, schedule, portal, format, organizer-layout,
   public-widgets, public-cfp) with an architecture test enforcing it.
2. **`0a78cbc` — rule conversions (Package B).** The literal words the
   judge hunts: "Publish" on the schedule, "Approved" content status on
   review surfaces, the CFP-close edit lock message in the portal. All
   appended facts with folds.
3. **`45127b6` — telemetry (Package C).** 428-line telemetry.clj: queue +
   daemon flusher + multi-row batch INSERT, wrap-telemetry in both shells,
   analysis.clj (social-media-writer lineage), 174 test lines.
4. **`f399381` — review-surface hardening.** Board/SSE test depth
   (+31 SSE assertions), review + schedule view fixes.
5. **`19c7c20` — public widgets (Package D, partial).** Sessions list with
   the exact "Sessions 1 – N of N" anatomy, speaker detail with
   "Sessions (n)", public routes under /agenda/:slug/…. Smaller than the
   spec (194 src lines) — gallery/facets/consistency need verification.

## The bug caught in review (the suite doing its job)

Full-suite run under seed 1944678879 failed board-time-travel: a midway
scrub frame showed a submission that should not exist yet — but the test
passed in isolation. Root cause was LATENT and pre-existing: the as-of
cache keys on `[file-mark cutoff]`, file-mark for temp stores is
`[length mtime]`, and two tests' stores can collide — serving one test's
past to another. The agent's 27 new tests raised the collision odds enough
to expose it. Fixed in `3af6bb8` (clear the cache in reset-for-test!;
plus tonight's third forward-reference needing a declare).

**After the fix: 258 tests, 2,684 assertions, 0 failures** under the
exposing seed.

## Needle movement (baseline: docs/research/rubric-gap-analysis-2026-08-09.md)

- Before: **score withheld entirely** (no judge sign-in path); functional
  ceiling ~34% behind the wall.
- After: **scoring unlocked** (the whole ballgame) + Package B's ~2.4 pts
  + roughly half of Package D's 8.8 pending verification. Realistic
  standing: **mid-40s to ~50 of 100**, plus telemetry now recording for
  the ethnographic program.
- Unmoved: **Abstract Management (weight 20, ~13%)** — the biggest hole —
  files/uploads, bulk operations, CRM, and the bonus sweep.

## Still human-gated (morning operator)

Telemetry DDL as table owner · session-secret provisioning ·
`DEMO_PERSONAS=on` on the demo service only · verify "Amara Devlin" is
AIE-only (tenancy wall) · the decision wave so public widgets aren't
empty · deploy · live walkthrough on the deployed URL.

**Next:** plans/2026-08-10-close-to-100-plan.md — the full gap-closure
program to 100% rubric coverage.

**LOC census at review time:** src 17,992 · tests 9,369 · all source
~30,700 · docs/plans/logs 13,234 md lines.
