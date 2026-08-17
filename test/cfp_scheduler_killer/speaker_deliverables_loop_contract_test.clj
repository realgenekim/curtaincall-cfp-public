(ns cfp-scheduler-killer.speaker-deliverables-loop-contract-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.files :as files]
   [cfp-scheduler-killer.inform :as inform]
   [cfp-scheduler-killer.io.blob :as blob]
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

(defn- login-cookie [handler email]
  (let [token (auth/issue-token! email)
        response (handler (mock/request :get (str "/auth/" token)))]
    (first (str/split (first (get-in response [:headers "Set-Cookie"])) #";"))))

(defn- as [cookie request]
  (mock/header request "cookie" cookie))

(defn- multipart-file [path filename content-type content]
  (let [boundary "speaker-deliverables-loop-boundary"
        body (str "--" boundary "\r\n"
                  "Content-Disposition: form-data; name=\"file\"; filename=\""
                  filename "\"\r\n"
                  "Content-Type: " content-type "\r\n\r\n"
                  content "\r\n--" boundary "--\r\n")]
    (-> (mock/request :post path)
        (mock/content-type (str "multipart/form-data; boundary=" boundary))
        (mock/body body))))

(def submission-params
  {"answer-talk-title" "A Complete Speaker Loop"
   "answer-abstract" "A joint technical and business experience report."
   "answer-audience-level" "Advanced"
   "answer-session-format" "Talk"
   "answer-session-length" "45 minutes"
   "speaker-name" "Priya Primary"
   "speaker-email" "priya-loop@example.com"
   "speaker-title" "VP Engineering"
   "speaker-org" "Loop Mutual"
   "speaker-bio" "Priya leads engineering."
   "speaker-2-role" "Co-speaker"
   "speaker-2-name" "Rae Partner"
   "speaker-2-email" "rae-loop@example.com"
   "speaker-2-title" "VP Claims"
   "speaker-2-org" "Loop Mutual"
   "speaker-2-bio" "Rae leads claims transformation."})

(deftest submission-to-deliverables-is-one-coherent-speaker-loop
  (let [event (events/create-event!
                {:name "Deliverables Loop Summit"
                 :slug "deliverables-loop"
                 :tz "America/New_York"
                 :support-email "organizer-loop@example.com"
                 :starts-on (LocalDate/of 2026 10 14)
                 :ends-on (LocalDate/of 2026 10 15)
                 :cfp-opens-at (LocalDateTime/of 2026 1 1 0 0)
                 :cfp-closes-at (LocalDateTime/of 2027 1 1 0 0)}
                "kaocha")
        committee-id (:id (first (events/committees-for-event (:id event))))
        _ (committees/add-member!
            committee-id
            {:name "Olivia Organizer"
             :email "organizer-loop@example.com"
             :role "chair"}
            "kaocha")
        handler (server/create-app)
        submitted (handler
                    (mock/request :post "/api/cfp/deliverables-loop/submit"
                                  submission-params))
        submission (first (submissions/for-event (:id event)))
        organizer-cookie (login-cookie handler "organizer-loop@example.com")
        priya-cookie (login-cookie handler "priya-loop@example.com")
        rae-cookie (login-cookie handler "rae-loop@example.com")
        portal #(handler (as % (mock/request :get "/portal")))
        objects (atom {})]
    (testing "submission confirms the handoff and invites every submitted speaker"
      (is (= 303 (:status submitted)))
      (is (str/starts-with? (get-in submitted [:headers "Location"])
                            "/cfp/deliverables-loop/submitted/"))
      (is (= #{"priya-loop@example.com" "rae-loop@example.com"}
             (->> (mail/history (:id event))
                  (filter #(= "portal-invite" (:kind %)))
                  (map :to)
                  set))))

    (reviews/set-status! (:id submission) "Accepted" "organizer-loop@example.com")

    (testing "acceptance remains private until the deliberate inform act"
      (doseq [cookie [priya-cookie rae-cookie]]
        (let [body (:body (portal cookie))]
          (is (str/includes? body "Under review"))
          (is (str/includes? body "Onboarding tasks will appear"))))
      (is (empty? (speaker-tasks/tasks-for-submission (:id submission)))))

    (inform/inform! event (store/submission-by-id (:id submission))
                    "organizer-loop@example.com")

    (testing "the communicated decision reaches every speaker who owes the work"
      (let [letters (->> (mail/history (:id event))
                         (filter #(= "decision" (:kind %)))
                         vec)]
        (is (= #{"priya-loop@example.com" "rae-loop@example.com"}
               (set (map :to letters))))
        (is (= #{"Priya" "Rae"}
               (set (keep #(second (re-find #"Hi ([^,]+)," (:body %))) letters)))))
      (doseq [cookie [priya-cookie rae-cookie]]
        (let [body (:body (portal cookie))]
          (is (str/includes? body "status-pill accepted"))
          (is (str/includes? body "Confirm your bio"))
          (is (str/includes? body "Upload your headshot"))
          (is (str/includes? body "Upload your slides"))
          (is (str/includes? body "Hotel stay requirements"))
          (is (str/includes? body "Flight reimbursement details"))))
      (is (= ["confirm-bio" "headshot" "slides-url" "hotel-stay"
              "flight-reimbursement"]
             (mapv :key (speaker-tasks/tasks-for-submission (:id submission)))))
      (is (some? (:notified-at (store/submission-by-id (:id submission)))))
      (is (= "Accepted" (:notified-status
                          (store/submission-by-id (:id submission)))))
      (let [before (count (filter #(= "decision" (:kind %))
                                  (mail/history (:id event))))]
        (is (nil? (inform/inform! event
                                  (store/submission-by-id (:id submission))
                                  "organizer-loop@example.com")))
        (is (= before
               (count (filter #(= "decision" (:kind %))
                              (mail/history (:id event))))))))

    (binding [blob/*put-fn*
              (fn [source key]
                (let [location (str "memory://" key)]
                  (swap! objects assoc location
                         (java.nio.file.Files/readAllBytes (.toPath source)))
                  location))
              blob/*read-bytes-fn* #(get @objects %)]
      (testing "speakers complete bio, headshot, and slides through their portal routes"
        (is (= 303
               (:status
                 (handler
                   (as priya-cookie
                       (mock/request :post
                                     (str "/api/submissions/" (:id submission) "/task")
                                     {:key "confirm-bio"}))))))
        (is (= 303
               (:status
                 (handler
                   (as rae-cookie
                       (multipart-file
                         (str "/api/submissions/" (:id submission)
                              "/files/headshot/upload")
                         "rae-headshot.png" "image/png" "rae-image"))))))
        (is (= 303
               (:status
                 (handler
                   (as rae-cookie
                       (multipart-file
                         (str "/api/submissions/" (:id submission)
                              "/files/slides-url/upload")
                         "loop-slides.pdf" "application/pdf" "loop deck")))))))

      (testing "all file evidence stays attached to the submission"
        (let [attached (files/for-submission (:id submission))]
          (is (= #{"Headshot" "Presentation"} (set (map :kind attached))))
          (is (every? #(= (:id submission) (:submission-id %)) attached))
          (is (every? (comp seq :versions) attached)))
        (is (= 3 (count (filter :done?
                                (speaker-tasks/tasks-for-submission
                                  (:id submission)))))))

      (testing "the organizer sees the completed work without leaving Curtain Call"
        (let [deliverables (:body
                             (handler
                               (as organizer-cookie
                                   (mock/request
                                     :get "/events/deliverables-loop/deliverables"))))
              file-library (:body
                             (handler
                               (as organizer-cookie
                                   (mock/request :get
                                                 "/events/deliverables-loop/files"))))]
          (is (str/includes? deliverables "Uploaded / complete"))
          (is (str/includes? deliverables "loop-slides.pdf"))
          (is (str/includes? file-library "rae-headshot.png"))
          (is (str/includes? file-library "loop-slides.pdf")))))))
