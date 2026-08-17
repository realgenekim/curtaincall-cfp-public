(ns populate-aie-reviews
  "Operator script, 2026-08-10 (Gene): the AI Engineer Code Summit review
   cycle — ratings + comments from Ann, Alex, Maya, and Devon across the
   500 fabricated submissions. swyx rates NOTHING: his You column stays
   empty on purpose, so the judge's first act in the product is his own.

   Same machinery as the EAIS run (dev/populate_demo_reviews.clj), scaled:
     ~60 submissions with NO ratings   (the coverage chase has a real tail)
     ~90 with one
     ~250 with two                     (the 2-review rule met)
     ~100 with three or four           (the argued-over ones)
   Personalities: Ann calibrated (2.5-4.5), Alex spiky (1.5-5.0),
   Maya thorough (2.0-4.5, comments on ~70%), Devon fast and terse
   (3.0-4.5, comments on ~15%, never more than one sentence).

   Every write is reviews/set-rating! / add-comment!, actor = reviewer
   email. Idempotent via the already-rated? guard (determinism is NOT
   idempotency — the EAIS lesson). Run: (run-all!) — batched internally."
  (:require [clojure.string :as str]
            [cfp-scheduler-killer.store :as store]
            [cfp-scheduler-killer.events :as events]
            [cfp-scheduler-killer.reviews :as reviews]))

(def event-slug "ai-engineer-code-summit-2026")

(def reviewers
  [{:key :ann :name "Ann Perry" :email "annp@itrevolution.net"
    :person-id "33a97d44-5f26-4031-8cc6-c5ce2d0f9a75"
    :lo 2.5 :hi 4.5 :comment-pct 45}
   {:key :alex :name "Alex Brodrick-Forster" :email "alex@itrevolution.net"
    :person-id "941d75c4-6490-4e5f-acf7-a75275bf3a9f"
    :lo 1.5 :hi 5.0 :comment-pct 45}
   {:key :maya :name "Maya Lindholm" :email "maya.lindholm@example.com"
    :person-id "c5bb9f7a-3501-4f62-8622-7b7d160446ed"
    :lo 2.0 :hi 4.5 :comment-pct 70}
   {:key :devon :name "Devon Reyes" :email "devon.reyes@example.com"
    :person-id "d4a0336f-19ff-4a3d-94dc-ee82aaeab96d"
    :lo 3.0 :hi 4.5 :comment-pct 15}])

(defn- h* [& xs] (Math/abs (long (hash (str/join "|" (map str xs))))))
(defn- pick [h coll] (nth coll (mod h (count coll))))

(defn- half-stars [h lo hi]
  (let [steps (int (inc (* 2 (- hi lo))))]
    (+ lo (* 0.5 (mod h steps)))))

;; --- Comment pools -----------------------------------------------------------

(def rave
  ["Instant accept for me — production detail on every claim, and the failure catalog alone is worth the slot."
   "This is the talk the hallway track argues about afterward. Strong yes."
   "Finally an evals story with a denominator. Top of my pile."
   "The rollback story is the rarest kind of honesty on a CFP. Yes."
   "Real system, real pager, real numbers. Exactly what this conference is for."])

(def solid
  ["Solid and specific. Would ask them to cut the architecture tour and double the incident detail."
   "Good bones. The cost numbers make it — most submissions in this track wave their hands there."
   "Yes with a nudge: the second half should lead with the eval-in-CI workflow."
   "Dependable practitioner material. Not flashy, which is why I trust it."
   "The track needs this one — less novelty, more receipts."])

(def mixed
  ["On the fence: strong opening, but the outcomes paragraph is carrying claims the abstract never earns."
   "Could be great or could be a vendor pitch with the logos filed off — depends entirely on the speaker."
   "Third submission this cycle with this exact arc. What is the distinct beat?"
   "The numbers feel rounded to the nearest keynote. Want more denominator."])

(def concern
  ["Not for this audience — this wants a leadership room, not a practitioner one."
   "The claims outrun the evidence badly. Pass unless someone talks me up."
   "Reads generated-by-marketing. Where is the engineer in this?"
   "I cannot find a single number I could check. That worries me here."])

(def devon-terse
  ["Yes." "Solid." "Fine but crowded topic." "Would attend." "Pass."
   "Top-quartile for the track." "Seen it before, done better here though."])

(defn- comment-for [reviewer stars]
  (if (= :devon (:key reviewer))
    devon-terse
    (cond (>= stars 4.5) rave
          (>= stars 3.5) solid
          (>= stars 2.5) mixed
          :else concern)))

;; --- The plan ----------------------------------------------------------------

(defn- demo-submissions []
  (let [ev (events/event-by-slug event-slug)]
    (->> (vals (:submissions @store/state))
         (filter #(= (:id ev) (:event-id %)))
         (sort-by :id))))

(defn- coverage-tier
  "0-4 raters, weighted 60/90/250/70/30 out of 500."
  [s]
  (let [r (mod (h* (:id s) "tier") 500)]
    (cond (< r 60) 0
          (< r 150) 1
          (< r 400) 2
          (< r 470) 3
          :else 4)))

(defn- raters-for [s]
  (let [n (coverage-tier s)
        order (pick (h* (:id s) "order")
                    [[0 1 2 3] [1 2 3 0] [2 3 0 1] [3 0 1 2]
                     [0 2 1 3] [1 3 2 0] [2 0 3 1] [3 1 0 2]])]
    (mapv #(nth reviewers %) (take n order))))

(defn plan []
  (for [s (demo-submissions)
        r (raters-for s)]
    (let [h (h* (:id s) (:key r))]
      {:sub-id (:id s) :reviewer r
       :stars (half-stars h (:lo r) (:hi r)) :h h})))

(defn- already-rated? [{:keys [sub-id reviewer]}]
  (some #(= (:person-id reviewer) (:person-id %))
        (:ratings (reviews/enrich (store/submission-by-id sub-id)))))

(defn- run-one! [{:keys [sub-id reviewer stars h] :as act}]
  (if (already-rated? act)
    :skipped
    (do
      (reviews/set-rating! sub-id (:person-id reviewer) stars (:email reviewer))
      (when (< (mod (quot h 5) 100) (:comment-pct reviewer))
        (reviews/add-comment! sub-id (:person-id reviewer)
                              (pick h (comment-for reviewer stars))
                              (:email reviewer)))
      :done)))

(defn run-batch!
  "Run acts [start, start+n) from the deterministic plan. Batched so no
   single eval outruns a timeout; resume = call with the next start."
  [start n]
  (let [acts (vec (plan))
        slice (subvec acts (min start (count acts))
                      (min (+ start n) (count acts)))
        results (mapv run-one! slice)]
    {:from start :ran (count results)
     :done (count (filter #(= :done %) results))
     :skipped (count (filter #(= :skipped %) results))
     :total-acts (count acts)}))
