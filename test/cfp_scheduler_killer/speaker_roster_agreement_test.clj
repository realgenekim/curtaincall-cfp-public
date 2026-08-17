(ns cfp-scheduler-killer.speaker-roster-agreement-test
  "SPK-01: the canonical event speaker roster and every other organizer read of
   the same people must agree.

   An organizer creates an invited speaker from the sidebar's `Create Speaker`
   page (`/events/:slug/speakers/new` → POST `/api/events/:slug/speakers/create`).
   That person appeared under Announce but was invisible on the dedicated
   roster (`/events/:slug/speakers`) and to its search box, because the creation
   path minted a person and a program entry and never recorded the EVENT
   PARTICIPATION the roster projection is built from.

   These tests drive the real router, so the two read paths are pinned together
   the way the product actually exercises them."
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.public-catalog :as public-catalog]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.speakers :as speakers]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock]))

(use-fixtures :each
  with-temp-store
  (fn [f] (reset! auth/tokens {}) (f)))

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
                  {"name" "Speaker Roster Summit"
                   "slug" slug
                   "starts-on" "2026-10-14"
                   "ends-on" "2026-10-15"
                   "presenter-visibility-mode" "visible"}))
  (events/event-by-slug slug))

(defn- create-speaker! [handler slug params]
  (handler (mock/request :post (str "/api/events/" slug "/speakers/create") params)))

(defn- roster-names [event]
  (mapv :name (speakers/roster-for-event (:id event))))

(deftest created-speakers-are-on-the-canonical-roster-and-in-its-search
  (let [handler (organizer-app)
        event (make-event! handler "roster-agreement")]
    (testing "the product's own creation form saves both speakers"
      (is (= 303 (:status (create-speaker! handler "roster-agreement"
                                           {"name" "Priya Raghavan"
                                            "email" "priya@example.com"
                                            "org" "Acme Bank"
                                            "title" "VP of Platform"
                                            "bio" "Priya has led platform work for a decade."
                                            "announce" "1"}))))
      (is (= 303 (:status (create-speaker! handler "roster-agreement"
                                           {"name" "Marcus Devlin"
                                            "email" "marcus@example.com"
                                            "org" "North Banc"
                                            "title" "Director of Engineering"
                                            "bio" "Marcus runs delivery for North Banc."})))))

    (testing "Announce sees them (the read path that already worked)"
      (let [program (public-catalog/program-speakers
                      (events/event-by-id (:id event)))]
        (is (= #{"Priya Raghavan" "Marcus Devlin"} (set (map :name program))))))

    (testing "the canonical roster query sees exactly the same people"
      (is (= ["Marcus Devlin" "Priya Raghavan"] (roster-names event)))
      (is (every? #(= "Invited" (:status %)) (speakers/roster-for-event (:id event)))
          "an organizer-created speaker is an INVITED participant of this event"))

    (testing "the dedicated roster page renders them"
      (let [body (str (:body (handler (mock/request
                                        :get "/events/roster-agreement/speakers"))))]
        (is (str/includes? body "Priya Raghavan"))
        (is (str/includes? body "Marcus Devlin"))
        (is (not (str/includes? body "No speakers match this view.")))))

    (testing "roster search finds one and excludes the other"
      (let [body (str (:body (handler (mock/request
                                        :get "/events/roster-agreement/speakers"
                                        {"q" "priya"}))))]
        (is (str/includes? body "Priya Raghavan"))
        (is (not (str/includes? body "Marcus Devlin"))))
      (let [body (str (:body (handler (mock/request
                                        :get "/events/roster-agreement/speakers"
                                        {"q" "North Banc"}))))]
        (is (str/includes? body "Marcus Devlin"))
        (is (not (str/includes? body "Priya Raghavan")))))

    (testing "the roster's Invited filter agrees with the roster query"
      (let [body (str (:body (handler (mock/request
                                        :get "/events/roster-agreement/speakers"
                                        {"status" "Invited"}))))]
        (is (str/includes? body "Priya Raghavan"))
        (is (str/includes? body "Marcus Devlin"))))

    (testing "creation appended exactly one participation fact per speaker"
      (store/await-sinks!)
      (is (= 2 (count (filter #(= "speaker.added-to-event" (:type %))
                              (store/read-events))))))))

(deftest announce-panel-still-owns-program-details-for-created-speakers
  ;; The roster and the Announce marquee share POST
  ;; /api/events/:slug/speakers/:person-id. Now that a created speaker is a
  ;; roster member, the shared route must still route the ANNOUNCE panel's
  ;; program shape (org + publish toggle) to the program updater.
  (let [handler (organizer-app)
        event (make-event! handler "announce-panel")
        _ (create-speaker! handler "announce-panel"
                           {"name" "Priya Raghavan"
                            "email" "priya@example.com"
                            "org" "Acme Bank"
                            "title" "VP of Platform"
                            "bio" "Priya has led platform work for a decade."
                            "announce" "1"})
        person-id (:person-id (first (speakers/roster-for-event (:id event))))
        path (str "/api/events/announce-panel/speakers/" person-id)]

    (testing "the panel's edit saves the program organization and profile"
      (is (= 303 (:status (handler (mock/request :post path
                                                 {"name" "Priya Raghavan"
                                                  "org" "North Banc"
                                                  "title" "CTO"
                                                  "bio" "Now at North Banc."
                                                  "announce" "1"})))))
      (let [speaker (public-catalog/program-speaker-by-id
                      (events/event-by-id (:id event)) person-id)]
        (is (= "North Banc" (:company speaker)))
        (is (= "CTO" (:tagline speaker)))
        (is (true? (:published? speaker)))))

    (testing "the panel can still unpublish a created speaker"
      (is (= 303 (:status (handler (mock/request :post path
                                                 {"name" "Priya Raghavan"
                                                  "org" "North Banc"
                                                  "title" "CTO"
                                                  "bio" "Now at North Banc."})))))
      (is (false? (:published? (public-catalog/program-speaker-by-id
                                 (events/event-by-id (:id event)) person-id))))
      (is (nil? (public-catalog/speaker-by-id
                  (events/event-by-id (:id event)) person-id))))

    (testing "the roster's own inline edit still writes event-local details"
      (is (= 303 (:status (handler (mock/request :post path
                                                 {"organization" "Acme Bank"
                                                  "title" "VP of Platform"
                                                  "notes" "Books her own travel."})))))
      (let [row (first (speakers/roster-for-event (:id event)))]
        (is (= "Acme Bank" (:organization row)))
        (is (= "Books her own travel." (:notes row)))))))
