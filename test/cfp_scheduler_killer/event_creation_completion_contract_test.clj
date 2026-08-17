(ns cfp-scheduler-killer.event-creation-completion-contract-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.forms :as forms]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock])
  (:import
   (org.jsoup Jsoup)))

(use-fixtures :each with-temp-store)

(defn- login-cookie [handler email]
  (let [token (auth/issue-token! email)
        response (handler (mock/request :get (str "/auth/" token)))]
    (first (str/split (first (get-in response [:headers "Set-Cookie"])) #";"))))

(deftest freshly-created-open-event-reports-its-real-setup-state-test
  (let [handler (server/create-app)
        cookie (login-cookie handler "organizer@example.com")
        as-organizer #(handler (mock/header % "cookie" cookie))
        create-response
        (as-organizer
          (mock/request :post "/api/events/create"
                        {"name" "Completion Truth Summit"
                         "slug" "completion-truth"
                         "starts-on" "2026-10-14"
                         "ends-on" "2026-10-15"
                         "presenter-visibility-mode" "visible"}))
        event (events/event-by-slug "completion-truth")
        committee (first (events/committees-for-event (:id event)))
        members (committees/members-for-committee (:id committee))
        dashboard-response
        (as-organizer (mock/request :get "/events/completion-truth"))
        document (Jsoup/parse (:body dashboard-response))
        marker-state
        (into {}
              (map (fn [element]
                     [(.attr element "data-setup-marker")
                      (.attr element "data-complete")]))
              (.select document "[data-setup-marker]"))]
    (testing "creation lands inside the organizer workspace with a next action"
      (is (= 303 (:status create-response)))
      (is (= "/events/completion-truth"
             (get-in create-response [:headers "Location"])))
      (is (= 200 (:status dashboard-response)))
      (is (str/includes? (.text document) "Share the public link")))

    (testing "the facts created atomically are all genuinely present"
      (is (seq (forms/fields-for-event (:id event))))
      (is (= :open (submissions/cfp-state event)))
      (is (some #(= "chair" (:role %)) members)))

    (testing "every corresponding completion marker tells that same truth"
      (is (= {"event" "true"
              "cfp-form" "true"
              "review-committee" "true"
              "public-cfp" "true"}
             (select-keys marker-state
                          ["event" "cfp-form" "review-committee" "public-cfp"])))
      (doseq [href ["/events/completion-truth/form"
                    "/events/completion-truth/committee"
                    "/cfp/completion-truth"]]
        (is (some? (.selectFirst document
                                (str "a[href='" href "'] .sb-inline-check")))
            (str href " is visibly complete in the event spine"))))))
