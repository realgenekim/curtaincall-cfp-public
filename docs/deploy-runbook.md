# Deploy runbook — getting a URL the AIE team can open

> **CURRENT RELEASE RULE (Gene, 2026-08-16):** GitHub Actions deploys tagged
> staging revisions only. It never changes production traffic. The old
> `make deploy-all` and `make cloudrundeploy` targets are retired and fail
> closed. After Gene inspects staging, release the exact approved artifact with
> `make promote-staging-to-production EXPECTED_SHA=<sha> PROMOTE=YES`.

*Written 2026-08-09 overnight. Prerequisites below were VERIFIED that night; the
three decisions are Gene's and are the only thing blocking execution. bd
`sessionize-sched-killer-ghz`.*

The brief is explicit: the winning submission needs a **"Deployed site we can test
out with the walkthrough shown."** Everything else we have is worth nothing to a
judge who cannot open it.

## Verified prerequisites (checked 2026-08-09, all green)

| Check | Result |
|---|---|
| `gcloud projects describe EXAMPLE-GCP-PROJECT-B` | **ACTIVE** |
| `run.googleapis.com` enabled | ✅ |
| `containerregistry.googleapis.com` enabled | ✅ |
| `artifactregistry.googleapis.com` enabled | ✅ |
| Cloud Run already serving in `us-west1` | ✅ (6 existing services) |
| `:jib-deploy` alias in `deps.edn` | ✅ (genek/thin-jar-build) |
| gcloud authenticated | ✅ `genek@itrevolution.net` |

Makefile already carries the right config:

```
GCP_PROJECT  := EXAMPLE-GCP-PROJECT-B
GCP_REGION   := us-west1
SERVICE_NAME := cfp-scheduler-killer
```

and `cloudrundeploy` already passes **`--max-instances 1`**, which is exactly what
the JSONL store needs — one writer, one log. That decision is already made
correctly in the Makefile; nobody has to think about it.

Expected URL shape, based on the other services in this project:
`https://cfp-scheduler-killer-rhpg5b3znq-uw.a.run.app`

## The three decisions only Gene can make

### 1. Which GCP project?

The Makefile says `EXAMPLE-GCP-PROJECT-B` (verified active, APIs on, other services
already running there). `gcloud`'s *current* default is `does2020`, which is why
this needs a conscious answer rather than a shrug.

**Recommendation: keep `EXAMPLE-GCP-PROJECT-B`.** It is already configured and proven;
changing it means enabling APIs and re-testing registry pushes on the deadline.

### 2. SMTP — real email, or stay honest?

Today, with no SMTP configured, the app says so plainly and **records every letter
on the Comms page so a human can send it by hand**. The inform flow still works;
the speaker's status still updates immediately.

- **With SMTP**: magic-link sign-in works for anyone, and decision letters actually
  send. A judge who types their own email gets a link.
- **Without SMTP**: a judge cannot sign in unless we hand them a link or seed their
  email onto a committee.

**Recommendation: configure SMTP**, or accept that the deployed demo needs a
pre-seeded organizer account plus a visible "sign in as the demo organizer" path.
The second is less impressive but has zero delivery risk on a deadline. Do **not**
ship a deployed site where a judge hits a login wall they cannot pass — that is the
account-wall failure we are explicitly criticizing in the incumbent.

### 3. Durability of the event log

The store is one append-only file on an ephemeral Cloud Run disk. `--max-instances
1` means no split-brain, but a revision restart still starts from whatever is baked
into the image.

- **Option A (recommended for the contest): seed at boot.** The image ships the
  demo world; every cold start gives a judge a full, realistic screen — which is
  non-negotiable #10. A judge's own edits vanish on restart, which for a
  demo is acceptable and arguably desirable.
- **Option B: GCS snapshot.** The `:gcs-snapshot` sink already exists (debounced,
  injectable copy-fn) and copies the log off the box. Restore on boot makes a
  judge's session survive. More moving parts on a deadline.

**Recommendation: A for the submission, B as the follow-up.**

## Execution (once decided — about 15 minutes)

```bash
# 0. from the repo root, with a clean test run behind you
make runtests-once            # expect 171+ tests, 0 failures
python3 bin/e2e_drive.py      # expect 107/107 against localhost

# 1. verify the PRODUCTION jar boots locally before pushing a container.
#    This is the step most likely to surprise: the thin jar needs its deps on the
#    classpath, and prod mode is NOT the dev server (no ENV=dev, no auto-reload).
make build
java -cp "target/cfp-scheduler-killer.jar:target/lib/*" cfp_scheduler_killer.core
#    -> hit http://localhost:20500, sign in, create an event. Then stop it.

# 2. push the reviewed commit to origin/staging. GitHub Actions tests, builds,
#    and deploys tagged 0%-production-traffic revisions on both services.
git push origin staging

# 3. verify the workflow, then inspect the staging URL by hand.
gh run list --workflow build-and-deploy.yml
make open-staging

# 4. only after Gene approves the visible SHA, promote the exact staged release.
make promote-staging-to-production EXPECTED_SHA=<approved-sha> PROMOTE=YES

# 5. inspect both production traffic assignments.
make traffic
```

## Post-deploy checklist — do NOT skip

Run the end-to-end driver **against the deployed URL**, not just locally. It takes
one flag and it is the difference between "we deployed" and "it works":

```bash
python3 bin/e2e_drive.py --base https://cfp-scheduler-killer-....run.app
```

Then, by hand:

- [ ] The public CFP page loads **signed out, in a private window** — this is the
      first thing a judge will do.
- [ ] Submit a talk as a stranger. Confirm the confirmation page.
- [ ] Sign in as the organizer; the submission is on the board.
- [ ] **Seeded demo data is present** — a judge must never see an empty screen
      (non-negotiable #10).
- [ ] Exports resolve over HTTPS: `sessions.json`, `speakers.json`, `calendar.ics`,
      `llms.txt`.
- [ ] Page loads feel instant. "We do not want slow SaaS pls" is a stated criterion,
      and locally we serve in ~130ms.
- [ ] **Security**: with a speaker account, confirm the organizer mutations still
      return 403 in production (this is bd `-53i`; the gate is default-deny, but
      verify it in the deployed environment rather than assuming).

## Known gaps to weigh before publishing

- **Authorization is not scoped per event** — a committee member of event A can act
  on event B. With one demo event this is invisible; it is still true. If the
  deployed site invites judges to create their own events, fix this first.
- Secrets must come from Secret Manager, never `--set-env-vars` (house rule). If
  SMTP is configured, its credentials go through `gcp-secrets`.
