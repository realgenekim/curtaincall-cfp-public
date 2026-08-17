(ns cfp-scheduler-killer.speaker-csv-headshot-import-contract-test
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

(deftest csv-headshot-reaches-both-surfaces-after-validation-test
  (let [handler (server/create-app)
        organizer-email "organizer@example.com"
        organizer-cookie (login-cookie handler organizer-email)
        _ (handler
            (as (mock/request :post "/api/events/create"
                              {"name" "Headshot Import Summit"
                               "slug" "headshot-import"
                               "starts-on" "2026-10-14"
                               "ends-on" "2026-10-15"
                               "presenter-visibility-mode" "visible"})
                organizer-cookie))
        event (events/event-by-slug "headshot-import")
        invalid-before (count (store/read-events))
        invalid-response
        (handler
          (as (mock/request :post "/api/events/headshot-import/speakers/import"
                            {"csv-text"
                             (str "Name,Email,Headshot URL\n"
                                  "Bad Photo,bad-photo@example.com,javascript:alert(1)\n")})
              organizer-cookie))
        invalid-after (count (store/read-events))
        headshot-url "https://images.example.test/ada-csv.png"
        csv-text (str "Name,Email,Photo URL,Status\n"
                      "Ada Speaker,ada@example.com," headshot-url ",Invited\n")
        import-response
        (handler
          (as (mock/request :post "/api/events/headshot-import/speakers/import"
                            {"csv-text" csv-text})
              organizer-cookie))
        speaker (some #(when (= "ada@example.com" (:email %)) %)
                      (speakers/roster-for-event (:id event)))
        person (store/person-by-id (:person-id speaker))
        organizer-body
        (:body (handler
                 (as (mock/request :get "/events/headshot-import/speakers")
                     organizer-cookie)))
        speaker-cookie (login-cookie handler "ada@example.com")
        portal-body (:body (handler (as (mock/request :get "/portal") speaker-cookie)))
        profile-fact
        (last (filter #(and (= "person.profile-updated" (:type %))
                            (= (:person-id speaker) (get-in % [:payload :person-id])))
                      (store/read-events)))
        before-retry (count (store/read-events))
        retry-response
        (handler
          (as (mock/request :post "/api/events/headshot-import/speakers/import"
                            {"csv-text" csv-text})
              organizer-cookie))]
    (is (= 422 (:status invalid-response)))
    (is (str/includes? (:body invalid-response)
                       "Headshot URL must be a complete http:// or https:// URL"))
    (is (= invalid-before invalid-after))
    (is (not-any? #(= "bad-photo@example.com" (:email %))
                  (speakers/roster-for-event (:id event)))
        "an invalid profile URL must reject the whole CSV before writes")
    (is (= 303 (:status import-response)))
    (is (= headshot-url (get-in person [:profile :headshot-url])))
    (is (= headshot-url (:headshot-url speaker)))
    (is (str/includes? organizer-body (str "src=\"" headshot-url "\"")))
    (is (str/includes? portal-body (str "src=\"" headshot-url "\"")))
    (is (= organizer-email (:actor profile-fact)))
    (is (= ["headshot-url"] (get-in profile-fact [:payload :changed])))
    (is (= 303 (:status retry-response)))
    (is (= before-retry (count (store/read-events)))
        "retrying the identical import must append no facts")))
