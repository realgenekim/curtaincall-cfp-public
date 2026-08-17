(ns cfp-scheduler-killer.speaker-history-test
  (:require
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.inform :as inform]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.schedule :as schedule]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.speaker-history :as speaker-history]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock])
  (:import
   (java.time LocalDate LocalDateTime)))

(use-fixtures :each with-temp-store)

(def day "2026-10-14")

;; ---------------------------------------------------------------------------
;; Pure lookup: history-for
;; ---------------------------------------------------------------------------

(deftest history-for-normalizes-name-test
  (testing "matches a known repeat speaker exactly"
    (let [h (speaker-history/history-for "Steve Yegge")]
      (is (some? h))
      (is (= "Sourcegraph" (:org h)))
      (is (pos? (:talk-count h)))
      (is (= "https://videos.itrevolution.com/speakers/steve-yegge"
             (:history-url h)))))

  (testing "match is case- and whitespace-insensitive"
    (is (= (speaker-history/history-for "Steve Yegge")
           (speaker-history/history-for "  steve   YEGGE ")))
    (is (some? (speaker-history/history-for "dustin warner"))))

  (testing "uses the authoritative alumni year and speaker archive"
    (let [jd (speaker-history/history-for "Jondavid “JD” Black")
          elisabeth (speaker-history/history-for "Elisabeth Hendrickson")]
      (is (= 2023 (:since jd)))
      (is (= "https://videos.itrevolution.com/speakers/jondavid-jd-black"
             (:history-url jd)))
      (is (= 2014 (:since elisabeth)))
      (is (= [2014 2015 2017 2020] (:years elisabeth)))
      (is (= 4 (:talk-count elisabeth)))))

  (testing "unknown speaker returns nil"
    (is (nil? (speaker-history/history-for "Nobody Atall")))
    (is (nil? (speaker-history/history-for "")))
    (is (nil? (speaker-history/history-for nil)))))

;; ---------------------------------------------------------------------------
;; Rendered page: the enrichment appears for matched speakers only
;; ---------------------------------------------------------------------------

(defn- make-submission! [event fields {:keys [title speaker email company]}]
  (let [params {:answer-talk-title title
                :answer-abstract (str title " is a detailed public description of the work and the result.")
                :answer-session-format "Experience Report"
                :answer-track "Developer Practices"
                :answer-org-size ">10,000"
                :answer-industry "Software"
                :answer-ai-transformation-history "Since 2023"
                :answer-measurable-outcomes "Measured outcomes"
                :speaker-name speaker :speaker-email email
                :speaker-title "VP of AI" :speaker-org company
                :speaker-bio (str speaker " has led enterprise AI programs for a decade.")}]
    (submissions/create-submission!
     event
     (submissions/parse-answers fields params)
     (submissions/parse-speaker params)
     "form"
     "kaocha")))

(defn- setup! []
  (let [event (events/create-event!
               {:name "History Widget Summit" :slug "history-widgets"
                :tz "America/New_York" :location "Charlotte, NC"
                :starts-on (LocalDate/of 2026 10 14)
                :ends-on (LocalDate/of 2026 10 15)
                :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
                :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
               "kaocha")
        committee-id (:id (first (events/committees-for-event (:id event))))
        _ (committees/add-member! committee-id
                                  {:name "Gene Kim" :email "gene@example.com" :role "chair"}
                                  "kaocha")
        fields (:fields (events/form-for-event (:id event)))
        ;; A speaker WITH history (matched by name to the curated EDN).
        yegge (make-submission! event fields
                                {:title "Fleet Mode" :speaker "Steve Yegge"
                                 :email "steve@example.com" :company "Sourcegraph"})
        ;; A speaker WITHOUT history.
        newcomer (make-submission! event fields
                                   {:title "First Timer Talk" :speaker "Nadia Newcomer"
                                    :email "nadia@example.com" :company "Fresh Co"})]
    (doseq [submission [yegge newcomer]]
      (reviews/set-status! (:id submission) "Accepted" "gene@example.com")
      (inform/inform! event (store/submission-by-id (:id submission)) "gene@example.com"))
    (let [room (schedule/add-room! event "Main Stage" "gene@example.com")]
      (schedule/place! event (:id yegge)
                       {:day day :start "09:00" :room-id (:id room)} "gene@example.com")
      (schedule/place! event (:id newcomer)
                       {:day day :start "10:30" :room-id (:id room)} "gene@example.com"))
    {:event (events/event-by-slug "history-widgets")}))

(defn- get-body [handler path]
  (:body (handler (mock/request :get path))))

(deftest speakers-page-shows-history-for-matched-speaker-only-test
  ;; The standalone speakers page retired 2026-08-11 — the roster now renders
  ;; on the stacked program page (/program/:slug).
  (setup!)
  (let [handler (server/create-app)
        speakers (get-body handler "/program/history-widgets")]
    (testing "the matched speaker gets the enrichment + history link"
      (is (str/includes? speakers "Steve Yegge"))
      (is (str/includes? speakers "class=\"public-itrev-history\""))
      (is (str/includes? speakers "target=\"_blank\""))
      (is (str/includes? speakers "rel=\"noopener\""))
      (is (str/includes? speakers "alum - see archive)"))
      (is (str/includes?
            speakers
            "https://videos.itrevolution.com/speakers/steve-yegge")))

    (testing "a speaker without history does NOT get the element"
      (is (str/includes? speakers "Nadia Newcomer"))
      ;; Exactly one badge on the page — the newcomer contributes none.
      (is (= 1 (count (re-seq #"class=\"public-itrev-history\"" speakers)))))))
