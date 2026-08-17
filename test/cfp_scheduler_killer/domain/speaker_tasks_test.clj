(ns cfp-scheduler-killer.domain.speaker-tasks-test
  (:require
   [cfp-scheduler-killer.domain.speaker-tasks :as tasks]
   [cfp-scheduler-killer.folds :as folds]
   [clojure.test :refer [deftest is testing]])
  (:import
   (java.time Instant LocalDate)))

(def event-day (LocalDate/of 2026 10 10))
(def today (LocalDate/of 2026 10 1))

(def base-state
  (assoc folds/empty-state
         :events {"summit" {:id "event-1"
                            :slug "summit"
                            :name "Summit"
                            :starts-on event-day}}
         :submissions {"s-1" {:id "s-1"
                              :event-id "event-1"
                              :answers {:talk-title "Algebraic Programs"}
                              :speakers [{:person-id "p-1"
                                          :name "Ada Lovelace"
                                          :email "ada@example.com"}]}
                       "s-2" {:id "s-2"
                              :event-id "event-1"
                              :answers {:talk-title "Simple Made Easy"}
                              :speakers [{:person-id "p-2"
                                          :name "Rich Hickey"
                                          :email "rich@example.com"}]}}
         :tasks {["s-1" "slides"] {:submission-id "s-1"
                                   :key "slides"
                                   :label "Slides draft"
                                   :type "url"
                                   :due-offset-days -21}
                 ["s-2" "confirm"] {:submission-id "s-2"
                                    :key "confirm"
                                    :label "Confirm participation"
                                    :type "check"
                                    :due-offset-days -60}}))

(deftest deadlines-are-derived-from-the-current-event-date
  (let [task (get-in base-state [:tasks ["s-1" "slides"]])
        moved-event-day (.plusDays event-day 14)]
    (is (= (LocalDate/of 2026 9 19)
           (tasks/due-date event-day task)))
    (is (= (LocalDate/of 2026 10 3)
           (tasks/due-date moved-event-day task)))
    (is (= (LocalDate/of 2026 9 19)
           (tasks/due-date event-day {:key "slides-url"}))
        "Existing task facts gain the evidence-based deadline without migration.")
    (is (= -21 (:due-offset-days task))
        "Moving an event recomputes the deadline without rewriting the task fact.")))

(deftest outstanding-ledger-is-a-derived-overdue-first-projection
  (let [rows (tasks/outstanding-obligations base-state "event-1" today)]
    (is (= [["s-2" "confirm"] ["s-1" "slides"]]
           (mapv (juxt :submission-id :key) rows)))
    (is (= [:overdue :overdue] (mapv :status rows)))
    (is (= [51 12] (mapv :days-overdue rows)))
    (is (= ["Rich Hickey" "Ada Lovelace"] (mapv :speaker-name rows)))
    (is (= ["Simple Made Easy" "Algebraic Programs"]
           (mapv :talk-title rows)))))

(deftest completed-work-is-not-outstanding-and-unscheduled-work-is-safe
  (let [completed (assoc-in base-state
                            [:tasks ["s-2" "confirm"] :completed-at]
                            "2026-09-01T00:00:00Z")
        unscheduled (-> completed
                        (assoc-in [:events "summit" :starts-on] nil)
                        (assoc-in [:tasks ["s-1" "slides"] :last-chased-at]
                                  (Instant/parse "2026-09-30T00:00:00Z")))
        [row] (tasks/outstanding-obligations unscheduled "event-1" today)]
    (is (= "s-1" (:submission-id row)))
    (is (= :unscheduled (:status row)))
    (is (nil? (:due-on row)))
    (is (= (Instant/parse "2026-09-30T00:00:00Z") (:last-chased-at row)))))

(deftest due-soon-is-a-distinct-actionable-state
  (let [[row] (tasks/outstanding-obligations
                (update base-state :tasks select-keys [["s-1" "slides"]])
                "event-1"
                (LocalDate/of 2026 9 10))]
    (is (= :due-soon (:status row)))
    (is (= (LocalDate/of 2026 9 19) (:due-on row)))))

(deftest explicit-file-request-dates-win-over-derived-defaults
  (let [due-on (LocalDate/of 2026 9 25)
        command {:submission-id "s-1" :key "slides-final"
                 :label "Final slides" :task-type "file" :required? true
                 :due-on due-on :file-kind "Presentation"
                 :instructions "Upload the communications-approved deck."
                 :actor "ann@example.com" :at "2026-08-10T12:00:00Z"}
        decision (tasks/decide-install base-state command)
        installed (folds/fold-event base-state (first (:facts decision)))]
    (is (= ["task.installed"] (mapv :type (:facts decision))))
    (is (= due-on (tasks/due-date event-day
                                  (get-in installed [:tasks ["s-1" "slides-final"]]))))
    (is (empty? (:facts (tasks/decide-install installed command))))
    (is (= :task-key-conflict
           (get-in (tasks/decide-install installed (assoc command :label "Different"))
                   [:rejected :type])))))

(deftest completion-is-an-idempotent-decision
  (let [command {:submission-id "s-1"
                 :task-key "slides"
                 :value "https://example.com/slides"
                 :actor "ada@example.com"
                 :at "2026-10-01T12:00:00Z"}
        decision (tasks/decide-complete base-state command)
        completed-state (folds/fold-event base-state (first (:facts decision)))]
    (is (= ["task.completed"] (mapv :type (:facts decision))))
    (is (= [] (:facts (tasks/decide-complete completed-state command))))
    (is (= :task-not-found
           (get-in (tasks/decide-complete base-state
                                          (assoc command :task-key "missing"))
                   [:rejected :type])))
    (is (= :value-required
           (get-in (tasks/decide-complete base-state
                                          (assoc command :value "  "))
                   [:rejected :type])))
    (testing "URL obligations accept only absolute HTTP(S) destinations"
      (doseq [value ["done" "/slides" "javascript:alert(1)" "data:text/plain,nope"
                     "https:///missing-host"]]
        (is (= :invalid-url
               (get-in (tasks/decide-complete base-state
                                              (assoc command :value value))
                       [:rejected :type]))
            value))
      (doseq [value ["http://slides.example.com/deck"
                     "https://slides.example.com/deck?q=1"]]
        (is (= ["task.completed"]
               (mapv :type (:facts (tasks/decide-complete
                                     base-state (assoc command :value value)))))
            value)))))

(deftest file-obligations-require-a-matching-upload-version-test
  (let [command {:submission-id "s-1" :task-key "release"
                 :value "file-1" :actor "ada@example.com"
                 :at "2026-10-01T12:00:00Z"}
        file-task (assoc-in base-state [:tasks ["s-1" "release"]]
                            {:submission-id "s-1" :key "release"
                             :label "Signed release" :task-type "file"})]
    (testing "a generic value cannot manufacture file completion"
      (is (= :file-upload-required
             (get-in (tasks/decide-complete file-task command)
                     [:rejected :type]))))
    (testing "a versioned file attached to this exact task is sufficient evidence"
      (let [with-upload (assoc-in file-task [:files "file-1"]
                                  {:id "file-1" :submission-id "s-1"
                                   :task-key "release"
                                   :versions [{:id "version-1"}]})]
        (is (= ["task.completed"]
               (mapv :type (:facts (tasks/decide-complete with-upload command)))))))
    (testing "a file uploaded for a different task cannot be reused as evidence"
      (let [wrong-task (assoc-in file-task [:files "file-1"]
                                 {:id "file-1" :submission-id "s-1"
                                  :task-key "slides"
                                  :versions [{:id "version-1"}]})]
        (is (= :file-upload-required
               (get-in (tasks/decide-complete wrong-task command)
                       [:rejected :type])))))))

(deftest legacy-upload-labelled-url-tasks-accept-their-versioned-file-id
  (let [legacy-task (assoc-in base-state [:tasks ["s-1" "slides"] :file-kind]
                              "Presentation")
        with-upload (assoc-in legacy-task [:files "file-1"]
                              {:id "file-1" :submission-id "s-1"
                               :task-key "slides" :kind "Presentation"
                               :versions [{:id "version-1"}]})
        command {:submission-id "s-1" :task-key "slides" :value "file-1"
                 :actor "ada@example.com" :at "2026-10-01T12:00:00Z"}]
    (is (= ["task.completed"]
           (mapv :type (:facts (tasks/decide-complete with-upload command)))))
    (is (= :invalid-url
           (get-in (tasks/decide-complete legacy-task command) [:rejected :type]))
        "a fabricated file id without a matching version remains invalid")))

(deftest chase-history-is-human-authored-and-idempotent
  (let [command {:submission-id "s-1"
                 :task-key "slides"
                 :chase-id "chase-1"
                 :note "Asked Ada for the draft by email."
                 :actor "ann@example.com"
                 :at "2026-10-01T13:00:00Z"}
        decision (tasks/decide-record-chase base-state command)
        chased-state (folds/fold-event base-state (first (:facts decision)))
        task (get-in chased-state [:tasks ["s-1" "slides"]])]
    (is (= ["task.chase-recorded"] (mapv :type (:facts decision))))
    (is (= 1 (:chase-count task)))
    (is (= (Instant/parse "2026-10-01T13:00:00Z") (:last-chased-at task)))
    (is (= "Asked Ada for the draft by email." (-> task :chases first :note)))
    (is (= [] (:facts (tasks/decide-record-chase chased-state command))))
    (testing "automation cannot silently manufacture a human touch"
      (is (= :human-author-required
             (get-in (tasks/decide-record-chase base-state
                                                (assoc command :actor ""))
                     [:rejected :type]))))))

(deftest chase-drafts-are-pure-personalized-values
  (let [event (get-in base-state [:events "summit"])
        obligation (some #(when (= "s-1" (:submission-id %)) %)
                         (tasks/outstanding-obligations base-state "event-1" today))
        draft (tasks/chase-draft event obligation
                                 {:name "Ann Perry" :email "ann@example.com"})]
    (is (= "ann@example.com" (:from draft)))
    (is (= "ada@example.com" (:to draft)))
    (is (= "s-1" (:submission-id draft)))
    (is (= "slides" (:task-key draft)))
    (is (= "Quick follow-up: Slides draft for Summit" (:subject draft)))
    (is (re-find #"Hi Ada Lovelace" (:body draft)))
    (is (re-find #"Algebraic Programs" (:body draft)))
    (is (re-find #"Thanks,\nAnn Perry" (:body draft)))))

(deftest materials-chase-list-is-a-pure-fold-of-requests-receipts-and-delivery
  (let [request (fn [key label requested-at]
                  {:type "task.installed"
                   :actor "ann@example.com"
                   :event-id "event-1"
                   :payload {:submission-id "s-1"
                             :key key
                             :label label
                             :task-type "file"
                             :required? true
                             :due-offset-days -21
                             :at requested-at}})
        facts [(request "slides" "Slides draft" "2026-09-01T09:00:00Z")
               (request "headshot" "Speaker headshot" "2026-09-05T09:00:00Z")
               {:type "task.completed"
                :actor "ada@example.com"
                :event-id "event-1"
                :payload {:submission-id "s-1"
                          :key "headshot"
                          :value "file-headshot"
                          :at "2026-09-20T09:00:00Z"}}
               {:type "email.sent"
                :actor "ann@example.com"
                :event-id "event-1"
                :payload {:kind "speaker-chase"
                          :submission-id "s-1"
                          :task-key "slides"
                          :chase-id "slides-chase-1"
                          :to "ada@example.com"
                          :subject "Slides, please"
                          :body "Hi Ada, could you send the slides?"
                          :at "2026-09-28T09:00:00Z"
                          :message-id "provider-42"}}]
        state (reduce folds/fold-event
                      (-> base-state
                          (assoc :tasks {})
                          (assoc-in [:submissions "s-1" :status] "Accepted"))
                      facts)
        [row :as rows] (tasks/materials-chase-list state "event-1" today)]
    (is (= [["s-1" "slides"]]
           (mapv (juxt :submission-id :key) rows))
        "a receipt removes the fulfilled material from the chase list")
    (is (= 30 (:days-outstanding row))
        "age is derived from the immutable request fact")
    (is (= (Instant/parse "2026-09-28T09:00:00Z") (:last-chased-at row))
        "only rqh9's provider-delivery fact becomes the last touch")
    (is (= 1 (:chase-count row)))
    (is (= "Ada Lovelace" (:speaker-name row)))
    (is (= "Slides draft" (:label row)))))
