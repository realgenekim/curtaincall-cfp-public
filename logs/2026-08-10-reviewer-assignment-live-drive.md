# Reviewer assignment live drive — 2026-08-10

## Environment

- Local sandbox server: `PORT=20500 ENV=dev make server-jsonl`
- Store backend: JSONL, 904 facts loaded at boot
- Browser personas: Ann Perry (reviewer) and Gene Kim (chair)
- No PostgreSQL, production database, deploy, DDL, email, GCS, or external
  service was used.

## Flow driven

1. Signed in as `annp@itrevolution.net` through the development magic-link
   echo.
2. Opened `/events/enterprise-ai-summit-charlotte`.
3. Verified the event landing redirected to
   `/events/enterprise-ai-summit-charlotte/review`.
4. Verified the reviewer rail contained only **Assigned to you** and **Shared
   review board**; organizer operations such as Inform Speakers and Exports
   were absent.
5. Verified the empty queue honestly rendered `0 of 0 reviews complete`.
6. Switched to Gene Kim through the development identity selector and opened
   the board.
7. Revealed the first submission's Quick rate card and verified the **Assign
   reviewers** controls listed Gene Kim, Ann Perry, and Alex
   Broderick-Forster.
8. Clicked **Assign · Ann Perry** for “Ten Things We Got Wrong Automating Code
   Review.” Verified the inverse control rendered **Assigned · Ann Perry**.
9. Switched to Ann Perry and reopened the event landing. Verified the queue
   rendered `0 of 1 reviews complete`, `1 remaining`, and the assigned talk.
10. Switched back to Gene and clicked **Assigned · Ann Perry**. Verified the
    control returned to **Assign · Ann Perry**. The sandbox's active assignment
    state was restored by appending `reviewer.unassigned`; no fact was deleted.

## Result

PASS. Assignment, reviewer landing, reviewer-only navigation, derived progress,
and unassignment all worked through the real HTTP routes and browser session.

## Defects found and fixed during the drive

- The first route-table save had one unmatched delimiter near the end of
  `server.clj`. The focused UI test failed while loading routes, before the
  browser drive. The delimiter was repaired and the compile probe was changed
  to a single `(do (require ... :reload) :ok)` expression so a trailing `:ok`
  cannot mask a failed require.
- Demo Reviewer persona fixtures were absent from this local JSONL world. The
  existing seeded reviewer (`annp@itrevolution.net`) and development magic-link
  echo provided the real reviewer path. This is an operator-seeding gap, not an
  assignment-feature defect; Phase 0 already owns the AIE persona seed gate.

## Still open

- Full `bin/kaocha unit` verification for the complete worktree.
- Production Phase 0 persona seeding and acceptance drive remain human/operator
  work; this drive intentionally did not touch production.
