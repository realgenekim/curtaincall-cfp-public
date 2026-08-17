# Captain's log 2026-08-11 — the public allowlist export (crf.1)

**What was asked:** Gene picked ERA I node `crf` (the contest-required public
repo) off the tech tree, walked the light-up-tech-tree spec, and ratified:
EXPORT model (public repo is a manifest-driven artifact of this private repo,
fresh history, no shared git plumbing), name `curtain-call-cfp`, assembly dir
`~/src.local/curtain-call-cfp-public`, NO ethnographic studies, keep
gists/published links. "Publish now" — build today, Gene pushes after review.

**What was built:**
- `docs/allowlist-manifest.md` — the full KEEP/EXCLUDE verdict record with
  reasons, named accepted-risk items, and the three meters.
- `bin/export_public.sh` — repeatable: `git ls-files` → allowlist regex →
  one veto (a withheld third-party log) → rsync → redactions → park CI → README swap.
  Allowlist direction means a miss fails ABSENT, never fails public.
- `docs/public-readme.md` — judge-facing README (live URLs, personas, tour,
  trajectory map), swapped in as the export's README.md.
- Export itself: **419 tracked files**, single seat-authored commit, zero
  remotes until Gene pushes (bd `nuc` has the staged commands).

**Evidence / meters:**
1. Scrub grep over assembled tree: zero hits for credential patterns and the
   withheld third-party trace markers (only `T000/B000` test fixtures). Two third-party mentions had
   leaked into KEEP docs (open-signup.md, the-night-deploy log) — now redacted
   by the script, reproducibly.
2. Fresh-dir full suite, no secrets/: **378 tests, 3839 assertions, 0
   failures.**
3. Composition audit: fence dirs absent; docs/research = exactly the 10
   ratified KEEPs; docs/brief = brief.md only; CI/build files present.

**Defects found en route:**
- **clj-kondo silently lints 0 files when no `.clj-kondo/` dir exists up-tree**
  — broke `io-architecture-test` in the fresh dir with a baffling empty-analysis
  failure. The private repo has an empty untracked `.clj-kondo/`, so it never
  showed here. Export script now creates the dir. (File under: tools that fail
  quiet.)
- `.github/workflows/build-and-deploy.yml` would red-❌ on the public repo's
  first push (no WIF secrets) — parked as `workflows-disabled/` in the export.
- `secrets.clj` line 18 defaults `GCP_PROJECT` to `does2020` — the WRONG
  project (real: `swyx-cfp-saas-killer`, Makefile:158). Stale template
  leftover; harmless in prod (env always set) but should be fixed. Not part of
  crf.

**Accepted risks (Gene to confirm at review):** real staff work emails
(annp@/alex@itrevolution.net) remain in aie-corpus.json/seeds/tests — redacting
only the export breaks the green-suite meter, and they're already visible on
the deployed demo.

**Still open:** crf.2 (Gene: `gh repo create realgenekim/curtain-call-cfp
--public --source=. --push` from the export dir + Forge mirror). Discord
harvest (`x8d`) blocked on Gene re-logging into Discord in Chrome.
