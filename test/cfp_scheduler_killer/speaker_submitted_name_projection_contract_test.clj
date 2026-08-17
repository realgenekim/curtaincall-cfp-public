(ns cfp-scheduler-killer.speaker-submitted-name-projection-contract-test
  (:require
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.speakers :as speakers]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each with-temp-store)

(defn- create-submission! [event name email title]
  (let [fields (:fields (events/form-for-event (:id event)))
        params {:answer-talk-title title
                :answer-abstract "A practical experience report."
                :answer-session-format "Experience Report"
                :answer-track "Developer Practices"
                :answer-org-size ">10,000"
                :answer-industry "Technology"
                :answer-ai-transformation-history "2025."
                :answer-measurable-outcomes "Measured outcomes."
                :speaker-name name
                :speaker-email email
                :speaker-title "VP Engineering"
                :speaker-org "Projection Labs"
                :speaker-bio "An experienced speaker."}]
    (submissions/create-submission!
      event
      (submissions/parse-answers fields params)
      (submissions/parse-speaker params)
      "form"
      "kaocha")))

(deftest submitted-speaker-roster-follows-the-corrected-canonical-name
  (let [event (events/create-event!
                {:name "Submitted Name Summit"
                 :slug "submitted-name"
                 :tz "America/New_York"}
                "kaocha")
        submission (create-submission!
                     event "Katherine Snapshot" "katherine-submitted@example.com"
                     "Identity after submission")
        person (store/person-by-email "katherine-submitted@example.com")
        before (first (speakers/roster-for-event (:id event)))]
    (is (= "Katherine Snapshot" (:name before)))

    (speakers/rename! (:id event) (:id person)
                      "Katherine Canonical" "organizer@example.com")

    (let [fresh-person (store/person-by-id (:id person))
          fresh-submission (store/submission-by-id (:id submission))
          after (first (speakers/roster-for-event (:id event)))]
      (testing "the immutable submission keeps its historical snapshot"
        (is (= "Katherine Snapshot" (get-in fresh-submission [:speakers 0 :name]))))
      (testing "the organizer roster follows the corrected canonical identity"
        (is (= "Katherine Canonical" (:name fresh-person)))
        (is (= "Katherine Canonical" (:name after)))
        (is (= ["Identity after submission"] (:talks after)))))))
