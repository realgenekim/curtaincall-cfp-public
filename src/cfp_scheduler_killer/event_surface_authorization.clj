(ns cfp-scheduler-killer.event-surface-authorization
  "Fail-closed authorization declarations for the private event workspace.

   The legacy auth gate still owns sessions, event resolution, and friendly
   refusals.  This second boundary owns route completeness: registering a new
   /events workspace route without naming its audience must never inherit chair
   authority merely because the caller happens to manage the event."
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.store :as store]
   [clojure.string :as str]
   [taoensso.timbre :as log]))

;; INTENT: AUTHZ-001 — the router and this manifest are checked for exact
;; coverage; a route with no declaration is refused at runtime as well.
(def declared-event-surface-policies
  {[:get "/events/new"]                                      :event-creator

   [:get "/events/:slug"]                                   :speaker
   [:get "/events/:slug/details"]                           :speaker

   [:get "/events/:slug/fragment"]                          :reviewer
   [:get "/events/:slug/committee"]                         :reviewer
   [:get "/events/:slug/submissions/:submission-id"]        :reviewer
   [:get "/events/:slug/board"]                             :reviewer
   [:get "/events/:slug/board/review-scores.csv"]           :reviewer
   [:get "/events/:slug/board/fragment"]                    :reviewer
   [:get "/events/:slug/people/:person-id"]                 :reviewer

   [:get "/events/:slug/exports/sessions.json"]             :public
   [:get "/events/:slug/exports/speakers.json"]             :public
   [:get "/events/:slug/exports/calendar.ics"]              :public
   [:get "/events/:slug/llms.txt"]                          :public

   [:get "/events/:slug/announce"]                          :organizer
   [:get "/events/:slug/speakers/new"]                      :organizer
   [:get "/events/:slug/submissions/:submission-id/manage"] :organizer
   [:get "/events/:slug/review"]                            :organizer
   [:get "/events/:slug/reviewer-progress"]                :organizer
   [:get "/events/:slug/deliverables"]                      :organizer
   [:get "/events/:slug/speakers"]                          :organizer
   [:post "/events/:slug/speakers/filter"]                  :organizer
   [:get "/events/:slug/speakers/:person-id"]               :organizer
   [:post "/events/:slug/submissions/:submission-id/speakers"] :organizer
   [:post "/events/:slug/submissions/:submission-id/speakers/:person-id/remove"] :organizer
   [:get "/events/:slug/files"]                             :organizer
   [:get "/events/:slug/files.zip"]                         :organizer
   [:get "/events/:slug/files/:file-id/download"]           :organizer
   [:post "/events/:slug/mcp"]                              :public
   [:get "/events/:slug/submissions"]                       :organizer
   [:get "/events/:slug/log/fragment"]                      :organizer
   [:get "/events/:slug/inform"]                            :organizer
   [:get "/events/:slug/form"]                              :organizer
   [:get "/events/:slug/settings"]                          :organizer
   [:get "/events/:slug/comms"]                             :organizer
   [:get "/events/:slug/replay"]                            :organizer
   [:get "/events/:slug/capture"]                           :organizer
   [:get "/events/:slug/resources"]                         :organizer
   ;; INTENT: AUTHZ-003 — scheduling is an organizer operation, not review evidence.
   [:get "/events/:slug/schedule"]                          :organizer
   [:get "/events/:slug/exports"]                           :organizer
   [:get "/events/:slug/exports/review-results.csv"]        :organizer
   [:get "/events/:slug/exports/review-results.json"]       :organizer
   [:get "/events/:slug/embed"]                             :organizer
   [:get "/events/:slug/log"]                               :organizer})

(defn event-workspace-route?
  [template]
  (and (string? template)
       (or (= "/events/new" template)
           (= "/events/:slug" template)
           (str/starts-with? template "/events/:slug/"))))

(defn route-policy
  [req]
  (get declared-event-surface-policies
       [(:request-method req)
        (get-in req [:reitit.core/match :template])]))

(defn- event-id
  [snapshot req]
  (let [slug (or (get-in req [:path-params :slug])
                 (get-in req [:path-params "slug"]))]
    (:id (get-in snapshot [:events slug]))))

(defn authorized?
  "A missing person, event, or policy is false.  No default branch permits."
  [req policy]
  (let [person (auth/current-person req)
        snapshot (store/snapshot)
        eid (event-id snapshot req)]
    (boolean
      (case policy
        :public true
        :event-creator (auth/may-create-events? person)
        :speaker (and eid
                      (or (auth/event-manager? snapshot person eid)
                          (auth/member-of-event? snapshot person eid)
                          (auth/speaker-of-event? snapshot person eid)))
        :reviewer (and eid (auth/member-of-event? snapshot person eid))
        :organizer (and eid (auth/event-manager? snapshot person eid))
        false))))

(defn- forbidden
  []
  {:status 403
   :headers {"Content-Type" "text/plain; charset=utf-8"
             "Cache-Control" "no-store"}
   :body "Forbidden"})

(defn wrap-declared-event-authorization
  "Reitit route middleware: fail closed when a workspace route has no policy."
  [handler]
  (fn [req]
    (let [template (get-in req [:reitit.core/match :template])
          snapshot (store/snapshot)
          unknown-event-read? (and (= :get (:request-method req))
                                   (not= "/events/new" template)
                                   (nil? (event-id snapshot req)))]
      (if-not (event-workspace-route? template)
        (handler req)
        (let [policy (route-policy req)]
          (if (and policy
                   (or unknown-event-read?
                       (authorized? req policy)))
            (handler req)
            (do
              (log/info :event-surface-authorization-refused
                        :method (:request-method req)
                        :template template
                        :policy (or policy :missing)
                        :person-id (:id (auth/current-person req)))
              (forbidden))))))))
