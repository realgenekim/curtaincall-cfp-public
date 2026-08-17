(ns cfp-scheduler-killer.speaker-management-eval-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.files :as files]
   [cfp-scheduler-killer.inform :as inform]
   [cfp-scheduler-killer.mail :as mail]
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
   (java.time LocalDate LocalDateTime)))

(use-fixtures :each with-temp-store (fn [f] (reset! auth/tokens {}) (f)))

(defn- make-event! []
  (let [event (events/create-event!
                {:name "Speaker Eval Summit"
                 :slug "speaker-eval"
                 :tz "America/Los_Angeles"
                 :support-email "organizer@example.com"
                 :starts-on (LocalDate/of 2027 5 12)
                 :ends-on (LocalDate/of 2027 5 14)
                 :cfp-opens-at (LocalDateTime/of 2026 1 1 0 0)
                 :cfp-closes-at (LocalDateTime/of 2028 1 1 0 0)}
                "kaocha")
        committee-id (:id (first (events/committees-for-event (:id event))))]
    (committees/add-member!
      committee-id
      {:name "Jordan Organizer" :email "organizer@example.com" :role "chair"}
      "kaocha")
    event))

(defn- make-submission! [event name email title]
  (let [fields (:fields (events/form-for-event (:id event)))
        params {:answer-talk-title title
                :answer-abstract "A speaker-management characterization."
                :answer-session-format "Experience Report"
                :answer-track "Developer Practices"
                :answer-org-size "1,000-10,000"
                :answer-industry "Technology"
                :answer-ai-transformation-history "2025"
                :answer-measurable-outcomes "Faster delivery"
                :speaker-name name
                :speaker-email email
                :speaker-title "Principal Engineer"
                :speaker-org "Example Labs"
                :speaker-bio "Original bio"}]
    (submissions/create-submission!
      event
      (submissions/parse-answers fields params)
      (submissions/parse-speaker params)
      "form"
      "kaocha")))

(defn- login-cookie [handler email]
  (let [token (auth/issue-token! email)
        response (handler (mock/request :get (str "/auth/" token)))]
    (first (str/split (first (get-in response [:headers "Set-Cookie"])) #";"))))

(defn- as [cookie request]
  (mock/header request "cookie" cookie))

(defn- multipart-profile [fields filename content-type content]
  (let [boundary "speaker-profile-boundary"
        field-part (fn [[name value]]
                     (str "--" boundary "\r\n"
                          "Content-Disposition: form-data; name=\"" name "\"\r\n\r\n"
                          value "\r\n"))
        body (str (apply str (map field-part fields))
                  "--" boundary "\r\n"
                  "Content-Disposition: form-data; name=\"file\"; filename=\""
                  filename "\"\r\n"
                  "Content-Type: " content-type "\r\n\r\n"
                  content "\r\n--" boundary "--\r\n")]
    (-> (mock/request :post "/api/profile")
        (mock/content-type (str "multipart/form-data; boundary=" boundary))
        (mock/body body))))

(deftest profile-and-headshot-save-together-and-reach-the-organizer-test
  (let [event (make-event!)
        submission (make-submission! event "Priya Raman" "priya@example.com" "Fast CI")
        person-id (get-in submission [:speakers 0 :person-id])
        handler (server/create-app)
        speaker-cookie (login-cookie handler "priya@example.com")
        organizer-cookie (login-cookie handler "organizer@example.com")]
    (speaker-tasks/install!
      (:id submission)
      {:key "headshot" :label "Upload your headshot" :task-type "url"
       :required? true :due-on (LocalDate/of 2027 4 30)}
      "organizer@example.com")

    (testing "one profile form owns text fields, social fields, and the file input"
      (let [body (:body (handler (as speaker-cookie (mock/request :get "/portal"))))]
        (is (re-find
              #"(?s)<form action=\"/api/profile\".*enctype=\"multipart/form-data\".*name=\"bio\".*name=\"linkedin-url\".*name=\"file\".*Save profile.*</form>"
              body))))

    (testing "one save durably updates the profile and completes its headshot task"
      (with-redefs [files/upload! (constantly {:id "profile-headshot-1"})]
        (let [response
              (handler
                (as speaker-cookie
                    (multipart-profile
                      [["bio" "SBEK-PORTAL-BIO-01"]
                       ["linkedin-url" "https://linkedin.example/priya"]]
                      "headshot.png" "image/png" "png-bytes")))]
          (is (= 303 (:status response)))
          (is (= "/portal?saved=1&headshot=1"
                 (get-in response [:headers "Location"])))))
      (let [profile (:profile (store/person-by-id person-id))
            task (first (speaker-tasks/tasks-for-submission (:id submission)))]
        (is (= "SBEK-PORTAL-BIO-01" (:bio profile)))
        (is (= "https://linkedin.example/priya" (:linkedin-url profile)))
        (is (str/ends-with? (:headshot-url profile) "/headshots/profile-headshot-1"))
        (is (:done? task))))

    (testing "the organizer record immediately renders the same profile and image"
      (let [roster-body (:body
                          (handler
                            (as organizer-cookie
                                (mock/request :get "/events/speaker-eval/speakers"))))
            ;; 2026-08-17 redesign (7b54469): bio/LinkedIn text moved off the
            ;; roster row onto the extracted edit page at
            ;; /events/:slug/speakers/:person-id; the headshot still renders
            ;; inline on the roster row as the person's avatar image.
            edit-body (:body
                        (handler
                          (as organizer-cookie
                              (mock/request :get (str "/events/speaker-eval/speakers/" person-id)))))]
        (is (str/includes? edit-body "SBEK-PORTAL-BIO-01"))
        (is (str/includes? edit-body "https://linkedin.example/priya"))
        (is (str/includes? roster-body "/headshots/profile-headshot-1"))))))

(deftest portal-checklist-states-are-explicit-and-persistent-test
  (let [event (make-event!)
        submission (make-submission! event "Priya Raman" "priya@example.com" "Fast CI")
        handler (server/create-app)
        speaker-cookie (login-cookie handler "priya@example.com")]
    (reviews/set-status! (:id submission) "Accepted" "organizer@example.com")
    (inform/inform! event (store/submission-by-id (:id submission)) "organizer@example.com")

    (testing "assigned tasks start with literal due dates and incomplete state"
      (let [body (:body (handler (as speaker-cookie (mock/request :get "/portal"))))]
        (is (= 5 (count (re-seq #">Incomplete</span>" body))))
        (is (str/includes? body "due 2027-04-12"))))

    (testing "completion survives reload while untouched work stays incomplete"
      (is (= 303
             (:status
               (handler
                 (as speaker-cookie
                     (mock/request :post
                                   (str "/api/submissions/" (:id submission) "/task")
                                   {:key "confirm-bio"}))))))
      (store/load!)
      (let [body (:body (handler (as speaker-cookie (mock/request :get "/portal"))))]
        (is (= 1 (count (re-seq #">Complete</span>" body))))
        (is (= 4 (count (re-seq #">Incomplete</span>" body))))
        (is (str/includes? body "1 of 5 done"))))))

(deftest selected-speaker-group-send-is-audited-per-recipient-test
  (let [event (make-event!)
        priya (make-submission! event "Priya Raman" "priya@example.com" "Fast CI")
        marcus (make-submission! event "Marcus Okafor" "marcus@example.com" "Safe deploys")
        priya-id (get-in priya [:speakers 0 :person-id])
        marcus-id (get-in marcus [:speakers 0 :person-id])
        handler (server/create-app)
        organizer-cookie (login-cookie handler "organizer@example.com")]
    (store/await-sinks!)

    (testing "the compact queue exposes the people without a launch form"
      (let [body (:body
                   (handler
                     (as organizer-cookie
                         (mock/request :get "/events/speaker-eval/comms"))))]
        (is (str/includes? body "Waiting for approval"))
        (is (str/includes? body "Priya Raman"))
        (is (str/includes? body "Marcus Okafor"))
        (is (not (str/includes? body "id=\"comms-recipients\"")))))

    (testing "clearing the group cannot silently send to the preview speaker"
      (let [response
            (handler
              (as organizer-cookie
                  (mock/request
                    :post
                    "/api/events/speaker-eval/comms/approve-all"
                    {"command" "send-message"
                     "template" "reminder"
                     "submission-id" (:id priya)
                     "subject" "Welcome to DevFlow Conf 2027 speakers"
                     "body" "Welcome, speakers. We are glad you are here."})))]
        (is (= 422 (:status response)))
        (is (empty? (filter #(= "speaker-message" (:kind %))
                            (mail/history (:id event)))))))

    (testing "one reviewed send creates one successful history entry per speaker"
      (let [response
            (handler
              (as organizer-cookie
                  (mock/request
                    :post
                    "/api/events/speaker-eval/comms/approve-all"
                    {"command" "send-message"
                     "template" "reminder"
                     "submission-id" (:id priya)
                     "recipient-ids" [priya-id marcus-id]
                     "subject" "Welcome to DevFlow Conf 2027 speakers"
                     "body" "Welcome, speakers. We are glad you are here."})))
            location (get-in response [:headers "Location"])
            messages (filterv #(and (= "speaker-message" (:kind %))
                                    (= "email.sent" (:type %)))
                              (mail/history (:id event)))]
        (is (= 303 (:status response)))
        (is (str/ends-with? location "?delivery=sent&sent-count=2"))
        (is (= #{"priya@example.com" "marcus@example.com"}
               (set (map :to messages))))
        (is (every? #(= "email.sent" (:type %)) messages))
        (is (every? #(= "Welcome to DevFlow Conf 2027 speakers" (:subject %))
                    messages))
        (let [body (:body (handler (as organizer-cookie (mock/request :get location))))]
          (is (str/includes? body "Messages sent to 2 speakers and recorded in history"))
          (is (str/includes? body "priya@example.com"))
          (is (str/includes? body "marcus@example.com")))))))
