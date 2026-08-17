# Explainable Review Policy — acceptance test plan

**Feature:** `77s` Explainable Review Policy

**Buster source:** `feature/cc-explainable-policy` at `be372623e3c3ec994d8702ae89ba47858ccc5d78`

**Audience:** Gene as product acceptor, with one chair and two independent reviewer sessions

**Goal:** prove the policy is understandable, versioned, event-scoped, and incapable of leaking one reviewer's reveal to another.

## Automated evidence already in hand

| Slice | Focused receipt | Full receipt |
|---|---:|---:|
| `77s.1` policy algebra | 10 tests / 79 assertions | 378 / 3,839 |
| `77s.2` Board + public CFP | 17 / 168 | 380 / 3,859 |
| `77s.3` API + LLM/MCP | 12 / 136 | 381 / 3,879 |
| `77s.4` adversarial meter | 1 / 9 | **382 / 3,888** |

All Buster receipts are green. CI on the integrated mainline remains the final automated gate.

## Product-contract ratification before the tour

The implementation is internally consistent, but these v1 choices are narrower than the earliest proposal. Decide whether each is acceptable before calling the feature fully lit:

- [ ] **API shape:** accept singular `GET/PUT /api/v1/events/:slug/review-policy`. The earlier proposal used a collection at `/policies` plus `/:policy-id`.
- [ ] **Concurrency shape:** accept JSON `expectedVersion` in the PUT body. `If-Match` is not implemented.
- [ ] **Policy scope:** accept one self-describing mutable policy, `review.presenter-visibility`. Fixed `decision.notification` and `program.publication` catalog entries remain future work.
- [ ] **Visual grammar:** accept the prominent policy message/card headed `Presenter visibility · <mode>`. It carries state/version data attributes, but is not a separate compact pill component.

If any box fails ratification, keep the feature in 🧪 and file one outcome-level follow-up rather than papering over the difference during the tour.

## Setup

Use an event whose proposal data can safely be manipulated. Do not use production.

```text
Base URL:          ______________________________
Event slug:        ______________________________
Chair session:     ______________________________
Reviewer A:        ______________________________
Reviewer B:        ______________________________
Submission ID:     ______________________________
Original mode:     ______________________________
Original version:  ______________________________
```

Fixture requirements:

1. Chair, Reviewer A, and Reviewer B are three distinct people on the same event.
2. One proposal has a distinctive presenter name and organization, for example:
   - presenter: `ZZZ Canary Speaker`
   - organization: `ZZZ Canary Labs`
3. The proposal title and abstract do **not** contain those identity strings. The policy explicitly cannot redact identity typed into free text.
4. Neither reviewer has rated the proposal before the test.
5. Keep three isolated browser contexts open: chair, A, and B. An incognito window alone is insufficient if cookies are shared.

Record the starting resource:

```bash
export BASE='http://localhost:8080'
export SLUG='<event-slug>'

curl -fsS "$BASE/api/v1/events/$SLUG/review-policy" | tee /tmp/review-policy-before.json | jq .
```

Expected top-level shape:

```json
{
  "event": {"id": "...", "slug": "..."},
  "definition": {
    "id": "review.presenter-visibility",
    "mutable": true,
    "allowed-modes": [],
    "guarantees": [],
    "limitations": [],
    "explanation": {}
  },
  "policy": {"mode": "visible", "version": 0}
}
```

## Tour 1 — one glance explains the active policy

As the chair, open:

```text
/events/<slug>/board
```

- [ ] The Review Board contains a `Review plan` surface.
- [ ] It contains `Presenter visibility · <current label>` without navigating elsewhere.
- [ ] It explains before-vote and after-vote identity behavior.
- [ ] It shows `Policy version N`.
- [ ] All three choices are present:
  - `Visible to reviewers`
  - `Hidden throughout review`
  - `Hidden until each reviewer votes`
- [ ] The current choice is selected.
- [ ] The explanation is understandable without knowing the words “projection,” “fact,” or “event sourcing.”

Open the public CFP in a signed-out context:

```text
/cfp/<slug>
```

- [ ] The same mode label and version are visible.
- [ ] The submitter-oriented explanation says when reviewers can see identity.
- [ ] The public explanation agrees exactly with the chair's selected mode.

## Tour 2 — visible mode

Set `Visible to reviewers`, save, and reload all three sessions.

- [ ] The version increments by exactly one if the mode changed.
- [ ] Reviewer A sees `ZZZ Canary Speaker` and `ZZZ Canary Labs` before rating.
- [ ] Reviewer B sees the same identity before rating.
- [ ] The chair sees identity.
- [ ] Searching the Board for `ZZZ Canary` finds the proposal.
- [ ] The public CFP says identity is visible throughout review.

Save `visible` again without changing it:

- [ ] The request succeeds.
- [ ] The version does not increment.
- [ ] No user-visible duplicate action or error appears.

## Tour 3 — hidden throughout review

Set `Hidden throughout review`, then reload A and B.

- [ ] The version increments once.
- [ ] Both reviewers see `Anonymous speaker` on the Board and proposal detail.
- [ ] Neither page source contains the presenter name, email, organization, biography, LinkedIn URL, source, co-presenter identity, or identity-bearing custom answers.
- [ ] Searching for `ZZZ Canary` returns no proposal and does not alter the result count in a revealing way.
- [ ] Sorting by speaker or organization does not create an identity-correlated ordering oracle; hidden rows use the same anonymous/empty projected keys.
- [ ] A rating does not reveal identity in `hidden` mode.
- [ ] A comment does not reveal identity.
- [ ] The chair still sees identity.
- [ ] Returning to hidden after identity was previously visible shows the documented limitation: software can hide the fields again, but cannot make a reviewer forget them.

## Tour 4 — hidden until each reviewer votes

Use a fresh unrated proposal or remove prior test ratings. Set `Hidden until each reviewer votes`.

Before either reviewer votes:

- [ ] A sees `Anonymous speaker` and no identity-bearing source text.
- [ ] B sees `Anonymous speaker` and no identity-bearing source text.
- [ ] The chair sees the real identity.
- [ ] The policy card says the reveal trigger is the first rating.

Have Reviewer A add a comment without rating:

- [ ] A remains anonymous.
- [ ] B remains anonymous.

Have Reviewer A submit the first persisted rating:

- [ ] A's next Board/detail render reveals `ZZZ Canary Speaker`.
- [ ] B remains anonymous after reload.
- [ ] The chair remains visible.
- [ ] Searching `ZZZ Canary` as A can find the revealed proposal.
- [ ] The same search as B cannot find it.

Have Reviewer B submit a rating:

- [ ] B now sees the presenter.
- [ ] The policy version is unchanged; reviewer actions reveal projections but do not mutate policy configuration.
- [ ] The authoritative submission content is unchanged by either reveal.

This is the decisive demo. If A's rating reveals identity to B, stop immediately: the feature fails regardless of every other green check.

## Tour 5 — self-describing API and optimistic concurrency

The GET is public:

```bash
curl -fsS "$BASE/api/v1/events/$SLUG/review-policy" | jq .
```

- [ ] `allowed-modes` contains exactly `visible`, `hidden`, and `reveal-after-vote`.
- [ ] Every mode includes label, meaning, before/after behavior, search behavior, and sort behavior.
- [ ] `reveal-after-vote` names `first-recorded-rating` as its reveal trigger.
- [ ] The resource includes guarantees, limitations, and reviewer/submitter/organizer explanations.
- [ ] One GET is sufficient for an unfamiliar client or LLM to explain every choice.

For PUT, use a chair's session cookie without copying it into this document:

```bash
export CHAIR_COOKIE='<session cookie from the chair browser>'
export VERSION="$(curl -fsS "$BASE/api/v1/events/$SLUG/review-policy" | jq -r '.policy.version')"

curl -fsS -b "$CHAIR_COOKIE" \
  -X PUT "$BASE/api/v1/events/$SLUG/review-policy" \
  -H 'content-type: application/json' \
  --data "{\"mode\":\"hidden\",\"expectedVersion\":$VERSION}" | jq .
```

- [ ] A valid chair write returns `200` and the complete updated resource.
- [ ] A real mode change increments version exactly once.
- [ ] Repeating the same mode at the current version is a no-op and preserves the version.
- [ ] Reusing a stale version for a different mode returns `409` with type `stale-policy-version`.
- [ ] Signed out returns `401`.
- [ ] A signed-in non-chair returns `403`.
- [ ] Missing or non-integer `expectedVersion` returns `422`.
- [ ] An unknown mode returns `422`, type `invalid-presenter-visibility`, and advertises the allowed values in `details`.
- [ ] Changing Event A never changes Event B's mode or version.

## Tour 6 — LLM-readable surfaces

Open:

```text
/api/v1/events/<slug>/docs
/events/<slug>/llms.txt
```

- [ ] Generated API docs list both GET and PUT with auth and parameter notes.
- [ ] `llms.txt` links directly to the review-policy resource.
- [ ] Neither document invents a fourth mode or promises free-text redaction.

Exercise MCP discovery and the public read tool:

```bash
curl -fsS -X POST "$BASE/events/$SLUG/mcp" \
  -H 'content-type: application/json' \
  -H 'accept: application/json' \
  --data '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | jq .

curl -fsS -X POST "$BASE/events/$SLUG/mcp" \
  -H 'content-type: application/json' \
  -H 'accept: application/json' \
  --data '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_review_policy","arguments":{}}}' | jq .
```

- [ ] `tools/list` advertises `get_review_policy` with no model-controlled event slug argument.
- [ ] The call works signed out because it is read-only and public.
- [ ] Its mode, version, allowed modes, guarantees, limitations, and explanations match the HTTP GET.
- [ ] There is no LLM/MCP mutation tool for changing policy.

Ask an LLM, using only the returned MCP/HTTP JSON:

```text
Explain this event's presenter-identity policy to:
1. a reviewer before voting,
2. a submitter deciding whether to submit,
3. the organizer changing the mode.
State the reveal trigger, guarantees, and limitations. Do not infer facts absent from the resource.
```

- [ ] The explanation distinguishes all three audiences.
- [ ] It says a rating—not a comment—triggers per-reviewer reveal.
- [ ] It does not claim that free-text identity leaks are automatically redacted.
- [ ] It does not claim that one reviewer's reveal affects another reviewer.

## Cleanup and evidence receipt

Restore the original mode using the latest version. Do not reuse the starting version.

Capture:

```text
Integrated commit/SHA:        ______________________________
CI URL and conclusion:        ______________________________
Event slug:                   ______________________________
Starting mode/version:        ______________________________
Ending mode/version:          ______________________________
Chair screenshot:             ______________________________
Public CFP screenshot:        ______________________________
A before/after screenshots:   ______________________________
B before/after screenshots:   ______________________________
GET JSON:                     ______________________________
409 response JSON:            ______________________________
MCP response JSON:            ______________________________
Ratification deltas accepted: ______________________________
Unexpected behavior/bead:     ______________________________
Final verdict:                PASS / FAIL / PASS WITH FOLLOW-UP
```

The feature moves from 🧪 to ✅ only when CI is green, the two-reviewer isolation demo passes, the original mode is restored, and the four v1 contract choices at the top are explicitly accepted.
