(ns generate-aie-submissions
  "Operator script, 2026-08-10 (Gene): populate AI Engineer Code Summit with
   500 submissions, in inspectable waves (~100 at a time, Gene signs off
   between waves). Every submission is born through sub/create-submission!
   with parse-answers against the LIVE form — the same door a real speaker
   walks through, validation included.

   Content: AI-engineer voice (agents, evals, RAG, GPUs), all speakers and
   companies fictional (bd 9ot rule: never a real company on a fabricated
   submission). Deterministic by (wave, index) so waves are reproducible;
   the wave guard makes re-running a wave a no-op (it checks how many
   aie-wave submissions already exist).

   Loading defines fns only; mutations happen via explicit (run-wave! n)."
  (:require
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.forms :as forms]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as sub]
   [clojure.string :as str]))

(def event-slug "ai-engineer-code-summit-2026")
(def wave-size 100)

(defn- h* [& xs] (Math/abs (long (hash (str/join "|" (map str xs))))))
(defn- pick [h coll] (nth coll (mod h (count coll))))

;; --- Fictional people and companies -----------------------------------------

(def first-names
  ["Riley" "Sam" "Jordan" "Casey" "Morgan" "Avery" "Quinn" "Dakota"
   "Noor" "Wei" "Priya" "Mateo" "Ines" "Kofi" "Sasha" "Tomas"
   "Amara" "Jonas" "Leila" "Marcus" "Yuki" "Elena" "Ravi" "Freya"
   "Diego" "Hana" "Owen" "Zara" "Felix" "Nadia"])

(def last-names
  ["Okonkwo" "Lindqvist" "Ferreira" "Tanaka" "Brennan" "Osei" "Kowalski"
   "Ramírez" "Chen" "Novak" "Silva" "Varga" "Fischer" "Ivanova" "Mbeki"
   "Nakamura" "Devlin" "Whitfield" "Bergström" "Costa" "Halvorsen"
   "Adeyemi" "Petrov" "Marchetti" "Ohara" "Sandoval" "Kaur" "Eriksen"])

(def startups
  ["Tokensmith" "Halcyon Retrieval" "Quiet Fox Labs" "Parallax Loop"
   "EvalForge" "Crescent Context" "Warm Boot AI" "Stack of Turtles"
   "Sixteen Tokens" "Doubtful Oracle" "Beacon Loop" "Latent Harbor"
   "Gradient Grove" "Patchwork AI" "Softmax & Sons" "Recall Industries"
   "The Embedding Company" "Chunk Norris AI" "Off-Policy Labs" "Vector Meadow"])

(def enterprises
  ["Ironbridge Industries" "Skyward Communications" "Blue Harbor Bank"
   "Gulfstream Power" "Meridian Assurance" "Cascade Precision"
   "Northfield Logistics" "Beacon Health Systems"])

(def roles-startup
  ["Founding Engineer" "CTO" "Head of AI" "Staff Engineer"
   "AI Platform Lead" "Principal Engineer" "VP Engineering"])

(def roles-enterprise
  ["Staff ML Engineer" "Director of AI Platform" "Principal Architect"
   "Head of Developer Experience" "VP of Engineering"])

;; --- Titles, by track --------------------------------------------------------

(def title-skeletons
  {"Agents in Production"
   ["What {n} Months of Agents in Production Taught Us About {topic}"
    "Our Agent Opened {pct}% of Our PRs. Here Is Everything That Broke First"
    "From Demo to On-Call: Running Agents Where the Pager Is Real"
    "The Agent Rollback: Why We Turned Ours Off for Six Weeks (and What Fixed It)"
    "Guardrails That Actually Guard: {n} Incidents We Caught Before Users Did"]
   "Evals & Observability"
   ["Vibes Don't Scale: The Eval Harness That Caught {n} Regressions"
    "Our Eval Suite Is {n}x the Size of Our Prompt. It Should Be Bigger"
    "Grading the Grader: When Your LLM Judge Is the Bug"
    "Observability for Token Streams: What We Trace, What We Ignore, What It Costs"
    "The {pct}% Regression Nobody Noticed for a Month"]
   "RAG & Retrieval"
   ["Chunking Is a Product Decision: {n} Retrieval Failures From Real Support Tickets"
    "We Deleted Our Vector Database and Got Better Answers"
    "Hybrid Retrieval at {qps} QPS: What Survived Contact With Production"
    "The Long Tail of RAG: Queries Where Embeddings Simply Lose"
    "Freshness Beats Similarity: Rebuilding Retrieval for a Moving Corpus"]
   "Infra & GPUs"
   ["The ${cost}k GPU Bill That Taught Us Capacity Planning"
    "Serving {n} Models on Hardware We Actually Own"
    "Batch, Cache, or Distill: A Decision Tree Priced in Real Dollars"
    "Cold Starts, Warm Pools, Hot Takes: Latency Engineering for LLM Inference"
    "What We Learned Running Inference in {n} Regions Nobody Recommends"]
   "Multimodal"
   ["Screenshots Are the Universal API: Vision Models in a Text-First Stack"
    "Voice In, Actions Out: Shipping a Multimodal Agent to {n}k Users"
    "The Document-Understanding Pipeline That Replaced {n} Vendors"
    "When OCR Lies: Multimodal Failure Modes From Production"]
   "AI Leadership"
   ["Rebuilding a {n}-Person Engineering Org Around AI Without Losing Anyone"
    "The AI Engineer Career Ladder We Actually Shipped"
    "Buying vs Building in {year}: Our Scorecard After {n} Decisions"
    "How We Budget for Tokens: FinOps for the LLM Era"]})

;; --- Abstracts: three paragraphs from birth ---------------------------------

(defn- abstract-p1 [h {:keys [org track pct n cost]}]
  (pick h
        [(str "At " org " we ship " (str/lower-case track) " work for a living, "
              "and this talk is the part we wish someone had told us a year ago. "
              "We will open with the numbers: " pct "% of our production traffic "
              "now touches this system, the path to get there consumed " n
              " months and roughly $" cost "k, and the first version was wrong "
              "in ways our dashboards were designed not to see.")
         (str "This is a production story from " org ", not a framework tour. "
              "The system in question handles real users, a real on-call "
              "rotation, and a real budget — and the gap between the launch "
              "blog post we drafted and the incident review we actually wrote "
              "is the material of this talk.")
         (str org " went from prototype to production in " n " months, and the "
              "honest version of that sentence includes two rewrites, one "
              "vendor breakup, and a " pct "% metric everyone quotes without "
              "asking how it is measured. We will show the receipts for all of it.")]))

(defn- abstract-p2 [h {:keys [track]}]
  (pick (quot h 7)
        [(str "The middle of the talk is the failure catalog: the eval that "
              "passed while users suffered, the retry storm that tripled our "
              "bill in an afternoon, and the incident where the model was "
              "innocent and our plumbing was not. Each failure comes with the "
              "trace, the fix, and the test that now guards it.")
         (str "We will walk the architecture as it exists today — not the "
              "diagram from the design doc, but the one with the arrows nobody "
              "planned: the cache that became load-bearing, the fallback path "
              "that runs more than the primary, and the " (str/lower-case track)
              " decisions we would defend versus the ones we merely inherited.")
         (str "Expect specifics over slogans: the exact metrics we page on, "
              "the review process for prompt changes (versioned, evaluated, "
              "boring on purpose), and the weekly ritual that keeps the whole "
              "team looking at real outputs instead of aggregate scores.")]))

(defn- abstract-p3 [h {:keys [org]}]
  (pick (quot h 11)
        [(str "You will leave with our decision checklist, the three eval "
              "patterns that caught the most real bugs per line of code, and "
              "a candid sense of what this actually costs to run. Everything "
              "shown is live at " org " today.")
         (str "This talk is for engineers about to ship their first serious "
              "AI system and for the ones cleaning up after it. We will share "
              "the templates, the numbers, and the two decisions we would "
              "reverse if we could.")
         (str "No slideware: every artifact is from our repo, every number "
              "from our dashboards. Bring your hardest questions — the "
              "hallway conversation is half the reason we submitted.")]))

;; --- History and outcomes ----------------------------------------------------

(defn- history-3p [h {:keys [org year n]}]
  (str/join
    "\n\n"
    [(pick h
           [(str org " started taking AI seriously in " year ", when a hack-week "
                 "prototype embarrassed the roadmap by solving a problem the "
                 "official plan had scheduled for the following year. The "
                 "prototype was wrong in a dozen ways, but it was wrong FAST, "
                 "and that changed what the organization believed was possible.")
            (str "The honest origin is " year ": a customer escalation that "
                 "should have taken minutes was taking days, and " org
                 " committed a small team to fix that one workflow with AI — "
                 "no platform ambitions, one problem, weekly demos.")])
     (pick (quot h 5)
           [(str "The middle stretch was unglamorous: " n " months of eval "
                 "infrastructure, data cleanup nobody tweets about, and a "
                 "governance fight over what the system may do unsupervised. "
                 "Two approaches died in that period — killed by latency and "
                 "by a cost curve that bent the wrong way — and the postmortems "
                 "for both shaped everything that survived.")
            (str "What followed was a long correction: the first architecture "
                 "assumed the model was the product, and every hard lesson "
                 "since has been about the system around it — retrieval "
                 "freshness, output verification, and the human handoff that "
                 "turns a confident wrong answer into a caught one.")])
     (pick (quot h 13)
           [(str "Today the practice is boring in the best way: versioned "
                 "prompts, evals in CI, a weekly output review, and a team "
                 "that treats model upgrades like dependency bumps — tested, "
                 "budgeted, reversible. The talk covers what broke on the way "
                 "to boring.")
            (str "Now the question has flipped from 'can we ship this' to "
                 "'which of the ten things we could ship is worth the tokens' — "
                 "a prioritization problem this talk treats with the same "
                 "rigor we once reserved for uptime.")])]))

(defn- outcomes-2p [h {:keys [pct n cost]}]
  (str/join
    "\n\n"
    [(pick h
           [(str "Hard numbers first: p95 latency for the target workflow fell "
                 pct "% while volume grew " n "x, the per-request cost dropped "
                 "from unmentionable to $0.0" (inc (mod h 9)) ", and the eval "
                 "suite now catches roughly four of every five regressions "
                 "before deploy — a number we measure, not estimate.")
            (str "The measurable core: " pct "% of the workflow is now handled "
                 "without human touch, human review time on the remainder is "
                 "down by half, and the fully-loaded run cost is under $" cost
                 "k a month — audited by finance, not marketing.")])
     (pick (quot h 5)
           [(str "The second-order outcomes surprised us more: on-call pages "
                 "for the legacy path dropped as the new system absorbed its "
                 "traffic, new engineers reach first-meaningful-change in days "
                 "instead of weeks, and one regression metric got WORSE before "
                 "our instrumentation could explain it — that story is in the "
                 "talk, because that is the part conference audiences never get.")
            (str "Just as valuable is what we can now measure that we could "
                 "not before: per-query retrieval quality, cost per resolved "
                 "ticket, and the honest denominator under every accuracy "
                 "claim. Two metrics are still contested internally; the talk "
                 "presents both sides rather than pretending consensus.")])]))

;; --- Assembling one submission ----------------------------------------------

(defn- fields [] (forms/active-fields
                   (forms/fields-for-event
                     (:id (events/event-by-slug event-slug)))))

(defn- gen-one
  "Deterministic params for submission k (global index across waves)."
  [k]
  (let [h (h* "aie" k)
        startup? (< (mod h 10) 7)
        org (if startup? (pick h startups) (pick h enterprises))
        fname (pick (quot h 3) first-names)
        lname (pick (quot h 7) last-names)
        speaker-name (str fname " " lname)
        email (str (str/lower-case fname) "." (str/lower-case lname) "+" k
                   "@" (-> org str/lower-case (str/replace #"[^a-z]" "")) ".example.com")
        role (if startup? (pick (quot h 11) roles-startup)
                 (pick (quot h 11) roles-enterprise))
        track-field (first (filter #(= "track" (forms/field-id %)) (fields)))
        track (pick (quot h 13) (vec (:options track-field)))
        vars {:org org :track track
              :year (str (+ 2022 (mod h 4)))
              :n (+ 3 (mod (quot h 17) 22))
              :pct (+ 20 (mod (quot h 19) 60))
              :cost (* 5 (+ 4 (mod (quot h 23) 56)))
              :qps (* 100 (+ 1 (mod (quot h 29) 40)))}
        title (-> (pick (quot h 31) (get title-skeletons track
                                         (get title-skeletons "Agents in Production")))
                  (str/replace "{n}" (str (:n vars)))
                  (str/replace "{pct}" (str (:pct vars)))
                  (str/replace "{cost}" (str (:cost vars)))
                  (str/replace "{qps}" (str (:qps vars)))
                  (str/replace "{topic}" (pick (quot h 37)
                                               ["Trust" "Latency" "Rollbacks"
                                                "Human Handoffs" "Cost"]))
                  (str/replace "{year}" (:year vars)))
        base {:answer-talk-title title
              :answer-abstract (str/join "\n\n" [(abstract-p1 h vars)
                                                 (abstract-p2 h vars)
                                                 (abstract-p3 h vars)])
              :answer-track track
              :answer-ai-transformation-history (history-3p h vars)
              :answer-measurable-outcomes (outcomes-2p h vars)
              :answer-industry (if startup? "Developer Tools / AI"
                                   (pick (quot h 41) ["Financial Services" "Energy"
                                                      "Telecom" "Healthcare" "Logistics"]))
              :answer-org-size (if startup?
                                 (pick (quot h 43) ["<1,000" "<1,000" "1,000–10,000"])
                                 ">10,000")
              :speaker-name speaker-name
              :speaker-email email
              :speaker-title role
              :speaker-org org
              :speaker-bio (str fname " " (pick (quot h 47)
                                                ["ships AI systems" "runs the AI platform"
                                                 "leads applied AI" "builds agents"])
                                " at " org ".")}
        ;; self-adapting: any other required session field gets a valid value
        extra (into {}
                    (keep (fn [f]
                            (let [id (forms/field-id f)
                                  kw (keyword (str "answer-" id))]
                              (when (and (:required f) (not (contains? base kw)))
                                [kw (if (seq (:options f))
                                      (pick (quot h 53) (vec (:options f)))
                                      (str "See abstract — details in the talk."))])))
                          (sub/session-fields (fields))))]
    (merge extra base)))

(defn existing-count []
  (let [ev (events/event-by-slug event-slug)]
    (count (filter #(= (:id ev) (:event-id %))
                   (vals (:submissions @store/state))))))

(defn preview [k] (gen-one k))

(defn run-wave!
  "Create wave `w` (0-based): submissions [w*100, w*100+100). Skips if the
   event already holds more than the wave's floor — waves are append-only
   and inspected between runs (Gene's gate)."
  [w]
  (let [ev (events/event-by-slug event-slug)
        ff (forms/active-fields (forms/fields-for-event (:id ev)))
        start (* w wave-size)
        have (existing-count)]
    (if (> have start)
      {:skipped true :have have :wave w
       :why "event already has submissions past this wave's floor"}
      (let [results
            (mapv (fn [k]
                    (try
                      (let [params (gen-one k)
                            s (sub/create-submission!
                                ev (sub/parse-answers ff params)
                                (sub/parse-speaker params) "form"
                                "operator:aie-wave-2026-08-10")]
                        {:ok (some? (:id s))})
                      (catch Exception e {:ok false :k k :err (.getMessage e)})))
                  (range start (+ start wave-size)))]
        {:wave w
         :created (count (filter :ok results))
         :failed (filterv (comp not :ok) results)
         :now-total (existing-count)}))))

(defn resume!
  "Create ONLY the missing indexes in [0, total). Recovery for a wave cut
   mid-flight: each generated speaker email embeds its index as +k@, so the
   set of landed k's is recoverable from the submissions themselves."
  [total]
  (let [ev (events/event-by-slug event-slug)
        ff (forms/active-fields (forms/fields-for-event (:id ev)))
        have (set (keep (fn [s]
                          (some->> (:email (first (:speakers s)))
                                   (re-find #"\+(\d+)@")
                                   second
                                   Long/parseLong))
                        (filter #(= (:id ev) (:event-id %))
                                (vals (:submissions @store/state)))))
        missing (remove have (range total))
        results (mapv (fn [k]
                        (try
                          (let [params (gen-one k)]
                            (sub/create-submission!
                              ev (sub/parse-answers ff params)
                              (sub/parse-speaker params) "form"
                              "operator:aie-wave-2026-08-10")
                            {:ok true})
                          (catch Exception e {:ok false :k k :err (.getMessage e)})))
                      missing)]
    {:was (count have) :missing (count missing)
     :created (count (filter :ok results))
     :failed (filterv (comp not :ok) results)
     :now (existing-count)}))
