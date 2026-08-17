# Event-scoped agent interface — acceptance record

**Bead:** `sessionize-sched-killer-y7e`
**Accepted:** 2026-08-10 PDT / 2026-08-11 UTC
**Safety lane:** local JSONL only; no PostgreSQL, DDL, deploy, gcloud, Git commit, or push

## Outcome

One application-service registry now powers both an event-scoped MCP endpoint
and `clj -X:agent`. The adapters do not reimplement business rules. Event scope
comes from the trusted URL or CLI option, never from tool arguments.

The registry exposes 11 bounded commands: five public reads, five protected
operational reads, and one protected mutation. `set_submission_status` is dry
by default and, when explicitly confirmed by a signed event member, calls the
existing `reviews/set-status!` named domain verb. It cannot run through an API
key. There is no SQL, eval, generic fact append, delete, or arbitrary request
tool.

## Acceptance evidence

- Hot nREPL reload: `agent.commands`, `agent.mcp`, `agent.cli`,
  `handlers.agent`, `auth`, and `server` loaded with `:ok`.
- Focused parity: **6 tests / 71 assertions / 0 failures**.
- Agent + exports + route architecture/contract: **29 / 722 / 0**.
- Fast full suite: **353 / 3,518 / 0**.
- Required non-fail-fast full suite: **353 / 3,518 / 0**.
- Fresh JSONL server on port 20501: **263 / 263 e2e checks passed**.
- `python3 bin/agent_drive.py --base http://localhost:20501`: **PASS**;
  initialized MCP, discovered 11 tools, completed `get_event`, and proved an
  anonymous `review_coverage` call is rejected.
- Real local CLI `get_event`: passed.
- Real remote CLI `get_event` through `http://localhost:20500`: passed.
- Checked-in `dev/scenarios/agent-read-demo.edn`: three ordered reads executed
  through one local context and the shared registry; output `scenario — ok`.

## Permanent contracts

- Registry names, JSON schemas, output envelopes, error envelopes, and auth
  behavior are compared across direct service, MCP, and CLI calls.
- Anonymous callers receive only public projections. Event membership or an
  event-scoped API key is required for protected reads.
- Cross-event submission IDs are rejected.
- A confirmed mutation appends exactly one existing named domain fact and
  returns its `factLogIndex`; dry-run appends nothing.
- `/events/:slug/mcp` is advertised from the API event document and
  `llms.txt`.
- Route topology remains characterized at 170 routes: 68 GET and 102 POST.

## Operator-gated remainder

No production verification was attempted. Deployment, live-secret
provisioning, external MCP client configuration, and any intended production
mutation remain morning-operator actions.
