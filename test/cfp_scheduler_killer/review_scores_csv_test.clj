(ns cfp-scheduler-killer.review-scores-csv-test
  (:require
   [cfp-scheduler-killer.review-plan :as review-plan]
   [cfp-scheduler-killer.review-scores-csv :as review-scores-csv]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.store :as store]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(deftest export-contains-signed-stars-and-ignores-legacy-scorecards-test
  (let [rating {:person-id "reviewer-1"
                :person-name "Signed Reviewer"
                :stars 4.0
                :at "2026-08-16T20:00:00Z"}
        submission {:id "talk-1"
                    :event-id "event-1"
                    :status "Pending"
                    :answers {:talk-title "One score, many reasons"}
                    :ratings [rating]}
        state {:people {"reviewer-1" {:id "reviewer-1" :name "Signed Reviewer"}}
               ;; Historical projections remain replayable but are not product inputs.
               :review-rounds {"round-1" {:id "round-1" :event-id "event-1"}}
               :review-plans {"event-1" {:active-round-id "round-1"}}
               :review-criteria {"legacy" {:id "legacy" :name "Legacy weight"}}
               :criterion-values {["talk-1" "reviewer-1" "legacy"] {:value 5.0}}}]
    (with-redefs [store/snapshot (constantly state)
                  reviews/enriched-for-event (fn [_] [submission])]
      (let [row (zipmap review-scores-csv/header
                        (first (review-scores-csv/rows {:id "event-1"})))]
        (testing "the export is only signed Stars"
          (is (= "Signed Reviewer" (get row "reviewer_name")))
          (is (= "4.0" (get row "stars")))
          (is (= "4.0" (get row "mean_stars"))))
        (testing "retired scorecard columns cannot leak back into the contract"
          (is (not-any? #{"weighted_score" "criterion_values_json"}
                        review-scores-csv/header)))))))

(deftest review-results-bundle-is-nested-deterministic-and-visibility-aware-test
  (let [rating {:person-id "reviewer-1"
                :person-name "Signed Reviewer"
                :stars 4.0
                :at "2026-08-16T20:00:00Z"}
        comment {:id "comment-1"
                 :person-id "reviewer-1"
                 :person-name "Signed Reviewer"
                 :body "Strong proposal"
                 :at "2026-08-16T20:01:00Z"}
        submission {:id "talk-1"
                    :event-id "event-1"
                    :status "Pending"
                    :answers {:talk-title "One score, many reasons"}
                    :speakers [{:person-id "speaker-1"}]
                    :ratings [rating]
                    :comments [comment]}
        state {:people {"speaker-1" {:id "speaker-1"
                                     :name "Speaker One"
                                     :email "speaker@example.com"}
                        "reviewer-1" {:id "reviewer-1"
                                      :name "Signed Reviewer"}}}
        event {:id "event-1" :slug "event-one" :name "Event One"}]
    (with-redefs [store/snapshot (constantly state)
                  reviews/enriched-for-event (fn [_] [submission])
                  review-plan/presenter-visibility-policy (fn [_] {:mode "visible"})]
      (let [result (review-scores-csv/review-results-data event)
            exported (first (:submissions result))]
        (is (= "visible" (:presenterVisibility result)))
        (is (= "Speaker One" (:speakerName exported)))
        (is (= "speaker@example.com" (:speakerEmail exported)))
        (is (= 1 (:reviewCount exported)))
        (is (= 4.0 (-> exported :ratings first :stars)))
        (is (= "Strong proposal" (-> exported :comments first :body)))
        (is (not (contains? exported :scorecard)))
        (is (str/includes? (review-scores-csv/render-review-results event)
                           "Strong proposal")))
      (with-redefs [review-plan/presenter-visibility-policy (fn [_] {:mode "hidden"})]
        (let [exported (-> (review-scores-csv/review-results-data event)
                           :submissions
                           first)]
          (is (= "Anonymous speaker" (:speakerName exported)))
          (is (nil? (:speakerEmail exported))))))))
