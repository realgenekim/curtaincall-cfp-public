(ns cfp-scheduler-killer.speaker-portal-invite-return-contract-test
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

(deftest portal-invite-returns-to-the-organizers-filtered-roster
  (let [handler (server/create-app)
        cookie (session-cookie handler "organizer@example.com")
        organizer (partial as cookie)
        _ (handler
            (organizer
              (mock/request :post "/api/events/create"
                            {"name" "Invite Follow-up Summit"
                             "slug" "invite-follow-up"
                             "starts-on" "2026-10-14"
                             "ends-on" "2026-10-15"
                             "presenter-visibility-mode" "visible"})))
        event (events/event-by-slug "invite-follow-up")
        incomplete (speakers/add! (:id event)
                                  {:name "Ada Followup"
                                   :email "ada-followup@example.com"
                                   :status "Invited"
                                   :actor "organizer@example.com"})
        complete (speakers/add! (:id event)
                                {:name "Grace Ready"
                                 :email "grace-ready@example.com"
                                 :status "Invited"
                                 :actor "organizer@example.com"})
        _ (portal/update-profile! (:person-id complete)
                                  {:bio "Grace is ready for the program."
                                   :headshot-url "https://images.example.test/grace.png"}
                                  "grace-ready@example.com")
        filtered-body (:body
                        (handler
                          (organizer
                            (mock/request
                              :get
                              "/events/invite-follow-up/speakers?q=Ada&status=Invited&profile=incomplete"))))
        response (handler
                   (organizer
                     (mock/request
                       :post
                       (str "/api/events/invite-follow-up/speakers/"
                            (:person-id incomplete) "/portal-invite")
                       {"return-q" "Ada"
                        "return-status" "Invited"
                        "return-profile" "incomplete"})))
        expected-location (str "/events/invite-follow-up/speakers"
                               "?q=Ada&status=Invited&profile=incomplete"
                               "&notice=portal-invite#speaker-"
                               (:person-id incomplete))]
    (testing "the invite form carries the current roster context"
      (is (str/includes?
            filtered-body
            (str "action=\"/api/events/invite-follow-up/speakers/"
                 (:person-id incomplete)
                 "/portal-invite\" method=\"post\"><input name=\"return-q\""
                 " type=\"hidden\" value=\"Ada\" />"))))

    (testing "the successful invite preserves the organizer's working set"
      (is (= 303 (:status response)))
      (is (= expected-location (get-in response [:headers "Location"]))))

    (testing "following the redirect stays in the filtered follow-up queue"
      (let [path (first (str/split expected-location #"#"))
            body (:body (handler (organizer (mock/request :get path))))]
        (is (str/includes? body "Portal invite prepared and recorded in Comms."))
        (is (str/includes? body "Ada Followup"))
        (is (not (str/includes? body "Grace Ready")))))))
