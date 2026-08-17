# Cloud SQL Auth Proxy — Local Development

The Cloud SQL Auth Proxy creates a secure tunnel from your local machine to Cloud SQL,
so you can connect with `psql` or your app as if the database were on localhost.

## Binary Location

```
/Users/genekim/bin/cloud_sql_proxy    # v1.19.1 (legacy syntax)
```

To install v2 (optional):
```bash
brew install cloud-sql-proxy
```

## Quick Start

```bash
# Start proxy (v1 syntax — note the -instances= flag and =tcp:PORT)
/Users/genekim/bin/cloud_sql_proxy \
  -instances=EXAMPLE-GCP-PROJECT-A:us-central1:postgres1=tcp:5433 &

# Connect with psql
PGPASSWORD='yourpass' /opt/homebrew/opt/libpq/bin/psql \
  -h 127.0.0.1 -p 5433 -U genek -d social_media
```

## v1 vs v2 Syntax

The binary at `~/bin/cloud_sql_proxy` is **v1**. Syntax differs from v2:

| | v1 (what we have) | v2 (if installed via brew) |
|---|---|---|
| **Instance flag** | `-instances=PROJECT:REGION:INSTANCE=tcp:PORT` | `PROJECT:REGION:INSTANCE --port PORT` |
| **Example** | `-instances=EXAMPLE-GCP-PROJECT-A:us-central1:postgres1=tcp:5433` | `EXAMPLE-GCP-PROJECT-A:us-central1:postgres1 --port=5433` |
| **Multiple** | `-instances=INST1=tcp:5433,INST2=tcp:5434` | `INST1 INST2 --port 5433` |

## Authentication

The proxy uses your gcloud credentials (Application Default Credentials):

```bash
# Make sure you're logged in
gcloud auth application-default login

# Or use a service account key file
/Users/genekim/bin/cloud_sql_proxy \
  -credential_file=./secrets/does2020-741acccada21.json \
  -instances=EXAMPLE-GCP-PROJECT-A:us-central1:postgres1=tcp:5433
```

## Our Databases

| Database | Port | Connect as |
|----------|------|-----------|
| `social_media` | 5433 | `genek` (dev) or IAM SA (Cloud Run) |
| `reddit` | 5433 | `genek` |
| `paddle_partners` | 5433 | `paddle_partners` |

All on the same instance, so one proxy serves all databases — just change `-d` in psql.

## psql Location

psql is installed but not in PATH:
```
/opt/homebrew/opt/libpq/bin/psql
/opt/homebrew/opt/postgresql@15/bin/psql
```

Add to PATH (optional):
```bash
export PATH="/opt/homebrew/opt/libpq/bin:$PATH"
```

## Common Operations

### List tables
```bash
PGPASSWORD='yourpass' /opt/homebrew/opt/libpq/bin/psql \
  -h 127.0.0.1 -p 5433 -U genek -d social_media \
  -c "\dt"
```

### Run a migration
```bash
PGPASSWORD='yourpass' /opt/homebrew/opt/libpq/bin/psql \
  -h 127.0.0.1 -p 5433 -U genek -d social_media \
  -f gcp/migrations/001_initial.sql
```

### Admin operations (as postgres superuser)
```bash
PGPASSWORD='yourpass' /opt/homebrew/opt/libpq/bin/psql \
  -h 127.0.0.1 -p 5433 -U postgres -d social_media
```

### Kill the proxy
```bash
pkill -f cloud_sql_proxy
```

## Troubleshooting

### "Connection refused"
Proxy isn't running or wrong port. Check with:
```bash
ps aux | grep cloud_sql_proxy
```

### "password authentication failed"
Wrong password. Check `secrets/postgres.edn`.

### "could not connect to server: No such file or directory"
Using Unix socket syntax instead of TCP. Make sure you're connecting to `-h 127.0.0.1 -p PORT`, not just `-h /cloudsql/...`.

### Proxy hangs on start
Usually a credentials issue. Try:
```bash
gcloud auth application-default login
```
