(ns cfp-scheduler-killer.speaker-custom-fields-routes-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.speaker-custom-fields :as custom]
   [cfp-scheduler-killer.speakers :as speakers]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock]))

(use-fixtures :each with-temp-store)

(defn- login-cookie
  [handler email]
  (let [token (auth/issue-token! email)
        response (handler (mock/request :get (str "/auth/" token)))]
    (first (str/split (first (get-in response [:headers "Set-Cookie"])) #";"))))

(defn- as
  [cookie request]
  (mock/header request "cookie" cookie))

(defn- create-event!
  [handler cookie slug]
  (handler
    (as cookie
        (mock/request :post "/api/events/create"
                      {"name" (str "Custom Fields " slug)
                       "slug" slug
                       "starts-on" "2026-10-14"
                       "ends-on" "2026-10-15"
                       "presenter-visibility-mode" "visible"})))
  (events/event-by-slug slug))

(defn- submit-as-priya!
  [event]
  (events/open-cfp! event "organizer@example.com")
  (let [fields (:fields (events/form-for-event (:id event)))
        params {:answer-talk-title "Making accessibility operational"
                :answer-abstract "A practical account."
                :answer-session-format "Experience Report"
                :answer-track "Developer Practices"
                :answer-org-size ">10,000"
                :answer-industry "Insurance"
                :answer-ai-transformation-history "2023."
                :answer-measurable-outcomes "Numbers."
                :speaker-name "Priya Raghavan"
                :speaker-email "priya@example.com"
                :speaker-title "VP Engineering"
                :speaker-org "Meridian"
                :speaker-bio "Priya runs underwriting platforms."}]
    (submissions/create-submission!
      event
      (submissions/parse-answers fields params)
      (submissions/parse-speaker params)
      "form"
      "speaker")))

(deftest organizer-definition-and-speaker-value-round-trip
  (let [handler (server/create-app)
        organizer-cookie (login-cookie handler "organizer@example.com")
        event (create-event! handler organizer-cookie "custom-fields")]
    (submit-as-priya! event)

    (testing "the organizer defines an event-scoped required person field"
      (let [response (handler
                       (as organizer-cookie
                           (mock/request
                             :post "/api/events/custom-fields/speaker-fields"
                             {"label" "Dietary or accessibility needs"
                              "type" "textarea"
                              "required" "on"})))
            field (first (custom/fields-for-event (:id event)))]
        (is (= 303 (:status response)))
        (is (= "dietary-or-accessibility-needs" (:id field)))
        (is (:required field))
        (is (some #(= "speaker.custom-field-defined" (:type %)) (events/log-for-event (:id event)))))
      (let [body (:body (handler
                          (as organizer-cookie
                              (mock/request :get "/events/custom-fields/speakers"))))]
        (is (str/includes? body "Custom person fields"))
        (is (str/includes? body "Dietary or accessibility needs"))))

    (testing "the organizer can save logistics on the speaker record and reload it"
      (let [person-id (:person-id (first (speakers/roster-for-event (:id event))))
            record-url (str "/api/events/custom-fields/speakers/" person-id)
            speaker-url (str "/events/custom-fields/speakers/" person-id)
            speaker-body (:body (handler
                                  (as organizer-cookie
                                      (mock/request :get speaker-url))))
            response (handler
                       (as organizer-cookie
                           (mock/request
                             :post record-url
                             {"profile-edit" "true"
                              "custom-dietary-or-accessibility-needs"
                              "Arrival May 11, aisle seat; dietary: Vegetarian"})))]
        (is (str/includes? speaker-body
                           "name=\"custom-dietary-or-accessibility-needs\""))
        (is (= 303 (:status response)))
        (store/load!)
        (is (= "Arrival May 11, aisle seat; dietary: Vegetarian"
               (get (custom/values-for (:id event) person-id)
                    "dietary-or-accessibility-needs")))
        (is (str/includes?
              (:body (handler
                       (as organizer-cookie
                           (mock/request :get speaker-url))))
              "Arrival May 11, aisle seat; dietary: Vegetarian"))))

    (let [speaker-cookie (login-cookie handler "priya@example.com")]
      (testing "the speaker sees the field and required validation is server-owned"
        (let [portal (:body (handler (as speaker-cookie (mock/request :get "/portal"))))
              rejected (handler
                         (as speaker-cookie
                             (mock/request
                               :post "/api/events/custom-fields/speaker-custom-values"
                               {"custom-dietary-or-accessibility-needs" ""})))]
          (is (str/includes? portal "Event details — Custom Fields custom-fields"))
          (is (str/includes? portal "Save event details"))
          (is (= 422 (:status rejected)))
          (is (str/includes? (:body rejected)
                             "Dietary or accessibility needs is required"))))

      (testing "a valid answer persists, reloads, and appears to the organizer"
        (let [response (handler
                         (as speaker-cookie
                             (mock/request
                               :post "/api/events/custom-fields/speaker-custom-values"
                               {"custom-dietary-or-accessibility-needs" "Vegetarian"})))
              person-id (:person-id (first (speakers/roster-for-event (:id event))))]
          (is (= 303 (:status response)))
          (is (= {"dietary-or-accessibility-needs" "Vegetarian"}
                 (custom/values-for (:id event) person-id)))
          (store/load!)
          (is (= "Vegetarian"
                 (get (custom/values-for (:id event) person-id)
                      "dietary-or-accessibility-needs")))
          (is (str/includes?
                (:body (handler
                         (as organizer-cookie
                             (mock/request
                               :get (str "/events/custom-fields/speakers/" person-id)))))
                "Vegetarian"))))

      (testing "the same identity cannot write values into an event they do not belong to"
        (let [other (create-event! handler organizer-cookie "other-event")
              _ (handler
                  (as organizer-cookie
                      (mock/request :post "/api/events/other-event/speaker-fields"
                                    {"label" "Dietary or accessibility needs"
                                     "type" "textarea"})))
              before (count (events/log-for-event (:id other)))
              response (handler
                         (as speaker-cookie
                             (mock/request
                               :post "/api/events/other-event/speaker-custom-values"
                               {"custom-dietary-or-accessibility-needs" "Secret"})))]
          (is (= 404 (:status response)))
          (is (= before (count (events/log-for-event (:id other))))))))))
