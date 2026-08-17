(ns cfp-scheduler-killer.handlers.exports
  "Organizer export index and public machine-readable export handlers."
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.exports :as exports]
   [cfp-scheduler-killer.review-scores-csv :as review-scores-csv]
   [cfp-scheduler-killer.views.embed :as view-embed]
   [cfp-scheduler-killer.views.integrations :as view-integrations]
   [cfp-scheduler-killer.web.event :as event-web]
   [cfp-scheduler-killer.web.http :as http]))

(defn- download-response [response filename]
  (assoc-in response [:headers "Content-Disposition"]
            (str "attachment; filename=\"" filename "\"")))

(defn handle-exports-page
  "GET /events/:slug/exports — the organizer's export index."
  [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (http/html-response
        (view-integrations/exports-page (http/request-host req) event
                                        {:person (auth/current-person req)}))
      (event-web/not-found-page slug))))

(defn handle-embed-builder
  "GET /events/:slug/embed — generate a stable public handoff snippet."
  [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (http/html-response
        (view-embed/embed-builder-page
          (http/request-host req) event (auth/current-person req) (:params req)))
      (event-web/not-found-page slug))))

(defn handle-export-sessions [req]
  (http/with-etag
    req
    (event-web/with-event
      req
      (fn [event]
        (download-response
          (http/json-response (exports/sessions-json-data event))
          "sessions.json")))))

(defn handle-export-speakers [req]
  (http/with-etag
    req
    (event-web/with-event
      req
      (fn [event]
        (download-response
          (http/json-response (exports/speakers-json-data event))
          "speakers.json")))))

(defn handle-export-review-scores [req]
  (event-web/with-event
    req
    (fn [event]
      (if (auth/event-manager? (auth/current-person req) (:id event))
        {:status 200
         :headers {"Content-Type" "text/csv; charset=utf-8"
                   "Content-Disposition"
                   (str "attachment; filename=\"" (:slug event) "-review-scores.csv\"")}
         :body (review-scores-csv/render event)}
        {:status 403
         :headers {"Content-Type" "text/plain; charset=utf-8"
                   "Cache-Control" "no-store"}
         :body "Review score exports are available only to event chairs."}))))

(defn handle-export-review-results-csv [req]
  (event-web/with-event
    req
    (fn [event]
      (if (auth/event-manager? (auth/current-person req) (:id event))
        {:status 200
         :headers {"Content-Type" "text/csv; charset=utf-8"
                   "Content-Disposition"
                   (str "attachment; filename=\"" (:slug event) "-review-results.csv\"")}
         :body (review-scores-csv/render-review-results event)}
        {:status 403
         :headers {"Content-Type" "text/plain; charset=utf-8"
                   "Cache-Control" "no-store"}
         :body "Review result exports are available only to event organizers."}))))

(defn handle-export-review-results-json [req]
  (event-web/with-event
    req
    (fn [event]
      (if (auth/event-manager? (auth/current-person req) (:id event))
        (download-response
          (http/json-response (review-scores-csv/review-results-data event))
          (str (:slug event) "-review-results.json"))
        {:status 403
         :headers {"Content-Type" "text/plain; charset=utf-8"
                   "Cache-Control" "no-store"}
         :body "Review result exports are available only to event organizers."}))))

(defn handle-export-ics [req]
  (http/with-etag
    req
    (event-web/with-event
      req
      (fn [event]
        (-> (http/text-response "text/calendar; charset=utf-8" (exports/calendar-ics event))
            (download-response "calendar.ics")
            (assoc-in [:headers "ETag"]
                      (str "W/\"ics-" (exports/schedule-version (:id event)) "\"")))))))

(defn handle-site-llms-txt
  "GET /llms.txt — the ROOT agent index, reachable with no session at all
   (auth/public-path?). An agent that knows only the domain starts here."
  [req]
  (http/with-etag
    req
    (http/text-response "text/markdown; charset=utf-8"
                        (exports/site-llms-txt (exports/site-events)
                                               (http/request-host req)))))

(defn handle-llms-txt [req]
  (http/with-etag
    req
    (event-web/with-event
      req
      (fn [event]
        (download-response
          (http/text-response "text/markdown; charset=utf-8"
                              (exports/llms-txt event (http/request-host req)))
          "llms.txt")))))
