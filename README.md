# Curtain Call

**ITRev's entry in swyx's Kill My SaaS contest** — an open-source replacement
for Sessionboard's Program module, built by the team that has run DevOps
Enterprise Summit / Enterprise Tech Leadership Summit CFPs for 12 years:
CFP forms → committee review → decisions & notifications → speaker portal →
program/agenda → exports and API.

## Try it (judges start here)

| Instance | URL | What it is |
|---|---|---|
| **DEMO** | https://swyx-cfp-killer-109637679549.us-west1.run.app | One-click sign-in as Organizer (swyx) / Reviewer (Maya) / Speaker (Amara); self-seeding Charlotte world; ephemeral store — romp freely, it resets |
| **PROD** | https://curtaincallcfp.com | The real thing: Postgres-backed, running the actual Enterprise AI Summit Charlotte 2026 CFP |

The guided tour: [`docs/demo-script.md`](docs/demo-script.md). The design
doctrine it demonstrates, in one breath: **review is a conversation among
trusted peers over a shared table** (every score and comment visible inline);
the board's **two default sorts are the two work queues** (fewest-ratings
ascending = coverage worklist, mean-stars descending = decision queue);
**zero-to-open-CFP in ten minutes**; one page, no wizards, no account walls —
the account is created on submit.

## What's here

- **Stack**: Clojure + http-kit + [Datastar](https://data-star.dev) SSE
  (server-owned state, HTML over SSE, ~zero client JS) + Postgres.
- **Event-sourced store**: append-only facts, fold to state, time-machine
  replay (`?at-index`) — the demo's "watch the event unfold" trick.
- **Exports & API**: ai.engineer-compatible `sessions.json` / `speakers.json` /
  `calendar.ics` (stable UIDs — invites amend, never duplicate) / `llms.txt`,
  plus a Sessionboard-shaped REST API v1 with webhooks
  ([`docs/agent-interface.md`](docs/agent-interface.md)).
- **Speaker announce pages**: per-speaker share cards with OG/Twitter unfurls —
  the speaker wall recomposed into an artifact each speaker owns and shares.

## The trajectory (how it was built)

This project was built agent-first in ~4 days. The working artifacts are
published deliberately:

- [`docs/tech-tree.md`](docs/tech-tree.md) — the Civ-style living map of every
  feature: shipped / in-flight / blocked, with the era structure
- [`CLAUDE.md`](CLAUDE.md) + [`AGENTS.md`](AGENTS.md) — the actual agent
  operating instructions, including the design doctrine and hard-won
  Datastar/SSE lessons
- [`plans/`](plans/) — ratified handoff specs given to parallel coding agents
- [`logs/`](logs/) — dated captain's logs: every manual drive, defect, and fix
  as it happened
- [`docs/research/`](docs/research/) — the field research that grounded the
  design (15 years of CFP-tool archaeology:
  [`to-swyx/cfp-flow-comparison.md`](to-swyx/cfp-flow-comparison.md))

## Run it locally

```bash
make nrepl           # REPL + dev server (port 20500, ENV=dev)
make runtests-once   # kaocha suite
```

No secrets required for the default sandbox (jsonl event store). Postgres and
deploy plumbing: [`docs/postgres-store.md`](docs/postgres-store.md),
[`docs/deploy-runbook.md`](docs/deploy-runbook.md).

---

*This repository is a snapshot **export** of a private working repo, assembled
by an allowlist manifest ([`docs/allowlist-manifest.md`](docs/allowlist-manifest.md))
— field research involving private conversations stays out by construction.
Development continues upstream; refreshes land here as new commits.*
