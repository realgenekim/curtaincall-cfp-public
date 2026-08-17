(ns cfp-scheduler-killer.handlers.resource-pages
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.resource-pages :as resource-pages]
   [cfp-scheduler-killer.views.resource-pages :as view-resource-pages]
   [cfp-scheduler-killer.web.http :as http]
   [clojure.string :as str]))

(defn- visible-event [slug]
  (let [event (events/event-by-slug slug)]
    (when (and event (not (events/unlisted? event))) event)))

(defn handle-organizer-pages [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (let [pages (resource-pages/pages-for-event event)
            selected (some #(when (= (:id %) (get-in req [:params :page])) %) pages)]
        (http/html-response
         (view-resource-pages/organizer-page
          (http/request-host req) event (auth/current-person req) pages selected nil
          (= "1" (get-in req [:params :saved])))))
      (http/html-response 404 "Event not found."))))

(defn handle-page-save [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (let [params (:params req)
            result (resource-pages/save-page!
                    event
                    {:id (not-empty (:id params))
                     :title (:title params)
                     :slug (:slug params)
                     :body (:body params)
                     :published? (= "true" (:published params))}
                    (or (:email (auth/current-person req)) "organizer"))]
        (if-let [error (:error result)]
          (http/html-response 422
           (view-resource-pages/organizer-page
            (http/request-host req) event (auth/current-person req)
            (resource-pages/pages-for-event event) (merge (:page result) (:params req)) error false))
          (http/see-other (str "/events/" slug "/resources?page="
                               (get-in result [:page :id]) "&saved=1"))))
      (http/html-response 404 "Event not found."))))

(defn handle-public-index [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (visible-event slug)]
      (http/html-response (view-resource-pages/public-index-page event (resource-pages/published-pages event)))
      (http/html-response 404 "Resource pages are not public for this event."))))

(defn handle-public-page [req]
  (let [event-slug (get-in req [:path-params :slug])
        page-slug (get-in req [:path-params :page-slug])]
    (if-let [event (visible-event event-slug)]
      (if-let [page (resource-pages/published-page-by-slug event page-slug)]
        (http/html-response (view-resource-pages/public-page event page (= "1" (get-in req [:params :embed]))))
        (http/html-response 404 "That resource page is not published."))
      (http/html-response 404 "Resource pages are not public for this event."))))
