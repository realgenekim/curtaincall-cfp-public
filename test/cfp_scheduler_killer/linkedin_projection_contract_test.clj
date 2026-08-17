(ns cfp-scheduler-killer.linkedin-projection-contract-test
  (:require
   [cfp-scheduler-killer.speakers :as speakers]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [cfp-scheduler-killer.views.speakers :as speaker-view]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each with-temp-store)

(deftest speaker-managed-linkedin-survives-roster-to-form-projection-test
  (store/append! {:type "event.created"
                  :actor "organizer@example.com"
                  :event-id "linkedin-event"
                  :payload {:id "linkedin-event"
                            :slug "linkedin"
                            :name "LinkedIn Summit"
                            :created-at (store/now-iso)}})
  (speakers/add! "linkedin-event"
                 {:name "Priya Raghavan"
                  :email "priya@example.com"
                  :status "Invited"
                  :actor "organizer@example.com"})
  (let [person-id (:person-id (first (speakers/roster-for-event "linkedin-event")))
        linkedin "https://www.linkedin.com/in/priya-cross-layer-sentinel"]
    (store/append! {:type "person.profile-updated"
                    :actor "priya@example.com"
                    :payload {:person-id person-id
                              :changed ["linkedin-url"]
                              :changes {:linkedin-url linkedin}
                              :at (store/now-iso)}})
    (let [roster (speakers/roster-for-event "linkedin-event")
          html (speaker-view/speaker-page
                 {:id "linkedin-event" :slug "linkedin" :name "LinkedIn Summit"}
                 {:person nil :speaker (first roster) :custom-fields []})]
      (testing "person-owned profile data crosses the canonical roster projection"
        (is (= linkedin (:linkedin-url (first roster)))))
      (testing "the organizer speaker form renders the canonical LinkedIn URL"
        (is (str/includes? html "name=\"linkedin-url\""))
        (is (str/includes? html (str "value=\"" linkedin "\"")))))))
