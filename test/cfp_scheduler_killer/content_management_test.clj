(ns cfp-scheduler-killer.content-management-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.files :as files]
   [cfp-scheduler-killer.io.blob :as blob]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.speaker-tasks :as speaker-tasks]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock])
  (:import
   (java.time LocalDate)))

(use-fixtures :each with-temp-store)

(defn- authenticated-app
  [email]
  (let [raw (server/create-app)
        token (auth/issue-token! email)
        response (raw (mock/request :get (str "/auth/" token)))
        cookie (first (str/split (first (get-in response [:headers "Set-Cookie"])) #";"))]
    (fn [request]
      (raw (mock/header request "cookie" cookie)))))

(defn- organizer-app [] (authenticated-app "organizer@example.com"))

(defn- make-event!
  [handler]
  (handler (mock/request :post "/api/events/create"
                         {"name" "Content Lifecycle Summit"
                          "slug" "content-lifecycle-summit"
                          "tz" "UTC"
                          "cfp-opens-at" "2020-01-01T00:00"
                          "cfp-closes-at" "2099-01-01T00:00"
                          "presenter-visibility-mode" "visible"}))
  (events/event-by-slug "content-lifecycle-summit"))

(defn- submit!
  [event title speaker-name speaker-email]
  (let [fields (:fields (events/form-for-event (:id event)))
        params {:answer-talk-title title
                :answer-abstract "A complete content lifecycle."
                :answer-session-format "Experience Report"
                :answer-org-size ">10,000"
                :answer-industry "Technology"
                :answer-ai-transformation-history "2026."
                :answer-measurable-outcomes "Fewer missing assets."
                :answer-notes-to-committee "Content test fixture."
                :speaker-name speaker-name
                :speaker-email speaker-email
                :speaker-title "VP"
                :speaker-org "ExampleCo"
                :speaker-bio "A plausible speaker biography."}]
    (submissions/create-submission!
      event
      (submissions/parse-answers fields params)
      (submissions/parse-speaker params)
      "form"
      "organizer@example.com")))

(defn- setup!
  []
  (let [handler (organizer-app)
        event (make-event! handler)]
    {:handler handler
     :event event
     :alpha (submit! event "Talk Alpha" "Ada Alpha" "alpha@example.com")
     :beta (submit! event "Talk Beta" "Bea Beta" "beta@example.com")}))

(defn- request-section
  [body]
  (let [start (str/index-of body "File requests")
        end (str/index-of body "File library")]
    (subs body start end)))

(defn- library-section
  [body]
  (subs body (str/index-of body "File library")))

(defn- install-request!
  [submission key label kind due-on]
  (speaker-tasks/install!
    (:id submission)
    {:key key
     :label label
     :task-type "file"
     :required? true
     :due-on due-on
     :file-kind kind
     :instructions (str "Provide " label ".")}
    "organizer@example.com"))

(defn- upload-version!
  ([event submission task-key content]
   (upload-version! event submission task-key "final-deck.pptx" content))
  ([event submission task-key filename content]
   (upload-version! event submission task-key filename content false))
  ([event submission task-key filename content force-version?]
   (let [source (java.io.File/createTempFile "content-version-" ".pptx")]
     (try
       (spit source content)
       (binding [blob/*put-fn* (fn [_ storage-key] (str "memory://" storage-key))]
         (files/upload! {:source source
                         :filename filename
                         :content-type "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                         :size (.length source)
                         :event-id (:id event)
                         :submission-id (:id submission)
                         :task-key task-key
                         :kind "Presentation"
                         :force-version? force-version?
                         :actor "alpha@example.com"}))
       (finally
         (.delete source))))))

(deftest evaluator-visible-content-roundtrip-test
  (let [{:keys [handler event alpha beta]} (setup!)]
    (install-request! alpha "final-deck" "Upload Session Presentation"
                      "Presentation" (LocalDate/of 2027 5 1))
    (install-request! beta "final-deck" "Upload Session Presentation"
                      "Presentation" (LocalDate/of 2027 5 1))
    (install-request! alpha "final-headshot" "Upload Final Headshot (print quality)"
                      "Headshot" (LocalDate/of 2027 4 14))
    (install-request! beta "final-headshot" "Upload Final Headshot (print quality)"
                      "Headshot" (LocalDate/of 2027 4 14))
    (upload-version! event alpha "final-deck" "slides.pdf" "same fixture bytes")
    (upload-version! event alpha "final-deck" "slides.pdf" "same fixture bytes" true)

    (testing "an explicit replacement stacks even when the fixture bytes are identical"
      (let [file (files/for-task (:id alpha) "final-deck")
            body (:body (handler
                          (mock/request :get
                                        "/events/content-lifecycle-summit/files")))
            header-start (str/index-of body "Select slides.pdf for ZIP")
            header-end (str/index-of body "</h3>" header-start)
            visible-card-header (subs body header-start header-end)]
        (is (= [1 2] (mapv :number (:versions file))))
        (is (str/includes? body "2 versions · show history"))
        (is (str/includes? body "v2 · latest"))
        (is (str/includes? visible-card-header "Latest upload"))
        (is (str/includes? visible-card-header "alpha@example.com"))
        (is (str/includes? body "Generate selected ZIP"))
        (is (str/includes? body "latest version of each selected file"))
        (is (re-find
              (re-pattern
                (str "name=\"file-ids\"[^>]*value=\""
                     (java.util.regex.Pattern/quote (:id file)) "\""))
              body))))

    (testing "the speaker sees every immutable version and readable lifecycle timestamps"
      (reviews/set-status! (:id alpha) "Accepted" "organizer@example.com")
      (store/append! {:type "submission.notified"
                      :actor "organizer@example.com"
                      :event-id (:id event)
                      :payload {:submission-id (:id alpha)
                                :status-at-notify "Accepted"
                                :at (store/now-iso)}})
      (let [file (files/for-task (:id alpha) "final-deck")
            speaker-app (authenticated-app "alpha@example.com")
            comment-response
            (speaker-app
              (mock/request
                :post
                (str "/api/submissions/" (:id alpha) "/files/" (:id file) "/comment")
                {"comment-id" "speaker-lifecycle-note"
                 "body" "Draft deck - final version coming Friday."}))
            portal-body (:body (speaker-app (mock/request :get "/portal")))
            organizer-body (:body (handler
                                    (mock/request :get
                                                  "/events/content-lifecycle-summit/files")))]
        (is (= 303 (:status comment-response)))
        (is (= (str "/portal#onboarding-" (:id alpha))
               (get-in comment-response [:headers "Location"])))
        (is (str/includes? portal-body "Shared file conversation"))
        (is (str/includes? portal-body "Post comment to organizers"))
        (is (str/includes? portal-body "2 versions · show history"))
        (is (str/includes? portal-body "v2 · latest / current"))
        (is (str/includes?
              portal-body
              "2 versions retained · v2 is current · previous versions remain downloadable below"))
        (is (re-find #"<details(?=[^>]*open)[^>]*><summary>2 versions" portal-body))
        (is (= 2 (count (re-seq #"/download\?version-id=" portal-body))))
        (is (str/includes?
              organizer-body
              "2 versions retained · v2 is current · previous versions remain downloadable below"))
        (is (re-find #"<details(?=[^>]*open)[^>]*><summary>2 versions" organizer-body))
        (is (= 2 (count (re-seq #"/download\?version-id=" organizer-body))))
        (is (str/includes? portal-body "v1"))
        (is (str/includes? portal-body "slides.pdf"))
        (is (str/includes? portal-body "Ada Alpha &lt;alpha@example.com&gt;"))
        (is (str/includes? portal-body "Draft deck - final version coming Friday."))
        (is (str/includes? organizer-body "Ada Alpha &lt;alpha@example.com&gt;"))
        (is (str/includes? organizer-body "Draft deck - final version coming Friday."))
        (is (str/includes? portal-body
                           "Accepted file types: PDF, PowerPoint, Keynote, PNG, or JPEG"))
        (is (str/includes? portal-body "Maximum file size: 25 MB"))
        (is (not (re-find #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}" portal-body)))
        (is (not (re-find #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}" organizer-body)))))

    (testing "the deliverables dashboard keeps completed uploads and filters visibly"
      (let [all (:body (handler
                         (mock/request :get
                                       "/events/content-lifecycle-summit/deliverables")))
            complete (:body (handler
                              (mock/request
                                :get
                                "/events/content-lifecycle-summit/deliverables?status=complete")))
            open (:body (handler
                          (mock/request
                            :get
                            "/events/content-lifecycle-summit/deliverables?status=open")))]
        (is (str/includes? all "Filter deliverables"))
        (is (str/includes? all "1 uploaded / complete</strong> of 4 shown tasks"))
        (is (str/includes? all "Uploaded / complete"))
        (is (str/includes? all "Uploaded slides.pdf · v2 current · 2 versions"))
        (is (str/includes? all "Open in central files library"))
        (is (str/includes? all "Review uploaded files →"))
        (is (= 2 (count (re-seq #"Upload Final Headshot \(print quality\)" all))))
        (is (= 2 (count (re-seq #"Upload Session Presentation" all))))
        (is (str/includes? complete "Talk Alpha"))
        (is (str/includes? complete "1 uploaded / complete</strong> of 1 shown task"))
        (is (str/includes? complete "Talk Beta")
            "the lead speaker-completion table remains visible under task filters")
        (is (not (str/includes? complete "Upload Final Headshot (print quality)")))
        (is (str/includes? open "Talk Beta"))
        (is (str/includes? open "Talk Alpha"))
        (is (str/includes? open "0 uploaded / complete</strong> of 3 shown tasks"))
        (is (not (str/includes? open "Uploaded slides.pdf")))))

    (testing "file comments visibly identify the human, not only a raw address"
      (let [file (files/for-task (:id alpha) "final-deck")
            person (store/person-by-email "organizer@example.com")
            reply-response
            (handler
              (mock/request
                :post
                (str "/api/events/content-lifecycle-summit/files/" (:id file)
                     "/comment")
                {"comment-id" "organizer-reply"
                 "body" "Thanks — please upload final version by Tuesday."}))]
        (is (= 303 (:status reply-response)))
        (is (= (str "/events/content-lifecycle-summit/files#file-" (:id file))
               (get-in reply-response [:headers "Location"])))
        (let [body (:body (handler
                            (mock/request :get
                                          "/events/content-lifecycle-summit/files")))
              speaker-body (:body ((authenticated-app "alpha@example.com")
                                   (mock/request :get "/portal")))]
          (is (str/includes? body (:name person)))
          (is (str/includes? body "Shared file conversation"))
          (is (str/includes? body "Reply to speaker"))
          (is (str/includes? body "Draft deck - final version coming Friday."))
          (is (str/includes? body "Thanks — please upload final version by Tuesday."))
          (is (str/includes? speaker-body "Ada Alpha &lt;alpha@example.com&gt;"))
          (is (str/includes? speaker-body (:name person)))
          (is (str/includes? speaker-body "Draft deck - final version coming Friday."))
          (is (str/includes? speaker-body "Thanks — please upload final version by Tuesday.")))))

    (testing "selected latest-version ZIP parameters reach the archive boundary"
      (let [file-id (:id (files/for-task (:id alpha) "final-deck"))
            called (atom nil)]
        (with-redefs [files/event-latest-zip-bytes!
                      (fn [event-id file-ids grouping]
                        (reset! called [event-id file-ids grouping])
                        (.getBytes "PK" "UTF-8"))]
          (let [response (handler
                           (mock/request
                             :get
                             (str "/events/content-lifecycle-summit/files.zip?file-ids="
                                  file-id "&grouping=flat")))]
            (is (= 200 (:status response)))
            (is (= [(:id event) [file-id] "flat"] @called))))))

    (testing "the organizer reviews an explicit ZIP manifest before downloading"
      (let [file-id (:id (files/for-task (:id alpha) "final-deck"))
            body (:body
                   (handler
                     (mock/request
                       :get
                       (str "/events/content-lifecycle-summit/files?prepare-zip=true"
                            "&file-ids=" file-id "&grouping=flat"))))]
        (is (str/includes? body "ZIP ready to download"))
        (is (str/includes? body "1 latest file"))
        (is (str/includes? body "slides.pdf"))
        (is (str/includes? body "Download prepared ZIP"))
        (is (re-find
              (re-pattern
                (str "files\\.zip[^\"]*file-ids="
                     (java.util.regex.Pattern/quote file-id)))
              body))))))

(deftest organizer-creates-one-request-for-multiple-speakers-test
  (let [{:keys [handler alpha beta]} (setup!)
        form-body (:body
                    (handler
                      (mock/request :get
                                    "/events/content-lifecycle-summit/files")))
        response (handler
                   (mock/request
                     :post
                     "/api/events/content-lifecycle-summit/files/requests"
                     {"submission-ids" [(:id alpha) (:id beta)]
                      "request-name" "Final deck"
                      "file-kind" "Presentation"
                      "due-on" "2026-10-10"
                      "instructions" "Upload the approved final deck."}))
        alpha-task (first (filter #(= "Final deck" (:label %))
                                  (store/tasks-for-submission (:id alpha))))
        beta-task (first (filter #(= "Final deck" (:label %))
                                 (store/tasks-for-submission (:id beta))))]
    (testing "bulk assignment presents every session as an explicit choice"
      (is (= 2
             (count
               (re-seq #"<input(?=[^>]*name=\"submission-ids\")(?=[^>]*type=\"checkbox\")[^>]*>"
                       form-body))))
      (is (str/includes? form-body "Talk Alpha — Ada Alpha"))
      (is (str/includes? form-body "Talk Beta — Bea Beta")))
    (is (= 303 (:status response)))
    (is (= "Final deck" (:label alpha-task)))
    (is (= "Final deck" (:label beta-task)))
    (is (= (:key alpha-task) (:key beta-task))
        "one bulk request keeps one stable task key across selected speakers")
    (is (= (LocalDate/of 2026 10 10) (:due-on alpha-task)))
    (is (str/includes? (get-in response [:headers "Location"])
                       "2+sessions"))
    (testing "the whole selection is preflighted before the first task fact"
      (let [before (count (store/tasks-for-submission (:id alpha)))
            rejected (handler
                       (mock/request
                         :post
                         "/api/events/content-lifecycle-summit/files/requests"
                         {"submission-ids" [(:id alpha)
                                            "00000000-0000-0000-0000-000000000000"]
                          "request-name" "Unsafe partial request"
                          "file-kind" "Presentation"
                          "due-on" "2026-10-10"
                          "instructions" "This batch must be refused."}))]
        (is (= 404 (:status rejected)))
        (is (= before (count (store/tasks-for-submission (:id alpha)))))))))

(deftest bulk-file-requests-are-retry-safe-and-atomic-test
  (let [{:keys [handler alpha beta]} (setup!)
        request-id "00000000-0000-0000-0000-000000000101"
        params {"request-id" request-id
                "submission-ids" [(:id alpha) (:id beta)]
                "request-name" "Final handout"
                "file-kind" "Handout"
                "due-on" "2026-10-15"
                "instructions" "Upload the final attendee handout."}
        send! #(handler
                 (mock/request
                   :post
                   "/api/events/content-lifecycle-summit/files/requests"
                   %))]
    (testing "an exact browser retry does not duplicate the selected tasks"
      (is (= 303 (:status (send! params))))
      (is (= 303 (:status (send! params))))
      (is (= 2 (count (filter #(= "task.installed" (:type %))
                              (store/read-events)))))
      (is (= 1 (count (filter #(= "Final handout" (:label %))
                              (store/tasks-for-submission (:id alpha))))))
      (is (= 1 (count (filter #(= "Final handout" (:label %))
                              (store/tasks-for-submission (:id beta)))))))

    (testing "one conflicting target rejects the whole batch before any write"
      (let [conflict-id "00000000-0000-0000-0000-000000000102"
            conflict-key (str "file-" conflict-id)
            before (count (store/read-events))]
        (install-request! beta conflict-key "Existing private request" "Handout"
                          (LocalDate/of 2026 10 1))
        (let [before-attempt (count (store/read-events))
              response (send! (assoc params "request-id" conflict-id))]
          (is (= 409 (:status response)))
          (is (= before-attempt (count (store/read-events))))
          (is (nil? (get-in (store/snapshot)
                            [:tasks [(:id alpha) conflict-key]])))
          (is (= (inc before) before-attempt)))))

    (testing "the browser supplies a stable identity for safe resubmission"
      (let [body (:body (handler
                          (mock/request :get
                                        "/events/content-lifecycle-summit/files")))]
        (is (re-find #"name=\"request-id\" type=\"hidden\" value=\"[0-9a-f-]{36}\""
                     body))))))

(deftest file-dashboard-filters-sorts-and-keeps-version-history-test
  (let [{:keys [handler event alpha beta]} (setup!)
        _ (install-request! alpha "final-deck" "Final deck" "Presentation"
                            (LocalDate/of 2026 10 10))
        _ (install-request! beta "final-deck" "Final deck" "Presentation"
                            (LocalDate/of 2026 10 10))
        _ (install-request! alpha "portrait" "Speaker portrait" "Headshot"
                            (LocalDate/of 2026 9 1))]
    (upload-version! event alpha "final-deck" "draft-deck.pptx" "version one")
    (upload-version! event alpha "final-deck" "version two")

    (testing "uploading changed bytes stacks immutable versions"
      (let [file (files/for-task (:id alpha) "final-deck")]
        (is (= [1 2] (mapv :number (:versions file))))
        (is (= 1 (count (files/for-submission (:id alpha)))))))

    (testing "mixed completion can be filtered without hiding the controls"
      (let [received-body (:body (handler
                                   (mock/request
                                     :get
                                     "/events/content-lifecycle-summit/files?status=received")))
            received (request-section received-body)
            pending-body (:body (handler
                                  (mock/request
                                    :get
                                    "/events/content-lifecycle-summit/files?status=pending")))
            pending (request-section pending-body)]
        (is (str/includes? received-body "Filter files and requests"))
        (is (str/includes? received "Talk Alpha"))
        (is (not (str/includes? received "Talk Beta")))
        (is (str/includes? pending "Talk Beta"))
        (is (str/includes? pending "Speaker portrait"))
        (is (= 1 (count (re-seq #"<td>Final deck</td>" pending)))
            "the completed Alpha deck is absent; only Beta's deck remains pending")
        (is (not (str/includes? pending-body "final-deck.pptx"))
            "pending work does not include received file cards")
        (is (str/includes? pending-body "2 file requests · 0 uploaded files"))
        (is (str/includes? pending-body
                           "Uploaded files are hidden in the Pending view"))
        (is (not (str/includes? pending-body "No files uploaded yet"))
            "a filter must not falsely claim that durable uploads do not exist")))

    (testing "search, kind, and due-date order describe the same visible rows"
      (let [beta-only (request-section
                        (:body (handler
                                 (mock/request
                                   :get
                                   "/events/content-lifecycle-summit/files?q=Beta"))))
            headshots (request-section
                        (:body (handler
                                 (mock/request
                                   :get
                                   "/events/content-lifecycle-summit/files?kind=Headshot"))))
            due-desc (request-section
                       (:body (handler
                                (mock/request
                                  :get
                                  "/events/content-lifecycle-summit/files?sort=due-desc"))))]
        (is (str/includes? beta-only "Talk Beta"))
        (is (not (str/includes? beta-only "Talk Alpha")))
        (is (str/includes? headshots "Speaker portrait"))
        (is (not (str/includes? headshots "Final deck")))
        (is (< (str/index-of due-desc "Final deck")
               (str/index-of due-desc "Speaker portrait")))))

    (testing "the library still makes the replacement stack explicit"
      (let [body (:body (handler
                          (mock/request :get "/events/content-lifecycle-summit/files")))]
        (is (str/includes? body "3 file requests · 1 uploaded file"))
        (is (str/includes? body "Presentation · v2"))
        (is (str/includes? body "2 versions · show history"))
        (is (str/includes? body "v1"))
        (is (str/includes? body "v2 · latest"))))

    (testing "an empty search result describes filters, not an empty event"
      (let [body (:body (handler
                          (mock/request
                            :get
                            "/events/content-lifecycle-summit/files?q=Nobody")))]
        (is (str/includes? body "0 file requests · 0 uploaded files"))
        (is (str/includes? body "No uploaded files match these filters"))
        (is (not (str/includes? body "No files uploaded yet")))))

    (testing "search finds a filename retained only in immutable history"
      (let [body (:body (handler
                          (mock/request
                            :get
                            "/events/content-lifecycle-summit/files?q=draft-deck.pptx")))]
        (is (str/includes? body "final-deck.pptx"))
        (is (str/includes? body "draft-deck.pptx"))
        (is (str/includes? body "2 versions · show history"))))))

(deftest uploaded-file-library-has-explicit-workable-sorts-test
  (let [{:keys [handler event alpha beta]} (setup!)]
    (install-request! alpha "alpha-deck" "Alpha deck" "Presentation"
                      (LocalDate/of 2026 10 10))
    (install-request! beta "beta-deck" "Beta deck" "Presentation"
                      (LocalDate/of 2026 10 10))
    (with-redefs [store/now-iso (constantly "2026-08-14T10:00:00Z")]
      (upload-version! event alpha "alpha-deck" "alpha-old.pptx" "alpha bytes"))
    (with-redefs [store/now-iso (constantly "2026-08-14T11:00:00Z")]
      (upload-version! event beta "beta-deck" "beta-new.pptx" "beta bytes"))
    (let [source (java.io.File/createTempFile "beta-headshot-" ".png")]
      (try
        (spit source "beta headshot bytes")
        (binding [blob/*put-fn* (fn [_ storage-key] (str "memory://" storage-key))]
          (files/upload! {:source source
                          :filename "beta-headshot.png"
                          :content-type "image/png"
                          :size (.length source)
                          :event-id (:id event)
                          :person-id (get-in beta [:speakers 0 :person-id])
                          :kind "Headshot"
                          :actor "organizer@example.com"}))
        (finally
          (.delete source))))

    (letfn [(positions [query]
              (let [library (library-section
                              (:body (handler
                                       (mock/request
                                         :get
                                         (str "/events/content-lifecycle-summit/files?" query)))))]
                [(str/index-of library "alpha-old.pptx")
                 (str/index-of library "beta-new.pptx")]))]
      (testing "newest and oldest sort the uploaded-file library in both directions"
        (let [[new-alpha new-beta] (positions "file-sort=uploaded-newest")
              [old-alpha old-beta] (positions "file-sort=uploaded-oldest")]
          (is (< new-beta new-alpha))
          (is (< old-alpha old-beta))))

      (testing "speaker sort creates a stable owner worklist"
        (let [[alpha-position beta-position] (positions "file-sort=speaker-asc")]
          (is (< alpha-position beta-position))))

      (testing "speaker sort includes profile-owned headshots in the same alphabet"
        (let [library
              (library-section
                (:body
                  (handler
                    (mock/request
                      :get
                      "/events/content-lifecycle-summit/files?file-sort=speaker-asc"))))]
          (is (< (str/index-of library "alpha-old.pptx")
                 (str/index-of library "beta-headshot.png")))
          (is (re-find #"(?s)beta-headshot\.png.*Headshot · v1 · Bea Beta"
                       library))))

      (testing "the selected file sort remains visible in the controls"
        (let [body (:body (handler
                            (mock/request
                              :get
                              "/events/content-lifecycle-summit/files?file-sort=uploaded-oldest")))]
          (is (re-find #"(?s)<option selected=\"selected\" value=\"uploaded-oldest\">Uploaded · oldest</option>"
                       body)))))))

(deftest co-speakers-remain-visible-and-searchable-in-file-work-test
  (let [{:keys [handler event alpha]} (setup!)
        submission
        (submissions/create-submission!
          event
          (assoc (:answers alpha) :talk-title "Joint delivery")
          [{:name "Priya Primary" :email "priya@example.com"
            :title "VP Engineering" :org "ExampleCo" :bio "Primary biography."
            :role "Primary speaker"}
           {:name "Rae Partner" :email "rae@example.com"
            :title "VP Product" :org "ExampleCo" :bio "Partner biography."
            :role "Co-speaker"}]
          "form"
          "organizer@example.com")]
    (install-request! submission "joint-deck" "Joint final deck" "Presentation"
                      (LocalDate/of 2026 10 12))

    (testing "every co-speaker has a status row and reminder recipient"
      (let [[primary partner] (:speakers submission)
            dashboard (:body
                        (handler
                          (mock/request
                            :get
                            "/events/content-lifecycle-summit/deliverables")))
            draft (:body
                    (handler
                      (mock/request
                        :post
                        "/api/events/content-lifecycle-summit/speaker-chases/draft"
                        {"selected"
                         [(str (:id submission) "|joint-deck|" (:person-id primary))
                          (str (:id submission) "|joint-deck|" (:person-id partner))]})))
            legacy-draft
            (handler
              (mock/request
                :post
                "/api/events/content-lifecycle-summit/speaker-chases/draft"
                {"selected" (str (:id submission) "|joint-deck")}))
            duplicate-draft
            (:body
              (handler
                (mock/request
                  :post
                  "/api/events/content-lifecycle-summit/speaker-chases/draft"
                  {"selected"
                   [(str (:id submission) "|joint-deck|" (:person-id primary))
                    (str (:id submission) "|joint-deck|" (:person-id primary))]})))]
        (is (= 2 (count (re-seq #"name=\"selected\"" dashboard))))
        (is (str/includes? dashboard "Select Priya Primary for follow-up"))
        (is (str/includes? dashboard "Select Rae Partner for follow-up"))
        (is (str/includes? draft "priya@example.com"))
        (is (str/includes? draft "rae@example.com"))
        (is (= 200 (:status legacy-draft)))
        (is (str/includes? (:body legacy-draft) "priya@example.com"))
        (is (re-find #"<input name=\"count\" type=\"hidden\" value=\"1\""
                     duplicate-draft))))

    (upload-version! event submission "joint-deck" "joint version")

    (testing "a co-speaker search finds the shared request and uploaded file"
      (let [body (:body (handler
                          (mock/request
                            :get
                            "/events/content-lifecycle-summit/files?q=rae%40example.com")))]
        (is (str/includes? body "Joint final deck"))
        (is (str/includes? body "final-deck.pptx"))
        (is (str/includes? body "Priya Primary, Rae Partner"))))

    (testing "the unfiltered ledger names every person who can act on the task"
      (let [body (:body (handler
                          (mock/request :get "/events/content-lifecycle-summit/files")))]
        (is (str/includes? (request-section body) "Priya Primary, Rae Partner"))
        (is (re-find #"(?s)final-deck\.pptx.*Priya Primary, Rae Partner" body))))))
