(ns cfp-scheduler-killer.exports-test
  "Exports + REST API.

   Two gates carry this whole namespace, and both are tested as NEGATIVES,
   because the failure mode is silent leakage rather than a broken page:

     1. Only accepted-AND-informed sessions are published.
     2. Private fields never leave the building — token or no token."
  (:require
   [cfp-scheduler-killer.announce :as announce]
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.exports :as exports]
   [cfp-scheduler-killer.inform :as inform]
   [cfp-scheduler-killer.portal :as portal]
   [cfp-scheduler-killer.public-catalog :as public-catalog]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.schedule :as schedule]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as sub]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.data.json :as json]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock])
  (:import
   (java.io ByteArrayOutputStream)
   (java.time LocalDate LocalDateTime)
   (java.util Base64)
   (java.util.zip ZipEntry ZipOutputStream)))

(use-fixtures :each with-temp-store (fn [f] (reset! auth/tokens {}) (f)))

(def ^:private private-answer "PC eyes only: I have a scheduling conflict on day 2.")

(defn- captured-zip [members]
  (let [out (ByteArrayOutputStream.)]
    (with-open [zip (ZipOutputStream. out)]
      (doseq [[member body] members]
        (.putNextEntry zip (ZipEntry. member))
        (.write zip (.getBytes body "UTF-8"))
        (.closeEntry zip)))
    (.toByteArray out)))

;; INTENT-TEST: PRED-EXPORT-001
(deftest captured-export-predicates-are-exact-and-evidence-bound-test
  (let [zip-bytes (captured-zip [["talk-a/slides.pdf" "slides"]
                                 ["speaker-a/headshot.png" "photo"]])
        expected ["speaker-a/headshot.png" "talk-a/slides.pdf"]
        html-bytes (.getBytes (str "<main><article class='public-session-card'>A</article>"
                                   "<article class='public-session-card'>B</article></main>")
                              "UTF-8")
        json-bytes (.getBytes "{\"sessions\":[{\"id\":\"a\"},{\"id\":\"b\"}]}"
                              "UTF-8")]
    (testing "captured ZIP bytes must be valid and contain the exact expected members"
      (let [result (exports/latest-files-zip-verdict
                     {:zip-bytes zip-bytes :expected-members expected})]
        (is (true? (:verdict result)))
        (is (= expected (get-in result [:observed :members])))
        (is (= (.encodeToString (Base64/getEncoder) zip-bytes)
               (get-in result [:examined :zip-bytes-base64]))))
      (is (false? (:verdict
                    (exports/latest-files-zip-verdict
                      {:zip-bytes zip-bytes
                       :expected-members ["talk-a/slides.pdf" "headshot.png"]})))
          "a basename is not an exact archive-member match")
      (is (false? (:verdict
                    (exports/latest-files-zip-verdict
                      {:zip-bytes (.getBytes "not a zip" "UTF-8")
                       :expected-members []})))
          "present but malformed evidence is a product failure"))

    (testing "rendered cards must equal the sessions.json array length"
      (let [result (exports/sessions-card-count-verdict
                     {:sessions-html-bytes html-bytes
                      :sessions-json-bytes json-bytes})]
        (is (true? (:verdict result)))
        (is (= {:rendered-card-count 2 :sessions-json-count 2}
               (:observed result)))
        (is (= (.encodeToString (Base64/getEncoder) html-bytes)
               (get-in result [:examined :sessions-html-bytes-base64]))))
      (is (false? (:verdict
                    (exports/sessions-card-count-verdict
                      {:sessions-html-bytes html-bytes
                       :sessions-json-bytes (.getBytes
                                              "{\"sessions\":[{\"id\":\"a\"}]}"
                                              "UTF-8")})))))

    (testing "missing captures are measurement gaps, never false product verdicts"
      (is (= {:verdict :cannot-judge :missing [:zip-bytes]}
             (select-keys
               (exports/latest-files-zip-verdict
                 {:expected-members expected})
               [:verdict :missing])))
      (is (= {:verdict :cannot-judge :missing [:expected-members]}
             (select-keys
               (exports/latest-files-zip-verdict {:zip-bytes zip-bytes})
               [:verdict :missing])))
      (is (= {:verdict :cannot-judge :missing [:sessions-json-bytes]}
             (select-keys
               (exports/sessions-card-count-verdict
                 {:sessions-html-bytes html-bytes})
               [:verdict :missing]))))))

;; The hold-the-date banner VEVENT always leads calendar-ics (Gene ratified
;; 2026-08-11) — a stable UID ending in this suffix, independent of any
;; session. Assertions about SESSION vevents filter it out by that UID.
;; RFC 5545 folds lines at 75 octets (CRLF + a leading space), and the UID
;; line is long enough to fold mid-suffix — so unfold before matching.
(def ^:private hold-the-date-suffix "-hold-the-date@cfp-scheduler-killer.local")

(defn- unfold-ics [ics]
  (str/replace ics "\r\n " ""))

(defn- vevent-blocks [ics]
  (->> (str/split (unfold-ics ics) #"(?=BEGIN:VEVENT)")
       (filter #(str/starts-with? % "BEGIN:VEVENT"))))

(defn- session-vevent-count [ics]
  (count (remove #(str/includes? % hold-the-date-suffix) (vevent-blocks ics))))

(defn- setup!
  "One event with four submissions in four different states — published,
   accepted-but-untold, pending, and declined."
  []
  (let [event (events/create-eais-event!
                {:name "Export Test Summit" :slug "export-test" :tz "America/New_York"
                 :starts-on (LocalDate/of 2026 10 14) :ends-on (LocalDate/of 2026 10 15)
                 :location "Charlotte, NC" :website-url "https://example.com/eais"
                 :support-email "support@example.com"
                 :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
                 :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)
                 :presenter-visibility-mode "visible"}
                "kaocha")
        cid (:id (first (events/committees-for-event (:id event))))
        _ (committees/add-member! cid {:name "Gene Kim" :email "gene@example.com"
                                       :role "chair"} "kaocha")
        ff (:fields (events/form-for-event (:id event)))
        mk (fn [title email name*]
             (let [params {:answer-talk-title title
                           :answer-abstract (str "Abstract for " title ".")
                           :answer-track "Developer Practices"
                           :answer-session-format "Experience Report"
                           :answer-org-size ">10,000"
                           :answer-industry "Insurance"
                           :answer-ai-transformation-history "2023."
                           :answer-measurable-outcomes "Numbers."
                           :answer-notes-to-committee private-answer
                           :speaker-name name* :speaker-email email
                           :speaker-title "VP" :speaker-org "Meridian"
                           :speaker-bio "A bio." :speaker-linkedin "https://linkedin.com/in/x"}]
               (sub/create-submission! event (sub/parse-answers ff params)
                                       (sub/parse-speaker params) "form" "kaocha")))
        published (mk "Published talk" "pub@example.com" "Priya Raghavan")
        untold (mk "Accepted but untold" "untold@example.com" "Marcus Devlin")
        pending (mk "Still pending" "pending@example.com" "Dana Whitfield")
        declined (mk "Declined talk" "declined@example.com" "Alan Osei")]
    (reviews/set-status! (:id published) "Accepted" "gene@example.com")
    (reviews/set-status! (:id untold) "Accepted" "gene@example.com")
    (reviews/set-status! (:id declined) "Declined" "gene@example.com")
    ;; ONLY the first one is actually communicated.
    (inform/inform! event (store/submission-by-id (:id published)) "gene@example.com")
    ;; Inform queues the committee acceptance notification on an ordered sink.
    ;; Version assertions below need the completed setup fact boundary.
    (store/await-sinks!)
    {:event (events/event-by-slug "export-test")
     :published published :untold untold :pending pending :declined declined}))

;; --- Gate 1: published only -------------------------------------------------

(deftest published-gate-test
  (let [{:keys [event published untold pending declined]} (setup!)]

    (testing "published? needs BOTH accepted and informed"
      (is (exports/published? (store/submission-by-id (:id published))))
      (is (not (exports/published? (store/submission-by-id (:id untold))))
          "accepted, but the speaker has not been told")
      (is (not (exports/published? (store/submission-by-id (:id pending)))))
      (is (not (exports/published? (store/submission-by-id (:id declined))))))

    (testing "sessions.json contains ONLY the published one"
      (let [data (exports/sessions-json-data event)]
        (is (= 1 (get data "totalSessions")))
        (is (= ["Published talk"] (mapv #(get % "title") (get data "sessions"))))))

    (testing "and none of the other titles appear ANYWHERE in the payload"
      (let [blob (exports/->json (exports/sessions-json-data event))]
        (doseq [t ["Accepted but untold" "Still pending" "Declined talk"]]
          (is (not (str/includes? blob t)) (str t " must not be published")))))

    (testing "speakers.json is gated the same way"
      (let [blob (exports/->json (exports/speakers-json-data event))]
        (is (str/includes? blob "Priya Raghavan"))
        (doseq [n ["Marcus Devlin" "Dana Whitfield" "Alan Osei"]]
          (is (not (str/includes? blob n)) (str n " must not be published")))))

    (testing "the ics feed is gated the same way"
      (let [ics (exports/calendar-ics event)]
        (is (str/includes? ics "Published talk"))
        ;; One session VEVENT (the published talk) — the hold-the-date banner
        ;; leads the feed regardless of gating and is excluded here.
        (is (= 1 (session-vevent-count ics)))
        (is (str/includes? (unfold-ics ics) hold-the-date-suffix) "the banner is always present")
        (is (str/starts-with? (first (vevent-blocks ics)) "BEGIN:VEVENT")
            "the banner leads the feed")
        (is (str/includes? (first (vevent-blocks ics)) hold-the-date-suffix)
            "the FIRST vevent in the feed is the banner")
        (is (str/includes? (first (vevent-blocks ics)) "DTSTART;VALUE=DATE:"))
        (doseq [t ["Accepted but untold" "Still pending" "Declined talk"]]
          (is (not (str/includes? ics t))))))

    (testing "informing the second one publishes it — and only then"
      (inform/inform! event (store/submission-by-id (:id untold)) "gene@example.com")
      (let [data (exports/sessions-json-data (events/event-by-slug "export-test"))]
        (is (= 2 (get data "totalSessions")))
        (is (= #{"Published talk" "Accepted but untold"}
               (set (mapv #(get % "title") (get data "sessions")))))))))

(deftest explicit-content-readiness-gates-machine-publication-not-organizer-work
  (let [{:keys [event published untold]} (setup!)
        submission-id (:id published)
        actor "gene@example.com"]
    (inform/inform! event (store/submission-by-id (:id untold)) actor)
    (store/await-sinks!)
    (sub/set-content-status! submission-id "In review" actor)

    (testing "every machine-readable public surface withholds unapproved copy"
      (is (= "Draft"
             (sub/content-status (store/submission-by-id (:id untold))))
          "the second informed session still projects the default Draft state")
      (is (not (exports/published? (store/submission-by-id submission-id))))
      (is (zero? (get (exports/sessions-json-data event) "totalSessions")))
      (is (not (str/includes? (exports/->json (exports/speakers-json-data event))
                              "Priya Raghavan")))
      (is (not (str/includes? (exports/calendar-ics event) "Published talk")))
      (is (str/includes? (exports/llms-txt event "https://example.test")
                         "0 confirmed sessions"))
      (is (zero? (get (exports/api-sessions event nil false) "total"))))

    (testing "organizer projections keep the row available for remediation"
      (is (= 2 (get (exports/api-sessions event nil true) "total")))
      (is (= #{submission-id (:id untold)}
             (set (map :id (exports/published-sessions (:id event)))))))

    (testing "approval republishes only that stable entity"
      (sub/set-content-status! submission-id "Approved" actor)
      (is (exports/published? (store/submission-by-id submission-id)))
      (is (= [submission-id]
             (mapv #(get % "id")
                   (get (exports/sessions-json-data event) "sessions")))))))

;; --- Gate 2: private fields never leave --------------------------------------

(deftest private-field-never-exported-test
  (let [{:keys [event published]} (setup!)
        handler (server/create-app)
        token (get-in event [:settings :api-token])
        body-of (fn [req] (str (:body (handler req))))]

    (testing "the private answer IS stored (we're testing exclusion, not absence)"
      (is (= private-answer
             (get-in (store/submission-by-id (:id published)) [:answers :notes-to-committee]))))

    (testing "public-answers strips it, driven by the snapshot's :private flag"
      (let [pa (exports/public-answers (store/submission-by-id (:id published)))]
        (is (not (contains? pa :notes-to-committee)))
        (is (contains? pa :talk-title))))

    (testing "it is absent from every STATIC export"
      (doseq [path ["/events/export-test/exports/sessions.json"
                    "/events/export-test/exports/speakers.json"
                    "/events/export-test/exports/calendar.ics"
                    "/events/export-test/llms.txt"]]
        (is (not (str/includes? (body-of (mock/request :get path)) private-answer))
            (str path " leaked the private field"))))

    (testing "it is absent from the UNAUTHENTICATED API"
      (doseq [path ["/api/v1/events/export-test/sessions"
                    "/api/v1/events/export-test/speakers"
                    (str "/api/v1/events/export-test/submissions/" (:id published))]]
        (is (not (str/includes? (body-of (mock/request :get path)) private-answer))
            (str path " leaked the private field"))))

    (testing "and it is STILL absent with a valid token — a token is not consent"
      (doseq [path [(str "/api/v1/events/export-test/sessions?status=all&token=" token)
                    (str "/api/v1/events/export-test/submissions/" (:id published)
                         "?token=" token)]]
        (let [body (body-of (mock/request :get path))]
          (is (not (str/includes? body private-answer)) (str path " leaked with a token"))
          (is (not (str/includes? body "notes-to-committee"))
              "not even the key name"))))

    (testing "the authenticated call really did return the richer data"
      (let [data (json/read-str (body-of (mock/request
                                           :get (str "/api/v1/events/export-test/sessions?status=all&token=" token))))]
        (is (= 4 (get data "total")) "all four rows, including unpublished")))))

;; --- sessions.json shape ----------------------------------------------------

(deftest one-track-projection-feeds-every-public-surface-test
  (let [{:keys [event]} (setup!)
        html-track (:track (first (public-catalog/sessions event)))
        json-track (get (first (get (exports/sessions-json-data event) "sessions")) "track")
        api-track (get (first (get (exports/api-sessions event nil) "sessions")) "track")]
    (testing "the organizer's explicit track wins over the industry fallback"
      (is (= "Developer Practices" html-track))
      (is (not= "Insurance" html-track)))

    (testing "HTML, sessions.json, and the REST API agree"
      (is (= html-track json-track api-track)))))

(deftest sessions-json-shape-test
  (let [{:keys [event]} (setup!)
        data (exports/sessions-json-data event)]

    (testing "top-level keys match the probe doc exactly"
      (is (= #{"conference" "dates" "location" "website"
               "scheduleVersion" "totalSessions" "sessions"}
             (set (keys data)))))

    (testing "every key the probe doc has is still there, spelled the same"
      (let [probe #{"title" "description" "day" "time" "room"
                    "type" "track" "status" "speakers"}
            actual (set (keys (first (get data "sessions"))))]
        (is (every? actual probe))

        ;; The additions are ADDITIVE and enumerated. A consumer written against
        ;; ai.engineer's feed keeps working; a consumer written against ours
        ;; never has to fuzzy-match a human being. Pinning the difference (rather
        ;; than dropping the exact-match test) is what stops a third key from
        ;; drifting into someone else's published site unnoticed.
        (is (= #{"id" "speakerIds"} (set/difference actual probe)))))

    (testing "values are the plain strings ai.engineer's are — the ids are extra"
      (let [s (first (get data "sessions"))]
        (is (string? (get s "title")))
        (is (string? (get s "description")))
        (is (= "confirmed" (get s "status")))
        (is (= "session" (get s "type")))
        (is (vector? (get s "speakers")))
        (is (every? string? (get s "speakers")))
        (is (= ["Priya Raghavan"] (get s "speakers")))
        (is (string? (get s "id")))
        (is (= 1 (count (get s "speakerIds"))))
        (is (every? string? (get s "speakerIds")))))

    (testing "schedule fields are EMPTY rather than invented"
      (let [s (first (get data "sessions"))]
        (is (= "" (get s "day")))
        (is (= "" (get s "time")))
        (is (= "" (get s "room")))))

    (testing "the conference header carries the event's own facts"
      (is (= "Export Test Summit" (get data "conference")))
      (is (= "Charlotte, NC" (get data "location")))
      (is (= "https://example.com/eais" (get data "website")))
      (is (= "Oct 14–Oct 15, 2026" (get data "dates"))))

    (testing "it is valid JSON that round-trips"
      (is (= data (json/read-str (exports/->json data)))))))

(deftest schedule-version-test
  (let [{:keys [event published]} (setup!)
        v1 (exports/schedule-version (:id event))]
    (testing "the version is a positive integer"
      (is (pos-int? v1)))

    (testing "any change bumps it"
      (reviews/add-comment! (:id published)
                            (:id (store/person-by-email "gene@example.com"))
                            "A comment." "gene@example.com")
      (let [v2 (exports/schedule-version (:id event))]
        (is (> v2 v1))

        (testing "and it appears in the export"
          (is (= v2 (get (exports/sessions-json-data event) "scheduleVersion"))))

        (testing "reading it twice without a change does NOT bump it"
          (is (= v2 (exports/schedule-version (:id event)))))))))

;; --- calendar.ics -----------------------------------------------------------

(deftest ics-test
  (let [{:keys [event published]} (setup!)
        ics1 (exports/calendar-ics event)
        uid (exports/ics-uid (store/submission-by-id (:id published)))]

    (testing "well-formed envelope with CRLF line endings"
      (is (str/starts-with? ics1 "BEGIN:VCALENDAR\r\n"))
      (is (str/ends-with? ics1 "END:VCALENDAR\r\n"))
      (is (str/includes? ics1 "VERSION:2.0"))
      (doseq [calendar [(exports/calendar-ics event)
                        (exports/calendar-ics-for event [published])
                        (exports/submission-ics event published)]]
        (is (str/includes? calendar
                           "PRODID:-//Curtain Call//curtaincallcfp.com//EN"))
        (is (not (str/includes? calendar
                                "PRODID:-//cfp-scheduler-killer"))))
      (is (str/includes? ics1 "CALSCALE:GREGORIAN"))
      (is (not (re-find #"(?<!\r)\n" ics1)) "every newline is part of a CRLF"))

    (testing "the UID is stable and namespaced to us"
      (is (str/includes? ics1 (str "UID:" uid)))
      (is (str/ends-with? uid "@cfp-scheduler-killer.local"))
      (is (str/starts-with? uid (:id published))))

    (testing "DTEND is exclusive, so a 14–15 Oct event ends on the 16th"
      (is (str/includes? ics1 "DTSTART;VALUE=DATE:20261014"))
      (is (str/includes? ics1 "DTEND;VALUE=DATE:20261016")))

    (testing "the location comes from the event"
      (is (str/includes? ics1 "LOCATION:Charlotte\\, NC")
          "and the comma is escaped"))

    (testing "an edit keeps the SAME UID but bumps SEQUENCE — amend, never duplicate"
      (let [seq1 (exports/ics-sequence (store/submission-by-id (:id published)))
            res  (portal/update-answers! (:id published)
                                         {:answer-talk-title "Published talk (revised)"}
                                         "speaker")]
        (is (:ok res) (pr-str (:errors res)))
        (is (not (:unchanged? res)) "the edit must actually append a fact")
        (let [ics2 (exports/calendar-ics (events/event-by-slug "export-test"))
              seq2 (exports/ics-sequence (store/submission-by-id (:id published)))]
          (is (str/includes? ics2 (str "UID:" uid)) "the UID did not move")
          ;; Still one SESSION event, not two — the hold-the-date banner also
          ;; leads this feed and is excluded from the count.
          (is (= 1 (session-vevent-count ics2)) "still one event, not two")
          (is (> seq2 seq1) "SEQUENCE bumped")
          (is (str/includes? ics2 (str "SEQUENCE:" seq2)))
          (is (str/includes? ics2 "Published talk (revised)")))))))

(deftest issued-invite-uid-survives-title-amendment-test
  (let [{:keys [event published]} (setup!)
        before (exports/submission-ics event published)
        uid-of #(second (re-find #"(?m)^UID:([^\r\n]+)" %))
        sequence-of #(parse-long (second (re-find #"(?m)^SEQUENCE:(\d+)" %)))
        issued-uid (uid-of before)
        issued-sequence (sequence-of before)]
    (is (string? issued-uid) "the issued invitation has a durable identity")
    (is (:ok (portal/update-answers! (:id published)
                                     {:answer-talk-title "Amended after issue"}
                                     "speaker")))
    (let [amended-submission (store/submission-by-id (:id published))
          amended (exports/submission-ics
                    (events/event-by-slug "export-test")
                    amended-submission)]
      (is (= issued-uid (uid-of amended))
          "an amendment retains the UID already issued to calendar clients")
      (is (> (sequence-of amended) issued-sequence)
          "the retained UID carries a higher amendment sequence")
      (is (str/includes? amended "SUMMARY:Amended after issue")))))

(deftest ics-escaping-test
  (testing "the four special characters are escaped, per RFC 5545"
    (is (= "a\\, b" (exports/ics-escape "a, b")))
    (is (= "a\\; b" (exports/ics-escape "a; b")))
    (is (= "a\\\\b" (exports/ics-escape "a\\b")))
    (doseq [line-break ["\n" "\r" "\r\n"]]
      (let [escaped (exports/ics-escape (str "safe" line-break
                                             "ATTENDEE:mailto:x@example.com"))]
        (is (= "safe\\nATTENDEE:mailto:x@example.com" escaped))
        (is (not (re-find #"[\r\n]" escaped))
            "no input line break can become a calendar content line"))))

  (testing "long lines fold at 75 octets with a leading space"
    (let [folded (exports/fold-line (str "SUMMARY:" (apply str (repeat 200 "x"))))]
      (is (str/includes? folded "\r\n "))
      (is (every? #(<= (count %) 75) (str/split folded #"\r\n"))))))

(deftest unroomed-session-calendar-uses-event-location
  (let [{:keys [event published]} (setup!)]
    (schedule/place! event (:id published)
                     {:day "2026-10-14" :start "09:30" :room-id nil}
                     "gene@example.com")
    (let [ics (exports/calendar-ics (events/event-by-slug "export-test"))]
      (is (str/includes? ics "LOCATION:Charlotte\\, NC"))
      (is (not (str/includes? ics "no room yet"))
          "the conflict UI fallback is not a real calendar location"))))

;; --- llms.txt ---------------------------------------------------------------

(deftest llms-txt-test
  (let [{:keys [event]} (setup!)
        txt (exports/llms-txt event "https://cfp.example.com")]
    (testing "it names the event and links every export"
      (is (str/includes? txt "# Export Test Summit"))
      (is (str/includes? txt "Charlotte, NC"))
      (is (str/includes? txt "https://cfp.example.com/events/export-test/exports/sessions.json"))
      (is (str/includes? txt "https://cfp.example.com/events/export-test/exports/speakers.json"))
      (is (str/includes? txt "https://cfp.example.com/events/export-test/exports/calendar.ics"))
      (is (str/includes? txt "https://cfp.example.com/events/export-test/mcp"))
      (is (str/includes? txt "https://cfp.example.com/api/v1/events/export-test/review-policy"))
      (is (str/includes? txt "https://cfp.example.com/program/export-test"))
      (is (str/includes? txt "Open Graph/Twitter card"))
      (is (str/includes? txt "https://cfp.example.com/cfp/export-test")))

    (testing "it states the publication rule rather than leaving it implicit"
      (is (str/includes? txt "informed"))
      (is (str/includes? txt "1 confirmed session")))))

;; --- The API ----------------------------------------------------------------

(deftest api-auth-test
  (let [{:keys [event published untold]} (setup!)
        handler (server/create-app)
        token (get-in event [:settings :api-token])
        get* (fn [path & [hdrs]]
               (let [req (reduce-kv (fn [r k v] (mock/header r k v))
                                    (mock/request :get path)
                                    (or hdrs {}))]
                 (handler req)))]

    (testing "every event gets its OWN token at creation"
      (is (string? token))
      (let [other (events/create-event! {:name "Other" :slug "other" :tz "UTC"} "kaocha")]
        (is (not= token (get-in other [:settings :api-token])))))

    (testing "no token: the published program, and nothing else"
      (let [data (json/read-str (str (:body (get* "/api/v1/events/export-test/sessions"))))]
        (is (= 1 (get data "total")))
        (is (= ["Published talk"] (mapv #(get % "title") (get data "sessions"))))))

    (testing "asking for more WITHOUT a token is 401, not a silent downgrade"
      (let [resp (get* "/api/v1/events/export-test/sessions?status=all")]
        (is (= 401 (:status resp)))
        (is (str/includes? (str (:body resp)) "token"))))

    (testing "a WRONG token is also 401"
      (is (= 401 (:status (get* "/api/v1/events/export-test/sessions?status=all&token=nope")))))

    (testing "a valid token via ?token= returns everything"
      (let [resp (get* (str "/api/v1/events/export-test/sessions?status=all&token=" token))]
        (is (= 200 (:status resp)))
        (is (= 4 (get (json/read-str (str (:body resp))) "total")))))

    (testing "a valid token via Authorization: Bearer works identically"
      (let [resp (get* "/api/v1/events/export-test/sessions?status=all"
                       {"authorization" (str "Bearer " token)})]
        (is (= 200 (:status resp)))
        (is (= 4 (get (json/read-str (str (:body resp))) "total")))))

    (testing "status filtering by name, with a token"
      (let [data (json/read-str (str (:body (get* (str "/api/v1/events/export-test/sessions?status=Pending&token=" token)))))]
        (is (= 1 (get data "total")))
        (is (= "Still pending" (get (first (get data "sessions")) "title")))))

    (testing "one submission: published is public, unpublished needs a token"
      (is (= 200 (:status (get* (str "/api/v1/events/export-test/submissions/" (:id published))))))
      (is (= 401 (:status (get* (str "/api/v1/events/export-test/submissions/" (:id untold))))))
      (is (= 200 (:status (get* (str "/api/v1/events/export-test/submissions/" (:id untold)
                                     "?token=" token))))))

    (testing "the envelope is honest about not paginating"
      (let [data (json/read-str (str (:body (get* "/api/v1/events/export-test/sessions"))))]
        (is (false? (get-in data ["pagination" "paginated"])))))

    (testing "an unknown event is a JSON 404, not an HTML page"
      (let [resp (get* "/api/v1/events/nope/sessions")]
        (is (= 404 (:status resp)))
        (is (str/includes? (str (get-in resp [:headers "Content-Type"])) "application/json"))))))

(deftest exports-are-public-test
  (let [_ (setup!)
        handler (server/create-app)]
    (testing "exports need no login — that is what makes them exports"
      (doseq [path ["/events/export-test/exports/sessions.json"
                    "/events/export-test/exports/speakers.json"
                    "/events/export-test/exports/calendar.ics"
                    "/events/export-test/llms.txt"
                    "/api/v1/events/export-test/sessions"]]
        (let [resp (handler (mock/request :get path))]
          (is (= 200 (:status resp)) (str path " must be public"))
          (is (= "*" (get-in resp [:headers "Access-Control-Allow-Origin"]))))))

    (testing "but the organizer pages next to them are still gated"
      (is (= 302 (:status (handler (mock/request :get "/events/export-test")))))
      (is (= 302 (:status (handler (mock/request :get "/events/export-test/settings"))))))

    (testing "content types are right"
      (is (str/includes? (get-in (handler (mock/request :get "/events/export-test/exports/sessions.json"))
                                 [:headers "Content-Type"]) "application/json"))
      (is (str/includes? (get-in (handler (mock/request :get "/events/export-test/exports/calendar.ics"))
                                 [:headers "Content-Type"]) "text/calendar")))))

(deftest organizer-export-bundle-is-downloadable-test
  (let [{:keys [pending]} (setup!)
        handler (server/create-app)
        gene (store/person-by-email "gene@example.com")
        _ (reviews/set-rating! (:id pending) (:id gene) 4.0 "gene@example.com")
        _ (reviews/add-comment! (:id pending) (:id gene) "Strong proposal" "gene@example.com")
        token (auth/issue-token! "gene@example.com")
        login (handler (mock/request :get (str "/auth/" token)))
        cookie (first (str/split (first (get-in login [:headers "Set-Cookie"])) #";"))
        page (handler (mock/header (mock/request :get "/events/export-test/exports")
                                   "cookie" cookie))
        body (str (:body page))]
    (testing "the organizer page is the one place to find the bundle and API"
      (is (= 200 (:status page)))
      (doseq [path ["/events/export-test/exports/sessions.json"
                    "/events/export-test/exports/speakers.json"
                    "/events/export-test/exports/calendar.ics"
                    "/events/export-test/llms.txt"
                    "/events/export-test/exports/review-results.csv"
                    "/events/export-test/exports/review-results.json"
                    "/api/v1/events/export-test/docs"]]
        (is (str/includes? body path) path)))

    (testing "each artifact downloads with its ai.engineer-compatible filename"
      (doseq [[path filename]
              [["/events/export-test/exports/sessions.json" "sessions.json"]
               ["/events/export-test/exports/speakers.json" "speakers.json"]
               ["/events/export-test/exports/calendar.ics" "calendar.ics"]
               ["/events/export-test/llms.txt" "llms.txt"]]]
        (let [response (handler (mock/request :get path))]
          (is (= 200 (:status response)) path)
          (is (= (str "attachment; filename=\"" filename "\"")
                 (get-in response [:headers "Content-Disposition"]))
              path))))

    (testing "private review artifacts require the organizer session"
      (doseq [[path content-type filename]
              [["/events/export-test/exports/review-results.csv"
                "text/csv" "export-test-review-results.csv"]
               ["/events/export-test/exports/review-results.json"
                "application/json" "export-test-review-results.json"]]]
        (is (= 403 (:status (handler (mock/request :get path)))) path)
        (let [response (handler (mock/header (mock/request :get path)
                                             "cookie" cookie))]
          (is (= 200 (:status response)) path)
          (is (str/includes? (get-in response [:headers "Content-Type"])
                             content-type))
          (is (= (str "attachment; filename=\"" filename "\"")
                 (get-in response [:headers "Content-Disposition"])))
          (is (str/includes? (:body response) "Strong proposal")))))

    (testing "the linked Sessionboard-shaped API remains machine-readable"
      (doseq [path ["/api/v1/"
                    "/api/v1/events/export-test"
                    "/api/v1/events/export-test/sessions"
                    "/api/v1/events/export-test/speakers"
                    "/api/v1/events/export-test/schedule"
                    "/api/v1/events/export-test/rooms"]]
        (let [response (handler (mock/request :get path))]
          (is (= 200 (:status response)) path)
          (is (str/includes? (get-in response [:headers "Content-Type"])
                             "application/json")
              path))))))

;; --- Webhooks ---------------------------------------------------------------

(deftest webhook-registration-test
  (let [{:keys [event]} (setup!)]
    (testing "a webhook is registered as a stored event and folds into settings"
      (let [id (exports/register-webhook! event "https://hooks.example.com/cfp"
                                          ["submission.created"] "gene@example.com")
            fresh (events/event-by-slug "export-test")]
        (is (string? id))
        (is (= 1 (count (exports/webhooks-for fresh))))
        (is (= "https://hooks.example.com/cfp" (:url (first (exports/webhooks-for fresh)))))
        (is (= ["submission.created"] (:types (first (exports/webhooks-for fresh)))))

        (testing "and it survives a reload — it is data, not runtime config"
          (store/load!)
          (is (= 1 (count (exports/webhooks-for (events/event-by-slug "export-test"))))))

        (testing "removing it folds it back out"
          (exports/remove-webhook! (events/event-by-slug "export-test") id "gene@example.com")
          (is (empty? (exports/webhooks-for (events/event-by-slug "export-test")))))))

    (testing "a junk URL is refused rather than stored"
      (is (nil? (exports/register-webhook! event "not-a-url" [] "gene@example.com")))
      (is (empty? (exports/webhooks-for (events/event-by-slug "export-test")))))))

(deftest webhook-delivery-test
  (let [{:keys [event pending]} (setup!)
        delivered (atom [])]
    ;; Stand in for the network: the registry calls deliver-sink!, we record.
    (with-redefs [store/deliver-sink! (fn [sink evt] (swap! delivered conj [sink evt]))]
      (exports/register-webhook! event "https://hooks.example.com/cfp"
                                 ["submission.status-changed"] "gene@example.com")
      (reset! delivered [])
      (reviews/set-status! (:id pending) "Accept Queue" "gene@example.com")
      (Thread/sleep 350)

      (testing "the event's own webhook fires for a matching type"
        ;; Other default sinks (e.g. :acceptance-email) also receive this
        ;; status-changed event and no-op; the webhook is the one we asserted on.
        (let [webhooks (filter #(= :webhook (:type (first %))) @delivered)]
          (is (= 1 (count webhooks)))
          (is (= "https://hooks.example.com/cfp" (:url (first (first webhooks)))))
          (is (= "submission.status-changed" (:type (second (first webhooks)))))))

      (testing "a non-matching type does not fire it"
        (reset! delivered [])
        (reviews/add-comment! (:id pending)
                              (:id (store/person-by-email "gene@example.com"))
                              "Hi" "gene@example.com")
        (Thread/sleep 300)
        (is (empty? (filter #(= :webhook (:type (first %))) @delivered))))

      (testing "deliveries are recorded for the Settings page"
        (is (seq @store/deliveries))
        (is (some #(= "submission.status-changed" (:event-type %)) @store/deliveries))))))

(deftest settings-page-test
  (let [{:keys [event]} (setup!)
        handler (server/create-app)
        token-str (get-in event [:settings :api-token])
        login (fn []
                (let [t (auth/issue-token! "gene@example.com")
                      r (handler (mock/request :get (str "/auth/" t)))]
                  (first (str/split (first (get-in r [:headers "Set-Cookie"])) #";"))))
        cookie (login)
        body (str (:body (handler (mock/header (mock/request :get "/events/export-test/settings")
                                               "cookie" cookie))))]

    (testing "it shows export URLs, a masked token, a copy action and endpoints"
      (is (str/includes? body "exports/sessions.json"))
      (is (str/includes? body "exports/calendar.ics"))
      (is (str/includes? body "llms.txt"))
      (is (not (str/includes? body token-str))
          "the full legacy token must not enter Settings HTML")
      (is (str/includes? body (exports/key-prefix token-str)))
      (is (str/includes? body "Copy API token"))
      (is (str/includes? body "$CURTAIN_CALL_API_KEY"))
      (is (str/includes? body "/api/v1/events/export-test/sessions"))
      (is (str/includes? body "href=\"/events/export-test/details\""))
      (is (str/includes? body "Edit event details")))

    (testing "it states the two rules in the UI, not just in the code"
      (is (str/includes? body "published program only"))
      (is (str/includes? body "Private fields are never returned")))

    (testing "it is honest that the delivery list is not durable"
      (is (str/includes? body "forgotten on restart")))

    (testing "it shows delivery diagnostics only for this event"
      (reset! store/deliveries
              [{:at (store/now-iso) :event-id "another-event"
                :event-type "submission.created" :url "https://foreign.example/hook"
                :ok false :error "foreign failure" :ms 8}
               {:at (store/now-iso) :event-id (:id event)
                :event-type "submission.created" :url "https://local.example/hook"
                :ok true :ms 5}])
      (let [scoped-body (str (:body (handler
                                      (mock/header
                                        (mock/request :get "/events/export-test/settings")
                                        "cookie" cookie))))]
        (is (str/includes? scoped-body "https://local.example/hook"))
        (is (not (str/includes? scoped-body "https://foreign.example/hook")))
        (is (not (str/includes? scoped-body "foreign failure")))))

    (testing "adding a webhook through the form works"
      (handler (mock/header (mock/request :post "/api/events/export-test/webhooks/add"
                                          {"url" "https://hooks.example.com/x"
                                           "types" "submission.created, submission.notified"})
                            "cookie" cookie))
      (let [ws (exports/webhooks-for (events/event-by-slug "export-test"))]
        (is (= 1 (count ws)))
        (is (= ["submission.created" "submission.notified"] (:types (first ws))))))))

;; --- The anti-Sessionize API (bd vi9) ---------------------------------------
;;
;; The evidence these tests defend: ask three ITRev systems how many talks Jason
;; Cox has given and you get 6, 9 and 12 (docs/research/post-conference-corpus-
;; survey.md), because every join in the estate is a fuzzy match on a display
;; name. So the assertion that matters is not "an id field exists" — it is that
;; the id on one side of a join EQUALS the id on the other side, in the same
;; payload, with no string comparison in between.

(defn- get* [handler path & [hdrs]]
  (handler (reduce-kv (fn [r k v] (mock/header r k v))
                      (mock/request :get path)
                      (or hdrs {}))))

(defn- post* [handler path & [hdrs]]
  (handler (reduce-kv (fn [r k v] (mock/header r k v))
                      (mock/request :post path)
                      (or hdrs {}))))

(defn- json* [handler path & [hdrs]]
  (json/read-str (str (:body (get* handler path hdrs)))))

(deftest legacy-speaker-snapshots-retain-public-api-joins-test
  (let [{:keys [event published]} (setup!)
        submission-id (:id published)
        person-id (:person-id (first (:speakers (store/submission-by-id submission-id))))
        handler (server/create-app)]
    (swap! store/state update-in [:submissions submission-id :speakers]
           #(mapv (fn [speaker] (dissoc speaker :person-id)) %))
    (let [sessions (get (json* handler "/api/v1/events/export-test/sessions") "sessions")
          speakers (get (json* handler "/api/v1/events/export-test/speakers") "speakers")
          speaker (first speakers)]
      (is (= (str person-id) (get speaker "id")))
      (is (= [submission-id] (get speaker "sessionIds")))
      (is (= [(str person-id)] (get (first sessions) "speakerIds")))
      (is (= 200 (:status (get* handler
                                (str "/api/v1/events/export-test/speakers/" person-id))))))))

(deftest public-read-api-never-emits-null-speaker-ids-test
  (let [{:keys [published]} (setup!)
        submission-id (:id published)
        handler (server/create-app)]
    ;; Imported legacy snapshots may have neither a canonical person id nor an
    ;; email that can recover one. Every public projection must use the same
    ;; deterministic fallback id rather than exposing null/blank on this row.
    (swap! store/state update-in [:submissions submission-id :speakers]
           #(mapv (fn [speaker] (dissoc speaker :person-id :email)) %))
    (let [session (-> (json* handler "/api/v1/events/export-test/sessions")
                      (get "sessions") first)
          speaker (-> (json* handler "/api/v1/events/export-test/speakers")
                      (get "speakers") first)
          embedded-speaker-ids (mapv #(get % "id") (get session "speakers"))]
      (is (every? seq embedded-speaker-ids)
          "public session speaker objects must never expose a null or blank id")
      (is (= (get session "speakerIds") embedded-speaker-ids)
          "embedded speaker ids must close the same stable join")
      (is (= 200 (:status (get* handler
                                (str "/api/v1/events/export-test/speakers/"
                                     (get speaker "id")))))))))

(deftest speaker-publication-api-round-trips-through-public-read-api-test
  (let [{:keys [event]} (setup!)
        person (announce/create-announced-speaker!
                 (:id event)
                 {:name "API Toggle Speaker"
                  :email "api-toggle@example.com"
                  :org "Toggle Systems"
                  :title "CTO"
                  :bio "Published through the API."
                  :announce? true}
                 "gene@example.com")
        event (events/event-by-slug "export-test")
        handler (server/create-app)
        person-id (str (:id person))
        collection-path "/api/v1/events/export-test/speakers"
        publication-path (str collection-path "/" person-id)
        auth-h {"authorization" (str "Bearer " (get-in event [:settings :api-token]))}
        speaker-by-id (fn [headers]
                        (->> (get (json* handler
                                         (str collection-path
                                              (when headers "?status=all"))
                                         headers)
                                  "speakers")
                             (some #(when (= person-id (get % "id")) %))))]
    (testing "a public caller cannot mutate publication state"
      (is (true? (get (speaker-by-id nil) "published")))
      (is (= 401 (:status (post* handler (str publication-path "/unpublish"))))))
    (testing "unpublish appends a fact and removes the speaker from public reads"
      (is (= 204 (:status (post* handler (str publication-path "/unpublish") auth-h))))
      (is (nil? (speaker-by-id nil)))
      (is (false? (get (speaker-by-id auth-h) "published")))
      (is (= 404 (:status (get* handler publication-path))))
      (is (= 200 (:status (get* handler publication-path auth-h))))
      (is (false? (get-in (json* handler publication-path auth-h)
                          ["speaker" "published"]))))
    (testing "publish restores the same stable public identity"
      (is (= 204 (:status (post* handler (str publication-path "/publish") auth-h))))
      (is (true? (get (speaker-by-id nil) "published")))
      (is (= 200 (:status (get* handler publication-path))))
      (is (= ["speaker.unpublished" "speaker.published"]
             (->> (store/log-for-event (:id event))
                  (filter #(#{"speaker.published" "speaker.unpublished"} (:type %)))
                  (mapv :type))))
      (is (every? #(str/starts-with? (:actor %) "api:organizer:")
                  (->> (store/log-for-event (:id event))
                       (filter #(#{"speaker.published" "speaker.unpublished"} (:type %)))))))))

(deftest stable-ids-close-every-join-test
  (let [{:keys [event untold]} (setup!)
        _ (inform/inform! event (store/submission-by-id (:id untold)) "gene@example.com")
        event (events/event-by-slug "export-test")
        handler (server/create-app)]

    (testing "sessions.json and speakers.json join on ids, not on names"
      (let [sessions (get (exports/sessions-json-data event) "sessions")
            speakers (get (exports/speakers-json-data event) "speakers")
            speaker-ids (set (map #(get % "id") speakers))
            session-ids (set (map #(get % "id") sessions))]
        (is (= 2 (count sessions)))
        (is (every? #(seq (get % "id")) sessions))
        (is (every? #(seq (get % "speakerIds")) sessions))
        (doseq [s sessions]
          (is (every? speaker-ids (get s "speakerIds"))
              "a session's speakerIds must resolve inside the same payload's speakers"))
        (doseq [sp speakers]
          (is (every? session-ids (get sp "sessionIds"))
              "and the join closes from the speaker's side too"))))

    (testing "the ids are the SAME ids the REST API returns"
      (let [api (json* handler "/api/v1/events/export-test/sessions")
            file (exports/sessions-json-data event)]
        (is (= (set (map #(get % "id") (get file "sessions")))
               (set (map #(get % "id") (get api "sessions"))))
            "one entity, one id, whichever door you came through")))

    (testing "a person id survives a second talk — one speaker, two sessions"
      (let [ff (:fields (events/form-for-event (:id event)))
            second-talk (sub/create-submission!
                          event
                          (sub/parse-answers ff {:answer-talk-title "Priya's second talk"
                                                 :answer-abstract "More."
                                                 :answer-session-format "Experience Report"
                                                 :answer-org-size ">10,000"
                                                 :answer-industry "Insurance"
                                                 :answer-ai-transformation-history "2023."
                                                 :answer-measurable-outcomes "Numbers."})
                          (sub/parse-speaker {:speaker-name "Priya Raghavan"
                                              :speaker-email "pub@example.com"
                                              :speaker-title "VP" :speaker-org "Meridian"})
                          "form" "kaocha")]
        (reviews/set-status! (:id second-talk) "Accepted" "gene@example.com")
        (inform/inform! (events/event-by-slug "export-test")
                        (store/submission-by-id (:id second-talk)) "gene@example.com")
        (let [speakers (get (exports/speakers-json-data (events/event-by-slug "export-test"))
                            "speakers")
              priya (first (filter #(= "Priya Raghavan" (get % "name")) speakers))]
          (is (= 2 (count speakers)) "still one ROW per person, not one per talk")
          (is (= 2 (count (get priya "sessionIds")))
              "two talks, one person id — this is the 6/9/12 bug, fixed")
          (is (= 2 (count (get priya "sessions")))))))))

(deftest public-json-exports-close-legacy-co-speaker-joins-test
  (let [event {:id "event-legacy-joint" :name "Legacy Joint Summit"
               :slug "legacy-joint-summit"}
        submission {:id "session-legacy-joint"
                    :event-id (:id event)
                    :answers {:talk-title "Two speakers, one stable join"
                              :abstract "A joint session."}
                    :speakers [{:person-id "person-primary"
                                :name "Primary Speaker"
                                :email "primary@example.com"}
                               {:name "Legacy Co-speaker"}]}
        export-data
        (fn []
          (with-redefs [exports/publishable-sessions (constantly [submission])]
            {:sessions (get (exports/sessions-json-data event) "sessions")
             :speakers (get (exports/speakers-json-data event) "speakers")}))
        first-export (export-data)
        session-row (first (:sessions first-export))
        speaker-rows (:speakers first-export)
        session-speaker-ids (get session-row "speakerIds")
        exported-speaker-ids (mapv #(get % "id") speaker-rows)]
    (testing "every submitted speaker receives a nonblank stable id"
      (is (= 2 (count speaker-rows)))
      (is (every? #(and (string? %) (not (str/blank? %))) exported-speaker-ids))
      (is (= 2 (count (distinct exported-speaker-ids)))))
    (testing "sessions.json and speakers.json close the join in both directions"
      (is (= (set exported-speaker-ids) (set session-speaker-ids)))
      (is (every? #(= [(:id submission)] (get % "sessionIds")) speaker-rows)))
    (testing "the fallback identity is stable across repeated exports"
      (is (= first-export (export-data))))))

(deftest public-speaker-endpoint-resolves-every-presenter-by-requested-id-test
  (let [{:keys [event]} (setup!)
        fields (:fields (events/form-for-event (:id event)))
        params {:answer-talk-title "Nadia and Tomas build together"
                :answer-abstract "A joint session."
                :answer-track "Developer Practices"
                :answer-session-format "Experience Report"
                :answer-org-size ">10,000"
                :answer-industry "Technology"
                :answer-ai-transformation-history "2024."
                :answer-measurable-outcomes "Two presenters, one result."
                :speaker-name "Nadia Primary"
                :speaker-email "nadia@example.com"
                :speaker-title "VP Engineering"
                :speaker-org "Joint Systems"
                :speaker-bio "Nadia leads engineering."
                :speaker-2-name "Tomas Co-speaker"
                :speaker-2-email "tomas@example.com"
                :speaker-2-title "Principal Architect"
                :speaker-2-org "Joint Systems"
                :speaker-2-bio "Tomas leads architecture."
                :speaker-2-role "Co-speaker"}
        submission (sub/create-submission!
                     event (sub/parse-answers fields params)
                     (sub/parse-speakers params) "form" "kaocha")
        _ (reviews/set-status! (:id submission) "Accepted" "gene@example.com")
        _ (inform/inform! event (store/submission-by-id (:id submission))
                          "gene@example.com")
        handler (server/create-app)]
    (doseq [{:keys [person-id name]} (:speakers (store/submission-by-id
                                                  (:id submission)))]
      (let [response (get* handler
                           (str "/api/v1/events/export-test/speakers/" person-id))
            body (json/read-str (str (:body response)))]
        (is (= 200 (:status response)))
        (is (= (str person-id) (get-in body ["speaker" "id"])))
        (is (= name (get-in body ["speaker" "name"]))
            (str "the per-speaker endpoint must return the requested presenter: "
                 person-id))))))

(deftest announced-speakers-are-addressable-without-phantom-sessions-test
  (let [{:keys [event]} (setup!)
        _ (events/announce-speaker! event
                                    {:name "Manual Speaker" :org "Example Org"
                                     :title "Fellow" :headshot-url "https://example.com/manual.jpg"}
                                    "gene@example.com")
        event-with-manual (events/event-by-slug "export-test")
        manual-id (get-in event-with-manual [:settings :announced-speakers 0 :id])
        _ (events/announce-speaker! event-with-manual
                                    {:name "Manual Speaker" :org "Updated Org"
                                     :title "Fellow" :headshot-url "https://example.com/manual.jpg"}
                                    "gene@example.com")
        _ (store/append! {:type "event.speaker-announced" :actor "legacy-import"
                          :event-id (:id event)
                          :payload {:event-id (:id event)
                                    :name "Legacy Speaker" :org "Archive Inc"
                                    :title "Historian" :headshot-url ""
                                    :at (store/now-iso)}})
        event (events/event-by-slug "export-test")
        handler (server/create-app)
        session-file (exports/sessions-json-data event)
        speaker-file (exports/speakers-json-data event)
        by-name (into {} (map (juxt #(get % "name") identity))
                      (get speaker-file "speakers"))]
    (testing "speaker announcements never invent sessions"
      (is (= 1 (get session-file "totalSessions")))
      (is (= ["Published talk"]
             (mapv #(get % "title") (get session-file "sessions"))))
      (is (not-any? #(= "tba" (get % "status")) (get session-file "sessions"))))

    (testing "new and legacy announcements receive stable public ids"
      (is (= manual-id (get-in by-name ["Manual Speaker" "id"])))
      (is (= "Updated Org" (get-in by-name ["Manual Speaker" "org"])))
      (doseq [name ["Manual Speaker" "Legacy Speaker"]]
        (let [id (get-in by-name [name "id"])
              again (->> (exports/speakers-json-data event)
                         (#(get % "speakers"))
                         (filter #(= name (get % "name")))
                         first
                         (#(get % "id")))]
          (is (seq id))
          (is (= id again))
          (is (= 200 (:status (get* handler
                                    (str "/api/v1/events/export-test/speakers/" id))))))))

    (testing "the file and REST collection expose the same ids"
      (is (= (set (map #(get % "id") (get speaker-file "speakers")))
             (set (map #(get % "id")
                       (get (json* handler "/api/v1/events/export-test/speakers")
                            "speakers"))))))))

(deftest api-read-surface-test
  (let [{:keys [event published untold]} (setup!)
        handler (server/create-app)
        token (get-in event [:settings :api-token])
        auth-h {"authorization" (str "Bearer " token)}]

    (testing "the service index is public and lists every endpoint it has"
      (let [idx (json* handler "/api/v1/")]
        (is (= "v1" (get idx "apiVersion")))
        (is (= (count exports/api-endpoints) (count (get idx "endpoints"))))
        (is (contains? (set (map #(get % "path") (get idx "endpoints")))
                       "/api/v1/events/{slug}/schedule"))))

    (testing "the event document is the discovery document"
      (let [e (json* handler "/api/v1/events/export-test")]
        (is (= (:id event) (get e "id")))
        (is (= "America/New_York" (get e "timezone")))
        (is (= "2026-10-14" (get e "startsOn")))
        (is (= "open" (get-in e ["cfp" "state"])))
        (is (str/ends-with? (get-in e ["links" "sessions"])
                            "/api/v1/events/export-test/sessions"))
        (is (str/ends-with? (get-in e ["links" "docs"])
                            "/api/v1/events/export-test/docs"))

        (testing "and how many proposals came in is the organizer's business"
          (is (nil? (get-in e ["counts" "submissions"])))
          (is (= 4 (get-in (json* handler "/api/v1/events/export-test" auth-h)
                           ["counts" "submissions"]))))))

    (testing "/submissions is the funnel and needs a token; /sessions is the program"
      (is (= 401 (:status (get* handler "/api/v1/events/export-test/submissions"))))
      (let [subs (json* handler "/api/v1/events/export-test/submissions" auth-h)]
        (is (= 4 (get subs "total")))
        (is (= #{"Accepted" "Pending" "Declined"}
               (set (map #(get % "status") (get subs "sessions")))))
        (testing "status and notified are separate facts, both visible"
          (let [row (first (filter #(= "Accepted but untold" (get % "title"))
                                   (get subs "sessions")))]
            (is (= "Accepted" (get row "status")))
            (is (false? (get row "notified")))
            (is (nil? (get row "notifiedAt")))
            (is (false? (get row "published")))))
        (testing "?status= narrows it"
          (is (= 1 (get (json* handler "/api/v1/events/export-test/submissions?status=Pending"
                               auth-h)
                        "total"))))))

    (testing "speakers carry ids; ?status=all needs a token and widens to every submitter"
      (let [pub (json* handler "/api/v1/events/export-test/speakers")]
        (is (= 1 (get pub "total")))
        (is (every? #(seq (get % "id")) (get pub "speakers"))))
      (is (= 401 (:status (get* handler "/api/v1/events/export-test/speakers?status=all"))))
      (is (= 4 (get (json* handler "/api/v1/events/export-test/speakers?status=all" auth-h)
                    "total"))))

    (testing "one speaker by person id — 404 for someone not on the program"
      (let [pid (get-in (json* handler "/api/v1/events/export-test/speakers")
                        ["speakers" 0 "id"])
            untold-pid (str (:person-id (first (:speakers (store/submission-by-id (:id untold))))))]
        (is (= 200 (:status (get* handler (str "/api/v1/events/export-test/speakers/" pid)))))
        (is (= "Priya Raghavan"
               (get-in (json* handler (str "/api/v1/events/export-test/speakers/" pid))
                       ["speaker" "name"])))
        (testing "an unannounced speaker is a 404, not a 401 — no probing"
          (is (= 404 (:status (get* handler (str "/api/v1/events/export-test/speakers/"
                                                 untold-pid)))))
          (is (= 200 (:status (get* handler (str "/api/v1/events/export-test/speakers/"
                                                 untold-pid) auth-h)))))
        (testing "and a well-formed id that is nobody is also a 404"
          (is (= 404 (:status (get* handler (str "/api/v1/events/export-test/speakers/"
                                                 "00000000-0000-0000-0000-000000000000"))))))))

    (testing "the schedule is public, joined by id, and names what is unplaced"
      (let [room (schedule/add-room! (events/event-by-slug "export-test") "Main Stage" "kaocha")
            _ (schedule/place! (events/event-by-slug "export-test") (:id published)
                               {:day "2026-10-14" :start "09:30" :room-id (:id room)}
                               "gene@example.com")
            sched (json* handler "/api/v1/events/export-test/schedule")
            room-ids (set (map #(get % "id") (get sched "rooms")))
            items (mapcat #(get % "items") (get sched "days"))]
        (is (= "America/New_York" (get sched "timezone")))
        (is (false? (get sched "locked")))
        (is (= 1 (count items)))
        (is (= (:id published) (get (first items) "sessionId")))
        (is (contains? room-ids (get (first items) "roomId"))
            "an item's roomId resolves against the rooms listed in the same payload")
        (is (= "9:30am" (get (first items) "start")))
        (is (= 570 (get (first items) "startMinute")))
        (is (empty? (get sched "unscheduled")) "the only published talk is placed")))

    (testing "rooms have stable ids of their own"
      (let [rooms (json* handler "/api/v1/events/export-test/rooms")]
        (is (= 1 (get rooms "total")))
        (is (= "Main Stage" (get-in rooms ["rooms" 0 "name"])))))

    (testing "the change feed is token-gated, monotonic, and carries ids ONLY"
      (is (= 401 (:status (get* handler "/api/v1/events/export-test/changes"))))
      (let [all (json* handler "/api/v1/events/export-test/changes" auth-h)
            n (get all "total")
            tail (json* handler (str "/api/v1/events/export-test/changes?since=" (- n 2))
                        auth-h)]
        (is (pos? n))
        (is (= n (get all "scheduleVersion")))
        (is (= (range 1 (inc n)) (map #(get % "seq") (get all "changes"))))
        (is (= 2 (get tail "total")) "?since= returns only what came after")
        (doseq [bad ["abc" "-1"]]
          (let [resp (get* handler
                           (str "/api/v1/events/export-test/changes?since=" bad)
                           auth-h)]
            (is (= 422 (:status resp)) (str "invalid cursor " bad " must be refused"))
            (is (str/includes? (:body resp) "non-negative integer"))))
        (testing "no payload bodies — a change feed says WHAT moved, never the contents"
          (is (= #{"seq" "type" "at" "id" "submissionId" "personId" "roomId"}
                 (set (keys (first (get all "changes"))))))
          (is (not (str/includes? (str (:body (get* handler
                                                    "/api/v1/events/export-test/changes"
                                                    auth-h)))
                                  "Published talk"))))))

    (testing "every new endpoint is a JSON 404 for an unknown event, never an HTML page"
      (doseq [p ["/api/v1/events/nope" "/api/v1/events/nope/schedule"
                 "/api/v1/events/nope/rooms" "/api/v1/events/nope/changes"
                 "/api/v1/events/nope/submissions"
                 "/api/v1/events/nope/speakers/00000000-0000-0000-0000-000000000000"]]
        (let [resp (get* handler p)]
          (is (= 404 (:status resp)) p)
          (is (str/includes? (str (get-in resp [:headers "Content-Type"]))
                             "application/json") p))))))

(deftest api-private-fields-never-leak-on-the-new-surfaces-test
  ;; Gate 2, re-run against every endpoint added by bd vi9. A new read endpoint
  ;; is a new way for a private answer to escape; the only defence that scales is
  ;; asserting the negative on each one.
  (let [{:keys [event]} (setup!)
        handler (server/create-app)
        token (get-in event [:settings :api-token])
        room (schedule/add-room! event "Main Stage" "kaocha")
        _ (schedule/place! (events/event-by-slug "export-test")
                           (:id (first (exports/published-sessions (:id event))))
                           {:day "2026-10-14" :start "09:30" :room-id (:id room)}
                           "gene@example.com")]
    (doseq [path ["/api/v1/"
                  "/api/v1/events/export-test"
                  "/api/v1/events/export-test/schedule"
                  "/api/v1/events/export-test/rooms"
                  "/api/v1/events/export-test/speakers"
                  "/api/v1/events/export-test/docs"
                  (str "/api/v1/events/export-test?token=" token)
                  (str "/api/v1/events/export-test/schedule?token=" token)
                  (str "/api/v1/events/export-test/submissions?token=" token)
                  (str "/api/v1/events/export-test/speakers?status=all&token=" token)
                  (str "/api/v1/events/export-test/changes?token=" token)]]
      (let [body (str (:body (get* handler path)))]
        (is (not (str/includes? body private-answer)) (str path " leaked the private answer"))
        (is (not (str/includes? body "notes-to-committee"))
            (str path " leaked the private field NAME"))))))

(deftest conflicted-sessions-stay-off-the-new-public-surfaces-test
  ;; The invariant from commit 7508ff9, extended to everything bd vi9 added:
  ;; a KNOWN conflict leaves every PUBLIC surface, and a token is what lets an
  ;; organizer see the thing they have to fix.
  (let [{:keys [event published untold]} (setup!)
        _ (inform/inform! event (store/submission-by-id (:id untold)) "gene@example.com")
        event (events/event-by-slug "export-test")
        handler (server/create-app)
        token (get-in event [:settings :api-token])
        auth-h {"authorization" (str "Bearer " token)}
        main (schedule/add-room! event "Main Stage" "kaocha")]
    ;; Two sessions, same room, same minute — a room conflict.
    (schedule/place! (events/event-by-slug "export-test") (:id published)
                     {:day "2026-10-14" :start "10:30" :room-id (:id main)} "gene@example.com")
    (schedule/place! (events/event-by-slug "export-test") (:id untold)
                     {:day "2026-10-14" :start "10:30" :room-id (:id main)} "gene@example.com")

    (testing "the conflict is real"
      (is (seq (schedule/conflicts (events/event-by-slug "export-test")))))

    (testing "sessions.json still withholds both — the gate that already existed"
      (is (zero? (get (exports/sessions-json-data (events/event-by-slug "export-test"))
                      "totalSessions"))))

    (testing "and so does the UNAUTHENTICATED API, on every surface"
      (is (zero? (get (json* handler "/api/v1/events/export-test/sessions") "total")))
      (is (zero? (get (json* handler "/api/v1/events/export-test/speakers") "total")))
      (is (empty? (mapcat #(get % "items")
                          (get (json* handler "/api/v1/events/export-test/schedule") "days"))))
      (is (zero? (get-in (json* handler "/api/v1/events/export-test")
                         ["counts" "publishedSessions"]))))

    (testing "but a token sees everything — you cannot fix a conflict you cannot see"
      (is (= 2 (get (json* handler "/api/v1/events/export-test/sessions" auth-h) "total")))
      (is (= 2 (count (mapcat #(get % "items")
                              (get (json* handler "/api/v1/events/export-test/schedule" auth-h)
                                   "days"))))))

    (testing "resolving it republishes both sides on every surface"
      (schedule/clear-slot! (events/event-by-slug "export-test") (:id untold) "gene@example.com")
      (is (= 2 (get (json* handler "/api/v1/events/export-test/sessions") "total")))
      (is (= 1 (count (mapcat #(get % "items")
                              (get (json* handler "/api/v1/events/export-test/schedule")
                                   "days")))))
      (is (= 1 (count (get (json* handler "/api/v1/events/export-test/schedule")
                           "unscheduled")))
          "the cleared side is unplaced — a partial state, named out loud"))))

(deftest conditional-get-test
  ;; A scraper polls. The polite answer to a poll is 304.
  (let [_ (setup!)
        handler (server/create-app)
        etag-of (fn [path] (get-in (get* handler path) [:headers "ETag"]))]

    (testing "every open-data response carries an ETag"
      (doseq [path ["/events/export-test/exports/sessions.json"
                    "/events/export-test/exports/speakers.json"
                    "/events/export-test/exports/calendar.ics"
                    "/events/export-test/llms.txt"
                    "/api/v1/"
                    "/api/v1/events/export-test"
                    "/api/v1/events/export-test/sessions"
                    "/api/v1/events/export-test/schedule"
                    "/api/v1/events/export-test/docs"]]
        (is (some? (etag-of path)) (str path " has no ETag"))))

    (testing "sending it back gets a bodiless 304"
      (doseq [path ["/events/export-test/exports/sessions.json"
                    "/api/v1/events/export-test/sessions"]]
        (let [resp (get* handler path {"if-none-match" (etag-of path)})]
          (is (= 304 (:status resp)) path)
          (is (= "" (:body resp))))))

    (testing "a stale ETag gets the whole thing"
      (is (= 200 (:status (get* handler "/api/v1/events/export-test/sessions"
                                {"if-none-match" "W/\"deadbeef\""})))))

    (testing "the ics ETag is VERSION-based — its body carries a fresh DTSTAMP every
              fetch, so a content hash could never 304"
      (let [path "/events/export-test/exports/calendar.ics"]
        (is (str/includes? (etag-of path) "ics-"))
        (is (= 304 (:status (get* handler path {"if-none-match" (etag-of path)}))))))

    (testing "and a real change invalidates it"
      (let [path "/api/v1/events/export-test/sessions"
            before (etag-of path)]
        (inform/inform! (events/event-by-slug "export-test")
                        (store/submission-by-id
                          (:id (first (filter #(= "Accepted but untold"
                                                  (:talk-title (:answers %)))
                                              (store/submissions-for-event (:id (events/event-by-slug "export-test")))))))
                        "gene@example.com")
        (is (not= before (etag-of path)))
        (is (= 200 (:status (get* handler path {"if-none-match" before}))))))))

(deftest api-docs-page-test
  (let [_ (setup!)
        handler (server/create-app)
        resp (get* handler "/api/v1/events/export-test/docs")
        body (str (:body resp))]

    (testing "it is PUBLIC — a reference you must log in to read is a reference
              nobody reads"
      (is (= 200 (:status resp)))
      (is (str/includes? (str (get-in resp [:headers "Content-Type"])) "text/html")))

    (testing "it documents every endpoint in the table, with this event's slug baked in"
      (doseq [{:keys [path]} exports/api-endpoints]
        (is (str/includes? body (str/replace path "{slug}" "export-test"))
            (str path " is undocumented"))))

    (testing "every endpoint gets a runnable curl line of its own"
      (doseq [{:keys [path auth]} exports/api-endpoints]
        (let [line (str "curl -s "
                        (when (= :token auth) "-H &quot;Authorization: Bearer $TOKEN&quot; ")
                        "&apos;http://localhost"
                        (str/replace path "{slug}" "export-test") "&apos;")]
          (is (str/includes? body line) (str path " has no curl line")))))

    (testing "the token-gated ones say so"
      (is (str/includes? body "token required")))

    (testing "it states the two rules that govern the whole surface"
      (is (str/includes? body "accepted"))
      (is (str/includes? body "informed"))
      (is (str/includes? body "never unlocks")))

    (testing "and it tells a scraper-author what it is for"
      (is (str/includes? body "You do not need a scraper"))
      (is (str/includes? body "Never match on a name")))))
