# `/ping` keepalive — local acceptance

**Date:** 2026-08-11
**Bead:** `sessionize-sched-killer-ccn`

## Application contract

- `GET /ping` is a named, REPL-reloadable handler in
  `cfp-scheduler-killer.handlers.health`.
- It returns `200`, `Content-Type: text/plain; charset=utf-8`, and body `pong`.
- It has no store, secret, or domain dependency and appends no fact.
- Auth opens exact paths `#{"/" "/ping"}`. `/ping-neighbor` is not public and
  does not inherit the exception; protected `/events` remains protected.
- The ordinary middleware stack still handles the request, so a successful
  ping proves that the service accepts Ring requests rather than merely that a
  separate process-level probe is alive.

## Permanent tests

- Focused health contract: **1 test / 6 assertions / 0 failures**. It proves
  anonymous access, exact body/content type, no log growth, neighbor 404 for an
  authenticated caller, and continued anonymous protection of `/events`.
- Route topology: **193 routes (72 GET / 121 POST)**, hash
  `018e2f60903cf5365d890e3fc2e55ed5a4d7cf37dd2414647b8c0e10b1747818`.
- Architecture guard explicitly admits the coherent health-handler namespace:
  **1 test / 6 assertions / 0 failures**.
- Full 512 MB one-shot suite: **374 tests / 3,818 assertions / 0 failures**.

## Fresh-process HTTP proof

A JSONL sandbox server was cold-started on port 20502. Direct curl returned:

```text
HTTP/1.1 200 OK
Content-Type: text/plain; charset=utf-8

pong
```

The complete live HTTP drive then passed **263/263**. The sandbox server was
stopped and port 20502 was proven free. Port 20501 was deliberately left alone:
it belongs to the sibling `curtain-call-staging` process.

## Operator-only remainder

No gcloud command, deployment, live-service curl, credential change, or
production mutation was performed. After this checkpoint is deployed to both
active services, the operator must execute the idempotent two-job commands in
bead `ccn`, curl both deployed `/ping` URLs, run both Scheduler jobs once, and
prove enabled state plus Cloud Run 200 request logs. Until then, the application
half is complete but the outcome bead remains open.
