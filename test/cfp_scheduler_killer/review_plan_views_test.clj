(ns cfp-scheduler-killer.review-plan-views-test
  (:require
   [cfp-scheduler-killer.domain.review-plan :as domain-review-plan]
   [cfp-scheduler-killer.views.policy :as policy-view]
   [cfp-scheduler-killer.views.review :as review-view]
   [cfp-scheduler-killer.views.reviewer-queue :as queue-view]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [hiccup2.core :as h]))

(def event
  {:id "event-1"
   :slug "summit"
   :name "Enterprise AI Summit"
   :tz "America/New_York"
   :settings {:statuses ["Pending" "Accepted"]}})

(def reviewer
  {:id "reviewer-1" :name "Sam Reviewer" :email "sam@example.com"})

(def full-submission
  {:id "submission-1"
   :event-id "event-1"
   :status "Pending"
   :answers {:talk-title "Fast feedback loops"
             :abstract "A useful abstract"
             :speaker-linkedin "https://linkedin.example/ann"
             :business-co-presenter "Pat Co-speaker"}
   :form-snapshot []
   :speakers [{:name "Ann Secret Speaker"
               :email "ann-secret@example.com"
               :org "Secret Company"
               :bio "Secret biography"
               :linkedin-url "https://linkedin.example/ann"}
              {:name "Pat Co-speaker" :org "Secret Company"}]
   :ratings []
   :comments []
   :n 0})

(deftest presenter-visibility-summary-is-compact-and-headerless-test
  (let [html (str (h/html
                    (policy-view/presenter-visibility-summary
                      {:mode "hidden" :version 0}
                      domain-review-plan/presenter-visibility-policy-definition)))]
    (is (str/includes? html
                       "Blind review · Presenter identity is hidden throughout review."))
    (is (not (str/includes? html "Before a reviewer")))
    (is (not (str/includes? html "Policy version")))))

(deftest review-board-region-omits-the-standalone-presenter-policy-summary-test
  (doseq [[mode expected] [["visible" "Open review · Presenter identity is visible to reviewers."]
                           ["hidden" "Blind review · Presenter identity is hidden throughout review."]
                           ["reveal-after-vote" "Blind until rated · Presenter identity appears after a reviewer submits their first rating."]]]
    (let [html (str (h/html
                      (review-view/board-region
                        event
                        {:rows []
                         :coverage {}
                         :sort-key "coverage"
                         :status-counts {}
                         :person reviewer
                         :sort-presets []
                         :total 0
                         :track-counts {}
                         :review-plan
                         {:presenter-visibility {:mode mode :version 4}
                          :presenter-visibility-definition
                          domain-review-plan/presenter-visibility-policy-definition}})))]
      (is (not (str/includes? html expected)) mode)
      (is (not (str/includes? html "presenter-visibility-policy")) mode)
      (is (not (str/includes? html "data-policy-mode")) mode)
      (is (not (str/includes? html "Policy version")) mode)
      (is (not (str/includes? html "Before a reviewer")) mode))))
