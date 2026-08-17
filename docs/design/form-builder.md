# Form builder design + creation-time seed forms

*2026-08-08. Verdict: build, don't buy — for our scope (single form, ~12 field
types, one conditional pattern) this is a day or two, and the schema-as-data
approach is the demo-able heart of the tool.*

## Approach

**A form is a vector of field definitions, as data (EDN, stored as JSONB).**
One definition drives everything:
- Public form rendering: `field-def → hiccup` per type (server-rendered; Datastar)
- Validation: **malli** schemas derived from the defs (required/max/options/
  cross-field combined limits)
- Review board columns, exports, API fields: derived
- "Copy from last year": copy the EDN
- Conditional logic: `:show-if {:field :track :equals "X"}` evaluated
  SERVER-side — field change POSTs, server re-renders visible fields via SSE.
  No client rules engine.
- Builder UI: CRUD over the vector (add/remove/reorder/required/locked/private
  toggles); drag-reorder = small JS handler → postJSON (house doctrine).

**Why no library:** Java options are irrelevant (Spring binding) or heavyweight
enterprise XML (Orbeon/XForms); good builders are JS ecosystems (form.io, JSON
Forms) that drag client-side rendering into our server-rendered architecture.
malli + field-defs-as-data wins, and is itself the demo artifact.

**Design rules (scar tissue):**
1. **Snapshot field defs with each submission** — mid-CFP form edits must not
   corrupt old submissions' rendering/validation.
2. **Field IDs are forever** — labels rename freely; never re-key (the
   Sessionize track/org-size lesson).
3. Upload fields (headshot/slides) → GCS, always ATTACHED to submission/speaker.
4. Locked core fields (title, name, email) undeletable; per-field required
   toggle; `:private true` = PC-only (BusyConf's "Notes to the Planning
   Committee" split).
5. Cross-field character limits with live combined counter (printed-program
   block).

## Generic conference seed (the product default)

Every newly created conference starts with a broadly applicable CFP: session
title and abstract, audience level, format and length, prior-talk evidence,
AV/accessibility needs, and the repeatable speaker block (including bio). The
template is selected only while appending the new event's `form.installed`
fact. It never rewrites an existing event's form or a submission's snapshotted
field definitions.

The EAIS Charlotte questionnaire below is intentionally separate. Demo and
Enterprise AI fixture creation select it explicitly; ordinary event creation
must not inherit its industry, organization-size, or AI-transformation fields.

## The EAIS Charlotte seed form (dogfood data for the golden path)

| # | Field | Type | Req | Notes |
|---|---|---|---|---|
| 1 | Talk title | text 255 | ✓ | locked |
| 2 | Abstract | markdown 5000 | ✓ | published if accepted |
| 3 | Session format | select: Experience Report / SME talk / Panel | ✓ | review lens |
| 4 | Organization size | select: <1k / 1–10k / >10k | ✓ | own field at last — no slot-repurposing |
| 5 | Industry | select | ✓ | enterprise curation |
| 6 | When did your org begin its AI transformation, and what is deployed in production today? | textarea | ✓ | BusyConf interrogation lineage, AI-era |
| 7 | What measurable outcomes can you share? (adoption %, cycle time, cost, revenue) | textarea | ✓ | the vapor detector |
| 8 | What would you tell a peer CTO to do differently? | textarea | – | the so-what |
| 9 | Business co-presenter? (name/title, or would you add one?) | text | – | the PC's most repeated conditional-accept, as a question |
| 10 | Link to a video of a prior talk | url | – | stage-skill evidence (restored from BusyConf) |
| 11 | Notes to the Planning Committee | textarea, PRIVATE | – | public/private split restored |
| — | Speaker block (repeatable): name*, email*, title/tagline*, org*, bio*, headshot, LinkedIn, "Have a Sessionize profile? Paste the URL" → prefill | | | one owning account; co-speakers as rows |

Settings: close date + reminder email · 3-submissions-per-person cap (printed on
public page) · confirmation email from a real person's address · success page →
portal handoff ("make sure this works" — swyx).

Provenance: Sessionize ETLS-2025 scrape (what survived), BusyConf 2021 New
Proposal emails (the lost interrogation questions), PC-channel evidence
(conditional-accept patterns), swyx Q&A (single form + track options).
