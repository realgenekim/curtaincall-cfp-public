# One database: dev IS production

*Ratified by Gene 2026-08-10 ("100% go"), executed the same night. Supersedes
the GCS-snapshot durability plan (bd iy9) and the earlier split-brain proposal.
Pattern precedent: video-library 2026 (one Firebase production database, dev
connects to it, staging rarely used — a real production site).*

## The ruling

**Dev and production are the same world.** One production Cloud SQL database,
one deployed Cloud Run service, one code line. What you create locally IS what
a judge sees. No migrations between environments, no promote ceremonies in
daily life, no "which store am I looking at" — that confusion cost a night.

| | Runs where | Store |
|---|---|---|
| `make server-dev` | laptop :20500 | **production Postgres** (via `secrets/db.edn`) |
| Cloud Run `swyx-cfp-saas-killer` | us-west1, `--max-instances 1` | **the same database** (IAM, no password exists) |
| `make reset-jsonl-server` | laptop :20500 | restored, verified Judge Sandbox — **for swyx/Maya/Amara hill climbs** |
| `make server-jsonl` | laptop :20500 | local JSONL sandbox — **for coding agents + e2e drives** |
| test suites | in-process | throwaway temp stores (`with-temp-store`) |

The sandbox line is load-bearing: on 2026-08-09 the agent fleet generated
hundreds of junk events per hour through the dev server. Agents and
`bin/e2e_drive.py` drive `server-jsonl`, never the shared database.

## Why this is safe (the mitigation stack)

1. **History cannot be ruined, only appended to.** The store is an append-only
   event log (`store_events`); DB triggers refuse every UPDATE and DELETE, and
   the Cloud Run role holds SELECT+INSERT only. Projections fold from the log
   and rebuild from scratch on every boot; time-travel reads are free.
2. **Verbatim one-command backups.** `make backup-db` writes the whole log to
   `./backups/<utc-stamp>-events-pg.jsonl`, raw lines in order, never
   re-encoded. Restore = replay the file (identical to the original
   migration: 620/620 byte-verified). Take one before anything risky.
   Cloud SQL's instance-level automated backups sit underneath.
3. **Two writers, honest visibility.** The log itself is multi-writer-safe
   (pure INSERTs on a sequence). The in-memory folds are kept honest by the
   throttled external probe (`store/pg-observed-seq`): each process asks the
   database for `max(seq)` at most every ~2s, so the laptop notices Cloud
   Run's appends (and vice versa) within the interval. Cloud Run stays
   `--max-instances 1` — one server-side writer.
4. **Schema changes only via REPL as the table owner** (`genek`), additive
   only — the runbook is `docs/postgres-store.md`. The app cannot DDL.
5. **No secrets delivered by env.** Laptop: Cloud SQL **socket factory over
   ADC** with the runtime-loaded, gitignored `secrets/db.edn` (600). Never
   `cloud_sql_proxy`, never `psql` (Gene's rule — the REPL is the migration
   tool). Cloud Run: IAM auth; no database password exists at all.

## The commands (the clj -X operator CLI — `admin.clj`, VLAdmin pattern)

```bash
make backup-db          # production log -> ./backups/, verbatim, count-first
make promote-db         # replay local JSONL log into Postgres (guarded;
                        #   FORCE=true appends after existing — never overwrites)
make server-dev         # dev on the production database
make server-jsonl       # agent/e2e sandbox on a local file
```

Every task is a `clj -X` entry fn in `cfp-scheduler-killer.admin` that fires
up its own infrastructure, prints a count-first summary, and returns data.

## History of the cutover (2026-08-10, all meter-verified)

1. Relic row backed up (`backups/2026-08-10-023744…`, 1 stray test event),
   then cleared by the owner.
2. Local history replayed via REPL: **620 events local → 620 in Postgres,
   byte-identical.** Post-migration backup taken (671,507 bytes).
3. Service flipped: `STORE_BACKEND=postgres` + Cloud SQL attach + IAM SA +
   `--max-instances 1`; GCS snapshot env removed. Live URL verified serving
   the migrated world.
4. GCS snapshot machinery (`sinks.clj`) stays merged and tested but idle —
   it is the JSONL mode's durability story, not this one's.

## Later (explicitly deferred)

- A staging database, if multi-person dev ever needs it ("maybe later, not
  now" — Gene).
- Consolidating the service name (`curtaincallcfp` vs `swyx-cfp-saas-killer`)
  — one URL should be THE production; the other retires.
- Scheduled `make backup-db` (cron) + backup retention.
