# Cloud SQL Postgres — Easy Database Provisioning

**Goal:** Make creating a new Postgres database as easy as Heroku. One script, done.

## Prerequisites (One-Time Setup)

Before first use, you need a Cloud SQL Postgres instance with IAM auth enabled.

### 1. Check instance exists

```bash
gcloud sql instances describe postgres1 \
  --project=EXAMPLE-GCP-PROJECT-A \
  --format="yaml(databaseVersion,region,state,settings.databaseFlags,settings.tier)"
```

Current instance: **Postgres 17**, `us-central1`, custom-1-3840 tier.

### 2. Enable IAM database authentication

This is a one-time operation per instance. It enables service accounts to authenticate
without passwords. **Existing password-based connections are NOT affected.**

```bash
./gcp/enable-iam-auth.sh
```

**WARNING:** This restarts the instance (~1-2 min downtime). Coordinate with anyone
using the `reddit`, `paddle_partners`, or other databases on this instance.

### 3. Verify IAM auth is enabled

```bash
gcloud sql instances describe postgres1 \
  --project=EXAMPLE-GCP-PROJECT-A \
  --format="yaml(settings.databaseFlags)"
```

Expected output:
```yaml
settings:
  databaseFlags:
  - name: cloudsql.iam_authentication
    value: 'on'
```

## One-Shot Postgres Setup (30 seconds)

The complete flow for adding Postgres to a new Clojure project. Uses IAM auth --
no passwords to manage, store, rotate, or share.

### Step 1: Create database + IAM users

```bash
# Create database on existing shared instance
./gcp/create-postgres-db.sh esr_dashboard

# Create IAM DB user for local dev (your Google account)
gcloud sql users create "genek@itrevolution.net" \
  --instance=postgres1 \
  --type=CLOUD_IAM_USER \
  --project=EXAMPLE-GCP-PROJECT-A

# GRANT privileges (connect via proxy briefly as postgres admin)
/Users/genekim/bin/cloud_sql_proxy -instances=EXAMPLE-GCP-PROJECT-A:us-central1:postgres1=tcp:5433 &
PGPASSWORD='<admin-pw>' /opt/homebrew/opt/libpq/bin/psql \
  -h 127.0.0.1 -p 5433 -U postgres -d esr_dashboard -c "
GRANT CONNECT ON DATABASE esr_dashboard TO \"genek@itrevolution.net\";
GRANT USAGE, CREATE ON SCHEMA public TO \"genek@itrevolution.net\";
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO \"genek@itrevolution.net\";
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO \"genek@itrevolution.net\";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL PRIVILEGES ON TABLES TO \"genek@itrevolution.net\";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL PRIVILEGES ON SEQUENCES TO \"genek@itrevolution.net\";
"
pkill -f cloud_sql_proxy
```

### Step 2: deps.edn dependency

```clojure
com.google.cloud.sql/postgres-socket-factory {:mvn/version "1.15.2"}
```

### Step 3: Clojure connection code (IAM auth, no proxy needed)

```clojure
(import '[com.zaxxer.hikari HikariDataSource HikariConfig])

(defn make-datasource
  "Create HikariCP pool with IAM auth (no password needed).
   Local dev: uses ADC from `gcloud auth application-default login`
   Cloud Run: uses workload identity automatically"
  [{:keys [database iam-user instance]
    :or {instance "EXAMPLE-GCP-PROJECT-A:us-central1:postgres1"}}]
  (let [config (doto (HikariConfig.)
                 (.setJdbcUrl (str "jdbc:postgresql:///" database))
                 (.setUsername iam-user)
                 (.setPassword "ignored")  ; must be non-empty (driver quirk)
                 (.setMaximumPoolSize 5)
                 (.setMinimumIdle 1)
                 (.setConnectionTimeout 60000)
                 (.setIdleTimeout 300000)
                 (.setKeepaliveTime 60000)
                 (.setConnectionTestQuery "SELECT 1")
                 (.addDataSourceProperty "socketFactory"
                   "com.google.cloud.sql.postgres.SocketFactory")
                 (.addDataSourceProperty "cloudSqlInstance" instance)
                 (.addDataSourceProperty "enableIamAuth" "true")
                 (.addDataSourceProperty "cloudSqlRefreshStrategy" "lazy"))]
    (HikariDataSource. config)))

;; Local dev:
(def ds (make-datasource {:database "esr_dashboard"
                          :iam-user "genek@itrevolution.net"}))

;; Cloud Run (same code, different user):
(def ds (make-datasource {:database "esr_dashboard"
                          :iam-user "esr-dashboard@EXAMPLE-GCP-PROJECT-A.iam"}))
```

### Why IAM auth is better than password auth

1. No passwords to manage, store, rotate, or share
2. Same code path for local dev and Cloud Run (just different IAM user)
3. Short-lived tokens auto-rotated by socket factory
4. Full GCP audit trail of who accessed the database
5. New developer onboarding: one gcloud command, no secrets to share

### Why socket factory is better than Cloud SQL proxy

1. No separate process to start/manage/kill
2. Same connection method in dev and prod
3. One fewer thing to forget/break

---

## Creating a New Database (Service Account — Legacy Pattern)

### Quick start

```bash
# Create database + IAM user (read-write)
./gcp/create-postgres-db.sh my_database SA_EMAIL@PROJECT.iam.gserviceaccount.com

# Create database + IAM user (read-only)
./gcp/create-postgres-db.sh my_database SA_EMAIL@PROJECT.iam.gserviceaccount.com readonly
```

### What the script does

| Step | What | Command |
|------|-------|---------|
| 1 | Create database | `gcloud sql databases create` |
| 2 | Create IAM DB user | `gcloud sql users create --type=cloud_iam_service_account` |
| 3 | Grant IAM roles | `gcloud projects add-iam-policy-binding` (instanceUser + client) |
| 4 | Print SQL GRANTs | You run these manually as postgres admin |
| 5 | Print Clojure config | Copy into your project |

### Step-by-step walkthrough

#### Step 1: Run the provisioning script

```bash
./gcp/create-postgres-db.sh social_media 741acccada21@does2020.iam.gserviceaccount.com
```

This creates the database and IAM user, but you still need to GRANT privileges inside Postgres.

#### Step 2: Connect as admin and run the GRANTs

Option A — via Cloud SQL Auth Proxy (recommended for local):
```bash
# Terminal 1: start proxy
cloud-sql-proxy EXAMPLE-GCP-PROJECT-A:us-central1:postgres1 --port 5432

# Terminal 2: connect as admin
psql -h 127.0.0.1 -U genek -d social_media
```

Option B — via gcloud (if you have Cloud Shell or direct access):
```bash
gcloud sql connect postgres1 --database=social_media --user=postgres --project=EXAMPLE-GCP-PROJECT-A
```

Then run the SQL GRANTs that the script printed:
```sql
-- Read-write access
GRANT CONNECT ON DATABASE social_media TO "741acccada21@does2020.iam";
GRANT USAGE, CREATE ON SCHEMA public TO "741acccada21@does2020.iam";
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO "741acccada21@does2020.iam";
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO "741acccada21@does2020.iam";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL PRIVILEGES ON TABLES TO "741acccada21@does2020.iam";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL PRIVILEGES ON SEQUENCES TO "741acccada21@does2020.iam";
```

#### Step 3: Apply your schema

```bash
psql -h 127.0.0.1 -U genek -d social_media -f gcp/migrations/001_initial.sql
```

#### Step 4: Add deps to your project

```clojure
;; deps.edn
{:deps {org.postgresql/postgresql                              {:mvn/version "42.7.3"}
        com.google.cloud.sql/postgres-socket-factory           {:mvn/version "1.21.0"}
        com.zaxxer/HikariCP                                    {:mvn/version "5.1.0"}
        com.github.seancorfield/next.jdbc                      {:mvn/version "1.3.939"}
        com.github.seancorfield/honeysql                       {:mvn/version "2.6.1147"}}}
```

#### Step 5: Connect from Clojure (RECOMMENDED — IAM auth, no proxy)

```clojure
(ns myproject.db
  (:import [com.zaxxer.hikari HikariDataSource HikariConfig]))

(defn make-datasource
  "Create HikariCP pool with IAM auth (no password needed).
   Local dev: uses ADC from `gcloud auth application-default login`
   Cloud Run: uses workload identity automatically"
  [{:keys [database iam-user instance]
    :or {instance "EXAMPLE-GCP-PROJECT-A:us-central1:postgres1"}}]
  (let [config (doto (HikariConfig.)
                 (.setJdbcUrl (str "jdbc:postgresql:///" database))
                 (.setUsername iam-user)
                 (.setPassword "ignored")  ; must be non-empty (driver quirk)
                 (.setMaximumPoolSize 5)
                 (.setMinimumIdle 1)
                 (.setConnectionTimeout 60000)
                 (.setIdleTimeout 300000)
                 (.setKeepaliveTime 60000)
                 (.setConnectionTestQuery "SELECT 1")
                 (.addDataSourceProperty "socketFactory"
                   "com.google.cloud.sql.postgres.SocketFactory")
                 (.addDataSourceProperty "cloudSqlInstance" instance)
                 (.addDataSourceProperty "enableIamAuth" "true")
                 (.addDataSourceProperty "cloudSqlRefreshStrategy" "lazy"))]
    (HikariDataSource. config)))

;; Local dev (your Google account):
(def ds (make-datasource {:database "social_media"
                          :iam-user "genek@itrevolution.net"}))

;; Cloud Run (service account):
(def ds (make-datasource {:database "social_media"
                          :iam-user "my-sa@my-project.iam"}))
```

#### Legacy: Local dev via Cloud SQL Auth Proxy + password

```clojure
;; LEGACY — prefer IAM auth above (no proxy needed).
;; For local dev (Cloud SQL Auth Proxy running on port 5432):
(defn make-local-datasource [{:keys [database user password]
                               :or {user "genek" password "yourpass"}}]
  (let [config (doto (HikariConfig.)
                 (.setJdbcUrl (str "jdbc:postgresql://127.0.0.1:5432/" database))
                 (.setUsername user)
                 (.setPassword password)
                 (.setMaximumPoolSize 5))]
    (HikariDataSource. config)))
```

## How IAM Database Auth Works

Two separate auth layers, both using the same service account identity:

```
┌─────────────────────────────────────────────────┐
│ Layer 1: Network Connection (IAM)               │
│                                                 │
│ "Can this service account connect to the        │
│  Cloud SQL instance at all?"                    │
│                                                 │
│ Controlled by: roles/cloudsql.client            │
│                roles/cloudsql.instanceUser       │
│ Mechanism:     Socket Factory + ADC token       │
│ No password needed.                             │
└──────────────────────┬──────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────┐
│ Layer 2: Database Privileges (Postgres GRANT)    │
│                                                 │
│ "Which databases, schemas, and tables can       │
│  this user access?"                             │
│                                                 │
│ Controlled by: SQL GRANT/REVOKE statements      │
│ Per-database: GRANT CONNECT ON DATABASE x       │
│ Per-table:    GRANT SELECT ON TABLE y           │
│                                                 │
│ IAM users start with ZERO privileges.           │
│ You must explicitly GRANT everything.           │
└─────────────────────────────────────────────────┘
```

**Key insight:** An IAM user can connect to the instance but can't see ANY data until you GRANT it. This is how you isolate databases — the `social_media` user can't touch the `reddit` database.

## Granting Access to Additional Service Accounts

```bash
# Give another service account read-only access to an existing database
./gcp/create-postgres-db.sh social_media other-sa@other-project.iam.gserviceaccount.com readonly

# Then run the printed SQL GRANTs as admin
```

## Existing Databases on This Instance

| Database | Used by | Auth |
|----------|---------|------|
| `reddit` | reddit-scraper-fulcro/server2 | Password (genek) |
| `paddle_partners` | paddle-partners project | Password |
| `d1p9p634jn2hg9` | Unknown (investigate) | Unknown |
| `postgres` | Default admin database | — |

## Files

| File | Purpose |
|------|---------|
| `gcp/README.md` | This file |
| `gcp/enable-iam-auth.sh` | One-time: enable IAM auth on instance |
| `gcp/create-postgres-db.sh` | Create database + IAM user + print GRANTs |
| `gcp/migrations/` | SQL migration files per database |

## Troubleshooting

### "permission denied for database X"
The IAM user exists but doesn't have GRANTs. Connect as admin and run the GRANT statements.

### "Cloud SQL Admin API has not been enabled"
```bash
gcloud services enable sqladmin.googleapis.com --project=EXAMPLE-GCP-PROJECT-A
```

### "IAM authentication is not enabled"
Run `./gcp/enable-iam-auth.sh` first (requires instance restart).

### "password authentication failed" (from Clojure)
Make sure `enableIamAuth` is set to `"true"` in HikariConfig properties. Without it, the connector tries password auth.

### First connection is slow (~200ms)
Normal — IAM token fetch on first connect. Subsequent connections use cached tokens.

### Local dev with IAM (RECOMMENDED)
IAM auth works locally via `gcloud auth application-default login` and the socket factory. No proxy needed. Same `make-datasource` code as Cloud Run, just use your Google account email as `iam-user`.

### Legacy: Local dev without IAM
Use Cloud SQL Auth Proxy + password auth locally. This still works but requires managing a separate proxy process and passwords.
