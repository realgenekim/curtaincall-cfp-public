(ns cfp-scheduler-killer.speaker-tasks-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.folds :as folds]
   [cfp-scheduler-killer.handlers.speaker-tasks :as handlers]
   [cfp-scheduler-killer.io.email :as email-port]
   [cfp-scheduler-killer.mail :as mail]
   [cfp-scheduler-killer.speaker-tasks :as speaker-tasks]
   [cfp-scheduler-killer.sse :as sse]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [cfp-scheduler-killer.views.speaker-tasks :as view]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]])
  (:import
   (java.time Instant LocalDate)))

(use-fixtures :each with-temp-store)

(deftest deliverables-project-existing-portal-slot-and-ics-facts
  (with-redefs [store/submissions-for-event
                (fn [_]
                  [{:id "submission-1"
                    :status "Accepted"
                    :notified-at "2026-08-17T10:00:00Z"
                    :answers {:talk-title "Reliable systems"}
                    :speakers [{:person-id "person-1"
                                :name "Ann Perry"
                                :email "ann@example.com"}]}])
                speaker-tasks/tasks-for-submission (constantly [])
                store/person-by-id
                (fn [_] {:name "Ann Perry" :email "ann@example.com"})
                store/slot-for (constantly {:id "slot-1"})
                store/log-for-event
                (constantly [{:type "portal.visited"
                              :payload {:person-id "person-1"}}])
                mail/history-for-person
                (fn [_ _] [{:sent? true :has-ics? true}])]
    (let [progress (#'handlers/onboarding-by-speaker {:id "event-1"})
          funnel (#'handlers/onboarding-funnel progress)
          body (str (view/deliverables-live-region funnel progress []))]
      (is (= {:portal-visited? true
              :slot-assigned? true
              :ics-sent? true}
             (select-keys (first progress)
                          [:portal-visited? :slot-assigned? :ics-sent?])))
      (is (= 1 (:portal-visited funnel)))
      (is (str/includes? body "Portal"))
      (is (str/includes? body "Slot assigned"))
      (is (str/includes? body ".ics sent"))
      (is (str/includes? body "derived from delivery history")))))

(deftest installing-one-task-appends-one-fact-once
  (let [state (atom (assoc folds/empty-state
                           :events {"summit" {:id "event-1" :slug "summit"}}
                           :submissions {"submission-1" {:id "submission-1"
                                                         :event-id "event-1"}}))
        batches (atom [])]
    (with-redefs [store/snapshot #(deref state)
                  store/now-iso (constantly "2026-10-01T13:00:00Z")
                  store/append-all! (fn [facts]
                                      (swap! batches conj facts)
                                      (swap! state #(reduce store/fold-one % facts)))]
      (speaker-tasks/install! "submission-1"
                              {:key "consent" :label "Confirm consent"
                               :task-type "check" :required? true}
                              "organizer@example.com")
      (is (= 1 (count @batches)))
      (is (= ["task.installed"] (mapv :type (first @batches))))
      (is (= 1 (count (:log @state)))))))

(deftest task-completion-pushes-the-live-deliverables-region-to-connected-organizers
  (let [pushes (atom [])
        checked (atom [])]
    (with-redefs [store/submission-by-id (constantly {:event-id "event-1"})
                  events/event-by-id (constantly {:id "event-1" :tz "UTC"})
                  store/submissions-for-event (constantly [])
                  speaker-tasks/for-event (constantly [])
                  committees/members-for-event
                  (constantly [{:person-id "chair-1" :role "chair"}
                               {:person-id "admin-1" :role "admin"}
                               {:person-id "reviewer-1" :role "reviewer"}])
                  sse/person-connection-count
                  (fn [event-id person-id]
                    (swap! checked conj [event-id person-id])
                    (if (= "chair-1" person-id) 1 0))
                  sse/push-to-person!
                  (fn [event-id person-id selector hiccup-fn]
                    (swap! pushes conj {:event-id event-id
                                        :person-id person-id
                                        :selector selector
                                        :fragment (hiccup-fn)}))]
      (handlers/push-deliverables-update! "submission-1"))
    (is (= [["event-1" "chair-1"] ["event-1" "admin-1"]] @checked))
    (is (= 1 (count @pushes)))
    (is (= {:event-id "event-1"
            :person-id "chair-1"
            :selector "#deliverables-live"}
           (dissoc (first @pushes) :fragment)))
    (is (= :div#deliverables-live (first (:fragment (first @pushes)))))))

(deftest deliverables-page-makes-human-follow-up-literal
  (let [event {:id "event-1"
               :slug "summit"
               :name "Summit"
               :tz "America/Los_Angeles"
               :starts-on (LocalDate/of 2026 10 10)}
        obligation {:submission-id "11111111-1111-1111-1111-111111111111"
                    :key "slides"
                    :label "Slides draft"
                    :speaker-name "Ada Lovelace"
                    :talk-title "Algebraic Programs"
                    :due-on (LocalDate/of 2026 9 19)
                    :status :overdue
                    :days-overdue 12
                    :last-chased-at (Instant/parse "2026-09-30T12:00:00Z")
                    :chase-count 1
                    :scheduled? true}
        page-opts {:person nil
                   :obligations [obligation]
                   :reminder-schedule {:enabled? true :days-before 14}
                   :speaker-progress [{:speaker-name "Ada Lovelace"
                                       :speaker-email "ada@example.com"
                                       :talks ["Algebraic Programs"]
                                       :done 1 :total 2 :overdue 1}]
                   :funnel {:accepted 14
                            :notified 11
                            :portal-visited nil
                            :tasks-complete 5}}
        html (view/deliverables-page event page-opts)
        filtered-html (view/deliverables-page event
                                              (assoc page-opts :filters {:q "Ada"}))]
    (is (str/includes? html "Speaker deliverables"))
    (is (str/includes? html "Ada Lovelace"))
    (is (str/includes? html "Algebraic Programs"))
    (is (str/includes? html "12d overdue"))
    (is (str/includes? html "Record follow-up"))
    (testing "the onboarding funnel leads the dashboard from acceptance to completion"
      (let [accepted (str/index-of html "Accepted")
            notified (str/index-of html "Notified")
            visited (str/index-of html "Portal visited")
            complete (str/index-of html "Tasks complete")]
        (is (< accepted notified visited complete))
        (is (str/includes? html ">14<"))
        (is (str/includes? html ">11<"))
        (is (str/includes? html ">—<"))
        (is (str/includes? html ">5<"))))
    (testing "speaker completion leads the page and remains visible under filters"
      (is (< (str/index-of html "Tasks complete")
             (str/index-of html "Onboarding completion by speaker")
             (str/index-of html ">Open<")))
      (is (str/includes? filtered-html "Onboarding completion by speaker"))
      (is (str/includes? filtered-html "Ada Lovelace")))
    (testing "the product promises assistance, never autonomous sending"
      (is (str/includes? html "never sends an automatic reminder")))
    (testing "bulk drafting is explicit across speakers and task types"
      (is (str/includes? html "select any outstanding speakers and tasks")))
    (testing "the explicit default-off schedule only preselects draft review"
      (is (str/includes? html "Automated reminder schedule"))
      (is (str/includes? html "Save reminder schedule"))
      (is (str/includes? html "preselected for draft review"))
      (is (str/includes? html "never sent automatically")))
    (testing "example text cannot become submitted data"
      (is (str/includes? html "data-ghost-fill=\"\"")))))

(deftest reminder-schedule-is-event-sourced-default-off-and-draft-only
  (let [event (events/create-event!
                {:name "Reminder Summit" :slug "reminder-summit" :tz "UTC"
                 :starts-on (LocalDate/of 2026 10 10)
                 :ends-on (LocalDate/of 2026 10 10)
                 :cfp-opens-at (java.time.LocalDateTime/of 2020 1 1 0 0)
                 :cfp-closes-at (java.time.LocalDateTime/of 2099 1 1 0 0)}
                "kaocha")]
    (is (= {:enabled? false :days-before 7}
           (speaker-tasks/reminder-schedule event)))

    (let [response (handlers/handle-reminder-schedule
                     {:path-params {:slug "reminder-summit"}
                      :params {:enabled? "true" :days-before "10"}})
          current (store/get-event-by-id (:id event))
          today (speaker-tasks/today-for current)
          selected (speaker-tasks/apply-reminder-schedule
                     current
                     [{:key "near" :due-on (.plusDays today 5)}
                      {:key "far" :due-on (.plusDays today 11)}])]
      (is (= 303 (:status response)))
      (is (= {:enabled? true :days-before 10}
             (select-keys (speaker-tasks/reminder-schedule current)
                          [:enabled? :days-before])))
      (is (= [true false] (mapv :scheduled? selected))
          "the schedule selects draft candidates; it does not deliver them")
      (is (= "speaker.reminder-schedule-configured"
             (:type (last (store/log-for-event (:id event))))))
      (is (= {:enabled? true :days-before 10}
             (select-keys
               (get-in (store/fold (store/read-events))
                       [:events "reminder-summit" :settings :speaker-reminder-schedule])
               [:enabled? :days-before])))
      (is (empty?
            (filter #(= "speaker.reminder-schedule-configured" (:event-type %))
                    @store/deliveries))
          "configuration and candidate selection never cross the send gate"))

    (is (= 422
           (:status
             (handlers/handle-reminder-schedule
               {:path-params {:slug "reminder-summit"}
                :params {:enabled? "true" :days-before "0"}}))))
    (is (= 303
           (:status
             (handlers/handle-reminder-schedule
               {:path-params {:slug "reminder-summit"}
                :params {:days-before "10"}}))))
    (is (false?
          (:enabled?
            (speaker-tasks/reminder-schedule
              (store/get-event-by-id (:id event))))))))

(deftest organizer-creates-one-general-task-for-multiple-speakers
  (let [event {:id "event-1"
               :slug "summit"
               :name "Summit"
               :tz "UTC"
               :starts-on (LocalDate/of 2026 10 10)}
        state (atom (assoc folds/empty-state
                           :events {"summit" event}
                           :people {"ada" {:id "ada" :name "Ada Lovelace"
                                           :email "ada@example.com"}
                                    "grace" {:id "grace" :name "Grace Hopper"
                                             :email "grace@example.com"}}
                           :submissions
                           {"submission-1"
                            {:id "submission-1" :event-id "event-1"
                             :answers {:talk-title "Algebraic Programs"}
                             :speakers [{:person-id "ada" :name "Ada Lovelace"
                                         :email "ada@example.com"}]}
                            "submission-2"
                            {:id "submission-2" :event-id "event-1"
                             :answers {:talk-title "Compilers at Sea"}
                             :speakers [{:person-id "grace" :name "Grace Hopper"
                                         :email "grace@example.com"}]}}))
        batches (atom [])]
    (with-redefs [store/snapshot #(deref state)
                  store/now-iso (constantly "2026-09-01T12:00:00Z")
                  store/append-all! (fn [facts]
                                      (swap! batches conj facts)
                                      (swap! state #(reduce folds/fold-event % facts)))
                  auth/current-person (constantly {:email "organizer@example.com"})]
      (testing "the organizer sees a named, dated, multi-speaker task control"
        (let [html (view/deliverables-page
                     event
                     {:person nil
                      :obligations []
                      :reminder-schedule {:enabled? false :days-before 7}
                      :task-assignees
                      [{:submission-id "submission-1" :speaker-name "Ada Lovelace"
                        :talk-title "Algebraic Programs"}
                       {:submission-id "submission-2" :speaker-name "Grace Hopper"
                        :talk-title "Compilers at Sea"}]})]
          (is (str/includes? html "Create onboarding task"))
          (is (str/includes? html "Ada Lovelace"))
          (is (str/includes? html "Grace Hopper"))
          (is (str/includes? html "name=\"due-on\""))
          (is (str/includes? html "name=\"task-kind\""))
          (is (str/includes? html "Upload presentation"))
          (is (str/includes? html "name=\"submission-ids\""))))

      (testing "one post persists the task for every selected speaker"
        (let [response
              (handlers/handle-reminder-schedule
                {:path-params {:slug "summit"}
                 :params {:intent "create-general-task"
                          :task-id "onboarding-2026"
                          :task-name "Confirm travel details"
                          :due-on "2026-09-30"
                          :instructions "Confirm your arrival and departure dates."
                          :submission-ids ["submission-1" "submission-2"]}})]
          (is (= 303 (:status response)))
          (is (= 2 (count @batches)))
          (doseq [submission-id ["submission-1" "submission-2"]]
            (is (= {:label "Confirm travel details"
                    :task-type "check"
                    :due-on (LocalDate/of 2026 9 30)
                    :instructions "Confirm your arrival and departure dates."}
                   (select-keys
                     (get-in @state [:tasks [submission-id "general-onboarding-2026"]])
                     [:label :task-type :due-on :instructions])))))))))

(defn- chase-test-state []
  (assoc folds/empty-state
         :submissions {"s-1" {:id "s-1" :event-id "event-1"}}
         :tasks {["s-1" "slides"] {:submission-id "s-1"
                                   :key "slides"
                                   :label "Slides draft"}}))

(defn- reviewed-letter []
  {:chase-id "chase-1"
   :submission-id "s-1"
   :task-key "slides"
   :from "ann@example.com"
   :to "ada@example.com"
   :subject "Quick follow-up: Slides draft"
   :body "Hi Ada, please send the slides. Thanks, Ann"})

(defn- fold-chase-delivery! [state letter context]
  (swap! state folds/fold-event
         {:type "email.sent"
          :actor (:actor context)
          :event-id (:event-id context)
          :payload (merge
                     (select-keys letter [:to :subject :body])
                     (select-keys context
                                  [:kind :submission-id :task-key :chase-id])
                     {:email-id "email-1"
                      :at "2026-10-01T13:05:00Z"
                      :message-id "provider-42"})}))

(deftest a-send-return-value-alone-never-resets-the-chase-clock
  (doseq [provider-mode [:queued :sent]]
    (testing (name provider-mode)
      (let [state (atom (chase-test-state))
            mail-calls (atom [])]
        (with-redefs [store/snapshot #(deref state)
                      store/now-iso (constantly "2026-10-01T13:00:00Z")
                      store/append-all! (fn [facts]
                                          (swap! state #(reduce folds/fold-event % facts)))
                      mail/send! (fn [letter context]
                                   (swap! mail-calls conj [letter context])
                                   {:mode provider-mode})]
          (let [result (speaker-tasks/send-chases!
                         "event-1" [(reviewed-letter)] "ann@example.com")
                task (get-in @state [:tasks ["s-1" "slides"]])]
            (is (= provider-mode (-> result first :mode)))
            (is (= 1 (count @mail-calls)))
            (is (zero? (:chase-count task 0))
                "an ephemeral adapter return is not delivery evidence")))))))

(deftest chase-clock-advances-only-from-the-outbox-delivery-fact
  (let [payload {:email-id "email-1"
                 :kind "speaker-chase"
                 :submission-id "s-1"
                 :task-key "slides"
                 :chase-id "chase-1"
                 :to "ada@example.com"
                 :subject "Quick follow-up: Slides draft"
                 :body "Hi Ada, please send the slides. Thanks, Ann"}
        failed (folds/fold-event
                 (chase-test-state)
                 {:type "email.failed"
                  :actor "ann@example.com"
                  :event-id "event-1"
                  :payload (assoc payload :at "2026-10-01T13:00:00Z"
                                  :error "connection refused")})
        delivered-fact {:type "email.sent"
                        :actor "ann@example.com"
                        :event-id "event-1"
                        :payload (assoc payload :at "2026-10-01T13:05:00Z"
                                        :message-id "provider-42")}
        delivered (folds/fold-event failed delivered-fact)
        replayed (folds/fold-event delivered delivered-fact)]
    (testing "failed or undelivered outbox attempts consume no cadence"
      (is (zero? (get-in failed [:tasks ["s-1" "slides"] :chase-count] 0)))
      (is (nil? (get-in failed [:tasks ["s-1" "slides"] :last-chased-at]))))
    (testing "the durable provider-delivery fact advances the clock"
      (is (= 1 (get-in delivered [:tasks ["s-1" "slides"] :chase-count])))
      (is (= (Instant/parse "2026-10-01T13:05:00Z")
             (get-in delivered [:tasks ["s-1" "slides"] :last-chased-at])))
      (is (= {:chase-id "chase-1"
              :medium "email"
              :delivery-mode "sent"
              :subject "Quick follow-up: Slides draft"}
             (select-keys
               (first (get-in delivered [:tasks ["s-1" "slides"] :chases]))
               [:chase-id :medium :delivery-mode :subject]))))
    (testing "replaying the same delivery fact is algebraically safe"
      (is (= 1 (get-in replayed [:tasks ["s-1" "slides"] :chase-count])))
      (is (= (get-in delivered [:tasks ["s-1" "slides"]])
             (get-in replayed [:tasks ["s-1" "slides"]]))))))

(deftest outbox-preserves-chase-identity-through-provider-delivery
  (let [event-log (atom [])
        state (atom folds/empty-state)]
    (with-redefs [store/new-id (constantly "email-1")
                  store/now-iso (constantly "2026-10-01T13:05:00Z")
                  store/snapshot #(deref state)
                  store/read-events #(deref event-log)
                  store/append! (fn [fact]
                                  (swap! event-log conj fact)
                                  (swap! state store/fold-one fact))
                  mail/provider-delivery-enabled? (constantly false)]
      (let [letter (reviewed-letter)
            queued (mail/send!
                     (select-keys letter [:from :to :subject :body])
                     {:event-id "event-1"
                      :kind "speaker-chase"
                      :submission-id (:submission-id letter)
                      :task-key (:task-key letter)
                      :chase-id (:chase-id letter)
                      :actor "ann@example.com"})
            result (mail/approve! "event-1" (:email-id queued) "ann@example.com")
            sent (last (filter #(= "email.sent" (:type %)) @event-log))]
        (is (= :sent (:mode result)))
        (is (= "chase-1" (get-in sent [:payload :chase-id])))
        (is (= {:kind "speaker-chase"
                :submission-id "s-1"
                :task-key "slides"}
               (select-keys (:payload sent) [:kind :submission-id :task-key])))))))

(deftest failed-outbox-delivery-does-not-consume-the-chase-cadence
  (let [state (atom (chase-test-state))
        event-log (atom [])]
    (with-redefs [store/new-id (constantly "email-1")
                  store/now-iso (constantly "2026-10-01T13:05:00Z")
                  store/snapshot #(deref state)
                  store/read-events #(deref event-log)
                  store/append! (fn [fact]
                                  (swap! event-log conj fact)
                                  (swap! state store/fold-one fact))
                  mail/provider-delivery-enabled? (constantly true)
                  mail/config (constantly {:host "smtp.example.com"
                                           :port 587
                                           :user "user"
                                           :pass "pass"
                                           :from "ann@example.com"})
                  email-port/send-with-config!
                  (fn [& _] {:ok false :error "connection refused"})]
      (let [result (speaker-tasks/send-chases!
                     "event-1" [(reviewed-letter)] "ann@example.com")
            failed (last @event-log)]
        (is (= :failed (-> result first :mode)))
        (is (= ["email.queued" "email.approved" "email.failed"]
               (mapv :type @event-log)))
        (is (= "chase-1" (get-in failed [:payload :chase-id])))
        (is (zero? (get-in @state [:tasks ["s-1" "slides"] :chase-count] 0)))
        (is (nil? (get-in @state [:tasks ["s-1" "slides"] :last-chased-at])))))))

(deftest reviewed-reminder-batches-can-span-obligation-kinds
  (let [state (atom (assoc-in (chase-test-state) [:tasks ["s-1" "headshot"]]
                              {:submission-id "s-1" :key "headshot"
                               :label "Speaker headshot"}))
        mail-calls (atom [])]
    (with-redefs [store/snapshot #(deref state)
                  store/now-iso (constantly "2026-10-01T13:00:00Z")
                  store/append-all! #(swap! state (fn [s] (reduce folds/fold-event s %)))
                  mail/send! (fn [letter context]
                               (swap! mail-calls conj [letter context])
                               (fold-chase-delivery! state letter context)
                               {:mode :sent})]
      (let [results (speaker-tasks/send-chases!
                      "event-1"
                      [(reviewed-letter)
                       (assoc (reviewed-letter)
                              :chase-id "chase-2"
                              :task-key "headshot")]
                      "ann@example.com")]
        (is (= [:sent :sent] (mapv :mode results)))
        (is (= 2 (count @mail-calls)))
        (is (= 1 (get-in @state [:tasks ["s-1" "slides"] :chase-count])))
        (is (= 1 (get-in @state [:tasks ["s-1" "headshot"] :chase-count])))))))

(deftest reviewed-reminders-approve-the-queued-message
  (let [state (atom (chase-test-state))
        approved (atom nil)
        queued (atom nil)]
    (with-redefs [store/snapshot #(deref state)
                  store/now-iso (constantly "2026-10-01T13:00:00Z")
                  store/append-all! #(swap! state (fn [s] (reduce folds/fold-event s %)))
                  mail/send! (fn [letter context]
                               (reset! queued [letter context])
                               {:mode :queued :email-id "email-1"})
                  mail/approve! (fn [event-id email-id actor]
                                  (reset! approved [event-id email-id actor])
                                  (let [[letter context] @queued]
                                    (fold-chase-delivery! state letter context))
                                  {:mode :sent})]
      (let [result (speaker-tasks/send-chases!
                     "event-1" [(reviewed-letter)] "ann@example.com")]
        (is (= ["event-1" "email-1" "ann@example.com"] @approved))
        (is (= :sent (-> result first :mode)))
        (is (= 1 (get-in @state [:tasks ["s-1" "slides"] :chase-count])))))))

(deftest reviewed-reminder-retry-is-confirmed-without-redelivery
  (let [state (atom (chase-test-state))
        mail-calls (atom [])]
    (with-redefs [store/snapshot #(deref state)
                  store/now-iso (constantly "2026-10-01T13:00:00Z")
                  store/append-all! #(swap! state (fn [s] (reduce folds/fold-event s %)))
                  mail/send! (fn [letter context]
                               (swap! mail-calls conj [letter context])
                               (fold-chase-delivery! state letter context)
                               {:mode :sent})]
      (let [first-result (speaker-tasks/send-chases!
                           "event-1" [(reviewed-letter)] "ann@example.com")
            retry-result (speaker-tasks/send-chases!
                           "event-1" [(reviewed-letter)] "ann@example.com")
            receipt (view/chase-results-page
                      {:id "event-1" :slug "summit" :name "Summit"}
                      {:person {:email "ann@example.com"}
                       :results retry-result})]
        (is (= :sent (-> first-result first :mode)))
        (is (= :already-sent (-> retry-result first :mode)))
        (is (= 1 (count @mail-calls)) "a browser retry cannot send a second email")
        (is (= 1 (get-in @state [:tasks ["s-1" "slides"] :chase-count])))
        (is (str/includes? receipt "1 reminder already sent and recorded"))
        (is (str/includes? receipt "Already sent and recorded"))))))

(deftest chase-draft-handler-renders-the-explicit-human-gate
  (let [event {:id "event-1" :slug "summit" :name "Summit"}
        obligation {:submission-id "11111111-1111-1111-1111-111111111111"
                    :key "slides"
                    :label "Slides draft"
                    :speaker-name "Ada Lovelace"
                    :speaker-email "ada@example.com"
                    :talk-title "Algebraic Programs"
                    :chase-count 0}
        second-obligation {:submission-id "22222222-2222-2222-2222-222222222222"
                           :key "headshot"
                           :label "Speaker headshot"
                           :speaker-name "Grace Hopper"
                           :speaker-email "grace@example.com"
                           :talk-title "Compilers at Sea"
                           :chase-count 1}
        person {:name "Ann Perry" :email "ann@example.com"}]
    (with-redefs [events/event-by-slug (constantly event)
                  auth/current-person (constantly person)
                  speaker-tasks/outstanding-for-event
                  (constantly [obligation second-obligation])]
      (let [response (handlers/handle-draft-chases
                       {:path-params {:slug "summit"}
                        :params {:selected
                                 ["11111111-1111-1111-1111-111111111111|slides"
                                  "22222222-2222-2222-2222-222222222222|headshot"]}})]
        (is (= 200 (:status response)))
        (is (str/includes? (:body response) "Human send gate"))
        (is (str/includes? (:body response) "Nothing is sent until"))
        (is (str/includes? (:body response) "value=\"2\""))
        (is (str/includes? (:body response) "ann@example.com"))
        (is (str/includes? (:body response) "ada@example.com"))
        (is (str/includes? (:body response) "grace@example.com"))
        (is (str/includes? (:body response) "Send reviewed messages"))))))

(deftest chase-send-handler-rehydrates-only-the-reviewed-fields
  (let [event {:id "event-1" :slug "summit" :name "Summit"}
        person {:name "Ann Perry" :email "ann@example.com"}
        sent (atom nil)
        params {:count "1"
                :chase-id-0 "chase-1"
                :submission-id-0 "11111111-1111-1111-1111-111111111111"
                :task-key-0 "slides"
                :from-0 "ann@example.com"
                :to-0 "ada@example.com"
                :subject-0 "Edited subject"
                :body-0 "Edited body"}]
    (with-redefs [events/event-by-slug (constantly event)
                  auth/current-person (constantly person)
                  speaker-tasks/send-chases!
                  (fn [event-id letters actor]
                    (reset! sent [event-id letters actor])
                    [{:mode :sent :to "ada@example.com"}])]
      (let [response (handlers/handle-send-chases
                       {:path-params {:slug "summit"} :params params})]
        (is (= 200 (:status response)))
        (is (= ["event-1"
                [{:chase-id "chase-1"
                  :submission-id "11111111-1111-1111-1111-111111111111"
                  :task-key "slides"
                  :from "ann@example.com"
                  :to "ada@example.com"
                  :subject "Edited subject"
                  :body "Edited body"}]
                "ann@example.com"]
               @sent))
        (is (str/includes? (:body response) "Sent and recorded"))
        (is (str/includes? (:body response) "1 reminder sent and recorded"))
        (is (str/includes? (:body response) "role=\"status\""))))
    (testing "mixed provider outcomes never render as an all-success batch"
      (let [body (view/chase-results-page
                   event
                   {:person person
                    :results [{:mode :sent :to "ada@example.com"}
                              {:mode :failed :to "grace@example.com"}]})]
        (is (str/includes? body "1 reminder sent and recorded"))
        (is (str/includes? body "0 queued · 0 rendered only · 1 failed"))
        (is (str/includes? body "ui warning message"))
        (is (str/includes? body "Delivery failed; not marked contacted"))))))
