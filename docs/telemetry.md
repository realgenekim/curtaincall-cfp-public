# First-party telemetry

This application records two deliberately separate kinds of history:

- Domain facts such as `submission.created` live in `store_events` and rebuild
  the product state.
- Screen behavior lives in `telemetry_events`. It is research evidence, never
  an input to a domain fold.

## Privacy boundary

The server records route templates, status, duration, event slug, opaque person
ID, a one-way session hash, allowlisted behavioral parameters, user-agent class,
and Cloud Run revision. Search text is reduced to presence and length. It never
records request bodies, answers, email, raw cookies, raw session IDs, IP
addresses, or arbitrary query parameters.

The browser beacon adds only `page_view`, scroll depth, time on page, an
explicit developer-authored `data-track` label, outbound hostname, and a narrow
`app_event` name. The endpoint is same-origin, limited to 4 KiB, validates every
type, discards URL queries and unknown data keys, and hashes its session and
visitor IDs again. Ring requests remain canonical for actions Ring can see.

The browser does not send when Global Privacy Control or Do Not Track is on,
when `localStorage.telemetry_opt_out` is `"1"`, or when the page root has
`data-telemetry-opt-out`. The pseudonymous visitor ID rotates after 30 days.

Raw telemetry has a 90-day retention target. `TELEMETRY_ENABLED` must remain
off until the morning operator has installed owner-controlled table rotation or
another reviewed retention mechanism; the active table itself is append-only.
Only deidentified aggregates should survive beyond that window.

## Runtime behavior

Request threads use a non-blocking offer to a bounded in-memory queue. A daemon
flushes up to 500 rows with one multi-value INSERT about every two seconds.
Failed batches remain pending for retry; overflow and write failures are
counted and logged. Shutdown spends at most 750 ms flushing. A crash can lose
the bounded in-memory tail, never a product request or domain fact.

Each Cloud Run instance owns its own queue and writes to the same table. There
is no singleton, leader, or cross-instance coordination requirement.

## Morning operator setup

Do this through the owner REPL, never psql. Create `telemetry_events` using the
same `store-pg/ddl-statements` shape as `store_events`, then grant the app role
only `INSERT` and `SELECT` plus sequence usage. Install and verify the 90-day
retention mechanism before enabling collection. Then set
`TELEMETRY_ENABLED=on`, deploy/restart, and verify queue metrics and row growth.

No application startup path executes DDL.

## REPL analysis and export

`cfp-scheduler-killer.analysis/read-events` reads decoded rows through the
application pool. `journeys`, `journey-summary`, `event-breakdown`, and
`filter-date` are descended from `social-media-writer/writer.analysis`.
`export-jsonl!` produces the familiar laptop stream without granting analysts
write access to production. The short `dev/analysis.clj` façade keeps these
functions convenient at the development REPL.
