(ns cfp-scheduler-killer.speaker-organizer-tagline-edit-contract-test
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

(deftest organizer-can-maintain-the-speakers-canonical-tagline
  (let [handler (server/create-app)
        organizer-cookie (session-cookie handler "organizer@example.com")
        organizer (partial as organizer-cookie)
        _ (handler
            (organizer
              (mock/request :post "/api/events/create"
                            {"name" "Organizer Tagline Summit"
                             "slug" "organizer-tagline"
                             "starts-on" "2026-10-14"
                             "ends-on" "2026-10-15"
                             "presenter-visibility-mode" "visible"})))
        event (events/event-by-slug "organizer-tagline")
        result (speakers/add! (:id event)
                              {:name "Priya Tagline"
                               :email "priya-tagline@example.com"
                               :status "Invited"
                               :actor "organizer@example.com"})
        person-id (:person-id result)
        event-title "Event-specific host title"
        _ (speakers/edit! (:id event) person-id {:title event-title}
                          "organizer@example.com")
        edit-path (str "/api/events/organizer-tagline/speakers/" person-id)
        tagline "VP Engineering · Reliable systems"]
    ;; 2026-08-17 redesign (7b54469): the per-row inline edit <details> was
    ;; extracted to its own page at GET /events/:slug/speakers/:person-id.
    ;; The roster listing now shows a compact row + "Edit Speaker Details"
    ;; link; the tagline input itself lives on the edit page.
    (testing "the shared-profile form names the canonical tagline explicitly"
      (let [body (:body
                   (handler
                     (organizer
                       (mock/request :get (str "/events/organizer-tagline/speakers/" person-id)))))]
        (is (str/includes? body "name=\"tagline\" value=\"\""))))

    (testing "one organizer save updates the speaker-owned tagline"
      (let [response
            (handler
              (organizer
                (mock/request :post edit-path
                              {"profile-edit" "true"
                               "name" "Priya Tagline"
                               "tagline" tagline})))
            person (store/person-by-id person-id)
            speaker (first (speakers/roster-for-event (:id event)))
            body (:body
                   (handler
                     (organizer
                       (mock/request :get (str "/events/organizer-tagline/speakers/" person-id)))))]
        (is (= 303 (:status response)))
        (is (= tagline (get-in person [:profile :tagline])))
        (is (= tagline (:tagline speaker)))
        (is (= event-title (:title speaker)))
        (is (str/includes? body
                           (str "name=\"tagline\" value=\"" tagline "\"")))))))
