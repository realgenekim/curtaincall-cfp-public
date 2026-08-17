(ns cfp-scheduler-killer.schedule-grid-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.inform :as inform]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.schedule :as schedule]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock])
  (:import
   (java.time LocalDate LocalDateTime)))

(use-fixtures :each with-temp-store)

(defn- submission!
  [event title email]
  (let [fields (:fields (events/form-for-event (:id event)))
        params {:answer-talk-title title
                :answer-abstract (str title " abstract")
                :answer-session-format "Talk"
                :answer-track "Engineering"
                :speaker-name (str title " Speaker")
                :speaker-email email}]
    (submissions/create-submission!
     event
     (submissions/parse-answers fields params)
     (submissions/parse-speaker params)
     "form"
     "kaocha")))

(defn- publishable!
  [event submission]
  (reviews/set-status! (:id submission) "Accepted" "kaocha")
  (inform/inform! event (store/submission-by-id (:id submission)) "kaocha"))

(deftest schedule-grid-keeps-live-room-time-math-through-partial-states-test
  (let [day "2026-10-14"
        event (events/create-event!
               {:name "Grid Math Summit"
                :slug "grid-math"
                :tz "America/New_York"
                :starts-on (LocalDate/of 2026 10 14)
                :ends-on (LocalDate/of 2026 10 15)
                :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
                :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
               "kaocha")
        committee-id (:id (first (events/committees-for-event (:id event))))
        _ (committees/add-member!
           committee-id
           {:name "Grid Organizer" :email "organizer@example.com" :role "chair"}
           "kaocha")
        placed (submission! event "Placed Session" "placed@example.com")
        unroomed (submission! event "Room Later" "later@example.com")
        _ (publishable! event placed)
        _ (publishable! event unroomed)
        main (schedule/add-room! event "Main Stage" "kaocha")
        breakout (schedule/add-room! event "Breakout" "kaocha")
        _ (schedule/place! event (:id placed)
                           {:day day :start "09:00" :duration "60"
                            :room-id (:id main)}
                           "kaocha")
        _ (schedule/place! event (:id unroomed)
                           {:day day :start "11:00" :duration "25"}
                           "kaocha")
        _ (schedule/add-block! event
                               {:day day :start "10:00" :duration "30"
                                :room-id (:id breakout) :label "Keynote TBD"}
                               "kaocha")
        handler (server/create-app)
        token (auth/issue-token! "organizer@example.com")
        login (handler (mock/request :get (str "/auth/" token)))
        cookie (first (str/split (first (get-in login [:headers "Set-Cookie"])) #";"))
        as-organizer #(mock/header % "cookie" cookie)
        page #(-> (handler (as-organizer
                            (mock/request :get "/events/grid-math/schedule")))
                  :body)]
    (testing "partial states and live room-time math share the working grid"
      (let [body (page)]
        (is (str/includes? body "Keynote TBD"))
        (is (str/includes? body "Unroomed"))
        (is (str/includes? body "Room Later"))
        (is (str/includes? body "1h 30m"))
        (is (str/includes? body "32h"))
        (is (str/includes? body "room time filled"))
        (is (str/includes? body "30h 30m"))
        (is (str/includes? body "open"))))
    (testing "assigning a room late immediately updates the arithmetic"
      (let [response (handler
                      (as-organizer
                       (mock/request
                        :post "/api/events/grid-math/schedule/place"
                        {"submission-id" (:id unroomed)
                         "day" day
                         "start" "11:00"
                         "duration" "25"
                         "room-id" (:id breakout)})))]
        (is (= 303 (:status response)))
        (is (= (:id breakout) (:room-id (store/slot-for (:id unroomed)))))
        (let [body (page)]
          (is (str/includes? body "1h 55m"))
          (is (str/includes? body "30h 5m")))))))
