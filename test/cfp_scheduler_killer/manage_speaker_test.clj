(ns cfp-scheduler-killer.manage-speaker-test
  "The ORGANIZER managing an ACCEPTED speaker: editing the SESSION TITLE (on the
   submission) and the speaker BIO/HEADSHOT (on the person profile). This is
   the point of the Speaker-Management category — where the organizer engages accepted
   speakers on their behalf, distinct from the speaker's own portal.

   Two properties are asserted throughout:

     1. The edit FOLDS into the projection (the page shows the new value) — the
        organizer sees their change take effect.
     2. It rode the APPEND-ONLY log: the old value is still in the event stream,
        and a refused edit (a non-member) grew the log by NOTHING."
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.files :as files]
   [cfp-scheduler-killer.io.blob :as blob]
   [cfp-scheduler-killer.portal :as portal]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as sub]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock])
  (:import
   (java.time LocalDateTime)))

(use-fixtures :each
  with-temp-store
  (fn [f] (reset! auth/tokens {}) (f)))

;; --- The world: one event, Ann on its committee, an accepted talk -----------

(defn- setup!
  "An event Ann chairs with an accepted submission, plus a SEPARATE event that
   Mallory chairs. Mallory can sign in (she is on a roster) but is NOT a member
   of Ann's event — the exact case the per-event gate must refuse."
  []
  (let [event (events/create-event!
               {:name "Manage Test Summit" :slug "manage-test" :tz "America/New_York"
                :support-email "support@example.com" :location "Charlotte, NC"
                :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
                :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
               "kaocha")
        other-event (events/create-event!
                     {:name "Other Summit" :slug "other-summit" :tz "America/New_York"
                      :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
                      :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
                     "kaocha")
        other-cid (:id (first (events/committees-for-event (:id other-event))))
        _ (committees/add-member! other-cid {:name "Mallory Stranger"
                                             :email "mallory@example.com"
                                             :role "chair"} "kaocha")
        cid (:id (first (events/committees-for-event (:id event))))
        _ (committees/add-member! cid {:name "Ann Perry" :email "ann@example.com"
                                       :role "chair"} "kaocha")
        ff (:fields (events/form-for-event (:id event)))
        params {:answer-talk-title "Original title"
                :answer-abstract "Abstract."
                :answer-session-format "Experience Report"
                :answer-track "Developer Practices"
                :answer-org-size ">10,000"
                :answer-industry "Insurance"
                :answer-ai-transformation-history "2023."
                :answer-measurable-outcomes "Numbers."
                :speaker-name "Dana Speaker" :speaker-email "dana@example.com"
                :speaker-title "VP" :speaker-org "Meridian" :speaker-bio "Original bio."}
        submission (sub/create-submission! event (sub/parse-answers ff params)
                                           (sub/parse-speaker params) "form" "kaocha")]
    (reviews/set-status! (:id submission) "Accepted" "ann@example.com")
    {:event event :submission (store/submission-by-id (:id submission))}))

(defn- session-for
  [handler email]
  (let [token (auth/issue-token! email)
        resp (handler (mock/request :get (str "/auth/" token)))
        cookie (first (str/split (first (get-in resp [:headers "Set-Cookie"])) #";"))]
    (fn [req] (handler (mock/header req "cookie" cookie)))))

(defn- multipart-headshot
  [path title bio filename content-type content]
  (let [boundary "manage-speaker-headshot-boundary"
        field (fn [name value]
                (str "--" boundary "\r\n"
                     "Content-Disposition: form-data; name=\"" name "\"\r\n\r\n"
                     value "\r\n"))
        body (str (field "talk-title" title)
                  (field "bio" bio)
                  "--" boundary "\r\n"
                  "Content-Disposition: form-data; name=\"file\"; filename=\""
                  filename "\"\r\n"
                  "Content-Type: " content-type "\r\n\r\n"
                  content "\r\n--" boundary "--\r\n")]
    (-> (mock/request :post path)
        (mock/content-type (str "multipart/form-data; boundary=" boundary))
        (mock/body body))))

(defn- log-count []
  (store/await-sinks!)
  (count (store/read-events)))

(defn- titles-in-log
  "Every talk-title value that has ever appeared in the append-only log — proves
   the OLD title survives an edit."
  []
  (store/await-sinks!)
  (keep (fn [{:keys [type payload]}]
          (case type
            "submission.created" (get-in payload [:answers :talk-title])
            "submission.answers-updated" (get-in payload [:changes :talk-title])
            nil))
        (store/read-events)))

;; --- (1) The organizer edits the session title -------------------------------

(deftest organizer-edits-session-title-folds-and-appends-test
  (let [{:keys [event submission]} (setup!)
        person-id (:person-id (first (:speakers submission)))
        res (sub/update-session-title! (:id submission) "A sharper title" "ann@example.com")]

    (testing "the verb reports success"
      (is (:ok res)))

    (testing "the FOLD reflects the new title"
      (is (= "A sharper title"
             (get-in (store/submission-by-id (:id submission)) [:answers :talk-title]))))

    (testing "the OLD title is STILL in the log (append-only, nothing overwritten)"
      (is (contains? (set (titles-in-log)) "Original title")
          "the original title must survive as history")
      (is (contains? (set (titles-in-log)) "A sharper title")
          "the new title was appended, not substituted"))

    (testing "a blank title is refused and writes nothing"
      (let [before (log-count)
            r (sub/update-session-title! (:id submission) "   " "ann@example.com")]
        (is (not (:ok r)))
        (is (= before (log-count)) "a refused title must not grow the log")))

    (testing "an unchanged title is a no-op (no phantom append)"
      (let [before (log-count)
            r (sub/update-session-title! (:id submission) "A sharper title" "ann@example.com")]
        (is (:ok r))
        (is (:unchanged? r))
        (is (= before (log-count)))))

    ;; The bio edit rides the person-profile append path.
    (testing "editing the BIO updates the person profile via person.profile-updated"
      (let [r (portal/update-profile! person-id {:bio "A crisper bio."} "ann@example.com")]
        (is (:ok r))
        (is (= "A crisper bio."
               (get-in (store/person-by-id person-id) [:profile :bio]))
            "the person's profile bio folded to the new value")))))

;; --- (2) A non-member of the event cannot edit -------------------------------

(deftest non-member-cannot-manage-speaker-test
  (let [{:keys [event submission]} (setup!)
        raw (server/create-app)
        mallory (session-for raw "mallory@example.com")
        path (str "/api/events/manage-test/submissions/" (:id submission) "/manage")
        before (log-count)
        resp (mallory (-> (mock/request :post path)
                          (mock/header "accept" "text/html")
                          (mock/body {"talk-title" "Hijacked title"
                                      "bio" "Hijacked bio."})))]

    (testing "the write is REFUSED (403 — not a member of this event)"
      (is (= 403 (:status resp))
          (str path " answered " (:status resp) " — a stranger must be refused")))

    (testing "and the log did NOT grow — a 403 that still appended is not a refusal"
      (is (= before (log-count))))

    (testing "the folded title is untouched"
      (is (= "Original title"
             (get-in (store/submission-by-id (:id submission)) [:answers :talk-title]))))))

;; --- (3) The edit page renders current values --------------------------------

(deftest manage-page-renders-current-values-test
  (let [{:keys [event submission]} (setup!)
        person-id (:person-id (first (:speakers submission)))
        _ (sub/update-session-title! (:id submission) "Refined title" "ann@example.com")
        _ (portal/update-profile! person-id {:bio "Refined bio."} "ann@example.com")
        raw (server/create-app)
        ann (session-for raw "ann@example.com")
        body (str (:body (ann (mock/request
                               :get (str "/events/manage-test/submissions/"
                                         (:id submission) "/manage")))))]

    (testing "the page shows the CURRENT (folded) session title"
      (is (str/includes? body "Refined title")))

    (testing "the page shows the CURRENT (folded) speaker bio"
      (is (str/includes? body "Refined bio")))

    (testing "and it names the speaker being managed"
      (is (str/includes? body "Dana Speaker")))

    (testing "the organizer can choose a replacement headshot photo"
      (is (str/includes? body "Upload a new headshot")))))

(deftest organizer-headshot-upload-updates-the-shared-speaker-profile-test
  (let [{:keys [event submission]} (setup!)
        person-id (:person-id (first (:speakers submission)))
        raw (server/create-app)
        ann (session-for raw "ann@example.com")
        path (str "/api/events/manage-test/submissions/" (:id submission) "/manage")]
    (binding [blob/*put-fn* (fn [_ storage-key] (str "memory://" storage-key))]
      (let [response (ann (multipart-headshot path "Original title" "Updated bio."
                                              "dana-headshot.png" "image/png"
                                              "png fixture bytes"))
            profile (:profile (store/person-by-id person-id))
            headshot-file (first (filter #(= "Headshot" (:kind %))
                                         (files/for-event (:id event))))
            page (:body (ann (mock/request
                               :get (str "/events/manage-test/submissions/"
                                         (:id submission) "/manage"))))]
        (is (= 303 (:status response)))
        (is (= "Updated bio." (:bio profile)))
        (is (str/includes? (:headshot-url profile) "/headshots/"))
        (is (= person-id (:person-id headshot-file)))
        (is (= "dana-headshot.png" (-> headshot-file :versions peek :filename)))
        (is (str/includes? page (str "src=\"" (:headshot-url profile) "\"")))))))
