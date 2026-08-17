(ns cfp-scheduler-killer.server-test
  "Route-level tests: they exercise the real ring handler, so a router that
   won't even build (e.g. /events/new conflicting with /events/:slug) fails
   here instead of at server start."
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.handlers.public-cfp :as public-cfp]
   [cfp-scheduler-killer.mail :as mail]
   [cfp-scheduler-killer.review-plan :as review-plan]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [reitit.ring :as ring]
   [ring.mock.request :as mock]))

;; Each test gets its own temp log — no shared database, nothing to clean up.
(use-fixtures :each with-temp-store)

(defn- raw-app [] (server/create-app))

(defn- organizer-session
  "Sign in as the first-run organizer and return the session cookie.

   On an empty store this goes through auth/bootstrap-mode? — which is exactly
   how a real fresh install is used: the first person through the door becomes
   the first organizer, because otherwise nobody could ever create event #1."
  [handler]
  (let [token (auth/issue-token! "organizer@example.com")
        resp (handler (mock/request :get (str "/auth/" token)))]
    (first (str/split (first (get-in resp [:headers "Set-Cookie"])) #";"))))

(defn- app
  "A handler that carries an organizer session on every request, so these tests
   read as they did before the login gate landed. Anonymous behaviour is tested
   in board-test, which is where the gate belongs."
  []
  (let [raw (raw-app)
        cookie (organizer-session raw)]
    (fn [req] (raw (mock/header req "cookie" cookie)))))

(deftest routes-build-and-resolve-test
  (let [handler (app)]
    (testing "/ is the landing page for EVERYONE (Gene's second ruling) —
              signed-in people keep their homepage, with a door to /events"
      (let [resp (handler (mock/request :get "/"))]
        (is (= 200 (:status resp)))
        (is (str/includes? (:body resp) "Curtain Call"))
        (is (str/includes? (:body resp) "Your events"))))

    (testing "/ preserves the tool-archaeology story for every stranger"
      (let [resp ((raw-app) (mock/request :get "/"))
            body (:body resp)]
        (is (= 200 (:status resp)))
        (doseq [literal ["Curtain Call"
                         "HISTORY.LOG"
                         "Fifteen years, five tools, one lesson"
                         "eventpower.adopted"
                         "cvent.adopted"
                         "busyconf.adopted"
                         "sessionize.adopted"
                         "trello, zapier, the-Sheet"
                         "demo controls create clearly labeled simulations"
                         "Sign in"]]
          (is (str/includes? body literal)
              (str "the public homepage lost its " literal " story")))
        (is (not (str/includes? body "has no demo controls"))
            "the homepage must not contradict the live create-demo control")))

    (testing "GET /events renders"
      (let [resp (handler (mock/request :get "/events"))]
        (is (= 200 (:status resp)))
        (is (str/includes? (:body resp) "Create event"))))

    (testing "GET /events/new wins over /events/:slug"
      (let [resp (handler (mock/request :get "/events/new"))]
        (is (= 200 (:status resp)))
        (is (str/includes? (:body resp) "Speaker support email"))
        (is (str/includes? (:body resp) "Create demo event"))))

    (testing "the create form offers location + website and the timezone hint"
      (let [body (:body (handler (mock/request :get "/events/new")))]
        (is (str/includes? body "name=\"location\""))
        (is (str/includes? body "Charlotte, NC"))
        (is (str/includes? body "name=\"website-url\""))
        (is (str/includes? body "https://events.itrevolution.com/2026-charlotte/"))
        (is (str/includes? body "Daylight savings applies automatically."))))

    (testing "the marquee is on the page, ghosted, with a live SSE hookup"
      (let [body (:body (handler (mock/request :get "/events/new")))]
        (is (str/includes? body "id=\"event-marquee\""))
        (is (str/includes? body "Your event…") "the empty state is a ghost, not a blank")
        (is (str/includes? body "your-event-name…")
            "and the URL line ghosts too rather than deriving /cfp/2026")
        (is (str/includes? body "/api/sse?event-id=new-event")
            "without this hookup every preview push goes nowhere")
        (is (str/includes? body "@post(&apos;/api/events/preview&apos;)"))
        (is (str/includes? body "__debounce.300ms"))
        (is (str/includes? body "name=\"draft-token\""))
        (is (str/includes? body "data-star-bind:evdraft"))))

    (testing "an unknown slug 404s honestly"
      (is (= 404 (:status (handler (mock/request :get "/events/no-such-event-xyz"))))))

    (testing "the public CFP route is a declared stub, not a routing hole"
      (is (= 404 (:status (handler (mock/request :get "/cfp/no-such-event-xyz"))))))))

(deftest ping-health-contract-test
  (let [anonymous (raw-app)
        before (count (:log (store/snapshot)))
        ping (anonymous (mock/request :get "/ping"))]
    (testing "ping is exact-public, lightweight, and mutation-free"
      (is (= 200 (:status ping)))
      (is (= "pong" (:body ping)))
      (is (= "text/plain; charset=utf-8"
             (get-in ping [:headers "Content-Type"])))
      (is (= before (count (:log (store/snapshot))))))

    (testing "a neighboring unknown path is still unknown"
      (is (= 404 (:status ((app) (mock/request :get "/ping-neighbor"))))))

    (testing "opening ping does not open protected routes"
      (is (= 302 (:status (anonymous (mock/request :get "/events"))))))))

(deftest unknown-get-family-is-a-site-shell-404-test
  ;; 2lur + 3mg8 + p5ft: this is a family contract, not a three-URL patch.
  ;; UUID suffixes guarantee that every run probes paths the route table has
  ;; never seen, across both root and event-shaped namespaces.
  (let [anonymous (raw-app)
        organizer (app)
        known-slug "known-404-contract-event"
        _ (organizer (mock/request :post "/api/events/create"
                                   {"name" "Known 404 Contract Event"
                                    "slug" known-slug
                                    "tz" "America/New_York"
                                    "presenter-visibility-mode" "visible"}))
        random-paths (vec
                      (mapcat
                       (fn [_]
                         (let [suffix (str (java.util.UUID/randomUUID))]
                           [(str "/definitely-not-a-route-" suffix)
                            (str "/events/missing-event-" suffix
                                 "/definitely-not-a-route")
                            (str "/cfp/missing-event-" suffix
                                 "/definitely-not-a-route")]))
                       (range 12)))
        probes (concat (map (fn [path] [:anonymous anonymous path]) random-paths)
                       (map (fn [path] [:organizer organizer path]) random-paths)
                       [[:anonymous anonymous "/events/archive"]
                        [:organizer organizer "/events/archive"]
                        [:anonymous anonymous
                         (str "/events/" known-slug "/definitely-not-a-route")]])]
    (doseq [[viewer handler path] probes]
      (let [response (handler (mock/request :get path))
            content-type (get-in response [:headers "Content-Type"] "")]
        (testing (str (name viewer) " GET " path)
          (is (= 404 (:status response)))
          (is (str/starts-with? content-type "text/html"))
          (is (not= "application/octet-stream" content-type))
          (is (str/includes? (:body response) "<!DOCTYPE html>"))
          (is (or (str/includes? (:body response) "Not on the program")
                  (str/includes? (:body response) "No such event")))
          (is (str/includes? (:body response) "/css/app.css")))))

    (testing "known pages and static resources retain their response types"
      (doseq [[path expected-type] [["/" "text/html"]
                                    ["/css/app.css" "text/css"]
                                    ["/js/datastar-kit.js" "text/javascript"]]]
        (let [response (anonymous (mock/request :get path))]
          (is (= 200 (:status response)) path)
          (is (str/starts-with? (get-in response [:headers "Content-Type"] "")
                                expected-type)
              path))))))

(deftest unknown-event-resource-paths-never-fall-through-to-static-files-test
  (let [static-lookups (atom [])
        static-handler (fn [req]
                         (swap! static-lookups conj (:uri req))
                         {:status 200
                          :headers {"Content-Type" "application/octet-stream"
                                    "Content-Disposition" "attachment"}
                          :body "classpath collision"})]
    (with-redefs [ring/create-resource-handler (constantly static-handler)]
      (let [handler (raw-app)
            unknown-paths ["/events/known/speakers/not-a-speaker"
                           "/events/known/files/not-a-file"
                           "/events/known/deliverables/not-a-task"]]
        (doseq [path unknown-paths]
          (let [response (handler (mock/request :get path))]
            (testing path
              (is (= 404 (:status response)))
              (is (str/starts-with? (get-in response [:headers "Content-Type"] "")
                                    "text/html"))
              (is (nil? (get-in response [:headers "Content-Disposition"])))
              (is (or (str/includes? (:body response) "Not on the program")
                      (str/includes? (:body response) "No such event"))))))
        (is (empty? @static-lookups)
            "dynamic-looking route misses must never be probed as classpath files")
        (is (= 200 (:status (handler (mock/request :get "/css/app.css"))))
            "known static namespaces still use the resource handler")
        (is (= ["/css/app.css"] @static-lookups))))))

(deftest create-event-round-trip-test
  (let [slug (str "route-test-" (events/random-suffix 10))
        handler (app)]
    (do
      (testing "the choice point states our preference but preselects neither policy"
        (let [body (:body (handler (mock/request :get "/events/new")))
              policy-inputs (re-seq #"<input[^>]*name=\"presenter-visibility-mode\"[^>]*>"
                                    body)]
          (is (= 2 (count policy-inputs)))
          (is (not-any? #(str/includes? % "checked") policy-inputs))
          (is (str/includes? body "We prefer non-blind review"))
          (is (str/includes? (str/lower-case body) "evaluators and ai agents"))
          (is (str/includes? body "href=\"/manifesto\""))))

      (testing "omitting the policy cannot create an event or expose identities"
        (let [missing-slug (str "missing-policy-" (events/random-suffix 10))
              resp (handler (mock/request :post "/api/events/create"
                                          {"name" "Missing Policy Summit"
                                           "slug" missing-slug
                                           "tz" "America/New_York"}))]
          (is (= 422 (:status resp)))
          (is (str/includes? (:body resp) "Choose blind or visible review"))
          (is (nil? (events/event-by-slug missing-slug)))))

      (testing "a valid POST creates the event and 303s to organizer Mission Control"
        (let [resp (handler (mock/request :post "/api/events/create"
                                          {"name" "Route Test Summit"
                                           "slug" slug
                                           "tz" "America/New_York"
                                           "starts-on" "2026-10-14"
                                           "ends-on" "2026-10-15"
                                           "cfp-opens-at" "2026-08-10T00:00"
                                           "cfp-closes-at" "2026-09-15T23:59"
                                           "presenter-visibility-mode" "visible"
                                           "support-email" "support@example.com"
                                           "location" "Charlotte, NC"
                                           "website-url" "https://itrevolution.com"}))]
          (is (= 303 (:status resp)))
          (is (= (str "/events/" slug) (get-in resp [:headers "Location"])))
          (is (= {:mode "visible" :version 0}
                 (select-keys
                  (review-plan/presenter-visibility-policy
                   (:id (events/event-by-slug slug)))
                  [:mode :version])))))

      (testing "the dashboard renders the event's facts"
        (let [resp (handler (mock/request :get (str "/events/" slug)))
              body (:body resp)]
          (is (= 200 (:status resp)))
          (is (str/includes? body "Route Test Summit"))
          (is (str/includes? body (str "/cfp/" slug)))
          (is (str/includes? body "Charlotte, NC"))))

      (testing "the details page renders every fact supplied at creation"
        (let [body (:body (handler (mock/request :get (str "/events/" slug "/details"))))]
          (is (str/includes? body "support@example.com"))
          (is (str/includes? body "https://itrevolution.com"))))

      (testing "event details persist a validated programming-day window"
        (let [path (str "/api/events/" slug "/details")
              params {"name" "Route Test Summit"
                      "starts-on" "2026-10-14" "ends-on" "2026-10-15"
                      "tz" "America/New_York" "day-start" "08:15" "day-end" "19:45"
                      "support-email" "support@example.com"
                      "location" "Charlotte, NC"
                      "website-url" "https://itrevolution.com"}
              response (handler (mock/request :post path params))
              saved (events/event-by-slug slug)
              page (:body (handler (mock/request :get (str "/events/" slug "/details"))))]
          (is (= 303 (:status response)))
          (is (= {:day-start "08:15" :day-end "19:45"} (events/day-hours saved)))
          (is (re-find #"name=\"day-start\"[^>]*value=\"08:15\"" page))
          (is (re-find #"name=\"day-end\"[^>]*value=\"19:45\"" page))
          (doseq [[start end message] [["9:00" "17:00" "Enter a start time as HH:mm."]
                                       ["09:00" "" "Enter an end time as HH:mm."]
                                       ["18:00" "09:00" "must end after it starts"]
                                       ["09:00" "09:00" "must end after it starts"]]]
            (let [before (count (store/log-for-event (:id saved)))
                  invalid (handler (mock/request :post path
                                                 (assoc params "day-start" start "day-end" end)))]
              (is (= 422 (:status invalid)))
              (is (str/includes? (:body invalid) message))
              (is (= before (count (store/log-for-event (:id saved)))))))
          (is (= {:day-start "08:15" :day-end "19:45"}
                 (events/day-hours (events/event-by-slug slug))))))

      (testing "the creator joins the committee"
        (let [body (:body (handler (mock/request :get (str "/events/" slug "/committee"))))]
          (is (str/includes? body "Program Committee · 1 member"))
          (is (str/includes? body "organizer@example.com"))
          (is (str/includes? body ">chair<"))))

      (testing "the new event appears on the list page"
        (is (str/includes? (:body (handler (mock/request :get "/events")))
                           "Route Test Summit")))

      (testing "re-POSTing the same slug re-renders the form with an error, not a 500"
        (let [resp (handler (mock/request :post "/api/events/create"
                                          {"name" "Route Test Summit"
                                           "slug" slug
                                           "tz" "America/New_York"
                                           "presenter-visibility-mode" "visible"}))]
          (is (= 422 (:status resp)))
          (is (str/includes? (:body resp) "That URL is taken by"))
          (is (str/includes? (:body resp) "Route Test Summit")
              "the refusal names the event holding the address"))))))

(deftest invalid-post-rerenders-form-test
  (let [handler (app)
        resp (handler (mock/request :post "/api/events/create"
                                    {"name" ""
                                     "slug" "Not A Slug"
                                     "tz" "America/New_York"
                                     "presenter-visibility-mode" "visible"
                                     "website-url" "itrevolution.com"}))]
    (testing "errors come back as a server-rendered form, filled in"
      (is (= 422 (:status resp)))
      ;; hiccup escapes apostrophes, so match on an apostrophe-free phrase
      (is (str/includes? (:body resp) "Fix the fields marked below"))
      (is (str/includes? (:body resp) "Event name is required."))
      (is (str/includes? (:body resp) "lowercase letters, numbers and hyphens only"))
      (is (str/includes? (:body resp) "Enter a full URL starting with"))
      ;; what the organizer typed comes back, so nothing is retyped
      (is (str/includes? (:body resp) "value=\"Not A Slug\"")))))

(deftest create-recovers-only-the-current-preview-draft-test
  (let [handler (app)
        first-page (:body (handler (mock/request :get "/events/new")))
        draft-token (second (re-find #"name=\"draft-token\"[^>]*value=\"([^\"]+)\""
                                     first-page))
        slug (str "draft-recovery-" (events/random-suffix 10))
        preview-request (fn [token name]
                          (-> (mock/request :post "/api/events/preview")
                              (mock/content-type "application/json")
                              (mock/body (str "{\"evdraft\":\"" token
                                              "\",\"evname\":\"" name "\"}"))))]
    (is (seq draft-token))

    (testing "a matching server preview rescues a native field lost at submit"
      (is (= 204 (:status (handler (preview-request draft-token
                                                    "Recovered Preview Summit")))))
      (let [resp (handler (mock/request :post "/api/events/create"
                                        {"draft-token" draft-token
                                         "name" ""
                                         "slug" slug
                                         "tz" "America/New_York"
                                         "presenter-visibility-mode" "visible"
                                         "starts-on" "2027-05-12"
                                         "ends-on" "2027-05-14"}))]
        (is (= 303 (:status resp)))
        (is (= (str "/events/" slug)
               (get-in resp [:headers "Location"])))
        (is (= "Recovered Preview Summit"
               (:name (events/event-by-slug slug))))))

    (testing "a late preview from the completed page cannot seed the next form"
      (is (= 204 (:status (handler (preview-request draft-token
                                                    "Stale Completed Summit")))))
      (let [next-page (:body (handler (mock/request :get "/events/new")))
            next-token (second (re-find #"name=\"draft-token\"[^>]*value=\"([^\"]+)\""
                                        next-page))
            next-name (second (re-find #"name=\"name\"[^>]*value=\"([^\"]*)\""
                                       next-page))]
        (is (not= draft-token next-token))
        (is (= "" next-name))))))

(deftest committee-roster-routes-test
  (let [handler (app)
        slug (str "roster-test-" (events/random-suffix 10))
        email (str "roster-" (events/random-suffix 10) "@example.com")]
    (do
      (handler (mock/request :post "/api/events/create"
                             {"name" "Roster Test Summit" "slug" slug
                              "tz" "America/New_York"
                              "presenter-visibility-mode" "visible"}))
      (let [event (events/event-by-slug slug)
            committee-id (:id (first (events/committees-for-event (:id event))))
            add-url (str "/api/committees/" committee-id "/members/add")]

        (testing "the creator is already on the roster as chair"
          ;; Whoever creates an event joins its committee — true of every real
          ;; conference, and it's what stops a first-run organizer locking
          ;; themselves out when the bootstrap window closes.
          (let [body (:body (handler (mock/request :get (str "/events/" slug "/committee"))))]
            (is (str/includes? body "Program Committee · 1 member"))
            (is (str/includes? body "organizer@example.com"))
            (is (str/includes? body ">chair<"))))

        (testing "adding a member 303s back to the committee page"
          (let [resp (handler (mock/request :post add-url
                                            {"name" "Ann Perry" "email" email "role" "chair"}))]
            (is (= 303 (:status resp)))
            (is (= (str "/events/" slug "/committee")
                   (get-in resp [:headers "Location"])))))

        (testing "the roster now renders the member, the count, and the chair badge"
          (let [body (:body (handler (mock/request :get (str "/events/" slug "/committee"))))]
            (is (str/includes? body "Program Committee · 2 members"))
            (is (str/includes? body "Ann Perry"))
            (is (str/includes? body email))
            (is (str/includes? body ">chair<"))))

        (testing "a bad email re-renders the committee page with the error, not a 500"
          (let [resp (handler (mock/request :post add-url {"name" "Bob" "email" "nope"}))]
            (is (= 422 (:status resp)))
            (is (str/includes? (:body resp) "That doesn&apos;t look like an email address."))
            ;; still shows the existing roster underneath
            (is (str/includes? (:body resp) "Ann Perry"))))

        (testing "adding the same email twice is a message, not a crash"
          (let [resp (handler (mock/request :post add-url
                                            {"name" "Ann Perry" "email" email}))]
            (is (= 422 (:status resp)))
            (is (str/includes? (:body resp) "already on this committee"))))

        (testing "each roster row links to the person page"
          (let [person-id (:person-id (first (filter #(= email (:email %))
                                                     (committees/members-for-committee committee-id))))
                body (:body (handler (mock/request :get (str "/events/" slug "/committee"))))]
            (is (str/includes? body (str "/events/" slug "/people/" person-id)))
            (is (str/includes? body ">Open</a>"))

            (testing "and that page renders the person in this event's context"
              (let [resp (handler (mock/request :get (str "/events/" slug "/people/" person-id)))
                    pbody (:body resp)]
                (is (= 200 (:status resp)))
                (is (str/includes? pbody "Ann Perry"))
                (is (str/includes? pbody email))
                (is (str/includes? pbody "Program Committee"))
                (is (str/includes? pbody "Their reviews"))
                (is (str/includes? pbody "Their comments"))
                ;; A member who has rated nothing gets an honest empty state,
                ;; not a placeholder promising a future slice.
                (is (str/includes? pbody "haven&apos;t rated anything on this event yet"))
                (is (str/includes? pbody (str "/events/" slug))
                    "there is a way back to the event")))))

        (testing "an unknown person id 404s instead of rendering a blank page"
          (is (= 404 (:status (handler (mock/request
                                        :get (str "/events/" slug "/people/"
                                                  (store/new-id))))))))

        (testing "the person page still wins over nothing when the event is unknown"
          (is (= 404 (:status (handler (mock/request
                                        :get (str "/events/nope/people/" (store/new-id))))))))

        (testing "Remove deletes the membership and 303s back"
          (let [membership-id (:membership-id
                               (first (filter #(= email (:email %))
                                              (committees/members-for-committee committee-id))))
                resp (handler (mock/request :post (str "/api/memberships/" membership-id "/remove")))]
            (is (= 303 (:status resp)))
            (is (= (str "/events/" slug "/committee")
                   (get-in resp [:headers "Location"])))
            (is (= 1 (count (committees/members-for-committee committee-id)))
                "the creator stays on the roster")
            (is (str/includes? (:body (handler (mock/request :get (str "/events/" slug "/committee"))))
                               "Program Committee · 1 member"))))

        (testing "the person survived the removal"
          (is (some? (committees/person-by-email email))))))))

;; --- Public CFP + organizer chrome ------------------------------------------

(def ^:private cfp-form-params
  {"answer-talk-title" "Scaling AI at BigCo"
   "answer-abstract" "How we did it, and what broke."
   "answer-audience-level" "Intermediate"
   "answer-session-format" "Talk"
   "answer-session-length" "45 minutes"
   "speaker-name" "Ann Perry"
   "speaker-email" "ann@example.com"
   "speaker-title" "VP Engineering"
   "speaker-org" "BigCo"
   "speaker-bio" "Ann runs platform engineering."})

(defn- make-cfp-event! [handler slug]
  (handler (mock/request :post "/api/events/create"
                         {"name" "CFP Route Summit" "slug" slug
                          "tz" "America/New_York"
                          "cfp-opens-at" "2020-01-01T00:00"
                          "cfp-closes-at" "2099-01-01T00:00"
                          "presenter-visibility-mode" "visible"
                          "location" "Charlotte, NC"}))
  (events/event-by-slug slug))

(deftest public-cfp-page-test
  (let [handler (app)
        slug "cfp-route-test"
        event (make-cfp-event! handler slug)]

    (testing "the public page renders from the field defs"
      (let [resp (handler (mock/request :get (str "/cfp/" slug)))
            body (:body resp)]
        (is (= 200 (:status resp)))
        (is (str/includes? body "Call for Speakers"))
        (is (str/includes? body "Charlotte, NC"))
        (is (str/includes? body "Up to 3 talks per person."))
        (testing "every seed-form question is on the page"
          (doseq [f (submissions/session-fields (:fields (events/form-for-event (:id event))))]
            (is (str/includes? body (str "answer-" (name (:id f))))
                (str "missing field " (:id f)))))
        (testing "the format field renders as radios, per the wireframe"
          (is (str/includes? body "type=\"radio\""))
          (is (str/includes? body "Talk")))
        (testing "the import box is inside ABOUT YOU"
          (is (str/includes? body "Have a Sessionize profile?"))
          ;; The import box became a Datastar @post to /import-live (per-viewer
          ;; SSE morph) — the old full-form /import-sessionize action is gone.
          (is (str/includes? body "import-box"))
          (is (str/includes? body (str "/api/cfp/" slug "/import-live"))))
        (testing "the repeatable speaker group has explicit roles and no roadmap language"
          (is (str/includes? body "Primary speaker"))
          (is (str/includes? body "+ Add another speaker"))
          (is (str/includes? body "Speaker role"))
          (is (str/includes? body "Co-speaker"))
          (is (not (str/includes? body "later slice")))
          (is (not (str/includes? body "not wired up"))))
        (testing "it is a clean single column — NO organizer sidebar"
          (is (not (str/includes? body "class=\"sidebar\"")))
          (is (not (str/includes? body "All events"))))))

    (testing "an unknown slug 404s"
      (is (= 404 (:status (handler (mock/request :get "/cfp/nope"))))))))

(deftest public-embed-paths-are-distinct-html-and-unknown-paths-do-not-download-test
  (let [_ (make-cfp-event! (app) "embed-contract")
        handler (raw-app)
        responses (mapv #(handler (mock/request :get %))
                        ["/agenda/embed-contract/sessions"
                         "/agenda/embed-contract/speakers"
                         "/agenda/embed-contract/gallery"])]
    (testing "Sessions is distinct while Speakers and its Gallery alias are one product"
      (is (= [200 200 200] (mapv :status responses)))
      (is (every? #(str/starts-with? (get-in % [:headers "Content-Type"] "")
                                     "text/html")
                  responses))
      (is (= 2 (count (set (map #(str (:body %)) responses))))))

    (testing "an unknown public subpath is a real 404, not a browser download"
      (let [response (handler (mock/request :get
                                            "/agenda/embed-contract/not-a-widget"))]
        (is (= 404 (:status response)))
        (is (not= "application/octet-stream"
                  (get-in response [:headers "Content-Type"])))))))
(deftest cfp-drafts-are-isolated-between-events-test
  (let [organizer (app)
        _ (make-cfp-event! organizer "isolation-a")
        _ (make-cfp-event! organizer "isolation-b")
        anonymous (raw-app)
        first-page (anonymous (mock/request :get "/cfp/isolation-a"))
        cookie (first (str/split (first (get-in first-page [:headers "Set-Cookie"])) #";"))
        in-browser #(anonymous (mock/header % "cookie" cookie))]
    (try
      (in-browser
       (mock/request :post "/api/cfp/isolation-a/draft"
                     {"answer-talk-title" "Event A secret answer"
                      "speaker-name" "Event A Presenter"
                      "speaker-email" "event-a-presenter@example.com"}))
      (let [a-body (:body (in-browser (mock/request :get "/cfp/isolation-a")))
            b-body (:body (in-browser (mock/request :get "/cfp/isolation-b")))]
        (is (str/includes? a-body "Event A secret answer"))
        (is (str/includes? a-body "Event A Presenter"))
        (is (not (str/includes? b-body "Event A secret answer"))
            "CFP answers are tenant data and must not cross an event boundary")
        (is (not (str/includes? b-body "Event A Presenter"))
            "presenter fields are tenant data and must not cross an event boundary")
        (is (not (str/includes? b-body "event-a-presenter@example.com"))))
      (finally
        (reset! public-cfp/cfp-drafts {})
        (reset! public-cfp/cfp-notes-sent {})))))

(deftest cfp-live-note-cache-is-isolated-between-events-test
  (let [organizer (app)
        suffix (events/random-suffix 8)
        event-a (make-cfp-event! organizer (str "note-isolation-a-" suffix))
        event-b (make-cfp-event! organizer (str "note-isolation-b-" suffix))
        anonymous (raw-app)
        first-page (anonymous
                    (mock/request :get (str "/cfp/" (:slug event-a))))
        cookie (first (str/split
                       (first (get-in first-page [:headers "Set-Cookie"]))
                       #";"))
        in-browser #(anonymous (mock/header % "cookie" cookie))
        before-drafts @public-cfp/cfp-drafts
        before-notes @public-cfp/cfp-notes-sent]
    (try
      (doseq [event [event-a event-b]]
        (is (= 204
               (:status
                (in-browser
                 (mock/request
                  :post
                  (str "/api/cfp/" (:slug event) "/draft")
                  {"answer-talk-title" "A tenant-local draft"}))))))
      (let [event-ids #{(:id event-a) (:id event-b)}
            keys (->> (keys @public-cfp/cfp-notes-sent)
                      (filter #(and (vector? %) (event-ids (second %))))
                      vec)]
        (is (= 2 (count keys))
            "one browser editing two CFPs retains independent live-note state")
        (is (every? #(and (vector? %) (= 2 (count %))) keys)
            "a viewer-only cache key is a cross-event contamination path")
        (is (= #{(:id event-a) (:id event-b)}
               (set (map second keys)))
            "each live-note cache entry names its event tenant"))
      (finally
        (reset! public-cfp/cfp-drafts before-drafts)
        (reset! public-cfp/cfp-notes-sent before-notes)))))

(deftest cfp-submit-round-trip-test
  (let [handler (app)
        slug "cfp-submit-test"
        event (make-cfp-event! handler slug)]

    (testing "a valid submission 303s to its own confirmation page"
      (let [viewer-calls (atom 0)
            cfp-viewer public-cfp/cfp-viewer
            resp (with-redefs [public-cfp/cfp-viewer
                               (fn [req]
                                 (swap! viewer-calls inc)
                                 (cfp-viewer req))]
                   (handler (mock/request :post (str "/api/cfp/" slug "/submit")
                                          cfp-form-params)))
            location (get-in resp [:headers "Location"])
            submission-id (-> location
                              (str/split #"/")
                              last
                              (str/split #"\?")
                              first)]
        (is (= 303 (:status resp)))
        (is (= 1 @viewer-calls)
            "the successful submit resolves its draft owner only once")
        (is (str/starts-with? location (str "/cfp/" slug "/submitted/")))

        (testing "the success redirect names a submission that survives a full log replay"
          ;; Do not let the live in-memory projection conceal a dropped write.
          ;; A process restart must reconstruct the exact talk we acknowledged.
          (store/load!)
          (let [persisted (submissions/by-id submission-id)]
            (is (= submission-id (:id persisted)))
            (is (= (:id event) (:event-id persisted)))
            (is (= "Scaling AI at BigCo" (get-in persisted [:answers :talk-title])))
            (is (= "ann@example.com" (get-in persisted [:speakers 0 :email])))))

        (testing "and that page is addressable, so a refresh can't resubmit"
          (let [page (handler (mock/request :get location))]
            (is (= 200 (:status page)))
            (is (str/includes? (:body page) "Scaling AI at BigCo"))
            (is (str/includes? (:body page) "Your private portal link — save it"))
            (is (str/includes? (:body page) "/auth/"))
            (is (str/includes? (:body page) "confirmation email is queued"))
            (is (not (str/includes? (:body page) "Not yet wired up")))
            (let [portal-path (second (re-find #"href=\"http://localhost(/auth/[^\"]+)\""
                                               (:body page)))
                  portal-response (handler (mock/request :get portal-path))]
              (is (= 303 (:status portal-response)))
              (is (= "/portal" (get-in portal-response [:headers "Location"]))))
            (is (not (str/includes? (:body page) "class=\"sidebar\""))
                "the speaker confirmation page has no organizer chrome either")
            (is (= #{"submission-confirmation" "portal-invite"}
                   (->> (mail/queued (:id event))
                        (filter #(= "ann@example.com" (:to %)))
                        (map :kind)
                        set)))
            (is (= 1 (submissions/count-for-event (:id event)))
                "still exactly one submission after viewing")))))

    (testing "an invalid submission re-renders with messages and keeps the typing"
      (let [resp (handler (mock/request :post (str "/api/cfp/" slug "/submit")
                                        (-> cfp-form-params
                                            (assoc "answer-talk-title" "")
                                            (assoc "speaker-email" "nope"))))
            body (:body resp)]
        (is (= 422 (:status resp)))
        (is (str/includes? body "Session title is required."))
        (is (str/includes? body "A valid email address is required"))
        (is (str/includes? body "How we did it, and what broke.")
            "the abstract they typed comes back")
        (is (= 1 (submissions/count-for-event (:id event)))
            "a rejected submission appends nothing")))

    (testing "the organizer's dashboard and the BOARD both show it (the
              submissions page is retired and 303s to the board)"
      (let [dash (:body (handler (mock/request :get (str "/events/" slug))))
            redir (handler (mock/request :get (str "/events/" slug "/submissions")))
            board (:body (handler (mock/request :get (str "/events/" slug "/board"))))]
        (is (str/includes? dash "Review Board (1)"))
        (is (str/includes? dash "Scaling AI at BigCo"))
        (is (= 303 (:status redir)))
        (is (str/ends-with? (get-in redir [:headers "Location"]) "/board"))
        (is (str/includes? board "Scaling AI at BigCo"))
        (is (str/includes? board "Ann Perry"))
        (is (str/includes? board "Talk"))
        (is (str/includes? board "Pending"))))))

(deftest cfp-window-closed-test
  (let [handler (app)
        slug "cfp-closed-test"]
    (handler (mock/request :post "/api/events/create"
                           {"name" "Closed Summit" "slug" slug
                            "tz" "America/New_York"
                            "cfp-opens-at" "2020-01-01T00:00"
                            "cfp-closes-at" "2020-02-01T00:00"
                            "presenter-visibility-mode" "visible"}))
    (testing "the page says it is closed and shows no form"
      (let [body (:body (handler (mock/request :get (str "/cfp/" slug))))]
        (is (str/includes? body "call for speakers has closed"))
        (is (not (str/includes? body "Submit talk")))))

    (testing "and a POST cannot sneak past the closed window"
      ;; A refusal must LOOK like one. This used to answer 200 with the closed
      ;; page, which reads — to a script and to a speaker who scrolled — exactly
      ;; like a successful submit.
      (let [before (count (store/read-events))
            resp (handler (mock/request :post (str "/api/cfp/" slug "/submit") cfp-form-params))]
        (is (= 422 (:status resp)))
        (is (str/includes? (:body resp) "call for speakers closed")
            "the refusal is a plain sentence, not a status word")
        (is (zero? (submissions/count-for-event (:id (events/event-by-slug slug)))))
        (is (= before (count (store/read-events)))
            "a refused submission appends NOTHING")))))

(deftest cfp-opens-at-creation-test
  (let [handler (app)
        slug "cfp-opens-now-test"]
    (testing "an event created with only a name and dates lands on an OPEN call"
      ;; The whole 'zero-to-open-CFP in ten minutes' claim rests on this: no
      ;; opens-at field exists any more, so the call is live the moment the
      ;; button is pressed or the claim is false.
      (let [response (handler (mock/request :post "/api/events/create"
                                            {"name" "Opens Now Summit" "slug" slug
                                             "starts-on" "2027-05-04" "ends-on" "2027-05-05"
                                             "presenter-visibility-mode" "visible"}))
            event (events/event-by-slug slug)]
        (is (= 303 (:status response)))
        (is (= (str "/events/" slug) (get-in response [:headers "Location"])))
        (is (some? event) "a name, two dates, and an explicit review choice create an event")
        (is (= :open (submissions/cfp-state event)))
        (is (some? (:cfp-opens-at event)) "and the moment it opened is recorded")
        (is (nil? (:cfp-closes-at event)) "with no close date, it stays open")))

    (testing "the redirect target has the seed form and accepts submissions without an account wall"
      (let [response (handler (mock/request :get (str "/cfp/" slug)))
            body (:body response)
            event (events/event-by-slug slug)]
        (is (= 200 (:status response)))
        (is (str/includes? body "Submit talk"))
        (is (str/includes? body (str "/api/cfp/" slug "/submit")))
        (doseq [field (submissions/session-fields
                       (:fields (events/form-for-event (:id event))))]
          (is (str/includes? body (str "answer-" (name (:id field))))
              (str "the live CFP includes seeded field " (:id field))))))

    (testing "the support email defaults to whoever pressed the button"
      (is (= "organizer@example.com" (:support-email (events/event-by-slug slug)))))))

(deftest cfp-stays-closed-then-opened-by-hand-test
  (let [handler (app)
        slug "cfp-closed-at-create-test"]
    (handler (mock/request :post "/api/events/create"
                           {"name" "Not Yet Summit" "slug" slug
                            "cfp-state" "closed"
                            "starts-on" "2027-05-04" "ends-on" "2027-05-05"
                            "presenter-visibility-mode" "visible"}))

    (testing "'stays closed for now' reads back as NOT OPEN YET, not as closed"
      (let [event (events/event-by-slug slug)]
        (is (= :not-open-yet (submissions/cfp-state event)))))

    (testing "the public page says so warmly, and offers no form"
      (let [body (:body (handler (mock/request :get (str "/cfp/" slug))))]
        (is (str/includes? body "isn&apos;t open yet"))
        (is (not (str/includes? body "Submit talk")))))

    (testing "a submission is refused 422 and appends nothing"
      (let [before (count (store/read-events))
            resp (handler (mock/request :post (str "/api/cfp/" slug "/submit") cfp-form-params))]
        (is (= 422 (:status resp)))
        (is (str/includes? (:body resp) "isn&apos;t open yet"))
        (is (= before (count (store/read-events))))))

    (testing "the organizer opens it by hand, and then submissions land"
      (let [resp (handler (mock/request :post (str "/api/events/" slug "/cfp/open")))]
        (is (= 303 (:status resp)))
        (is (= :open (submissions/cfp-state (events/event-by-slug slug)))))
      (let [resp (handler (mock/request :post (str "/api/cfp/" slug "/submit") cfp-form-params))]
        (is (= 303 (:status resp)))
        (is (= 1 (submissions/count-for-event (:id (events/event-by-slug slug)))))))

    (testing "and closing it again by hand stops the next one"
      (is (= 303 (:status (handler (mock/request :post (str "/api/events/" slug "/cfp/close"))))))
      (is (= :closed (submissions/cfp-state (events/event-by-slug slug))))
      (let [before (count (store/read-events))
            resp (handler (mock/request :post (str "/api/cfp/" slug "/submit")
                                        (assoc cfp-form-params
                                               "speaker-email" "second@example.com")))]
        (is (= 422 (:status resp)))
        (is (= before (count (store/read-events))))))

    (testing "a close DATE can be set and cleared from settings"
      (handler (mock/request :post (str "/api/events/" slug "/cfp/close-date")
                             {"cfp-closes-on" ""}))
      ;; clearing the date alone does not reopen a call closed by hand: the
      ;; close instant IS the close date, so clearing it is the reopen.
      (is (= :open (submissions/cfp-state (events/event-by-slug slug))))
      (handler (mock/request :post (str "/api/events/" slug "/cfp/close-date")
                             {"cfp-closes-on" "2020-02-01"}))
      (is (= :closed (submissions/cfp-state (events/event-by-slug slug)))))))

(deftest cfp-import-test
  (let [handler (app)
        slug "cfp-import-test"]
    (make-cfp-event! handler slug)
    (testing "a bad profile URL re-renders the whole page with a human message"
      (let [resp (handler (mock/request :post (str "/api/cfp/" slug "/import-sessionize")
                                        {"speaker-sessionize-url" "https://example.com/nope"
                                         "speaker-name" "Already Typed"}))
            body (:body resp)]
        (is (= 200 (:status resp)))
        (is (str/includes? body "sessionize.com"))
        (is (str/includes? body "Already Typed")
            "whatever they had already typed survives the round trip")
        (is (str/includes? body "Submit talk")
            "the whole page comes back, not a fragment")))))

(deftest organizer-sidebar-test
  (let [handler (app)
        slug "sidebar-test"]
    (make-cfp-event! handler slug)

    (testing "top-level pages get the rail with the two ways in"
      (let [body (:body (handler (mock/request :get "/events")))]
        (is (str/includes? body "class=\"sidebar\""))
        (is (str/includes? body "All events"))
        (is (str/includes? body "+ New event"))))

    (testing "inside an event the lifecycle leads into organizer administration"
      ;; The order and the grouping are the design: open the call, review what
      ;; arrives, run the show, then administer speaker operations. An alphabetical list of
      ;; twelve peers said nothing about what to do next.
      (let [body (:body (handler (mock/request :get (str "/events/" slug))))
            at #(str/index-of body %)
            decide-items ["Manage Submissions and Speakers"
                          "Create Speaker (Bypass CFP)"
                          "Speaker deliverables"
                          ">Files<"]
            admin-items [">Comms<" ">Log<" ">Settings<" ">Manifesto<"]]
        ;; Event creation installs the default form, opens the public CFP, and
        ;; assigns its creator as chair, so a fresh event has completed setup
        ;; and the lifecycle begins with the normal "The call" group.
        (doseq [g ["The call" "Review CFP proposals" "Decide &amp; tell" "The show" "Admin"]]
          (is (str/includes? body g) (str "missing nav group " g)))
        (is (< (at "The call") (at "Review CFP proposals")
               (at "Decide &amp; tell") (at "The show") (at "Admin"))
            "the groups run in lifecycle order")
        (doseq [item decide-items]
          (is (some? (at item)) (str "missing Decide & tell item " item)))
        (is (apply < (map at (cons "Decide &amp; tell" decide-items)))
            "speaker operations stay together in their ratified order")
        (doseq [item admin-items]
          (is (some? (at item)) (str "missing Admin item " item)))
        (is (apply < (map at (cons "Admin" admin-items)))
            "general administration stays in its ratified order")
        (is (not (str/includes? body (str "href=\"/events/" slug "/capture\""))))
        (is (str/includes? body
                           (str "href=\"/events/" slug "/speakers?view=manage\"")))
        ;; The submissions page is retired (2026-08-10): the rail shows ONE
        ;; review entry, the board, carrying the count.
        (is (str/includes? body "Review Board ("))
        (is (not (str/includes? body (str "href=\"/events/" slug "/submissions\""))))
        (is (str/includes? body (str "/events/" slug "/log")))
        (is (str/includes? body (str "/events/" slug "/committee")))))

    (testing "the public doors open in a new tab and are marked with an arrow"
      (let [body (:body (handler (mock/request :get (str "/events/" slug))))]
        (is (str/includes? body (str "href=\"/cfp/" slug "\"")))
        (is (str/includes? body (str "href=\"/agenda/" slug "\"")))
        (is (str/includes? body "Public CFP page"))
        (is (str/includes? body "class=\"sb-inline-check\">✓</span>")
            "the already-open public door carries its completion marker")
        ;; The check is nested inside the label, so the rendered source is not
        ;; contiguous text. Pin the arrow independently of that markup detail.
        (is (str/includes? body " ↗</a>"))
        (is (str/includes? body "Public agenda ↗"))
        (is (str/includes? body "target=\"_blank\""))))

    ;; The call-status line left the sidebar 2026-08-09 (Gene: the header and
    ;; dashboard carry it; the rail is pure navigation) — the fact now lives on
    ;; the dashboard's Event card, asserted here instead.
    (testing "the dashboard says whether the call is open — the fact you came to check"
      (let [body (:body (handler (mock/request :get (str "/events/" slug))))]
        (is (str/includes? body "The call is open until"))))

    (testing "Exports & API is reachable from the nav and renders"
      (let [body (:body (handler (mock/request :get (str "/events/" slug))))]
        (is (str/includes? body (str "href=\"/events/" slug "/exports\""))))
      (let [resp (handler (mock/request :get (str "/events/" slug "/exports")))]
        (is (= 200 (:status resp)))
        (is (str/includes? (:body resp) "sessions.json"))
        (is (str/includes? (:body resp) "calendar.ics"))
        (is (str/includes? (:body resp) "llms.txt"))
        (is (str/includes? (:body resp) (str "/api/v1/events/" slug)))))

    (testing "every event page carries the breadcrumb, with the last crumb unlinked"
      (let [body (:body (handler (mock/request :get (str "/events/" slug "/board"))))]
        (is (str/includes? body "class=\"crumbs\""))
        (is (str/includes? body "<span class=\"here\">Review Board</span>"))))

    (testing "every nav item now goes somewhere real — nothing is greyed out"
      (let [body (:body (handler (mock/request :get (str "/events/" slug))))]
        ;; The form builder was the last honestly-greyed item; it exists now,
        ;; so the disabled span must be gone rather than lingering as a lie.
        (is (str/includes? body (str "href=\"/events/" slug "/form\"")))
        (is (not (str/includes? body "form builder UI coming")))
        (is (not (str/includes? body "sb-item disabled")))))

    (testing "the form builder marks itself active"
      ;; A fresh event's atomic defaults complete setup immediately, so the
      ;; regular lifecycle row—not the setup-wizard row—is active.
      (let [body (:body (handler (mock/request :get (str "/events/" slug "/form"))))]
        (is (str/includes? body (str "class=\"sb-item active\" href=\"/events/" slug "/form\"")))))

    (testing "the active item is marked server-side, per page"
      (let [dash (:body (handler (mock/request :get (str "/events/" slug))))
            board (:body (handler (mock/request :get (str "/events/" slug "/board"))))
            logp (:body (handler (mock/request :get (str "/events/" slug "/log"))))]
        (is (str/includes? dash (str "class=\"sb-item active\" href=\"/events/" slug "\"")))
        (is (str/includes? board (str "class=\"sb-item active\" href=\"/events/" slug "/board\"")))
        ;; Log was promoted from the quiet bottom row into the Admin group
        ;; (Gene ratified 2026-08-11) — same marking rule as every sb-link.
        (is (str/includes? logp (str "class=\"sb-item active\" href=\"/events/" slug "/log\"")))))))

(deftest exports-page-is-organizer-only-test
  ;; The RAW export files under /events/:slug/exports/<file> are deliberately
  ;; public (auth/open-data-pattern). The HTML index at /events/:slug/exports
  ;; has no trailing segment, so it must NOT inherit that — a speaker gets sent
  ;; to their portal, exactly like every other workspace page.
  (let [handler (app)
        slug "exports-gate-test"]
    (make-cfp-event! handler slug)
    (testing "the raw files stay public and the HTML index still renders"
      (is (= 200 (:status (handler (mock/request :get (str "/events/" slug "/exports/sessions.json"))))))
      (is (= 200 (:status (handler (mock/request :get (str "/events/" slug "/exports")))))))
    (testing "and the index is NOT matched by the open-data pattern"
      ;; The refusal for a non-member is asserted in authz-event-scope-test;
      ;; here we assert the narrower fact that makes it possible.
      (is (auth/public-path? (str "/events/" slug "/exports/sessions.json")))
      (is (not (auth/public-path? (str "/events/" slug "/exports")))))))

(deftest api-v1-read-surface-is-reachable-without-login-test
  ;; bd vi9. Wiring, not shapes — the shapes are pinned in exports-test. What is
  ;; asserted here is the thing an in-process shape test cannot see: that an
  ;; anonymous HTTP caller reaches these URLs at all. `raw-app` deliberately has
  ;; no session cookie.
  (let [handler (app)
        anon (raw-app)
        slug "api-surface-test"]
    (make-cfp-event! handler slug)

    (testing "every documented GET answers an anonymous caller"
      (doseq [path [(str "/api/v1/events/" slug)
                    (str "/api/v1/events/" slug "/docs")
                    (str "/api/v1/events/" slug "/sessions")
                    (str "/api/v1/events/" slug "/speakers")
                    (str "/api/v1/events/" slug "/schedule")
                    (str "/api/v1/events/" slug "/rooms")]]
        (let [resp (anon (mock/request :get path))]
          (is (= 200 (:status resp)) (str path " must be public"))
          (is (= "*" (get-in resp [:headers "Access-Control-Allow-Origin"]))
              (str path " must be CORS-open")))))

    (testing "the token-gated ones are REACHABLE but refuse — 401, never a login redirect"
      (doseq [path [(str "/api/v1/events/" slug "/submissions")
                    (str "/api/v1/events/" slug "/changes")]]
        (is (= 401 (:status (anon (mock/request :get path)))) path)))

    (testing "both service-index spellings are public, but not lookalike versions"
      (doseq [path ["/api/v1" "/api/v1/"]]
        (is (auth/public-path? path))
        (is (= 200 (:status (anon (mock/request :get path)))) path))
      (doseq [path ["/api/v10" "/api/v1evil"]]
        (is (not (auth/public-path? path)) path)))

    (testing "the docs page is HTML and names the event"
      (let [resp (anon (mock/request :get (str "/api/v1/events/" slug "/docs")))]
        (is (str/includes? (str (get-in resp [:headers "Content-Type"])) "text/html"))
        (is (str/includes? (str (:body resp)) slug))))))

(deftest event-log-page-test
  (let [handler (app)
        slug "log-page-test"
        event (make-cfp-event! handler slug)]
    (handler (mock/request :post (str "/api/cfp/" slug "/submit") cfp-form-params))

    (testing "the log renders this event's history in human sentences"
      (let [resp (handler (mock/request :get (str "/events/" slug "/log")))
            body (:body resp)]
        (is (= 200 (:status resp)))
        (is (str/includes? body "Created &quot;CFP Route Summit&quot;"))
        (is (str/includes? body "Spawned committee"))
        (is (str/includes? body "Installed the generic-conference form"))
        (is (str/includes? body "First saw Ann Perry"))
        (is (str/includes? body "submitted by Ann Perry"))
        (is (str/includes? body "event.created"))
        (is (str/includes? body "submission.created"))))

    (testing "it is scoped to THIS event"
      (make-cfp-event! handler "other-event")
      ;; The sidebar's event switcher legitimately links the other event, so
      ;; scope the assertion to the log region itself — that is what must not
      ;; leak another event's rows.
      (let [body (:body (handler (mock/request :get (str "/events/" slug "/log"))))
            region (subs body (str/index-of body "<div id=\"log-region\">"))]
        (is (not (str/includes? region "other-event")))
        (is (= 1 (count (re-seq #"event\.created" region)))
            "exactly one event.created row — this event's")))

    (testing "an unknown event 404s"
      (is (= 404 (:status (handler (mock/request :get "/events/nope/log"))))))))

(deftest demo-button-test
  (let [handler (app)
        resp (handler (mock/request :post "/api/events/demo"))
        location (get-in resp [:headers "Location"])
        slug (some-> location (str/replace "/events/" ""))]
    (do
      (testing "the demo button creates a uniquely-slugged demo event"
        (is (= 303 (:status resp)))
        (is (re-matches #"^/events/demo-[a-z0-9]{6}$" location)))
      (testing "and its dashboard renders"
        (let [page (handler (mock/request :get location))]
          (is (= 200 (:status page)))
          (is (str/includes? (:body page) "Demo Conference")))))))

;; INTENT-TEST: NAV-006
(deftest nav-rail-links-speaker-crm
  (let [organizer (app)
        slug "nav006-crm-rail-event"
        _ (organizer (mock/request :post "/api/events/create"
                                   {"name" "NAV006 CRM Rail Event"
                                    "slug" slug
                                    "tz" "America/New_York"
                                    "presenter-visibility-mode" "visible"}))
        body (:body (organizer (mock/request :get (str "/events/" slug))))]
    (is (str/includes? body "href=\"/people\""))
    (is (str/includes? body "Speaker CRM"))))
