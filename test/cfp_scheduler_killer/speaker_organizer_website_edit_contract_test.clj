(ns cfp-scheduler-killer.speaker-organizer-website-edit-contract-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.speakers :as speakers]
   [cfp-scheduler-killer.store :as store]
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

(deftest organizer-can-maintain-the-speakers-canonical-website
  (let [handler (server/create-app)
        organizer-cookie (session-cookie handler "organizer@example.com")
        organizer (partial as organizer-cookie)
        _ (handler
            (organizer
              (mock/request :post "/api/events/create"
                            {"name" "Organizer Website Summit"
                             "slug" "organizer-website"
                             "starts-on" "2026-10-14"
                             "ends-on" "2026-10-15"
                             "presenter-visibility-mode" "visible"})))
        event (events/event-by-slug "organizer-website")
        result (speakers/add! (:id event)
                              {:name "Priya Website"
                               :email "priya-website@example.com"
                               :status "Invited"
                               :actor "organizer@example.com"})
        person-id (:person-id result)
        edit-path (str "/api/events/organizer-website/speakers/" person-id)
        website-url "https://priya.example.com/speaking"]
    ;; 2026-08-17 redesign (7b54469): the per-row inline edit <details> was
    ;; extracted to its own page at GET /events/:slug/speakers/:person-id.
    ;; The roster listing no longer prints the speaker-managed website link
    ;; inline; the website-url input and its saved value live on the edit page.
    (testing "the shared-profile form offers the canonical website"
      (let [body (:body
                   (handler
                     (organizer
                       (mock/request :get (str "/events/organizer-website/speakers/" person-id)))))]
        (is (str/includes? body "name=\"website-url\""))))

    (testing "one organizer save updates the same website the speaker owns"
      (let [response
            (handler
              (organizer
                (mock/request :post edit-path
                              {"profile-edit" "true"
                               "name" "Priya Website"
                               "website-url" website-url})))
            person (store/person-by-id person-id)
            speaker (first (speakers/roster-for-event (:id event)))
            body (:body
                   (handler
                     (organizer
                       (mock/request :get (str "/events/organizer-website/speakers/" person-id)))))]
        (is (= 303 (:status response)))
        (is (= website-url (get-in person [:profile :website-url])))
        (is (= website-url (:website-url speaker)))
        (is (str/includes? body
                           (str "name=\"website-url\" type=\"url\" value=\""
                                website-url "\"")))))))
