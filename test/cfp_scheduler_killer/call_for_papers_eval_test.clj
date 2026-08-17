(ns cfp-scheduler-killer.call-for-papers-eval-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.forms :as forms]
   [cfp-scheduler-killer.mail :as mail]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [cfp-scheduler-killer.views.public-cfp :as public-cfp-view]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [hiccup2.core :as h]
   [ring.mock.request :as mock])
  (:import
   (java.time LocalDate LocalDateTime)))

(use-fixtures :each with-temp-store (fn [f] (reset! auth/tokens {}) (f)))

(defn- make-event! []
  (let [event (events/create-event!
                {:name "CFP Eval Summit"
                 :slug "cfp-eval"
                 :tz "America/Los_Angeles"
                 :support-email "organizer@example.com"
                 :starts-on (LocalDate/of 2027 5 12)
                 :ends-on (LocalDate/of 2027 5 14)
                 :cfp-opens-at (LocalDateTime/of 2026 1 1 0 0)
                 :cfp-closes-at (LocalDateTime/of 2028 1 1 0 0)}
                "kaocha")
        committee-id (:id (first (events/committees-for-event (:id event))))]
    (committees/add-member! committee-id
                            {:name "Jordan Organizer"
                             :email "organizer@example.com"
                             :role "chair"}
                            "kaocha")
    (forms/mark-reviewed! event "organizer@example.com")
    event))

(defn- submission! [event name email title]
  (submissions/create-submission!
    event
    {:talk-title title :abstract "A proposal." :session-format "Talk"}
    {:name name :email email :title "Engineer" :org "Example"
     :bio "Speaker biography."}
    "form"
    "kaocha"))

(defn- login-cookie [handler email]
  (let [token (auth/issue-token! email)
        response (handler (mock/request :get (str "/auth/" token)))]
    (first (str/split (first (get-in response [:headers "Set-Cookie"])) #";"))))

(defn- as [cookie request]
  (mock/header request "cookie" cookie))

(deftest cfp-02-conditional-question-toggles-with-the-controlling-signal
  (let [fields [{:id :session-format :type :select :label "Session format"
                 :options ["Talk (30 min)" "Workshop (120 min)"]}
                {:id :workshop-prerequisites :type :textarea
                 :label "Workshop prerequisites"
                 :show-when {:field-id "session-format"
                             :equals "Workshop (120 min)"}}]
        hidden (str (h/html (public-cfp-view/cfp-session-fields
                              fields {} {} {} {:answered 0 :total 1} false)))
        restored (str (h/html (public-cfp-view/cfp-session-fields
                                fields {:answer-session-format "Workshop (120 min)"}
                                {} {} {:answered 1 :total 2} false)))]
    (testing "the dependent control is present once and reacts in both directions"
      (is (str/includes? hidden "Workshop prerequisites"))
      (is (str/includes? hidden "data-star-show=\"$cfpanswersessionformat ==="))
      (is (not (str/includes? hidden "style=\"display:none;\"")))
      (is (str/includes? hidden "data-server-visible=\"false\""))
      (is (str/includes? restored "Workshop prerequisites"))
      (is (not (str/includes? restored "style=\"display:none;\"")))
      (is (str/includes? restored "data-server-visible=\"true\"")))))

(deftest cfp-10-reviewer-provisioning-queues-usable-credentials
  (let [event (make-event!)
        handler (server/create-app)
        cookie (login-cookie handler "organizer@example.com")
        committee-id (:id (first (events/committees-for-event (:id event))))
        response (handler
                   (as cookie
                       (mock/request
                         :post
                         (str "/api/committees/" committee-id "/members/add")
                         {:name "Sam Whitfield"
                          :email "sam@example.com"
                          :role "reviewer"})))
        page (:body (handler (as cookie (mock/request :get
                                                      (get-in response [:headers "Location"])))))
        ;; 2026-08-17: reviewer credentials deliberately bypass approval via
        ;; issue-token!/send-now!, so read the completed outbox record rather
        ;; than the pending-only queue.
        invite (first (filter #(= "committee-invite" (:kind %))
                              (mail/outbox (:id event))))
        token-path (second (re-find #"value=\"http://localhost(/auth/[^\"]+)\"" page))]
    (testing "the organizer sees a delivered invite and a directly usable credential"
      (is (= 303 (:status response)))
      (is (= "sam@example.com" (:to invite)))
      (is (= :sent (:state invite)))
      (is (str/includes? (:body invite)
                         "%2Fevents%2Fcfp-eval%2Fboard%3Fassigned%3D1"))
      (is (str/includes? page "To sam@example.com"))
      (is (some? token-path)))
    (testing "the credential signs Sam in and lands on this event's review board"
      (let [signed-in (handler (mock/request :get token-path))
            reviewer-cookie (first (str/split
                                     (first (get-in signed-in [:headers "Set-Cookie"]))
                                     #";"))
            board (:body (handler
                           (as reviewer-cookie
                               (mock/request :get "/events/cfp-eval/board?assigned=1"))))]
        (is (= 303 (:status signed-in)))
        (is (= "/events/cfp-eval/board?assigned=1"
               (get-in signed-in [:headers "Location"])))
        (is (str/includes? board "Review Board"))
        (is (not (str/includes? board "Create / edit CFP form")))
        (is (not (str/includes? board ">Settings<")))))))

(deftest cfp-14-accept-and-decline-notifications-have-dispatch-receipts
  (let [event (make-event!)
        accepted (submission! event "Priya Raman" "priya@example.com" "Accepted talk")
        declined (submission! event "Alex Kim" "alex@example.com" "Declined talk")
        _ (reviews/set-status! (:id accepted) "Accepted" "organizer@example.com")
        _ (reviews/set-status! (:id declined) "Declined" "organizer@example.com")
        handler (server/create-app)
        cookie (login-cookie handler "organizer@example.com")]
    (doseq [[status email] [["Accepted" "priya@example.com"]
                            ["Declined" "alex@example.com"]]]
      (let [response (handler
                       (as cookie
                           (mock/request :post "/api/events/cfp-eval/inform-all"
                                         {:status status})))
            location (get-in response [:headers "Location"])
            page (:body (handler (as cookie (mock/request :get location))))]
        (testing (str status " has an explicit queued receipt and recipient")
          (is (= 303 (:status response)))
          (is (str/includes? location (str "notification-status=" status)))
          (is (str/includes? page (str status " notification queued for 1 speaker")))
          (is (str/includes? page email)))))
    (is (= #{"priya@example.com" "alex@example.com"}
           (->> (mail/queued (:id event))
                (filter #(= "decision" (:kind %)))
                (map :to)
                set)))))
