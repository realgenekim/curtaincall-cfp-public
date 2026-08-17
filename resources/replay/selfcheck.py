#!/usr/bin/env python3
"""Self-check the replay corpus: no real people/orgs, fictional emails, valid JSON."""
import json, re, sys
from collections import Counter

CORPUS = "/Users/genekim/src.local/sessionize-sched-killer/resources/replay/aie-corpus.json"
SP = "speakers.json"
SE = "sessions.json"

doc = json.load(open(CORPUS))
blob = json.dumps(doc, ensure_ascii=False)
speakers = json.load(open(SP))["speakers"]
sessions = json.load(open(SE))["sessions"]

# --- universe of real names/orgs to forbid -----------------------------------
# Rule (documented in resources/replay/README.md): we forbid every real
# speaker FULL NAME, every real speaker SURNAME, and every real COMPANY name,
# matched case-SENSITIVELY on word boundaries. Lowercase common English that
# happens to also be somebody's company name ("daily", "elastic", "boundary")
# is not a leak; a capitalised occurrence would be, so case matters.
forbidden = set()
for s_ in speakers:
    if s_.get("name"):
        forbidden.add(s_["name"].strip())
        parts = s_["name"].split()
        if len(parts) > 1 and len(parts[-1]) > 3:
            forbidden.add(parts[-1].strip())
    if s_.get("company"):
        forbidden.add(s_["company"].strip())
forbidden |= {
    "Anthropic", "OpenAI", "Claude", "Gemini", "GPT-5", "Opus 4.6", "GPT 5.4",
    "Thomson Reuters", "Arcee", "Cloudflare", "Firecrawl", "monday.com", "Cogny",
    "Conviction", "Stanford HAI", "AlphaLab", "Autopilot", "Alyx", "Automind",
    "Universalis", "Antigravity", "PatchPilot", "auth.md", "OpenClaw", "CrabRAG",
    "BrowseComp-Plus", "ShadowRay", "CL-Bench", "Uber Eats", "MCP Toolbox",
    "DSPy", "LangChain", "LangGraph", "Oracle", "Yutori", "Scouts", "Monty",
    "Sessionize", "Figma", "Pinecone", "Neo4j", "Braintrust", "Daytona",
}
# Terms that are generic technology or English, kept deliberately.
GENERIC_OK = {"Kubernetes", "Python", "Rust", "Markdown", "BM25", "MCP",
              "Factory", "Technology", "Product", "Security", "Research",
              "Engineering", "Design", "Growth", "Data", "Platform", "Vision"}
# "ai.engineer" appears once, on purpose, in meta.source (provenance).
PROVENANCE_OK = {"ai.engineer"}

hits = []
for term in sorted(forbidden):
    t = term.strip()
    if not t or len(t) < 4 or t in GENERIC_OK or t in PROVENANCE_OK:
        continue
    pat = r"(?<![A-Za-z0-9.-])" + re.escape(t) + r"(?![A-Za-z0-9.-])"
    if re.search(pat, blob):          # case-SENSITIVE
        hits.append(t)

print("=== forbidden-term hits ===")
if hits:
    for h in hits:
        m = re.search(r".{70}" + re.escape(h) + r".{70}", blob, re.IGNORECASE)
        print(f"  !! {h!r} :: ...{m.group(0) if m else ''}...")
else:
    print("  none — clean")

# --- real titles reused verbatim? (allowed, but report how many) -------------
real_titles = {s["title"].strip() for s in sessions}
corpus_titles = [e["submission"]["answers"]["talk-title"]
                 for e in doc["timeline"] if e["kind"] == "submission"]
verbatim = [t for t in corpus_titles if t in real_titles]
print(f"\n=== titles kept verbatim from program: {len(verbatim)} of {len(corpus_titles)} ===")
for t in verbatim:
    print("   ", t)

# --- emails ------------------------------------------------------------------
emails = re.findall(r'"email": "([^"]+)"', blob)
bad = [e for e in emails if ".example." not in e]
print(f"\n=== emails: {len(emails)} speaker emails, non-example: {bad} ===")
pc = {e["by"] for e in doc["timeline"] if "by" in e}
print("    reviewer addresses:", sorted(pc))

# --- structure ---------------------------------------------------------------
tl = doc["timeline"]
print("\n=== structure ===")
print("  kinds:", Counter(e["kind"] for e in tl))
print("  monotonic offsets:", all(tl[i]["offset-secs"] <= tl[i+1]["offset-secs"]
                                  for i in range(len(tl)-1)))
first = {}
for e in tl:
    if e["kind"] == "submission":
        first[e["submission"]["answers"]["talk-title"]] = e["offset-secs"]
orphans = [e for e in tl if e["kind"] != "submission" and
           (e["on-title"] not in first or e["offset-secs"] <= first[e["on-title"]])]
print("  review events before/without their submission:", len(orphans))
print("  window (days):", round(tl[-1]["offset-secs"] / 86400, 2))
print("  dup titles:", [t for t, c in Counter(corpus_titles).items() if c > 1])

# formats / sizes / industries actually used
fa = [e["submission"]["answers"] for e in tl if e["kind"] == "submission"]
print("  formats:", Counter(a["session-format"] for a in fa))
print("  org-size:", Counter(a["org-size"] for a in fa))
print("  industries:", Counter(a["industry"] for a in fa))
print("  blank prior-talk-video:", sum(1 for a in fa if "prior-talk-video" not in a))
print("  with notes-to-committee:", sum(1 for a in fa if "notes-to-committee" in a))
print("  with business-co-presenter:", sum(1 for a in fa if "business-co-presenter" in a))
print("  abstract chars: min %d max %d mean %d" % (
    min(len(a["abstract"]) for a in fa), max(len(a["abstract"]) for a in fa),
    sum(len(a["abstract"]) for a in fa) // len(fa)))

# --- splits ------------------------------------------------------------------
by_title = {}
for e in tl:
    if e["kind"] == "rating":
        by_title.setdefault(e["on-title"], []).append(e["stars"])
splits = {t: v for t, v in by_title.items() if max(v) - min(v) >= 2}
print(f"\n=== splits (>=2.0 spread): {len(splits)} ===")
for t, v in splits.items():
    print(f"    {sorted(v)}  {t}")
print("  reviewed submissions:", len(by_title), "| unrated:", len(corpus_titles) - len(by_title))
print("  stars all valid halves:", all(abs(s*2 - round(s*2)) < 1e-9 and 1.0 <= s <= 5.0
                                       for v in by_title.values() for s in v))
print("  status moves:", [(e["to"], e["on-title"][:40]) for e in tl if e["kind"] == "status"])
sys.exit(1 if hits or bad or orphans else 0)
