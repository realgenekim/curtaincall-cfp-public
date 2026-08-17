(ns cfp-scheduler-killer.speaker-roster-website-projection-contract-test
  (:require
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.portal :as portal]
   [cfp-scheduler-killer.speakers :as speakers]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [cfp-scheduler-killer.views.speakers :as view-speakers]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each with-temp-store)

(deftest portal-website-appears-on-the-organizer-speaker-form
  (let [event (events/create-event!
                {:name "Speaker Website Summit"
                 :slug "speaker-website"
                 :tz "America/New_York"}
                "organizer@example.com")
        result (speakers/add! (:id event)
                              {:name "Priya Website"
                               :email "priya-website@example.com"
                               :status "Invited"
                               :actor "organizer@example.com"})
        person-id (:person-id result)
        website-url "https://priya.example.com/speaking"
        _ (portal/update-profile! person-id {:website-url website-url}
                                  "priya-website@example.com")
        person (store/person-by-id person-id)
        roster (speakers/roster-for-event (:id event))
        speaker (first roster)
        body (str (view-speakers/speaker-page
                    event
                    {:speaker speaker
                     :custom-fields []}))]
    (testing "the portal stores the canonical website"
      (is (= website-url (get-in person [:profile :website-url]))))
    (testing "the organizer speaker form projects the current website"
      (is (= website-url (:website-url speaker)))
      (is (str/includes? body "name=\"website-url\""))
      (is (str/includes? body (str "value=\"" website-url "\""))))))
