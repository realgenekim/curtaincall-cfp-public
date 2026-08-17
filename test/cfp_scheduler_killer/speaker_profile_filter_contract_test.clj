(ns cfp-scheduler-killer.speaker-profile-filter-contract-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.portal :as portal]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.speakers :as speakers]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock]))

(use-fixtures :each with-temp-store)

(defn- session-cookie [handler email]
  (let [token (auth/issue-token! email)
        response (handler (mock/request :get (str "/auth/" token)))]
    (first (str/split (first (get-in response [:headers "Set-Cookie"])) #";"))))

(defn- as [cookie request]
  (mock/header request "cookie" cookie))

(deftest organizer-can-filter-speakers-by-profile-completion
  (let [handler (server/create-app)
        organizer-cookie (session-cookie handler "organizer@example.com")
        organizer (partial as organizer-cookie)
        _ (handler
            (organizer
              (mock/request :post "/api/events/create"
                            {"name" "Profile Filter Summit"
                             "slug" "profile-filter"
                             "starts-on" "2026-10-14"
                             "ends-on" "2026-10-15"
                             "presenter-visibility-mode" "visible"})))
        event (events/event-by-slug "profile-filter")
        _ (speakers/add! (:id event)
                         {:name "Incomplete Ada"
                          :email "incomplete-ada@example.com"
                          :status "Invited"
                          :actor "organizer@example.com"})
        complete (speakers/add! (:id event)
                                {:name "Complete Grace"
                                 :email "complete-grace@example.com"
                                 :status "Invited"
                                 :actor "organizer@example.com"})
        _ (portal/update-profile!
            (:person-id complete)
            {:bio "Grace has completed her speaker profile."
             :headshot-url "https://images.example.test/grace.png"}
            "complete-grace@example.com")
        page #(-> (handler
                    (organizer
                      (mock/request :get (str "/events/profile-filter/speakers?profile=" %))))
                  :body)]
    (testing "the roster exposes completion as a first-class filter"
      ;; b1d8c29 retired the Public Profiles dropdown in favor of state chips.
      (is (not (str/includes? (page "incomplete") "name=\"profile\""))))

    (testing "incomplete isolates the speakers who still need follow-up"
      (let [body (page "incomplete")]
        (is (str/includes? body "Incomplete Ada"))
        (is (not (str/includes? body "Complete Grace")))))

    (testing "complete isolates speakers whose required profile is ready"
      (let [body (page "complete")]
        (is (str/includes? body "Complete Grace"))
        (is (not (str/includes? body "Incomplete Ada")))))))
