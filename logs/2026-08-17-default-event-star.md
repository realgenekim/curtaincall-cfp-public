# Default event star live-drive — 2026-08-17

## Surface

- Chrome, signed in as Gene Kim at `http://localhost:20500/events`.
- Six active event rows were visible.
- The initial browser connection briefly returned `ERR_BLOCKED_BY_CLIENT`; reconnecting to the live Chrome tab restored the page.

## Acceptance flow

1. Confirmed every active event row had a server-rendered star button to the left of its name.
2. Selected **Enterprise AI Summit** (`enterprise-ai-summit-charlotte-2026`).
3. Confirmed the POST returned to `/events` and the account projection persisted event id `edb67dc1-a564-417f-bcdf-7ad4bbfaf21f` as `:default-event-id`.
4. Reloaded the page and confirmed exactly one active star and five inactive stars.
5. Confirmed the selected button's accessible name changed to “Enterprise AI Summit is your default event”.
6. Reduced star size from `1.25em` to `0.83em` and set inactive opacity to `0.18`; retained full-opacity gold for the selected star and a visible hover/focus state.

## Result

- PASS: the Charlotte event remains selected after reload.
- PASS: unselected stars are visually quiet; the selected star remains legible.
- PASS: selection is a normal POST followed by a server repaint; no client-side DOM mutation.
- Focused default-event test: 1 test, 11 assertions, 0 failures.
- The shared suite remained red in concurrent communications work; no default-event test failed.
