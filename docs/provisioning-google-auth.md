# Provisioning Google auth — the complete runbook

*Born 2026-08-10/11, the night open sign-up shipped and every one of these
steps was learned by hitting its failure mode. If sign-in misbehaves, the
answer is on this page.*

## The two-gate model (understand this first)

A Google sign-in passes through TWO independent gates. Diagnose by asking
which one refused:

1. **Google's gate** — the OAuth app config (consent screen, publishing
   status, redirect URIs, client credentials). Failures happen ON GOOGLE'S
   pages: `Error 400: redirect_uri_mismatch`, "app not verified", "access
   blocked".
2. **Our gate** — the app's own policy in `auth_google.clj`. Since the
   RATIFIED open sign-up model (docs/open-signup.md), any verified Google
   identity is admitted via `find-or-create-person!` — sign-in IS sign-up.
   Failures here log `:google-refused` (historic; the roster gate is retired).

Field case: an ai.engineer team member, 2026-08-10 3pm — passed Google, refused by
our (then) roster gate. The fix was policy, not OAuth config.

## Google Cloud Console setup (one-time, project swyx-cfp-saas-killer)

Console → **Google Auth Platform** (APIs & Services → OAuth consent screen):

- **User type: External.** (Internal = itrevolution.net only, forever.)
- **Publishing status: In production.** Click "Publish app" — with only
  `openid email profile` scopes this needs NO verification review and takes
  effect immediately. Until published, only Test users (cap 100) get past
  Google. Published 2026-08-10 ~10:04pm. Reversible ("Back to testing").
- **Scopes:** exactly `openid email profile`. Adding sensitive scopes later
  triggers Google verification review — don't, without a plan.

**Clients** (Google Auth Platform → Clients → the web client):

- **Authorized redirect URIs** — the exact-match allowlist. Currently:
  - `https://curtaincallcfp-109637679549.us-west1.run.app/auth/google/callback`
  - `http://localhost:20500/auth/google/callback` (shared-tree dev server)
  - `http://localhost:20501/auth/google/callback` (worktree dev server,
    added 2026-08-11 ~00:00)
- **Every new dev port or deployed hostname needs its callback added here
  FIRST** — otherwise Google answers `redirect_uri_mismatch` and no code of
  ours even runs. When curtaincallcfp.com lands (DNS milestone), add
  `https://curtaincallcfp.com/auth/google/callback` (and www if served).

## Credentials — where the secret lives per environment

- **Local dev:** `secrets/google-oauth-client.json` + `.txt` in the repo's
  gitignored `secrets/` dir (mode 700/600; never committed, never pasted).
  `auth-google/enabled?` renders the Google button only when the credential
  is readable — **no secret = no button**, silently.
- **Git worktrees DO NOT inherit `secrets/`** (gitignored ≠ checked out).
  First act in any new worktree: `ln -s <main-checkout>/secrets secrets`.
  (This is why the worktree dev server had no Google button on 2026-08-10.)
- **Cloud Run:** Secret Manager, secret `google-oauth-client`, fetched at
  boot via gcp-secrets with metadata-server auth — zero env vars, zero
  mounts. The runtime SA (`cfp-saas-killer-run@…`) holds secretAccessor.
- **`GCP_PROJECT` env var must be set on every Cloud Run service** —
  secrets.clj falls back to project `does2020` without it and dies with a
  cross-project 403 at boot (bead 0a1 will make this fail loud; the demo
  service was missing it 2026-08-10 — two dead revisions before diagnosis).

## Related boot secret: session-cookie-key

Cookie sessions (judge-readiness) require Secret Manager secret
`session-cookie-key` containing **exactly 16 UTF-8 bytes**
(`openssl rand -hex 8`). Wrong length = container exits at boot.
`make preflight-deploy` checks existence + byte-length before any build.

## Dev/prod parity (Gene's standing ruling, 2026-08-10)

Dev and prod show the SAME login screen and the same behavior:

- The Google button renders in dev (secrets present) and genuinely works
  (redirect URI registered for the dev port).
- Additionally, dev echoes a magic sign-in link on the page for ANY email —
  parity with prod's open Google sign-up, needed because dev may lack the
  browser Google session and prod has no SMTP. Guarded by `auth/dev?`;
  prod NEVER echoes links (enumeration guard test:
  sinks_test `prod-magic-link-never-leaks-test`).

## Troubleshooting quick table

| Symptom | Gate | Fix |
|---|---|---|
| `Error 400: redirect_uri_mismatch` | Google | Add THIS host:port's `/auth/google/callback` to the client's redirect URIs |
| "Access blocked / app not verified" | Google | Publishing status must be In production |
| No Google button on the page | ours | `secrets/google-oauth-client.json` unreadable — in a worktree, symlink `secrets/` |
| Boot crash, Secret Manager 403 project does2020 | ours | Set `GCP_PROJECT` env var on the service |
| Boot crash, "exactly 16 UTF-8 bytes" | ours | `session-cookie-key` secret wrong length |
| Signed in but refused | ours | Should no longer happen (open sign-up); check `:google-refused` in logs |
