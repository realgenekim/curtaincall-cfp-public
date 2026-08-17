(ns cfp-scheduler-killer.domain.review-plan-test
  (:require
   [cfp-scheduler-killer.domain.review-plan :as plan]
   [cfp-scheduler-killer.folds :as folds]
   [clojure.test :refer [deftest is testing]]))

(def base-state
  (assoc folds/empty-state
         :events {"summit" {:id "event-1" :slug "summit" :name "Summit"}
                  "other" {:id "event-2" :slug "other" :name "Other"}}
         :committees {"committee-1" {:id "committee-1" :event-id "event-1"}
                      "committee-2" {:id "committee-2" :event-id "event-2"}}
         :people {"chair" {:id "chair" :name "Chair"}
                  "reviewer" {:id "reviewer" :name "Reviewer"}
                  "other-reviewer" {:id "other-reviewer" :name "Other"}}
         :memberships {"chair-membership" {:id "chair-membership" :committee-id "committee-1"
                                           :person-id "chair" :role "chair"}
                       "reviewer-membership" {:id "reviewer-membership" :committee-id "committee-1"
                                              :person-id "reviewer" :role "reviewer"}
                       "other-membership" {:id "other-membership" :committee-id "committee-2"
                                           :person-id "other-reviewer" :role "reviewer"}}
         :review-criteria {"stars" {:id "stars" :event-id "event-1" :name "Stars" :kind :numeric}
                           "impact" {:id "impact" :event-id "event-1" :name "Impact" :kind :numeric}
                           "other-stars" {:id "other-stars" :event-id "event-2" :name "Stars" :kind :numeric}}))

(defn- fold-decision [state decision]
  (reduce folds/fold-event state (:facts decision)))
(deftest presenter-visibility-policy-is-versioned-and-self-describing
  (testing "an absent policy fails safe to blind without an appended fact"
    (is (= {:mode "hidden" :version 0}
           (plan/presenter-visibility-policy base-state "event-1")))
    (is (= #{"visible" "hidden" "reveal-after-vote"}
           (set (map :id plan/presenter-visibility-mode-definitions)))))
  (testing "every malformed canonical policy fails closed, even beside legacy visible state"
    (doseq [bad-policy [nil
                        {}
                        {:mode "visible"}
                        {:mode "visible" :version -1}
                        {:mode "sometimes" :version 4}
                        "visible"]]
      (let [state (-> base-state
                      (assoc-in [:review-plans "event-1" :blind?] false)
                      (assoc-in [:review-plans "event-1" :presenter-visibility]
                                bad-policy))]
        (is (= "hidden"
               (:mode (plan/presenter-visibility-policy state "event-1")))
            (str "must fail closed for " (pr-str bad-policy))))))
  (testing "explicit legacy decisions keep their stored meaning"
    (doseq [[state expected]
            [[(assoc-in base-state [:review-plans "event-1" :blind?] false) "visible"]
             [(assoc-in base-state [:review-plans "event-1" :blind?] true) "hidden"]
             [(assoc-in base-state [:events "summit" :settings :hide-presenter-info] false)
              "visible"]
             [(assoc-in base-state [:events "summit" :settings :hide-presenter-info] true)
              "hidden"]
             [(-> base-state
                  (assoc-in [:events "summit" :settings :hide-presenter-info] true)
                  (assoc-in [:events "summit" :settings :reveal-after-vote] true))
              "reveal-after-vote"]]]
      (is (= expected
             (:mode (plan/presenter-visibility-policy state "event-1"))))))
  (let [visible (plan/decide-set-presenter-visibility
                  base-state
                  {:event-id "event-1"
                   :mode "visible"
                   :expected-version 0
                   :actor-person-id "chair"
                   :actor "chair@example.com"
                   :at "2026-08-10T12:00:00Z"})
        visible-state (fold-decision base-state visible)
        reveal (plan/decide-set-presenter-visibility
                 visible-state
                 {:event-id "event-1"
                  :mode "reveal-after-vote"
                  :expected-version 1
                  :actor-person-id "chair"
                  :actor "chair@example.com"
                  :at "2026-08-10T12:01:00Z"})
        reveal-state (fold-decision visible-state reveal)]
    (is (= ["review.presenter-visibility-set"] (mapv :type (:facts visible))))
    (is (= {:mode "visible" :version 1}
           (select-keys (plan/presenter-visibility-policy visible-state "event-1")
                        [:mode :version])))
    (is (= {:mode "reveal-after-vote" :version 2}
           (select-keys (plan/presenter-visibility-policy reveal-state "event-1")
                        [:mode :version])))
    (testing "a repeated value is a no-op and does not advance the version"
      (is (empty? (:facts (plan/decide-set-presenter-visibility
                            reveal-state
                            {:event-id "event-1"
                             :mode "reveal-after-vote"
                             :expected-version 2
                             :actor-person-id "chair"})))))
    (testing "a stale version cannot overwrite a newer choice"
      (is (= :stale-policy-version
             (get-in (plan/decide-set-presenter-visibility
                       reveal-state
                       {:event-id "event-1"
                        :mode "visible"
                        :expected-version 1
                        :actor-person-id "chair"})
                     [:rejection :type]))))
    (testing "the catalog rejects unknown modes"
      (is (= :invalid-presenter-visibility
             (get-in (plan/decide-set-presenter-visibility
                       reveal-state
                       {:event-id "event-1"
                        :mode "sometimes"
                        :actor-person-id "chair"})
                     [:rejection :type]))))))
(deftest reveal-after-vote-is-per-reviewer
  (let [row {:id "submission-1"
             :speakers [{:name "Ada Speaker" :org "Secret Co"}]
             :answers {:talk-title "A talk"
                       :abstract "Useful content"
                       :speaker-email "ada@example.com"}}
        decision (plan/decide-set-presenter-visibility
                   base-state
                   {:event-id "event-1"
                    :mode "reveal-after-vote"
                    :actor-person-id "chair"
                    :actor "chair@example.com"
                    :at "2026-08-10T12:00:00Z"})
        reveal-state (fold-decision base-state decision)
        rated-state (assoc-in reveal-state
                              [:ratings ["submission-1" "reviewer"]]
                              {:submission-id "submission-1"
                               :person-id "reviewer"
                               :stars 4})]
    (is (= "Anonymous speaker"
           (get-in (plan/project-submission reveal-state "event-1" "reviewer" row)
                   [:speakers 0 :name])))
    (is (= row
           (plan/project-submission rated-state "event-1" "reviewer" row)))
    (is (= "Anonymous speaker"
           (get-in (plan/project-submission rated-state "event-1" "other-reviewer" row)
                   [:speakers 0 :name])))
    (is (= row
           (plan/project-submission reveal-state "event-1" "chair" row)))
    (testing "hidden mode stays hidden even after a rating"
      (let [hidden (plan/decide-set-presenter-visibility
                     base-state
                     {:event-id "event-1"
                      :mode "hidden"
                      :actor-person-id "chair"
                      :actor "chair@example.com"
                      :at "2026-08-10T12:00:00Z"})
            hidden-rated (assoc-in (fold-decision base-state hidden)
                                   [:ratings ["submission-1" "reviewer"]]
                                   {:submission-id "submission-1"
                                    :person-id "reviewer"
                                    :stars 4})]
        (is (= "Anonymous speaker"
               (get-in (plan/project-submission hidden-rated "event-1" "reviewer" row)
                       [:speakers 0 :name])))))))
(deftest legacy-blind-facts-advance-the-canonical-policy
  (let [hidden (folds/fold-event
                 base-state
                 {:type "review.blind-mode-set"
                  :event-id "event-1"
                  :payload {:blind? true}})
        visible (folds/fold-event
                  hidden
                  {:type "review.blind-mode-set"
                   :event-id "event-1"
                   :payload {:blind? false}})]
    (is (= {:mode "hidden" :version 1}
           (select-keys (plan/presenter-visibility-policy hidden "event-1")
                        [:mode :version])))
    (is (= {:mode "visible" :version 2}
           (select-keys (plan/presenter-visibility-policy visible "event-1")
                        [:mode :version])))
    (is (false? (plan/blind-review? visible "event-1")))))
