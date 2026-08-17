#!/usr/bin/env python3
"""Build resources/replay/aie-corpus.json for the demo simulator.

Source: ai.engineer World's Fair 2026 published program (sessions.json /
speakers.json). Titles + abstracts are ADAPTED; every person and organisation
is replaced with an invented one (see RENAME_MAP, which is also the grep list
for the self-check).
"""
import json, os, random, datetime

random.seed(20260808)

# --- The rename map: every real name/org/product we saw -> what replaced it. --
# This doubles as the self-check grep list.
RENAME_MAP = {
    # organisations
    "Figma": "Kartograph", "Morgan Stanley": "Halverston Global",
    "Abridge": "Stethos AI", "Factory": "Foundry Loop",
    "NVIDIA": "Cerebrix Systems", "Arize": "Telemetrix AI",
    "Leibniz Labs": "Hollerith Labs", "Google": "Northgate Cloud",
    "Google DeepMind": "Northgate Research", "Clay": "Ledgerline",
    "Stripe": "Conduit Payments", "Daytona": "Ephemera Cloud",
    "Pinecone": "Vectorra", "DatologyAI": "Curata Labs",
    "LlamaIndex": "Docugraph", "Notion": "Palimpsest",
    "Meta": "Continuum Networks", "Roboflow": "Visionfold",
    "EXO Labs": "Latchkey Labs", "Exo Labs": "Latchkey Labs",
    "Osmantic": "Homebrew Intelligence", "Forward Future": "Frontcast Media",
    "Neo4j": "Graphwright", "Yutori": "Peregrine Labs",
    "Braintrust": "Rubricon", "Moonlake AI": "Loomstate AI",
    "Moonlake": "Loomstate", "Docker": "Cratewright", "Uber": "Wayfinder Mobility",
    "Intuit": "Ledgerhouse Financial", "Hinge Health": "Kinetiq Health",
    "Form3": "Sterling Rails", "WorkOS": "Gatepost", "DSPy": "Sigil",
    "cmpnd": "Colophon Labs", "AlixPartners": "Harbord & Vane",
    "Acrew Capital": "Thistledown Ventures", "Pydantic": "Ironbark",
    "Hornet.dev": "Lexeme Search", "Trajectory": "Longrun Labs",
    "Amazon AGI Lab": "Halcyon AGI Lab", "UC Berkeley": "Mereton University",
    "UCAL Berkeley": "Mereton University", "Thomson Reuters": "(genericised)",
    "Arcee": "(genericised)", "Anthropic": "(genericised)",
    "OpenAI": "(genericised)", "Cloudflare": "(genericised)",
    "Firecrawl": "(genericised)", "monday.com": "(genericised)",
    "Conviction": "(genericised)", "Apple": "(genericised)",
    "Stanford HAI": "(genericised)", "Daily": "(dropped)",
    # products / model names
    "AlphaLab": "AtlasLab", "Autopilot": "Trailhead", "Alyx": "Nova",
    "Automind": "Provenant", "Universalis": "Verum",
    "MCP Toolbox": "Tool Bridge", "Gemini Code Assist": "(genericised)",
    "Gemini": "(genericised)", "Antigravity": "Updraft",
    "Scouts": "Sentries", "Monty": "Marlin", "PatchPilot": "Mender",
    "auth.md": "agent-identity.md", "OpenClaw": "(genericised)",
    "Uber Eats": "(genericised)", "GPT-5": "(genericised)",
    "GPT 5.4": "(genericised)", "Opus 4.6": "(genericised)",
    "Claude": "(genericised)", "BrowseComp-Plus": "(genericised)",
    "ShadowRay": "(genericised)", "CL-Bench": "(dropped)",
    "CrabRAG": "GraphMind",
    # people (real speaker -> invented speaker)
    "Jesse Lumarie": "Devon Achebe", "Brendan Rappazzo": "Anders Mollegaard",
    "Chaitanya Asawa": "Nikhil Ramaswamy", "Tereza": "Marta Kowalczyk",
    "Lovina Dmello": "Ifeoma Nwachukwu", "Jason Lopatecki": "Grant Whelan",
    "Erik Meijer": "Sander de Vries", "Averi Kitsch": "Whitney Osei",
    "Prerna Kakkar": "Meera Sundaresan", "Everett Berry": "Colm Fitzgerald",
    "Nilofer Rajpurkar": "Yasmin Al-Rashid", "Ivan Burazin": "Luka Novosel",
    "Edo Liberty": "Tomer Ben-Ami", "Ari Morcos": "Nathaniel Boateng",
    "Jerry Liu": "Wei-Lin Tsao", "Geoffrey Litt": "Theo Marchetti",
    "Nishant Gupta": "Arjun Raval", "Naman Ahuja": "Karthik Venkataraman",
    "Nader Khalil": "Omar Haddad", "Joseph Nelson": "Elliot Reddick",
    "Alex Cheema": "Ana Beatriz Sousa", "Ahmad Osman": "Idris Bello",
    "Matthew Berman": "Tobias Grant", "Stephen Chin": "Vikram Sethi",
    "Dhruv Batra": "Aditya Deshmukh", "Ameya Bhatawdekar": "Sanjay Iyer",
    "Christopher Manning": "Gustav Ehrlich", "John Craft": "Bianca Oduya",
    "Uday Kiran Medisetty": "Rohit Subramanian", "Adam Huda": "Brett Kowalski",
    "Udi Menkes": "Noa Bergman", "Rashi Agrawal": "Divya Menon",
    "Kevin Hou": "Julian Ferreira", "Moritz Johner": "Lars Beckmann",
    "Soumya Gupta": "Ishani Kapadia", "Jai Chopra": "Devin Alcaraz",
    "Michael Grinich": "Owen Radcliffe", "Maxime Rivest": "Etienne Beaulieu",
    "Isaac Miller": "Nate Ogunyemi", "Kevin Madura": "Peter Halloran",
    "Aliisa Rosenthal": "Camille Duarte", "Samuel Colvin": "Duncan Ashworth",
    "Jo Kristian Bergum": "Henrik Solberg", "Nidhi Kaushik Vyas": "Anjali Prakash",
    "Parth Asawa": "(dropped)", "Ronak Malde": "(dropped)",
    "Antje Barth": "(dropped)", "Kwindla Kramer": "(dropped)",
    "Frank Coyle": "(dropped)",
}

SMALL, MID, BIG = "<1,000", "1,000–10,000", ">10,000"
ER, SME, PANEL = "Experience Report", "SME talk", "Panel"

# --- The 32 adapted-from-real submissions ------------------------------------
REAL = [
 dict(t="Building the Engine While Flying the Plane: Launching the Kartograph MCP Server",
   ab="What does it actually take to go from a vague idea to a production AI system that people depend on? This is the real story of building Kartograph's MCP server as the founding engineer while the MCP spec itself was still changing underneath us — early prototypes, the dead ends, two architectural pivots, and the path from an initial local product to a fully remote server our customers now run against in production.",
   fmt=ER, size=MID, ind="Technology",
   hist="We started in early 2025 with a prototype behind a feature flag for internal designers. The remote MCP server has been generally available since November 2025 and now serves every paying workspace.",
   out="Roughly 34,000 weekly active MCP sessions. Median tool latency down from 1.9s to 340ms after the second pivot. Support tickets mentioning “wrong file context” fell 71% once we moved selection server-side.",
   adv="Do not build against a moving spec on the assumption it will settle. Version your own surface and treat the upstream spec as an input, not a contract.",
   co="", vid="https://www.youtube.com/watch?v=example-kartograph-mcp",
   notes="",
   sp=dict(name="Devon Achebe", email="devon.achebe@kartograph.example.com", title="Staff Software Engineer",
     org="Kartograph", bio="Devon was the founding engineer on Kartograph's agent platform and has shipped three versions of the same server. He spends most of his time deleting tools nobody called.")),

 dict(t="AtlasLab: Autonomous Multi-Agent Research Across Optimization Domains",
   ab="We built AtlasLab to automate quantitative research inside our Machine Learning Research Lab — the experimental grind of architecture search, hyperparameter tuning, and literature review that consumes most of a researcher's week. To show it generalises, we ran it on three deliberately different domains: GPU kernel optimisation, model pretraining under a fixed time budget, and traffic forecasting. AtlasLab is an agentic harness that takes a dataset and a natural-language objective and runs a full research campaign in three phases: it explores the data and surveys prior work, it constructs and adversarially validates its own evaluation framework, and then it runs experiments at scale on a multi-GPU cluster through a Strategist/Worker loop with a persistent playbook that accumulates domain knowledge across experiments. The playbook ends up containing methodology that existed nowhere in the launch prompts.",
   fmt=ER, size=BIG, ind="Financial Services",
   hist="The lab began agentic research tooling in Q2 2024. AtlasLab has been running unattended campaigns on our internal cluster since March 2025 and now supports four quant teams.",
   out="4.4x mean speedup on kernel optimisation (91x peak). 22% lower validation loss under a 20-minute pretraining budget. 23–25% RMSE improvement on traffic forecasting. Researcher time on hyperparameter sweeps down roughly 60%.",
   adv="Make the agent build and attack its own evaluation harness before it is allowed to run a single experiment. Every campaign we lost, we lost to a broken metric, not a bad model.",
   co="Our Head of Quantitative Research would co-present the results section.",
   vid="", notes="Compliance review of the numbers takes about three weeks on our side; flagging now so it is not a surprise in September.",
   sp=dict(name="Anders Mollegaard", email="anders.mollegaard@halverston.example.com", title="Machine Learning Scientist",
     org="Halverston Global", bio="Anders works in Halverston Global's Machine Learning Research Lab, where he builds systems that run experiments while everyone is asleep. He previously spent six years in high-frequency execution research.")),

 dict(t="From Ambient Documentation to Clinical Intelligence",
   ab="A practical session on how healthcare AI moves beyond ambient note generation into context-aware clinical decision support: grounding outputs in the patient encounter, surfacing evidence with citations inside the clinician's existing workflow, preserving clinician agency at every step, and building rigorous evaluations for safety and trust in a live environment where the reviewer is the physician who was in the room.",
   fmt=ER, size=MID, ind="Healthcare",
   hist="Ambient documentation shipped in 2023. Decision support went live in two specialties in June 2025 and is now in fourteen, covering about 9,000 clinicians.",
   out="Documentation time down 38 minutes per clinician per day. Citation-click-through 44%, which we treat as our trust proxy. Zero autonomous orders — the number we defend hardest.",
   adv="Put the citation in front of the recommendation, not behind it. Clinicians will not audit a claim they have to go looking for.",
   co="", vid="https://vimeo.com/example/stethos-clinical-intelligence", notes="",
   sp=dict(name="Nikhil Ramaswamy", email="nikhil.ramaswamy@stethos.example.com", title="Head of Engineering, Clinical Decision Support",
     org="Stethos AI", bio="Nikhil leads clinical decision support engineering at Stethos AI and works alongside a standing panel of practising physicians. He came to healthcare from search ranking and says the evaluation problem is harder here.")),

 dict(t="Rise of the Software Factory",
   ab="Public research reports a roughly 30x productivity gap between AI leaders and laggards. The differentiator is not culture, prompting technique, or model selection — it is infrastructure. Organisations capturing outsized value from agents have machine-readable codebases, deterministic internal APIs, CI/CD pipelines with agent-addressable hooks, and permission models granular enough to scope exactly what an agent may touch. This talk defines agent readiness as a concrete infrastructure checklist: structured codebases, deterministic APIs, per-agent scoped credentials, atomic and idempotent operations, structured execution traces, and explicit thresholds for when the agent stops and a human takes over.",
   fmt=SME, size=SMALL, ind="Technology",
   hist="We have been shipping agent infrastructure since 2024 and run the checklist with about 40 enterprise customers.",
   out="Across customer deployments we see agent task completion move from roughly 30% to 70% once deterministic APIs and scoped credentials are in place. Median time-to-first-merged-agent-PR drops from six weeks to nine days.",
   adv="Stop tuning prompts and go make your build reproducible. Every agent-readiness problem we have seen was a platform engineering problem wearing a hat.",
   co="", vid="", notes="",
   sp=dict(name="Marta Kowalczyk", email="marta.kowalczyk@foundryloop.example.com", title="Head of Growth",
     org="Foundry Loop", bio="Marta runs growth at Foundry Loop and spends most of her week inside customer platform teams. She writes the checklist down after every failed rollout.")),

 dict(t="Your LLM Stack Is a 2008 Database With Better Marketing",
   ab="A billion dollars of data was exposed through a missing authentication check on a widely deployed compute framework. It was not a zero-day. It was a default config someone never flipped off. That story is the rule in production ML, not the exception. We synthesised 139 peer-reviewed papers on production ML security across access control, runtime security, infrastructure, and operations. Misconfiguration, not missing features, is the dominant failure mode; adversarial defences impose 15–30% inference overhead, which is why almost nobody runs them; ML security tooling lags general DevOps tooling by years; and security, data science, and ops teams sit in expertise silos that create gaps no single team can see. You leave knowing where your stack sits and which three misconfigurations account for most of the risk.",
   fmt=SME, size=BIG, ind="Manufacturing",
   hist="Our platform security group has been reviewing customer ML deployments since 2023; the literature synthesis behind this talk was completed in early 2026.",
   out="Across 60 customer environments audited, 78% had at least one of the three top misconfigurations. Median time to remediate once named: 4 days. The maturity model has been adopted by 12 of those customers as their internal standard.",
   adv="Audit your defaults before you buy another tool. The mechanisms you need almost certainly shipped already and are switched off.",
   co="", vid="https://www.youtube.com/watch?v=example-cerebrix-mlsec", notes="",
   sp=dict(name="Ifeoma Nwachukwu", email="ifeoma.nwachukwu@cerebrix.example.com", title="Senior Software Developer, Platform Security",
     org="Cerebrix Systems", bio="Ifeoma works on platform security at Cerebrix Systems and spends her time reading other people's configuration files. She has opinions about defaults.")),

 dict(t="From Signal to PR: Anatomy of a Self-Improving Agent",
   ab="What if your observability platform did not just tell you something was wrong, but told you why, and opened a PR with the fix? We walk through how we built Trailhead: an autonomous investigation agent that triggers on monitor alerts or a schedule, pulls traces into a working filesystem, runs root-cause analysis, and produces a PR with prompt or code changes ready for review. We cover the architecture decisions (cloud agents versus sandboxed containers, harness plus skills), why traces-on-a-filesystem is the key unlock for agent-driven debugging, and how we dogfooded the system on our own support agent before shipping it to customers — including where and why the human stays in the loop.",
   fmt=ER, size=SMALL, ind="Technology",
   hist="Trailhead began as an internal on-call experiment in January 2025 and became a shipped product in October 2025. It runs against our own production traces every day.",
   out="Mean time to root cause on agent regressions down from 5.5 hours to 40 minutes. 61% of Trailhead PRs merged with no human edit; the other 39% is the interesting part of the talk.",
   adv="Give the agent a filesystem, not an API. Every debugging capability we tried to expose as a tool was worse than letting it grep.",
   co="", vid="", notes="Happy to take an early slot. I know a vendor-authored talk has to earn it — I will bring the failure cases, not the demo reel.",
   sp=dict(name="Grant Whelan", email="grant.whelan@telemetrix.example.com", title="Chief Executive Officer",
     org="Telemetrix AI", bio="Grant co-founded Telemetrix AI after a decade in machine learning observability. He still carries the pager for the agent that fixes the agents.")),

 dict(t="In Code They Act, In Proof We Trust",
   ab="AI agents today execute on blind trust, and the failure modes are already in the headlines: a dealership chatbot agreeing to sell a $76,000 truck for $1, a coding agent wiping a production database during a change freeze, an agent skill quietly installing a keylogger. Execution is irreversible — you cannot unsend a message, unwire a payment, or un-delete a database. Provenant is an agent harness that enforces that discipline by construction: before any action runs, the agent must submit its execution plan together with a machine-checkable proof of safety, written in Verum, a literate logic-programming language designed to be read by humans and verified by machines. A small auditable checker decides whether the plan may execute. We no longer have to trust the agent's proposal, or even its proof — only the checker.",
   fmt=SME, size=SMALL, ind="Financial Services",
   hist="Research began in 2024; the checker has been gating real workloads for two design-partner banks since September 2025.",
   out="At one design partner, 4.1% of proposed agent plans were refused by the checker, of which post-hoc review judged 84% genuinely unsafe. Proof-checking overhead: 60–180ms per action.",
   adv="Decide what your agent is allowed to prove before you decide which model it runs on. The policy is the product; the model is a detail.",
   co="", vid="https://www.youtube.com/watch?v=example-hollerith-proofs", notes="",
   sp=dict(name="Sander de Vries", email="sander.devries@hollerithlabs.example.org", title="Research Scholar",
     org="Hollerith Labs", bio="Sander is a research scholar at Hollerith Labs working on verified execution for autonomous systems. He has spent twenty years arguing that types were never the expensive part.")),

 dict(t="Build-Time vs. Run-Time: Why Your Dev Tools Will Fail in Production",
   ab="A dangerous pattern is spreading: teams deploy build-time tools into run-time environments. We draw the distinction that the MCP ecosystem is missing — build-time agents (developer assistants) versus run-time agents (an end-user support bot) — and show why the atomic tools that make build-time agents powerful become catastrophic liabilities at run time. We give a framework for moving your architecture across three axes: design (from flexible atomic primitives to composite workflows that encapsulate business logic), security (from developer identity to workload identity, where the agent is treated as an untrusted user), and reliability (why production agents need agent-readable errors rather than the stack traces developers rely on). Attendees leave with a rubric for whether their tools are production-ready or merely prototype-ready.",
   fmt=SME, size=BIG, ind="Technology",
   hist="Our data-tooling group shipped Tool Bridge in 2024; the run-time hardening described here landed across 2025 after two customer incidents.",
   out="Composite workflows cut run-time agent error rates from 12% to 2.8% in our reference deployment. Workload-identity migration removed 100% of long-lived developer credentials from run-time paths across 140 internal services.",
   adv="Never ship a tool to production that a developer would only ever call once, by hand, while watching.",
   co="", vid="", notes="Co-speaker is confirmed but her travel approval is pending; if it falls through I will present solo.",
   sp=dict(name="Whitney Osei", email="whitney.osei@northgatecloud.example.com", title="Staff Software Engineer",
     org="Northgate Cloud", bio="Whitney is a staff engineer on Northgate Cloud's data tooling team and wrote most of Tool Bridge's second implementation. She co-presents with Meera Sundaresan, senior engineer on the same team.")),

 dict(t="GTM Engineering: The Technical Bits",
   ab="Everyone talks about GTM engineering; this talk shows the actual plumbing. We go under the hood on the parts most talks skip: enrichment pipelines, agent-driven data classification, identity resolution, and the systems that turn unstructured web data into clean, deterministic CRM fields. A builder's-eye view of what GTM engineering actually is once you strip away the buzzwords — including the three places we let a model make a decision and the eleven where we refuse to.",
   fmt=ER, size=SMALL, ind="Technology",
   hist="We rebuilt our enrichment stack around LLM classification in mid-2024. Every inbound record has passed through it since January 2025.",
   out="Identity-resolution precision 91% to 98.5%. Cost per enriched record down 62%. Sales-accepted lead rate up 19 points, which is the only number our CRO cares about.",
   adv="Keep a deterministic field at the end of every probabilistic pipeline. Your revenue team cannot filter on a vibe.",
   co="Our VP of Sales Development would co-present the adoption half.",
   vid="", notes="",
   sp=dict(name="Colm Fitzgerald", email="colm.fitzgerald@ledgerline.example.com", title="Head of GTM Engineering",
     org="Ledgerline", bio="Colm runs GTM engineering at Ledgerline and came to it from data infrastructure. He maintains that the CRM is a database and should be treated like one.")),

 dict(t="Inside the AI Economy: What Our Payments Data Reveals",
   ab="We process payments for a large share of the fastest-growing AI companies, which gives us index-level visibility into how the AI economy is actually behaving. AI companies are growing faster, selling globally by default, and monetising earlier than any cohort we have measured. This session shows the data behind the growth: how the cost of launching collapsed, how the fastest-growing companies are changing their pricing (and what happens to the ones that do not), and the early, unglamorous role agents are starting to play in real commerce flows.",
   fmt=ER, size=BIG, ind="Financial Services",
   hist="Our agent and developer-experience group formed in 2024; agentic checkout primitives have been in production with pilot merchants since August 2025.",
   out="AI-native merchants reach $1M annualised revenue a median 4.2x faster than the prior SaaS cohort. Agent-initiated transaction volume grew 31x year over year from a small base — I will show the base.",
   adv="Price on outcomes before your competitors force you to. Every company in our data that waited had the change made for them.",
   co="", vid="https://www.youtube.com/watch?v=example-conduit-ai-economy", notes="",
   sp=dict(name="Yasmin Al-Rashid", email="yasmin.alrashid@conduitpay.example.com", title="Product Lead, Agent and Developer Experience",
     org="Conduit Payments", bio="Yasmin leads agent and developer experience at Conduit Payments. She spends her time on the boundary between what merchants will allow and what agents will attempt.")),

 dict(t="Kubernetes Is Not Your Sandbox",
   ab="Teams are reaching for container orchestration to run agent sandboxes, and it is the wrong tool. Orchestrators are built to keep things alive and hold them in a steady state; a sandbox is born, forked, and killed before that machinery catches up. The mismatch compounds because the sandbox keeps gaining requirements without shedding any — in eighteen months it went from a fast code-snippet runner, to a stateful box for long-running agents, to ten thousand ephemeral environments that fork for RL rollouts and die in under a second. The cost shows up the moment you measure it: we ran the same 50-action bug-fix trajectory across five stacks and got a 12x spread, 12.9s on the fastest and 161.5s on the slowest. The gap is not compute, it is lifecycle overhead per action, and we explain the mechanism behind each number.",
   fmt=ER, size=SMALL, ind="Technology",
   hist="We have run agent sandboxes as a product since 2023 and rewrote the lifecycle layer twice, most recently in Q3 2025.",
   out="12.9s vs 161.5s on an identical 50-action trajectory across five stacks. Cold-start p99 under 90ms. Cost per agent-hour down 74% after the second rewrite.",
   adv="Measure per-action lifecycle overhead, not throughput. Throughput hides the tax your agent actually pays.",
   co="", vid="", notes="I will name competing stacks by name and show the numbers. If that is a problem for sponsorship reasons, tell me now and I will restructure.",
   sp=dict(name="Luka Novosel", email="luka.novosel@ephemeracloud.example.com", title="Chief Executive Officer",
     org="Ephemera Cloud", bio="Luka founded Ephemera Cloud after building developer environments for a decade. He has now written the same scheduler three times and expects a fourth.")),

 dict(t="Vectorra 2.0: Knowledge Engines, Not Retrieval Loops",
   ab="Autonomous agents are capable but they do not know your business or your objectives. That is why most enterprise agents stay stuck in retrieval loops, burning millions of tokens re-reading raw documents. A shift from traditional retrieval-plus-agent architectures to purpose-built knowledge engines is underway. This talk covers why moving reasoning upstream — compiling raw enterprise data into specialised, task-specific context artifacts — is what actually unlocks reliable agentic workflows, and how offloading knowledge management to a dedicated layer lets engineering teams cut token consumption dramatically while improving task completion, speed, and accuracy.",
   fmt=SME, size=SMALL, ind="Technology",
   hist="We shipped our first vector index in 2021; the knowledge-engine architecture described here went GA in February 2026.",
   out="Up to 90% reduction in token consumption on customer agentic workloads. Task completion up 22 points on our internal enterprise benchmark. Median query latency 180ms at 400M vectors.",
   adv="Stop paying your model to re-read the same PDF. Compile once, retrieve the artifact, spend the tokens on reasoning.",
   co="", vid="https://www.youtube.com/watch?v=example-vectorra-two", notes="",
   sp=dict(name="Tomer Ben-Ami", email="tomer.benami@vectorra.example.com", title="Founder and Chief Scientist",
     org="Vectorra", bio="Tomer founded Vectorra and spent the previous decade in approximate nearest-neighbour research. He is still slightly annoyed that everyone calls it RAG.")),

 dict(t="Data Quality Is the Compute Multiplier",
   ab="Better data quality is the highest-leverage and most underinvested part of building a model: it produces a better model for the same compute, whether you are mid-training on an open base or pretraining from scratch. This is a practical look at data curation — what data quality actually means, the stages of a modern curation pipeline (cleaning, filtering, deduplication, synthetic data generation, algorithmic mixing, multi-stage composition), and which steps matter most in practice. It draws on our frontier data research and on customer results, including a global legal-information publisher's mid-training gains on proprietary domain data and an open-weights partner reaching the open frontier on public data alone.",
   fmt=SME, size=SMALL, ind="Technology",
   hist="Founded in 2023 around data curation; customer pipelines have been in production since mid-2024.",
   out="Same-compute benchmark gains of 8–19% across customer mid-training runs. One legal-domain customer matched their prior quality target with 40% less compute.",
   adv="Spend your next quarter on the corpus, not the architecture. It is the least glamorous line item with the best return we can measure.",
   co="", vid="", notes="",
   sp=dict(name="Nathaniel Boateng", email="nathaniel.boateng@curatalabs.example.com", title="Co-founder and Chief Executive Officer",
     org="Curata Labs", bio="Nathaniel co-founded Curata Labs after research work on dataset pruning. He believes most training runs are a data problem that bought GPUs.")),

 dict(t="Building the Document Context Layer for AI Agents",
   ab="AI agents are the new knowledge workers, but knowledge work depends on unstructured enterprise context, and roughly 90% of that context lives in document containers — from human-native formats (PDF, Word, slides) to emerging agent-native ones (HTML, Markdown). Retrieval in 2026 means generalised agent harnesses with tools, MCP servers, and skills. In that world every company building agents needs a document context layer: the bridge between their unstructured documents and the agents trying to reason over them. This talk covers what that layer looks like in practice — document understanding, retrieval, and workflows — and the parts nobody has solved yet: agent-native formats, versioning, editing, permissions, and long-running agents.",
   fmt=SME, size=SMALL, ind="Technology",
   hist="We have shipped document parsing and retrieval tooling since 2023; the enterprise document layer has been in production with regulated customers since early 2025.",
   out="Table extraction accuracy 71% to 94% on our internal enterprise document set. Customers report 3–5x fewer retrieval-caused agent failures after moving parsing upstream.",
   adv="Treat parsing as a first-class production system with its own SLO. Most ‘model quality’ complaints we investigate are parsing failures.",
   co="", vid="https://vimeo.com/example/docugraph-context-layer", notes="",
   sp=dict(name="Wei-Lin Tsao", email="weilin.tsao@docugraph.example.com", title="Chief Executive Officer",
     org="Docugraph", bio="Wei-Lin founded Docugraph and has read more badly-scanned invoices than anyone should. She is unreasonably interested in table borders.")),

 dict(t="Understanding Is the New Bottleneck",
   ab="Autonomous loops are fashionable, but the reality is that most agentic tasks still require human judgement. To guide your agents well it is not enough to verify correctness — you have to actually understand the work they are doing. This talk shares techniques for staying in the loop and developing understanding efficiently, combining old ideas from education and cognitive science with modern agent capabilities. You walk away with practical tactics for moving faster with agents by understanding more, not less.",
   fmt=ER, size=MID, ind="Technology",
   hist="We began shipping agentic features to customers in 2024; the internal practices in this talk came out of watching our own engineers review agent work through 2025.",
   out="On our internal instrumentation, engineers using the review techniques accepted 23% fewer agent changes and shipped 31% fewer follow-up fixes. Time-to-review per change fell 18%.",
   adv="Stop measuring acceptance rate. It rewards the reviewer who reads least.",
   co="", vid="https://www.youtube.com/watch?v=example-palimpsest-understanding", notes="",
   sp=dict(name="Theo Marchetti", email="theo.marchetti@palimpsest.example.com", title="Design Engineer",
     org="Palimpsest", bio="Theo is a design engineer at Palimpsest working on how humans read what machines wrote. He collects examples of interfaces that make you smarter.")),

 dict(t="Operating Distributed Inference Systems at Scale",
   ab="Inference has become one of the most important infrastructure problems in modern computing. As AI systems evolve into agents with persistent memory, tool use, and multi-step reasoning, traditional inference architectures struggle under demands for latency, throughput, cost efficiency, and reliability. This talk shares lessons from building large-scale compute infrastructure powering production workloads: distributed inference architectures, GPU scheduling and capacity management, multi-tenant inference infrastructure, caching and batching, reliability and fault isolation, observability and control loops, and why inference is increasingly an orchestration problem rather than a modelling one.",
   fmt=ER, size=BIG, ind="Telecom",
   hist="Our inference platform has been in production since 2022 and was rearchitected for agentic traffic across 2025.",
   out="Cost per million tokens served down 58% year over year. p99 time-to-first-token 2.4s to 610ms. GPU utilisation 41% to 76% after we rewrote admission control.",
   adv="Instrument the queue, not the GPU. Every latency problem we chased into the kernel was actually admission control.",
   co="", vid="", notes="My co-presenter is on the same team; if headcount changes we may swap him for another tech lead.",
   sp=dict(name="Arjun Raval", email="arjun.raval@continuumnet.example.com", title="Software Engineer, Tech Lead",
     org="Continuum Networks", bio="Arjun leads inference infrastructure at Continuum Networks and has spent four years making other people's models cheaper. He presents with Karthik Venkataraman, a senior engineer on the same platform.")),

 dict(t="State of the Union: Why Local, Why Now",
   ab="Local AI has crossed from interesting to useful, driven by stronger open models, better hardware, and a maturing ecosystem for running intelligence outside the cloud. This panel explores what that shift unlocks for sovereignty, defence, regulated industries, privacy, cost, and resilience — and why open models may decide who benefits from the next wave. Panellists span accelerator hardware, computer vision tooling, distributed local inference, and the enthusiast community that keeps proving things work before the vendors do.",
   fmt=PANEL, size=BIG, ind="Technology",
   hist="Each panellist's organisation has shipped local or on-premise inference in production; the earliest started in 2023 and the newest in 2025.",
   out="Panellists will each bring one number: on-premise cost per million tokens, model quality delta versus hosted equivalents, and deployment counts in regulated environments. We will not pretend the gap is zero.",
   adv="Run the cost model with your own utilisation numbers before you believe anyone's local-versus-cloud slide, including ours.",
   co="Panel of five, moderated.",
   vid="https://www.youtube.com/watch?v=example-local-ai-panel",
   notes="Panellists: Elliot Reddick (Visionfold), Ana Beatriz Sousa (Latchkey Labs), Idris Bello (Homebrew Intelligence), Tobias Grant (Frontcast Media). One panellist may change — travel is not confirmed for all four.",
   sp=dict(name="Omar Haddad", email="omar.haddad@cerebrix.example.com", title="Director of Developer Technology",
     org="Cerebrix Systems", bio="Omar directs developer technology at Cerebrix Systems and has moderated this argument at four events. He promises to cut off anyone who says ‘it depends’ twice.")),

 dict(t="GraphMind: Why Assistants Need Graph Memory, Not More Tokens",
   ab="Autonomous assistants are easy to demo and hard to make reliable. The problem is usually not tool access — it is memory. Most assistant architectures still treat memory as a chat log plus vector retrieval, which is fine for document question-answering and breaks down when the assistant must connect conversations, people, tools, and decisions across many iterations. A single request can depend on a chat thread, a pull request, a failed CI run, a calendar event, and prior operating constraints. This talk shows why knowledge graphs and context graphs are a better foundation: knowledge graphs capture durable entities and relationships, context graphs capture the operational layer assistants lose — actions, decision traces, provenance, recency. Attendees leave with patterns for schema design, retrieval routing, and evaluation.",
   fmt=ER, size=MID, ind="Technology",
   hist="Graph-backed retrieval has been in our product since 2023; the context-graph patterns here came out of customer deployments through 2025.",
   out="On our internal assistant benchmark, graph-plus-vector retrieval improved multi-hop task success from 48% to 79% while cutting context tokens per request by 55%.",
   adv="Model the decisions, not just the documents. Everything your assistant forgets is a decision nobody wrote down.",
   co="", vid="", notes="",
   sp=dict(name="Vikram Sethi", email="vikram.sethi@graphwright.example.com", title="VP of Developer Relations",
     org="Graphwright", bio="Vikram runs developer relations at Graphwright and has been drawing nodes and edges on whiteboards since long before it was an AI topic. He is trying very hard not to say ‘it's a graph problem’.")),

 dict(t="Computer-Use Models Will Agentify the Web, Not APIs",
   ab="We are rushing toward a world where every digital surface built for humans is managed by agents. The technical challenges are real: context windows have barely moved while the digital world is orders of magnitude bigger; most of the web has no API and requires agents to act like humans, consuming pixels and emitting keyboard and mouse actions; and both human preferences and the web itself change, requiring dynamic memory and continual learning. But even if we solve those, what does the resulting world look like? The web was built for human consumption and is often hostile to bots; for a long while we will share the digital roadways with these agents; and end-to-end encryption means something different when the other end is a model. Grounded in a year of building always-on computer-use agents.",
   fmt=ER, size=SMALL, ind="Technology",
   hist="We began training computer-use models in early 2025; our monitoring agents have been running continuously against the open web since September 2025.",
   out="Our model is modestly ahead of the leading frontier models on browser-use benchmarks while running about 2x faster and 4–5x cheaper. Monitoring agents have run 11M unattended sessions with a 0.7% harmful-action intervention rate.",
   adv="Budget for the web being hostile. Half our engineering goes to surfaces that actively do not want us there, and that fraction is rising.",
   co="", vid="", notes="",
   sp=dict(name="Aditya Deshmukh", email="aditya.deshmukh@peregrinelabs.example.com", title="Co-founder and Chief Scientist",
     org="Peregrine Labs", bio="Aditya co-founded Peregrine Labs and previously led embodied AI research. He has watched more screen recordings of agents failing than he cares to admit.")),

 dict(t="Your Agent Evolved. Your Evals Didn't.",
   ab="Knowing which generation your agent is in, which failure modes your current evals are blind to, and what to build next is the difference between shipping with confidence and flying blind. Agent architectures have evolved through six generations — prompt, chain, ReAct loop, workflow graph, modern agent loop, full harness — and each quietly breaks the eval strategy of the one before. A prompt-quality rubric will not catch a bad tool call; a trace scorer will not catch memory poisoning. Using a single incident-response agent threaded through every generation, this talk shows exactly where each architecture outgrows its evals and what you need to close the gap.",
   fmt=SME, size=MID, ind="Technology",
   hist="We have shipped evaluation tooling since 2023 and rebuilt our own scoring stack twice as customer agents changed shape.",
   out="Customers who moved from output rubrics to trace-level scoring found a median 3.4x more production failure modes in the first month. Our own regression suite catches 71% of incidents pre-deploy, up from 29%.",
   adv="Re-derive your eval strategy every time your architecture changes. The eval you wrote for last year's agent is now a comfort blanket.",
   co="", vid="https://www.youtube.com/watch?v=example-rubricon-evals", notes="",
   sp=dict(name="Sanjay Iyer", email="sanjay.iyer@rubricon.example.com", title="VP, Field CTO",
     org="Rubricon", bio="Sanjay is a field CTO at Rubricon and spends his weeks inside customer eval reviews. He keeps a list of the excuses teams give for not looking at traces.")),

 dict(t="From Approval Loops to Autonomous Agents",
   ab="You have invested in the best models, coding agents, and AI tooling. Now comes the hard part: unlocking autonomous development without creating security gaps, governance holes, or endless approval loops. In this hands-on session you will learn how to run coding agents in isolated environments built for autonomous work, create a golden path for AI-assisted development across an organisation, reduce supply-chain risk with hardened images, manage multiple agents with the right permissions and guardrails, and scale AI-assisted development without slowing developers down.",
   fmt=SME, size=MID, ind="Technology",
   hist="We shipped agent-isolation tooling in 2024; the golden-path pattern is now deployed at roughly 90 enterprise customers.",
   out="At a reference customer, agent PR approval latency fell from 3.1 days to 4 hours while policy violations went to zero. Hardened base images cut critical CVEs per build by 84%.",
   adv="Build the golden path before you buy the agents. Otherwise every team invents its own escape hatch and you inherit all of them.",
   co="", vid="", notes="This is a 90-minute hands-on with a lab environment — I need reliable wifi and power at seats, or it should be a 45-minute talk instead. Your call.",
   sp=dict(name="Bianca Oduya", email="bianca.oduya@cratewright.example.com", title="Principal Solutions Engineer",
     org="Cratewright", bio="Bianca is a principal solutions engineer at Cratewright and has run this workshop in eleven cities. She has strong feelings about conference wifi.")),

 dict(t="Agentic SDLC: Building Blocks for Our Software Factory",
   ab="99% of our engineers use AI every month, 70% of pull requests are attributed to AI, and 15% are now done entirely by autonomous agents. This session goes behind the scenes on exactly what it took to get there, starting with the foundational building blocks — the model gateway, MCP infrastructure, agent skills, knowledge systems, and cloud development environments that make agentic engineering possible at scale. Then, once the foundations are in place, we show how to assemble them into a fully agentic SDLC: research and spec writing, autonomous code generation, verification and validation before ship, monitoring after it lands, and continuous improvement. With tooling demos throughout.",
   fmt=ER, size=BIG, ind="Transportation & Logistics",
   hist="Our developer platform group began the model gateway in early 2024. Autonomous agents have been merging code to production since Q1 2025 across roughly 5,000 engineers.",
   out="99% monthly AI adoption among engineers. 70% of PRs AI-attributed; 15% fully autonomous. Median PR cycle time down 41%. Change failure rate flat, which took two rewrites of the verification stage to achieve.",
   adv="Invest in verification before generation. We built the generator first and spent a year catching up on trust.",
   co="Our SVP of Engineering can co-present the adoption and change-management half.",
   vid="https://www.youtube.com/watch?v=example-wayfinder-agentic-sdlc",
   notes="Two presenters from the same team; if only one slot is available, I will present and my colleague will take Q&A.",
   sp=dict(name="Rohit Subramanian", email="rohit.subramanian@wayfindermobility.example.com", title="Distinguished Engineer",
     org="Wayfinder Mobility", bio="Rohit is a distinguished engineer at Wayfinder Mobility working on developer platform and agentic tooling. He presents with Brett Kowalski, who leads AI developer tools there.")),

 dict(t="Why Off-the-Shelf AI Doesn't Understand Money",
   ab="Ask any general model a financial question about your business and you get a fluent, confident, generic answer — one that does not know your business, or what happened when businesses like yours made the same decision. We build financial AI for over 100 million customers. Our custom models outperform general-purpose ones on accuracy while cutting latency in half. But that is the foundation, not the destination. This talk covers where financial intelligence goes when AI stops reporting what happened and starts helping you decide what to do next — and then does it for you, inside the constraints of a regulated financial product.",
   fmt=ER, size=BIG, ind="Financial Services",
   hist="We started domain-specific model training in 2023. Custom financial models have served every customer-facing surface since mid-2025.",
   out="Domain accuracy 12 points above the best general model we tested, at half the latency. Deflection of finance support contacts up 27%. Advice-acceptance rate (customer takes the recommended action) 38%.",
   adv="Fine-tune on your outcomes, not your documents. Our first custom model knew our help centre by heart and helped nobody.",
   co="", vid="", notes="",
   sp=dict(name="Noa Bergman", email="noa.bergman@ledgerhouse.example.com", title="Principal Product Manager, Financial AI",
     org="Ledgerhouse Financial", bio="Noa is a principal product manager at Ledgerhouse Financial working on financial intelligence for small businesses. She started as an accountant, which she says was the useful part.")),

 dict(t="Guardrails First: Engineering Member-Facing Health AI",
   ab="Everywhere else in the company an AI pilot can reach production in weeks. For our member-facing clinical assistant it cannot, and that single constraint redesigned our entire architecture. This is a field report on building conversational AI in regulated digital health, where move-fast-and-break-things is not a culture choice but a liability. PHI is protected by architecture, not policy: production and non-production are hard-isolated, dashboards are sanitised, and engineers outside the US never touch protected health information. Must-not-fail behaviour never lives in a prompt — emergency escalation and intent routing run as deterministic rules at the top of every turn, before the model is consulted. Clinical safety is a continuous evaluation layer, not a launch gate. Every turn, tool call, and reasoning step is traced. The throughline: in regulated healthcare, compliance constraints are not a tax you pay around the architecture. They become the architecture.",
   fmt=ER, size=MID, ind="Healthcare",
   hist="We began member-facing conversational AI in late 2024 after an eight-month safety review. It has been live to members since July 2025.",
   out="Roughly 30 LLM-as-judge evaluators running continuously. Escalation routing recall 99.4% against a red-team suite. Zero clinical-safety incidents in 14 months and 2.6M member turns. Time-to-production: 11 months, which is the honest number.",
   adv="Put your must-not-fail behaviour in deterministic code at the top of the turn. If you cannot afford to get it wrong, do not leave it to a probabilistic system.",
   co="Our Chief Medical Officer would co-present the safety-review section.",
   vid="", notes="This is non-diagnostic member-facing AI. Please do not title or market it as clinical decision-making — our regulatory team will ask.",
   sp=dict(name="Divya Menon", email="divya.menon@kinetiqhealth.example.com", title="Head of Agentic AI",
     org="Kinetiq Health", bio="Divya leads agentic AI at Kinetiq Health and has spent two years building things slowly on purpose. She keeps a printed list of behaviours that are never allowed to be a prompt.")),

 dict(t="Get Out of the Model's Way",
   ab="From autocomplete to chat to agents to agent orchestration — how do you build a product that scales with intelligence rather than working around it? Which core primitives let agents operate at the technical and non-technical frontier, and how do you squeeze every ounce of capability out of agentic development tools? This talk answers those questions and breaks down how our development environment assembles dynamic agent teams to attack complex tasks, from writing an OS kernel to automating research workflows — including the features we deleted because they were doing the model's thinking for it.",
   fmt=ER, size=BIG, ind="Technology",
   hist="Our agentic IDE work started in 2024; the multi-agent orchestration layer shipped to general availability in early 2026.",
   out="Task completion on our internal long-horizon benchmark 34% to 68% after removing scaffolding rather than adding it. Median agent session length up 4.1x with no drop in acceptance.",
   adv="Delete a layer of scaffolding every time the model improves. Most of our wins were subtractions.",
   co="", vid="https://www.youtube.com/watch?v=example-updraft-out-of-the-way", notes="",
   sp=dict(name="Julian Ferreira", email="julian.ferreira@northgateresearch.example.com", title="Engineering Lead, Updraft",
     org="Northgate Research", bio="Julian leads engineering on Updraft, an agentic development environment. He is professionally suspicious of his own abstractions.")),

 dict(t="We Gave an Agent Production Code Access and Then Tried to Sleep at Night",
   ab="We let an agent touch production code to fix CVEs. That is either automation or a supply-chain incident, depending on how honest your architecture is. Mender started simple: find vulnerable dependencies, patch them, open a PR, let CI prove the fix, move on. Then reality showed up. The agent needed repository access, CI logs, credentials, and a container socket. Without those it was useless; with them, every security reviewer in the room had a point. This is the production case study: what we gave the agent, what we refused, what our security team pushed back on, and where they were right. We cover scoped permissions, constrained PRs, audit trails, approval gates, CI evidence, credential boundaries, and the gap between ‘it generated a patch’ and ‘we can defend this change’.",
   fmt=ER, size=MID, ind="Financial Services",
   hist="We began agentic remediation in March 2025 in a sandboxed repository; it has been patching production dependencies under approval gates since September 2025.",
   out="Median CVE remediation 23 days to 2 days. 640 dependency PRs opened, 71% merged unchanged. Two agent actions blocked by the credential boundary — both would have been incidents, and both are in the talk.",
   adv="Write down what you will refuse the agent before you write the prompt. Our refusal list is the only artefact that survived contact with our auditors.",
   co="Our Head of Information Security is willing to co-present the pushback section, and disagrees with me on stage.",
   vid="", notes="",
   sp=dict(name="Lars Beckmann", email="lars.beckmann@sterlingrails.example.com", title="Staff Engineer, Platform Security",
     org="Sterling Rails", bio="Lars is a staff engineer at Sterling Rails, a regulated payments infrastructure company. He is the person who has to explain the agent's commits to auditors.")),

 dict(t="Building Closed-Loop Evals for a Multimodal Agent at Scale",
   ab="How we designed evaluations for an agent that edits food photography to better present dishes for small independent merchants on our delivery marketplace — along with the pitfalls and the lessons. The problem is uniquely hard: we must stay faithful to the original dish, preserve each merchant's brand and packaging, and avoid homogenising the marketplace, all without an existing playbook for multimodal evaluation in a narrow domain. We dig into reward hacking, where the agent worked out how to game the eval loop, and how we built a closed feedback loop combining offline and online signals for continuous improvement while balancing creativity against rigid safety guardrails at scale.",
   fmt=ER, size=BIG, ind="Retail",
   hist="The image enhancement agent shipped to a merchant pilot in early 2025 and now runs across the full independent-merchant catalogue.",
   out="Merchant opt-in 62%. Order conversion on enhanced listings up 9.4%. Two documented reward-hacking regressions caught by the online loop, one of which ran for eleven days before we noticed — that story is the middle of the talk.",
   adv="Assume your agent will find the seam in your metric, and staff someone to go looking for it weekly.",
   co="", vid="", notes="Co-presenter is our PM; if he is reassigned before October another PM on the team will take his half.",
   sp=dict(name="Ishani Kapadia", email="ishani.kapadia@wayfindermobility.example.com", title="Machine Learning Engineer",
     org="Wayfinder Mobility", bio="Ishani builds multimodal evaluation systems at Wayfinder Mobility and spends a lot of time looking at photographs of food. She presents with Devin Alcaraz, the product manager for the agent.")),

 dict(t="Auth for Agents: Unblocking Autonomous AI",
   ab="AI agents are ready to act on users' behalf, but legacy authentication flows were built for humans, not agents. This session introduces agent-identity.md, an open protocol that lets agents register and authenticate users without sign-up forms, and shares what early implementers have learned since launch — including the three ways the first draft was wrong. We cover the delegation model, revocation, the audit story enterprises actually ask for, and what adopters (two infrastructure providers and a project-management SaaS) found when they put it in front of real users.",
   fmt=SME, size=SMALL, ind="Technology",
   hist="We published the protocol draft in mid-2025; four production implementations have shipped since November 2025.",
   out="Agent onboarding completion 31% to 88% at the first adopter. Median time from agent launch to first authenticated action: 6 seconds, down from a form flow that took 4 minutes and lost most agents.",
   adv="Design revocation first. Every auth protocol that got adopted and then got yanked failed on revocation, not registration.",
   co="", vid="https://www.youtube.com/watch?v=example-gatepost-agent-auth", notes="",
   sp=dict(name="Owen Radcliffe", email="owen.radcliffe@gatepost.example.com", title="Founder and Chief Executive Officer",
     org="Gatepost", bio="Owen founded Gatepost after a decade building identity infrastructure. He has now read every OAuth extension, which he does not recommend.")),

 dict(t="The Unreasonable Effectiveness of Separating the Task from the Model",
   ab="By declaring your task's inputs and outputs without first considering model capability, you create the space to figure out execution later. Our framework's whole promise is that you should evaluate and execute AI engineering at a level higher than a prompt template or a provider's API shape — at the signature. But models have changed enormously. How can the same input/output specification still work in a world of tools, reasoning models, and skills? By defining the task strictly through its inputs and outputs, the implementation stays completely flexible: you can experiment with different models, settings, weights, templating strategies, and output formats without touching your workflow. We preview where this goes next — letting the model write the code beneath a signature, and learning directly from user interactions rather than a hand-tuned scalar metric.",
   fmt=PANEL, size=SMALL, ind="Technology",
   hist="The framework has been open source since 2023; the version described here has been running in production at three enterprise design partners since late 2025.",
   out="At one design partner, swapping the underlying model required zero workflow changes and cut inference cost 46% at equal task quality. Prompt-maintenance engineering hours down roughly 70%.",
   adv="Write the signature before you write the prompt, and then refuse to write the prompt.",
   co="", vid="", notes="We would run this as a two-person conversation rather than a lecture, and can bring a third voice from a production user of the framework if you want a fuller panel. We are maintainers of the same open-source project rather than colleagues at one employer — flagging in case that complicates your speaker fields.",
   sp=dict(name="Etienne Beaulieu", email="etienne.beaulieu@colophonlabs.example.com", title="Core Contributor, Sigil",
     org="Colophon Labs", bio="Etienne is a core contributor to Sigil, an open-source framework for declarative LLM programming. He presents with Nate Ogunyemi, the project's lead maintainer.")),

 dict(t="Reverse-Engineering the AI Buyer",
   ab="You built the best AI product in the room. Now what? The go-to-market lessons builders skip. This talk decodes the commercial mistakes technical teams make most often: why enterprise procurement is nothing like consumer adoption, how to design for trust and change management from day one, the difference between a pilot and a deal, and the signals that tell you a product is ready to scale versus ready to get stuck. War stories and counterintuitive lessons from years inside enterprise AI sales organisations, told from the buyer's side of the table.",
   fmt=SME, size=SMALL, ind="Financial Services",
   hist="Not applicable in the usual sense — this draws on enterprise AI deployments across roughly 40 portfolio companies since 2023.",
   out="Across the portfolio, products that shipped an admin/audit surface in the first year converted pilots to paid at 3.2x the rate of those that did not. Median pilot-to-contract time: 7 months, and I will show what shortens it.",
   adv="Ship the boring enterprise surface — audit log, SSO, admin controls — a year before you think you need it. It is the difference between a pilot and a contract.",
   co="", vid="https://vimeo.com/example/thistledown-ai-buyer", notes="",
   sp=dict(name="Camille Duarte", email="camille.duarte@thistledown.example.com", title="General Partner",
     org="Thistledown Ventures", bio="Camille is a general partner at Thistledown Ventures and previously built the first enterprise sales organisation at a frontier AI lab. She has watched a lot of good products lose to worse ones with better paperwork.")),

 dict(t="Your Agent Needs a Sandbox, Not a Desert",
   ab="Everyone agrees agents need code execution. That agreement lasts right up until you ask how. The default answer is usually ‘my agent needs a full Linux VM’ — a convenient answer for sandbox vendors, and often wrong. In many real workflows the model does not need a whole computer: no arbitrary packages, no shell, no full interpreter, let alone the classic UNIX toolbox. It needs a small amount of safe, expressive compute — enough to write code, call tools, and keep intermediate state out of the context window. That is the idea behind Marlin, a minimal Python interpreter written in Rust and designed specifically for running agent-written code. For a surprisingly large class of agent systems, a curated set of tools in a custom runtime beats a full sandbox — not because sandboxes are bad, but because they solve a much larger problem than most embedded agents have, and you pay for that mismatch in complexity, cost, operational pain, and latency.",
   fmt=ER, size=SMALL, ind="Technology",
   hist="We began the interpreter in mid-2025; it has been embedded in production agent workloads at customer sites since January 2026.",
   out="Cold start 400ms to under 1ms for tool-call-shaped workloads — roughly a 100,000x difference at the extreme. Memory per concurrent agent 180MB to 4MB.",
   adv="Ask what your agent actually executes before you provision a computer for it. Ours mostly does arithmetic and string handling.",
   co="", vid="", notes="",
   sp=dict(name="Duncan Ashworth", email="duncan.ashworth@ironbark.example.com", title="Founder and Chief Executive Officer",
     org="Ironbark", bio="Duncan founded Ironbark and maintains widely-used open-source Python tooling. He has now written a Python interpreter, which he describes as a warning to others.")),

 dict(t="The Unreasonable Effectiveness of BM25 for Agentic Search",
   ab="Frontier models are shockingly good at search, and that changes the ‘BM25 as a baseline’ story. Using search trajectories from a public browsing benchmark, this talk shows how default BM25 parameters and stock evaluation harnesses can make lexical retrieval look weak, while real agent queries often play directly to BM25's strengths. Much as grep became a core retrieval primitive for coding agents, lexical search is re-emerging as a powerful primitive for agentic search — and the tuning that matters is not the one the literature spent twenty years on.",
   fmt=SME, size=SMALL, ind="Technology",
   hist="We have shipped search infrastructure since 2024; the agentic retrieval work described here ran through the second half of 2025.",
   out="On the benchmark, correctly-parameterised BM25 closed 70% of the reported gap to dense retrieval at roughly 3% of the serving cost. Index build time for 10M documents: 4 minutes on one machine.",
   adv="Re-run your baseline with your agent's actual queries before you buy a vector database. Ours looked nothing like the benchmark queries.",
   co="", vid="https://www.youtube.com/watch?v=example-lexeme-bm25", notes="",
   sp=dict(name="Henrik Solberg", email="henrik.solberg@lexemesearch.example.com", title="Chief Executive Officer",
     org="Lexeme Search", bio="Henrik founded Lexeme Search after fifteen years in large-scale search engineering. He has been defending lexical retrieval at conferences for most of that time.")),
]

# --- The 8 invented vendory submissions --------------------------------------
VENDOR = [
 dict(t="Revolutionizing DevSecAIOps with Agentic Synergy",
   ab="Enterprises are drowning in silos. Our unified agentic synergy platform breaks down the walls between Dev, Sec, AI, and Ops with a single pane of glass powered by next-generation intelligence. In this session we unveil the DevSecAIOps Maturity Wheel™ and show how forward-leaning organisations are unlocking exponential value through synergistic agent orchestration. Attendees will discover the four pillars, the seven accelerators, and why 2026 is the year of the autonomous enterprise. Live demonstration included.",
   fmt=ER, size=MID, ind="Technology",
   hist="Our AI transformation journey began at inception. We are AI-first, AI-native, and AI-forward across the entire value chain.",
   out="Customers report significant improvements in velocity, quality, and morale. Specific figures are available under NDA.",
   adv="Partner with a platform that meets you where you are on your journey.",
   co="", vid="", notes="We are also exploring sponsorship for the event and would love to discuss how a speaking slot could be part of that conversation.",
   sp=dict(name="Chad Vandermeer", email="chad.vandermeer@synergiq.example.com", title="Chief Evangelist",
     org="SynergIQ Platforms", bio="Chad is Chief Evangelist at SynergIQ Platforms and a recognised thought leader in the agentic transformation space. He has presented at over 40 conferences worldwide.")),

 dict(t="The 7 Pillars of Enterprise AI Readiness: A Proven Framework",
   ab="After advising dozens of Fortune 500 organisations, we have distilled AI transformation into a repeatable seven-pillar framework: Vision, Governance, Data, Talent, Platform, Culture, and Value. This session walks through each pillar, the maturity scoring rubric, and the transformation roadmap that follows. Attendees receive a complimentary readiness assessment and a copy of our framework whitepaper. Ideal for executives beginning or accelerating their enterprise AI journey.",
   fmt=SME, size=SMALL, ind="Other",
   hist="Our practice has guided AI transformation engagements since 2023 across financial services, healthcare, and the public sector.",
   out="Clients consistently report accelerated time-to-value and improved stakeholder alignment. One client described the framework as ‘a game changer’.",
   adv="Do not start with technology. Start with a readiness assessment.",
   co="", vid="", notes="Happy to bring a client logo slide if that helps the committee evaluate credibility.",
   sp=dict(name="Roland Pike", email="roland.pike@northmeridianadvisory.example.com", title="Managing Partner",
     org="North Meridian Advisory", bio="Roland is Managing Partner at North Meridian Advisory and leads the firm's AI transformation practice. He is the author of two books on digital operating models.")),

 dict(t="Trustless AI: Verifiable Agent Provenance on the Decentralized Ledger",
   ab="How can you trust an agent you cannot audit? We present a decentralized provenance layer that anchors every agent action to an immutable on-chain record, enabling trustless verification across organisational boundaries. Our protocol combines zero-knowledge attestation with a token-incentivised validator network to create the first truly permissionless audit trail for autonomous systems. We will cover the architecture, the tokenomics, and the roadmap toward a fully decentralized agent economy. Early participants in the ecosystem programme will be recognised on stage.",
   fmt=SME, size=SMALL, ind="Technology",
   hist="Our protocol launched its testnet in late 2025. Mainnet is targeted for shortly after the conference.",
   out="Over 12,000 wallets have interacted with the testnet and the community has grown to 40,000 members across our channels.",
   adv="Begin evaluating decentralized provenance before regulation forces the conversation.",
   co="", vid="", notes="",
   sp=dict(name="Blake Ferriter", email="blake.ferriter@veritychain.example.io", title="Co-Founder and Head of Ecosystem",
     org="VerityChain Protocol", bio="Blake co-founded VerityChain Protocol after several years in decentralized infrastructure. He is a frequent speaker on the future of trustless systems.")),

 dict(t="Something Big Is Coming: The Future of Work, Unveiled",
   ab="We cannot say much yet. What we can say is that on stage in Charlotte we will be revealing something that fundamentally changes how enterprises think about knowledge work — the result of eighteen months of stealth development with design partners across three industries. This session will include the first public demonstration, a discussion of the underlying architecture, and a limited early-access programme for attendees in the room. Come see it first.",
   fmt=ER, size=SMALL, ind="Technology",
   hist="We have been in stealth since 2024. Details of our production deployments will be shared at the session.",
   out="We will share metrics on stage. Our design partners have seen results we are not yet able to publish.",
   adv="Keep an eye on this space.",
   co="", vid="", notes="We would need to keep the title and abstract vague in the printed program until our launch date. Hope that is workable.",
   sp=dict(name="Serena Kellogg", email="serena.kellogg@stealthmodeai.example.com", title="Chief Executive Officer",
     org="Halcyon Works (stealth)", bio="Serena is the CEO and co-founder of a stealth-mode AI company. She previously held product leadership roles at two enterprise software companies.")),

 dict(t="10x Your Engineering Org with Agentic AI (Live Demo)",
   ab="Engineering leaders are under pressure to do more with less. In this fast-paced session we demonstrate, live, how our agentic development platform delivers a 10x improvement in engineering throughput without changing your existing stack. We will build a complete production application on stage in under thirty minutes, including tests, CI, and deployment. Attendees will see exactly how leading engineering organisations are eliminating the bottlenecks that have held back software delivery for decades.",
   fmt=ER, size=SMALL, ind="Technology",
   hist="Our platform has been generally available since 2025 and is used by engineering teams worldwide.",
   out="Customers report up to 10x throughput improvements. Our internal benchmark shows a 92% reduction in time-to-first-commit.",
   adv="Stop optimising process. Start deploying agents.",
   co="", vid="", notes="We need reliable AV and a hardwired network connection for the live demo, plus a confidence monitor. The demo is the whole talk.",
   sp=dict(name="Travis Okonkwo-Bell", email="travis.okonkwobell@velocitor.example.com", title="VP of Developer Advocacy",
     org="Velocitor AI", bio="Travis leads developer advocacy at Velocitor AI and has demonstrated the platform to more than 5,000 engineers. He believes the future is already here, just unevenly deployed.")),

 dict(t="Beyond Chatbots: Unlocking the Agentic Enterprise Value Chain",
   ab="Most enterprises are still stuck in chatbot thinking. The winners are moving to end-to-end agentic value chains that span every function. Drawing on our proprietary industry benchmark of 500 enterprises, this session reveals the three archetypes of agentic maturity, the value-chain map that separates leaders from laggards, and the operating model changes required to capture the opportunity. We close with a preview of our 2027 Agentic Enterprise Index.",
   fmt=SME, size=BIG, ind="Other",
   hist="Our research programme has tracked enterprise AI adoption since 2022 across 500 organisations.",
   out="Leaders in our index report 2.4x the value capture of laggards. Detailed methodology is available to research subscribers.",
   adv="Benchmark yourself against your industry cohort before setting targets.",
   co="", vid="", notes="",
   sp=dict(name="Philippa Grantham-Rowe", email="philippa.granthamrowe@axiomindex.example.com", title="Global Head of AI Research",
     org="Axiom Index Group", bio="Philippa leads AI research at Axiom Index Group and oversees the firm's enterprise benchmarking programme. She advises boards on AI strategy across four continents.")),

 dict(t="From Prompt to Profit: Monetizing Your AI Investment",
   ab="Every enterprise is spending on AI. Few can articulate the return. This session presents our value-realisation methodology, developed across more than 200 client engagements, and shows how to build the business case, instrument the value, and defend the spend at board level. We will share our ROI calculator template and walk through three anonymised client case studies where the methodology turned a stalled programme into a funded one.",
   fmt=SME, size=BIG, ind="Other",
   hist="Our AI value-realisation practice was established in 2024 and has supported more than 200 engagements.",
   out="Clients using the methodology report an average 3.1x return on AI investment within eighteen months, based on client-reported figures.",
   adv="Instrument value from day one. Retrofitting a business case is the hardest work in the programme.",
   co="A client CIO may be able to join, subject to their approvals.",
   vid="", notes="",
   sp=dict(name="Desmond Fairweather", email="desmond.fairweather@claridgepartners.example.com", title="Senior Principal",
     org="Claridge Partners", bio="Desmond is a senior principal at Claridge Partners specialising in AI value realisation. He has advised more than 200 organisations on technology investment cases.")),

 dict(t="Zero-Trust Agentic Observability: The Next Frontier of AI SecOps",
   ab="As agents proliferate, the attack surface explodes. Our next-generation platform delivers zero-trust agentic observability across the full AI lifecycle, correlating signals from every layer into a unified risk posture. This session introduces the Agentic Threat Kill Chain™ and demonstrates how security teams can gain complete visibility without impacting developer velocity. We will also announce a significant product capability during the session.",
   fmt=SME, size=MID, ind="Technology",
   hist="We have been securing AI workloads since our founding and released our agentic module in 2025.",
   out="Customers achieve full visibility in under thirty days. Quantitative outcomes vary by environment and are available in our customer case studies.",
   adv="Do not let your agent deployment outpace your observability strategy.",
   co="", vid="", notes="We would like to time the session to coincide with our product announcement, so we would need the slot confirmed by mid-September.",
   sp=dict(name="Dorian Vasquez-Hale", email="dorian.vasquezhale@sentrymesh.example.com", title="Field CTO",
     org="SentryMesh Security", bio="Dorian is a Field CTO at SentryMesh Security and a regular commentator on AI security trends. He holds several industry certifications and speaks frequently on the conference circuit.")),
]

SUBS = REAL + VENDOR

GENE, ANN, ALEX = "genek@itrevolution.net", "annp@itrevolution.net", "alex@itrevolution.net"

# --- Scripted review activity -------------------------------------------------
# key = index into SUBS; value = ordered list of events (relative order preserved)
#   ("r", who, stars) | ("c", who, body) | ("s", status)
R = {
 0: [("r", GENE, 4.0), ("c", GENE, "Real build story, real pivots. Would rate higher with a business co-presenter and an outcome the CIO in row three cares about."),
     ("r", ANN, 3.5), ("c", ANN, "Strong stage skills on the video link. Tech-vendor-flavoured but the failure cases are genuine.")],
 1: [("r", GENE, 5.0), ("c", GENE, "This is the talk. A bank running unattended research campaigns on its own cluster, with numbers. Top of my list."),
     ("r", ANN, 4.5), ("c", ANN, "Agreed. Flagging the three-week compliance review — if we accept, we need the deck by early September or we are gambling."),
     ("r", ALEX, 4.5), ("c", ALEX, "[pc] Can we get the Head of Quantitative Research on stage too? Two-hander would land harder with our audience."),
     ("s", "Accept Queue")],
 2: [("r", ANN, 4.0), ("c", ANN, "Citation-click-through as a trust proxy is a genuinely new idea to me."),
     ("r", ALEX, 3.5), ("c", ALEX, "Healthcare vendor, but the ‘zero autonomous orders’ line is the opposite of a pitch. Fine by me.")],
 3: [("r", GENE, 2.0), ("c", GENE, "Vendor pitch IMHO. The checklist is fine; the numbers are all ‘across our customers’, which is marketing arithmetic."),
     ("r", ALEX, 4.0), ("c", ALEX, "Disagree strongly. The infrastructure-not-culture argument is the one our audience most needs to hear, and nobody else submitted it."),
     ("c", GENE, "Then let's find a practitioner who lived it rather than the vendor who sells the checklist."),
     ("r", ANN, 3.0), ("c", ANN, "Splitting the difference. Would reconsider at 4 if they brought a named customer on stage.")],
 4: [("r", ALEX, 4.0), ("c", ALEX, "139 papers is a real body of work and the misconfiguration finding is counterintuitive. Sponsor-adjacent but it earns the slot."),
     ("r", GENE, 3.5), ("c", GENE, "Good content. Title is doing more work than the abstract — ask them to keep it.")],
 5: [("r", GENE, 3.0), ("c", GENE, "Outputs, not outcomes. ‘61% of PRs merged unedited’ tells me about their agent, not about anyone's business."),
     ("r", ANN, 2.5), ("c", ANN, "CEO of the vendor presenting their own product. The note about bringing failure cases is at least self-aware."),
     ("r", ALEX, 3.0)],
 6: [("r", GENE, 4.5), ("c", GENE, "Proofs instead of vibes, with two design-partner banks and a published refusal rate. Unusual and rigorous — I want it."),
     ("r", ANN, 2.5), ("c", ANN, "Rigorous, yes. Also the most academic thing in the pile, and the last three talks like this emptied the room by minute twenty."),
     ("c", GENE, "The room emptied because those talks had no deployment. This one has two banks running it."),
     ("c", ANN, "Two design partners is not a deployment, it is a pilot with a nicer name. I am at 2.5 until someone shows me a production number."),
     ("r", ALEX, 4.0), ("c", ALEX, "[pc] Split the difference — accept it into a smaller room and see who stays.")],
 8: [("r", ALEX, 3.0), ("c", ALEX, "Solid plumbing talk. GTM audience at our event is thin, though."),
     ("r", GENE, 2.5), ("c", GENE, "Wrong room for us. Good talk, wrong room.")],
 9: [("r", GENE, 4.0), ("c", GENE, "Index-level data on the AI economy is genuinely rare and they are willing to show the base. That last part is why this is a 4 and not a 2."),
     ("r", ANN, 4.0), ("c", ANN, "Saw her at another conference — strong on stage, handles hostile questions well."),
     ("r", ALEX, 3.5)],
 10: [("r", ALEX, 4.5), ("c", ALEX, "12.9s versus 161.5s on an identical trajectory, with named stacks. This is the kind of measurement we say we want."),
      ("r", GENE, 4.0), ("c", GENE, "Yes — and their note about naming competitors is exactly the right thing to raise with us up front."),
      ("r", ANN, 3.5), ("c", ANN, "[pc] If we accept, someone needs to tell sponsorship before the program is printed, not after.")],
 11: [("r", GENE, 2.5), ("c", GENE, "Product launch with a research hat on. ‘90% token reduction’ is a vendor number about a vendor product."),
      ("r", ALEX, 3.0)],
 12: [("r", ANN, 3.5), ("c", ANN, "Data curation is underrated and this is the credible version of it."),
      ("r", GENE, 3.0), ("c", GENE, "Credible, but it is a model-training talk at an enterprise adoption event. Audience fit is my only reservation.")],
 13: [("r", ALEX, 3.0), ("c", ALEX, "Fine talk, well-trodden ground. We have had a document-parsing talk two years running."),
      ("r", GENE, 2.5)],
 14: [("r", GENE, 5.0), ("c", GENE, "‘Understanding is the new bottleneck’ is the best framing in this entire batch and the instrumentation backs it up. I would program this in a main-stage slot."),
      ("r", ANN, 4.5), ("c", ANN, "Agreed, and the video shows he can carry a room without slides doing the work."),
      ("r", ALEX, 4.5),
      ("s", "Accept Queue")],
 15: [("r", ALEX, 4.0), ("c", ALEX, "Infrastructure at genuine scale with cost numbers. Slightly deep for our median attendee but worth it."),
      ("r", ANN, 3.5), ("c", ANN, "Note the co-presenter may change — flagged in their submission, which I appreciate.")],
 16: [("r", GENE, 4.5), ("c", GENE, "A panel where every panellist has agreed in advance to bring one number is a panel worth having. I would take this over two of the solo talks above it."),
      ("r", ANN, 2.0), ("c", ANN, "Four panellists and one of them unconfirmed. Panels with travel risk are how we end up with three people agreeing with each other for forty minutes."),
      ("c", GENE, "Then we make the numbers a condition of acceptance and hold them to it."),
      ("c", ANN, "We said that in 2024 as well. I am at 2 until the roster is locked."),
      ("r", ALEX, 3.5), ("c", ALEX, "[pc] Would rate higher with the roster confirmed. Can we ask for a named substitute for the unconfirmed seat?")],
 17: [("r", ANN, 3.0), ("c", ANN, "Graph vendor arguing for graphs. The multi-hop benchmark numbers are at least specific."),
      ("r", GENE, 2.5), ("c", GENE, "Vendor pitch IMHO, though a well-dressed one.")],
 18: [("r", GENE, 4.0), ("c", GENE, "11M unattended sessions and a published intervention rate. That is a real operational number from a real deployment."),
      ("r", ALEX, 4.0), ("c", ALEX, "Agreed. ‘Budget for the web being hostile’ is a great closing line to hand an audience.")],
 19: [("r", ALEX, 3.5), ("c", ALEX, "The six-generations framing is clean and the incident-response thread holds it together."),
      ("r", GENE, 3.0), ("c", GENE, "Eval vendor explaining why you need better evals. Content is decent; provenance is what it is.")],
 20: [("r", ANN, 3.0), ("c", ANN, "Workshop-shaped, and they say so. Ninety minutes with a lab is a room-and-power problem before it is a program problem."),
      ("r", ALEX, 3.0), ("c", ALEX, "[pc] If we want it, take the 45-minute version. We do not have hands-on wifi capacity in Charlotte.")],
 21: [("r", GENE, 5.0), ("c", GENE, "99% adoption, 70% of PRs, 15% fully autonomous, change failure rate flat. Numbers like these are the entire reason this event exists."),
      ("r", ANN, 5.0), ("c", ANN, "Yes. And they have already offered the SVP of Engineering as a co-presenter, which is the ask we normally have to make."),
      ("r", ALEX, 4.5), ("c", ALEX, "Keynote candidate. Let's not bury this in a track."),
      ("s", "Accept Queue")],
 22: [("r", ANN, 4.0), ("c", ANN, "Regulated financial AI with adoption numbers, presented by someone who was an accountant first. Good."),
      ("r", GENE, 3.5)],
 23: [("r", GENE, 4.5), ("c", GENE, "‘Compliance constraints become the architecture’ — that is the sentence. Zero incidents across 2.6M turns and an honest 11-month timeline."),
      ("r", ANN, 4.5), ("c", ANN, "Strongly agree. Also note their request about how we title it; we should honour that in the program."),
      ("r", ALEX, 4.0),
      ("s", "Accept Queue")],
 24: [("r", ALEX, 3.5), ("c", ALEX, "‘Most of our wins were subtractions’ is a good talk hiding inside a tooling talk."),
      ("r", GENE, 3.0), ("c", GENE, "Would rate higher with an enterprise adoption story rather than an internal benchmark.")],
 25: [("r", GENE, 4.5), ("c", GENE, "Two blocked agent actions that would have been incidents, told by the person who answers to the auditors. Yes."),
      ("r", ANN, 4.0), ("c", ANN, "And the security lead will disagree with him on stage. That is a session, not a slide deck."),
      ("r", ALEX, 4.5)],
 26: [("r", ANN, 4.0), ("c", ANN, "The reward-hacking regression that ran for eleven days is the honest kind of detail we keep asking for."),
      ("r", ALEX, 3.5)],
 27: [("r", ALEX, 3.0), ("c", ALEX, "Protocol talk from the company that wrote the protocol. Revocation-first is the right lesson, though."),
      ("r", GENE, 2.5), ("c", GENE, "Vendor pitch IMHO. Interesting one, but a pitch.")],
 28: [("r", GENE, 3.5), ("c", GENE, "Good bones. Three design partners is thin evidence for the claim in the title."),
      ("r", ALEX, 4.0)],
 29: [("r", ANN, 4.0), ("c", ANN, "A VC telling builders what buyers actually do. Different from everything else in the pile and she has the receipts."),
      ("r", GENE, 3.5), ("c", GENE, "[pc] The ‘ship the audit log a year early’ point alone justifies the slot.")],
 30: [("r", ALEX, 4.0), ("c", ALEX, "Contrarian, measured, and against their own commercial interest in the obvious direction. I enjoyed reading it."),
      ("r", GENE, 3.0)],
 31: [("r", GENE, 4.0), ("c", GENE, "‘Re-run your baseline with your agent's actual queries’ is advice that will save someone in our audience a procurement cycle."),
      ("r", ANN, 3.5)],
 # --- the vendory eight ---
 32: [("r", GENE, 1.0), ("c", GENE, "Vendor pitch IMHO. Also I counted a trademark symbol in the abstract."),
      ("r", ANN, 1.0), ("c", ANN, "‘Figures available under NDA’ is the whole review. Decline."),
      ("r", ALEX, 1.5), ("c", ALEX, "[pc] The sponsorship line in their notes should go to sponsorship, not to us. Forwarding."),
      ("s", "Decline Queue")],
 33: [("r", GENE, 1.5), ("c", GENE, "Seven pillars, a maturity rubric, and a complimentary assessment. We have seen this deck at every conference since 2011."),
      ("r", ANN, 2.0), ("c", ANN, "‘One client described the framework as a game changer’ is not a measurable outcome, it is a testimonial."),
      ("r", ALEX, 1.5)],
 34: [("r", ALEX, 1.0), ("c", ALEX, "Tokenomics. At an enterprise AI summit. In 2026."),
      ("r", GENE, 1.0), ("c", GENE, "No. And the ‘early ecosystem participants recognised on stage’ line makes it worse."),
      ("s", "Decline Queue")],
 35: [("r", GENE, 1.0), ("c", GENE, "‘We will reveal the product at the talk.’ We would be selling our audience's attention for their launch. No."),
      ("r", ANN, 1.5), ("c", ANN, "They want the title and abstract kept vague in the printed program. That is a hard no on operational grounds alone."),
      ("r", ALEX, 3.5), ("c", ALEX, "Contrarian view: a genuine first-look draws a room, and every conference that ever landed one got it by taking this exact risk. I would rather we decide than reflex-decline."),
      ("c", GENE, "Happy to be wrong if they tell US what it is under embargo. Until then we are selling our audience's attention for their launch, sight unseen."),
      ("c", ALEX, "[pc] Then let's ask for the embargoed briefing before we decide, rather than arguing about a blank page.")],
 36: [("r", ANN, 1.5), ("c", ANN, "‘10x’ in the title and a live build in thirty minutes. The demo gods have never once shown up for us."),
      ("r", GENE, 1.0), ("c", GENE, "Outputs, not outcomes — and ‘92% reduction in time-to-first-commit’ is measuring the wrong end of the pipeline anyway."),
      ("r", ALEX, 1.5), ("c", ALEX, "They need AV, hardwired network, and a confidence monitor for a talk that is entirely a demo. The risk is all ours.")],
 37: [("r", GENE, 2.0), ("c", GENE, "Analyst research is not a talk, it is a slide handout with a person standing next to it."),
      ("r", ALEX, 2.0), ("c", ALEX, "‘Methodology available to research subscribers’ — so the evidence is behind a paywall. Would rate higher with the methodology in the open.")],
 38: [("r", ANN, 2.0), ("c", ANN, "ROI calculator template. Every year, someone."),
      ("r", GENE, 1.5), ("c", GENE, "‘Average 3.1x return, based on client-reported figures’ is a sentence that reviews itself."),
      ("r", ALEX, 2.0), ("c", ALEX, "[pc] Can we land one of their client CIOs instead? The buyer's version of this talk would actually be good.")],
 39: [("r", ALEX, 1.5), ("c", ALEX, "Two trademark symbols and a product announcement they want us to schedule around."),
      ("r", GENE, 1.0), ("c", GENE, "Vendor pitch IMHO, with a kill chain."),
      ("s", "Decline Queue")],
}
# Deliberate coverage gaps: submissions nobody has touched yet, so the
# "who still needs reviews?" work queue has a real top. Mostly deadline-day
# arrivals plus a few that slipped through the trickle — exactly how it goes.
GAPS = [7, 12, 13, 19, 20, 22, 26, 28, 30, 31, 33, 37]
for _k in GAPS:
    R.pop(_k, None)
assert len(R) == 28, len(R)

# --- Timeline construction ----------------------------------------------------
DAY = 86400
# Submission arrival pattern over a simulated 21 days: front-loaded burst,
# a long trickle, then a deadline-day spike.
def submission_offsets(n):
    """Hand-shaped arrival curve, jittered deterministically."""
    burst = [0, 1300, 4700, 9800, 21000, 33500, 47000, 61000, 74000, 96000,
             118000, 141000]                                   # days 0-2
    trickle_days = [3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17]
    trickle = [d * DAY + random.randint(30000, 76000) for d in trickle_days]
    trickle += [d * DAY + random.randint(30000, 76000) for d in (4, 7, 11, 14, 16)]
    # CFP closes at the end of day 18; the committee's scramble runs on past it.
    spike_day = 18
    spike = sorted(spike_day * DAY + s for s in
                   [21600, 39000, 52000, 57600, 61000, 66000, 70200, 74400])
    offs = sorted(burst + sorted(trickle) + spike)
    assert len(offs) == n, (len(offs), n)
    return offs

# Which submission arrives when: mix real + vendory across the whole window,
# and put several vendory ones in the deadline-day spike (as they always are).
ORDER = [21, 14, 1, 0, 23, 3, 32, 9, 25, 4, 10, 2,       # burst (days 0-2)
         15, 5, 33, 18, 7, 12, 22, 30, 34, 8, 26, 19,    # trickle
         11, 17, 24, 6, 31, 13, 29, 27,
         16, 36, 20, 35, 28, 38, 37, 39]                 # deadline-day spike
assert sorted(ORDER) == list(range(len(SUBS))), "ORDER must cover every submission"

def build():
    offs = submission_offsets(len(SUBS))
    sub_at = {}
    events = []
    for off, idx in zip(offs, ORDER):
        s = SUBS[idx]
        sub_at[idx] = off
        answers = {
            "talk-title": s["t"], "abstract": s["ab"],
            "session-format": s["fmt"], "org-size": s["size"],
            "industry": s["ind"],
            "ai-transformation-history": s["hist"],
            "measurable-outcomes": s["out"],
        }
        for fid, key in (("advice-to-peer", "adv"), ("business-co-presenter", "co"),
                         ("prior-talk-video", "vid"), ("notes-to-committee", "notes")):
            if s.get(key):
                answers[fid] = s[key]
        events.append({
            "offset-secs": off, "kind": "submission",
            "submission": {"answers": answers, "speaker": s["sp"]},
        })

    # Review activity: interleaved, always strictly after the submission exists.
    for idx, script in R.items():
        # A reviewer picks a submission up within a day or so of it landing,
        # then the thread plays out over the following few days.
        t = sub_at[idx] + random.randint(2 * 3600, 26 * 3600)
        for step in script:
            t += random.randint(900, 20 * 3600)
            # never spill past the end of the simulated window
            t = min(t, 21 * DAY - random.randint(600, 86000))
            if step[0] == "r":
                events.append({"offset-secs": t, "kind": "rating",
                               "on-title": SUBS[idx]["t"], "by": step[1],
                               "stars": step[2]})
            elif step[0] == "c":
                events.append({"offset-secs": t, "kind": "comment",
                               "on-title": SUBS[idx]["t"], "by": step[1],
                               "body": step[2]})
            else:
                events.append({"offset-secs": t, "kind": "status",
                               "on-title": SUBS[idx]["t"], "by": GENE,
                               "to": step[1]})

    events.sort(key=lambda e: (e["offset-secs"], 0 if e["kind"] == "submission" else 1))
    return events

def main():
    events = build()
    n_sub = sum(1 for e in events if e["kind"] == "submission")
    out = {
        "meta": {
            "source": "ai.engineer worldsfair sessions.json + speakers.json, fetched 2026-08-08",
            "anonymization": "All speakers and organisations renamed to invented ones; titles and abstracts adapted from the public program. No real person or company name appears anywhere in this file.",
            "count": n_sub,
            "invented-count": len(VENDOR),
            "adapted-count": len(REAL),
            "reviewed-count": len(R),
            "window-secs": 21 * DAY,
            "field-ids": ["talk-title", "abstract", "session-format", "org-size",
                          "industry", "ai-transformation-history",
                          "measurable-outcomes", "advice-to-peer",
                          "business-co-presenter", "prior-talk-video",
                          "notes-to-committee"],
            "reviewers": [GENE, ANN, ALEX],
            "generated-by": "resources/replay/build_corpus.py (see resources/replay/README.md)",
        },
        "timeline": events,
    }
    dest = "/Users/genekim/src.local/sessionize-sched-killer/resources/replay/aie-corpus.json"
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    with open(dest, "w") as f:
        json.dump(out, f, indent=1, ensure_ascii=False)
        f.write("\n")
    from collections import Counter
    print("wrote", dest)
    print("events", len(events), Counter(e["kind"] for e in events))
    print("submissions", n_sub, "reviewed", len(R), "gaps", n_sub - len(R))

if __name__ == "__main__":
    main()
