# Judge Sandbox fixture

`events.jsonl` is the immutable golden world shown by the three Judge Sandbox
personas. It contains an adjusted Enterprise AI Summit — Charlotte call with
500 synthetic submissions and a five-person programming committee.

The current specimen is a deliberately adjusted snapshot of the live
Enterprise AI Summit — Charlotte event. `enterprise-ai-summit.edn` records the
public source facts, real announced-speaker roster, and real committee. The
review corpus is different by design: every proposal and submitter is
synthetic, so the sandbox never attributes invented words to a real person.

Normal startup never appends to this resource. `make judge-sandbox` copies it
to `data/store/judge-sandbox/events.jsonl`; all sandbox interactions append to
that disposable runtime copy. Restarting the sandbox restores the golden file
byte-for-byte.

Regenerate the golden file explicitly with `make regenerate-judge-sandbox`.
The Makefile invokes the `clj -X` entrypoint in `src/cli/judge_sandbox.clj`;
the generator uses the application's domain verbs and suppresses external mail.
