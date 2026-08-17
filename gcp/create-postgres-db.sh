#!/bin/bash
# ============================================================================
# create-postgres-db.sh — Heroku-like Postgres database provisioning
# ============================================================================
#
# Creates a new database on Cloud SQL Postgres with IAM auth, fully automated.
# One command, everything done: database, service account, IAM roles, SQL GRANTs,
# secrets file written, ready to connect from Clojure.
#
# Prerequisites:
#   - cloudsql.iam_authentication flag enabled on instance (one-time setup)
#   - gcloud CLI authenticated
#   - cloud_sql_proxy v1 at ~/bin/cloud_sql_proxy
#   - psql at /opt/homebrew/opt/libpq/bin/psql
#
# Usage:
#   ./gcp/create-postgres-db.sh <db_name>
#   ./gcp/create-postgres-db.sh <db_name> --readonly   # read-only SA access
#
# Examples:
#   ./gcp/create-postgres-db.sh social_media
#   ./gcp/create-postgres-db.sh funnel_analytics
#   ./gcp/create-postgres-db.sh social_media --readonly  # add read-only SA
#
# What it does:
#   1. Creates the database (idempotent)
#   2. Creates a service account named <db_name> (idempotent)
#   3. Creates IAM database user (idempotent)
#   4. Grants IAM roles (cloudsql.instanceUser + cloudsql.client)
#   5. Starts proxy, runs SQL GRANTs as postgres superuser, stops proxy
#   6. Writes secrets/<db_name>-postgres.edn
#   7. Prints Clojure connection snippet
# ============================================================================

set -euo pipefail

# ── Config ───────────────────────────────────────────────────────────────────
INSTANCE=postgres1
PROJECT=EXAMPLE-GCP-PROJECT-A
REGION=us-central1  # Postgres instance region (fixed — don't change)
PROXY_PORT=5433
PROXY_BIN="$HOME/bin/cloud_sql_proxy"
PSQL_BIN="/opt/homebrew/opt/libpq/bin/psql"
GENPASSWORD="$HOME/bin/genpassword"
ADMIN_USER=postgres
APP_USER=genek

# Shared secrets directory (all projects read from here)
SHARED_SECRETS="$HOME/src.local/secrets"
ADMIN_SECRETS="$SHARED_SECRETS/cloud-sql-admin.edn"

# ── Parse args ───────────────────────────────────────────────────────────────
if [ $# -lt 1 ]; then
    echo "Usage: $0 <db_name> [--readonly]"
    echo ""
    echo "Creates a new Postgres database with IAM auth, fully automated."
    echo "Service account is auto-created as <db_name>@$PROJECT.iam.gserviceaccount.com"
    exit 1
fi

DB_NAME=$1
ACCESS="readwrite"
if [ "${2:-}" = "--readonly" ]; then
    ACCESS="readonly"
fi

# Derive SA name from database name (replace underscores with hyphens for SA naming)
SA_NAME="${DB_NAME//_/-}"
SA_EMAIL="${SA_NAME}@${PROJECT}.iam.gserviceaccount.com"
SA_USER="${SA_NAME}@${PROJECT}.iam"
SECRETS_FILE="secrets/${DB_NAME}-postgres.edn"

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║  Postgres Database Provisioning                             ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "  Instance:  $PROJECT:$REGION:$INSTANCE"
echo "  Database:  $DB_NAME"
echo "  SA:        $SA_EMAIL"
echo "  IAM User:  $SA_USER"
echo "  Access:    $ACCESS"
echo "  Secrets:   $SECRETS_FILE"
echo ""

# ── Preflight checks ────────────────────────────────────────────────────────
echo "--- Preflight checks ---"

if ! command -v gcloud &>/dev/null; then
    echo "ERROR: gcloud not found. Install Google Cloud SDK." >&2
    exit 1
fi

if [ ! -f "$PROXY_BIN" ]; then
    echo "ERROR: cloud_sql_proxy not found at $PROXY_BIN" >&2
    exit 1
fi

if [ ! -f "$PSQL_BIN" ]; then
    echo "ERROR: psql not found at $PSQL_BIN" >&2
    echo "Install: brew install libpq" >&2
    exit 1
fi

# Check IAM auth is enabled
IAM_FLAG=$(gcloud sql instances describe "$INSTANCE" --project="$PROJECT" \
    --format="yaml(settings.databaseFlags)" 2>/dev/null || true)
if ! echo "$IAM_FLAG" | grep -q "cloudsql.iam_authentication"; then
    echo "ERROR: IAM auth not enabled on $INSTANCE. Run ./gcp/enable-iam-auth.sh first." >&2
    exit 1
fi

echo "All checks passed."
echo ""

# ── Load admin secrets ───────────────────────────────────────────────────────
if [ ! -f "$ADMIN_SECRETS" ]; then
    echo "--- First run: creating $ADMIN_SECRETS ---"
    if [ -f "$GENPASSWORD" ]; then
        NEW_ADMIN_PW=$("$GENPASSWORD" --no-copy)
        NEW_APP_PW=$("$GENPASSWORD" --no-copy)
    else
        NEW_ADMIN_PW=$(openssl rand -base64 24 | tr '+/' '_-' | head -c 20)
        NEW_APP_PW=$(openssl rand -base64 24 | tr '+/' '_-' | head -c 20)
    fi

    cat > "$ADMIN_SECRETS" <<SECRETS
{;; Cloud SQL admin credentials for $PROJECT:$REGION:$INSTANCE
 ;; Shared across all projects — do NOT check into git
 :admin-user     "$ADMIN_USER"
 :admin-password "$NEW_ADMIN_PW"
 :app-user       "$APP_USER"
 :app-password   "$NEW_APP_PW"
 :instance       "$PROJECT:$REGION:$INSTANCE"}
SECRETS

    echo "  Generated passwords via genpassword and wrote $ADMIN_SECRETS"
    echo ""
    echo "  WARNING: You must set these passwords in Cloud SQL:"
    echo "    gcloud sql users set-password $ADMIN_USER --instance=$INSTANCE --project=$PROJECT --password='$NEW_ADMIN_PW'"
    echo "    gcloud sql users set-password $APP_USER --instance=$INSTANCE --project=$PROJECT --password='$NEW_APP_PW'"
    echo ""
    read -p "  Have you set the passwords? (y/N) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "Set the passwords and re-run this script."
        exit 1
    fi
fi

# Parse passwords from EDN (simple grep — no EDN parser needed)
ADMIN_PASSWORD=$(grep ':admin-password' "$ADMIN_SECRETS" | sed 's/.*"\(.*\)".*/\1/')
APP_PASSWORD=$(grep ':app-password' "$ADMIN_SECRETS" | sed 's/.*"\(.*\)".*/\1/')

if [ -z "$ADMIN_PASSWORD" ] || [ -z "$APP_PASSWORD" ]; then
    echo "ERROR: Could not read passwords from $ADMIN_SECRETS" >&2
    exit 1
fi

# ── Step 1: Create database ─────────────────────────────────────────────────
echo "--- Step 1/6: Create database '$DB_NAME' ---"
if gcloud sql databases describe "$DB_NAME" --instance="$INSTANCE" --project="$PROJECT" &>/dev/null; then
    echo "  Already exists, skipping."
else
    gcloud sql databases create "$DB_NAME" --instance="$INSTANCE" --project="$PROJECT" --quiet
    echo "  Created."
fi

# ── Step 2: Create service account ──────────────────────────────────────────
echo "--- Step 2/6: Create service account '$SA_NAME' ---"
if gcloud iam service-accounts describe "$SA_EMAIL" --project="$PROJECT" &>/dev/null 2>&1; then
    echo "  Already exists, skipping."
else
    gcloud iam service-accounts create "$SA_NAME" \
        --display-name="$SA_NAME" \
        --project="$PROJECT" --quiet
    echo "  Created. Waiting for propagation..."
    sleep 5
fi

# ── Step 3: Create IAM database user ────────────────────────────────────────
echo "--- Step 3/6: Create IAM database user ---"
if gcloud sql users list --instance="$INSTANCE" --project="$PROJECT" \
    --format="value(name)" 2>/dev/null | grep -q "^${SA_USER}$"; then
    echo "  Already exists, skipping."
else
    # Retry loop for SA propagation delay
    for attempt in 1 2 3; do
        if gcloud sql users create "$SA_USER" \
            --instance="$INSTANCE" \
            --type=cloud_iam_service_account \
            --project="$PROJECT" --quiet 2>/dev/null; then
            echo "  Created."
            break
        else
            if [ "$attempt" -lt 3 ]; then
                echo "  SA not propagated yet, retrying in 5s... (attempt $attempt/3)"
                sleep 5
            else
                echo "ERROR: Failed to create IAM DB user after 3 attempts." >&2
                exit 1
            fi
        fi
    done
fi

# ── Step 4: Grant IAM roles ─────────────────────────────────────────────────
echo "--- Step 4/6: Grant IAM roles ---"
gcloud projects add-iam-policy-binding "$PROJECT" \
    --member="serviceAccount:$SA_EMAIL" \
    --role="roles/cloudsql.instanceUser" \
    --condition=None --quiet &>/dev/null
echo "  Granted roles/cloudsql.instanceUser"

gcloud projects add-iam-policy-binding "$PROJECT" \
    --member="serviceAccount:$SA_EMAIL" \
    --role="roles/cloudsql.client" \
    --condition=None --quiet &>/dev/null
echo "  Granted roles/cloudsql.client"

# ── Step 5: SQL GRANTs via proxy ────────────────────────────────────────────
echo "--- Step 5/6: Apply SQL GRANTs ---"

# Kill any existing proxy on our port
pkill -f "cloud_sql_proxy.*tcp:${PROXY_PORT}" 2>/dev/null || true
sleep 1

# Start proxy in background
$PROXY_BIN -instances="${PROJECT}:${REGION}:${INSTANCE}=tcp:${PROXY_PORT}" &>/dev/null &
PROXY_PID=$!
sleep 3

# Build SQL based on access level
if [ "$ACCESS" = "readonly" ]; then
    GRANT_SQL="
-- This CONNECT grant is load-bearing ONLY if the DB had PUBLIC connect revoked at creation
-- (the readwrite path does that). For a PRE-EXISTING shared DB, do NOT blindly revoke PUBLIC —
-- pre-flight who connects first, grant them, THEN revoke (see postgres-connect-isolation.md).
GRANT CONNECT ON DATABASE ${DB_NAME} TO \"${SA_USER}\";
GRANT USAGE ON SCHEMA public TO \"${SA_USER}\";
GRANT SELECT ON ALL TABLES IN SCHEMA public TO \"${SA_USER}\";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO \"${SA_USER}\";
"
else
    GRANT_SQL="
-- Grant genek full schema access
GRANT ALL ON SCHEMA public TO ${APP_USER};
-- Grant IAM SA full access
GRANT CONNECT ON DATABASE ${DB_NAME} TO \"${SA_USER}\";
GRANT USAGE, CREATE ON SCHEMA public TO \"${SA_USER}\";
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO \"${SA_USER}\";
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO \"${SA_USER}\";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL PRIVILEGES ON TABLES TO \"${SA_USER}\";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL PRIVILEGES ON SEQUENCES TO \"${SA_USER}\";
-- CONNECT isolation (2026-06-02): only THIS database's own roles may connect. Without this,
-- Postgres' default grants CONNECT to PUBLIC (every role on the instance), so on a SHARED
-- instance any app's DB role can connect to + enumerate the schema of every OTHER app's DB.
-- Safe here: freshly-created DB (only APP_USER + SA_USER; the owner connects implicitly).
GRANT CONNECT ON DATABASE ${DB_NAME} TO ${APP_USER};
REVOKE CONNECT ON DATABASE ${DB_NAME} FROM PUBLIC;
"
fi

# Run GRANTs as postgres superuser
PGPASSWORD="$ADMIN_PASSWORD" "$PSQL_BIN" \
    -h 127.0.0.1 -p "$PROXY_PORT" -U "$ADMIN_USER" -d "$DB_NAME" \
    -c "$GRANT_SQL" 2>&1 | grep -v "^$"

# Verify genek can create tables
PGPASSWORD="$APP_PASSWORD" "$PSQL_BIN" \
    -h 127.0.0.1 -p "$PROXY_PORT" -U "$APP_USER" -d "$DB_NAME" \
    -c "CREATE TABLE IF NOT EXISTS _provisioning_test (id int); DROP TABLE _provisioning_test; SELECT 'Verified: CREATE/DROP works' AS status;" 2>&1

# Kill proxy
kill "$PROXY_PID" 2>/dev/null || true
wait "$PROXY_PID" 2>/dev/null || true

echo "  GRANTs applied and verified."

# ── Step 6: Write secrets file ──────────────────────────────────────────────
echo "--- Step 6/6: Write $SECRETS_FILE ---"
mkdir -p secrets

cat > "$SECRETS_FILE" <<EOF
{:dbtype   "postgresql"
 :dbname   "$DB_NAME"
 :cloud-sql-instance "$PROJECT:$REGION:$INSTANCE"

 ;; Local dev (via cloud_sql_proxy on port $PROXY_PORT)
 :username "$APP_USER"
 :password "$APP_PASSWORD"

 ;; Cloud Run (IAM auth — no password needed)
 :iam-user "$SA_USER"

 ;; Admin (for GRANTs, schema migrations)
 :postgres-user "$ADMIN_USER"
 :postgres-password "$ADMIN_PASSWORD"}
EOF

echo "  Written."

# ── Done ─────────────────────────────────────────────────────────────────────
echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║  Done! Database '$DB_NAME' is ready.                        "
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "=== Clojure connection (local dev) ==="
cat <<EOF
(def ds (jdbc/get-datasource
          {:dbtype "postgresql" :host "127.0.0.1" :port $PROXY_PORT
           :dbname "$DB_NAME" :user "$APP_USER" :password "$APP_PASSWORD"}))
EOF
echo ""
echo "=== Clojure connection (Cloud Run, IAM auth) ==="
cat <<EOF
(def ds (make-datasource
          {:database "$DB_NAME"
           :sa-user  "$SA_USER"
           :instance "$PROJECT:$REGION:$INSTANCE"}))
EOF
echo ""
echo "=== Next steps ==="
echo "  1. Start proxy:  $PROXY_BIN -instances=$PROJECT:$REGION:$INSTANCE=tcp:$PROXY_PORT"
echo "  2. Apply schema: PGPASSWORD='$APP_PASSWORD' $PSQL_BIN -h 127.0.0.1 -p $PROXY_PORT -U $APP_USER -d $DB_NAME -f gcp/migrations/001_initial.sql"
echo ""
