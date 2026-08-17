(ns cfp-scheduler-killer.session-invites
  "Per-session calendar attachments.

   The export feed owns discovery and aggregation. This boundary owns the
   single-session attachment while deliberately reusing its public VEVENT
   projection, so both surfaces retain the same stable UID and SEQUENCE."
  (:require
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.exports :as exports]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.web.http :as http]))

(defn session-ics
  "One current session invite. Schedule amendments keep the submission UID."
  [event submission]
  (exports/submission-ics event submission))

(defn handle-session-invite [req]
  (let [slug (get-in req [:path-params :slug])
        submission-id (http/clean-id (get-in req [:path-params :submission-id]))
        event (events/event-by-slug slug)
        submission (when submission-id (store/submission-by-id submission-id))]
    (if (and event
             submission
             (= (:id event) (:event-id submission))
             (exports/published? submission))
      (-> (http/text-response "text/calendar; charset=utf-8"
                              (session-ics event submission))
          (assoc-in [:headers "Content-Disposition"]
                    (str "attachment; filename=\"" slug "-" submission-id ".ics\""))
          (assoc-in [:headers "Cache-Control"] "no-cache"))
      (http/text-response 404 "text/calendar; charset=utf-8" ""))))
