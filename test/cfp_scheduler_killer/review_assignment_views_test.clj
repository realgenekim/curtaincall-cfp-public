(ns cfp-scheduler-killer.review-assignment-views-test
  (:require
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.review-assignments :as assignments]
   [cfp-scheduler-killer.views.review-assignment :as assignment-view]
   [cfp-scheduler-killer.views.reviewer-progress :as progress-view]
   [cfp-scheduler-killer.views.reviewer-queue :as queue-view]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [hiccup2.core :as h]))

(def event {:id "event-1" :slug "summit" :name "Enterprise AI Summit"})
(def reviewer {:person-id "reviewer-1" :name "Sam Reviewer" :role "reviewer"})
(def submission
  {:id "submission-1"
   :event-id "event-1"
   :status "Pending"
   :answers {:talk-title "Fast feedback loops"}
   :speakers [{:name "Ann Speaker" :org "IT Revolution"}]})

(deftest organizer-assignment-control-test
  (with-redefs [events/committees-for-event (constantly [{:id "committee-1"}])
                committees/members-for-committee (constantly [reviewer])]
    (testing "an unassigned reviewer gets an explicit assignment action"
      (with-redefs [assignments/assigned? (constantly false)]
        (let [html (str (h/html (assignment-view/assignment-control event submission)))]
          (is (str/includes? html "Assign reviewers"))
          (is (str/includes? html "Assign · Sam Reviewer"))
          (is (str/includes? html
                             "/api/submissions/submission-1/reviewers/reviewer-1/assign")))))
    (testing "the current state and inverse action are both visible"
      (with-redefs [assignments/assigned? (constantly true)]
        (let [html (str (h/html (assignment-view/assignment-control event submission)))]
          (is (str/includes? html "Assigned · Sam Reviewer"))
          (is (str/includes? html
                             "/api/submissions/submission-1/reviewers/reviewer-1/unassign")))))))

(deftest reviewer-queue-page-test
  (let [html (queue-view/queue-page
               event
               {:person {:id "reviewer-1" :name "Sam Reviewer"}
                :rows [submission]
                :progress {:assigned 2 :completed 1 :remaining 1}})]
    (testing "the page names the exact queue and progress"
      (is (str/includes? html "Assigned to you"))
      (is (str/includes? html "1 of 2 reviews complete"))
      (is (str/includes? html "Fast feedback loops")))
    (testing "reviewer navigation exposes the shared board, not organizer operations"
      (is (str/includes? html "Open shared review board"))
      (is (not (str/includes? html "Inform Speakers")))
      (is (not (str/includes? html "Exports &amp; API"))))))

(deftest reviewer-progress-and-human-nudge-views-test
  (let [progress [{:person-id "person-1" :name "Sam Reviewer" :email "sam@example.com"
                   :role "reviewer" :review-count 1 :assigned 3 :completed 1 :remaining 2}]
        panel (str (h/html (progress-view/chair-panel event progress)))
        page (progress-view/nudge-draft-page
               event {:person reviewer
                      :drafts [(assoc (first progress)
                                      :progress (first progress)
                                      :body "Please finish your two reviews.")]})
        queued-page (progress-view/nudge-draft-page
                      event {:person reviewer
                             :recipients [(assoc (first progress)
                                                 :progress (first progress))]
                             :outcome :queued
                             :queued-count 1})]
    (testing "chair progress exposes real counts and a multi-select draft action"
      (is (str/includes? panel "reviewer-progress-panel"))
      (is (not (str/includes? panel "<details")))
      (is (str/includes? panel "1 reviews"))
      (is (str/includes? panel "3 assigned"))
      (is (str/includes? panel "2 remaining"))
      (is (str/includes? panel "Draft nudges"))
      (is (str/includes? panel "name=\"reviewer-id\""))
      (is (str/includes? panel "Nothing is sent automatically")))
    (testing "resolved drafts stay editable behind an explicit human send gate"
      (is (str/includes? page "Draft reviewer nudges"))
      (is (str/includes? page "Please finish your two reviews."))
      (is (str/includes? page "Human send gate"))
      (is (str/includes? page "Record reviewed nudges")))
    (testing "queued confirmation retains the recipient's outstanding-review truth"
      (is (str/includes? queued-page "Reviewer reminder emails queued"))
      (is (str/includes? queued-page "1 reminder queued for approval"))
      (is (str/includes? queued-page
                         "href=\"/events/summit/comms?tab=send\""))
      (is (str/includes? queued-page "Review and send queued reminders"))
      (is (str/includes? queued-page "successful send shows Email sent"))
      (is (str/includes? queued-page "Sam Reviewer"))
      (is (str/includes? queued-page "sam@example.com"))
      (is (str/includes? queued-page "1 of 3 complete · 2 remaining"))
      (is (str/includes? queued-page "role=\"status\"")))))

(deftest reviewer-recusal-control-test
  (let [open-html (str (h/html (progress-view/recusal-control submission reviewer nil)))
        recused-html (str (h/html (progress-view/recusal-control
                                    submission reviewer
                                    {:reason "I work with the speaker."})))]
    (is (str/includes? open-html "Conflict of interest"))
    (is (str/includes? open-html "Recuse from this review"))
    (is (str/includes? open-html
                       "Recusing removes this submission from any actionable assigned queue"))
    (is (str/includes? open-html "Your prior review history is preserved"))
    (is (str/includes? open-html "You can restore review controls later"))
    (is (str/includes? open-html "/recuse"))
    (is (str/includes? recused-html "Recused from this review"))
    (is (str/includes? recused-html "Restore review"))
    (is (str/includes? recused-html
                       "Restoring reopens review controls and returns any prior assignment"))
    (is (str/includes? recused-html "/unrecuse"))))

(deftest bulk-distribution-views-test
  (let [progress [{:person-id "reviewer-1" :name "Sam Reviewer" :role "reviewer"
                   :assigned 1 :completed 0 :remaining 1}
                  {:person-id "reviewer-2" :name "Rae Reviewer" :role "reviewer"
                   :assigned 0 :completed 0 :remaining 0}]
        panel (str (h/html
                     (assignment-view/bulk-distribution-panel
                       event progress {"Applied AI" 3 nil 1})))
        result {:person reviewer
                :track "Applied AI"
                :cap 2
                :assignments [{:submission-id "submission-1"
                               :person-id "reviewer-2"}]
                :unassigned [{:submission-id "submission-2"
                              :reason :cap-reached}]
                :reviewer-by-id {"reviewer-1" (first progress)
                                 "reviewer-2" (second progress)}
                :submission-by-id {"submission-1" {:title "Fast feedback loops"}
                                   "submission-2" {:title "Pure domain decisions"}}
                :selected-reviewer-ids ["reviewer-1" "reviewer-2"]
                :loads-before {"reviewer-1" 1 "reviewer-2" 0}
                :loads-after {"reviewer-1" 1 "reviewer-2" 1}}
        preview (assignment-view/bulk-distribution-preview-page event result)
        confirmed (assignment-view/bulk-distribution-preview-page
                    event (assoc result :confirmed? true))]
    (testing "the board makes track, cap, reviewers, and read-only preview explicit"
      (is (str/includes? panel "Bulk reviewer distribution"))
      (is (str/includes? panel "Preview bulk distribution"))
      (is (str/includes? panel "Per-reviewer cap"))
      (is (str/includes? panel "Applied AI (3)"))
      (is (= 2 (count (re-seq #"name=\"reviewer-id\"" panel)))))
    (testing "preview names every proposed assignment and requires confirmation"
      (is (str/includes? preview "Fast feedback loops"))
      (is (str/includes? preview "Rae Reviewer"))
      (is (str/includes? preview "Pure domain decisions — Per-reviewer cap reached"))
      (is (str/includes? preview "Confirm bulk distribution"))
      (is (str/includes? preview "/api/events/summit/reviewers/distribute")))
    (testing "confirmed result is an event-log receipt, not another mutation button"
      (is (str/includes? confirmed "Bulk reviewer distribution complete"))
      (is (str/includes? confirmed "Assignments recorded"))
      (is (not (str/includes? confirmed "Confirm bulk distribution"))))))
