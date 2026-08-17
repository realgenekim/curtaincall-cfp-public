(ns cfp-scheduler-killer.judge-signin-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.demo :as demo]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.handlers.auth :as auth-handlers]
   [cfp-scheduler-killer.mail :as mail]
   [cfp-scheduler-killer.portal :as portal]
   [cfp-scheduler-killer.secrets :as secrets]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock])
  (:import
   (java.time LocalDateTime)))

(use-fixtures :each with-temp-store)

(defn- event! [slug name]
  (events/create-event!
    {:name name
     :slug slug
     :tz "America/Los_Angeles"
     :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
     :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
    "judge-signin-test"))

(defn- setup-aie! []
  (let [aie (event! "ai-engineer-code-summit" "AI Engineer Code Summit")
        eais (event! "enterprise-ai-summit-charlotte-2026" "Enterprise AI Summit")
        committee-id (:id (first (events/committees-for-event (:id aie))))
        eais-committee-id (:id (first (events/committees-for-event (:id eais))))]
    (committees/add-member! committee-id
                            {:name "swyx" :email "swyx@ai.engineer" :role "chair"}
                            "judge-signin-test")
    (committees/add-member! eais-committee-id
                            {:name "Gene Kim"
                             :email "genek@itrevolution.net"
                             :role "chair"}
                            "judge-signin-test")
    (committees/add-member! committee-id
                            {:name "Maya Lindholm"
                             :email "maya.lindholm@example.com"
                             :role "reviewer"}
                            "judge-signin-test")
    (submissions/create-submission!
      eais
      {:talk-title "Agents that remember" :abstract "A durable agent architecture."}
      {:name "Amara Devlin"
       :email "amara.devlin+472@beaconloop.example.com"
       :title "VP Engineering"
       :org "Beacon Loop"
       :bio "Amara builds durable AI systems."}
      "fixture"
      "judge-signin-test")
    {:aie aie :eais eais}))

(defn- response-cookie [response]
  (some-> (first (get-in response [:headers "Set-Cookie"]))
          (str/split #";")
          first))

(deftest persona-buttons-are-explicit-aie-only-and-gated-test
  (setup-aie!)
  (testing "the judge demo puts real sign-in first, then one organizer-first sandbox pane"
    (with-redefs [demo/personas? (constantly true)]
      (let [body (:body (auth-handlers/handle-login-page {:params {}}))]
        (is (< (.indexOf body "Just submitting a talk?")
               (.indexOf body "Judge Sandbox")))
        (is (not (str/includes? body "See the whole call")))
        (is (not (str/includes? body "Just exploring")))
        (is (not (str/includes? body "lm-sandbox-kicker")))
        (is (str/includes? body "<h2>Judge Sandbox</h2>"))
        (is (str/includes? body "Isolated data · no real email · resets automatically"))
        (is (str/includes? body "lm-persona lm-persona-organizer"))
        (doseq [label ["Organizer · swyx"
                       "Reviewer · Maya Lindholm"
                       "Speaker · Amara Devlin"]]
          (is (= 1 (count (re-seq (re-pattern label) body)))))
        (doseq [role ["organizer" "reviewer" "speaker"]]
          (is (= 1 (count (re-seq (re-pattern (str "/api/demo-login\\?role=" role)) body)))))
        (is (str/includes? body "lm-persona lm-persona-speaker"))
        (is (not (str/includes? body "lm-persona lm-persona-speaker disabled")))
        (is (not (str/includes? body "Coming next")))
        (is (not (str/includes? body "Ann Perry")))
        (is (not (str/includes? body "Alex Brodrick-Forster")))
        (do
          (is (not (str/includes? body "Organizer · Gene Kim")))
          (is (not (str/includes? body "genek@itrevolution.net")))))))
  (testing "a non-demo service does not render the buttons"
    (with-redefs [demo/personas? (constantly false)]
      (let [body (:body (auth-handlers/handle-login-page {:params {}}))]
        (is (not (str/includes? body "Judge Sandbox")))
        (is (not (str/includes? body "lm-sandbox")))
        (doseq [prefix ["Organizer ·" "Reviewer ·" "Speaker ·"]]
          (is (not (str/includes? body prefix))))))))

(deftest each-persona-signs-into-only-the-aie-fixture-test
  (let [{:keys [aie eais]} (setup-aie!)]
    (with-redefs [demo/personas? (constantly true)]
      (doseq [[role email home] [["organizer" "swyx@ai.engineer" "/welcome"]
                                 ["reviewer" "maya.lindholm@example.com" "/welcome"]]]
        (let [person (store/person-by-email email)
              response (auth-handlers/handle-demo-login {:params {:role role}})]
          (is (= 303 (:status response)))
          (is (= home (get-in response [:headers "Location"])))
          (is (= (:id person) (get-in response [:session :person-id])))
          (is (true? (get-in response [:session :demo?])))
          (is (= role (get-in response [:session :persona-role])))
          (is (= {:demo? true :persona-role role}
                 (select-keys (auth/current-person {:session (:session response)})
                              [:demo? :persona-role])))
          (is (= #{(:id aie)}
                 (set (map :id (events/events-for-person (:id person))))))))
      (testing "Amara enters the shipped speaker shell"
        (let [person (store/person-by-email "amara.devlin+472@beaconloop.example.com")
              response (auth-handlers/handle-demo-login {:params {:role "speaker"}})]
          (is (= 303 (:status response)))
          (is (= "/welcome" (get-in response [:headers "Location"])))
          (is (= (:id person) (get-in response [:session :person-id])))
          (is (= "speaker" (get-in response [:session :persona-role]))))))))

(deftest reviewer-event-capabilities-are-enforced-at-the-route-gate-test
  (let [{:keys [aie]} (setup-aie!)
        slug (:slug aie)]
    (with-redefs [demo/personas? (constantly true)]
      (let [app (server/create-app)
            login (app (mock/request :post "/api/demo-login?role=reviewer"))
            cookie (response-cookie login)
            request (fn [method path]
                      (app (mock/header (mock/request method path) "cookie" cookie)))
            before (submissions/cfp-state aie)]
        (is (= 303 (:status login)))
        (is (= 200 (:status (request :get (str "/events/" slug "/details")))))
        (is (= 200 (:status (request :get (str "/events/" slug "/committee")))))
        (is (= 200 (:status (request :get (str "/events/" slug "/board")))))
        (let [settings (request :get (str "/events/" slug "/settings"))]
          (is (= 403 (:status settings)))
          (is (str/includes? (:body settings) "Organizer or chair required"))
          (is (str/includes? (:body settings) "class=\"layout\"")))
        (let [board (:body (request :get (str "/events/" slug "/board")))]
          (doseq [chair-only ["Inform speakers" "not yet communicated"
                              ">Tell<" ">Announce<" "Export scores CSV"
                              "+ Add submission"]]
            (is (not (str/includes? board chair-only)) chair-only)))
        (is (= 403 (:status (request :post (str "/api/events/" slug "/cfp/close")))))
        (is (= before (submissions/cfp-state (events/event-by-slug slug))))))))

(deftest committee-created-reviewer-gets-link-and-failed-switch-keeps-session-test
  (let [{:keys [aie]} (setup-aie!)
        app (server/create-app)]
    (with-redefs [demo/personas? (constantly true)]
      (let [login (app (mock/request :post "/api/demo-login?role=organizer"))
            cookie (response-cookie login)
            request (fn [method path]
                      (app (mock/header (mock/request method path) "cookie" cookie)))
            committee-id (:id (first (events/committees-for-event (:id aie))))
            added (app (-> (mock/request :post
                                         (str "/api/committees/" committee-id "/members/add")
                                         {"name" "Fresh Reviewer"
                                          "email" "fresh-reviewer@example.com"
                                          "role" "reviewer"})
                           (mock/header "host" "review.test")
                           (mock/header "cookie" cookie)))
            committee-page (request :get (get-in added [:headers "Location"]))
            link (second (re-find #"value=\"(http://review\.test/auth/[^\"]+)\""
                                  (:body committee-page)))
            path (some-> link (str/replace "http://review.test" ""))
            reviewer-login (app (mock/request :get path))
            reviewer-cookie (response-cookie reviewer-login)]
        (is (= 303 (:status added)))
        (is (= 200 (:status committee-page)))
        (is (some? link) "the organizer can copy the new reviewer's one-time link")
        (is (= 303 (:status reviewer-login)))
        (is (= 200 (:status (app (mock/header
                                   (mock/request :get (str "/events/" (:slug aie) "/board"))
                                   "cookie" reviewer-cookie)))))
        (with-redefs [auth/dev? (constantly true)]
          (let [failed (app (-> (mock/request :post "/api/demo-login"
                                              {"email" "missing@example.com"})
                                (mock/header "referer" (str "/events/" (:slug aie) "/board"))
                                (mock/header "cookie" cookie)))]
            (is (= 303 (:status failed)))
            (is (str/includes? (get-in failed [:headers "Location"])
                               "You+are+still+signed+in"))
            (is (= 200 (:status (request :get (str "/events/" (:slug aie) "/settings")))))))))))

(deftest speaker-persona-shell-follows-event-relationships-test
  (let [{:keys [aie eais]} (setup-aie!)
        amara (store/person-by-email "amara.devlin+472@beaconloop.example.com")
        aie-committee-id (:id (first (events/committees-for-event (:id aie))))]
    (committees/add-member! aie-committee-id
                            {:name "Amara Devlin"
                             :email (:email amara)
                             :role "reviewer"}
                            "judge-signin-test")
    (with-redefs [demo/persona-enabled? (constantly true)]
      (let [app (server/create-app)
            login (app (mock/request :post "/api/demo-login?role=speaker"))
            cookie (response-cookie login)
            request (fn [method path]
                      (app (mock/header (mock/request method path) "cookie" cookie)))
            slug (:slug eais)
            welcome (:body (request :get "/welcome"))
            event-page (:body (request :get (str "/events/" slug)))
            events-page (:body (request :get "/events"))
            details (:body (request :get (str "/events/" slug "/details")))
            portal-page (:body (request :get "/portal"))]
        (testing "the fixed speaker door lands in the red-outline orientation room"
          (is (= 303 (:status login)))
          (is (= "/welcome" (get-in login [:headers "Location"])))
          (is (str/includes? welcome "wh-sandbox-orientation"))
          (is (str/includes? welcome "Hello, Amara"))
          (is (str/includes? welcome "Start with My participation → My proposal")))

        (testing "the relationship union includes speaking and reviewing events"
          (is (= #{(:id aie) (:id eais)}
                 (set (map :id (events/events-for-person (:id amara))))))
          (is (str/includes? event-page "AI Engineer Code Summit"))
          (is (str/includes? event-page "Enterprise AI Summit"))
          (is (not (str/includes? event-page "+ New event")))
          (is (str/includes? events-page "Every event you&apos;re part of"))
          (is (not (str/includes? events-page "archive the focused event"))))

        (testing "the speaker rail is their real participation and nothing administrative"
          (doseq [label ["Event overview" "Event details" "View" "Public CFP page"
                         "My participation" "My proposal" "Speaker profile"
                         "Onboarding tasks (0)" "The show" "My session"
                         "My schedule" "Public agenda"]]
            (is (str/includes? event-page label) label))
          (is (str/includes? event-page
                             (str "/portal#proposal-"
                                  (:id (portal/submission-for-event (:id amara) (:id eais))))))
          (is (str/includes? portal-page
                             (str "id=\"proposal-"
                                  (:id (portal/submission-for-event (:id amara) (:id eais)))
                                  "\"")))
          (is (str/includes? portal-page "id=\"speaker-profile\""))
          (doseq [hidden ["Committee (" "Review Board" "CFP Form" "Decide &amp; tell"
                          "Announce / Manage Speakers" "Create Speaker (Bypass CFP)" "Exports &amp; API"
                          (str "href=\"/events/" slug "/comms\"")
                          (str "href=\"/events/" slug "/log\"")
                          (str "href=\"/events/" slug "/settings\"")]]
            (is (not (str/includes? event-page hidden)) hidden)))

        (testing "direct URLs enforce the same read and mutation boundary"
          (is (= 200 (:status (request :get (str "/events/" slug)))))
          (is (= 200 (:status (request :get (str "/events/" slug "/details")))))
          (is (str/includes? details "view only"))
          (doseq [path [(str "/events/" slug "/committee")
                        (str "/events/" slug "/board")
                        (str "/events/" slug "/form")
                        (str "/events/" slug "/schedule")
                        (str "/events/" slug "/settings")]]
            (is (= 403 (:status (request :get path))) path))
          (is (= 403 (:status
                       (request :post (str "/api/events/" slug "/cfp/close")))))
          (is (= 403 (:status (request :post "/api/events/archive"))))
          (is (nil? (:archived-at (events/event-by-slug slug)))))))))

(deftest deployed-demo-cannot-use-the-dev-email-switcher-test
  (setup-aie!)
  (with-redefs [auth/dev? (constantly false)
                demo/personas? (constantly true)]
    (let [maya (store/person-by-email "maya.lindholm@example.com")
          response (auth-handlers/handle-demo-login
                     {:params {:role "reviewer"
                               :email "genek@itrevolution.net"}})]
      (is (= 303 (:status response)))
      (is (= (:id maya) (get-in response [:session :person-id])))))
  (testing "even an operator typo cannot allowlist a non-persona identity"
    (with-redefs [demo/personas? (constantly true)
                  demo/persona-emails (constantly #{"genek@itrevolution.net"})]
      (is (false? (demo/persona-email? "genek@itrevolution.net"))))))

;; INTENT-TEST: AUTH-001
(deftest magic-link-echo-requires-explicit-demo-mode-and-sbek-allowlist-test
  (let [mode-var #'auth-handlers/demo-mode-value
        cases [{:label "demo + allowlisted"
                :mode "true" :email "sbek-organizer@example.com" :echo? true}
               {:label "demo + allowlisted speaker"
                :mode "true" :email "sbek-speaker@example.com" :echo? true}
               {:label "demo + allowlisted second speaker"
                :mode "true" :email "sbek-speaker2@example.com" :echo? true}
               {:label "demo + normalized allowlisted"
                :mode "true" :email " SBEK-REVIEWER@EXAMPLE.COM " :echo? true}
               {:label "demo + non-allowlisted"
                :mode "true" :email "ordinary@example.com" :echo? false}
               {:label "non-demo + allowlisted"
                :mode "false" :email "sbek-organizer@example.com" :echo? false}
               {:label "non-demo + non-allowlisted"
                :mode "false" :email "ordinary@example.com" :echo? false}
               {:label "missing demo config + allowlisted"
                :mode nil :email "sbek-organizer@example.com" :echo? false}
               {:label "missing demo config + non-allowlisted"
                :mode nil :email "ordinary@example.com" :echo? false}
               {:label "unparseable demo config + allowlisted"
                :mode "definitely-not-a-boolean"
                :email "sbek-organizer@example.com" :echo? false}
               {:label "demo + missing persona"
                :mode "true" :email nil :echo? false}]]
    (doseq [{:keys [label mode email echo?]} cases]
      (testing label
        (with-redefs-fn
          {#'auth/dev? (constantly false)
           #'auth/issue-token! (constantly "matrix-token")
           #'mail/configured? (constantly false)
           mode-var (constantly mode)}
          #(let [body (:body (auth-handlers/handle-login
                               {:params {:email email}}))]
             (is (= echo? (str/includes? body "/auth/matrix-token")))))))
    (testing "local development retains its explicit inbox-free affordance"
      (with-redefs-fn
        {#'auth/dev? (constantly true)
         #'auth/issue-token! (constantly "dev-token")
         #'mail/configured? (constantly false)
         mode-var (constantly nil)}
        #(is (str/includes?
               (:body (auth-handlers/handle-login
                        {:params {:email "ordinary@example.com"}}))
               "/auth/dev-token"))))))

(deftest cookie-key-and-rebuilt-app-session-test
  (testing "Ring receives exactly sixteen raw bytes"
    (is (= 16 (alength (server/cookie-key-bytes "0123456789abcdef"))))
    (is (= :invalid-session-cookie-key
           (:type (ex-data (try
                             (server/cookie-key-bytes "too-short")
                             (catch clojure.lang.ExceptionInfo e e)))))))
  (testing "Cloud Run has no random-key fallback"
    (with-redefs [server/cloud-run-service? (constantly true)
                  secrets/load-secret (constantly nil)]
      (is (= :missing-session-cookie-key
             (:type (ex-data (try
                               (server/make-session-cookie-store)
                               (catch clojure.lang.ExceptionInfo e e))))))))
  (testing "a cookie minted by one app instance is accepted after rebuilding the handler"
    (setup-aie!)
    (let [first-app (server/create-app)
          token (auth/issue-token! "maya.lindholm@example.com")
          cookie (response-cookie (first-app (mock/request :get (str "/auth/" token))))
          rebuilt-app (server/create-app)
          response (rebuilt-app
                     (mock/header (mock/request :get "/events") "cookie" cookie))
          logout (rebuilt-app
                   (mock/header (mock/request :post "/logout") "cookie" cookie))
          replayed (rebuilt-app
                     (mock/header (mock/request :get "/events") "cookie" cookie))
          auth-facts (filter #(str/starts-with? (:type %) "auth.session-")
                             (:log (store/snapshot)))]
      (is (string? cookie))
      (is (= 200 (:status response)))
      (is (str/includes? (:body response) "AI Engineer Code Summit"))
      (is (= 303 (:status logout)))
      (is (= 302 (:status replayed)))
      (is (= ["auth.session-started" "auth.session-ended"]
             (mapv :type auth-facts))))))

(deftest judge-demo-submission-cap-can-override-the-event-setting-test
  (let [event {:settings {:submissions-per-person-cap 3}}
        demo-cap-var (ns-resolve 'cfp-scheduler-killer.submissions
                                 'demo-submission-cap)]
    (is (= 3 (submissions/submission-cap event)))
    (is (= 25
           (with-redefs-fn {demo-cap-var (constantly 25)}
             #(submissions/submission-cap event))))))

(deftest conference-time-travel-does-not-rewind-authentication-test
  (setup-aie!)
  (let [maya (store/person-by-email "maya.lindholm@example.com")
        session-id (auth/start-session! (:id maya))]
    (binding [store/*as-of-state* store/empty-state]
      (is (= (:id maya)
             (:id (auth/current-person
                    {:session {:person-id (:id maya)
                               :session-id session-id}})))))))
