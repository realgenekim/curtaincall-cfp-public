# Event-scoped agent interface

The app exposes one application-service registry through two adapters:

```text
                     ┌─ clj -X CLI (local or remote)
command registry ────┤
                     └─ MCP over POST /events/:slug/mcp
```

The registry, authorization, JSON Schemas, results, and errors are shared. The
adapters do not contain conference business rules.

## Safety model

- The event slug comes from the trusted endpoint or CLI option. Tools do not
  accept an `eventSlug` argument, so a model cannot silently change tenancy.
- Public commands return only published program data.
- Operational reads require membership in that event or one of that event's
  API keys.
- API keys are read-only. Mutations require a signed-in human event member.
- The only mutation is `set_submission_status`. It calls the existing
  `reviews/set-status!` domain verb, which appends
  `submission.status-changed`; there is no arbitrary fact, SQL, eval, delete,
  or generic query tool.
- `confirm=false` is a dry run and appends nothing. `confirm=true` appends one
  fact and returns its event-local log index as proof.
- Every result carries an audit envelope with actor, event, command, source,
  access class, mutation flag, changed flag, and fact log index.

## MCP configuration

The endpoint supports the MCP `initialize`, `tools/list`, `tools/call`, and
`ping` messages over JSON-RPC HTTP POST. It also answers the newer stateless
`server/discover` method. Notifications return HTTP 202.

For public tools, a client configuration needs only the event URL:

```json
{
  "mcpServers": {
    "aie-program": {
      "type": "http",
      "url": "http://localhost:20500/events/enterprise-ai-summit-charlotte-2026/mcp"
    }
  }
}
```

For operational reads, add `Authorization: Bearer <event-api-key>` in the MCP
client's private configuration. Never commit the key. API keys can inspect but
cannot mutate.

Protocol smoke test:

```bash
curl -sS http://localhost:20500/events/enterprise-ai-summit-charlotte-2026/mcp \
  -H 'content-type: application/json' \
  --data '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'

curl -sS http://localhost:20500/events/enterprise-ai-summit-charlotte-2026/mcp \
  -H 'content-type: application/json' \
  --data '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'

curl -sS http://localhost:20500/events/enterprise-ai-summit-charlotte-2026/mcp \
  -H 'content-type: application/json' \
  --data '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_event","arguments":{}}}'
```

## CLI

The canonical local CLI is a Clojure `-X` function over the same registry:

```bash
clj -X:agent :help true

clj -X:agent \
  :event '"enterprise-ai-summit-charlotte-2026"' \
  :command :get_event

clj -X:agent \
  :event '"enterprise-ai-summit-charlotte-2026"' \
  :command :review_coverage \
  :actor '"organizer@example.com"'
```

The default output is stable JSON. Add `:output :human` for a concise status
line.

The same CLI can call a remote MCP endpoint rather than opening the local
event store:

```bash
clj -X:agent \
  :base-url '"https://your-service.example"' \
  :event '"enterprise-ai-summit-charlotte-2026"' \
  :command :get_event
```

For operational remote reads, add `:token '"<event-api-key>"'` at the shell
prompt or supply it through a private launcher. Do not put tokens in scripts or
the repository.

## Safe status mutation

Preview first:

```bash
clj -X:agent \
  :event '"enterprise-ai-summit-charlotte-2026"' \
  :command :set_submission_status \
  :actor '"organizer@example.com"' \
  :args '{:submissionId "SUBMISSION-ID" :status "Accepted" :confirm false}'
```

After a human reviews the proposal, repeat with `:confirm true`. The response
names the appended `submission.status-changed` fact and its `factLogIndex`.

## Deterministic scenarios

A scenario is EDN compiled into an ordered command script:

```clojure
{:event "enterprise-ai-summit-charlotte-2026"
 :actor "organizer@example.com"
 :commands
 [{:command :get_event :args {}}
  {:command :set_submission_status
   :args {:submissionId "SUBMISSION-ID"
          :status "Accepted"
          :confirm true}}]}
```

Run it safely:

```bash
clj -X:agent :scenario '"dev/scenarios/agent-read-demo.edn"'
```

Scenarios are dry-run by default: mutation confirmations are forced to false.
`:apply true` honors the confirmations already written in the reviewed EDN. A
scenario never edits JSONL or PostgreSQL directly; it invokes the same named
commands and domain verbs as MCP and the UI.

## Command catalog

| Command | Access | Purpose |
|---|---|---|
| `get_event` | Public | Identity, dates, CFP state, counts, canonical links |
| `list_sessions` | Public | Published sessions with stable joins |
| `list_speakers` | Public | Published speakers with stable joins |
| `get_schedule` | Public | Days, rooms, placements, blocks, unscheduled rows |
| `export_event` | Public | Sessions, speakers, iCal, or llms export |
| `list_submissions` | Event read | Complete submission funnel |
| `review_coverage` | Event read | Reviewer assigned/done/remaining |
| `schedule_conflicts` | Event read | Coverage and room/speaker/date collisions |
| `speaker_obligations` | Event read | Outstanding deliverables, overdue first |
| `event_history` | Event read | Monotonic, IDs-only change feed |
| `set_submission_status` | Signed human | Dry-run or append the named status fact |

The MCP endpoint is also advertised in each event's `llms.txt` and API event
discovery document.
