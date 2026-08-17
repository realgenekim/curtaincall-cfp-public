# 2026-08-09 — Mid-deployment survey: was the code an investment or theater?

*Gene asked (qq, while the Postgres one-shot ran in the background): how much
code exists, and is it durable investment or contest throwaway?*

**Volume, day 2 of the contest, 94 commits:** ~13.4k lines of app source,
~8.1k of tests/drivers/probes, ~1.1k CSS/JS, ~7.4k of design + research docs.
~30k lines total.

**Verdict: almost none of it is theater.** Judged by "does ITRev still want
this in 2027": the app source is the decade-long Sessionize/Sched replacement
itself; the event-log fold/replay layer survives the in-flight Postgres swap
untouched — the swap being a one-day background job is itself evidence the
architecture was an investment. The test estate found four real
"instrument-lied" bugs this weekend and is why the pace is possible. The
research docs are both the product spec and chronicle ore — the swyx letter,
the tips series, and the writeup were all already mined from them.

**Honest caveats recorded:** contest-shaped effort (demo doors, boot-seed,
replay, rubric-chasing) ≈ 10–15%, but demo machinery doubles as sales-demo
machinery. Consumed-by-design: mockup HTMLs (hours of life, did their job),
Discord transcripts, the rubric gap analysis (contest-scoped but re-aimed two
days of work). The real payoff test is not Wednesday's judging — it is whether
Ann runs Charlotte in this instead of Basecamp; today's wizard, seed
questions, and honest validation were aimed at her.

**Flywheel accounting (the Prime Directive test):** LIVE = the constellation
built it; CHRONICLE = ethnographies + these logs; TEACH = tips series, open
letter; SELL = the contest entry + the real Charlotte CFP. All four edges fed;
work feeding no edge ≈ nil. Flywheel-correct.

*Context at time of writing: create page/dashboard/form page shipped and
Chrome-verified; demo instance live on Cloud Run; Postgres event-store
one-shot mid-build; next: deploy flip → real Charlotte CFP.*

---

## Addendum: the question Gene was actually asking (three tries to land it)

Gene's real question: *yesterday's* code — is today's redesigned app using any
of it, 1 (purge it all, never looked) to 10 (it skeletonized the app we now
have)? Gene expected the answer to be low. **Answer: 9 — and he called the
result "crazy, that is not what I expected at all."**

The evidence: yesterday's code is not being referenced, it is RUNNING on the
live URL. The store/fold, the Review Board and its two-sorts doctrine, the
7-status + Notified flow, inform letters, scheduler, exports, import, the e2e
driver — all untouched by the redesign sprint and serving every request.
Overnight's auth gate and form builder are today's load-bearing walls. Even
the new wizard navigation was *derived* from yesterday's dashboard checklist,
which already tracked the true states — today promoted that skeleton into
navigation rather than inventing one.

Deducted point: the create page's presentation layer was superseded same-day,
the CFP opens-at concept was killed in redesign, and the default Fomantic look
is being painted over. Structural discard ≈ zero.

The lens worth keeping: **nine consecutive green test runs during a furious
all-day reshape is what reuse looks like** — we bent yesterday's skeleton all
day and never re-grew a bone. The perceived "we're redoing everything" came
from the only layer Gene could see — pixels — which was precisely the layer
designed to be cheap to change (tokens, server-rendered views, tests
underneath).
