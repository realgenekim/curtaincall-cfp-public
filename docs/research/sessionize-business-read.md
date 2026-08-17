# The Sessionize business read

## 1. How we measured the event-creation rate

Sessionize assigns each new event record an integer ID from one global counter.
Evidence: our team created DOES Virtual EU 2024 and ETLS Vegas 2024 in one
sitting. Their IDs are 13507 and 13508 — consecutive. One global counter is the
only simple explanation.

Each event we created is therefore a sample of the platform. Our Slack archive
dates each creation to within about one month. Two (ID, date) points give the
platform-wide creation rate between them:

- 7171 (Feb 2022) to 10643 (Jan 2023): 3,472 events in ~11 months ≈ 3,500/yr
- 10643 (Jan 2023) to 13508 (Feb 2024): 2,865 events in ~13 months ≈ 2,600/yr
- 13508 (Feb 2024) to 18388 (Mar 2025): 4,880 events in ~13 months ≈ 4,500/yr

This is the German tank problem. In WWII, Allied statisticians estimated tank
production from serial numbers on captured tanks, and beat the spy estimates.
Sequential IDs leak production rates.

## 2. How we estimated revenue

Sessionize pricing has three tiers: free for community events, $499 +tax per
event occurrence (Professional), and negotiated bulk for 5+ events/yr.

Revenue = events/yr × paid share × ~$499. We do not observe the paid share, so
we bracket it at ~4,000 events/yr:

| Paid share | Paid events/yr | Revenue/yr |
|---|---|---|
| 10% | 400 | ~$200k |
| 30% | 1,200 | ~$600k |
| 50% | 2,000 | ~$1M |

We call 30% generous. The free tier is designed to capture the long tail —
meetups, user groups, community conferences — and the long tail is most of the
volume. Bulk discounts pull the average price below $499. Central estimate:
**$500–700k/yr**. This matches the visible company size: Sessionize appears to
be a one-to-two person shop. When Gene emailed support in 2024, the founder
replied personally.

## 3. What the number explains

A ~$500k/yr product cannot afford:

- **An organizer API.** An API serves existing customers and creates support
  cost. It earns no new revenue. The only API is the attendee-embed JSON,
  because that one markets the product. Result on our side: a 654-line
  headless-Chrome scraper and 1,700 GitHub Actions runs.
- **Review-UX improvement.** The evaluation-plan flow did not change in our four
  years of use. Customers do not churn over it, because there is nowhere to go.
- **A second maintainer.** BusyConf died of the same arithmetic. In January
  2022, Ryan McGeary priced its survival at $3,450/mo from us alone. The
  category could not sustain one maintainer through a Heroku repricing.

Conclusion: standalone CFP tooling is a revenue desert. Below it sit free tools
(Google Forms, community platforms). Above it sit enterprise suites (Cvent,
Bizzabo, Sessionboard) that bundle CFP into a $40k–$200k platform sale. A good
standalone program tool at SMB prices cannot fund itself as a SaaS. Occupants
stay tiny (Sessionize), die (BusyConf), or move upmarket (Sessionboard).

## 4. Why this is the moat swyx's contest attacks

Sessionboard's $40k price does not buy $40k of software. It buys the absence of
an alternative. The moat is scarcity, not capability. swyx's bet: AI coding
repriced "build" from months of engineering to a weekend plus ~$500 of tokens.
When build cost collapses, scarcity-priced categories fall first. He pays $10k
to test whether open source built in 72 hours replaces a $40k/yr contract.

The demand side sharpens the story. Our session-ID samples show platform-wide
submissions rising from ~145k/yr (2022–2024) to ~265k/yr into 2025 — near
double. More conferences than ever run on tooling that nobody can afford to
improve. That gap is where the contest, and our tool, enter.

## 5. Error bars

- The estimate assumes dense IDs: no sharding, no large gaps. The 13507/13508
  adjacency and the smooth year-over-year curve support this. It is not proven.
- Creation dates are ±1 month.
- The paid share is a prior, not a measurement.
- Combined, the revenue estimate can move 2–3× in either direction. The
  conclusion needs only the order of magnitude: $300k or $1.5M both mean a
  category too small to defend itself against free.
- The `:submitted-date` fields in the copied GCS data can tighten the
  submission-rate curve if the writeup needs it.
