# Captain's Log — local CI during the GitHub Actions outage (2026-08-17, ~10:15–10:45 PT)

*Mid-morning, GitHub declared a major outage: Actions and API down, git
operations degraded. Our CI-is-the-lane doctrine suddenly had no lane — runs
queued but never executed, and auto-promote (which drinks from CI's green)
went correctly idle. Gene: "For this emergency, manually run the CI locally —
run in /tmp and simulate a CI run… Make sure you can do via Makefile."*

## The emergency lane, built in one commit

`make ci-local` (191316e): clean `git clone --no-local` of the pinned staging
tip into `/tmp/ci-local/<sha>/`, then the exact two invocations the workflow
runs — `bin/kaocha unit` and `bin/kaocha ci --fail-fast` — with the verdict
written to `/tmp/ci-local/<sha>/VERDICT` as `GREEN <sha>` or `RED <sha>`.
Same bytes CI would have tested, because it clones the commit, never a
working tree.

## The first run RED-gated the Mayor's own corner-cut

First verdict: `RED 191316e — 3 failures`, all in the intent-witness
architecture suites. Cause: the Mayor had pinned the new NAV-006 rail link
(cross-event Speaker CRM) as a `testing` block inside an existing deftest,
while the registry declared a witness named `:nav-rail-links-speaker-crm` —
and the witness contract demands a deftest by exactly that name, exactly
once. The machine refused the shortcut; the fix (9ddc940) wrote the
standalone deftest. Second run: `GREEN 9ddc940`.

There is no better advertisement for the architecture tests than the
emergency lane's first-ever verdict catching the person who built it.

## Deploy continuity

On GREEN, the pre-CI local deploy lane took over, unchanged since it was
retired to fallback status: `make deploy-staging` (jib build FROM the pinned
clone, tagged 0%-traffic revisions on both services) → verify staged URLs →
the guarded `make promote-staging-to-production EXPECTED_SHA=… PROMOTE=YES`,
Gene-ordered. Production never depended on GitHub being up; only our
*confidence machinery* did, and now that has a local twin.

When Actions recovers, the queued runs execute and the receipt-aware
auto-promote loop reconciles — it skips anything the local lane already
promoted (promotion receipts are the dedupe key).

## Doctrine takeaways

1. **The lane is the ceremony, not the vendor.** CI-builds-from-the-commit
   survived translation to `/tmp` because the invariant was never "GitHub
   runs it" — it was "test the exact bytes of the pinned commit."
2. **Verdict files over exit codes**: `GREEN <sha>` in a file is a receipt a
   later step can gate on; a terminal scrollback is not.
3. **Gates that catch their own author are the only gates worth having.**
