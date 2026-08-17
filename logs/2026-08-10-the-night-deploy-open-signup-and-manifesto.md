# Captain's log 2026-08-10 (night) — open sign-up + manifesto to PRODUCTION, both services

**What was driven:** the full staging train, Gene + laptop Fable pair, ~10:00pm–11:00pm PT.
Deadline context: Kill My SaaS judging Tue Aug 12 10pm PT; an ai.engineer team member bounced
off the locked door at 3pm.

## What shipped (staging branch, pushed to github.com/realgenekim/curtaincall-cfp)

1. `ba68a2c` bridge's staging Makefile lane (deploy-staging / promote-staging / traffic)
2. `ea18151` bridge's manifesto page (/manifesto, public, cube-vs-table)
3. `53e7dbb` **open sign-up**: Google callback admits ANY verified identity via
   find-or-create-person! — roster gate + refused-page deleted (RATIFIED model,
   docs/open-signup.md). Magic-link path deliberately untouched.
4. Test fix: view-architecture test globs files (clj-kondo v2023 dir-walk returns
   0 files in worktree checkouts — same bug mothership documents).
5. Makefile perfection: preflight-deploy + rollback-staging (see below).

Suite gate: **262 tests / 2,707 assertions / 0 failures** (worktree, unsandboxed).

## Deploy receipts

- Demo svc revision **swyx-cfp-saas-killer-00018-dis** → 100% traffic
- **curtaincallcfp-00020-row** → 100% traffic (same image; env inherited)
- Rollback points recorded: 00015-r5p / 00019-8rc (`make rollback-staging`)
- Post-flip verification: `/`, `/manifesto`, `/login`, Charlotte CFP slug,
  AIE agenda + llms.txt — all 200 on BOTH main URLs.
- Google OAuth app **published to production** (Gene's click, ~10:04pm) —
  Testing-mode test-user list no longer applies.

## The three silent boot-killers (two failed revisions before green)

Revisions 00016-mis and 00017-yed died at startup probe. Causes, in order found:
1. `session-cookie-key` secret did not exist anywhere (pending Phase-0 item;
   the new cookie-store code at HEAD is the first to REQUIRE it at boot).
2. Demo service lacked `GCP_PROJECT` env var → secrets.clj:18 silently fell back
   to project **does2020** → cross-project 403 (bead 0a1: kill the fallback).
3. First minted secret was 64 bytes; server.clj cookie-key-bytes demands EXACTLY 16.

Each was found by a 0%-traffic tagged revision. **A direct-to-prod deploy would
have been an outage all three times.** The staging lane paid for itself on its
first night. Now encoded: `make preflight-deploy` checks all three in seconds
and gates every deploy-staging.

## Isolation discipline (Sol/cfp3 ran throughout, undisturbed)

All work in git worktree `../curtain-call-staging`; shared tree untouched except
read-only git ops. Sol's uncommitted refactor: two zero-touch checkpoint commits
on `sol/big-refactor` (0fb346a, 6a0e514), pushed. Sol commits for real at its
green; reconciliation with staging is tomorrow's small merge.

## Still open tonight/tomorrow

- Gene's stranger test (incognito + personal Gmail → create event).
- DM swyx the URL (Discord protocol).
- DNS: Cloudflare account + curtaincallcfp.com + domain mapping (Gene + skiff).
- Tomorrow's promotion train: Sol's refactor at green; bridge operator-admin;
  api-cli wave (2,932 lines, review first); new landing hero (art №35 uncrowned).
- Beads filed tonight: qfp (chair/admin naming), dfj (ssk→curtaincall rename),
  1ia (manifesto inline styles), 0a1 (does2020 fallback).
