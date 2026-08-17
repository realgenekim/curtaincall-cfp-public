(ns cfp-scheduler-killer.reviewer-progress-views-test
  (:require
   [cfp-scheduler-killer.domain.review-plan :as domain-review-plan]
   [cfp-scheduler-killer.views.review :as review-view]
   [cfp-scheduler-killer.views.review-assignment :as assignment-view]
   [cfp-scheduler-killer.views.reviewer-progress :as progress-view]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [hiccup2.core :as h])
  (:import
   (org.jsoup Jsoup)))

(def event
  {:id "event-1"
   :slug "summit"
   :name "Enterprise AI Summit"
   :tz "UTC"
   :settings {:statuses []}})

(def review-plan
  {:presenter-visibility {:mode "visible" :version 1}
   :presenter-visibility-definition
   domain-review-plan/presenter-visibility-policy-definition})

(def progress
  [{:person-id "reviewer-lagging"
    :name "Lina Laggard"
    :role "reviewer"
    :review-count 4
    :assigned 3
    :completed 1
    :remaining 2}
   {:person-id "reviewer-complete"
    :name "Dana Done"
    :role "reviewer"
    :review-count 2
    :assigned 2
    :completed 2
    :remaining 0}])

(defn- board-html [chair?]
  (with-redefs [assignment-view/bulk-distribution-panel (constantly nil)]
    (str
      (h/html
        (review-view/board-region
          event
          {:rows []
           :coverage {}
           :sort-key "coverage"
           :status-counts {}
           :person {:id "chair-1" :name "Casey Chair"}
           :sort-presets []
           :total 0
           :track-counts {}
           :review-plan review-plan
           :reviewer-progress progress
           :chair? chair?})))))

(defn- panel-html []
  (str (h/html (progress-view/chair-panel
                 event progress
                 {:total 5 :covered 3 :target 2 :pct 60.0 :review-count 6}))))

(deftest dedicated-view-renders-current-reviewer-progress-test
  (let [document (Jsoup/parse (panel-html))
        panel (.selectFirst document ".reviewer-progress-panel")
        rows (vec (.select panel "tbody tr"))
        cells (mapv (fn [row]
                      (mapv #(.text %) (.select row "td")))
                    rows)
        laggard-ids (mapv #(.attr % "value")
                          (.select panel "input[name=reviewer-id]"))]
    (testing "the dedicated progress view composes the truthful chair panel"
      (is (some? panel))
      (is (= "section" (.tagName panel)))
      (is (nil? (.selectFirst panel "summary")))
      (is (str/includes? (.text panel)
                         "Review coverage: 3 of 5 submissions have at least 2 reviews (60%) · 6 signed reviews recorded")))
    (testing "reviews, assignments, and remaining counts render without recomputation"
      (is (= [["Lina Laggard reviewer" "4" "3" "2"]
              ["Dana Done reviewer" "2" "2" "0"]]
             (mapv #(subvec % 1 5) cells))))
    (testing "only reviewers with work remaining are current laggards"
      (is (= ["reviewer-lagging"] laggard-ids))
      (is (= "Draft a nudge for Lina Laggard"
             (.attr (.selectFirst panel "input[name=reviewer-id]") "aria-label"))))))

(deftest reviewer-progress-panel-is-not-embedded-in-board-test
  (doseq [chair? [true false]]
    (let [document (Jsoup/parse (board-html chair?))]
      (is (nil? (.selectFirst document ".reviewer-progress-panel"))))))
