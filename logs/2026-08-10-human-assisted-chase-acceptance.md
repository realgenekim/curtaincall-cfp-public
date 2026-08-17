# Human-assisted speaker chase — acceptance evidence

## Outcome

The organizer can select several speakers who owe the same item, review and
edit an individually addressed email for each person, and press one explicit
**Send reviewed messages** button. Nothing sends during selection or drafting.

The effect boundary distinguishes three truthful outcomes:

- `:sent` appends `comms.sent`, then `task.chase-recorded`, and resets the
  obligation's last-touch clock;
- `:rendered` appends `comms.rendered` but does not claim the speaker was
  contacted; and
- `:failed` appends `comms.failed` and does not reset the clock.

Manual off-platform follow-up remains available with Email, Text, Call, and
Other as recorded media. There is no scheduler or autonomous sender.

## Durable facts and folds

`task.chase-recorded` now retains the human-selected medium, exact reviewed
subject/body, and delivery mode. `comms.sent|rendered|failed` retains the
organizer `:from` address and obligation `:task-key`. Stable chase IDs keep
retries idempotent.

## Verification

- Hot nREPL compile: all changed domain, fold, mail, app, view, handler, and
  server namespaces loaded; `server/make-routes` returned 160.
- Route characterization: 160 routes, 65 GET / 95 POST, SHA-256
  `a4c18c4d8d902a538d3cee465ed91cdfcfee56d4f1f4cb066b963425135c0970`.
- Focused laws/adapters/contracts: 26 tests, 414 assertions, 0 failures.
- `make runtests-once`: 331 tests, 3,303 assertions, 0 failures.
- Full non-fail-fast `bin/kaocha unit`: 331 tests, 3,303 assertions, 0
  failures.
- Cold JSONL server on port 20501: loaded and rendered the changed
  deliverables surface.
- Cold HTTP drive: 263/263 checks passed; no PostgreSQL, deploy, DDL, or cloud
  mutation was used.

## Literal human gate

The review screen renders **Human send gate**, **Nothing is sent until you
press “Send reviewed messages”**, and **Send reviewed messages**. The results
screen says that only provider-accepted messages reset the obligation clock.
