(ns cfp-scheduler-killer.portal-test
  "Speaker portal + the decision flow.

   The assertion that matters most is the INFORM GATE, checked against rendered
   HTML: a speaker must not be able to learn the committee's decision from the
   page before a human decided to tell them."
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.inform :as inform]
   [cfp-scheduler-killer.portal :as portal]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.schedule :as schedule]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.speaker-tasks :as speaker-tasks]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as sub]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock])
  (:import
   (java.time Instant LocalDateTime)))

(use-fixtures :each with-temp-store (fn [f] (reset! auth/tokens {}) (f)))

(defn- raw-app [] (server/create-app))
(defn- as [req cookie] (mock/header req "cookie" cookie))

(defn- login! [handler email]
  (let [token (auth/issue-token! email)
        resp (handler (mock/request :get (str "/auth/" token)))]
    {:cookie (first (str/split (first (get-in resp [:headers "Set-Cookie"])) #";"))
     :landing (get-in resp [:headers "Location"])}))

(def ^:private speaker-email "priya@example.com")

(defn- setup!
  "An event, a committee of one, and three submissions from two speakers."
  []
  (let [event (events/create-eais-event!
                {:name "Portal Test Summit" :slug "portal-test" :tz "America/New_York"
                 :support-email "support@example.com" :location "Charlotte, NC"
                 :starts-on (java.time.LocalDate/of 2026 10 14)
                 :ends-on (java.time.LocalDate/of 2026 10 15)
                 :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
                 :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
                "kaocha")
        cid (:id (first (events/committees-for-event (:id event))))
        gene (committees/add-member! cid {:name "Gene Kim" :email "gene@example.com"
                                          :role "chair"} "kaocha")
        ff (:fields (events/form-for-event (:id event)))
        mk (fn [title email]
             (let [params {:answer-talk-title title
                           :answer-abstract "Abstract."
                           :answer-session-format "Experience Report"
                           :answer-track "Developer Practices"
                           :answer-org-size ">10,000"
                           :answer-industry "Insurance"
                           :answer-ai-transformation-history "2023."
                           :answer-measurable-outcomes "Numbers."
                           :speaker-name "Priya Raghavan" :speaker-email email
                           :speaker-title "VP" :speaker-org "Meridian" :speaker-bio "Bio."}]
               (sub/create-submission! event (sub/parse-answers ff params)
                                       (sub/parse-speaker params) "form" "kaocha")))
        accepted (mk "Accepted talk" speaker-email)
        declined (mk "Declined talk" speaker-email)
        other (mk "Someone else's talk" "other@example.com")]
    (reviews/set-status! (:id accepted) "Accepted" "gene@example.com")
    (reviews/set-status! (:id declined) "Declined" "gene@example.com")
    {:event event :gene gene :accepted accepted :declined declined :other other}))

;; --- The inform gate --------------------------------------------------------

(deftest portal-visit-is-event-sourced-once
  (portal/record-visit! "event-1" "person-1" "speaker@example.com")
  (portal/record-visit! "event-1" "person-1" "speaker@example.com")
  (let [visits (filter #(= "portal.visited" (:type %)) (store/read-events))]
    (is (= 1 (count visits)))
    (is (= {:type "portal.visited"
            :actor "speaker@example.com"
            :event-id "event-1"
            :payload {:person-id "person-1"}}
           (select-keys (first visits) [:type :actor :event-id :payload])))))

;; INTENT-TEST: CFP-001
(deftest inform-gate-test
  (let [{:keys [event accepted declined]} (setup!)
        handler (raw-app)
        {:keys [cookie]} (login! handler speaker-email)
        portal-body #(:body (handler (as (mock/request :get "/portal") cookie)))]

    (testing "the committee HAS decided"
      (is (= "Accepted" (:status (store/submission-by-id (:id accepted)))))
      (is (= "Declined" (:status (store/submission-by-id (:id declined))))))

    (testing "…but the speaker's portal says Under review for both"
      (let [body (portal-body)]
        (is (str/includes? body "Accepted talk"))
        (is (str/includes? body "Declined talk"))
        (is (= 2 (count (re-seq #"Under review" body))))
        (is (not (str/includes? body "You&apos;re in! 🎉")))
        (is (not (str/includes? body "status-pill accepted")))
        (is (not (str/includes? body "status-pill declined")))
        (is (str/includes? body "This submission can no longer be edited."))
        (is (not (str/includes? body
                                "This submission can no longer be edited (it is Declined).")))))

    (testing "visible-status is the gate in code, too"
      (is (= "Under review" (portal/visible-status (store/submission-by-id (:id accepted)))))
      (is (= "Under review" (portal/visible-status (store/submission-by-id (:id declined))))))

    (testing "after informing, the real status appears — and only for that talk"
      (inform/inform! event (store/submission-by-id (:id accepted)) "gene@example.com")
      (let [body (portal-body)]
        (is (str/includes? body "status-pill accepted"))
        (is (str/includes? body "You&apos;re in! 🎉"))
        (is (= 1 (count (re-seq #"Under review" body)))
            "the declined talk is still under review — it hasn't been told")))

    (testing "after informing the decline, its communicated decision may render"
      (inform/inform! event (store/submission-by-id (:id declined)) "gene@example.com")
      (let [body (portal-body)]
        (is (str/includes? body "status-pill declined"))
        (is (str/includes? body
                           "This submission can no longer be edited (it is Declined)."))
        (is (not (str/includes? body "Under review")))))

    (testing "an informed accepted speaker sees the exact assigned slot"
      (let [room (schedule/add-room! event "Main Stage" "gene@example.com")]
        (schedule/place! event (:id accepted)
                         {:day "2026-10-14" :start "09:30"
                          :duration 45 :room-id (:id room)}
                         "gene@example.com")
        (let [body (portal-body)]
          (is (str/includes? body "Your schedule"))
          (is (str/includes? body "Day 1 — Oct 14"))
          (is (str/includes? body "9:30am-10:15am"))
          (is (str/includes? body "Main Stage")))))

    (testing "a speaker never sees another speaker's talk"
      (is (not (str/includes? (portal-body) "Someone else"))))))

(deftest notify-installs-tasks-test
  (let [{:keys [event accepted declined other]} (setup!)]
    (testing "an accepted talk has NO tasks before the speaker is told"
      (is (empty? (portal/tasks-for (:id accepted)))))

    (inform/inform! event (store/submission-by-id (:id accepted)) "gene@example.com")

    (testing "informing installs the event's default checklist"
      (let [ts (portal/tasks-for (:id accepted))]
        (is (= 5 (count ts)))
        (is (= ["confirm-bio" "headshot" "slides-url" "hotel-stay"
                "flight-reimbursement"]
               (mapv :key ts)))
        (is (= [-30 -30 -21 -45 -45] (mapv :due-offset-days ts)))
        (is (every? :required? ts))
        (is (every? (complement :done?) ts))))

    (testing "the notified fact is recorded with the status at the time"
      (let [s (store/submission-by-id (:id accepted))]
        (is (some? (:notified-at s)))
        (is (= "Accepted" (:notified-status s)))))

    (testing "a DECLINED speaker is informed but gets no checklist — nothing to do"
      (inform/inform! event (store/submission-by-id (:id declined)) "gene@example.com")
      (is (some? (:notified-at (store/submission-by-id (:id declined)))))
      (is (empty? (portal/tasks-for (:id declined)))))

    (testing "informing twice is a no-op, not a second notification"
      (is (nil? (inform/inform! event (store/submission-by-id (:id accepted)) "gene@example.com")))
      (is (= 1 (count (filter #(and (= "submission.notified" (:type %))
                                    (= (:id accepted) (get-in % [:payload :submission-id])))
                              (store/read-events))))))

    (testing "an internal queue status is NOT informable"
      (reviews/set-status! (:id other) "Accept Queue" "gene@example.com")
      (is (thrown? clojure.lang.ExceptionInfo
                   (inform/inform! event (store/submission-by-id (:id other)) "g"))))

    (testing "it all survives a reload"
      (store/load!)
      (is (= 5 (count (portal/tasks-for (:id accepted)))))
      (is (some? (:notified-at (store/submission-by-id (:id accepted))))))))

(deftest onboarding-recipe-instantiates-and-completes-a-form-task-test
  (let [{:keys [event accepted]} (setup!)
        handler (raw-app)
        {:keys [cookie]} (login! handler speaker-email)]
    (inform/inform! event (store/submission-by-id (:id accepted)) "gene@example.com")
    (let [installed (filter #(and (= "task.installed" (:type %))
                                  (= (:id accepted)
                                     (get-in % [:payload :submission-id])))
                            (store/read-events))
          hotel-task (some #(when (= "hotel-stay" (get-in % [:payload :key])) %)
                           installed)
          body (:body (handler (as (mock/request :get "/portal") cookie)))]
      (testing "the data recipe is snapshotted into per-speaker task facts"
        (is (= "speaker-onboarding-v1" (get-in hotel-task [:payload :recipe-id])))
        (is (= "form" (get-in hotel-task [:payload :task-type])))
        (is (= ["hotel-needed" "hotel-notes"]
               (mapv (comp name :id) (get-in hotel-task [:payload :fields])))))
      (testing "the portal renders the form from those shared field definitions"
        (is (str/includes? body "Hotel stay requirements"))
        (is (str/includes? body "Do you need a hotel room?"))
        (is (str/includes? body "Hotel accessibility or arrival notes")))
      (testing "the shared schema refuses an omitted required recipe answer"
        (let [response (handler
                         (as (mock/request
                               :post
                               (str "/api/submissions/" (:id accepted) "/task")
                               {:key "hotel-stay"})
                             cookie))]
          (is (= 422 (:status response)))
          (is (not (:done? (some #(when (= "hotel-stay" (:key %)) %)
                                 (portal/tasks-for (:id accepted))))))))
      (testing "submitting the recipe form stores its answers and completes the task"
        (let [response (handler
                         (as (mock/request
                               :post
                               (str "/api/submissions/" (:id accepted) "/task")
                               {:key "hotel-stay"
                                :answer-hotel-needed "Yes"
                                :answer-hotel-notes "Late arrival; step-free room."})
                             cookie))
              _ (store/load!)
              task (some #(when (= "hotel-stay" (:key %)) %)
                         (portal/tasks-for (:id accepted)))]
          (is (= 303 (:status response)))
          (is (:done? task))
          (is (= {:hotel-needed "Yes"
                  :hotel-notes "Late arrival; step-free room."}
                 (:value task))))))))

(deftest task-completion-test
  (let [{:keys [event accepted]} (setup!)]
    (inform/inform! event (store/submission-by-id (:id accepted)) "gene@example.com")

    (testing "a check task completes with no value"
      (portal/complete-task! (:id accepted) "confirm-bio" nil "speaker")
      (let [t (first (filter #(= "confirm-bio" (:key %)) (portal/tasks-for (:id accepted))))]
        (is (:done? t))))

    (testing "a url task is only done once it HAS a url"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"requires a URL"
                            (portal/complete-task! (:id accepted) "slides-url" "   " "speaker")))
      (is (not (:done? (first (filter #(= "slides-url" (:key %))
                                      (portal/tasks-for (:id accepted)))))))
      (portal/complete-task! (:id accepted) "slides-url" "https://slides.example.com/deck" "speaker")
      (let [t (first (filter #(= "slides-url" (:key %)) (portal/tasks-for (:id accepted))))]
        (is (:done? t))
        (is (= "https://slides.example.com/deck" (:value t)))))

    (testing "re-saving a url UPDATES the same task rather than adding a second"
      (portal/complete-task! (:id accepted) "slides-url" "https://slides.example.com/v2" "speaker")
      (is (= 5 (count (portal/tasks-for (:id accepted)))))
      (is (= "https://slides.example.com/v2"
             (:value (first (filter #(= "slides-url" (:key %))
                                    (portal/tasks-for (:id accepted))))))))

    (testing "progress counts what is actually done"
      (is (= {:total 5 :done 2} (portal/task-progress (:id accepted)))))

    (testing "an unknown task key does nothing"
      (is (nil? (portal/complete-task! (:id accepted) "not-a-task" nil "speaker"))))))

(deftest invalid-url-task-rerenders-the-speaker-portal-test
  (let [{:keys [event accepted]} (setup!)
        _ (inform/inform! event (store/submission-by-id (:id accepted)) "gene@example.com")
        handler (raw-app)
        {:keys [cookie]} (login! handler speaker-email)
        response (handler
                   (as (mock/request :post
                                     (str "/api/submissions/" (:id accepted) "/task")
                                     {:key "slides-url" :value "javascript:alert(1)"})
                       cookie))]
    (is (= 422 (:status response)))
    (is (str/includes? (:body response) "Enter a complete http:// or https:// URL."))
    (is (not (:done? (first (filter #(= "slides-url" (:key %))
                                    (portal/tasks-for (:id accepted)))))))))

(deftest portal-task-completion-records-the-authenticated-speaker-test
  (let [{:keys [event accepted]} (setup!)
        _ (inform/inform! event (store/submission-by-id (:id accepted)) "gene@example.com")
        handler (raw-app)
        {:keys [cookie]} (login! handler speaker-email)
        response (handler
                   (as (mock/request :post
                                     (str "/api/submissions/" (:id accepted) "/task")
                                     {:key "confirm-bio"})
                       cookie))
        completion (last (filter #(and (= "task.completed" (:type %))
                                       (= (:id accepted)
                                          (get-in % [:payload :submission-id])))
                                 (store/read-events)))]
    (is (= 303 (:status response)))
    (is (= speaker-email (:actor completion)))
    (is (= "confirm-bio" (get-in completion [:payload :key])))))

(deftest generic-task-post-cannot-complete-a-file-obligation-test
  (let [{:keys [event accepted]} (setup!)
        _ (inform/inform! event (store/submission-by-id (:id accepted)) "gene@example.com")
        _ (speaker-tasks/install! (:id accepted)
                                  {:key "release"
                                   :label "Signed speaker release"
                                   :task-type "file"
                                   :required? true
                                   :due-offset-days -14
                                   :instructions "Upload the signed release."
                                   :file-kind "release"}
                                  "gene@example.com")
        handler (raw-app)
        {:keys [cookie]} (login! handler speaker-email)
        response (handler
                   (as (mock/request :post
                                     (str "/api/submissions/" (:id accepted) "/task")
                                     {:key "release" :value "pretend-file"})
                       cookie))]
    (is (= 422 (:status response)))
    (is (str/includes? (:body response) "Upload a file to complete this deliverable."))
    (is (not (:done? (first (filter #(= "release" (:key %))
                                    (portal/tasks-for (:id accepted)))))))))

;; --- Profile ----------------------------------------------------------------

(deftest profile-update-test
  (let [{:keys []} (setup!)
        person (store/person-by-email speaker-email)]

    (testing "the first submission seeds the reusable speaker profile"
      (is (= {:tagline "VP" :org "Meridian" :bio "Bio."}
             (select-keys (:profile person) [:tagline :org :bio]))))

    (testing "a valid update merges into the profile"
      (let [r (portal/update-profile! (:id person)
                                      {:tagline "VP Engineering, Meridian"
                                       :bio "Priya runs underwriting platforms."
                                       :linkedin-url "https://linkedin.com/in/priya"}
                                      "speaker")]
        (is (:ok r))
        (let [p (store/person-by-id (:id person))]
          (is (= "VP Engineering, Meridian" (get-in p [:profile :tagline])))
          (is (= "https://linkedin.com/in/priya" (get-in p [:profile :linkedin-url]))))))

    (testing "a later edit leaves untouched keys alone"
      (portal/update-profile! (:id person) {:tagline "VP, Meridian Mutual"} "speaker")
      (let [p (store/person-by-id (:id person))]
        (is (= "VP, Meridian Mutual" (get-in p [:profile :tagline])))
        (is (= "Priya runs underwriting platforms." (get-in p [:profile :bio]))
            "the bio survived an edit that didn't mention it")))

    (testing "a bad URL is refused with a message, and nothing is written"
      (let [r (portal/update-profile! (:id person) {:headshot-url "not-a-url"} "speaker")]
        (is (false? (:ok r)))
        (is (contains? (:errors r) :headshot-url))
        (is (nil? (get-in (store/person-by-id (:id person)) [:profile :headshot-url])))))

    (testing "a repo-owned image is a valid headshot URL"
      (let [r (portal/update-profile! (:id person)
                                      {:headshot-url "/images/judge-sandbox/swyx.png"}
                                      "speaker")]
        (is (:ok r))
        (is (= "/images/judge-sandbox/swyx.png"
               (get-in (store/person-by-id (:id person)) [:profile :headshot-url])))))

    (testing "the profile prefills the next submission"
      (let [pre (portal/prefill-from-profile (store/person-by-id (:id person)))]
        (is (= "VP, Meridian Mutual" (:speaker-title pre)))
        (is (= "Priya runs underwriting platforms." (:speaker-bio pre)))
        (is (= speaker-email (:speaker-email pre)))
        (is (= "/images/judge-sandbox/swyx.png" (:speaker-headshot-url pre)))))

    (testing "an unchanged submit writes no event"
      (let [before (count (store/read-events))]
        (portal/update-profile! (:id person) {:tagline "VP, Meridian Mutual"} "speaker")
        (is (= before (count (store/read-events))))))

    (testing "it survives a reload"
      (store/load!)
      (is (= "VP, Meridian Mutual"
             (get-in (store/person-by-email speaker-email) [:profile :tagline]))))))

;; --- Editing answers --------------------------------------------------------

(deftest answers-update-test
  (let [{:keys [event accepted declined]} (setup!)]
    (inform/inform! event (store/submission-by-id (:id accepted)) "gene@example.com")

    (testing "an ACCEPTED speaker may still edit — no lock (swyx)"
      (is (portal/editable? event (store/submission-by-id (:id accepted))))
      (let [r (portal/update-answers! (:id accepted)
                                      {:answer-talk-title "Accepted talk (revised)"
                                       :answer-abstract "A better abstract."
                                       :answer-session-format "Experience Report"
                                       :answer-org-size ">10,000"
                                       :answer-industry "Insurance"
                                       :answer-ai-transformation-history "2023."
                                       :answer-measurable-outcomes "Numbers."}
                                      "speaker")]
        (is (:ok r))
        (is (= "Accepted talk (revised)"
               (get-in (store/submission-by-id (:id accepted)) [:answers :talk-title])))))

    (testing "the edit is recorded with before AND after"
      (let [e (last (filter #(= "submission.answers-updated" (:type %)) (store/read-events)))]
        (is (some #{"talk-title"} (get-in e [:payload :changed])))
        (is (= "Accepted talk" (get-in e [:payload :before :talk-title])))
        (is (= "Accepted talk (revised)" (get-in e [:payload :changes :talk-title])))))

    (testing "validation runs against the SNAPSHOT, so a blank required field is refused"
      (let [r (portal/update-answers! (:id accepted)
                                      {:answer-talk-title ""
                                       :answer-abstract "A better abstract."
                                       :answer-session-format "Experience Report"
                                       :answer-org-size ">10,000"
                                       :answer-industry "Insurance"
                                       :answer-ai-transformation-history "2023."
                                       :answer-measurable-outcomes "Numbers."}
                                      "speaker")]
        (is (false? (:ok r)))
        (is (contains? (:errors r) :talk-title))
        (is (= "Accepted talk (revised)"
               (get-in (store/submission-by-id (:id accepted)) [:answers :talk-title]))
            "nothing was written")))

    (testing "a field the LIVE form added later can't invalidate an old submission"
      (store/append! {:type "form.installed" :actor "kaocha"
                      :payload {:id (store/new-id) :event-id (:id event)
                                :fields (conj (:fields (events/form-for-event (:id event)))
                                              {:id :brand-new :type :text
                                               :label "Brand new" :required true})
                                :created-at (store/now-iso)}})
      (let [r (portal/update-answers! (:id accepted)
                                      {:answer-talk-title "Still fine"
                                       :answer-abstract "A better abstract."
                                       :answer-session-format "Experience Report"
                                       :answer-org-size ">10,000"
                                       :answer-industry "Insurance"
                                       :answer-ai-transformation-history "2023."
                                       :answer-measurable-outcomes "Numbers."}
                                      "speaker")]
        (is (:ok r) "the submission validates against the form it was submitted on")))

    (testing "a DECLINED talk is closed to edits"
      (is (not (portal/editable? event (store/submission-by-id (:id declined)))))
      (is (false? (:ok (portal/update-answers! (:id declined) {} "speaker")))))

    (testing "a PARTIAL edit touches only what it names — it never blanks the rest"
      (let [before (:answers (store/submission-by-id (:id accepted)))
            r (portal/update-answers! (:id accepted)
                                      {:answer-talk-title "Partial edit only"}
                                      "speaker")
            after (:answers (store/submission-by-id (:id accepted)))]
        (is (:ok r))
        (is (= "Partial edit only" (:talk-title after)))
        (is (= (dissoc before :talk-title) (dissoc after :talk-title))
            "every answer the request didn't mention survived")))

    (testing "the board reflects the speaker's edit"
      (let [row (first (filter #(= (:id accepted) (:id %))
                               (reviews/enriched-for-event (:id event))))]
        (is (= "Partial edit only" (get-in row [:answers :talk-title])))))))

;; --- Letters + alert rows ---------------------------------------------------

(deftest letters-test
  (let [{:keys [event accepted declined]} (setup!)]
    (testing "the acceptance letter merges every field"
      (let [l (inform/letter-for event (store/submission-by-id (:id accepted)))]
        (is (= "Your talk was accepted for Portal Test Summit" (:subject l)))
        (is (= speaker-email (:to l)))
        (is (str/includes? (:body l) "Hi Priya,"))
        (is (str/includes? (:body l) "\"Accepted talk\""))
        (is (str/includes? (:body l) "Portal Test Summit, Charlotte, NC"))
        (is (str/includes? (:body l) "support@example.com"))
        (is (not (str/includes? (:body l) "{{"))
            "no merge field is left unrendered")))

    (testing "the decline letter is gracious and names the talk"
      (let [l (inform/letter-for event (store/submission-by-id (:id declined)))]
        (is (str/includes? (:body l) "\"Declined talk\""))
        (is (str/includes? (:body l) "submit again next year"))
        (is (not (str/includes? (:body l) "{{")))))

    (testing "there is no letter for an internal queue status"
      (is (nil? (inform/letter-for event {:status "Accept Queue"}))))

    (testing "an unknown merge field renders empty rather than leaking braces"
      (is (= "Hi !" (inform/render-template "Hi {{nope}}!" {}))))))

(deftest alert-rows-test
  (let [{:keys [event accepted]} (setup!)]
    (testing "count-first rows, with the urgent one flagged"
      (let [rows (inform/alert-rows event)
            by-key (into {} (map (juxt :key identity)) rows)]
        (is (= 1 (:count (:awaiting by-key))) "one talk still Pending")
        (is (= 2 (:count (:uncommunicated by-key))) "accepted + declined, untold")
        (is (true? (:urgent? (:uncommunicated by-key))))
        (is (str/includes? (:href (:uncommunicated by-key)) "/inform"))))

    (testing "informing the accepted one drops the uncommunicated count"
      (inform/inform! event (store/submission-by-id (:id accepted)) "gene@example.com")
      (let [by-key (into {} (map (juxt :key identity)) (inform/alert-rows event))]
        (is (= 1 (:count (:uncommunicated by-key))))))

    (testing "an informed accepted speaker with no headshot raises the third row"
      (let [by-key (into {} (map (juxt :key identity)) (inform/alert-rows event))]
        (is (= 1 (:count (:missing by-key))))))

    (testing "rows with a zero count are simply absent"
      (let [quiet (events/create-event! {:name "Quiet" :slug "quiet" :tz "UTC"} "kaocha")]
        (is (empty? (inform/alert-rows quiet)))))))

;; --- Routing + the portal page ----------------------------------------------

(deftest speaker-routing-test
  (let [{:keys [event accepted other]} (setup!)
        handler (raw-app)]

    (testing "a speaker-only person lands on /portal, not /events"
      (is (= "/portal" (:landing (login! handler speaker-email)))))

    (testing "a committee member lands on /events"
      (is (= "/events" (:landing (login! handler "gene@example.com")))))

    (testing "a speaker who visits /events sees their own (possibly empty) events
              home — open sign-up (2026-08-10) made every session a potential
              organizer, so /events is theirs to browse and create from"
      (let [{:keys [cookie]} (login! handler speaker-email)
            resp (handler (as (mock/request :get "/events") cookie))]
        (is (= 200 (:status resp)))))

    (testing "a stranger with no submissions and no committee cannot sign in"
      ;; (bootstrap is closed here — a committee exists)
      (is (nil? (auth/issue-token! "nobody@example.com"))))

    (testing "the portal is speaker chrome: no organizer sidebar anywhere"
      (let [{:keys [cookie]} (login! handler speaker-email)
            body (:body (handler (as (mock/request :get "/portal") cookie)))]
        (is (not (str/includes? body "class=\"sidebar\"")))
        (is (not (str/includes? body "Review Board")))
        (is (not (str/includes? body "All events")))
        (is (str/includes? body "Your speaker portal"))))

    (testing "a speaker can edit their own talk through the portal"
      (let [{:keys [cookie]} (login! handler speaker-email)
            resp (handler (as (mock/request :post (str "/api/submissions/" (:id accepted) "/answers")
                                            {"answer-talk-title" "Edited via HTTP"
                                             "answer-abstract" "Abstract."
                                             "answer-session-format" "Experience Report"
                                             "answer-org-size" ">10,000"
                                             "answer-industry" "Insurance"
                                             "answer-ai-transformation-history" "2023."
                                             "answer-measurable-outcomes" "Numbers."})
                              cookie))]
        (is (= 303 (:status resp)))
        (is (= "Edited via HTTP"
               (get-in (store/submission-by-id (:id accepted)) [:answers :talk-title])))
        (is (= speaker-email
               (:actor (last (filter #(= "submission.answers-updated" (:type %))
                                     (store/read-events))))))))

    (testing "profile edits retain the authenticated speaker in immutable history"
      (let [{:keys [cookie]} (login! handler speaker-email)
            resp (handler (as (mock/request :post "/api/profile"
                                            {"bio" "Speaker-authored route bio"})
                              cookie))]
        (is (= 303 (:status resp)))
        (is (= "Speaker-authored route bio"
               (get-in (store/person-by-email speaker-email) [:profile :bio])))
        (is (= speaker-email
               (:actor (last (filter #(= "person.profile-updated" (:type %))
                                     (store/read-events))))))))

    (testing "closing the CFP makes the portal read-only and refuses direct POSTs"
      (events/update-event-details! (:id event)
                                    {:cfp-closes-at (Instant/parse "2001-01-01T17:00:00Z")}
                                    "gene@example.com")
      (let [{:keys [cookie]} (login! handler speaker-email)
            edit-page (:body (handler (as (mock/request
                                            :get
                                            (str "/portal?edit=" (:id accepted)))
                                          cookie)))
            before (get-in (store/submission-by-id (:id accepted)) [:answers :talk-title])
            resp (handler (as (mock/request
                                :post
                                (str "/api/submissions/" (:id accepted) "/answers")
                                {"answer-talk-title" "Too late"})
                              cookie))]
        (is (str/includes? edit-page "The call closed Jan 1 — editing is locked."))
        (is (not (str/includes? edit-page "Editing:")))
        (is (= 422 (:status resp)))
        (is (str/includes? (:body resp) "The call closed Jan 1 — editing is locked."))
        (is (= before (get-in (store/submission-by-id (:id accepted)) [:answers :talk-title])))))

    (testing "a speaker CANNOT touch someone else's talk"
      (let [{:keys [cookie]} (login! handler speaker-email)
            before (get-in (store/submission-by-id (:id other)) [:answers :talk-title])
            resp (handler (as (mock/request :post (str "/api/submissions/" (:id other) "/answers")
                                            {"answer-talk-title" "Hijacked"})
                              cookie))]
        (is (= 404 (:status resp)))
        (is (= before (get-in (store/submission-by-id (:id other)) [:answers :talk-title])))))

    (testing "and the inform page is committee-only — a speaker without a seat
              on THIS event gets the wrong-event 403 (open sign-up 2026-08-10
              retired the gentle portal redirect; the wall is per-conference)"
      (let [{:keys [cookie]} (login! handler speaker-email)
            resp (handler (as (mock/request :get (str "/events/" (:slug event) "/inform")) cookie))]
        (is (= 403 (:status resp)))))))

(deftest inform-page-test
  (let [{:keys [event accepted]} (setup!)
        handler (raw-app)
        {:keys [cookie]} (login! handler "gene@example.com")
        page #(:body (handler (as (mock/request :get (str "/events/" (:slug event) "/inform")) cookie)))]

    (testing "queued decisions are grouped by outgoing letter, shown IN FULL"
      (let [body (page)]
        (is (str/includes? body "Accepted — 1 speaker"))
        (is (str/includes? body "Declined — 1 speaker"))
        (is (str/includes? body "Your talk was accepted for Portal Test Summit"))
        (is (str/includes? body "Hi Priya,"))
        (is (str/includes? body "Inform all 1"))
        (is (not (str/includes? body "SMTP is not configured")))))

    (testing "the banner shows on the dashboard and the board"
      (doseq [path [(str "/events/" (:slug event))
                    (str "/events/" (:slug event) "/board")]]
        (let [body (:body (handler (as (mock/request :get path) cookie)))]
          (is (str/includes? body "2 decisions not yet communicated") path)
          (is (str/includes? body "Inform speakers") path))))

    (testing "informing one removes it from the queue and records the receipt"
      (handler (as (mock/request :post (str "/api/submissions/" (:id accepted) "/inform")) cookie))
      (let [body (page)]
        (is (not (str/includes? body "Accepted — 1 speaker")))
        (is (str/includes? body "1 decision already communicated"))
        (is (not (str/includes? body "Nothing here has been sent")))
        (is (str/includes? body "Already informed (1)"))))

    (testing "inform-all clears the rest — the empty queue is a HANDOFF: the
              pipeline cascade renders instead of a dead 'nothing waiting' box
              (Gene ratified 2026-08-11, bead 4d3)"
      (handler (as (mock/request :post (str "/api/events/" (:slug event) "/inform-all")) cookie))
      (is (empty? (inform/pending-decisions (:id event))))
      (let [body (page)]
        (is (str/includes? body "pipeline-cascade"))
        (is (str/includes? body "we&apos;re clear"))))

    (testing "the log tells the story in human sentences"
      (let [body (:body (handler (as (mock/request :get (str "/events/" (:slug event) "/log")) cookie)))]
        (is (str/includes? body "submission.notified"))))))
