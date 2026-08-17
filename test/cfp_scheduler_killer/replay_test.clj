(ns cfp-scheduler-killer.replay-test
  "The replay simulator and time-travel.

   Replay is tested with an INJECTED corpus rather than the shipped one, so
   these tests describe the contract instead of the content — and stay green
   whatever the corpus agent puts in the file."
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.replay :as replay]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as sub]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock])
  (:import
   (java.time LocalDate LocalDateTime)))

(use-fixtures :each
  with-temp-store
  (fn [f] (reset! auth/tokens {}) (reset! replay/runs {}) (f)))

(def tiny-corpus
  "A miniature corpus in the SHIPPED format (resources/replay/README.md):
   a timeline on offset-secs, review entries joined by on-title and attributed
   by reviewer email."
  {:meta {:window-secs 259200}
   :window-secs 259200
   :entries [{:offset-secs 0 :kind "submission"
              :submission {:answers {:talk-title "Shipping agents in production"
                                     :abstract "What broke, and what we changed."
                                     :session-format "Experience Report"
                                     :org-size ">10,000"
                                     :industry "Technology"
                                     :ai-transformation-history "Since 2023."
                                     :measurable-outcomes "40% faster."}
                           :speaker {:name "Sam Okafor" :email "sam@example.com"
                                     :title "Staff Engineer" :org "Northwind"
                                     :bio "Sam builds platforms."}}}
             {:offset-secs 3600 :kind "submission"
              :submission {:answers {:talk-title "Evals that actually caught something"
                                     :abstract "Three evals, two useless, one that saved us."
                                     :session-format "SME talk"
                                     :org-size "<1,000"
                                     :industry "Technology"
                                     :ai-transformation-history "Since 2024."
                                     :measurable-outcomes "One prevented outage."}
                           :speaker {:name "Rae Lin" :email "rae@example.com"
                                     :title "ML Lead" :org "Cloudbank"
                                     :bio "Rae works on evals."}}}
             {:offset-secs 90000 :kind "rating"
              :on-title "Shipping agents in production"
              :by "genek@itrevolution.net" :stars 4.5}
             {:offset-secs 95000 :kind "comment"
              :on-title "Shipping agents in production"
              :by "annp@itrevolution.net"
              :body "The rollback story alone is worth the slot."}
             {:offset-secs 180000 :kind "status"
              :on-title "Shipping agents in production"
              :by "genek@itrevolution.net" :to "Accept Queue"}]})

(defn- demo-event! []
  (with-redefs [replay/load-corpus (constantly tiny-corpus)
                replay/corpus-available? (constantly true)]
    (replay/create-demo-event! "gene@example.com" nil)))

;; --- The guard --------------------------------------------------------------

(deftest replay-guard-test
  (let [real (events/create-event! {:name "A Real Conference" :slug "real-one"
                                    :tz "UTC"} "gene@example.com")
        demo (demo-event!)]

    (testing "a normal event is NOT a replay target"
      (is (not (replay/replay-event? real))))

    (testing "the demo event the simulator created IS"
      (is (replay/replay-event? demo))
      (is (str/starts-with? (:slug demo) "aie-replay-")))

    (testing "every entry point refuses a non-replay event"
      (doseq [f [#(replay/tick! real 1)
                 #(replay/start! real 60 nil)
                 #(replay/skip-to-end! real)]]
        (is (= :not-a-replay-event
               (:type (ex-data (try (f) nil (catch clojure.lang.ExceptionInfo e e))))))))

    (testing "and the real event was not touched"
      (is (zero? (count (store/submissions-for-event (:id real))))))

    (testing "the guard survives a reload — it is a stored fact, not runtime state"
      (store/load!)
      (is (replay/replay-event? (events/event-by-slug (:slug demo))))
      (is (not (replay/replay-event? (events/event-by-slug "real-one")))))))

(deftest demo-event-setup-test
  (let [demo (demo-event!)
        committee (first (events/committees-for-event (:id demo)))
        members (committees/members-for-committee (:id committee))]
    (testing "the demo event comes with the three PC members"
      (is (= 3 (count members)))
      (is (= #{"genek@itrevolution.net" "annp@itrevolution.net" "alex@itrevolution.net"}
             (set (map :email members)))))
    (testing "its CFP is open, so submissions can actually be played in"
      (is (= :open (cfp-scheduler-killer.submissions/cfp-state demo))))))

;; --- Ticking through the REAL mutations -------------------------------------

(deftest replay-tick-test
  (with-redefs [replay/load-corpus (constantly tiny-corpus)
                replay/corpus-available? (constantly true)]
    (let [demo (demo-event!)]
      ;; prime the run state the way start! would
      (swap! replay/runs assoc (:id demo)
             {:idx 0 :refs {} :day 0 :status :playing
              :speed 60 :corpus tiny-corpus :started-at (System/currentTimeMillis)})

      (testing "nothing has happened yet"
        (is (zero? (:idx (replay/progress (:id demo)))))
        (is (zero? (count (store/submissions-for-event (:id demo))))))

      (testing "a two-entry tick creates two real submissions"
        (replay/tick! demo 2)
        (let [p (replay/progress (:id demo))]
          (is (= 2 (:idx p)))
          (is (= 2 (:submissions p))))
        (is (= 2 (count (store/submissions-for-event (:id demo)))))
        (is (= #{"Shipping agents in production" "Evals that actually caught something"}
               (set (map #(get-in % [:answers :talk-title])
                         (store/submissions-for-event (:id demo)))))))

      (testing "they went through the REAL create path — same events, same shape"
        (let [types (mapv :type (store/log-for-event (:id demo)))]
          (is (= 2 (count (filter #(= "submission.created" %) types))))
          (is (some #{"person.created"} types))
          ;; the confirmation letter the real path sends
          (is (some #{"email.queued"} types))))

      (testing "ratings and comments land on the right submission, by the right person"
        (replay/tick! demo 4)
        (let [a (first (filter #(= "Shipping agents in production"
                                   (get-in % [:answers :talk-title]))
                               (store/submissions-for-event (:id demo))))
              stats (reviews/submission-stats (:id a))]
          (is (= 1 (:n stats)))
          (is (= 4.5 (:mean stats)))
          (is (= "Gene Kim" (:name (store/person-by-id
                                     (:person-id (first (:ratings stats)))))))
          (is (= ["The rollback story alone is worth the slot."]
                 (mapv :body (:comments stats))))
          (is (= "Ann Perry" (:name (store/person-by-id
                                      (:person-id (first (:comments stats)))))))))

      (testing "a status entry moves it through the real status mutation"
        (replay/tick! demo 5)
        (let [a (first (filter #(= "Shipping agents in production"
                                   (get-in % [:answers :talk-title]))
                               (store/submissions-for-event (:id demo))))]
          (is (= "Accept Queue" (:status a)))))

      (testing "the run reports itself finished-ish and is idempotent past the end"
        (is (= 5 (:idx (replay/progress (:id demo)))))
        (replay/tick! demo 99)
        (is (= 5 (:idx (replay/progress (:id demo)))))
        (is (= 2 (count (store/submissions-for-event (:id demo)))))))))

(deftest skip-to-end-test
  (with-redefs [replay/load-corpus (constantly tiny-corpus)
                replay/corpus-available? (constantly true)]
    (let [demo (demo-event!)]
      (replay/skip-to-end! demo)
      (testing "everything played"
        (let [p (replay/progress (:id demo))]
          (is (= 5 (:idx p)))
          (is (= :done (:status p)))
          (is (= 2 (:submissions p)))
          (is (= 3 (:reviews p)))))
      (testing "and the world really changed"
        (is (= 2 (count (store/submissions-for-event (:id demo)))))
        (is (= 1 (count (filter #(= "Accept Queue" (:status %))
                                (store/submissions-for-event (:id demo))))))))))

(deftest missing-corpus-is-honest-test
  (with-redefs [replay/load-corpus (constantly nil)
                replay/corpus-available? (constantly false)]
    (let [demo (with-redefs [replay/load-corpus (constantly tiny-corpus)]
                 (replay/create-demo-event! "gene@example.com" nil))]
      (testing "with no corpus, starting does nothing rather than inventing filler"
        (is (nil? (replay/start! demo 60 nil)))
        (is (zero? (count (store/submissions-for-event (:id demo)))))))))

;; --- Time travel ------------------------------------------------------------

(defn- setup-history! []
  (let [event (events/create-event!
                {:name "Time Travel Summit" :slug "tt-test" :tz "UTC"
                 :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
                 :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
                "gene@example.com")
        cid (:id (first (events/committees-for-event (:id event))))
        gene (committees/add-member! cid {:name "Gene Kim" :email "gene@example.com"
                                          :role "chair"} "kaocha")
        ff (:fields (events/form-for-event (:id event)))
        mk (fn [title email]
             (let [params {:answer-talk-title title :answer-abstract "A."
                           :answer-session-format "Experience Report"
                           :answer-org-size ">10,000" :answer-industry "Insurance"
                           :answer-ai-transformation-history "x"
                           :answer-measurable-outcomes "y"
                           :speaker-name "S" :speaker-email email
                           :speaker-title "VP" :speaker-org "Co" :speaker-bio "B"}]
               (sub/create-submission! event (sub/parse-answers ff params)
                                       (sub/parse-speaker params) "form" "kaocha")))
        a (mk "First talk" "a@example.com")
        _ (Thread/sleep 5)
        b (mk "Second talk" "b@example.com")
        _ (Thread/sleep 5)
        _ (reviews/set-rating! (:id a) (:person-id gene) 5.0 "gene@example.com")]
    {:event event :a a :b b :gene gene}))

(deftest fold-prefix-purity-test
  (let [{:keys [event a b]} (setup-history!)
        all (store/read-events)
        ats (mapv :at all)]

    (testing "folding the whole log reproduces the live state exactly"
      (is (= (:submissions @store/state)
             (:submissions (store/state-as-of (last ats) all)))))

    (testing "a prefix reproduces an EARLIER world"
      ;; the moment just after the first submission was created
      (let [cutoff (str (.minusNanos (java.time.Instant/parse (:at (first (filter #(= (:id b) (get-in % [:payload :id])) all)))) 1))
            past (store/state-as-of cutoff all)]
        (is (= 1 (count (:submissions past))))
        (is (= "First talk" (get-in (first (vals (:submissions past)))
                                    [:answers :talk-title])))
        (is (empty? (:ratings past)) "the rating had not happened yet")))

    (testing "folding is pure — the live atom is untouched"
      (let [before @store/state]
        (store/state-as-of (first ats) all)
        (is (= before @store/state))
        (is (= 2 (count (store/submissions-for-event (:id event)))))))

    (testing "an empty prefix is an empty world, not an error"
      (is (= store/empty-state
             (update (store/state-as-of "1970-01-01T00:00:00Z" all) :log vec))))))

(deftest as-of-is-read-only-test
  (let [{:keys [event]} (setup-history!)]
    (testing "writing from inside a time-travel render is refused"
      (binding [store/*as-of-state* (store/state-as-of "1970-01-01T00:00:00Z")]
        (is (= :read-only-as-of
               (:type (ex-data (try (store/append! {:type "x" :payload {}})
                                    nil
                                    (catch clojure.lang.ExceptionInfo e e))))))
        (is (= :read-only-as-of
               (:type (ex-data (try (store/append-all! [{:type "x" :payload {}}])
                                    nil
                                    (catch clojure.lang.ExceptionInfo e e))))))))

    (testing "and the log gained nothing"
      (is (not-any? #(= "x" (:type %)) (store/read-events))))))

(deftest board-time-travel-test
  (let [{:keys [event b]} (setup-history!)
        handler (server/create-app)
        cookie (let [t (auth/issue-token! "gene@example.com")
                     r (handler (mock/request :get (str "/auth/" t)))]
                 (first (str/split (first (get-in r [:headers "Set-Cookie"])) #";")))
        as #(mock/header % "cookie" cookie)
        board (fn [qs] (:body (handler (as (mock/request :get (str "/events/tt-test/board" qs))))))
        log (fn [qs] (:body (handler (as (mock/request :get (str "/events/tt-test/log" qs))))))]

    (testing "now: both talks and the rating"
      (let [body (board "")]
        (is (str/includes? body "First talk"))
        (is (str/includes? body "Second talk"))
        (is (str/includes? body "★5"))))

    ;; The scrubber CHROME moved off the board into the dev strip (ENV=dev only,
    ;; views/dev-strip) on 2026-08-09; the log page still carries it always.
    ;; The board keeps the BEHAVIOUR — ?at-index re-folds what it renders.
    (testing "the slider spans the log and is offered on the log page"
      (let [body (log "")]
        (is (str/includes? body "CFP Time Machine"))
        (is (str/includes? body "type=\"range\""))
        (is (str/includes? body "recorded events"))))

    (testing "as of the very first event, the board is empty of talks"
      (let [body (board "?at-index=0")]
        (is (not (str/includes? body "First talk")))
        (is (not (str/includes? body "Second talk")))))

    (testing "and the log page says so, read-only, with a way back"
      (let [body (log "?at-index=0")]
        (is (str/includes? body "Viewing as of"))
        (is (str/includes? body "read-only"))
        (is (str/includes? body "Return to now"))))

    (testing "midway, the first talk exists and the second does not"
      (let [ats (mapv :at (store/log-for-event (:id event)))
            ;; index of the event that created the SECOND talk, minus one
            idx (dec (count (take-while #(not= (:id b) (get-in % [:payload :id]))
                                        (store/log-for-event (:id event)))))
            body (board (str "?at-index=" idx))]
        (is (str/includes? body "First talk"))
        (is (not (str/includes? body "Second talk")))
        (is (seq ats))))

    (testing "time-travelling did NOT change anything"
      (is (= 2 (count (store/submissions-for-event (:id event)))))
      (let [body (board "")]
        (is (str/includes? body "Second talk"))))

    (testing "the log page time-travels too"
      (let [body (:body (handler (as (mock/request :get "/events/tt-test/log?at-index=0"))))]
        (is (str/includes? body "Viewing as of"))
        (is (not (str/includes? body "Second talk")))))))

(deftest replay-routes-test
  (with-redefs [replay/load-corpus (constantly tiny-corpus)
                replay/corpus-available? (constantly true)]
    (let [handler (server/create-app)
          cookie (let [t (auth/issue-token! "starter@example.com")
                       r (handler (mock/request :get (str "/auth/" t)))]
                   (first (str/split (first (get-in r [:headers "Set-Cookie"])) #";")))
          as #(mock/header % "cookie" cookie)]

      (testing "the events list offers the demo door"
        ;; W2 (Gene, 2026-08-11): the newcomer welcome has no header buttons —
        ;; the demo offer moved into the ghost rail's replay door.
        (is (str/includes? (:body (handler (as (mock/request :get "/events"))))
                           "Replay a simulated CFP")))

      (testing "starting the demo creates a fresh replay event and lands on its page"
        (let [resp (handler (as (mock/request :post "/api/replay/start-demo")))
              location (get-in resp [:headers "Location"])]
          (is (= 303 (:status resp)))
          (is (re-matches #"/events/aie-replay-[a-z0-9]{6}/replay" location))

          (let [slug (second (re-find #"/events/([^/]+)/replay" location))
                page (:body (handler (as (mock/request :get location))))]
            (is (str/includes? page "Replay simulator"))
            (is (str/includes? page "▶ Play"))
            (is (str/includes? page "Skip to end"))
            (is (str/includes? page "3 weeks in 60 seconds"))
            (is (str/includes? page "id=\"replay-progress\""))

            (testing "skip-to-end plays the whole corpus through the real mutations"
              (let [resp (handler (as (mock/request :post
                                                    (str "/api/events/" slug "/replay/skip"))))
                    event (events/event-by-slug slug)]
                (is (= 303 (:status resp)))
                (is (= 2 (count (store/submissions-for-event (:id event)))))))

            (testing "and the board shows what the replay produced"
              (let [body (:body (handler (as (mock/request :get (str "/events/" slug "/board")))))]
                (is (str/includes? body "Shipping agents in production"))
                (is (str/includes? body "★4.5"))
                (is (str/includes? body "The rollback story alone is worth the slot.")))))))

      ;; Two different refusals guard an ordinary event, and they fire in this
      ;; order: since 2026-08-09 the GATE refuses a reviewer of one conference
      ;; at another's routes (403) before any handler runs; only a reviewer OF
      ;; that event reaches the replay guard itself (409).
      (let [ordinary (events/create-event! {:name "Ordinary" :slug "ordinary" :tz "UTC"} "x")]
        (testing "someone who does not review this event never reaches the guard"
          (is (= 403 (:status (handler (as (mock/request
                                             :post "/api/events/ordinary/replay/play")))))))
        (committees/add-member! (:id (first (events/committees-for-event (:id ordinary))))
                                {:name "Starter" :email "starter@example.com"
                                 :role "chair"}
                                "kaocha"))

      (testing "the replay endpoints refuse an ordinary event with 409"
        (doseq [path ["/api/events/ordinary/replay/play"
                      "/api/events/ordinary/replay/skip"]]
          (let [resp (handler (as (mock/request :post path)))]
            (is (= 409 (:status resp)) path)
            (is (str/includes? (str (:body resp)) "not a replay target"))))
        (is (zero? (count (store/submissions-for-event
                            (:id (events/event-by-slug "ordinary"))))))))))
