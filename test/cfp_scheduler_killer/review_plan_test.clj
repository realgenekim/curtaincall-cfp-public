(ns cfp-scheduler-killer.review-plan-test
  (:require
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.review-plan :as plan]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.test :refer [deftest is testing use-fixtures]])
  (:import
   (java.time LocalDateTime)))

(use-fixtures :each with-temp-store)

(defn- make-event! []
  (events/create-event!
    {:name "Review Plan Summit"
     :slug "review-plan-summit"
     :tz "America/New_York"
     :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
     :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
    "kaocha"))

(defn- add-member! [event name email role]
  (let [committee-id (:id (first (events/committees-for-event (:id event))))]
    (committees/add-member! committee-id {:name name :email email :role role} "kaocha")))
(deftest presenter-visibility-shell-is-versioned-and-idempotent
  (let [event (make-event!)
        chair-membership (add-member! event "Chair" "chair@example.com" "chair")
        reviewer-membership (add-member! event "Reviewer" "reviewer@example.com" "reviewer")
        chair-id (:person-id chair-membership)
        reviewer-id (:person-id reviewer-membership)]
    (is (= {:mode "hidden" :version 0}
           (plan/presenter-visibility-policy (:id event))))
    (is (= {:mode "reveal-after-vote" :version 1}
           (select-keys
             (plan/set-presenter-visibility!
               (:id event) "reveal-after-vote" 0 chair-id "chair@example.com")
             [:mode :version])))
    (plan/set-presenter-visibility!
      (:id event) "reveal-after-vote" 1 chair-id "chair@example.com")
    (is (= 1
           (->> (store/log-for-event (:id event))
                (filter #(= "review.presenter-visibility-set" (:type %)))
                count)))
    (is (= :stale-policy-version
           (try
             (plan/set-presenter-visibility!
               (:id event) "visible" 0 chair-id "chair@example.com")
             nil
             (catch clojure.lang.ExceptionInfo e
               (:type (ex-data e))))))
    (is (= :chair-required
           (try
             (plan/set-presenter-visibility!
               (:id event) "hidden" 1 reviewer-id "reviewer@example.com")
             nil
             (catch clojure.lang.ExceptionInfo e
               (:type (ex-data e))))))))
