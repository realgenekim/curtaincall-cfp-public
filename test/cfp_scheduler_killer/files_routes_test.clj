(ns cfp-scheduler-killer.files-routes-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.files :as files]
   [cfp-scheduler-killer.handlers.files :as file-handlers]
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
   (java.io ByteArrayInputStream)
   (java.time LocalDate LocalDateTime)
   (java.util Arrays)
   (java.util.zip ZipInputStream)))

(use-fixtures :each with-temp-store)

(defn- zip-entry-metadata [bytes]
  (with-open [zip (ZipInputStream. (ByteArrayInputStream. bytes))]
    (loop [entries []]
      (if-let [entry (.getNextEntry zip)]
        (recur (conj entries {:name (.getName entry) :time (.getTime entry)}))
        entries))))

(deftest bulk-file-archive-is-reproducible-and-private
  (let [alpha {:id "file-alpha" :event-id "event-1"
               :submission-id "submission-alpha" :kind "Presentation"
               :versions [{:id "version-alpha-1" :number 1
                           :filename "alpha-draft.pdf" :storage-key "alpha-1"}]}
        beta {:id "file-beta" :event-id "event-1"
              :submission-id "submission-beta" :kind "Poster"
              :versions [{:id "version-beta-1" :number 1
                          :filename "beta-draft.pdf" :storage-key "beta-1"}
                         {:id "version-beta-2" :number 2
                          :filename "beta-final.pdf" :storage-key "beta-2"}]}
        stored-bytes {"alpha-1" (.getBytes "alpha" "UTF-8")
                      "beta-1" (.getBytes "beta draft" "UTF-8")
                      "beta-2" (.getBytes "beta final" "UTF-8")}]
    (testing "the same durable state produces one canonical complete archive"
      (with-redefs [files/for-event (constantly [beta alpha])
                    blob/read-bytes! stored-bytes]
        (let [first-archive (files/event-zip-bytes! "event-1")
              second-archive (files/event-zip-bytes! "event-1")
              entries (zip-entry-metadata first-archive)]
          (is (= ["submission-alpha/Presentation-file-alpha-v1-alpha-draft.pdf"
                  "submission-beta/Poster-file-beta-v1-beta-draft.pdf"
                  "submission-beta/Poster-file-beta-v2-beta-final.pdf"]
                 (mapv :name entries)))
          (is (every? zero? (map :time entries)))
          (is (Arrays/equals first-archive second-archive)))))

    (testing "session/speaker grouping uses human folder names"
      (let [headshot {:id "file-headshot" :event-id "event-1"
                      :person-id "person-beta" :kind "Headshot"
                      :versions [{:id "version-headshot-1" :number 1
                                  :filename "beta.png" :storage-key "headshot-1"}]}]
        (with-redefs [files/for-event (constantly [headshot alpha])
                      store/submission-by-id
                      (fn [id]
                        (when (= "submission-alpha" id)
                          {:answers {:talk-title "Algebraic Programs"}}))
                      store/person-by-id
                      (fn [id]
                        (when (= "person-beta" id) {:name "Bea Beta"}))
                      blob/read-bytes! (assoc stored-bytes
                                              "headshot-1"
                                              (.getBytes "headshot" "UTF-8"))]
          (is (= ["Algebraic-Programs/Presentation-file-alpha-v1-alpha-draft.pdf"
                  "Bea-Beta/Headshot-file-headshot-v1-beta.png"]
                 (mapv :name
                       (zip-entry-metadata
                         (files/event-latest-zip-bytes! "event-1" [] "by-session"))))))))

    (testing "the successful organizer download cannot outlive authorization"
      (with-redefs [events/event-by-slug
                    (constantly {:id "event-1" :slug "files-summit"})
                    files/event-zip-bytes! (constantly (.getBytes "zip" "UTF-8"))]
        (let [response (file-handlers/handle-files-zip
                         {:path-params {:slug "files-summit"}})]
          (is (= 200 (:status response)))
          (is (= "private, no-store" (get-in response [:headers "Cache-Control"])))
          (is (= "no-cache" (get-in response [:headers "Pragma"])))
          (is (= "0" (get-in response [:headers "Expires"])))
          (is (= "nosniff" (get-in response
                             [:headers "X-Content-Type-Options"]))))))))

(deftest durable-file-metadata-never-renders-a-false-success
  (let [event {:id "event-1" :slug "files-summit"}
        version {:id "version-1" :filename "slides.pdf"
                 :content-type "application/pdf"}
        file {:id "file-1" :event-id "event-1" :kind "Presentation"
              :versions [version]}
        headshot (assoc file :kind "Headshot")
        request {:path-params {:slug "files-summit" :file-id "file-1"}
                 :params {}}]
    (with-redefs [events/event-by-slug (constantly event)
                  files/by-id (constantly file)
                  files/latest-bytes! (constantly nil)
                  files/version-bytes! (constantly nil)]
      (testing "latest and immutable-version downloads report unavailable bytes"
        (doseq [response [(file-handlers/handle-organizer-download request)
                          (file-handlers/handle-organizer-download
                            (assoc request :params {:version-id "version-1"}))]]
          (is (= 503 (:status response)))
          (is (re-find #"temporarily unavailable" (:body response)))))

      (testing "public headshots do not claim success without image bytes"
        (with-redefs [files/by-id (constantly headshot)]
          (let [response (file-handlers/handle-headshot request)]
            (is (= 503 (:status response)))
            (is (re-find #"temporarily unavailable" (:body response)))))))

    (testing "a failed backing-store read produces a controlled bulk response"
      (with-redefs [events/event-by-slug (constantly event)
                    files/event-zip-bytes!
                    (fn [_]
                      (throw (ex-info "backing store unavailable" {})))]
        (let [response (file-handlers/handle-files-zip request)]
          (is (= 503 (:status response)))
          (is (re-find #"temporarily unavailable" (:body response))))))))

(defn- login! [handler email]
  (let [token (auth/issue-token! email)
        response (handler (mock/request :get (str "/auth/" token)))]
    (first (str/split (first (get-in response [:headers "Set-Cookie"])) #";"))))

(defn- as [request cookie]
  (mock/header request "cookie" cookie))

(defn- multipart-file [path filename content-type content]
  (let [boundary "cfp-file-boundary"
        body (str "--" boundary "\r\n"
                  "Content-Disposition: form-data; name=\"file\"; filename=\"" filename "\"\r\n"
                  "Content-Type: " content-type "\r\n\r\n"
                  content "\r\n--" boundary "--\r\n")]
    (-> (mock/request :post path)
        (mock/content-type (str "multipart/form-data; boundary=" boundary))
        (mock/body body))))

(defn- setup! []
  (let [event (events/create-event!
                {:name "Files Summit" :slug "files-summit" :tz "America/New_York"
                 :starts-on (LocalDate/of 2026 10 10)
                 :ends-on (LocalDate/of 2026 10 11)
                 :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
                 :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
                "kaocha")
        committee-id (:id (first (events/committees-for-event (:id event))))
        _ (committees/add-member! committee-id
                                  {:name "Ann Organizer" :email "ann@example.com" :role "chair"}
                                  "kaocha")
        fields (:fields (events/form-for-event (:id event)))
        make-submission
        (fn [title email]
          (let [params {:answer-talk-title title :answer-abstract "Abstract."
                        :answer-session-format "Experience Report"
                        :answer-track "Developer Practices" :answer-org-size ">10,000"
                        :answer-industry "Insurance"
                        :answer-ai-transformation-history "2023."
                        :answer-measurable-outcomes "Numbers."
                        :speaker-name "Ada Speaker" :speaker-email email
                        :speaker-title "VP" :speaker-org "Analytical Engines"
                        :speaker-bio "Bio."}]
            (submissions/create-submission!
              event (submissions/parse-answers fields params)
              (submissions/parse-speaker params) "form" "kaocha")))
        own (make-submission "Algebraic Programs" "ada@example.com")
        other (make-submission "Someone Else's Deck" "other@example.com")]
    (reviews/set-status! (:id own) "Accepted" "ann@example.com")
    (store/append! {:type "submission.notified" :actor "ann@example.com"
                    :event-id (:id event)
                    :payload {:submission-id (:id own) :status-at-notify "Accepted"
                              :at (store/now-iso)}})
    (speaker-tasks/install!
      (:id own)
      {:key "slides-final" :label "Upload final slides" :task-type "file"
       :required? true :due-on (LocalDate/of 2026 9 25)
       :file-kind "Presentation" :instructions "Use the communications-approved deck."}
      "ann@example.com")
    ;; Existing events installed the default upload-labelled checklist with a
    ;; URL task type. Those durable facts must keep working after first-class
    ;; file requests were introduced.
    (speaker-tasks/install!
      (:id own)
      {:key "slides-url" :label "Upload your slides" :task-type "url"
       :required? true :due-offset-days -21 :file-kind "Presentation"}
      "ann@example.com")
    {:event event :own own :other other}))

(deftest speaker-and-organizer-file-roundtrip
  (let [{:keys [own other]} (setup!)
        handler (server/create-app)
        speaker-cookie (login! handler "ada@example.com")
        organizer-cookie (login! handler "ann@example.com")
        objects (atom {})]
    (binding [blob/*put-fn*
              (fn [source key]
                (let [location (str "memory://" key)]
                  (swap! objects assoc location (java.nio.file.Files/readAllBytes (.toPath source)))
                  location))
              blob/*read-bytes-fn* #(get @objects %)]
      (testing "an organizer creates a dated, instructed file request"
        (is (= 303 (:status
                     (handler
                       (as (mock/request
                             :post "/api/events/files-summit/files/requests"
                             {"submission-id" (:id own)
                              "request-name" "Print-ready lobby poster"
                              "file-kind" "Poster"
                              "due-on" "2026-09-20"
                              "instructions" "Upload the print-ready poster."})
                           organizer-cookie)))))
        (let [poster (first (filter #(= "Poster" (:file-kind %))
                                    (store/tasks-for-submission (:id own))))]
          (is (= (LocalDate/of 2026 9 20) (:due-on poster)))
          (is (= "Print-ready lobby poster" (:label poster)))
          (is (= "Upload the print-ready poster." (:instructions poster))))
        (let [body (:body (handler
                            (as (mock/request :get "/events/files-summit/files")
                                organizer-cookie)))]
          (is (str/includes? body "File requests"))
          (is (str/includes? body "Print-ready lobby poster"))
          (is (str/includes? body "Algebraic Programs"))
          (is (str/includes? body "2026-09-20"))
          (is (str/includes? body "Upload the print-ready poster."))
          (is (str/includes? body "Pending"))))

      (testing "the portal states the upload constraints and exact request"
        (let [body (:body (handler (as (mock/request :get "/portal") speaker-cookie)))]
          (is (str/includes? body "Upload final slides"))
          (is (str/includes? body "Upload your slides"))
          (is (str/includes? body "Print-ready lobby poster"))
          (is (str/includes? body "25 MB maximum"))
          (is (str/includes? body "communications-approved deck"))))

      (testing "an assigned presentation task owns its upload and completion"
        (let [task-key "general-cnt02-presentation"
              assigned
              (handler
                (as (mock/request
                      :post "/api/events/files-summit/reminder-schedule"
                      {"intent" "create-general-task"
                       "task-id" "cnt02-presentation"
                       "task-name" "Upload Session Presentation"
                       "due-on" "2026-09-24"
                       "instructions" "Upload the final conference deck."
                       "submission-ids" (:id own)})
                    organizer-cookie))
              portal-before (:body (handler
                                     (as (mock/request :get "/portal")
                                         speaker-cookie)))
              uploaded
              (handler
                (as (multipart-file
                      (str "/api/submissions/" (:id own) "/files/" task-key "/upload")
                      "session-presentation.pdf" "application/pdf" "assigned deck")
                    speaker-cookie))
              task (get-in (store/snapshot) [:tasks [(:id own) task-key]])
              file (files/for-task (:id own) task-key)
              built-in (get-in (store/snapshot) [:tasks [(:id own) "slides-url"]])
              portal-after (:body (handler
                                    (as (mock/request :get "/portal")
                                        speaker-cookie)))
              organizer-after
              (:body (handler
                       (as (mock/request :get "/events/files-summit/deliverables")
                           organizer-cookie)))]
          (is (= 303 (:status assigned)))
          (is (str/includes? portal-before "Upload Session Presentation"))
          (is (str/includes?
                portal-before
                (str "/api/submissions/" (:id own) "/files/" task-key "/upload")))
          (is (= 303 (:status uploaded)))
          (is (= "/portal?uploaded=session-presentation.pdf"
                 (get-in uploaded [:headers "Location"])))
          (is (= task-key (:task-key file)))
          (is (= "session-presentation.pdf" (:filename (peek (:versions file)))))
          (is (= (:id file) (:value task)))
          (is (some? (:completed-at task)))
          (is (re-find #"(?s)Upload Session Presentation.*Complete.*session-presentation\.pdf"
                       portal-after))
          (is (re-find #"(?s)Upload Session Presentation.*Uploaded / complete.*session-presentation\.pdf"
                       organizer-after))
          (is (nil? (:completed-at built-in))
              "the explicit assigned task stays separate from the legacy built-in")))

      (testing "a rejected upload returns the speaker to actionable portal guidance"
        (let [before (count (files/for-submission (:id own)))
              response
              (handler
                (as (multipart-file
                      (str "/api/submissions/" (:id own) "/files/slides-final/upload")
                      "slides.html" "text/html" "<script>bad</script>")
                    speaker-cookie))
              location (get-in response [:headers "Location"])
              portal-response (handler (as (mock/request :get location) speaker-cookie))]
          (is (= 303 (:status response)))
          (is (str/starts-with? location "/portal?upload-error="))
          (is (= 200 (:status portal-response)))
          (is (str/includes? (:body portal-response) "Upload could not be saved"))
          (is (str/includes? (:body portal-response) "Use PDF, PowerPoint, Keynote, PNG, or JPEG"))
          (is (= before (count (files/for-submission (:id own)))))))

      (testing "a legacy upload-labelled URL task accepts its real file evidence"
        (let [response
              (handler
                (as (multipart-file
                      (str "/api/submissions/" (:id own) "/files/slides-url/upload")
                      "legacy-slides.pdf" "application/pdf" "legacy deck")
                    speaker-cookie))
              location (get-in response [:headers "Location"])
              portal-response (handler (as (mock/request :get location) speaker-cookie))
              task (get-in (store/snapshot) [:tasks [(:id own) "slides-url"]])]
          (is (= 303 (:status response)))
          (is (= "/portal?uploaded=legacy-slides.pdf" location))
          (is (str/includes? (:body portal-response) "legacy-slides.pdf uploaded"))
          (is (str/includes? (:body portal-response) "task is now complete"))
          (is (some? (:completed-at task)))
          (is (= (:id (files/for-task (:id own) "slides-url")) (:value task)))))

      (testing "a speaker uploads v1 and v2 without overwriting history"
        (is (= 303 (:status
                     (handler
                       (as (multipart-file
                             (str "/api/submissions/" (:id own) "/files/slides-final/upload")
                             "keynote-slides.pptx"
                             "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                             "deck one")
                           speaker-cookie)))))
        (is (= 303 (:status
                     (handler
                       (as (multipart-file
                             (str "/api/submissions/" (:id own) "/files/slides-final/upload")
                             "keynote-slides.pptx"
                             "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                             "deck two")
                           speaker-cookie)))))
        (let [file (files/for-task (:id own) "slides-final")]
          (is (= [1 2] (mapv :number (:versions file))))
          (is (= "keynote-slides.pptx" (:filename (peek (:versions file)))))
          (is (some? (get-in (store/snapshot)
                             [:tasks [(:id own) "slides-final"] :completed-at])))
          (let [body (:body (handler
                              (as (mock/request :get "/events/files-summit/files")
                                  organizer-cookie)))]
            (is (str/includes? body "Upload final slides"))
            (is (str/includes? body "Received")))))

      (let [file (files/for-task (:id own) "slides-final")
            file-id (:id file)
            [v1 v2] (:versions file)]
        (testing "each immutable history row downloads its own bytes and filename"
          (let [download-version
                (fn [version-id]
                  (handler
                    (as (mock/request
                          :get
                          (str "/events/files-summit/files/" file-id
                               "/download?version-id=" version-id))
                        organizer-cookie)))
                first-response (download-version (:id v1))
                second-response (download-version (:id v2))
                missing-response
                (download-version "00000000-0000-0000-0000-000000000000")]
            (is (= "deck one" (String. ^bytes (:body first-response) "UTF-8")))
            (is (= "deck two" (String. ^bytes (:body second-response) "UTF-8")))
            (is (str/includes? (get-in first-response [:headers "Content-Disposition"])
                               "keynote-slides.pptx"))
            (is (= 404 (:status missing-response)))))

        (testing "invalid conversation input is actionable and never appends"
          (let [before (count (:comments (files/by-id file-id)))
                organizer-path (str "/api/events/files-summit/files/" file-id "/comment")
                speaker-path (str "/api/submissions/" (:id own) "/files/" file-id "/comment")
                blank-response
                (handler
                  (as (mock/request :post organizer-path
                                    {"comment-id" "blank-comment" "body" "   "})
                      organizer-cookie))
                missing-id-response
                (handler
                  (as (mock/request :post organizer-path
                                    {"body" "This comment has no stable identity."})
                      organizer-cookie))
                too-long-response
                (handler
                  (as (mock/request :post speaker-path
                                    {"comment-id" "too-long-comment"
                                     "body" (apply str (repeat 2001 "x"))})
                      speaker-cookie))]
            (is (= 422 (:status blank-response)))
            (is (str/includes? (:body blank-response) "Write a comment first"))
            (is (= 422 (:status missing-id-response)))
            (is (str/includes? (:body missing-id-response)
                               "stable comment identity is required"))
            (is (= 422 (:status too-long-response)))
            (is (str/includes? (:body too-long-response)
                               "2,000 characters or fewer"))
            (is (= before (count (:comments (files/by-id file-id)))))))

        (testing "both sides share one file conversation"
          (is (= 303 (:status
                       (handler
                         (as (mock/request
                               :post
                               (str "/api/events/files-summit/files/" file-id "/comment")
                               {"comment-id" "organizer-comment"
                                "body" "Please update the customer logo."})
                             organizer-cookie)))))
          (is (str/includes? (:body (handler (as (mock/request :get "/portal")
                                                 speaker-cookie)))
                             "Please update the customer logo."))
          (is (= 303 (:status
                       (handler
                         (as (mock/request
                               :post
                               (str "/api/submissions/" (:id own) "/files/" file-id "/comment")
                               {"comment-id" "speaker-comment" "body" "Updated in v2."})
                             speaker-cookie)))))
          (is (str/includes?
                (:body (handler (as (mock/request :get "/events/files-summit/files")
                                    organizer-cookie)))
                "Updated in v2.")))

        (testing "the latest file and all-files ZIP are downloadable"
          (let [download (handler
                           (as (mock/request
                                 :get
                                 (str "/api/submissions/" (:id own) "/files/" file-id "/download"))
                               speaker-cookie))
                zip (handler (as (mock/request :get "/events/files-summit/files.zip")
                                 organizer-cookie))]
            (is (= 200 (:status download)))
            (is (= "deck two" (String. ^bytes (:body download) "UTF-8")))
            (is (= "private, no-store"
                   (get-in download [:headers "Cache-Control"])))
            (is (= "no-cache" (get-in download [:headers "Pragma"])))
            (is (= "0" (get-in download [:headers "Expires"])))
            (is (= "nosniff"
                   (get-in download [:headers "X-Content-Type-Options"])))
            (is (= "application/zip" (get-in zip [:headers "Content-Type"])))
            (is (= [80 75] (mapv #(bit-and 255 %) (take 2 (:body zip)))))))

        (testing "ownership is checked after the speaker-tier auth gate"
          (let [before (count (:log (store/snapshot)))
                response (handler
                           (as (multipart-file
                                 (str "/api/submissions/" (:id other) "/files/slides-final/upload")
                                 "stolen.pptx"
                                 "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                                 "stolen")
                               speaker-cookie))]
            (is (= 404 (:status response)))
            (is (= before (count (:log (store/snapshot))))))))

      (testing "a profile headshot is an upload with a public safe download"
        (is (= 303 (:status
                     (handler
                       (as (multipart-file
                             (str "/api/submissions/" (:id own) "/headshot")
                             "headshot.png" "image/png" "png-bytes")
                           speaker-cookie)))))
        (let [person (store/person-by-email "ada@example.com")
              url (get-in person [:profile :headshot-url])
              path (second (re-find #"https?://[^/]+(/headshots/.+)$" url))
              response (handler (mock/request :get path))
              portal-body (:body (handler
                                   (as (mock/request :get "/portal")
                                       speaker-cookie)))]
          (is (str/includes? url "/headshots/"))
          (is (= 200 (:status response)))
          (is (= "image/png" (get-in response [:headers "Content-Type"])))
          (is (= "public, max-age=0, must-revalidate"
                 (get-in response [:headers "Cache-Control"])))
          (is (str/includes? portal-body "Current published headshot"))
          (is (str/includes? portal-body (str "src=\"" url "\"")))
          (is (str/includes? portal-body "alt=\"Ada Speaker headshot\""))
          (let [library-body
                (:body (handler
                         (as (mock/request :get "/events/files-summit/files")
                             organizer-cookie)))]
            (is (re-find #"(?s)headshot\.png.*Headshot · v1 · Ada Speaker"
                         library-body))
            (is (re-find #"(?s)keynote-slides\.pptx.*Presentation · v2 · Algebraic Programs"
                         library-body))
            (is (str/includes? library-body "Uploaded by"))
            (is (re-find #"(?s)headshot\.png.*<td>ada@example\.com</td>"
                         library-body))))))))
