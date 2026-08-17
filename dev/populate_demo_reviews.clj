(ns populate-demo-reviews
  "Operator script, 2026-08-10 (Gene): populate ratings + comments from the
   three real committee members (Gene, Ann Perry, Alex Brodrick-Forster) so
   the time-travel scrubber shows a review cycle actually happening —
   ratings piling up, splits emerging, the coverage bar filling.

   Every write is a domain verb (reviews/set-rating!, reviews/add-comment!)
   appending a fact; actor is the reviewer's email, exactly as the board
   would record it.

   The distribution is the review doctrine, staged:
     ~10 submissions with NO ratings   (the coverage worklist keeps work)
     ~15 with one                       (2-review rule unmet, bar shows it)
     ~40 with two                       (the rule satisfied)
     ~15 with all three                 (the hot ones)
   Reviewer personalities: Gene generous (3.0-5.0), Ann calibrated
   (2.5-4.5), Alex spiky (1.5-5.0) — spiky vs generous manufactures real
   SPLIT rows (spread >= 2.0), which get paired argument comments.
   Chronology: each reviewer's queue is cut into bursts of 5-9 and
   round-robined, so the scrub shows three people working in overlapping
   sessions, not three monolithic passes.

   Deterministic by hash(submission-id, reviewer). Idempotent: a
   (submission, reviewer) pair that already has a rating is skipped.

   Loading defines functions only; mutations happen via explicit
   run-all! calls."
  (:require [clojure.string :as str]
            [cfp-scheduler-killer.store :as store]
            [cfp-scheduler-killer.events :as events]
            [cfp-scheduler-killer.committees :as committees]
            [cfp-scheduler-killer.reviews :as reviews]))

(def event-slug "enterprise-ai-summit-charlotte-2026")

(def reviewers
  [{:key :gene :name "Gene Kim" :email "genek@itrevolution.net"
    :person-id "2e93069a-29d2-4ce8-a4c3-5d4a2e62e79e"
    :lo 3.0 :hi 5.0}
   {:key :ann :name "Ann Perry" :email "annp@itrevolution.net"
    :person-id "33a97d44-5f26-4031-8cc6-c5ce2d0f9a75"
    :lo 2.5 :hi 4.5}
   {:key :alex :name "Alex Brodrick-Forster" :email "alex@itrevolution.net"
    :person-id "941d75c4-6490-4e5f-acf7-a75275bf3a9f"
    :lo 1.5 :hi 5.0}])

(defn- h* [& xs] (Math/abs (long (hash (str/join "|" (map str xs))))))
(defn- pick [h coll] (nth coll (mod h (count coll))))

(defn- half-stars
  "A rating in [lo, hi] on the half-star grid, deterministic."
  [h lo hi]
  (let [steps (int (inc (* 2 (- hi lo))))]
    (+ lo (* 0.5 (mod h steps)))))

;; --- Comment pools, by score band -------------------------------------------

(def rave-comments
  ["This is exactly the talk I want opening a track. Real numbers, real scars."
   "Best abstract in my pile so far — the failed-rollout honesty is rare."
   "Strong yes from me. The finance-audited savings figure alone will fill the room."
   "I would attend this twice. The operating-model framing is the whole conference in one talk."
   "Clear, specific, earned. This is what an experience report should look like."])

(def solid-comments
  ["Solid. The middle section could tighten, but the material is clearly real."
   "Good bones — I want more on what they'd do differently, less on the tooling."
   "Yes with a note: ask them to lead with the plateau story, it's the strongest part."
   "The org detail sells it for me. Slightly worried it runs long for the slot."
   "Dependable content. Not flashy, exactly why I trust it."])

(def mixed-comments
  ["On the fence. The claims are big and the evidence section is thinner than the abstract promises."
   "Could go either way — feels like three talks compressed into one. Would benefit from a narrower cut."
   "The numbers are good but I've heard this arc twice already this year. What's the distinct beat?"
   "Middle of my pile. Would flip to yes if the speaker can commit to the failure detail."])

(def concern-comments
  ["Not convinced. Reads like a vendor deck with the logos filed off."
   "The metrics don't hang together for me — adoption and impact are doing separate dances."
   "Weak fit for this audience; this wants a practitioner room, not a leadership one."
   "I can't find the practitioner in this abstract. Who actually did the work?"])

(def split-pro
  ["I'll fight for this one — the spread here is exactly why it belongs on the discussion call."
   "Strong disagree with the low scores. The messy middle is the value, not a flaw."
   "Flagging for the call: if half of us love it this much, the audience will too."])

(def split-con
  ["Am I the only one seeing a rerun? Would love someone to talk me up."
   "Holding my low score until someone shows me what's new here. Happy to be argued out of it."
   "The high scores puzzle me — the outcomes paragraph is doing a lot of unaudited lifting."])

(defn- comment-for [stars split? pro?]
  (cond
    (and split? pro?)      split-pro
    (and split? (not pro?)) split-con
    (>= stars 4.5) rave-comments
    (>= stars 3.5) solid-comments
    (>= stars 2.5) mixed-comments
    :else concern-comments))

;; --- The plan ---------------------------------------------------------------

(defn- demo-submissions []
  (let [ev (events/event-by-slug event-slug)]
    (->> (vals (:submissions @store/state))
         (filter #(= (:id ev) (:event-id %)))
         (sort-by :id))))

(defn- gene-own? [s]
  (some #(re-find #"(?i)genek|itrev" (str (:email %))) (:speakers s)))

(defn- coverage-tier
  "0, 1, 2, or 3 raters for this submission — weighted 10/15/40/15 over 80."
  [s]
  (let [r (mod (h* (:id s) "tier") 80)]
    (cond (< r 10) 0
          (< r 25) 1
          (< r 65) 2
          :else 3)))

(defn- raters-for
  "WHICH reviewers rate this submission. Gene's own talk gets Ann + Alex,
   never Gene. Otherwise a deterministic pick of tier-many reviewers."
  [s]
  (if (gene-own? s)
    [(nth reviewers 1) (nth reviewers 2)]
    (let [n (coverage-tier s)
          order (pick (h* (:id s) "order")
                      [[0 1 2] [1 2 0] [2 0 1] [0 2 1] [1 0 2] [2 1 0]])]
      (mapv #(nth reviewers %) (take n order)))))

(defn plan
  "Every rating act: {:sub :reviewer :stars :comment}. Pure."
  []
  (for [s (demo-submissions)
        r (raters-for s)]
    (let [h (h* (:id s) (:key r))
          stars (if (gene-own? s)
                  (if (= :ann (:key r)) 4.5 4.0)
                  (half-stars h (:lo r) (:hi r)))]
      {:sub s :reviewer r :stars stars :h h})))

(defn- with-splits
  "Mark acts on submissions whose planned spread is >= 2.0."
  [acts]
  (let [by-sub (group-by #(get-in % [:sub :id]) acts)
        split-ids (set (for [[id as] by-sub
                             :when (and (> (count as) 1)
                                        (>= (- (apply max (map :stars as))
                                               (apply min (map :stars as)))
                                            2.0))]
                         id))]
    (map (fn [a]
           (let [split? (contains? split-ids (get-in a [:sub :id]))
                 mx (when split?
                      (apply max (map :stars (by-sub (get-in a [:sub :id])))))]
             (assoc a :split? split? :pro? (and split? (= (:stars a) mx)))))
         acts)))

;; interleave-all: like interleave but exhausts every collection
(defn- interleave-all [& colls]
  (lazy-seq
   (let [cs (filter seq colls)]
     (when (seq cs)
       (concat (map first cs)
               (apply interleave-all (map rest cs)))))))

(defn- interleaved
  "Round-robin bursts of 5-9 per reviewer — three people in overlapping
   sessions, not three monolithic passes."
  [acts]
  (let [queues (group-by #(get-in % [:reviewer :key]) acts)
        burst (fn [k q] (partition-all (+ 5 (mod (h* k "burst") 5)) q))
        chunked (map (fn [[k q]] (burst k q)) queues)]
    (apply concat (apply interleave-all chunked))))

(defn- commented?
  "~45% of ratings carry a comment; every split act does."
  [{:keys [h split?]}]
  (or split? (< (mod (quot h 5) 100) 45)))

;; --- Execution --------------------------------------------------------------

(defn- already-rated? [{:keys [sub reviewer]}]
  ;; ratings live on the ENRICHED projection, not the raw submission map
  (some #(= (:person-id reviewer) (:person-id %))
        (:ratings (reviews/enrich (store/submission-by-id (:id sub))))))

(defn run-one! [{:keys [sub reviewer stars split? pro?] :as act}]
  (if (already-rated? act)
    {:skipped true}
    (do
      (reviews/set-rating! (:id sub) (:person-id reviewer) stars (:email reviewer))
      (when (commented? act)
        (reviews/add-comment! (:id sub) (:person-id reviewer)
                              (pick (:h act) (comment-for stars split? pro?))
                              (:email reviewer)))
      {:rated stars :commented (commented? act)})))

(defn run-all! []
  (let [acts (interleaved (with-splits (plan)))
        results (mapv run-one! acts)]
    {:acts (count results)
     :rated (count (filter :rated results))
     :commented (count (filter :commented results))
     :skipped (count (filter :skipped results))
     :split-submissions (count (distinct (map #(get-in % [:sub :id])
                                              (filter :split? acts))))}))
