# 2026-08-11 — The CI pipeline one-shot (commit = deploy)

**What was asked:** Gene, mid-ship-train, after a parallel-agent working-tree
collision: "can you create a gh build/test/deploy, so we can do things like
'commit this one fix' while other agents work on other things? we should be
able to one shot this; so many sibling repos use this."

**Why it mattered today specifically:** local `make deploy-staging` builds the
image from the WORKING TREE. This afternoon two lanes shared the tree, and a
deploy could have shipped another agent's half-done edits. CI builds from the
commit — that hazard is now structural, not procedural.

## What was built (one session, ~40 minutes)

1. **GCP provisioning** (keyless — no service-account key anywhere), following
   the conference-ticket-funnel / joe-payne-app house pattern:
   - WIF pool `github-pool` + OIDC provider `github-provider` in
     swyx-cfp-saas-killer, condition `repository_owner == 'realgenekim'`
   - SA `gha-deployer@swyx-cfp-saas-killer`: roles/run.developer,
     roles/artifactregistry.writer, serviceAccountUser on
     cfp-saas-killer-run, secretAccessor on session-cookie-key +
     google-oauth-client (for preflight)
   - workloadIdentityUser binding scoped to ONLY realgenekim/curtaincall-cfp
2. **`.github/workflows/build-and-deploy.yml`** (replacing the dead
   booktracker scaffold from the template): push to staging →
   - test job: clone ../datastar-helpers (deps.edn :local/root sibling),
     Temurin 21, Clojure CLI + clj-kondo, `bin/kaocha unit`
     (CFP_MAIL_DISABLE baked in — CI cannot send mail)
   - deploy job: WIF auth → project guard → `make deploy-staging`
     (preflight + uberjar + Jib + tagged 0%-traffic revision) →
     `make promote-staging` → same image to curtaincallcfp → promote →
     live smoke check of /cfp and /program

## Evidence

- First run: 283 tests, 2807 assertions, **1 error** — clj-kondo missing on
  the runner (view-architecture-test shells out to it). Fixed by adding
  `clj-kondo: latest` to setup-clojure.
- Run 31540901541 (commit c0e7080): **every step green**, both services
  promoted, live verify 200/200. Commit → production with zero laptop
  involvement.

## Gotchas recorded

- **Push triggers did not fire for the first three pushes** after adding the
  workflow (dispatch worked; `event=push` run count stayed 0). A minimal
  any-branch probe workflow pushed ~30 min later fired BOTH workflows —
  GitHub-side indexing lag on a freshly added workflow file in a brand-new
  repo (created 04:56Z same day). Nothing was wrong with the YAML. If this
  recurs: `gh workflow run build-and-deploy.yml --ref staging` while it warms.
- Tests need **no Postgres in CI**: the store defaults to :jsonl and every
  test runs in `with-temp-store`. Cloud SQL is reached only by the deployed
  service, from inside Cloud Run. Decision recorded: if store-pg integration
  tests ever land, use a `services: postgres:16` container — never the Cloud
  SQL Auth Proxy from CI (dev=prod; CI must not hold a prod write path).
- `[skip ci]` in a commit message is the way to push workflow housekeeping
  without triggering an 8-minute redeploy.

## Also shipped on this train (same afternoon, pre-CI)

unlisted events + editorial 404, speakers.json announced-roster merge,
sessions.json tba placeholders (14-speaker story), acceptance letter portal
link, Mik Kersten reattribution (deferring to the gene-repl lane's better
talk), replay-test flake filed as a P2 bead.
