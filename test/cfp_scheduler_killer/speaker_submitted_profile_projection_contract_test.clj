(ns cfp-scheduler-killer.speaker-submitted-profile-projection-contract-test
  (:require
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.portal :as portal]
   [cfp-scheduler-killer.speakers :as speakers]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each with-temp-store)

(defn- create-submission! [event]
  (let [fields (:fields (events/form-for-event (:id event)))
        params {:answer-talk-title "Organization after submission"
                :answer-abstract "A practical experience report."
                :answer-session-format "Experience Report"
                :answer-track "Developer Practices"
                :answer-org-size ">10,000"
                :answer-industry "Technology"
                :answer-ai-transformation-history "2025."
                :answer-measurable-outcomes "Measured outcomes."
                :speaker-name "Priya Speaker"
                :speaker-email "priya-submitted@example.com"
                :speaker-title "VP Engineering"
                :speaker-org "Snapshot Org"
                :speaker-bio "An experienced speaker."}]
    (submissions/create-submission!
      event
      (submissions/parse-answers fields params)
      (submissions/parse-speaker params)
      "form"
      "kaocha")))

(deftest submitted-speaker-roster-follows-canonical-organization
  (let [event (events/create-event!
                {:name "Submitted Organization Summit"
                 :slug "submitted-organization"
                 :tz "America/New_York"}
                "kaocha")
        submission (create-submission! event)
        person (store/person-by-email "priya-submitted@example.com")]
    (is (= "Snapshot Org"
           (:organization (first (speakers/roster-for-event (:id event))))))

    (portal/update-profile! (:id person) {:org "Canonical Org"}
                            "priya-submitted@example.com")

    (let [fresh-person (store/person-by-id (:id person))
          fresh-submission (store/submission-by-id (:id submission))
          after-profile-edit (first (speakers/roster-for-event (:id event)))]
      (testing "the immutable submission keeps its historical snapshot"
        (is (= "Snapshot Org" (get-in fresh-submission [:speakers 0 :org]))))
      (testing "the organizer roster follows the speaker's canonical profile"
        (is (= "Canonical Org" (get-in fresh-person [:profile :org])))
        (is (= "Canonical Org" (:organization after-profile-edit)))))

    (speakers/edit! (:id event) (:id person)
                    {:organization "Event Program Org"}
                    "organizer@example.com")

    (testing "an explicit event-local organizer override remains authoritative"
      (is (= "Event Program Org"
             (:organization (first (speakers/roster-for-event (:id event)))))))))
