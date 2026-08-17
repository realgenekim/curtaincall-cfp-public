(ns cfp-scheduler-killer.routes-contract-test
  "Permanent route contracts for refactors that move rendering code.

   The inventory comes from server/make-routes. New GET routes therefore enter
   this test automatically instead of depending on somebody remembering to add
   another hardcoded smoke-test case."
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock]))

(use-fixtures :each with-temp-store)

(defn- signed-in-app []
  (let [raw (server/create-app)
        token (auth/issue-token! "route-contract@example.com")
        login (raw (mock/request :get (str "/auth/" token)))
        cookie (first (str/split (first (get-in login [:headers "Set-Cookie"])) #";"))]
    (fn [req]
      (raw (mock/header req "cookie" cookie)))))

(defn- create-fixture-event! [handler slug]
  (let [response
        (handler
          (mock/request :post "/api/events/create"
                        {"name" "Route Contract Summit"
                         "slug" slug
                         "tz" "America/New_York"
                         "starts-on" "2026-10-14"
                         "ends-on" "2026-10-15"
                         "cfp-opens-at" "2020-01-01T00:00"
                         "cfp-closes-at" "2099-01-01T00:00"
                         "presenter-visibility-mode" "visible"
                         "support-email" "support@example.com"
                         "location" "Charlotte, NC"}))]
    (is (= 303 (:status response)) "route fixture event must be created")
    (is (some? (events/event-by-slug slug)) "route fixture event must be readable")))

(defn- get-route-definitions []
  (->> (server/make-routes)
       (keep (fn [[path methods]]
               (when-let [route (:get methods)]
                 {:path path
                  :handler (:handler route)
                  :handler-name (some-> route :handler meta :name name)})))
       vec))

(defn- route-topology []
  (->> (server/make-routes)
       (mapcat (fn [[path methods]]
                 (for [[method route] methods
                       :when (and (keyword? method)
                                  (map? route)
                                  (:handler route))]
                   [method path (some-> route :handler meta :name str)])))
       (sort-by (juxt second first))
       vec))

(defn- sha-256 [value]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                        (.getBytes (pr-str value) "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) digest))))

(defn- concrete-path [path slug]
  (-> path
      (str/replace ":slug" slug)
      (str/replace ":submission-id" "missing-submission")
      (str/replace ":person-id" "missing-person")
      (str/replace ":kind" "homepage")
      (str/replace ":token" "invalid-token")))

(defn- route-contract [path]
  (cond
    (or (= path "/api/sse")
        (= path "/api/cfp/live")
        (str/ends-with? path "/stream")
        (str/ends-with? path "/fragment"))
    {:execution :persistent :content-type "text/event-stream"}

    (str/starts-with? path "/auth/google")
    {:execution :external}

    (= path "/dev/reload-check")
    {:execution :finite}

    (= path "/dev/sse-state")
    {:execution :finite :content-type "application/json"}

    (= path "/ping")
    {:execution :finite :content-type "text/plain"}

    (str/ends-with? path ".png")
    {:execution :finite}

    (str/ends-with? path "/docs")
    {:execution :finite :content-type "text/html"}

    (or (str/starts-with? path "/api/v1")
        (str/ends-with? path ".json"))
    {:execution :finite :content-type "application/json"}

    (str/ends-with? path ".ics")
    {:execution :finite :content-type "text/calendar"}

    (str/ends-with? path ".csv")
    {:execution :finite :content-type "text/csv"}

    (str/ends-with? path ".zip")
    {:execution :finite :content-type "application/zip"}

    (and (str/starts-with? path "/api/submissions/")
         (str/ends-with? path "/download"))
    {:execution :finite}

    (or (str/ends-with? path ".png")
        (str/starts-with? path "/admin/zoo/social-sharing/cards/"))
    {:execution :finite :content-type "image/png"}

    (str/ends-with? path "/llms.txt")
    {:execution :finite :content-type "text/markdown"}

    (not (str/starts-with? path "/api/"))
    {:execution :finite :content-type "text/html"}

    :else nil))

(deftest every-get-route-has-a-live-contract
  (let [handler (signed-in-app)
        slug (str "route-contract-" (events/random-suffix 10))
        routes (get-route-definitions)]
    (create-fixture-event! handler slug)
    (is (pos? (count routes)) "make-routes must expose GET routes")
    (is (= (count routes) (count (distinct (map :path routes))))
        "GET route paths must be unique")
    (doseq [{:keys [path handler-name]} routes]
      (testing (str "GET " path " -> " handler-name)
        (let [{:keys [execution content-type] :as contract} (route-contract path)]
          (is (some? contract)
              (str "new GET route needs a derived contract classification: " path))
          (when (= :finite execution)
            (let [response (handler (mock/request :get (concrete-path path slug)))
                  status (:status response)
                  actual-content-type (get-in response [:headers "Content-Type"] "")]
              (is (integer? status) "finite routes must return a Ring response")
              (is (< status 500) (str "route returned " status))
              (when (and content-type (not (<= 300 status 399)))
                (is (str/includes? actual-content-type content-type)
                    (str "expected " content-type ", got " actual-content-type))))))))))

(deftest root-llms-txt-is-a-public-agent-front-door
  ;; hg63: GET /llms.txt at the ROOT redirected home. The only llms.txt was
  ;; event-scoped, so /llms.txt fell through the router to the default-deny
  ;; gate, which sends a stranger to "/". An agent that starts at the domain
  ;; could therefore discover nothing. A door that 404s or redirects home is a
  ;; defect, not a missing feature.
  (let [signed-in (signed-in-app)
        anonymous (server/create-app)
        slug (str "route-contract-" (events/random-suffix 10))]
    (create-fixture-event! signed-in slug)
    (testing "the door opens with no session at all"
      (let [response (anonymous (mock/request :get "/llms.txt"))]
        (is (= 200 (:status response)) "the root agent index must not redirect or 404")
        (is (str/includes? (get-in response [:headers "Content-Type"] "") "text/markdown"))
        (is (str/includes? (:body response) "Route Contract Summit"))
        (is (str/includes? (:body response) (str "/events/" slug "/llms.txt"))
            "the site index must hand off to each event's own index")
        (is (str/includes? (:body response) (str "/api/v1/events/" slug)))
        (is (str/includes? (:body response) "/api/v1")
            "the service index is one of the next hops")))
    (testing "the service index is public too"
      (let [response (anonymous (mock/request :get "/api/v1"))]
        (is (= 200 (:status response)))
        (is (str/includes? (get-in response [:headers "Content-Type"] "")
                           "application/json"))))
    (testing "an unlisted event stays invisible to an agent"
      (events/set-unlisted! (events/event-by-slug slug) true "kaocha")
      (let [body (:body (anonymous (mock/request :get "/llms.txt")))]
        (is (not (str/includes? body slug)))
        (is (not (str/includes? body "Route Contract Summit")))))
    (testing "the allowlist opens exactly one path and not its neighbours"
      (is (auth/public-path? "/llms.txt"))
      (is (not (auth/public-path? "/llms.txtx")))
      (is (not (auth/public-path? "/llms.txt/anything"))))))

(deftest per-session-invite-amends-by-stable-uid
  (let [handler (server/create-app)
        submission-id "11111111-1111-1111-1111-111111111111"
        event {:id "event-1" :slug "summit" :name "Summit"
               :starts-on (java.time.LocalDate/of 2026 10 14)
               :ends-on (java.time.LocalDate/of 2026 10 15)
               :location "Charlotte, NC"}
        submission {:id submission-id :event-id "event-1" :status "Accepted"
                    :notified-at "2026-08-14T00:00:00Z"
                    :answers {:talk-title "Stable calendars" :abstract "Amend me."}
                    :speakers [{:name "Ada Speaker"}]}
        slot (atom {:day "2026-10-14" :start 600 :end 660})
        history (atom [{:payload {:submission-id submission-id}}])
        path (str "/agenda/summit/sessions/" submission-id "/invite.ics")
        uid-of #(second (re-find #"UID:([^\r\n]+)" %))
        sequence-of #(second (re-find #"SEQUENCE:(\d+)" %))]
    (with-redefs [events/event-by-slug (constantly event)
                  store/submission-by-id (constantly submission)
                  store/slot-for (fn [_] @slot)
                  store/rooms-for-event (constantly [{:id "hall-a" :name "Hall A"}])
                  store/log-for-event (fn [_] @history)]
      (let [before (handler (mock/request :get path))]
        (is (= 200 (:status before)))
        (is (str/includes? (get-in before [:headers "Content-Type"]) "text/calendar"))
        (is (str/includes? (get-in before [:headers "Content-Disposition"]) "attachment"))
        (swap! slot assoc :start 660 :end 720 :room-id "hall-a")
        (swap! history conj {:payload {:submission-id submission-id}})
        (let [after (handler (mock/request :get path))]
          (is (= (uid-of (:body before)) (uid-of (:body after)))
              "time and room amendments retain the session UID")
          (is (not= (sequence-of (:body before)) (sequence-of (:body after)))
              "the amendment increments SEQUENCE")
          (is (str/includes? (:body after) "LOCATION:Hall A\\, Charlotte\\, NC")))))))

(deftest complete-route-topology-is-characterized
  ;; Intentional topology update 2026-08-11 (host playbill + the /welcome
  ;; split, both Gene ratified): +/organizers/:slug GET, +/welcome GET.
  ;; 123 -> 125. Then BLIND REVIEW voting policy: +/api/events/:slug/blind-review
  ;; POST. 125 -> 126. Then the per-speaker shareable announce hero page
  ;; (Gene ratified): +/agenda/:slug/speakers/:person-id/announce GET. 126 -> 127.
  ;; Then Gallery deleted (duplicated Speakers; Gene 2026-08-11): 127 -> 126.
  ;; Then /program/:slug — THE canonical public face; agenda/speakers/sessions
  ;; lists redirect to it (Gene ratified 2026-08-11): 126 -> 127.
  ;; Then the zoo (bead sessionize-sched-killer-92x): +/admin/zoo/social-sharing
  ;; GET — an organizer-only element gallery, absent from auth/public-prefixes
  ;; so the default-deny gate requires a session. 127 -> 128.
  ;; Then Ann's speaker marquee (bead a3b): +announce and +create-speaker GET,
  ;; plus create/adopt/adopt-all POST. 128 -> 133.
  ;; Then generated Open Graph cards (bead cc8): +card.png GET. 133 -> 134.
  ;; Then CFP discovery (bead b0d): +/cfps GET (merged with cc8). 134 -> 135.
  ;; Then inline program speaker editing (bead g9v):
  ;; +/api/events/:slug/speakers/:person-id POST. 135 -> 136.
  ;; The reconstructed house then restores the main-only handler modules plus
  ;; Notify, Edit Speaker, Tracks, Mention, and the event-scoped API-key copy
  ;; endpoint. The additive route table is deliberately de-duplicated against
  ;; core-routes, and the intentionally retired Gallery route stays retired:
  ;; 216 distinct entries, then the approval-gated outbox adds three organizer
  ;; commands (approve one, discard one, approve all): 216 -> 219. The organizer
  ;; event brag page adds /program/:slug/announce GET: 219 -> 220. Then the
  ;; ROOT agent index (hg63): +/llms.txt GET — the front door an agent knocks
  ;; on when all it has is the domain: 220 -> 221. Fleet evidence restored the
  ;; generated speaker-gallery URL as a distinct public surface: 221 -> 222.
  ;; Per-session calendar attachments add one public GET: 222 -> 223. The
  ;; organizer co-speaker workflow adds assignment and removal POSTs: 223 -> 225.
  ;; Lazy board review controls add one server-push POST: 225 -> 226.
  ;; Speaker publication API adds publish and unpublish POSTs: 226 -> 228.
  ;; Server-owned CFP live validation adds one GET and Speakers filtering adds
  ;; one POST: 228 -> 230.
  ;; Then NATIVE QUICK RATE (review-board rebuild 2026-08-16, Mayor-swept,
  ;; pending GENEDEV ratification): the board's Quick Rate card became a plain
  ;; <form method="post" action="/api/submissions/:id/rate">, so the SSE-reveal
  ;; endpoint /api/submissions/:submission-id/quick-rate POST was DELETED,
  ;; along with handlers.board/handle-quick-rate and
  ;; review-updates/push-quick-rate!. This entry is a route REMOVAL, not an
  ;; addition: 230 -> 229 (:post 138 -> 137).
  ;; Then qd1d retires formal review rounds and configurable scorecards by
  ;; deleting their twelve POST writers while preserving the presenter-policy
  ;; writer and all historical fold readers: 229 -> 217 (:post 137 -> 125).
  ;; A per-person default event adds one event-scoped POST writer that moves the
  ;; star on /events: 217 -> 218 (:post 125 -> 126).
  ;; Dedicated speaker editing adds one organizer GET and live roster filtering
  ;; adds one organizer POST: 218 -> 220.
  ;; Extracting reviewer progress from the board adds its dedicated organizer
  ;; GET page: 220 -> 221.
  ;; nx6f ABS-13 adds two organizer-only review-results GET exports: 221 -> 223.
  ;; 9v0f adds the per-member reviewer track-scope POST writer: 223 -> 224.
  ;; vbo7 REST API v1 writes add the scoped submission review POST: 224 -> 225
  ;; (:post 128 -> 129).
  ;; jwpb adds the anonymous public itinerary GET: 225 -> 226 (:get 95 -> 96).
  ;; af6b makes visitor picks browser-owned: it retires the two server-star
  ;; POST writers and changes the personal calendar export from GET to POST,
  ;; leaving 224 routes overall.
  ;; g3gl adds organizer resources, public resource index/detail, and the
  ;; fact-writing page save: 224 -> 228 (:get 95 -> 98, :post 128 -> 129).
  ;; The public surname directory adds one GET beside the preserved unified
  ;; Speakers/Gallery product: 228 -> 229.
  (let [topology (route-topology)]
    (is (= 229 (count topology))
        "adding or removing a route requires an intentional topology update")
    (is (= {:get 99 :post 129 :put 1} (frequencies (map first topology)))
        "all GET, POST, and PUT routes participate in the characterization")
    (is (= "4ffff0757f01317347a2bcb598b6db32e7b6b484ad44a74f3dee832d94f45029"
           (sha-256 topology))
        (str "method/path/handler-name wiring changed: " (pr-str topology)))))
