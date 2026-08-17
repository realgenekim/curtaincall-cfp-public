(ns restage-aie-timeline
  "Operator script, 2026-08-10 (Gene): the AIE demo data all landed inside
   ~90 minutes, so every sparkline collapses to one vertical spike —
   'typically the submissions come first and then the reviews come later,
   maybe the data needs to match that.' This restages the WHOLE population
   on a believable summer:

     Jun 25          event created, committee seated over that week
     Jul 1 -> today  500 submissions, early-ramp curve, business hours
     +2..20 days     each rating trails ITS submission (per-reviewer lag),
                     comments ride along; nothing later than 'now'

   Mechanism: store/*clock* (the operator clock seam) bound per fact, and
   every fact appended in true chronological order — so the time-travel
   scrubber replays an actual summer. All through the same domain verbs as
   before; the old compressed-time event is ARCHIVED (a fact), not deleted.

   New slug: ai-engineer-code-summit (the -2026 slug stays on the archived
   original — slugs are forever).

   Loading defines fns only. Run: (step1-event!) then (step2-acts!) —
   batched via run-acts! so no eval outruns a timeout."
  (:require [clojure.string :as str]
            [cfp-scheduler-killer.store :as store]
            [cfp-scheduler-killer.events :as events]
            [cfp-scheduler-killer.committees :as committees]
            [cfp-scheduler-killer.forms :as forms]
            [cfp-scheduler-killer.submissions :as sub]
            [cfp-scheduler-killer.reviews :as reviews]
            [generate-aie-submissions :as gen]
            [populate-aie-reviews :as rev]))

(def old-slug "ai-engineer-code-summit-2026")
(def new-slug "ai-engineer-code-summit")
(def actor "operator:aie-restage-2026-08-10")

(defn- h* [& xs] (Math/abs (long (hash (str/join "|" (map str xs))))))

(defn- at
  "Instant at day-offset from Jul 1 2026 (UTC-ish New York morning), plus
   hour/minute scatter biased into working hours."
  [day-frac h]
  (let [base (.toEpochMilli (java.time.Instant/parse "2026-07-01T04:00:00Z"))
        ms-per-day 86400000
        hour (+ 12 (mod h 11))              ; 08:00-19:00 ET as UTC 12-23
        minute (mod (quot h 13) 60)]
    (java.time.Instant/ofEpochMilli
     (+ base (long (* day-frac ms-per-day))
        (* hour 3600000) (* minute 60000)))))

(def days-elapsed
  "Jul 1 -> Aug 10."
  40.0)

(defn sub-instant
  "Submission k's staged arrival: early-ramp power curve over the elapsed
   window + up to half a day of jitter."
  [k]
  (let [h (h* "when" k)
        frac (Math/pow (/ (double k) 500.0) 0.85)
        jitter (/ (double (mod h 12)) 24.0)]
    (at (min (- days-elapsed 0.2) (+ (* frac days-elapsed) jitter)) h)))

(defn- reviewer-lag-days [reviewer h]
  (case (:key reviewer)
    :devon (+ 1 (mod h 4))                 ; fast
    :maya (+ 4 (mod h 14))                 ; thorough, slower
    :alex (+ 2 (mod h 10))
    (+ 3 (mod h 12))))                     ; ann

(defn rating-instant
  "A rating trails ITS submission by the reviewer's lag — but never lands
   before the JUDGING PHASE opens (Gene, 2026-08-10: 'two phases: CFP is
   open, and then the judging begins'). The committee kicks off ~Jul 17;
   early-July submissions get reviewed in the kickoff wave."
  [sub-inst reviewer h]
  (let [lag-ms (* (reviewer-lag-days reviewer h) 86400000)
        judging-opens (.toEpochMilli (java.time.Instant/parse "2026-07-17T13:00:00Z"))
        kickoff-scatter (+ judging-opens (* (mod h 96) 3600000)) ; first 4 days
        cap (.toEpochMilli (java.time.Instant/parse "2026-08-10T06:00:00Z"))
        natural (+ (.toEpochMilli ^java.time.Instant sub-inst) lag-ms
                   (* (mod (quot h 7) 10) 3600000))
        t (min (- cap (* (mod h 20) 3600000))
               (max natural kickoff-scatter))]
    (java.time.Instant/ofEpochMilli
     (max (+ (.toEpochMilli ^java.time.Instant sub-inst) 3600000) t))))

;; --- Step 1: archive old, create new, seat the committee ---------------------

(defn step1-event! []
  (let [old (events/event-by-slug old-slug)]
    (when (and old (not (:archived-at old)))
      ;; archive-event! takes the event MAP, not the id (Guardrails warns
      ;; but does not throw — the original run appended a nil-payload fact)
      (events/archive-event! old actor))
    (binding [store/*clock* "2026-06-25T14:00:00Z"]
      (events/create-event!
       {:name "AI Engineer Code Summit"
        :slug new-slug
        :tz "America/New_York"
        :starts-on (java.time.LocalDate/of 2026 11 19)
        :ends-on (java.time.LocalDate/of 2026 11 20)
        :location "New York, NY"
        :website-url "https://ai.engineer/code"
        :support-email "cfp@ai.engineer"
        :cfp-opens-at (java.time.LocalDateTime/of 2026 7 1 0 0)
        :cfp-closes-at (java.time.LocalDateTime/of 2026 9 15 23 59)}
       actor))
    (let [ev (events/event-by-slug new-slug)
          cid (:id (first (events/committees-for-event (:id ev))))]
      (doseq [[i m] (map-indexed vector
                                 [{:name "swyx" :email "swyx@ai.engineer" :role "chair"}
                                  {:name "Ann Perry" :email "annp@itrevolution.net" :role "reviewer"}
                                  {:name "Alex Brodrick-Forster" :email "alex@itrevolution.net" :role "reviewer"}
                                  {:name "Maya Lindholm" :email "maya.lindholm@example.com" :role "reviewer"}
                                  {:name "Devon Reyes" :email "devon.reyes@example.com" :role "reviewer"}
                                  {:name "Gene Kim" :email "genek@itrevolution.net" :role "reviewer"}])]
        (binding [store/*clock* (str "2026-06-2" (+ 5 (mod i 4)) "T1" i ":30:00Z")]
          (committees/add-member! cid m actor)))
      (binding [store/*clock* "2026-06-26T15:00:00Z"]
        (let [track (forms/find-field (forms/fields-for-event (:id ev)) "track")]
          (forms/update-field!
           ev "track"
           {:label "Track" :help (:help track) :required true :private false
            :options ["Agents in Production" "Evals & Observability" "RAG & Retrieval"
                      "Infra & GPUs" "Multimodal" "AI Leadership"]}
           actor))
        (events/update-event-details!
         (:id ev)
         {:cfp-intro "The conference where AI engineers who ship talk to AI engineers who ship. Two days in New York, every talk from a practitioner with production scars: agents that survived contact with users, evals that caught what vibes missed, and the infrastructure bills nobody warns you about. No keynote fluff — if you built it, ran it, and can show the numbers, this stage is yours."}
         actor))
      {:archived old-slug :created new-slug
       :members (count (committees/members-for-committee cid))})))

;; --- Step 2: every act on the staged clock, in chronological order -----------

(defn- sub-act [ev ff k]
  (let [t (sub-instant k)]
    {:t t
     :run! (fn []
             (binding [store/*clock* t]
               (let [params (#'generate-aie-submissions/gen-one k)]
                 (sub/create-submission!
                  ev (sub/parse-answers ff params)
                  (sub/parse-speaker params) "form" actor))))}))

(defn- rating-acts
  "Planned after all submissions exist: reviewers + stars come from the
   SAME deterministic plan machinery as before (rev/plan against the new
   event), but each act is stamped with its staged instant."
  []
  (with-redefs [rev/event-slug new-slug]
    (doall
     (for [{:keys [sub-id reviewer stars h]} (rev/plan)]
       (let [s (store/submission-by-id sub-id)
             sub-inst (java.time.Instant/parse (str (:created-at s)))
             t (rating-instant sub-inst reviewer h)]
         {:t t
          :run! (fn []
                  (binding [store/*clock* t]
                    (reviews/set-rating! sub-id (:person-id reviewer) stars
                                         (:email reviewer))
                    (when (< (mod (quot h 5) 100)
                             (:comment-pct reviewer))
                      (reviews/add-comment! sub-id (:person-id reviewer)
                                            (#'populate-aie-reviews/pick
                                             h (#'populate-aie-reviews/comment-for reviewer stars))
                                            (:email reviewer)))))})))))

(defonce acts (atom nil))

(defn stage-submissions!
  "Batched: create submissions [from, from+n), in k order (already
   chronological by construction)."
  [from n]
  (let [ev (events/event-by-slug new-slug)
        ff (forms/active-fields (forms/fields-for-event (:id ev)))
        have (count (filter #(= (:id ev) (:event-id %))
                            (vals (:submissions @store/state))))]
    (if (> have from)
      {:skipped true :have have}
      (do (doseq [k (range from (min 500 (+ from n)))]
            ((:run! (sub-act ev ff k))))
          {:done (min 500 (+ from n))}))))

(defn stage-ratings!
  "Plan once (into `acts`, sorted by instant), then run [from, from+n)."
  [from n]
  (when (nil? @acts)
    (reset! acts (vec (sort-by :t (rating-acts)))))
  (let [slice (subvec @acts (min from (count @acts))
                      (min (+ from n) (count @acts)))]
    (doseq [a slice] ((:run! a)))
    {:ran (count slice) :through (+ from (count slice)) :total (count @acts)}))
