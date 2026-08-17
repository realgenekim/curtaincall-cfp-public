# Review board render performance — 2026-08-15

## Scope

- Local Judge Sandbox, organizer session
- Event: `enterprise-ai-summit-charlotte-2026`
- Source submissions: 500
- URL: `/events/enterprise-ai-summit-charlotte-2026/board?sort=ready-to-decide`

## Before

The board rendered all 500 rows and 500 inline rating forms.

| Run | Total time | Response bytes |
| --- | ---: | ---: |
| 1 | 1.142880 s | 4,065,024 |
| 2 | 0.803766 s | 4,065,024 |
| 3 | 0.882510 s | 4,065,024 |

## After

The board renders 25 ordered rows per page and zero inline rating forms. The
acted row receives its form through the reviewer's existing SSE stream.

| Run | Total time | Response bytes |
| --- | ---: | ---: |
| 1 | 0.639647 s | 131,547 |
| 2 | 0.505288 s | 131,547 |
| 3 | 0.540420 s | 131,547 |
| Final | 0.512033 s | 131,656 |

The final response is 96.8% smaller than the baseline. It contains 25 rows,
25 mean-star cells, 25 review-count cells, zero rating forms, page navigation
(`Page 1 of 20`), and the review-coverage summary. A direct quick-rate POST
returned HTTP 204 with an empty body; the SSE push contract is covered by the
route tests.

## Verification

- Focused board/review suite: 57 tests, 473 assertions, zero failures.
- Full unit suite: 725 tests, 8,210 assertions, zero failures.
- Application namespace compile check passed.
- `bin/e2e_drive.py` could not run against the isolated Judge Sandbox because
  that legacy driver hard-codes `data/store/events.jsonl`; the sandbox uses
  `data/store/judge-sandbox/events.jsonl`. It stopped before reaching any
  review-board action.
