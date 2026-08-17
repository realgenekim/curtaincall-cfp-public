(ns cfp-scheduler-killer.speakers-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.handlers.speakers :as speaker-handlers]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.speaker-csv :as csv]
   [cfp-scheduler-killer.speakers :as speakers]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [cfp-scheduler-killer.views.speakers :as view]
   [cfp-scheduler-killer.web.datastar :as datastar]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock]))

(use-fixtures :each with-temp-store)

(defn- add-event! [id slug]
  (store/append!
    {:type "event.created"
     :actor "test"
     :event-id id
     :payload {:id id :slug slug :name slug :created-at (store/now-iso)}}))

(defn- organizer-app []
  (let [handler (server/create-app)
        token (auth/issue-token! "organizer@example.com")
        response (handler (mock/request :get (str "/auth/" token)))
        cookie (first (str/split (first (get-in response [:headers "Set-Cookie"])) #";"))]
    (fn [request]
      (handler (mock/header request "cookie" cookie)))))

(defn- make-event! [handler slug]
  (handler
    (mock/request :post "/api/events/create"
                  {"name" "Speaker Operations Summit"
                   "slug" slug
                   "starts-on" "2026-10-14"
                   "ends-on" "2026-10-15"
                   "presenter-visibility-mode" "visible"}))
  (events/event-by-slug slug))

(defn- multipart-csv-request [path csv-text]
  (let [boundary "speaker-import-boundary"
        body (str "--" boundary "\r\n"
                  "Content-Disposition: form-data; name=\"csv-file\"; filename=\"speakers.csv\"\r\n"
                  "Content-Type: text/csv\r\n\r\n"
                  csv-text "\r\n--" boundary "--\r\n")]
    (-> (mock/request :post path)
        (mock/content-type (str "multipart/form-data; boundary=" boundary))
        (mock/body body))))

(deftest csv-import-is-one-identity-per-email-and-retry-idempotent
  (add-event! "event-a" "a")
  (let [parsed (csv/parse
                 (str "Name,Email,Company,Status\n"
                      "Priya Raghavan,priya@example.com,Acme Bank,Confirmed\n"
                      "Marcus Devlin,marcus@example.com,North Banc,Invited\n"))
        first-result (speakers/import! "event-a" parsed "organizer@example.com")
        second-result (speakers/import! "event-a" parsed "organizer@example.com")
        roster (speakers/roster-for-event "event-a")]
    (is (= 2 (:imported-count first-result)))
    (is (pos? (:facts-count first-result)))
    (is (:unchanged? second-result))
    (is (zero? (:facts-count second-result)))
    (is (= 2 (count roster)))
    (is (= 2 (count (:people (store/snapshot)))))
    (is (= ["Invited" "Confirmed"]
           (mapv :status roster)))
    (is (= "Acme Bank"
           (:organization (some #(when (= "Priya Raghavan" (:name %)) %) roster))))))

(deftest status-and-details-stay-inside-one-event
  (add-event! "event-a" "a")
  (add-event! "event-b" "b")
  (speakers/add! "event-a"
                 {:name "Priya Raghavan" :email "priya@example.com"
                  :status "Invited" :actor "organizer-a@example.com"})
  (let [person-id (:person-id (first (speakers/roster-for-event "event-a")))]
    (speakers/edit! "event-a" person-id
                    {:organization "Acme Bank"} "organizer-a@example.com")
    (speakers/change-status! "event-a" person-id "Confirmed"
                             "organizer-a@example.com")
    (speakers/add! "event-b"
                   {:name "Priya Raghavan" :email "PRIYA@example.com"
                    :status "Invited" :actor "organizer-b@example.com"})
    (let [a (first (speakers/roster-for-event "event-a"))
          b (first (speakers/roster-for-event "event-b"))]
      (is (= (:person-id a) (:person-id b)))
      (is (= "Confirmed" (:status a)))
      (is (= "Invited" (:status b)))
      (is (= "Acme Bank" (:organization a)))
      (is (nil? (:organization b))))))

(deftest roster-projects-speaker-managed-linkedin
  (add-event! "event-a" "a")
  (speakers/add! "event-a"
                 {:name "Priya Raghavan" :email "priya@example.com"
                  :status "Invited" :actor "organizer@example.com"})
  (let [person-id (:person-id (first (speakers/roster-for-event "event-a")))
        linkedin "https://www.linkedin.com/in/priya-projection-sentinel"]
    (store/append!
      {:type "person.profile-updated"
       :actor "priya@example.com"
       :payload {:person-id person-id
                 :changed ["linkedin-url"]
                 :changes {:linkedin-url linkedin}
                 :at (store/now-iso)}})
    (is (= linkedin
           (:linkedin-url (first (speakers/roster-for-event "event-a")))))))

(deftest event-local-edits-cannot-shadow-speaker-managed-profile
  (add-event! "event-a" "a")
  (speakers/add! "event-a"
                 {:name "Priya Raghavan" :email "priya@example.com"
                  :status "Invited" :actor "organizer@example.com"})
  (let [person-id (:person-id (first (speakers/roster-for-event "event-a")))]
    (store/append!
      {:type "person.profile-updated"
       :actor "priya@example.com"
       :payload {:person-id person-id
                 :changed ["bio" "headshot-url"]
                 :changes {:bio "SBEK-PORTAL-BIO-CANONICAL"
                           :headshot-url "https://images.example.test/priya.png"}
                 :at (store/now-iso)}})
    (store/append!
      {:type "speaker.details-updated"
       :actor "legacy-organizer@example.com"
       :event-id "event-a"
       :payload {:event-id "event-a"
                 :person-id person-id
                 :changes {:name "Legacy Organizer Shadow"
                           :bio "SBEK-LEGACY-SHADOW"
                           :headshot-url "https://images.example.test/legacy-shadow.png"}
                 :changed-at (store/now-iso)}})
    (speakers/edit! "event-a" person-id
                    {:name "Organizer Shadow"
                     :bio "SBEK-ORG-SHADOW"
                     :headshot-url "https://images.example.test/shadow.png"
                     :organization "Event-local organization"}
                    "organizer@example.com")
    (let [speaker (first (speakers/roster-for-event "event-a"))]
      (is (= "Priya Raghavan" (:name speaker)))
      (is (= "SBEK-PORTAL-BIO-CANONICAL" (:bio speaker)))
      (is (= "https://images.example.test/priya.png" (:headshot-url speaker)))
      (is (= "Event-local organization" (:organization speaker))))))

(deftest portal-invite-is-scoped-to-the-route-event
  (add-event! "event-a" "a")
  (add-event! "event-b" "b")
  (let [person-id (store/new-id)
        speaker {:person-id person-id :name "Dana Whitfield"
                 :email "dana@example.com" :talks ["Algebraic Programs"]}
        issued (atom [])
        roster-lookups (atom [])
        request-for (fn [slug]
                      {:path-params {:slug slug :person-id person-id}
                       :scheme :https :server-name "cfp.example.test" :server-port 443
                       :headers {"host" "cfp.example.test"}})]
    (with-redefs [auth/current-person (constantly {:email "ann@example.com"})
                  speakers/roster-for-event
                  (fn [event-id]
                    (swap! roster-lookups conj event-id)
                    (if (= "event-a" event-id) [speaker] []))
                  auth/issue-token!
                  (fn [email options]
                    (swap! issued conj [email options])
                    "one-time-token")]
      (is (= 303 (:status (speaker-handlers/handle-portal-invite (request-for "a")))))
      (is (= 404 (:status (speaker-handlers/handle-portal-invite (request-for "b")))))
      (is (= ["event-a" "event-b"] @roster-lookups))
      (is (= 1 (count @issued))
          "the same person ID cannot be invited through another event")
      (let [[email {:keys [letter-fn context]}] (first @issued)
            letter (letter-fn "token-123" nil)]
        (is (= "dana@example.com" email))
        (is (= {:event-id "event-a" :kind "portal-invite"
                :actor "ann@example.com" :person-id person-id}
               context))
        (is (= "ann@example.com" (:from letter)))
        (is (= "ann@example.com" (:reply-to letter)))
        (is (str/includes? (:body letter)
                           "https://cfp.example.test/auth/token-123"))))))

(deftest invalid-csv-never-appends
  (add-event! "event-a" "a")
  (let [before (count (:log (store/snapshot)))
        result (speakers/import! "event-a"
                                 (csv/parse "Name,Email\nNope,not-an-email\n")
                                 "organizer@example.com")]
    (is (= :invalid-csv (get-in result [:rejected :reason])))
    (is (= before (count (:log (store/snapshot)))))))

(deftest directly-invited-speaker-can-receive-profile-portal-invite
  (add-event! "event-a" "a")
  (let [person-id (store/new-id)
        issued (atom nil)
        request {:path-params {:slug "a" :person-id person-id}
                 :scheme :https :server-name "cfp.example.test" :server-port 443
                 :headers {"host" "cfp.example.test"}}]
    (with-redefs [auth/current-person (constantly {:email "ann@example.com"})
                  speakers/roster-for-event
                  (constantly [{:person-id person-id :name "Keynote Kim"
                                :email "keynote@example.com" :talks []}])
                  auth/issue-token!
                  (fn [email options]
                    (reset! issued [email options])
                    "one-time-token")]
      (is (= 303 (:status (speaker-handlers/handle-portal-invite request))))
      (let [[email {:keys [letter-fn]}] @issued
            letter (letter-fn "token-123" nil)]
        (is (= "keynote@example.com" email))
        (is (str/includes? (:body letter) "invited to speak at a"))
        (is (str/includes? (:body letter) "set up your speaker profile"))
        (is (not (str/includes? (:body letter) "review your talks")))))))

(deftest roster-page-renders-the-literal-judge-and-operator-controls
  (let [event {:id "event-a" :slug "summit" :name "Summit"}
        html (view/speakers-page
               event
               {:person nil
                :speakers [{:person-id "p-1" :name "Priya" :email "p@example.com"
                            :status "Invited" :talks [] :profile-complete? true
                            :title "VP Engineering"
                            :event-organization "Acme Bank"
                            :profile-organization "Shared Organization"
                            :bio "SBEK-PORTAL-BIO-01"
                            :headshot-url "/headshots/profile-file-1"
                            :linkedin-url "https://www.linkedin.com/in/priya-sentinel"
                            :lifecycle
                            {:status "Confirmed"
                             :pending-tasks
                             [{:submission-id "submission-1"
                               :key "profile"
                               :label "Complete bio and profile"
                               :due-on "2026-09-01"
                               :last-chased-at "2026-08-12T10:00:00Z"
                               :chase-count 1}]
                             :history
                             [{:type "submission.created"
                               :talk-title "AI with receipts"
                               :actor "priya@example.com"
                               :at "2026-08-10T10:00:00Z"}
                              {:type "email.sent"
                               :label "Complete bio and profile"
                               :actor "organizer@example.com"
                               :at "2026-08-12T10:00:00Z"}]}}
                           {:person-id "p-2" :name "Dana" :email "d@example.com"
                            :status "Confirmed" :talks ["Algebraic Programs"]
                            :profile-complete? true
                            :lifecycle {:status "Confirmed"
                                        :pending-tasks []
                                        :history []}}]})]
    (is (str/includes? html "Speakers"))
    (is (str/includes? html "Invited"))
    (is (str/includes? html "Confirmed"))
    (is (not (str/includes? html "Add speaker")))
    (is (str/includes? html "Import speakers.csv"))
    (is (str/includes? html "Preview first. Nothing is written until you confirm."))
    (is (str/includes? html "src=\"/headshots/profile-file-1\""))
    (is (str/includes? html "alt=\"Priya headshot\""))
    (is (str/includes? html "class=\"board-table ledger-table speaker-ledger-table\""))
    (is (str/includes? html "id=\"speaker-roster-results\""))
    (is (str/includes? html "data-star-bind:speakerq=\"\""))
    (is (str/includes? html "data-star-bind:speakerstatus=\"\""))
    (is (not (str/includes? html "data-star-bind:speakerprofile=\"\"")))
    (is (str/includes? html "data-star-on:input__debounce.50ms"))
    (is (str/includes? html "/events/summit/speakers/filter"))
    (is (not (str/includes? html ">Search</button>")))
    (is (str/includes? html "class=\"lg-person-link\""))
    (is (str/includes? html "VP Engineering"))
    (is (str/includes? html "Acme Bank"))
    (is (str/includes? html "Edit Speaker Details"))
    (is (not (str/includes? html "name=\"profile-edit\"")))
    (is (str/includes? html "1 pending task"))
    (is (str/includes? html "Complete bio and profile"))
    (is (str/includes? html "Submission received: AI with receipts"))
    (is (str/includes? html "Follow-up delivered: Complete bio and profile"))
    (is (not (str/includes? html "Sign speaker release"))
        "completed work stays in history but not in the pending-task list")
    (is (= 2 (count (re-seq #"Send portal invite" html)))
        "directly invited and CFP speakers can both receive portal access")))

(deftest live-roster-filter-composes-search-and-dropdown-signals
  (add-event! "event-filter" "filter-summit")
  (speakers/add! "event-filter"
                 {:name "Ada Lovelace"
                  :email "ada@example.com"
                  :status "Confirmed"
                  :actor "organizer@example.com"})
  (speakers/add! "event-filter"
                 {:name "Grace Hopper"
                  :email "grace@example.com"
                  :status "Invited"
                  :actor "organizer@example.com"})
  (with-redefs [datastar/signals
                (constantly {:speakerq "Ada"
                             :speakerstatus "Confirmed"
                             :speakerprofile ""
                             :speakerworkflow "roster"})
                datastar/sse-fragment-response
                (fn [_req selector html]
                  {:status 200 :selector selector :body html})]
    (let [response (speaker-handlers/handle-speakers-filter
                     {:path-params {:slug "filter-summit"}})]
      (is (= 200 (:status response)))
      (is (= "#speaker-roster-results" (:selector response)))
      (is (str/includes? (:body response) "Ada Lovelace"))
      (is (not (str/includes? (:body response) "Grace Hopper")))
      (is (str/includes? (:body response) "id=\"speaker-roster-results\"")))))

(deftest organizer-speaker-routes-round-trip
  (let [handler (organizer-app)
        event (make-event! handler "speaker-ops")]
    (testing "empty roster is an actual organizer page"
      (let [response (handler (mock/request :get "/events/speaker-ops/speakers"))]
        (is (= 200 (:status response)))
        (is (str/includes? (:body response) "No speakers match this view."))))
    (testing "manual add, status, and event-local edit"
      (let [bad (handler
                  (mock/request :post "/api/events/speaker-ops/speakers"
                                {"name" "Priya Raghavan"
                                 "email" "not-an-email"
                                 "status" "Invited"}))]
        (is (= 422 (:status bad)))
        (is (str/includes? (:body bad) "Enter a complete speaker email address."))
        (is (not (str/includes? (:body bad) "id=\"add-speaker\"")))
        (is (empty? (speakers/roster-for-event (:id event)))))
      (is (= 303 (:status
                   (handler
                     (mock/request :post "/api/events/speaker-ops/speakers"
                                   {"name" "Priya Raghavan"
                                    "email" "priya@example.com"
                                    "status" "Invited"})))))
      (let [person-id (:person-id (first (speakers/roster-for-event (:id event))))]
        (is (= 303 (:status
                     (handler
                       (mock/request :post
                                     (str "/api/events/speaker-ops/speakers/" person-id "/status")
                                     {"status" "Confirmed"})))))
        (testing "Add Speaker cannot reset an existing participant's status"
          (let [before (count (:log (store/snapshot)))
                response (handler
                           (mock/request :post "/api/events/speaker-ops/speakers"
                                         {"name" "Different spelling"
                                          "email" "  PRIYA@EXAMPLE.COM "
                                          "status" "Invited"}))]
            (is (= 303 (:status response)))
            (is (= (str "/events/speaker-ops/speakers#speaker-" person-id)
                   (get-in response [:headers "Location"])))
            (is (= "Confirmed"
                   (:status (first (speakers/roster-for-event (:id event))))))
            (is (= before (count (:log (store/snapshot)))))))
        (testing "an invalid status stays on the filtered row with its real error"
          (let [before (count (:log (store/snapshot)))
                response
                (handler
                  (mock/request :post
                                (str "/api/events/speaker-ops/speakers/" person-id "/status")
                                {"status" "Nope"
                                 "return-q" "Priya Raghavan"
                                 "return-status" "Confirmed"}))]
            (is (= 422 (:status response)))
            (is (str/includes? (:body response) "Choose a known speaker status."))
            (is (str/includes? (:body response) "role=\"alert\""))
            (is (str/includes? (:body response) (str "id=\"speaker-" person-id "\"")))
            (is (str/includes? (:body response) "value=\"Priya Raghavan\""))
            (is (re-find #"class=\"chip on\"[^>]*>Confirmed</a>"
                         (:body response)))
            (is (= before (count (:log (store/snapshot)))))))
        (let [filtered-path "/events/speaker-ops/speakers?q=Priya+Raghavan&status=Confirmed"
              filtered (:body (handler (mock/request :get filtered-path)))
              edit-response
              (handler
                (mock/request :post
                              (str "/api/events/speaker-ops/speakers/" person-id)
                              {"title" "VP Engineering"
                               "organization" "Acme Bank"
                               "name" "Crafted Organizer Shadow"
                               "bio" "SBEK-CRAFTED-SHADOW"
                               "headshot-url" "https://images.example.test/crafted-shadow.png"
                               "return-q" "Priya Raghavan"
                               "return-status" "Confirmed"}))]
          (is (str/includes? filtered (str "id=\"speaker-" person-id "\"")))
          (is (str/includes? filtered
                             "name=\"return-q\" type=\"hidden\" value=\"Priya Raghavan\""))
          (is (= 303 (:status edit-response)))
          (is (= (str "/events/speaker-ops/speakers/" person-id)
                 (get-in edit-response [:headers "Location"]))))
        (let [body (:body (handler (mock/request :get "/events/speaker-ops/speakers")))]
          (is (str/includes? body "Confirmed"))
          (is (str/includes? body "Acme Bank"))
          (is (str/includes? body "Priya Raghavan"))
          (is (not (str/includes? body "Crafted Organizer Shadow")))
          (is (not (str/includes? body "SBEK-CRAFTED-SHADOW")))
          (is (not (str/includes? body "crafted-shadow.png"))))))
    (testing "CSV preview is read-only, confirmation imports"
      (let [csv-text "Name,Email,Company,Status\nMarcus Devlin,marcus@example.com,North Banc,Invited\n"
            before (count (:log (store/snapshot)))
            preview (handler
                      (multipart-csv-request
                        "/api/events/speaker-ops/speakers/import/preview"
                        csv-text))]
        (is (= 200 (:status preview)))
        (is (str/includes? (:body preview) "Import preview"))
        (is (str/includes? (:body preview) "Marcus Devlin"))
        (is (= before (count (:log (store/snapshot)))))
        (is (= 303 (:status
                     (handler
                       (mock/request :post "/api/events/speaker-ops/speakers/import"
                                     {"csv-text" csv-text})))))
        (is (= 2 (count (speakers/roster-for-event (:id event)))))))))
