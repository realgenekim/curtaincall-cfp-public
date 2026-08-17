#!/bin/bash
# Install (or rotate) the Google OAuth client secret — the whole ceremony in
# one shot, runnable by a human. Usage:
#   bin/install_google_oauth_secret.sh [path-to-client_secret*.json]
# With no argument it takes the newest client_secret*.json in ~/Downloads
# (i.e. run it right after clicking "Download JSON" in the Google console).
#
# What it does, in order:
#   1. moves the JSON into ./secrets/ (700 dir, 600 file, gitignored)
#   2. extracts client_secret -> secrets/google-oauth-client.txt (what the
#      app's secrets.clj reads in dev)
#   3. pushes it to Secret Manager as `google-oauth-client` in the app's GCP
#      project (adds a new VERSION if it already exists — that's rotation)
#   4. prints the client_id + registered redirect URIs so you can eyeball
#      them — but never prints the secret.
set -euo pipefail
PROJECT="swyx-cfp-saas-killer"
SECRET_NAME="google-oauth-client"

SRC="${1:-$(ls -t ~/Downloads/client_secret*.json 2>/dev/null | head -1)}"
[ -n "$SRC" ] && [ -f "$SRC" ] || { echo "No client_secret*.json found (pass a path)"; exit 1; }

mkdir -p secrets && chmod 700 secrets
DEST="secrets/google-oauth-client.json"
[ "$SRC" -ef "$DEST" ] 2>/dev/null || mv "$SRC" "$DEST"
chmod 600 "$DEST"

python3 - "$DEST" << 'EOF'
import json, sys, re
d = json.load(open(sys.argv[1]))
w = d.get('web') or d.get('installed')
cid = w['client_id']
# GUARD: the downloaded client MUST match config.edn's :google-client-id.
# The 2026-08-09 footgun: a stale client_secret*.json from another project
# was newest in ~/Downloads and got installed, giving invalid_client at the
# token exchange. Refuse a mismatch instead of installing a broken pair.
cfg = open('resources/config.edn').read()
m = re.search(r':google-client-id\s+"([^"]+)"', cfg)
want = m.group(1) if m else None
if want and want != cid:
    sys.stderr.write(
        f"REFUSING: this JSON is for client\n  {cid}\n"
        f"but config.edn expects\n  {want}\n"
        f"Download the JSON for the RIGHT client (project swyx-cfp-saas-killer)\n"
        f"and pass its path explicitly.\n")
    sys.exit(3)
open('secrets/google-oauth-client.txt', 'w').write(w['client_secret'])
print("client_id:", cid, "(matches config ✓)")
print("redirect_uris:", w.get('redirect_uris'))
EOF
chmod 600 secrets/google-oauth-client.txt

if gcloud secrets describe "$SECRET_NAME" --project="$PROJECT" >/dev/null 2>&1; then
  gcloud secrets versions add "$SECRET_NAME" --data-file=secrets/google-oauth-client.txt --project="$PROJECT"
else
  gcloud secrets create "$SECRET_NAME" --data-file=secrets/google-oauth-client.txt --project="$PROJECT"
fi

git check-ignore -q secrets/google-oauth-client.txt \
  && echo "OK: secret installed locally + Secret Manager ($PROJECT/$SECRET_NAME); files gitignored" \
  || { echo "DANGER: secrets/ is NOT gitignored — fix .gitignore before committing"; exit 1; }
