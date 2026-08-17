# The replay corpus — `aie-corpus.json`

Demo-simulator input: a three-week CFP season, compressed. The engine walks
`timeline` in order and replays each event at its `offset-secs`, so a judge
watches submissions arrive, ratings land, committee members argue, and talks
move into the Accept and Decline queues — on a real board, through the real
mutations.

## Provenance

Adapted from **swyx's own published conference program**: `sessions.json` and
`speakers.json` from `https://www.ai.engineer/worldsfair/`, fetched
**2026-08-08** (561 sessions, 552 speakers, `scheduleVersion` 4945). Using the
customer's own data means the shapes are right: how long an abstract really
runs, how many talks are vendor-adjacent, what a multi-speaker session looks
like, how uneven the industry mix is at a real AI event.

- **32 submissions are adapted from real sessions.** Title and abstract keep
  the source talk's claims and structure; long abstracts are trimmed to
  abstract length (mean 649 chars, max 974). Everything the EAIS form asks for
  and a conference program does not — org size, industry, transformation
  history, measurable outcomes, advice to a peer CTO, business co-presenter,
  prior-talk video, private notes to the committee — is written fresh, grounded
  in the claims the abstract already makes.
- **8 submissions are wholly invented**: the vendor pitches, consultant
  frameworks, the crypto-adjacent one, and the "we'll reveal the product on
  stage" one. Every planning committee has seen all eight.

## The anonymization rule

> **Zero real people and zero real organizations. Titles and abstracts are
> adapted from the public program; every human and every company in this file
> is invented.**

Concretely:

1. **Every speaker is renamed** to an invented, plausible, deliberately diverse
   name. No real full name, and no real speaker's *surname*, appears anywhere.
2. **Every organization is renamed**, keeping the *flavor* of the original: a
   bank stays bank-shaped (`Halverston Global`), a research lab stays lab-shaped
   (`Hollerith Labs`), a vector database stays infra-shaped (`Vectorra`), a
   hyperscaler stays hyperscaler-shaped (`Northgate Cloud`). Sector, size, and
   posture survive; identity does not.
3. **Vendor product and model names are renamed or genericized** — a product
   name identifies its company as surely as the company name does. Frontier
   model names become "a frontier model"; named third parties in the abstracts
   ("a global legal-information publisher") lose their names too.
4. **Company mentions inside abstracts are rewritten**, not just the metadata:
   "at Morgan Stanley's research lab" became "in our Machine Learning Research
   Lab" under the invented bank.
5. **All emails are fictional and end in `.example.com` / `.example.org` /
   `.example.io`.** Only the three PC reviewer addresses are real
   (`genek@`, `annp@`, `alex@itrevolution.net`) — they are the demo's operators,
   not subjects.
6. **No snark about a real person.** Committee comments are sharp by design, and
   every target of one is fictional.
7. **Generic technology terms are kept** (Kubernetes, Python, Rust, MCP, BM25),
   including where a company happens to share the word. A lowercase English word
   that is also somebody's company name ("daily", "elastic", "boundary") is not
   a leak; a capitalized occurrence would be, so the check is case-sensitive.

17 of the 40 titles are kept verbatim from the public program — that is
deliberate. The title is the part a program committee argues about, and
inventing 40 new ones would have produced 40 titles that sound like an LLM
wrote them. The talks behind those titles are attributed to nobody real.

### Verification

`selfcheck.py` is the enforcement, not the promise. It builds the forbidden set
from the *source files themselves* — every real speaker full name, every real
surname, every real company, plus a hand-added list of third-party brands and
products seen in the adapted abstracts — and greps the built corpus for all of
them, case-sensitively, on word boundaries. It also asserts every email is
fictional, every review event lands strictly after its submission exists, offsets
are monotonic, and star ratings are valid halves in 1.0–5.0.

The check needs the two source files next to the scripts (they are not committed —
1.5 MB of someone else's program):

```
cd resources/replay
curl -sLO https://www.ai.engineer/worldsfair/sessions.json
curl -sLO https://www.ai.engineer/worldsfair/speakers.json
python3 build_corpus.py && python3 selfcheck.py    # exit 0 = clean
```

Last run: **clean** — 0 forbidden-term hits, 0 non-`example` emails, 0 orphaned
review events.

## Format

```jsonc
{
  "meta": { "source": "…", "anonymization": "…", "count": 40, … },
  "timeline": [
    { "offset-secs": 0, "kind": "submission",
      "submission": { "answers": { "talk-title": "…", "abstract": "…", … },
                      "speaker":  { "name": "…", "email": "…@example.com",
                                    "title": "…", "org": "…", "bio": "…" } } },
    { "offset-secs": 21600, "kind": "rating",
      "on-title": "…", "by": "genek@itrevolution.net", "stars": 4.5 },
    { "offset-secs": 25200, "kind": "comment",
      "on-title": "…", "by": "annp@itrevolution.net", "body": "…" },
    { "offset-secs": 90000, "kind": "status",
      "on-title": "…", "by": "genek@itrevolution.net", "to": "Accept Queue" }
  ]
}
```

- `answers` is keyed by **field id** from the EAIS Charlotte seed form
  (`src/cfp_scheduler_killer/seed.clj`), never by label — field ids are forever.
  Optional fields (`advice-to-peer`, `business-co-presenter`,
  `prior-talk-video`, `notes-to-committee`) are **absent** when the speaker left
  them blank, exactly as a real submission stores them.
- `speaker` is singular: one owning speaker per submission, matching the create
  path in `submissions.clj`. Co-presenters live where they live in real life —
  in the speaker bio, in `business-co-presenter`, or in a note to the committee.
- `on-title` joins a review event to its submission by talk title (titles are
  unique in this corpus, and it keeps the file readable).
- `offset-secs` runs 0 → ~1,810,000 (just under 21 days). The CFP closes at the
  end of day 18; days 19–20 are the committee's post-deadline scramble. The
  engine compresses the window; the *shape* is what matters.

## What the timeline is shaped to demonstrate

| | |
|---|---|
| **40 submissions** | 32 adapted + 8 invented vendor pitches |
| **Arrival curve** | 12 in the first 48 hours, 20 trickling across days 3–17, **8 on day 18 in the hours before close** — the deadline-day spike every CFP has |
| **190 events** | 40 submissions · 71 ratings · 72 comments · 7 status moves |
| **28 reviewed / 12 untouched** | the "who still needs reviews?" work queue has a real top, mostly deadline-day arrivals |
| **4 genuine splits** (≥2.0 spread, with dueling comments) | *Rise of the Software Factory* (2.0/3.0/4.0) · *In Code They Act, In Proof We Trust* (2.5/4.0/4.5) · *State of the Union: Why Local, Why Now* (2.0/3.5/4.5) · *Something Big Is Coming* (1.0/1.5/3.5) |
| **7 status moves** | 4 → Accept Queue, 3 → Decline Queue (all three declines are vendor pitches) |
| **Formats** | 21 Experience Report · 17 SME talk · 2 Panel |
| **Org size** | 18 `<1,000` · 10 `1,000–10,000` · 12 `>10,000` |
| **Private field in play** | 16 submissions carry `notes-to-committee` — co-speaker churn, AV demands, embargo requests, compliance delays |
| **25 of 40** | no prior-talk video, so "stage-skill evidence" is genuinely missing where it matters |

Comment voice follows the house snark documented in
`docs/research/cfp-flow-comparison.md`: professional, punchy, process-focused.
"Vendor pitch IMHO." "Outputs, not outcomes." "Would rate higher with a business
co-presenter and actual numbers." "Saw her at another conference — strong on
stage." "[pc] Can we land one of their client CIOs instead?" The vendory eight
get the best lines, because they earned them.

## Regenerating

`build_corpus.py` holds the corpus as source (the rename map is at the top and
doubles as the audit trail) and emits `aie-corpus.json` with deterministic
offsets (`random.seed(20260808)`), so a rebuild is byte-identical. Editing the
JSON by hand is fine for a one-off; editing the builder is how a change survives.
