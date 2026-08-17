# Eight-tab SSE acceptance — 2026-08-10

## Automated attempt

- Target: `http://localhost:20500/events/enterprise-ai-summit-charlotte-2026/board?at-index=130`
- Chrome already had five localhost tabs. Three were logged-out login pages.
- A newly created Chrome tab navigating directly to localhost failed with
  `ERR_BLOCKED_BY_CLIENT`.
- Reusing an existing login tab and posting a demo-persona login reached
  `/api/demo-login?...`, but Chrome blocked that localhost navigation too.
- The in-app browser fallback was unavailable (`Browser is not available: iab`).
- A fresh Chrome address-bar fallback remained at `about:blank`.
- Agent-created tabs were closed and user-owned tabs were released.

No curl or synthetic HTTP substitute was recorded as a pass: the acceptance
criterion is specifically eight real browser tabs sharing the browser's
per-origin connection pool. The code decomposition gates and full suite pass,
but the parent decomposition bead remains open until this browser-level check
can be executed in a supported browser session.
