(ns cfp-scheduler-killer.speaker-people-profile-links-contract-test
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

(deftest portal-profile-links-appear-on-the-organizer-person-view
  (let [event (events/create-event!
                {:name "Speaker Links Summit"
                 :slug "speaker-links"
                 :tz "America/New_York"}
                "organizer@example.com")
        result (speakers/add! (:id event)
                              {:name "Priya Links"
                               :email "priya-links@example.com"
                               :status "Invited"
                               :actor "organizer@example.com"})
        person-id (:person-id result)
        linkedin-url "https://www.linkedin.com/in/priya-links"
        website-url "https://priya.example.com"
        _ (portal/update-profile! person-id
                                  {:linkedin-url linkedin-url
                                   :website-url website-url}
                                  "priya-links@example.com")
        person (store/person-by-id person-id)
        body (str (view-people/person-page
                    event
                    {:person person
                     :memberships []
                     :review-summary {:rated-count 0
                                      :total-submissions 0
                                      :ratings []
                                      :comments []}}))]
    (testing "the portal stores the current canonical links"
      (is (= linkedin-url (get-in person [:profile :linkedin-url])))
      (is (= website-url (get-in person [:profile :website-url]))))
    (testing "the organizer sees both speaker-managed links"
      (is (str/includes? body (str "href=\"" linkedin-url "\"")))
      (is (str/includes? body ">LinkedIn</a>"))
      (is (str/includes? body (str "href=\"" website-url "\"")))
      (is (str/includes? body ">Website</a>"))
      (is (not (str/includes? body "No profile details yet"))))))
