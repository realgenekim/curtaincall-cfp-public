(ns cli.judge-sandbox
  "Build the immutable Judge Sandbox world through production domain verbs."
  (:require
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.forms :as forms]
   [cfp-scheduler-killer.inform :as inform]
   [cfp-scheduler-killer.mail :as mail]
   [cfp-scheduler-killer.replay :as replay]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.schedule :as schedule]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [clojure.edn :as edn]
   [clojure.java.io :as io])
  (:import
   (java.time LocalDate ZoneId)))

(def golden-path "resources/judge-sandbox/events.jsonl")
(def source-resource "judge-sandbox/enterprise-ai-summit.edn")
(def source-event
  (-> source-resource io/resource slurp edn/read-string))
(def event-slug (get-in source-event [:event :slug]))
(def submission-count 500)

(def ^:private tracks
  ["Leadership & Organizational Change"
   "Developer Practices"
   "Individual Productivity"
   "AI Models"
   "Infrastructure & Operations"
   "Architecture"])

(def ^:private review-comments
  ["Strong evidence and a clear story. I want to hear the hard part in the room."
   "Promising, but the outcome needs to be separated from the tooling claim."
   "Excellent fit for the audience. The operational details make this useful."
   "I would ask for one concrete failure and what changed afterward."
   "Good practitioner signal. Pairing the technical and organizational story will make it land."])

(def ^:private sandbox-reviewers
  (:committee source-event))

(defn- submission-entries []
  (->> (:entries (replay/load-corpus))
       (filter #(= "submission" (:kind %)))
       vec))

(defn- submission-variant [entry index]
  (let [{:keys [answers speaker]} (:submission entry)
        title (:talk-title answers)
        first? (zero? index)]
    {:answers (assoc answers
                     :talk-title (if first?
                                   "Agents that remember"
                                   (format "%s · Submission %03d" title (inc index)))
                     :track (nth tracks (mod index (count tracks))))
     :speaker (if first?
                {:name "Amara Devlin"
                 :email "amara.devlin+472@beaconloop.example.com"
                 :title "VP Engineering"
                 :org "Beacon Loop"
                 :bio "Amara builds durable AI systems."
                 :profile {}}
                (assoc speaker
                       :name (format "Sandbox Speaker %03d" (inc index))
                       :email (format "speaker-%03d@judge-sandbox.example.com"
                                      (inc index))
                       :profile {}))}))

(defn- create-event! []
  (let [{:keys [name slug tz starts-on ends-on support-email location website-url]}
        (:event source-event)]
    (events/create-eais-event!
      {:name name
       :slug slug
       :tz tz
       :starts-on (LocalDate/parse starts-on)
       :ends-on (LocalDate/parse ends-on)
       :cfp-opens-at (.atTime (LocalDate/of 2026 7 22) 9 0)
       :cfp-closes-at (.atTime (LocalDate/of 2026 9 18) 23 59)
       :support-email support-email
       :location location
       :website-url website-url}
      "judge-sandbox")))

(defn- event-instant [zone year month day hour]
  (-> (LocalDate/of year month day)
      (.atTime hour 0)
      (.atZone zone)
      .toInstant))

(defn- hockey-stick-instant [start seconds-span index total]
  (let [progress (/ (double index) (max 1 (dec total)))
        time-progress (Math/pow progress 0.45)]
    (.plusSeconds start (long (* seconds-span time-progress)))))

(defn- seed-review-history! [submissions reviewers zone]
  (let [total (count submissions)
        decision-limit (min 184 total)
        review-start (event-instant zone 2026 8 1 9)
        review-span (* 61 24 60 60)
        decision-start (event-instant zone 2026 8 15 9)
        decision-span (* 46 24 60 60)
        immediate #{0 11 37 83}]
    ;; Reviews lag submissions, then accelerate toward the deadline. Every
    ;; submission finishes with two ratings (one 3 and one 4), so the final
    ;; average is exactly 3.5.
    (doseq [[index submission] (map-indexed vector submissions)
            :let [scheduled (hockey-stick-instant
                              review-start review-span index total)
                  earliest (.plusSeconds (:created-at submission) (* 24 60 60))
                  at (if (.isBefore scheduled earliest) earliest scheduled)
                  comment-count (if (< (mod index 10) 7) 2 1)]]
      (doseq [offset (range 2)
              :let [reviewer (nth reviewers
                                  (mod (+ index offset) (count reviewers)))]]
        (binding [store/*clock* (.plusSeconds at (* offset 24 60 60))]
          (reviews/set-rating! (:id submission) (:person-id reviewer)
                               (nth [3 4] offset)
                               (:email reviewer))))
      (doseq [offset (range comment-count)
              :let [reviewer (nth reviewers
                                  (mod (+ index offset) (count reviewers)))]]
        (binding [store/*clock* (.plusSeconds at (+ (* offset 24 60 60)
                                                    (* 3 60 60)))]
          (reviews/add-comment! (:id submission) (:person-id reviewer)
                                (nth review-comments
                                     (mod (+ index offset)
                                          (count review-comments)))
                                (:email reviewer))))
      (when (contains? immediate index)
        (binding [store/*clock* (.plusSeconds at (* 30 60 60))]
          (reviews/set-status! (:id submission) "Accepted"
                               "swyx@ai.engineer"))))

    ;; Decisions remain partial so the finished specimen still has a useful
    ;; pending queue, while the review coverage itself is complete.
    (doseq [index (range 4 decision-limit)
            :when (not (contains? immediate index))
            :let [submission (nth submissions index)
                  at (hockey-stick-instant
                       decision-start decision-span index decision-limit)
                  status (cond
                           (zero? (mod index 7)) "Accepted"
                           (zero? (mod index 5)) "Waitlisted"
                           :else "Declined")]]
      (binding [store/*clock* at]
        (reviews/set-status! (:id submission) status "swyx@ai.engineer")))))

(defn- publish-program!
  "Turn the sandbox's accepted decisions into a real, judgeable public program.
   Informing is the production publication boundary; placement then gives both
   event days and all rooms observable content without bypassing domain rules."
  [event submissions zone]
  (let [accepted (filterv #(= "Accepted" (:status %)) submissions)
        rooms (mapv #(schedule/add-room! event % "judge-sandbox")
                    ["Main Stage" "Discovery Theater" "Transformation Lab"])
        publish-start (event-instant zone 2026 10 2 9)]
    (doseq [[index submission] (map-indexed vector accepted)
            :let [day (nth (schedule/event-days event) (mod index 2))
                  room (nth rooms (mod (quot index 2) (count rooms)))
                  start (+ (* 9 60) (* 45 (quot index 6)))]]
      (binding [store/*clock* (.plusSeconds publish-start index)]
        (inform/inform! event submission "judge-sandbox")
        (schedule/place! event (:id submission)
                         {:day day
                          :start (schedule/minutes->hhmm start)
                          :room-id (:id room)}
                         "judge-sandbox")))
    accepted))

(defn- seed-count!
  [target-count]
  (when-not (pos-int? target-count)
    (throw (ex-info "Judge Sandbox requires at least one submission."
                    {:submission-count target-count})))
  (let [zone (ZoneId/of "America/New_York")
        birth (event-instant zone 2026 7 20 8)
        submission-start (event-instant zone 2026 7 22 10)
        submission-span (* 58 24 60 60)
        corpus (submission-entries)
        event (binding [store/*clock* birth] (create-event!))
        committee-id (:id (first (events/committees-for-event (:id event))))]
    (when (empty? corpus)
      (throw (ex-info "Judge Sandbox corpus has no submissions."
                      {:resource replay/corpus-resource})))
    (binding [store/*clock* birth]
      (forms/mark-reviewed! event "judge-sandbox")
      (events/set-email-notifications! event false "judge-sandbox")
      (events/update-event-details!
        (:id event) {:cfp-intro (get-in source-event [:event :cfp-intro])}
        "judge-sandbox")
      (events/set-hero-image!
        event (get-in source-event [:event :hero-image-url]) "judge-sandbox")
      (doseq [reviewer sandbox-reviewers]
        (committees/add-member! committee-id reviewer "judge-sandbox"))
      (doseq [speaker (:announced-speakers source-event)]
        (events/announce-speaker! event speaker "judge-sandbox")))
    ;; A golden fixture records the product's domain history, but never its
    ;; external side effects. Every synthetic address is reserved example.com.
    (with-redefs [mail/send! (fn [& _] nil)]
      (doseq [index (range target-count)
              :let [{:keys [answers speaker]}
                    (submission-variant (nth corpus (mod index (count corpus)))
                                        index)]]
        (binding [store/*clock* (hockey-stick-instant submission-start submission-span index target-count)]
          (submissions/create-submission! event answers speaker
                                          "judge-sandbox" "judge-sandbox"))))
    (let [submissions (->> (submissions/for-event (:id event))
                           (sort-by :created-at)
                           vec)
          reviewers (committees/members-for-committee committee-id)]
      (seed-review-history! submissions reviewers zone)
      ;; Fixture generation records the publication facts but never queues the
      ;; synthetic decision emails as outbound work.
      (with-redefs [mail/send! (fn [& _] nil)]
        (publish-program! event
                          (mapv #(store/submission-by-id (:id %)) submissions)
                          zone)))
    (let [actual (submissions/count-for-event (:id event))]
      (when-not (= target-count actual)
        (throw (ex-info "Judge Sandbox fixture generation was incomplete."
                        {:expected target-count :actual actual}))))
    (println "Generated Judge Sandbox:" (:name event) "·"
             target-count "submissions ·" (count sandbox-reviewers)
             "committee members")
    event))

(defn seed!
  ([] (seed-count! submission-count))
  ([target-count] (seed-count! target-count)))

(defn generate [_opts]
  (io/make-parents golden-path)
  (io/delete-file golden-path true)
  (reset! store/store-path golden-path)
  (store/load!)
  (let [event (seed!)]
    (shutdown-agents)
    {:fixture golden-path
     :event (:slug event)
     :submissions submission-count
     :committee-members (count sandbox-reviewers)}))
