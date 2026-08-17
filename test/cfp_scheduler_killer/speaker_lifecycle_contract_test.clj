(ns cfp-scheduler-killer.speaker-lifecycle-contract-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.io.blob :as blob]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.speaker-tasks :as speaker-tasks]
   [cfp-scheduler-killer.speakers :as speakers]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock])
  (:import
   (java.time LocalDate LocalDateTime)))

(use-fixtures :each with-temp-store)

(defn- login-cookie [handler email]
  (let [token (auth/issue-token! email)
        response (handler (mock/request :get (str "/auth/" token)))]
    (first (str/split (first (get-in response [:headers "Set-Cookie"])) #";"))))

(defn- as [cookie request]
  (mock/header request "cookie" cookie))

(defn- multipart-file [path filename content-type content]
  (let [boundary "speaker-lifecycle-boundary"
        body (str "--" boundary "\r\n"
                  "Content-Disposition: form-data; name=\"file\"; filename=\""
                  filename "\"\r\n"
                  "Content-Type: " content-type "\r\n\r\n"
                  content "\r\n--" boundary "--\r\n")]
    (-> (mock/request :post path)
        (mock/content-type (str "multipart/form-data; boundary=" boundary))
        (mock/body body))))

(defn- create-submission! [event fields name email title]
  (let [params {:answer-talk-title title
                :answer-abstract "A practical experience report."
                :answer-session-format "Experience Report"
                :answer-track "Developer Practices"
                :answer-org-size ">10,000"
                :answer-industry "Technology"
                :answer-ai-transformation-history "2025."
                :answer-measurable-outcomes "Measured outcomes."
                :speaker-name name
                :speaker-email email
                :speaker-title "VP Engineering"
                :speaker-org "Lifecycle Labs"
                :speaker-bio "An experienced speaker."}]
    (submissions/create-submission!
      event
      (submissions/parse-answers fields params)
      (submissions/parse-speaker params)
      "form"
      "kaocha")))

(defn- install-headshot-task! [submission-id]
  (speaker-tasks/install!
    submission-id
    {:key "headshot"
     :label "Upload your headshot"
     :task-type "url"
     :required? true
     :due-on (LocalDate/of 2026 9 20)
     :file-kind "Headshot"}
    "organizer@example.com"))

(deftest headshot-and-onboarding-stay-in-sync-across-speaker-and-organizer-views
  (let [event (events/create-event!
                {:name "Speaker Lifecycle Summit"
                 :slug "speaker-lifecycle"
                 :tz "America/New_York"
                 :starts-on (LocalDate/of 2026 10 10)
                 :ends-on (LocalDate/of 2026 10 11)
                 :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
                 :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
                "kaocha")
        committee-id (:id (first (events/committees-for-event (:id event))))
        _ (committees/add-member!
            committee-id
            {:name "Olivia Organizer"
             :email "organizer@example.com"
             :role "chair"}
            "kaocha")
        fields (:fields (events/form-for-event (:id event)))
        ada (create-submission! event fields "Ada Speaker" "ada@example.com"
                                "Algebraic Programs")
        grace (create-submission! event fields "Grace Speaker" "grace@example.com"
                                  "Compilers at Sea")
        _ (install-headshot-task! (:id ada))
        _ (install-headshot-task! (:id grace))
        _ (speaker-tasks/install!
            (:id grace)
            {:key "slides-final"
             :label "Upload final slides"
             :task-type "file"
             :required? true
             :due-on (LocalDate/of 2026 9 25)
             :file-kind "Presentation"
             :instructions "Upload the final conference deck."}
            "organizer@example.com")
        handler (server/create-app)
        organizer-cookie (login-cookie handler "organizer@example.com")
        ada-cookie (login-cookie handler "ada@example.com")
        grace-cookie (login-cookie handler "grace@example.com")
        objects (atom {})]
    (binding [blob/*put-fn*
              (fn [source key]
                (let [location (str "memory://" key)]
                  (swap! objects assoc location
                         (java.nio.file.Files/readAllBytes (.toPath source)))
                  location))
              blob/*read-bytes-fn* #(get @objects %)]
      (testing "the organizer can request a headshot as a first-class deliverable"
        (let [body (:body (handler
                            (as organizer-cookie
                                (mock/request :get "/events/speaker-lifecycle/files"))))]
          (is (str/includes? body "value=\"Headshot\""))))

      (testing "a profile upload completes the matching onboarding task"
        (is (= 303
               (:status
                 (handler
                   (as ada-cookie
                       (multipart-file
                         (str "/api/submissions/" (:id ada) "/headshot")
                         "ada.png" "image/png" "ada-image"))))))
        (is (:done? (first (speaker-tasks/tasks-for-submission (:id ada))))))

      (testing "a checklist headshot upload updates the canonical profile and roster"
        (is (= 303
               (:status
                 (handler
                   (as grace-cookie
                       (multipart-file
                         (str "/api/submissions/" (:id grace)
                              "/files/headshot/upload")
                         "grace.jpg" "image/jpeg" "grace-image"))))))
        (let [profile-url (get-in (store/person-by-email "grace@example.com")
                                  [:profile :headshot-url])
              roster (speakers/roster-for-event (:id event))
              grace-row (some #(when (= "grace@example.com" (:email %)) %) roster)]
          (is (str/includes? profile-url "/headshots/"))
          (is (= profile-url (:headshot-url grace-row)))))

      (testing "the organizer sees complete and mixed per-speaker onboarding progress"
        (let [body (:body (handler
                            (as organizer-cookie
                                (mock/request
                                  :get "/events/speaker-lifecycle/deliverables"))))]
          (is (str/includes? body "Onboarding completion by speaker"))
          (is (re-find #"(?s)Ada Speaker.*1 of 1 complete.*All tasks complete" body))
          (is (re-find #"(?s)Grace Speaker.*1 of 2 complete.*1 open" body)))))))
