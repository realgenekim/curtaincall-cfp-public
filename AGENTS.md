# AGENTS.md — start here, then read CLAUDE.md

**All project instructions live in [CLAUDE.md](CLAUDE.md)** — mission, design
doctrine, research-doc map, and Clojure dev guidelines. Read it first.

Quick orientation: this is cfp-scheduler-killer (swyx Kill My SaaS entry,
deadline Wed Aug 12 2026 10PM PT). Datastar SSE server-rendered app; the 12
Datastar NEVERs in the user-global CLAUDE.md are binding. Run `make
runtests-once` after src changes. clj-surgeon MCP needs
`workspace_root: /Users/genekim/src.local/sessionize-sched-killer`.

## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` for full
workflow context and the session-close protocol.

```bash
bd ready                # find available work
bd show <id>            # issue details
bd update <id> --claim  # claim work
bd close <id>           # complete work
```

Rules: use bd for ALL task tracking (no TodoWrite/markdown TODOs); `bd remember`
for persistent knowledge. Do not commit/push unless asked. Captain's logs go in
`./logs/` (work-log convention).
