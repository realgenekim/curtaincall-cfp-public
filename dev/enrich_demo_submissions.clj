(ns enrich-demo-submissions
  "Operator script, 2026-08-10 (Gene): the demo submissions read too thin —
   real CFP submissions run longer. Abstract -> ~3x (3 paragraphs), the
   when-did-your-transformation-begin answer -> 3 paragraphs, measurable
   outcomes -> 2 paragraphs.

   HOW: every change goes through portal/update-answers!, which appends a
   submission.answers-updated fact carrying :before and :changes — nothing
   is overwritten, and the board's time-travel scrubber replays the growth.
   Originals are preserved verbatim: the old abstract IS paragraph one, the
   old outcomes line opens its paragraph, and the year in the old history
   answer seeds the new origin story.

   Deterministic by (hash submission-id): the same submission always grows
   the same prose. NOTE (learned on the first live row): determinism alone
   is NOT idempotency — enriching an already-enriched submission compounds,
   because the grown abstract becomes the 'original' paragraph one. run-one!
   therefore guards on already-enriched? and skips.

   Loading this file defines functions only. Mutations happen exclusively
   through explicit REPL calls to run-one!/run-all!."
  (:require [clojure.string :as str]
            [cfp-scheduler-killer.store :as store]
            [cfp-scheduler-killer.events :as events]
            [cfp-scheduler-killer.portal :as portal]))

(def event-slug "enterprise-ai-summit-charlotte-2026")
(def actor "operator:demo-enrich-2026-08-10")

(defn- pick [h k coll] (nth coll (mod (quot h k) (count coll))))

(defn- vars-for
  "Everything the prose slots need, derived from the submission itself."
  [s]
  (let [h (Math/abs (long (hash (:id s))))
        a (:answers s)
        history (str (:ai-transformation-history a))
        ;; a submission must not contradict its own headline numbers: reuse
        ;; the $ figure and cycle-time % the seed text already claims
        prior (str (:abstract a) " " (:measurable-outcomes a))
        prior-dollars (re-find #"\$\d+(?:\.\d+)?M" prior)
        prior-cycle (second (re-find #"[Cc]ycle time[^.%]*?(\d+)%" prior))]
    {:h h
     :org (or (:org (first (:speakers s))) "our organization")
     :industry-lc (str/lower-case (or (:industry a) "enterprise"))
     :year (or (re-find #"20\d\d" history) (str (+ 2022 (mod h 3))))
     :pct (+ 25 (mod (quot h 7) 50))
     :pct2 (+ 15 (mod (quot h 19) 25))
     :pct3 (if prior-cycle (Long/parseLong prior-cycle) (+ 20 (mod (quot h 23) 45)))
     :pct4 (+ 20 (mod (quot h 29) 40))
     :dollars (or prior-dollars
                  (format "$%.1fM" (+ 1.2 (* 0.4 (mod (quot h 11) 18)))))
     :engineers (* 50 (+ 2 (mod (quot h 13) 14)))
     :months (+ 9 (mod (quot h 17) 22))
     :sponsor (pick h 31 ["CTO" "COO" "chief data officer" "VP of engineering"])
     :next (pick h 37 ["agent-to-agent workflows across team boundaries"
                       "extending the same discipline to our non-engineering functions"
                       "autonomous remediation in production, behind the same guardrails"])}))

(defn- abstract-p2 [{:keys [h months industry-lc pct]}]
  (pick h 3
        [(str "This talk is the unvarnished version of that journey. We will walk "
              "through the two rollouts that quietly died — one killed by a security "
              "review that took longer than the pilot, one by champions who left "
              "before the habits stuck — and the third attempt that finally held, "
              "because we stopped treating adoption as a tooling problem and started "
              "treating it as an operating-model change. Along the way we will show "
              "the actual dashboards we used to convince a skeptical CFO, including "
              "the week the numbers went backwards.")
         (str "We will cover the parts that never make it into vendor case studies: "
              "the " months "-month gap between the demo that wowed the executive "
              "team and the first week an agent's output shipped to production "
              "unedited; the review-queue bottleneck nobody predicted; and the "
              "moment we realized our measurement framework was rewarding the wrong "
              "behavior. Expect real artifacts — the rollout memos, the guardrail "
              "configs, and the adoption curves with the embarrassing plateau left in.")
         (str "Rather than a highlight reel, this session reconstructs the decisions "
              "in order: why we started in " industry-lc " workflows where the "
              "compliance stakes were highest (and why that turned out to be the "
              "right call), how we got " pct "% of our engineers past the trough of "
              "disillusionment, and which of our early bets we would not make again. "
              "We will show before-and-after numbers for every claim.")]))

(defn- abstract-p3 [{:keys [h dollars year org]}]
  (pick h 5
        [(str "Attendees will leave with the three-question framework we now use to "
              "greenlight any AI initiative, a copy of our internal adoption "
              "playbook, and a candid sense of what " dollars " in banked savings "
              "actually costs to earn. If you are somewhere between pilot and "
              "production, this is the map of the minefield.")
         (str "This talk is for leaders who suspect their pilot numbers are lying to "
              "them, and for engineers tired of transformation theater. You will get "
              "our metrics definitions, the org-chart change that mattered more than "
              "any model upgrade, and an honest accounting of what we would do "
              "differently with " year " hindsight.")
         (str "You will leave with concrete artifacts: our rollout sequencing "
              "template, the guardrail checklist we require before any agent touches "
              "production, and the one-page narrative that finally aligned our "
              "executive team. No slideware promises — everything shown is running "
              "today at " org ".")]))

(defn- history-p1 [{:keys [h org year sponsor industry-lc months]}]
  (pick h 3
        [(str org " began its AI transformation in earnest in " year ", when a "
              "small platform team got executive cover to run a genuinely scoped "
              "experiment rather than another innovation-lab demo. The trigger was "
              "mundane: a backlog metric that had not moved in two years, and a new "
              sponsor " willing to be accountable for changing it.")
         (str "The honest starting date is " year ". There were earlier experiments "
              "— every " industry-lc " company had a chatbot pilot by then — but "
              year " is when " org " committed real budget, named an owner, and "
              "picked a production workflow rather than a sandbox as the proving "
              "ground.")
         (str "Our transformation started in " year " with an uncomfortable board "
              "question: why were our competitors shipping faster with smaller "
              "teams? The first answer was a " months "-week discovery effort that "
              "produced a finding nobody liked — our bottleneck was not talent, it "
              "was the review-and-release process AI would soon make impossible to "
              "ignore.")]))

(defn- history-p2 [{:keys [h pct2 months]}]
  (pick h 7
        [(str "The middle chapter was harder than any conference talk had prepared "
              "us for. The first rollout stalled at roughly " pct2 "% adoption when "
              "early enthusiasm collided with unreviewed-output anxiety; the second "
              "died in a procurement-and-security loop that took " months " weeks. "
              "What eventually worked was smaller than either: one team, one "
              "workflow, one metric, published weekly to the whole company — "
              "failures included.")
         (str "What followed was eighteen months of unglamorous work: data access "
              "fights, a governance framework rewritten three times, and a "
              "champions program that initially rewarded exactly the wrong people. "
              "The turning point came when we moved AI adoption from a special "
              "initiative into the ordinary engineering ladder — the moment it "
              "stopped being somebody's side project, the curve bent.")
         (str "Progress was nothing like the smooth curve in our original deck. We "
              "hit a long plateau where usage was high but impact was flat — "
              "engineers were accepting suggestions, not changing how work flowed. "
              "Breaking that required rebuilding the delivery pipeline itself: "
              "review gates designed for human-only throughput simply could not "
              "absorb what the tools produced.")]))

(defn- history-p3 [{:keys [h org pct engineers dollars next]}]
  (pick h 11
        [(str "Today, roughly " pct "% of " org "'s engineers use AI agents in "
              "their daily workflow, " engineers "+ people have been through the "
              "internal enablement program, and the practices that survived are "
              "boring in the best way — versioned prompts, evaluated outputs, and a "
              "standing weekly forum where teams show real work. The next frontier "
              "for us is " next ", which is where this talk ends and the hallway "
              "conversation begins.")
         (str "As of this year the transformation has stopped being a program and "
              "become the way " org " operates: " pct "% weekly active usage across "
              "engineering, agent-assisted changes in the majority of production "
              "deploys, and — more telling — new hires who assume this is normal. "
              "We are now working on " next ", with the same discipline of small "
              "scopes and published numbers.")
         (str "The current state is durable rather than dramatic: adoption above "
              pct "% and still climbing slowly, " dollars " in audited annual "
              "savings, and a leadership team that budgets for AI capability the "
              "way it budgets for cloud. The three-paragraph version ends here; the "
              "fuller story — including what still does not work — is the talk.")]))

(defn- outcomes-p1 [original {:keys [pct3 pct4 dollars]}]
  (str original
       (when-not (str/blank? original) " ")
       "Behind the headline: cycle time for the target workflow fell " pct3
       "% year over year, AI-assisted changes now account for " pct4
       "% of merged work in the adopting teams, and audited run-rate savings "
       "reached " dollars " — a number our finance team, not our vendor, signed "
       "off on."))

(defn- outcomes-p2 [{:keys [h]}]
  (pick h 13
        [(str "The softer outcomes took longer but matter more: onboarding time "
              "for new engineers dropped from months to weeks, internal mobility "
              "into AI-heavy roles doubled, and attrition in the adopting teams "
              "fell measurably. We will also be honest about the metrics that went "
              "the wrong way — review load and incident-response complexity both "
              "rose before they fell — and how we instrumented our way out.")
         (str "Just as important is what we can now measure that we could not "
              "before: per-workflow baselines, suggestion-acceptance quality (not "
              "just volume), and the cost of un-adopted seats. Not everything "
              "improved — two metrics regressed and one is still contested — and "
              "the talk treats those with the same rigor as the wins, because that "
              "is what made the numbers credible internally.")]))

(defn enriched-answers
  "The three grown answers for one submission. Pure."
  [s]
  (let [v (vars-for s)
        a (:answers s)]
    {:answer-abstract
     (str/join "\n\n" [(str (:abstract a)) (abstract-p2 v) (abstract-p3 v)])
     :answer-ai-transformation-history
     (str/join "\n\n" [(history-p1 v) (history-p2 v) (history-p3 v)])
     :answer-measurable-outcomes
     (str/join "\n\n" [(outcomes-p1 (str (:measurable-outcomes a)) v)
                       (outcomes-p2 v)])}))

(defn targets
  "Demo submissions for the event — Gene's own excluded, editable only."
  []
  (let [ev (events/event-by-slug event-slug)
        mine? (fn [s] (some #(re-find #"(?i)genek|itrev" (str (:email %)))
                            (:speakers s)))]
    (->> (vals (:submissions @store/state))
         (filter #(= (:id ev) (:event-id %)))
         (remove mine?)
         (filter portal/editable?)
         (sort-by :id))))

(defn preview-one
  "Simulate: what would the nth target's answers become? No writes."
  [n]
  (let [s (nth (targets) n)]
    {:title (get-in s [:answers :talk-title])
     :org (:org (first (:speakers s)))
     :before-chars (into {} (map (fn [k] [k (count (str (get-in s [:answers k])))])
                                 [:abstract :ai-transformation-history :measurable-outcomes]))
     :after (enriched-answers s)}))

(defn already-enriched?
  "The one-line seed answers never contain a paragraph break; the enriched
   ones always do."
  [s]
  (str/includes? (str (get-in s [:answers :ai-transformation-history])) "\n\n"))

(defn run-one!
  "Enrich ONE submission through the domain verb. Returns the verb's result
   plus count evidence. Skips a submission that is already enriched —
   re-enriching compounds (see ns docstring)."
  [s]
  (if (already-enriched? s)
    {:id (:id s) :title (get-in s [:answers :talk-title])
     :ok true :unchanged true :errors nil}
    (let [res (portal/update-answers! (:id s) (enriched-answers s) actor)]
      {:id (:id s)
       :title (get-in s [:answers :talk-title])
       :ok (:ok res)
       :unchanged (boolean (:unchanged? res))
       :errors (:errors res)})))

(defn run-all!
  "The batch. Count-first summary; every write is one appended fact."
  []
  (let [results (mapv run-one! (targets))]
    {:targets (count results)
     :updated (count (filter #(and (:ok %) (not (:unchanged %))) results))
     :unchanged (count (filter :unchanged results))
     :failed (filterv #(not (:ok %)) results)}))
