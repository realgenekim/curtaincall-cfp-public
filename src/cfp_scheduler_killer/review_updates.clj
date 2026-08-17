(ns cfp-scheduler-killer.review-updates
  (:require
   [cfp-scheduler-killer.review-plan :as review-plan]
   [cfp-scheduler-killer.review-policy :as review-policy]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.sse :as sse]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.views.review :as view-review]
   [cfp-scheduler-killer.views.submission-row :as submission-row]))

(defn push-board-updates!
  "Repaint one submission row (rendered per viewer, because it shows each
   reviewer their own rating) and the coverage headline. Only the reviewer
   who just acted receives inline controls; every other stream receives the
   compact row."
  ([event submission-id]
   (push-board-updates! event submission-id nil))
  ([event submission-id active-person-id]
   (when (store/submission-by-id submission-id)
     (let [target (review-policy/coverage-target-for (:id event))
           visibility-event (review-plan/visibility-policy-event event)]
       ;; Build the projection inside the push helper's synchronous render.
       ;; Concurrent POSTs can reach this point out of order; whichever queues
       ;; last must read the store last, never carry a snapshot captured before
       ;; the other write landed.
       (sse/push-personal-fragment!
         (:id event) (str "#sub-" submission-id)
         (fn [person-id]
           (submission-row/board-row visibility-event
                                     (reviews/enrich (store/submission-by-id submission-id))
                                     (when person-id (store/person-by-id person-id))
                                     nil nil
                                     (= person-id active-person-id))))
       (sse/push-fragment!
         (:id event) "#coverage-bar"
         (fn [] (view-review/coverage-bar event (reviews/coverage (:id event) target))))))))
