(ns cfp-scheduler-killer.track-scope-test
  "Track SCOPING — 'room, not cubicle'. A committee scoped to a track makes
   those tracks the DEFAULT board slice for its reviewers, but never a fence:
   the reviewer can reveal every track with one click, and can rate assigned
   work outside their track. Scope is a filter/default; assignment remains the
   independent write authority.

   The two assertions that keep it honest are NEGATIVE:
     - the 'show all' escape reveals tracks the committee never scoped;
     - a reviewer can rate a submission OUTSIDE their track.
   And the additive guarantee: scope {:all true} = today's behavior, unchanged."
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.handlers.dashboard :as dashboard-handlers]
   [cfp-scheduler-killer.review-assignments :as assignments]
   [cfp-scheduler-killer.review-work :as review-work]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as sub]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [cfp-scheduler-killer.tracks :as tracks]
   [cfp-scheduler-killer.views.committee :as committee-view]
   [cfp-scheduler-killer.web.datastar :as web-datastar]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [hiccup2.core :as h])
  (:import
   (java.time LocalDateTime)))

(use-fixtures :each with-temp-store)

;; --- Fixtures ---------------------------------------------------------------

(def ^:private track-a "AI Models")
(def ^:private track-b "Architecture")

(defn- make-event! []
  (events/create-eais-event!
    {:name "Track Scope Summit" :slug "track-scope" :tz "America/New_York"
     :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
     :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
    "kaocha"))

(defn- committee-id [event]
  (:id (first (events/committees-for-event (:id event)))))

(defn- add-reviewer! [event email]
  (committees/add-member! (committee-id event)
                          {:name "Rev Iewer" :email email :role "reviewer"}
                          "kaocha"))

(defn- submit! [event title track]
  (let [ff (:fields (events/form-for-event (:id event)))
        params {:answer-talk-title title
                :answer-abstract "Abstract."
                :answer-session-format "Experience Report"
                :answer-track track
                :answer-org-size ">10,000"
                :answer-industry "Insurance"
                :answer-ai-transformation-history "2023."
                :answer-measurable-outcomes "Numbers."
                :answer-notes-to-committee "A private note."
                :speaker-name "Ann Speaker"
                :speaker-email (str (random-uuid) "@example.com")
                :speaker-title "VP" :speaker-org "BigCo" :speaker-bio "Bio."}]
    (sub/create-submission! event (sub/parse-answers ff params) (sub/parse-speaker params)
                            "form" "kaocha")))

(defn- board-req
  "A ring request for the board with `person` signed in and query `params`."
  [event person params]
  {:path-params {:slug (:slug event)}
   :params params
   ;; board-state reads the signed-in person via auth/current-person, which
   ;; consults the request session; inject the person's id there.
   :session {:person-id (:id person)}})

;; board-state is private; reach it the way the handler does.
(def ^:private board-state #'server/board-state)

(defn- board-tracks
  "The set of tracks actually shown on the board for `person` under `params`."
  [event person params]
  (->> (:rows (board-state (board-req event person params) event))
       (map #(get-in % [:answers :track]))
       set))

;; --- Pure scope resolution --------------------------------------------------

(deftest scope-tracks-test
  (testing "a track scope yields its set; open/absent scopes yield nil"
    (is (= #{track-a} (committees/scope-tracks {:field :track :in [track-a]})))
    (is (= #{track-a track-b}
           (committees/scope-tracks {:field :track :in [track-a track-b]})))
    (is (nil? (committees/scope-tracks {:all true})))
    (is (nil? (committees/scope-tracks {:field :track :in []})))
    (is (nil? (committees/scope-tracks nil)))))

(deftest member-scope-is-append-only-and-latest-wins-test
  (let [event (make-event!)
        cid (committee-id event)
        member (add-reviewer! event "scoped@example.com")
        person-id (:person-id member)]
    (testing "absence is the zero-migration open-table default"
      (is (= :all (committees/member-scope member)))
      (is (nil? (committees/tracks-for-person-on-event (:id event) person-id))))
    (testing "the latest member-scoped fact wins"
      (committees/set-member-scope! cid person-id #{track-a} "organizer")
      (committees/set-member-scope! cid person-id #{track-b} "organizer")
      (is (= #{track-b}
             (committees/tracks-for-person-on-event (:id event) person-id)))
      (is (= 2 (->> (:log (store/snapshot))
                    (filter #(= "committee.member-scoped" (:type %)))
                    count))))
    (testing "clearing the last track restores All tracks"
      (let [updated (committees/set-member-scope! cid person-id #{} "organizer")]
        (is (= :all (committees/member-scope updated)))
        (is (nil? (committees/tracks-for-person-on-event (:id event) person-id)))))))

(deftest committee-member-scope-controls-post-and-repaint-test
  (let [event (make-event!)
        cid (committee-id event)
        chair-member (committees/add-member!
                       cid {:name "Chair Person" :email "chair@example.com" :role "chair"}
                       "kaocha")
        chair (store/person-by-id (:person-id chair-member))
        reviewer (add-reviewer! event "chips@example.com")
        person-id (:person-id reviewer)
        track (first (tracks/tracks-for-event event))
        page-body (:body (dashboard-handlers/render-committee-page
                           {:session {:person-id (:id chair)}} event))
        html (str (h/html
                    (committee-view/member-scope-controls
                      cid [track-a track-b] reviewer true)))]
    (testing "the page explains that tracks are defaults, never walls"
      (is (str/includes?
            page-body
            "Tracks shape each reviewer&apos;s default work queue — a filter, not a wall"))
      (is (str/includes? page-body (str "/events/" (:slug event) "/details"))))
    (testing "the chips are server-rendered from track data"
      (is (str/includes? html "All tracks"))
      (is (str/includes? html track-a))
      (is (str/includes? html track-b))
      (is (str/includes? html "action=only"))
      (is (str/includes? html "data-star-on:click")))
    (testing "a chair's POST appends the scope and repaints only this member"
      (let [pushed (atom nil)
            response
            (with-redefs [web-datastar/sse-fragment-response
                          (fn [_req selector fragment]
                            (reset! pushed {:selector selector :html fragment})
                            {:status 200 :headers {} :body ""})]
              (dashboard-handlers/handle-member-scope
                {:path-params {:committee-id cid :person-id person-id}
                 :params {:action "only" :track track}
                 :session {:person-id (:id chair)}}))]
        (is (= 200 (:status response)))
        (is (= #{track}
               (committees/tracks-for-person-on-event (:id event) person-id)))
        (is (= (str "#member-scope-" (:membership-id reviewer))
               (:selector @pushed)))
        (is (str/includes? (:html @pushed) "All tracks"))))
    (testing "clearing the only selected track restores All tracks"
      (with-redefs [web-datastar/sse-fragment-response
                    (fn [_req _selector _fragment]
                      {:status 200 :headers {} :body ""})]
        (dashboard-handlers/handle-member-scope
          {:path-params {:committee-id cid :person-id person-id}
           :params {:action "remove" :track track}
           :session {:person-id (:id chair)}}))
      (is (nil? (committees/tracks-for-person-on-event (:id event) person-id))))))

;; --- set-scope! is config-as-data -------------------------------------------

(deftest set-scope-is-config-as-data-test
  (let [event (make-event!)
        cid (committee-id event)]
    (testing "the auto-committee starts open (:all true) — Gene's degenerate case"
      (is (= {:all true} (:scope (get-in (store/snapshot) [:committees cid])))))
    (testing "an organizer can set a track scope; it is stored verbatim"
      ;; The store round-trips every payload through JSON: keys keywordise,
      ;; values keep their string form, so `:track` reads back as \"track\".
      ;; scope-tracks matches by name, so both shapes resolve identically.
      (committees/set-scope! cid {:field :track :in [track-a]} "organizer")
      (is (= {:field "track" :in [track-a]}
             (:scope (get-in (store/snapshot) [:committees cid]))))
      (is (= #{track-a}
             (committees/scope-tracks (:scope (get-in (store/snapshot) [:committees cid]))))))
    (testing "re-scoping is one fact, and reopening restores the open table"
      (committees/set-scope! cid {:all true} "organizer")
      (is (= {:all true} (:scope (get-in (store/snapshot) [:committees cid])))))
    (testing "an unknown committee refuses loudly"
      (is (thrown? clojure.lang.ExceptionInfo
                   (committees/set-scope! "nope" {:all true}))))))

;; --- The default board view is the committee's rooms ------------------------

(deftest default-board-is-committee-rooms-test
  (let [event (make-event!)
        rev (add-reviewer! event "rev@example.com")
        person (store/person-by-id (:person-id rev))
        _a (submit! event "In-track talk" track-a)
        _b (submit! event "Out-of-track talk" track-b)]

    (testing "open scope (:all) shows every track — unchanged behavior"
      (is (= #{track-a track-b} (board-tracks event person {}))))

    (committees/set-member-scope! (committee-id event) (:id person) #{track-a})

    (testing "a track-scoped committee defaults its reviewer's board to that track"
      (is (= #{track-a} (board-tracks event person {}))
          "the board opens pre-filtered to the committee's rooms"))

    (testing "the 'show all tracks' escape reveals the other tracks"
      (is (= #{track-a track-b} (board-tracks event person {:all "1"}))
          "one click on the open-table escape shows tracks the committee never scoped"))

    (testing "an explicit single-track facet still works (and can pick OUTSIDE the scope)"
      (is (= #{track-b} (board-tracks event person {:track track-b}))
          "clicking a facet is navigation, never blocked by committee scope"))

    (testing "the view carries the toggle state: scoped by default, open under ?all=1"
      (let [scoped (board-state (board-req event person {}) event)
            opened (board-state (board-req event person {:all "1"}) event)]
        (is (= #{track-a} (:scoped-tracks scoped)))
        (is (= #{track-a} (:my-tracks scoped)) "the reviewer's tracks are always known")
        (is (= 1 (get-in scoped [:coverage :total]))
            "coverage denominator follows the reviewer's default queue")
        (is (nil? (:scoped-tracks opened)) "escaped: nothing narrows the view")
        (is (= 2 (get-in opened [:coverage :total]))
            "Show all tracks restores event-wide coverage")
        (is (true? (:show-all? opened)))))))

(deftest reviewer-progress-reports-coverage-of-member-scope-test
  (let [event (make-event!)
        member (add-reviewer! event "progress@example.com")
        person-id (:person-id member)
        _a (submit! event "Scoped" track-a)
        _b (submit! event "Other" track-b)]
    (committees/set-member-scope! (committee-id event) person-id #{track-a})
    (let [progress (->> (review-work/progress-for-event (:id event))
                        (filter #(= person-id (:person-id %)))
                        first)]
      (is (= track-a (:scope-label progress)))
      (is (= {:total 1 :covered 0 :target 2 :pct 0.0}
             (:scope-coverage progress))))))

;; --- Union across committees, never intersection ----------------------------

(defn- add-committee!
  "A second committee on `event`, scoped to `tracks`, via the store's own event
   vocabulary (the same facts events/create-event! emits). Returns its id."
  [event tracks]
  (let [cid (store/new-id)]
    (store/append! {:type "committee.created" :actor "kaocha"
                    :payload {:id cid :event-id (:id event) :name "Track Committee"
                              :scope {:field :track :in tracks} :coverage-target 2
                              :created-at (store/now-iso)}})
    cid))

(deftest union-of-committee-rooms-test
  (let [event (make-event!)
        cid1 (committee-id event)
        cid2 (add-committee! event [track-b])
        ;; Put ONE reviewer on both committees.
        rev (committees/add-member! cid1 {:name "Multi" :email "multi@example.com"
                                          :role "reviewer"} "kaocha")
        person (store/person-by-id (:person-id rev))]
    (committees/add-member! cid2 {:name "Multi" :email "multi@example.com"
                                  :role "reviewer"} "kaocha")
    (testing "an open (:all) committee anywhere means the whole table is the default"
      (is (nil? (committees/tracks-for-person-on-event (:id event) (:id person)))
          "cid1 is still :all, so its reviewer is never pre-filtered"))
    (committees/set-member-scope! cid1 (:id person) #{track-a})
    (committees/set-member-scope! cid2 (:id person) #{track-b})
    (testing "two track-committees WIDEN the reviewer's rooms — union, not intersection"
      (is (= #{track-a track-b}
             (committees/tracks-for-person-on-event (:id event) (:id person)))))))

;; --- Rating is NEVER gated by track (the anti-cubicle guarantee) ------------

(deftest rating-is-never-gated-by-track-test
  (let [event (make-event!)
        rev (add-reviewer! event "rater@example.com")
        person (store/person-by-id (:person-id rev))
        _a (submit! event "In-track talk" track-a)
        out (submit! event "Out-of-track talk" track-b)]
    (committees/set-member-scope! (committee-id event) (:id person) #{track-a})

    (testing "the reviewer's default board does NOT include the out-of-track talk"
      (is (not (contains? (board-tracks event person {}) track-b))))

    (testing "yet the reviewer can rate that very out-of-track submission"
      ;; The assignment grants write authority; track scope is never consulted.
      (assignments/assign! (:id out) (:id person) "chair@example.com")
      (reviews/set-rating! (:id out) (:id person) 4.5 "rater@example.com")
      (let [enriched (->> (reviews/enriched-for-event (:id event))
                          (filter #(= (:id out) (:id %)))
                          first)]
        (is (= 1 (count (:ratings enriched)))
            "a rating on a submission outside the reviewer's track is accepted")
        (is (= 4.5 (:stars (first (:ratings enriched)))))))

    (testing "and it shows up when the reviewer reveals all tracks"
      (is (contains? (board-tracks event person {:all "1"}) track-b)))))

;; --- Additive: an open committee behaves EXACTLY as before ------------------

(deftest open-scope-is-unchanged-test
  (let [event (make-event!)
        rev (add-reviewer! event "open@example.com")
        person (store/person-by-id (:person-id rev))
        _a (submit! event "One" track-a)
        _b (submit! event "Two" track-b)]
    (testing "{:all true} committee => no default narrowing, no toggle rendered"
      (let [state (board-state (board-req event person {}) event)]
        (is (nil? (:scoped-tracks state)))
        (is (nil? (:my-tracks state)) "an open reviewer has no rooms to escape from")
        (is (= #{track-a track-b}
               (set (map #(get-in % [:answers :track]) (:rows state)))))))))
