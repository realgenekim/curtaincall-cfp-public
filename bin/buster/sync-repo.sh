#!/usr/bin/env bash
# One-shot: get or refresh the Curtain Call repo on Buster (bridge-cos account).
#
# Why a git BUNDLE and not a GitHub clone: the repo is PRIVATE
# (github.com/realgenekim/curtaincall-cfp) and Buster's genek-forge account
# deliberately holds no broad GitHub credentials (the conn passes, the powers
# don't). A bundle over ssh needs nothing but the tailnet.
#
# Gotchas this script bakes in (learned 2026-08-10, see docs/buster-lane.md):
#   - deps.edn uses :local/root ../datastar-helpers — the SIBLING repo must
#     exist on Buster AND be current (missing live-scrub = suite won't load).
#   - Buster's datastar-helpers may point at the marvin-openclaw777 FORK with
#     bridge's own unpushed branch work — always merge upstream, never clobber.
#   - Never tar working trees for code transfer (macOS tar litters ._* files);
#     bundles carry committed refs cleanly.
#   - An existing repo dir is NEVER overwritten: refs land under laptop/* for
#     a human (or agent) to merge deliberately.
set -euo pipefail

BUSTER="${BUSTER:-genek-forge@BUSTER-TAILNET-HOST}"
DEST="${DEST:-src/curtaincall-cfp}"
REPO="$(cd "$(dirname "$0")/../.." && pwd)"

echo "── bundling all refs from $REPO ──"
git -C "$REPO" bundle create /tmp/cc-sync.bundle --all
scp -q /tmp/cc-sync.bundle "$BUSTER:/tmp/cc-sync.bundle"

ssh "$BUSTER" '
  set -e
  if [ -d ~/'"$DEST"'/.git ]; then
    cd ~/'"$DEST"'
    git fetch /tmp/cc-sync.bundle "refs/heads/*:refs/remotes/laptop/*"
    echo "existing repo: refs refreshed under laptop/* (merge deliberately)"
  else
    git clone -q /tmp/cc-sync.bundle ~/'"$DEST"'
    cd ~/'"$DEST"' && git checkout -q staging
    echo "fresh clone at ~/'"$DEST"' on branch staging"
  fi
  rm /tmp/cc-sync.bundle

  if [ -d ~/src/datastar-helpers/.git ]; then
    cd ~/src/datastar-helpers
    git remote add upstream https://github.com/realgenekim/datastar-helpers.git 2>/dev/null || true
    git fetch -q upstream
    git merge -q upstream/main --no-edit \
      || { echo "WARN: datastar-helpers merge conflicted — resolve by hand"; exit 1; }
  else
    git clone -q https://github.com/realgenekim/datastar-helpers.git ~/src/datastar-helpers
  fi
  grep -q live-scrub ~/src/datastar-helpers/src/datastar_kit/ds.clj \
    && echo "datastar-helpers OK (live-scrub present)" \
    || { echo "FAIL: helpers stale — live-scrub missing"; exit 1; }

  command -v clj-kondo >/dev/null || command -v ~/bin/clj-kondo >/dev/null \
    || echo "NOTE: clj-kondo not installed (view-architecture test will error): \
run bash <(curl -sL https://raw.githubusercontent.com/clj-kondo/clj-kondo/master/script/install-clj-kondo) --dir ~/bin"
'
rm -f /tmp/cc-sync.bundle
echo "── done: $BUSTER:~/$DEST ready ──"
