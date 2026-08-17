(ns cfp-scheduler-killer.submission-content-views-test
  (:require
   [cfp-scheduler-killer.views.submission-content :as view]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [hiccup2.core :as h]))

(def event
  {:id "event-1" :slug "content-summit" :tz "UTC"})

(def submission
  {:id "submission-1"
   :content-status "Approved"
   :answers {:talk-title "Current title"
             :abstract "Current abstract"}})

(def revisions
  [{:log-index 42
    :at "2026-08-14T09:30:00Z"
    :actor "organizer@example.com"
    :changed ["talk-title" "abstract"]
    :before {:talk-title "Original title" :abstract ""}
    :after {:talk-title "Current title" :abstract "Current abstract"}}])

(deftest history-is-a-truthful-version-restore-workflow-test
  (let [html (str (h/html (view/history-section event submission revisions)))]
    (testing "current state is explicit beside immutable history"
      (doseq [literal ["Current content" "Current title" "Current abstract"
                       "Content status" "Approved"]]
        (is (str/includes? html literal) literal)))

    (testing "the restore action names the exact previous-state semantics"
      (is (str/includes? html "Restore state before this edit"))
      (is (str/includes? html "creates a new revision"))
      (is (str/includes? html "state before its edit")))

    (testing "diffs use human labels and unquoted values"
      (is (str/includes? html "Talk title"))
      (is (str/includes? html "Before: Original title"))
      (is (str/includes? html "After: Current title"))
      (is (not (str/includes? html "&quot;Original title&quot;"))))))
