# Cloud SQL Postgres — Quick Start

## Create a database (Heroku-style, one command)

```bash
./gcp/create-postgres-db.sh my_database
```

That's it. The script:
1. Creates the database on the shared Cloud SQL Postgres instance
2. Creates a dedicated service account
3. Sets up IAM database authentication (no passwords in Cloud Run)
4. Grants all privileges via SQL
5. Writes `secrets/<db_name>-postgres.edn` with connection details
6. Prints Clojure connection snippets

## First-time setup

On a brand new GCP project/instance, you need two one-time steps:

### 1. Enable IAM auth on the instance

```bash
./gcp/enable-iam-auth.sh
```

This restarts the instance (~1-2 min). Only needed once per instance.

### 2. Create the shared admin secrets file

The provisioning script reads admin credentials from `~/src.local/secrets/cloud-sql-admin.edn`.
On first run, it generates passwords via `~/bin/genpassword` and prompts you to set them in Cloud SQL.

## Connecting from Clojure

### deps.edn

```clojure
{:deps {org.postgresql/postgresql                    {:mvn/version "42.7.3"}
        com.google.cloud.sql/postgres-socket-factory {:mvn/version "1.21.0"}
        com.zaxxer/HikariCP                          {:mvn/version "5.1.0"}
        com.github.seancorfield/next.jdbc            {:mvn/version "1.3.939"}
        com.github.seancorfield/honeysql             {:mvn/version "2.6.1147"}}}
```

### Local + Cloud Run (Socket Factory — works everywhere, no proxy needed)

The Cloud SQL Socket Factory handles the secure tunnel directly from your JVM.
No proxy binary needed. Same code works locally and on Cloud Run.

```clojure
(ns myproject.db
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [com.zaxxer.hikari HikariConfig HikariDataSource]))

(defn make-datasource
  "Create HikariCP pool via Cloud SQL Socket Factory.
   Works locally (uses gcloud ADC) and on Cloud Run (uses metadata server)."
  [{:keys [dbname user password instance]
    :or {instance "EXAMPLE-GCP-PROJECT-A:us-central1:postgres1"}}]
  (let [props (doto (java.util.Properties.)
                (.putAll
                 {"user"                    user
                  "password"                password
                  "socketFactory"           "com.google.cloud.sql.postgres.SocketFactory"
                  "cloudSqlInstance"        instance
                  "cloudSqlRefreshStrategy" "lazy"}))
        hconfig (doto (HikariConfig.)
                  (.setJdbcUrl (str "jdbc:postgresql:///" dbname))
                  (.setDataSourceProperties props)
                  (.setMaximumPoolSize 5)
                  (.setMinimumIdle 1)
                  (.setConnectionTimeout 30000))]
    (HikariDataSource. hconfig)))

;; Local dev (password auth via Socket Factory):
(def ds (make-datasource
          {:dbname "my_database"
           :user "genek"
           :password "yourpass"}))

;; Cloud Run (IAM auth — no password):
(def ds (make-datasource
          {:dbname "my_database"
           :user "my-sa@my-project.iam"
           :password "ignored"}))
;; Also add "enableIamAuth" "true" to props for IAM auth

;; Test it:
(jdbc/execute! ds ["SELECT current_database() AS db"])
;; => [{:db "my_database"}]
```

### psql access (via Cloud SQL Auth Proxy)

For admin tasks (migrations, GRANTs), use the proxy + psql:

```bash
~/bin/cloud_sql_proxy -instances=EXAMPLE-GCP-PROJECT-A:us-central1:postgres1=tcp:5433 &
PGPASSWORD='yourpass' /opt/homebrew/opt/libpq/bin/psql -h 127.0.0.1 -p 5433 -U genek -d my_database
```

See `gcp/cloud-sql-proxy.md` for details.

## How IAM auth works

Two layers, both using the same service account:

```
Layer 1: Network (IAM)          "Can this SA connect to the instance?"
                                 → roles/cloudsql.client + roles/cloudsql.instanceUser

Layer 2: Database (PG GRANT)    "Which databases/tables can this user access?"
                                 → GRANT CONNECT ON DATABASE x TO "sa@project.iam"
```

IAM users start with ZERO privileges. The provisioning script handles all GRANTs automatically.

## Files

| File | Purpose |
|------|---------|
| `gcp/create-postgres-db.sh` | One-command database provisioning |
| `gcp/enable-iam-auth.sh` | One-time: enable IAM auth on instance |
| `gcp/cloud-sql-proxy.md` | Proxy usage guide for local dev |
| `gcp/README.md` | Full reference docs |
| `~/src.local/secrets/cloud-sql-admin.edn` | Shared admin credentials (not in git) |
| `secrets/<db>-postgres.edn` | Per-database connection details (not in git) |

## Read-only access for another service

```bash
./gcp/create-postgres-db.sh my_database --readonly
```

This creates a separate SA with SELECT-only access to the same database.

## Schema migrations

```bash
# Start proxy, then apply
~/bin/cloud_sql_proxy -instances=EXAMPLE-GCP-PROJECT-A:us-central1:postgres1=tcp:5433 &
PGPASSWORD='yourpass' /opt/homebrew/opt/libpq/bin/psql \
  -h 127.0.0.1 -p 5433 -U genek -d my_database \
  -f gcp/migrations/001_initial.sql
```
