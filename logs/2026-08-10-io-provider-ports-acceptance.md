# I/O provider ports acceptance — 2026-08-10

Bead: `sessionize-sched-killer-973.1`

## Outcome

Email and blob effects now cross application-owned, provider-neutral ports.
Views, folds, and pure decisions do not select providers. SMTP/AWS SES, Resend,
Cloudflare Email Service, local files, and GCS are replaceable adapters with
network-free recording tests.

The business boundary remains event sourced: `mail/send!` appends
`comms.rendered`, `comms.sent`, or `comms.failed` after interpreting the
normalized provider result. Provider adapters never write domain state.

## Namespace map

- `io.email` — secret-backed configuration, provider dispatch, normalized
  `{:ok :message-id/:error}` contract.
- `io.email.smtp` — lazy Postal transport and multipart calendar conversion.
- `io.email.aws-ses` — SES SMTP configuration over the same SMTP contract.
- `io.email.resend` — Resend REST payload, bearer auth, idempotency header, and
  normalized response.
- `io.email.cloudflare` — Cloudflare Email Service REST payload and normalized
  response.
- `io.blob` — dynamic put/read/copy port and provider selection.
- `io.blob.local` — local filesystem implementation.
- `io.blob.gcs` — ADC/token acquisition, GCS JSON API, and laptop CLI fallback.

`sinks.clj` no longer contains GCS URI parsing, URLs, access tokens, HTTP copy,
shell fallback, or a cloud-specific copy seam. Snapshot and restore call
`io.blob/copy!`.

## Permanent guards

- `io_email_test.clj` proves Resend and Cloudflare share the normalized email
  contract and preserve reply-to/calendar payloads.
- `io_blob_test.clj` proves the three-operation recording algebra and the local
  provider contract without network access.
- `io_architecture_test.clj` analyzes namespace edges and prevents provider
  imports from views, folds, and pure decisions. It also confines Postal to the
  SMTP adapter and GCS access-token acquisition to the GCS blob adapter.
- Existing communication tests prove sent/failed outcomes append the correct
  facts and failures do not roll back the triggering domain act.

## Verification

- Hot nREPL load, provider adapters through dependent services: `:ok`.
- Changed-provider lint: 0 errors, 0 warnings in the newly cleaned adapter
  files; repository-existing warnings remain outside this slice.
- Focused I/O/comms/files/sinks suite: 40 tests, 354 assertions, 0 failures.
- `make runtests-once`: 339 tests, 3,348 assertions, 0 failures.
- Full non-fail-fast `bin/kaocha unit`: 339 tests, 3,348 assertions, 0 failures.
- Cold JSONL server on port 20501 plus `bin/e2e_drive.py`: 263/263 HTTP checks.
- Sandbox server stopped; port 20501 has no listener.

No PostgreSQL, production database, GCS bucket, email service, browser, DDL,
deployment, or cloud state was touched. Live credentials and provider delivery
remain morning-operator work.

## Clj Surgeon observation

Exact-source whole-form deletion worked for independent leaf forms. Deleting a
six-form dependency cluster could not be compiled as one basis while the
semantic index was warming; sequential deletion left intermediate unresolved
dependencies and was correctly refused. The remaining contiguous provider
cluster required one bounded native contraction. The missing high-payoff
capability is an exact-source multi-owner transaction: prepare several named
forms from one file, rewrite callers, and delete the mutually dependent owners
atomically without requiring an LSP index.
