# 2026-08-09 — First deploy: swyx-cfp-saas-killer on Cloud Run

**What was asked:** Gene: create the GCP project and deploy. Name deliberated
live (10 candidates critiqued — literal, Gene's name, contest-joke; Gene
ratified the contest-joke) and settled as **`swyx-cfp-saas-killer`** — his
"sass" typo corrected to "saas" with his knowledge, standing offer to recreate
if the pun was intended. Two-layer naming decision: project ID = contest fossil
(near-invisible, immutable), Cloud Run service name = also `swyx-cfp-saas-killer`
for the submission, freely renameable to a product name (Callboard was the
runner-up) post-contest with zero migration.

**What was done, in order (all verified at each step):**

1. `create-project.sh swyx-cfp-saas-killer` — project + billing + APIs. First
   attempt failed on expired gcloud auth (Gene re-authed interactively);
   second run clean. Artifact Registry creation then hit IAM propagation lag
   (`PERMISSION_DENIED` on a fresh project as owner) — a retry-until loop
   cleared it in ~1 min. Repo: `cloud-run-source-deploy` (us-west1).
2. Repointed `Makefile` + `jib.clj` from the template's `EXAMPLE-GCP-PROJECT-B`
   placeholder. **Found and fixed a latent deploy-breaker**: Makefile's
   `cloudrundeploy` deployed `us.gcr.io/...` (legacy GCR) while Jib pushes to
   `us-west1-docker.pkg.dev/...` — the first deploy would have referenced an
   image that doesn't exist. Both now share one `IMAGE_URL` var; every gcloud
   call carries `--project`.
3. `make uberjar-smoke` — prod artifact booted locally: /login 200, /events
   302 (auth on), store loaded. Per the runbook rule: boot the artifact before
   pushing a container.
4. `make jib-deploy` — 44s first push (deps layer + app layer). No Docker
   daemon anywhere; Jib pushes direct (Gene confirmed the no-Docker
   requirement before go).
5. `make cloudrundeploy` — 17s. Revision `swyx-cfp-saas-killer-00001-972`,
   max-instances 1 (single-writer JSONL store), 2G, us-west1.

**Live URL: https://swyx-cfp-saas-killer-109637679549.us-west1.run.app**
Verified from outside: `/login` 200 · `/events` 302 (auth on) ·
`/cfp/enterprise-ai-summit-charlotte` **404 — expected**: the container ships
no store data; the deployed instance is an empty first-run world.

**What this deploy is and is not:** it proves the infrastructure path
end-to-end (the largest un-started hard requirement this morning). It is NOT
judge-ready. The two blockers, both filed:

- **bd -o42 (P0)**: nobody can sign in — magic link renders only under
  `ENV=dev`, no signup, fixture personas not on any roster. Without this the
  eval kit reports "insufficient coverage — score withheld."
- **bd -iy9 (P0, new)**: store persistence + demo seed — instance recycle
  wipes all state (and invalidates the eval kit's saved auth cookies mid-run);
  the Charlotte demo world needs to exist at the public URL. Recommended
  shape: GCS snapshot load-on-boot/save-on-write + seed.

Context for why speed mattered today: swyx is running his freshly-released
eval kit against any MVP URL DM'd to him (three competitors already had), so
the deployed URL is the gateway to free calibration feedback before Wednesday.

**Bookkeeping:** bd -ghz closed (deployed), -iy9 opened. Earlier same session:
per-event authz + API keys landed and verified (see
`2026-08-09-per-event-authorization.md`), dev server on :20500 restarted with
the new gate, `docs/auth.md` written.
