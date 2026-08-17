(ns cfp-scheduler-killer.speaker-csv-bio-import-contract-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.speakers :as speakers]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is use-fixtures]]
   [ring.mock.request :as mock]))

(use-fixtures :each with-temp-store)

(defn- login-cookie [handler email]
  (let [token (auth/issue-token! email)
        response (handler (mock/request :get (str "/auth/" token)))]
    (first (str/split (first (get-in response [:headers "Set-Cookie"])) #";"))))

(defn- as [request cookie]
  (mock/header request "cookie" cookie))

(deftest csv-biography-reaches-the-canonical-speaker-profile-test
  (let [handler (server/create-app)
        organizer-email "organizer@example.com"
        organizer-cookie (login-cookie handler organizer-email)
        _ (handler
            (as (mock/request :post "/api/events/create"
                              {"name" "Biography Import Summit"
                               "slug" "bio-import"
                               "starts-on" "2026-10-14"
                               "ends-on" "2026-10-15"
                               "presenter-visibility-mode" "visible"})
                organizer-cookie))
        event (events/event-by-slug "bio-import")
        biography "Imported biography sentinel for both sides of the portal."
        csv-text (str "Name,Email,Bio,Status\n"
                      "Ada Speaker,ada@example.com," biography ",Invited\n")
        import-response
        (handler
          (as (mock/request :post "/api/events/bio-import/speakers/import"
                            {"csv-text" csv-text})
              organizer-cookie))
        speaker (first (speakers/roster-for-event (:id event)))
        person (store/person-by-id (:person-id speaker))
        ;; 2026-08-17 redesign (7b54469): the roster row no longer prints the
        ;; bio text inline — it lives on the extracted edit page at
        ;; /events/:slug/speakers/:person-id (see speaker-edit-form).
        organizer-body
        (:body (handler
                 (as (mock/request :get (str "/events/bio-import/speakers/" (:person-id speaker)))
                     organizer-cookie)))
        speaker-cookie (login-cookie handler "ada@example.com")
        portal-body (:body (handler (as (mock/request :get "/portal") speaker-cookie)))
        profile-fact (last (filter #(= "person.profile-updated" (:type %))
                                   (store/read-events)))
        before-retry (count (store/read-events))
        retry-response
        (handler
          (as (mock/request :post "/api/events/bio-import/speakers/import"
                            {"csv-text" csv-text})
              organizer-cookie))]
    (is (= 303 (:status import-response)))
    (is (= biography (get-in person [:profile :bio]))
        "the accepted Bio column must become canonical profile data")
    (is (= biography (:bio speaker))
        "the organizer roster must project the imported biography")
    (is (str/includes? organizer-body biography))
    (is (str/includes? portal-body biography)
        "the imported speaker must see the same biography in their portal")
    (is (= organizer-email (:actor profile-fact)))
    (is (= (:person-id speaker) (get-in profile-fact [:payload :person-id])))
    (is (= 303 (:status retry-response)))
    (is (= before-retry (count (store/read-events)))
        "retrying the identical import must append no facts")))
