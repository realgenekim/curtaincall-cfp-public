# GitHub Actions Setup Guide

## Overview

This project uses GitHub Actions for CI/CD, replacing the previous CircleCI configuration. The workflow builds, tests, and deploys the `pubsub` component to Google Cloud Run.

## Workflow File

Location: `.github/workflows/build-and-deploy.yml`

## Jobs

### 1. Test Job

Runs on all pushes and pull requests to `main` branch.

**Steps:**
1. Checkout code
2. Clone `google-cloud` dependency repository
3. Setup Java 25 (Corretto distribution)
4. Setup Clojure CLI tools
5. Cache Maven/Clojure dependencies
6. Download project dependencies
7. Authenticate to GCP (with `id: auth` for credentials file path)
8. Create symlink for Makefile compatibility (`$HOME/gcloud-service-key.json` → credentials file)
9. Run tests via `make tests-on-circleci`
10. Build uberjar via `make uberjar-on-circleci`
11. Upload JAR as artifact (7 day retention)

### 2. Deploy Job

Only runs on `main` branch pushes (after test job succeeds).

**Steps:**
1. Checkout code
2. Clone `google-cloud` dependency
3. Setup Java 25 and Clojure
4. Restore cached dependencies
5. Authenticate to GCP (with `id: auth` for credentials file path)
6. Create symlink for Makefile compatibility (`$HOME/gcloud-service-key.json` → credentials file)
7. Configure Docker for Artifact Registry
8. Deploy to Cloud Run using Jib (`make jib-deploy`)
9. Verify deployment status

**Important:**
- The authentication step must have `id: auth` so that `steps.auth.outputs.credentials_file_path` can be referenced in subsequent steps.
- A symlink is created from `$HOME/gcloud-service-key.json` to the credentials file because the Makefile targets (`tests-on-circleci`, `uberjar-on-circleci`, `jib-deploy`) hardcode this path for CircleCI compatibility.

## GCP Authentication

Uses **Workload Identity Federation** (recommended secure method, no service account keys).

## Cloud SQL Connection

The tests connect to Cloud SQL using the **Google Cloud SQL connector library** (not Cloud SQL Proxy). This approach:

- Uses the `com.google.cloud.sql.mysql.SocketFactory` to establish connections
- Requires the `roles/cloudsql.client` IAM permission
- Creates ephemeral SSL certificates automatically
- Works seamlessly in both local development and CI/CD environments
- Falls back to regular JDBC connections if `cloud-sql-instance` is not configured

**Key advantages over Cloud SQL Proxy:**
- No separate proxy process needed
- Direct connection from application code
- Automatic credential handling via Application Default Credentials
- Better integration with Workload Identity Federation

### Required GCP Setup

1. **Enable Required APIs:**
```bash
# Enable IAM Service Account Credentials API (required for Workload Identity Federation)
gcloud services enable iamcredentials.googleapis.com --project=EXAMPLE-GCP-PROJECT-B
```

2. **Create Workload Identity Pool:**
```bash
gcloud iam workload-identity-pools create "github-actions" \
  --project="EXAMPLE-GCP-PROJECT-B" \
  --location="global" \
  --display-name="GitHub Actions Pool"
```

3. **Create Workload Identity Provider:**
```bash
gcloud iam workload-identity-pools providers create-oidc "github" \
  --project="EXAMPLE-GCP-PROJECT-B" \
  --location="global" \
  --workload-identity-pool="github-actions" \
  --display-name="GitHub Provider" \
  --attribute-mapping="google.subject=assertion.sub,attribute.actor=assertion.actor,attribute.repository=assertion.repository" \
  --attribute-condition="assertion.repository_owner == 'realgenekim'" \
  --issuer-uri="https://token.actions.githubusercontent.com"
```

4. **Create Service Account:**
```bash
gcloud iam service-accounts create github-actions \
  --project=EXAMPLE-GCP-PROJECT-B \
  --display-name="GitHub Actions Service Account"
```

5. **Grant Required Permissions:**
```bash
# Cloud Run Admin (to deploy jobs)
gcloud projects add-iam-policy-binding EXAMPLE-GCP-PROJECT-B \
  --member="serviceAccount:github-actions@EXAMPLE-GCP-PROJECT-B.iam.gserviceaccount.com" \
  --role="roles/run.admin"

# Storage Admin (for Artifact Registry)
gcloud projects add-iam-policy-binding EXAMPLE-GCP-PROJECT-B \
  --member="serviceAccount:github-actions@EXAMPLE-GCP-PROJECT-B.iam.gserviceaccount.com" \
  --role="roles/storage.admin"

# Artifact Registry Writer
gcloud projects add-iam-policy-binding EXAMPLE-GCP-PROJECT-B \
  --member="serviceAccount:github-actions@EXAMPLE-GCP-PROJECT-B.iam.gserviceaccount.com" \
  --role="roles/artifactregistry.writer"

# Secret Manager Accessor (for app to access secrets at runtime)
gcloud projects add-iam-policy-binding EXAMPLE-GCP-PROJECT-B \
  --member="serviceAccount:github-actions@EXAMPLE-GCP-PROJECT-B.iam.gserviceaccount.com" \
  --role="roles/secretmanager.secretAccessor"

# Service Account User (to deploy as Cloud Run service account)
gcloud projects add-iam-policy-binding EXAMPLE-GCP-PROJECT-B \
  --member="serviceAccount:github-actions@EXAMPLE-GCP-PROJECT-B.iam.gserviceaccount.com" \
  --role="roles/iam.serviceAccountUser"

# Cloud SQL Client (to connect to Cloud SQL during tests)
gcloud projects add-iam-policy-binding EXAMPLE-GCP-PROJECT-B \
  --member="serviceAccount:github-actions@EXAMPLE-GCP-PROJECT-B.iam.gserviceaccount.com" \
  --role="roles/cloudsql.client"
```

6. **Allow GitHub to Impersonate Service Account:**
```bash
gcloud iam service-accounts add-iam-policy-binding \
  github-actions@EXAMPLE-GCP-PROJECT-B.iam.gserviceaccount.com \
  --project=EXAMPLE-GCP-PROJECT-B \
  --role="roles/iam.workloadIdentityUser" \
  --member="principalSet://iam.googleapis.com/projects/PROJECT_NUMBER/locations/global/workloadIdentityPools/github-actions/attribute.repository/realgenekim/book-pubsub-components"
```

**Note:** Replace `PROJECT_NUMBER` with your actual GCP project number.

7. **Get Project Number:**
```bash
gcloud projects describe EXAMPLE-GCP-PROJECT-B --format="value(projectNumber)"
```

## Required GitHub Secrets

Add these secrets in GitHub repository settings (Settings → Secrets and variables → Actions):

1. **GCP_PROJECT_NUMBER**
   - Description: GCP project number (not project ID)
   - Value: Get from `gcloud projects describe EXAMPLE-GCP-PROJECT-B --format="value(projectNumber)"`
   - Example: `123456789012`

## Environment Variables

Defined in workflow file:

```yaml
PROJECT_ID: 'EXAMPLE-GCP-PROJECT-B'       # GCP project ID
REGION: 'us-west1'                   # Cloud Run region (must match Cloud SQL)
SERVICE: 'booktracker-scanner-job'   # Cloud Run job name
REPOSITORY: 'booktracker-scanner'    # Artifact Registry repository
```

## Dependency Caching

Caches the following paths to speed up builds:

- `~/.m2/repository` - Maven dependencies
- `~/.gitlibs` - Git-based Clojure dependencies
- `~/.deps.clj` - Clojure CLI cache
- `bookserver/.cpcache` - Bookserver compile cache
- `pubsub/.cpcache` - Pubsub compile cache

**Cache Key:** Based on checksums of all `deps.edn` and `project.clj` files

## Differences from CircleCI

### Advantages of GitHub Actions:

1. **Better Integration:**
   - Native GitHub integration
   - Automatic PR status checks
   - Built-in artifact storage

2. **Improved Security:**
   - Workload Identity Federation (no service account keys)
   - Secrets automatically injected per job
   - Fine-grained permissions per workflow

3. **Better Caching:**
   - GitHub Actions cache is faster
   - Automatic cache eviction policies
   - Better cache hit rates

4. **Free for Public Repos:**
   - Unlimited minutes for public repositories
   - 2,000 free minutes/month for private repos

5. **Better Conditionals:**
   - Simpler branch/PR filtering
   - Native `if:` conditions on jobs/steps

### Migration Changes:

1. **Removed:**
   - Custom Docker image (use setup actions instead)
   - CircleCI-specific orbs
   - Workspace persistence (not needed with separate jobs)

2. **Added:**
   - Workload Identity Federation authentication
   - Artifact uploads for built JARs
   - Deployment verification step
   - Separate test and deploy jobs

3. **Kept:**
   - Same build process (`make` commands)
   - Same deployment tool (Jib)
   - Same test commands
   - Same GCP services

## Testing the Workflow

### Test on Pull Request:

1. Create a new branch
2. Make a change
3. Open a Pull Request to `main`
4. Workflow runs tests only (no deployment)

### Test Deployment:

1. Merge to `main` branch
2. Workflow runs tests AND deployment
3. Check Cloud Run job status in GCP Console

## Monitoring

### View Workflow Runs:
- GitHub repo → Actions tab
- See all workflow runs, logs, and artifacts

### View Deployment:
```bash
# Describe the Cloud Run job
gcloud run jobs describe booktracker-scanner-job --region us-west1

# View recent executions
gcloud run jobs executions list --job booktracker-scanner-job --region us-west1
```

## Troubleshooting

### Authentication Failures:

Check that:
1. `GCP_PROJECT_NUMBER` secret is set correctly
2. Workload Identity Pool and Provider are created
3. Service account has required permissions
4. GitHub repo is allowed to impersonate service account

### Build Failures:

Check:
1. Dependencies are cached correctly
2. `google-cloud` repo is accessible
3. Test credentials are valid

### Deployment Failures:

Check:
1. Service account has Cloud Run Admin role
2. Artifact Registry repository exists
3. Docker authentication is configured
4. Jib can push to Artifact Registry

## Related Documentation

- [CircleCI Configuration](./circleci-config.md) - Previous CI/CD setup
- [Google Cloud Workload Identity Federation](https://cloud.google.com/iam/docs/workload-identity-federation)
- [GitHub Actions](https://docs.github.com/en/actions)

## Related Issues

- **book-pubsub-components-6** - Migrate CI/CD from CircleCI to GitHub Actions
