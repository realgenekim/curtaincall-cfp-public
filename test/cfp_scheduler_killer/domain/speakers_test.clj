(ns cfp-scheduler-killer.domain.speakers-test
  (:require
   [cfp-scheduler-killer.domain.speakers :as speakers]
   [cfp-scheduler-killer.folds :as folds]
   [clojure.test :refer [deftest is testing]]))

(def base-state
  (assoc folds/empty-state
         :events {"event-a" {:id "event-a" :name "A"}
                  "event-b" {:id "event-b" :name "B"}}
         :people {"person-1" {:id "person-1"
                              :name "Priya Raghavan"
                              :email "priya@example.com"
                              :profile {:tagline "VP Engineering"}}}
         :submissions {"submission-1" {:id "submission-1"
                                       :event-id "event-a"
                                       :status "Accepted"
                                       :notified-at "2026-08-10T12:00:00Z"
                                       :answers {:talk-title "AI with receipts"}
                                       :speakers [{:person-id "person-1"
                                                   :name "Priya Raghavan"
                                                   :email "priya@example.com"}]}}))

(deftest submissions-project-into-the-event-roster-without-migration
  (let [row (first (speakers/roster base-state "event-a"))]
    (is (= "person-1" (:person-id row)))
    (is (= "Priya Raghavan" (:name row)))
    (is (= "Confirmed" (:status row)))
    (is (= ["AI with receipts"] (:talks row)))
    (is (empty? (speakers/roster base-state "event-b")))))

(deftest roster-folds-one-speaker-lifecycle-read
  (let [state (assoc base-state
                     :tasks {["submission-1" "profile"]
                             {:submission-id "submission-1"
                              :key "profile"
                              :label "Complete bio and profile"
                              :due-on "2026-09-01"
                              :chases [{:chase-id "chase-1"}]
                              :last-chased-at "2026-08-12T10:00:00Z"}
                             ["submission-1" "release"]
                             {:submission-id "submission-1"
                              :key "release"
                              :label "Sign speaker release"
                              :due-on "2026-09-05"
                              :completed-at "2026-08-12T11:00:00Z"}}
                     :log [{:type "submission.created"
                            :actor "priya@example.com"
                            :event-id "event-a"
                            :payload (get-in base-state [:submissions "submission-1"])}
                           {:type "submission.status-changed"
                            :actor "organizer@example.com"
                            :event-id "event-a"
                            :payload {:submission-id "submission-1"
                                      :to "Accepted"
                                      :at "2026-08-10T11:00:00Z"}}
                           {:type "submission.notified"
                            :actor "organizer@example.com"
                            :event-id "event-a"
                            :payload {:submission-id "submission-1"
                                      :status-at-notify "Accepted"
                                      :at "2026-08-10T12:00:00Z"}}
                           {:type "task.installed"
                            :actor "organizer@example.com"
                            :event-id "event-a"
                            :payload {:submission-id "submission-1"
                                      :key "profile"
                                      :label "Complete bio and profile"
                                      :at "2026-08-11T09:00:00Z"}}
                           {:type "email.sent"
                            :actor "organizer@example.com"
                            :event-id "event-a"
                            :payload {:submission-id "submission-1"
                                      :task-key "profile"
                                      :kind "speaker-chase"
                                      :chase-id "chase-1"
                                      :at "2026-08-12T10:00:00Z"}}
                           {:type "task.completed"
                            :actor "priya@example.com"
                            :event-id "event-a"
                            :payload {:submission-id "submission-1"
                                      :task-key "release"
                                      :at "2026-08-12T11:00:00Z"}}
                           {:type "speaker.status-changed"
                            :actor "other@example.com"
                            :event-id "event-b"
                            :payload {:event-id "event-b"
                                      :person-id "person-1"
                                      :status "Withdrawn"
                                      :changed-at "2026-08-13T00:00:00Z"}}])
        lifecycle (:lifecycle (first (speakers/roster state "event-a")))]
    (testing "status, open work, and history are one event-scoped read"
      (is (= "Confirmed" (:status lifecycle)))
      (is (= [{:submission-id "submission-1"
               :key "profile"
               :label "Complete bio and profile"
               :due-on "2026-09-01"
               :last-chased-at "2026-08-12T10:00:00Z"
               :chase-count 1}]
             (:pending-tasks lifecycle)))
      (is (= ["submission.created"
              "submission.status-changed"
              "submission.notified"
              "task.installed"
              "email.sent"
              "task.completed"]
             (mapv :type (:history lifecycle))))
      (is (not-any? #(= "speaker.status-changed" (:type %))
                    (:history lifecycle))
          "another event's speaker history cannot bleed into this one"))))

(deftest adding-a-speaker-reuses-normalized-email-and-is-idempotent
  (let [command {:event-id "event-a"
                 :person-id "unused-new-id"
                 :email "  PRIYA@example.com "
                 :name "A third party spelling"
                 :status "Invited"
                 :actor "organizer@example.com"
                 :at "2026-08-10T13:00:00Z"}
        decision (speakers/decide-add base-state command)
        facts (:facts decision)
        folded (reduce folds/fold-event base-state facts)]
    (is (:existing? decision))
    (is (= "person-1" (:person-id decision)))
    (is (empty? facts))
    (is (= "Confirmed" (:status (first (speakers/roster folded "event-a"))))
        "Add Speaker never resets an existing event participant")
    (is (:existing? (speakers/decide-add folded command)))))

(deftest adding-a-new-speaker-emits-person-before-participation
  (let [decision (speakers/decide-add
                   base-state
                   {:event-id "event-b"
                    :person-id "person-2"
                    :email "marcus@example.com"
                    :name "Marcus Devlin"
                    :status "Invited"
                    :actor "organizer@example.com"
                    :at "2026-08-10T13:00:00Z"})]
    (is (= ["person.created" "speaker.added-to-event"]
           (mapv :type (:facts decision))))
    (is (= "marcus@example.com"
           (get-in decision [:facts 0 :payload :email])))))

(deftest malformed-speaker-email-never-creates-a-global-identity
  (doseq [email ["not-an-email" "missing-domain@" "@missing-local.example"
                 "space @example.com" "speaker@example"]]
    (let [decision (speakers/decide-add
                     base-state
                     {:event-id "event-b" :person-id "person-2"
                      :email email :name "Broken Address" :status "Invited"
                      :actor "organizer@example.com" :at "2026-08-10T13:00:00Z"})]
      (is (= :invalid-email (get-in decision [:rejected :reason])) email)
      (is (empty? (:facts decision)) email))))

(deftest status-is-event-scoped-and-retries-are-no-ops
  (let [command {:event-id "event-a"
                 :person-id "person-1"
                 :status "Invited"
                 :actor "organizer@example.com"
                 :at "2026-08-10T14:00:00Z"}
        fact (first (:facts (speakers/decide-status base-state command)))
        folded (folds/fold-event base-state fact)]
    (is (= "speaker.status-changed" (:type fact)))
    (is (= "Invited" (:status (first (speakers/roster folded "event-a")))))
    (is (empty? (speakers/roster folded "event-b")))
    (is (empty? (:facts (speakers/decide-status folded command))))))

(deftest organizer-details-are-an-event-overlay-not-a-submission-rewrite
  (let [before (get-in base-state [:submissions "submission-1" :speakers])
        command {:event-id "event-a"
                 :person-id "person-1"
                 :changes {:organization "Acme Bank" :title "CTO"}
                 :actor "organizer@example.com"
                 :at "2026-08-10T15:00:00Z"}
        fact (first (:facts (speakers/decide-edit base-state command)))
        folded (folds/fold-event base-state fact)
        row (first (speakers/roster folded "event-a"))]
    (is (= "speaker.details-updated" (:type fact)))
    (is (= "Acme Bank" (:organization row)))
    (is (= "CTO" (:title row)))
    (is (= before (get-in folded [:submissions "submission-1" :speakers]))
        "event-local edits never rewrite the submitted speaker snapshot")))

(deftest invalid-commands-are-explicitly-rejected
  (is (= :event-not-found
         (get-in (speakers/decide-add base-state {:event-id "missing"})
                 [:rejected :reason])))
  (is (= :invalid-status
         (get-in (speakers/decide-status
                   base-state
                   {:event-id "event-a" :person-id "person-1" :status "Maybe"})
                 [:rejected :reason])))
  (is (= :speaker-not-found
         (get-in (speakers/decide-edit
                   base-state
                   {:event-id "event-a" :person-id "missing" :changes {:title "Nope"}})
                 [:rejected :reason]))))
