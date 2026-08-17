(ns cfp-scheduler-killer.embed-widget-test
  (:require
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

(deftest accepted-sessions-embed-is-copyable-and-read-only-test
  (let [day "2026-10-14"
        event (events/create-event!
                {:name "Embed Widget Summit"
                 :slug "embed-widget"
                 :tz "America/New_York"
                 :starts-on (LocalDate/of 2026 10 14)
                 :ends-on (LocalDate/of 2026 10 14)
                 :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
                 :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
                "kaocha")
        accepted (submission! event "Published Session" "published@example.com")
        declined (submission! event "Private Decline" "private@example.com")
        room (schedule/add-room! event "Main Stage" "kaocha")]
    (reviews/set-status! (:id accepted) "Accepted" "kaocha")
    (inform/inform! event (store/submission-by-id (:id accepted)) "kaocha")
    (schedule/place! event (:id accepted)
                     {:day day :start "09:00" :room-id (:id room)}
                     "kaocha")
    (reviews/set-status! (:id declined) "Declined" "kaocha")
    (inform/inform! event (store/submission-by-id (:id declined)) "kaocha")
    (let [handler (server/create-app)
          request #(-> (mock/request :get %)
                       (mock/header "host" "wiki.example"))
          program (handler (request "/program/embed-widget"))
          widget (handler (request "/program/embed-widget?embed=accepted-sessions"))]
      (testing "the program offers an exact copy-paste iframe snippet"
        (is (= 200 (:status program)))
        (is (str/includes? (:body program) "Embed accepted sessions"))
        (is (str/includes?
              (:body program)
              "http://wiki.example/program/embed-widget"))
        (is (str/includes? (:body program) "&lt;iframe"))
        (is (str/includes? (:body program) "Copy to clipboard"))
        (is (str/includes? (:body program) "Copied embed code to clipboard")))
      (testing "embed mode is a public, read-only fragment of published sessions"
        (is (= 200 (:status widget)))
        (is (= "text/html; charset=utf-8"
               (get-in widget [:headers "Content-Type"])))
        (is (str/includes? (:body widget) "accepted-sessions-widget"))
        (is (str/includes? (:body widget) "Published Session"))
        (is (str/includes? (:body widget) "Main Stage"))
        (is (not (str/includes? (:body widget) "Private Decline")))
        (is (not (str/includes? (:body widget) "The program")))
        (is (not (str/includes? (:body widget) "<form")))))))
