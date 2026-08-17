(ns cfp-scheduler-killer.speaker-people-organization-contract-test
  (:require
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.portal :as portal]
   [cfp-scheduler-killer.speakers :as speakers]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [cfp-scheduler-killer.views.people :as view-people]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each with-temp-store)

(deftest portal-organization-appears-on-the-organizer-person-view
  (let [event (events/create-event!
                {:name "Speaker Organization Summit"
                 :slug "speaker-organization"
                 :tz "America/New_York"}
                "organizer@example.com")
        result (speakers/add! (:id event)
                              {:name "Priya Organization"
                               :email "priya-organization@example.com"
                               :status "Invited"
                               :actor "organizer@example.com"})
        person-id (:person-id result)
        organization "Current Speaker Org"
        _ (portal/update-profile! person-id {:org organization}
                                  "priya-organization@example.com")
        person (store/person-by-id person-id)
        body (str (view-people/person-page
                    event
                    {:person person
                     :memberships []
                     :review-summary {:rated-count 0
                                      :total-submissions 0
                                      :ratings []
                                      :comments []}}))]
    (testing "the portal stores the current canonical organization"
      (is (= organization (get-in person [:profile :org]))))
    (testing "the organizer person view renders that organization"
      (is (str/includes? body "<dt>Organization</dt>"))
      (is (str/includes? body (str "<dd>" organization "</dd>")))
      (is (not (str/includes? body "No profile details yet"))))))
