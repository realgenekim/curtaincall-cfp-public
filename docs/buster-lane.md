# Running a Curtain Call work lane on Buster (bridge-cos)

*Born 2026-08-10 night: Gene's lane-B protocol (a Buster agent takes a
disjoint bead while Sol works locally; merge only after both suites green).
This doc + `bin/buster/sync-repo.sh` make the setup a one-shot.*

## The one-shot

From the laptop, in this repo:

```bash
bin/buster/sync-repo.sh
```

Result: `genek-forge@buster:~/src/curtaincall-cfp` exists on branch `staging`
with all branches from the laptop, the `datastar-helpers` sibling is present
and current, and the script warns if `clj-kondo` is missing. Idempotent: an
existing repo is never overwritten — refreshed refs land under `laptop/*`
for deliberate merging.

## Why each choice (the gotchas, earned the hard way)

1. **Bundle over ssh, not a GitHub clone.** The repo is PRIVATE
   (github.com/realgenekim/curtaincall-cfp) and Buster's `genek-forge`
   account holds no broad GitHub creds by design ("the conn passes, the
   powers don't"). A `git bundle` needs only the tailnet. (GitHub-auth route
   for Buster = open question; a fine-grained single-repo PAT would do.)
2. **Never tar a working tree for code transfer.** macOS bsdtar writes
   `._*` AppleDouble files GNU tar can't fold back in — 652 of them polluted
   the first transfer until cleaned with `find . -name '._*' -delete`.
   Bundles carry committed refs cleanly. (Tar overlay is still the right
   tool when you deliberately want UNCOMMITTED state moved — excludes:
   secrets/, 00SERVER-LOGS.txt, backups/, docs/recordings, docs/video,
   data/sessionize-scrapes, .beads.)
3. **`deps.edn` needs the sibling**: `:local/root "../datastar-helpers"` —
   so `~/src/datastar-helpers` must exist AND be current. A stale copy
   fails at load with `No such var: ds/live-scrub`. Buster's copy pointed
   at the marvin-openclaw777 FORK with bridge's own unpushed branch — the
   script merges `upstream/main` (realgenekim) without clobbering that work.
4. **`clj-kondo` binary required** by the view-architecture test (installed
   2026-08-10 to `~/bin` via the official install script; linux-static-amd64).
5. **Fresh directory per purpose.** `~/src/sessionize-sched-killer` (earlier
   snapshot, bridge's) and `~/src/curtaincall-cfp` (lane work) coexist —
   never overwrite another lane's checkout.

## Lane rules (from the Sol/cfp3 coordination protocol)

- One lane = one disjoint file-set, its own worktree if needed.
- Agents never commit to main; suite green before any merge.
- Merges happen on the laptop (lane C owner) after all lanes are green.
- Getting work BACK from Buster: `git bundle create` on Buster + scp home,
  or push to the private GitHub repo once Buster has a scoped PAT.

## Addresses

- Buster: `genek-forge@BUSTER-TAILNET-HOST` (tailnet). bridge-cos = Gene's own
  CoS tmux session there; `bridge` = the infra worker seat. Details:
  kiloclaw `docs/bridge-seats.md`.
