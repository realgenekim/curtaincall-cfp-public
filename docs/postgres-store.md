# The Postgres event store (STORE_BACKEND=postgres)

**Status: LIVE (2026-08-10).** Cloud Run (`swyx-cfp-saas-killer`) runs
`STORE_BACKEND=postgres` against this database, and so does local
`make server-dev` — dev IS production (see `docs/one-database.md`, the
ratified strategy + mitigation stack). The local connection comes from
`secrets/db.edn` (socket factory over ADC — never cloud_sql_proxy, never
psql; the REPL is the migration tool).

## What it is

The store is an append-only event log. JSONL says that as *a file of ordered
lines*; Postgres says it as *a table of ordered lines*. That is the entire
difference. Two functions change (`store/append-line!`, `store/read-events`);
`fold`, `state-as-of`, replay, exports, and every test above the seam cannot
tell which one they are talking to.

Why it exists: **Cloud Run's disk is ephemeral.** A JSONL log on a container
filesystem is gone at the next instance recycle. Postgres is the durable answer
that does not change the design.

### The seam

| | JSONL (default) | Postgres |
|---|---|---|
| append | `io/writer :append true` | `INSERT INTO store_events (line) VALUES (?)` |
| read | `line-seq` over the file | `SELECT line FROM store_events ORDER BY seq` |
| change mark | `[file-length last-modified]` | highest `seq` this process wrote/loaded |
| other-process writes | noticed (`refresh-if-changed!`) | **not** noticed — single-writer by construction |

Selection is `STORE_BACKEND=postgres`; anything else is JSONL. `store-pg` is
loaded lazily via `requiring-resolve`, so the default path never touches JDBC —
a judge who clones the repo still gets zero-setup.

**Single writer is load-bearing.** In Postgres mode the change mark is an
in-process counter, not a query (`snapshot` runs on every read; a `SELECT` there
would be dozens of round trips per page). A second concurrent writer would go
unnoticed until the next `load!`. The deploy therefore pins `--max-instances 1`.
That is not a performance setting; it is a correctness setting.

## Schema (as created, 2026-08-09)

```sql
CREATE TABLE IF NOT EXISTS store_events (
  seq        BIGSERIAL PRIMARY KEY,
  line       TEXT NOT NULL,          -- the JSON line, VERBATIM
  created_at TIMESTAMPTZ NOT NULL DEFAULT now());

CREATE OR REPLACE FUNCTION prevent_delete() RETURNS TRIGGER AS $fn$
BEGIN RAISE EXCEPTION 'DELETE not allowed on %. The log is append-only.', TG_TABLE_NAME; END;
$fn$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION prevent_update() RETURNS TRIGGER AS $fn$
BEGIN RAISE EXCEPTION 'UPDATE not allowed on %. The log is append-only.', TG_TABLE_NAME; END;
$fn$ LANGUAGE plpgsql;

CREATE TRIGGER no_delete_store_events BEFORE DELETE ON store_events
  FOR EACH ROW EXECUTE FUNCTION prevent_delete();
CREATE TRIGGER no_update_store_events BEFORE UPDATE ON store_events
  FOR EACH ROW EXECUTE FUNCTION prevent_update();
```

Source of truth: `store-pg/ddl-statements`, applied idempotently at boot. There
is no migration framework and no version table — one table, `CREATE IF NOT
EXISTS`, triggers re-created each boot.

**`line` is TEXT, not JSONB, on purpose.** House doctrine: store data in its most
original form, transform on the way out. The line we wrote is the line we read
back, byte for byte, so `store/canonicalize` still holds and a jsonb round-trip
can never reorder keys or coerce a number. A jsonb column can be added later as a
*generated* (derived) column for querying; that is the only honest way to have one.

**No upserts. No `ON CONFLICT`. Pure INSERT.** A repeated fact is a repeated
fact; the log's job is to record that it happened twice.

## Provisioned resources

| | |
|---|---|
| Instance | `EXAMPLE-GCP-PROJECT-A:us-central1:postgres1` (shared) |
| Database | `cfp_scheduler_killer` |
| Table owner | `genek` |
| DB service account | `cfp-scheduler-killer@EXAMPLE-GCP-PROJECT-A.iam.gserviceaccount.com` (from `provision-postgres-db.sh`) |
| **Cloud Run service account** | `cfp-saas-killer-run@swyx-cfp-saas-killer.iam.gserviceaccount.com` |
| Cloud Run service | `swyx-cfp-saas-killer` (project `swyx-cfp-saas-killer`, us-west1) |
| Local secrets | `secrets/cfp_scheduler_killer-postgres.edn` (gitignored) |

Grants (issued by the **table owner** `genek`, not `postgres` — a superuser
cannot grant on tables owned by someone else):

```sql
GRANT SELECT, INSERT ON store_events TO "cfp-saas-killer-run@swyx-cfp-saas-killer.iam";
GRANT USAGE, SELECT ON SEQUENCE store_events_seq_seq TO "cfp-saas-killer-run@swyx-cfp-saas-killer.iam";
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT SELECT, INSERT ON TABLES TO "cfp-saas-killer-run@swyx-cfp-saas-killer.iam";
```

The Cloud Run role gets **SELECT + INSERT only** — no UPDATE, no DELETE. The
triggers are the enforcement; the grant is the second lock on the same door.

CONNECT isolation (shared instance, issued as `postgres`, the database owner):

```sql
GRANT CONNECT ON DATABASE cfp_scheduler_killer TO "cfp-saas-killer-run@swyx-cfp-saas-killer.iam";
GRANT CONNECT ON DATABASE cfp_scheduler_killer TO "cfp-scheduler-killer@EXAMPLE-GCP-PROJECT-A.iam";
GRANT CONNECT ON DATABASE cfp_scheduler_killer TO genek;   -- BEFORE the revoke, or the owner is locked out
REVOKE CONNECT ON DATABASE cfp_scheduler_killer FROM PUBLIC;
```

Verified: `datacl` shows `=T/cloudsqlsuperuser` for PUBLIC (TEMP only, no `c`),
so no other app's role on `postgres1` can connect to this database or enumerate
its schema.

## Running it

### Local, against the cloud database

```bash
~/bin/cloud_sql_proxy -instances=EXAMPLE-GCP-PROJECT-A:us-central1:postgres1=tcp:5433 &
PW=$(grep ':app-password' ~/src.local/secrets/cloud-sql-admin.edn | sed 's/.*"\(.*\)".*/\1/')
STORE_BACKEND=postgres DEMO_MODE=true PORT=20599 \
  DATABASE_URL="jdbc:postgresql://127.0.0.1:5433/cfp_scheduler_killer?user=genek&password=$PW" \
  clojure -M -m cfp-scheduler-killer.core
```

### On Cloud Run (IAM auth — no password secret exists at all)

Set `CLOUD_SQL_INSTANCE` and the socket factory takes over: `DB_NAME`,
`DB_IAM_USER`, workload identity. Nothing to mount, nothing to rotate.

### Tests

The Postgres tests hit the real database and are OFF unless asked for:

```bash
PG_TESTS=true DATABASE_URL='jdbc:postgresql://127.0.0.1:5433/cfp_scheduler_killer?user=genek&password=…' \
  bin/kaocha --focus cfp-scheduler-killer.store-pg-test
```

They write to **`store_events_test`**, never `store_events`. An append-only log
has no undo, so isolation comes from writing somewhere else, not from cleaning up
afterwards. The scratch table is dropped and rebuilt each run.

## READY TO PASTE — the Cloud Run switch-over (NOT yet applied)

Gene's/the main loop's act, not this workstream's. Check the current deploy
target in the `Makefile` first and keep the image/region it already uses.

```bash
gcloud run deploy swyx-cfp-saas-killer \
  --project=swyx-cfp-saas-killer \
  --region=us-west1 \
  --image=<the image the Makefile already deploys> \
  --service-account=cfp-saas-killer-run@swyx-cfp-saas-killer.iam.gserviceaccount.com \
  --add-cloudsql-instances=EXAMPLE-GCP-PROJECT-A:us-central1:postgres1 \
  --set-env-vars=STORE_BACKEND=postgres,CLOUD_SQL_INSTANCE=EXAMPLE-GCP-PROJECT-A:us-central1:postgres1,DB_NAME=cfp_scheduler_killer,DB_IAM_USER=cfp-saas-killer-run@swyx-cfp-saas-killer.iam,DEMO_MODE=true \
  --max-instances=1 \
  --allow-unauthenticated
```

`--max-instances=1` is **required**, for the single-writer reason above.

Once this is live, the GCS JSONL snapshot (`GCS_SNAPSHOT_BUCKET`) is redundant:
it copies `data/store/events.jsonl`, which Postgres mode never writes. It is
inert rather than wrong (`snapshot-now!` no-ops on a missing file), but the env
var should be dropped from the deploy to avoid implying a durability story we
are no longer using.

### Migrating an existing JSONL log into Postgres

The migration is "fold the file into the table" — by construction, because both
are the same sequence of lines:

```clojure
(require '[cfp-scheduler-killer.store :as store] '[cfp-scheduler-killer.store-pg :as pg])
(pg/start!)
;; ONLY into an empty table — appending into a non-empty log duplicates history.
(assert (zero? (pg/count-lines)))
(doseq [line (line-seq (clojure.java.io/reader "data/store/events.jsonl"))]
  (when-not (clojure.string/blank? line) (pg/append-line! line)))
```

Not yet exercised on real data — see the honest list in the work notes.
