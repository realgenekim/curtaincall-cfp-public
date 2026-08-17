(ns cfp-scheduler-killer.handlers.dashboard
  "Web server with http-kit, reitit routing, and dev auto-reload.

   Handler convention (CLAUDE.md): every handler is a named `defn handle-*`
   referenced as `#'var` in the route table, so REPL redefinition takes effect
   without a restart."
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.domain.review-plan :as domain-review-plan]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.forms :as forms]
   [cfp-scheduler-killer.inform :as inform]
   [cfp-scheduler-killer.portal :as portal]
   [cfp-scheduler-killer.review-plan :as review-plan]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.speaker-tasks :as speaker-tasks]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.tracks :as tracks]
   [cfp-scheduler-killer.views.committee :as view-committee]
   [cfp-scheduler-killer.views.dashboard :as view-dashboard]
   [cfp-scheduler-killer.views.event-setup :as view-event-setup]
   [cfp-scheduler-killer.views.log :as view-log]
   [cfp-scheduler-killer.views.people :as view-people]
   [cfp-scheduler-killer.views.shell :as view-shell]
   [cfp-scheduler-killer.web.datastar :as datastar]
   [cfp-scheduler-killer.web.event :as web-event]
   [cfp-scheduler-killer.web.http :as web-http]
   [hiccup2.core :as h]
   [taoensso.timbre :as log])
  (:gen-class))

(defonce ^:private pending-reviewer-links (atom {}))

(defn- submission-projector
  "Capture the viewer's current presenter-visibility rights before rendering.

   The returned projection remains authoritative inside a historical fold, so
   scrubbing to a moment before blind review was enabled cannot revive identity."
  [event person]
  (let [snapshot (store/snapshot)]
    #(when %
       (domain-review-plan/project-submission
         snapshot (:id event) (:id person) %))))

(defn- speaker-materials-for-dashboard
  "Chair-only identity-bearing chase rows. This boundary is intentionally
   server-side: blind-review callers never receive speaker material HTML."
  [event person]
  (if (= "chair" (committees/role-on-event (:id event) (:id person)))
    (speaker-tasks/materials-chase-list-for-event (:id event))
    []))

(defn- dashboard-state
  "Build the Mission Control facts from the currently bound store projection."
  ([req event member-form]
   (let [person (auth/current-person req)]
     (dashboard-state req event member-form
                      person (submission-projector event person))))
  ([_req event member-form person visible-submission]
   (let [cs (events/committees-for-event (:id event))
         committee (first cs)
         members (if committee (committees/members-for-committee (:id committee)) [])
         enriched (reviews/enriched-for-event (:id event))]
     {:committee (when committee
                   {:committee-id (:id committee) :name (:name committee)})
      :member-count (count members)
      :chair-assigned? (committees/chair-assigned? (:id event))
      :member-form member-form
      :subs (submissions/for-event (:id event))
      :sub-count (submissions/count-for-event (:id event))
      :speaker-count (submissions/unique-speaker-count (:id event))
      :cfp-state (submissions/cfp-state event)
      :alerts (inform/alert-rows event)
      :uncommunicated (count (inform/pending-decisions (:id event)))
      ;; Speaker identity remains a chair-only server projection. Reviewers are
      ;; never sent these rows, including through the dashboard fragment.
      :speaker-materials (speaker-materials-for-dashboard event person)
      ;; Setup completion follows the form speakers can actually submit, not a
      ;; second acknowledgement click after creation already installed it.
      :form-configured? (forms/configured? (:id event))
      :form-field-count (count (submissions/session-fields
                                 (forms/active-fields (forms/fields-for-event (:id event)))))
      ;; Mission-control data (Gene, 2026-08-10): tiles, queues, feed.
      ;; The dashboard fragment is reviewer-authorized and its recent feed
      ;; renders speaker name, organization, and headshot. Project at this
      ;; view-model boundary just like the board; only the chair keeps the raw
      ;; identity-bearing rows.
      :enriched (mapv visible-submission enriched)
      :coverage (reviews/coverage (:id event)
                                  (or (some-> committee :coverage-target) 2))
      :recent (->> (store/log-for-event (:id event)) (take-last 12) reverse vec)
      :person-name-of (fn [pid] (get-in @store/state [:people pid :name]))
      :submission-of (comp visible-submission store/submission-by-id)
      :person person})))

(defn handle-event-log
  "This event's slice of the store, rendered as readable history. Nearly free:
   it is a filter over the same rows the whole app is folded from."
  [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (let [tt (web-event/time-travel-context req event (str "/events/" slug "/log"))]
        (web-event/with-as-of (:as-of tt)
          (let [past-event (or (events/event-by-slug slug) event)]
            (web-http/html-response (view-log/log-page past-event
                                                       (events/log-for-event (:id past-event))
                                                       (auth/current-person req)
                                                       tt)))))
      (web-event/not-found-page slug))))

(defn handle-person-detail
  "One person, in the context of one event."
  [req]
  (let [slug (get-in req [:path-params :slug])
        person-id (web-http/clean-id (get-in req [:path-params :person-id]))
        event (events/event-by-slug slug)]
    (cond
      (nil? event) (web-event/not-found-page slug)
      (nil? person-id) (web-event/not-found-page (str slug "/people/…"))
      :else
      (if-let [detail (committees/person-detail (:id event) person-id)]
        (web-http/html-response (view-people/person-page
                                  event
                                  (assoc detail
                                         :viewer (auth/current-person req)
                                         :review-summary
                                         (reviews/reviewer-summary (:id event) person-id))))
        (web-http/html-response 404 (view-shell/page-shell
                                      "Person not found"
                                      [:div.ui.warning.message
                                       [:div.header "No such person"]
                                       [:p "We don't have anyone with that id."]]
                                      [:a.ui.basic.button {:href (str "/events/" slug)}
                                       "Back to the event"]))))))

(defn log-fragment-html
  [req event]
  (let [tt (web-event/time-travel-context req event (str "/events/" (:slug event) "/log"))]
    (web-event/with-as-of (:as-of tt)
      (let [past-event (or (events/event-by-slug (:slug event)) event)]
        (str (h/html (view-log/log-region past-event
                                          (events/log-for-event (:id past-event)))))))))

(defn render-committee-page
  "Render step 3's own page. `member-form` carries a rejected add's state."
  ([req event] (render-committee-page req event nil 200))
  ([req event member-form status]
   (render-committee-page req event member-form status nil))
  ([req event member-form status sign-in-links]
   (let [committee (first (events/committees-for-event (:id event)))]
     (web-http/html-response
       status
       (view-committee/committee-page
         event
         {:committee (when committee
                       {:committee-id (:id committee) :name (:name committee)})
          :members (if committee
                     (committees/members-for-committee (:id committee)) [])
          :track-options (tracks/tracks-for-event event)
          :member-form member-form
          :sign-in-links sign-in-links
          :cfp-state (submissions/cfp-state event)
          :presenter-visibility
          (review-plan/presenter-visibility-policy (:id event))
          :presenter-visibility-definition
          domain-review-plan/presenter-visibility-policy-definition
          :person (auth/current-person req)})))))

(defn dashboard-fragment-html
  "The Mission Control facts as of the slider position. The page shell and
   scrubber stay outside this fragment so a patch cannot interrupt dragging."
  [req event]
  (let [tt (web-event/time-travel-context req event (str "/events/" (:slug event)))
        person (auth/current-person req)
        visible-submission (submission-projector event person)]
    (web-event/with-as-of (:as-of tt)
      (let [past-event (or (events/event-by-slug (:slug event)) event)]
        (str (h/html
               (view-dashboard/event-dashboard-region
                 (web-http/request-host req)
                 past-event
                 (dashboard-state req past-event nil person visible-submission))))))))

(defn render-event-dashboard
  "Render an event's dashboard. `member-form` carries the state of a rejected
   add-member attempt so the form comes back filled in with its errors."
  ([req event] (render-event-dashboard req event nil 200))
  ([req event member-form status]
   (let [base-path (str "/events/" (:slug event))
         tt (web-event/time-travel-context req event base-path)]
     (web-http/html-response
       status
       (view-dashboard/event-dashboard-page
         (web-http/request-host req)
         event
         (assoc (dashboard-state req event member-form) :time-travel tt))))))

(defn handle-log-fragment [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (datastar/sse-fragment-response req "#log-region" (log-fragment-html req event))
      (web-event/not-found-page slug))))

(defn handle-add-member
  "Plain form POST. Valid → 303 back to the event dashboard.
   Invalid / already a member → 422 re-render of the dashboard with the message
   next to the add form. All validation is server-side."
  [req]
  (let [committee-id (web-http/clean-id (get-in req [:path-params :committee-id]))
        committee (when committee-id (committees/committee-by-id committee-id))]
    (if-not committee
      (web-event/not-found-page (str "committee " (get-in req [:path-params :committee-id])))
      (let [event (events/event-by-slug (:event-slug committee))
            params (:params req)
            draft (committees/parse-member-form params)
            errors (committees/validation-errors draft)
            reject (fn [errs message]
                     (render-committee-page req event
                                            {:values params :errors errs :message message}
                                            422))]
        (if errors
          (do (log/info :member-add-rejected :fields (vec (keys errors)))
              (reject errors nil))
          (try
            (let [member (committees/add-member! committee-id draft)
                  actor (auth/current-person req)
                  origin (web-http/request-host req)
                  next-path (str "/events/" (:event-slug committee) "/board?assigned=1")
                  token (auth/issue-token!
                          (:email member)
                          {:origin origin
                           :context {:event-id (:id event)
                                     :kind "committee-invite"
                                     :person-id (:person-id member)
                                     :actor (:email actor)}
                           :letter-fn
                           (fn [token _]
                             {:to (:email member)
                              :subject (str "You're invited to review for " (:name event))
                              :body (str "Hi " (:name member) ",\n\n"
                                         (or (:name actor) (:email actor))
                                         " invited you to review for " (:name event)
                                         ".\n\nOpen your reviewer dashboard:\n\n    "
                                         origin "/auth/" token "?next="
                                         (java.net.URLEncoder/encode next-path "UTF-8")
                                         "\n\nThis one-time link expires in 24 hours.")})})
                  link (str origin "/auth/" token "?next="
                            (java.net.URLEncoder/encode next-path "UTF-8"))
                  flash-key [(:id (auth/current-person req)) (:id event)]]
              (swap! pending-reviewer-links assoc flash-key
                     {(:person-id member) {:link link :delivery :queued}})
              (web-http/see-other (str "/events/" (:event-slug committee) "/committee")))
            (catch clojure.lang.ExceptionInfo e
              (if (= :already-member (:type (ex-data e)))
                (reject nil (str (:email draft) " is already on this committee."))
                (throw e)))))))))

(defn handle-event-committee
  "GET /events/:slug/committee — create / edit the review committee, a page
   you go to rather than an anchor you scroll to (Gene, 2026-08-09)."
  [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (let [flash-key [(:id (auth/current-person req)) (:id event)]
            links (get @pending-reviewer-links flash-key)]
        (swap! pending-reviewer-links dissoc flash-key)
        (render-committee-page req event nil 200 links))
      (web-event/not-found-page slug))))

(defn handle-remove-member
  "Delete a membership and 303 back to the dashboard. The person row survives —
   identity persists across committees and events.

   Removing the LAST reviewer is refused with a 422 and a sentence, not a stack
   trace: since authorization became per-event, that click would have locked
   everyone out of the conference for good."
  [req]
  (let [membership-id (web-http/clean-id (get-in req [:path-params :membership-id]))
        m (when membership-id (committees/membership-by-id membership-id))]
    (if-not m
      (web-event/not-found-page (str "membership " (get-in req [:path-params :membership-id])))
      (try
        (committees/remove-member! membership-id (:email (auth/current-person req)))
        (web-http/see-other (str "/events/" (:event-slug m) "/committee"))
        (catch clojure.lang.ExceptionInfo e
          (if (= :last-reviewer (:type (ex-data e)))
            (render-committee-page
              req (events/event-by-slug (:event-slug m))
              {:message (str "You are the last reviewer on this event — removing "
                             "the last one would lock everyone out of it. Add "
                             "someone else to the program committee first.")}
              422)
            (throw e)))))))

(defn handle-member-scope
  "Set one reviewer's default track queue and repaint only that member's chips."
  [req]
  (let [committee-id (web-http/clean-id (get-in req [:path-params :committee-id]))
        person-id (web-http/clean-id (get-in req [:path-params :person-id]))
        committee (when committee-id (committees/committee-by-id committee-id))
        event (when committee (events/event-by-slug (:event-slug committee)))
        member (when person-id
                 (->> (committees/members-for-committee committee-id)
                      (filter #(= person-id (:person-id %)))
                      first))
        actor (auth/current-person req)
        action (some-> (get-in req [:params :action]) keyword)
        track (get-in req [:params :track])
        track-options (when event (tracks/tracks-for-event event))]
    (cond
      (or (nil? committee) (nil? event) (nil? member))
      (web-event/not-found-page "committee member")

      (not (auth/event-manager? actor (:id event)))
      {:status 403
       :headers {"Content-Type" "text/plain; charset=utf-8"
                 "Cache-Control" "no-store"}
       :body "Only an event chair may change reviewer track scopes."}

      (and (not= :all action) (not (some #{track} track-options)))
      {:status 422
       :headers {"Content-Type" "text/plain; charset=utf-8"
                 "Cache-Control" "no-store"}
       :body "Choose a track from Event details."}

      :else
      (let [current (committees/member-scope member)
            desired (case action
                      :all :all
                      :only #{track}
                      :add (conj (if (= :all current) #{} current) track)
                      :remove (if (= :all current)
                                :all
                                (committees/normalize-member-scope (disj current track)))
                      current)
            updated (if (= current desired)
                      member
                      (committees/set-member-scope!
                        committee-id person-id desired (or (:email actor) "organizer")))]
        (datastar/sse-fragment-response
          req
          (str "#member-scope-" (:membership-id member))
          (str (h/html
                 (view-committee/member-scope-controls
                   committee-id track-options updated true))))))))

(defn handle-dashboard-fragment
  "Patches only Mission Control facts for this organizer's scrub position."
  [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (datastar/sse-fragment-response req "#dashboard-region" (dashboard-fragment-html req event))
      (web-event/not-found-page slug))))

(defn handle-event-dashboard [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (let [person (auth/current-person req)
            role (when person
                   (committees/role-on-event (:id event) (:id person)))
            submission (and (not (auth/member-of-event? person (:id event)))
                            (portal/submission-for-event (:id person) (:id event)))]
        (cond
          submission
          (web-http/html-response
            (view-event-setup/speaker-event-overview-page event person submission))

          (= "reviewer" role)
          (web-http/see-other (str "/events/" slug "/review"))

          :else
          (render-event-dashboard req event)))
      (web-event/not-found-page slug))))
