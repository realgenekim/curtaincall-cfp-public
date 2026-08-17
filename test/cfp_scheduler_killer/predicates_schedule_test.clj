(ns cfp-scheduler-killer.predicates-schedule-test
  (:require
   [cfp-scheduler-killer.agent.commands :as commands]
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.inform :as inform]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.rubric-predicates-schedule :as predicates]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.data.json :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock])
  (:import
   (java.time LocalDate LocalDateTime)))

(use-fixtures :each with-temp-store (fn [f] (reset! auth/tokens {}) (f)))

;; INTENT-TEST: PRED-003 — exact captured schedule evidence decides SPK-11.
(deftest spk-11-exact-schedule-predicate-test
  (let [event-json
        (json/write-str
         {"id" "event-1" "startsOn" "2026-10-14" "endsOn" "2026-10-16"})
        canonical-json
        (json/write-str
         {"rooms" [{"id" "room-main" "name" "Main Stage"}]
          "days" [{"date" "2026-10-14" "label" "Day 1 — Oct 14" "items" []}
                  {"date" "2026-10-15" "label" "Day 2 — Oct 15"
                   "items" [{"kind" "session" "sessionId" "talk-7"
                             "day" "2026-10-15"
                             "startMinute" 630 "endMinute" 660
                             "start" "10:30am" "end" "11:00am"
                             "roomId" "room-main" "room" "Main Stage"}]}]})
        speaker-map
        {"sessionId" "talk-7"
         "schedule" {"day" "2026-10-15" "dayNumber" 2
                     "dayLabel" "Day 2 — Oct 15"
                     "startMinute" 630 "endMinute" 660
                     "start" "10:30am" "end" "11:00am"
                     "roomId" "room-main" "room" "Main Stage"}}
        speaker-json (json/write-str speaker-map)
        evidence {:event-json event-json
                  :canonical-schedule-json canonical-json
                  :speaker-schedule-json speaker-json}
        result (predicates/spk-11 evidence)]
    (testing "the speaker projection equals the canonical placement and day arithmetic"
      (is (true? (:verdict result)))
      (is (= [{:name :event-json :bytes event-json}
              {:name :canonical-schedule-json :bytes canonical-json}
              {:name :speaker-schedule-json :bytes speaker-json}]
             (:examined result))))

    (testing "a near match is an exact, deterministic failure"
      (let [near-json (json/write-str (assoc-in speaker-map ["schedule" "room"]
                                               "Main Stag"))]
        (is (false? (:verdict
                     (predicates/spk-11
                      (assoc evidence :speaker-schedule-json near-json)))))))

    (testing "day number is derived from dates, not trusted from prose"
      (let [wrong-day-number
            (json/write-str (assoc-in speaker-map ["schedule" "dayNumber"] 1))]
        (is (false? (:verdict
                     (predicates/spk-11
                      (assoc evidence :speaker-schedule-json wrong-day-number)))))))))

(defn- setup-overlap!
  []
  (let [event (events/create-eais-event!
               {:name "Predicate Summit" :slug "predicate-summit" :tz "America/New_York"
                :starts-on (LocalDate/of 2026 10 14)
                :ends-on (LocalDate/of 2026 10 15)
                :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
                :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
               "predicate-test")
        committee-id (:id (first (events/committees-for-event (:id event))))
        _ (committees/add-member! committee-id
                                  {:name "Gene Kim" :email "gene@example.com" :role "chair"}
                                  "predicate-test")
        fields (:fields (events/form-for-event (:id event)))
        create (fn [title]
                 (let [params {:answer-talk-title title
                               :answer-abstract "An exact conflict fixture."
                               :answer-session-format "Experience Report"
                               :answer-org-size ">10,000"
                               :answer-industry "Insurance"
                               :answer-ai-transformation-history "2023."
                               :answer-measurable-outcomes "Numbers."
                               :answer-notes-to-committee "Private."
                               :speaker-name "Marcus Devlin"
                               :speaker-email "marcus@example.com"
                               :speaker-title "VP"
                               :speaker-org "Meridian"
                               :speaker-bio "Bio."}]
                   (submissions/create-submission!
                    event (submissions/parse-answers fields params)
                    (submissions/parse-speaker params) "form" "predicate-test")))
        a (create "Talk A")
        b (create "Talk B")]
    (doseq [submission [a b]]
      (reviews/set-status! (:id submission) "Accepted" "gene@example.com")
      (inform/inform! event (store/submission-by-id (:id submission)) "gene@example.com"))
    {:event (events/event-by-slug "predicate-summit") :a a :b b}))

;; INTENT-TEST: PRED-003 — API-built overlap evidence decides AIA-04/05.
(deftest aia-04-05-conflict-predicates-from-api-fixture-test
  (let [{:keys [event a b]} (setup-overlap!)
        handler (server/create-app)
        token (auth/issue-token! "gene@example.com")
        login (handler (mock/request :get (str "/auth/" token)))
        cookie (first (str/split (first (get-in login [:headers "Set-Cookie"])) #";"))
        as-member #(mock/header % "cookie" cookie)
        room-response
        (handler (as-member
                  (mock/request :post "/api/events/predicate-summit/schedule/room-add"
                                {"name" "Main Stage"})))
        room-id (:id (first (store/rooms-for-event (:id event))))
        place (fn [submission]
                (handler
                 (as-member
                  (mock/request :post "/api/events/predicate-summit/schedule/place"
                                {"submission-id" (:id submission)
                                 "day" "2026-10-14"
                                 "start" "10:30"
                                 "duration" "30"
                                 "room-id" room-id}))))
        a-response (place a)
        b-response (place b)
        person (committees/person-by-email "gene@example.com")
        api-response (commands/invoke!
                      {:event-slug "predicate-summit"
                       :person person
                       :actor "gene@example.com"
                       :source :test}
                      "schedule_conflicts" {})
        conflicts-json (json/write-str (get api-response "data"))
        speaker-id (str (get-in (store/submission-by-id (:id a))
                                [:speakers 0 :person-id]))
        placement (fn [submission]
                    {"submissionId" (:id submission)
                     "title" (get-in submission [:answers :talk-title])
                     "day" "2026-10-14"
                     "startMinute" 630 "endMinute" 660
                     "roomId" room-id})
        expected-json
        (json/write-str
         {"personId" speaker-id
          "speakerMessage" "⚡ Marcus Devlin is also in Main Stage at 10:30am"
          "roomId" room-id
          "roomMessage" "Room double-booked: Main Stage at 10:30am"
          "placements" [(placement a) (placement b)]})
        evidence {:conflicts-json conflicts-json
                  :expected-overlap-json expected-json}]
    (testing "the overlap itself is constructed through authenticated HTTP APIs"
      (is (= 303 (:status room-response)))
      (is (= 303 (:status a-response)))
      (is (= 303 (:status b-response))))

    (testing "the captured speaker conflict names the exact person and both placements"
      (let [result (predicates/aia-04 evidence)]
        (is (true? (:verdict result)))
        (is (= conflicts-json
               (:bytes (first (:examined result)))))))

    (testing "the captured room conflict names the exact room and both placements"
      (is (true? (:verdict (predicates/aia-05 evidence)))))

    (testing "a near name cannot receive credit"
      (let [near-json
            (json/write-str
             {"personId" speaker-id
              "speakerMessage" "⚡ Marcus Devli is also in Main Stage at 10:30am"
              "roomId" room-id
              "roomMessage" "Room double-booked: Main Stage at 10:30am"
              "placements" [(placement a) (placement b)]})]
        (is (false? (:verdict
                     (predicates/aia-04
                      (assoc evidence :expected-overlap-json near-json)))))))))

;; INTENT-TEST: PRED-003 — absent evidence is a measurement gap, not false.
(deftest missing-schedule-evidence-is-cannot-judge-test
  (let [event-json (json/write-str {"startsOn" "2026-10-14"
                                    "endsOn" "2026-10-15"})
        result (predicates/spk-11 {:event-json event-json})]
    (is (= :cannot-judge (:verdict result)))
    (is (not (false? (:verdict result))))
    (is (= [:canonical-schedule-json :speaker-schedule-json]
           (:missing result)))
    (is (= event-json (get-in result [:examined 0 :bytes])))))

(deftest malformed-conflict-evidence-is-cannot-judge-test
  (let [result (predicates/aia-04
                {:conflicts-json "{not-json"
                 :expected-overlap-json "{}"})]
    (is (= :cannot-judge (:verdict result)))
    (is (= [:conflicts-json/valid-json] (:missing result)))))
