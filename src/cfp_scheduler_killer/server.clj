(ns cfp-scheduler-killer.server
  "Web server with http-kit, reitit routing, and dev auto-reload.

   Handler convention (CLAUDE.md): every handler is a named `defn handle-*`
   referenced as `#'var` in the route table, so REPL redefinition takes effect
   without a restart."
  (:require
   [cfp-scheduler-killer.auth-google :as auth-google]
   [cfp-scheduler-killer.dev-warmup :as dev-warmup]
   [cfp-scheduler-killer.event-surface-authorization :as event-surface-authorization]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.handlers.agent :as agent-handlers]
   [cfp-scheduler-killer.handlers.announce :as announce-handlers]
   [cfp-scheduler-killer.handlers.auth :as auth-handlers]
   [cfp-scheduler-killer.handlers.board :as board-handlers]
   [cfp-scheduler-killer.handlers.communications :as communication-handlers]
   [cfp-scheduler-killer.handlers.crm :as crm-handlers]
   [cfp-scheduler-killer.handlers.dashboard :as dashboard-handlers]
   [cfp-scheduler-killer.handlers.dev :as dev-handlers]
   [cfp-scheduler-killer.handlers.events :as event-handlers]
   [cfp-scheduler-killer.handlers.exports :as export-handlers]
   [cfp-scheduler-killer.handlers.files :as file-handlers]
   [cfp-scheduler-killer.handlers.forms :as form-handlers]
   [cfp-scheduler-killer.handlers.health :as health-handlers]
   [cfp-scheduler-killer.handlers.integrations :as integration-handlers]
   [cfp-scheduler-killer.handlers.portal :as portal-handlers]
   [cfp-scheduler-killer.handlers.public-api :as public-api-handlers]
   [cfp-scheduler-killer.handlers.public-cfp :as public-cfp-handlers]
   [cfp-scheduler-killer.handlers.public-widgets :as public-widget-handlers]
   [cfp-scheduler-killer.handlers.resource-pages :as resource-page-handlers]
   [cfp-scheduler-killer.handlers.replay :as replay-handlers]
   [cfp-scheduler-killer.handlers.review-plan :as review-plan-handlers]
   [cfp-scheduler-killer.handlers.schedule :as schedule-handlers]
   [cfp-scheduler-killer.handlers.speaker-tasks :as speaker-task-handlers]
   [cfp-scheduler-killer.handlers.speakers :as speaker-handlers]
   [cfp-scheduler-killer.middleware :as middleware]
   [cfp-scheduler-killer.routes-additive :as additive-routes]
   [cfp-scheduler-killer.sinks :as sinks]
   [cfp-scheduler-killer.speakers :as speaker-domain]
   [cfp-scheduler-killer.sse :as sse]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.telemetry :as telemetry]
   [cfp-scheduler-killer.web.not-found :as web-not-found]
   [org.httpkit.server :as http]
   [reitit.ring :as ring]
   [taoensso.timbre :as log])
  (:gen-class))

(defonce server (atom nil))

(def render-cfp public-cfp-handlers/render-cfp)
(def board-state board-handlers/board-state)
(def board-fragment-html board-handlers/board-fragment-html)
(def dashboard-fragment-html dashboard-handlers/dashboard-fragment-html)
(def log-fragment-html dashboard-handlers/log-fragment-html)
(defn cloud-run-service? [] (middleware/cloud-run-service?))
(defn cookie-key-bytes [secret] (middleware/cookie-key-bytes secret))
(defn make-session-cookie-store []
  (with-redefs [middleware/cloud-run-service? cloud-run-service?
                middleware/cookie-key-bytes cookie-key-bytes]
    (middleware/make-session-cookie-store)))
(def handle-update-speaker
  (fn [req]
    (let [event (events/event-by-slug (get-in req [:path-params :slug]))
          person-id (get-in req [:path-params :person-id])]
      ((if (some #(= person-id (:person-id %))
                 (speaker-domain/roster-for-event (:id event)))
         speaker-handlers/handle-edit-speaker
         announce-handlers/handle-update-speaker) req))))

;; --- Helpers ---

(defn make-routes []
  (additive-routes/with-additive
    [["/" {:get {:handler #'event-handlers/handle-home}}]
     ["/ping" {:get {:handler #'health-handlers/handle-ping}}]
     ["/events" {:get {:handler #'event-handlers/handle-events-list}}]
     ["/people" {:get {:handler #'crm-handlers/handle-people}}]
     ["/people/outreach" {:get {:handler #'crm-handlers/handle-outreach}}]
     ["/people/:person-id" {:get {:handler #'crm-handlers/handle-person}}]
     ["/api/people/:person-id/notes" {:post {:handler #'crm-handlers/handle-note-add}}]
     ["/api/people/:person-id/tags/add" {:post {:handler #'crm-handlers/handle-tag-add}}]
     ["/api/people/:person-id/tags/remove" {:post {:handler #'crm-handlers/handle-tag-remove}}]
     ["/api/people/:person-id/events/add" {:post {:handler #'crm-handlers/handle-push-to-event}}]
     ["/api/people/import/preview" {:post {:handler #'crm-handlers/handle-import-preview}}]
     ["/api/people/import" {:post {:handler #'crm-handlers/handle-import}}]
     ["/api/people/segments" {:post {:handler #'crm-handlers/handle-segment-save}}]
     ["/api/people/segments/:segment-id/remove" {:post {:handler #'crm-handlers/handle-segment-remove}}]
     ["/api/people/outreach/preview" {:post {:handler #'crm-handlers/handle-outreach-preview}}]
     ["/api/people/outreach/record" {:post {:handler #'crm-handlers/handle-outreach-record}}]
     ["/events/new" {:get {:handler #'event-handlers/handle-new-event}}]
     ["/events/:slug" {:get {:handler #'dashboard-handlers/handle-event-dashboard}}]
     ["/events/:slug/fragment" {:get {:handler #'dashboard-handlers/handle-dashboard-fragment}}]
     ["/events/:slug/details" {:get {:handler #'event-handlers/handle-event-details}}]
     ["/events/:slug/committee" {:get {:handler #'dashboard-handlers/handle-event-committee}}]
     ["/api/events/:slug/details" {:post {:handler #'event-handlers/handle-event-details-save}}]
     ["/events/:slug/submissions" {:get {:handler #'board-handlers/handle-submissions-list}}]
     ["/events/:slug/submissions/:submission-id" {:get {:handler #'board-handlers/handle-submission-detail}}]
     ["/api/events/:slug/submissions/:submission-id/content"
      {:post {:handler #'board-handlers/handle-session-content}}]
     ["/api/events/:slug/submissions/:submission-id/history/:log-index/restore"
      {:post {:handler #'board-handlers/handle-session-content-restore}}]
     ["/events/:slug/board" {:get {:handler #'board-handlers/handle-board}}]
     ["/events/:slug/review" {:get {:handler #'board-handlers/handle-reviewer-queue}}]
     ["/events/:slug/reviewer-progress" {:get {:handler #'board-handlers/handle-reviewer-progress}}]
     ["/events/:slug/board/review-scores.csv" {:get {:handler #'export-handlers/handle-export-review-scores}}]
     ["/events/:slug/board/fragment" {:get {:handler #'board-handlers/handle-board-fragment}}]
     ["/events/:slug/log/fragment" {:get {:handler #'dashboard-handlers/handle-log-fragment}}]
     ["/events/:slug/inform" {:get {:handler #'communication-handlers/handle-inform-page}}]
     ["/events/:slug/deliverables" {:get {:handler #'speaker-task-handlers/handle-deliverables}}] ;; INTENT: NAV-004
     ["/api/events/:slug/reminder-schedule" {:post {:handler #'speaker-task-handlers/handle-reminder-schedule}}]
     ["/events/:slug/speakers" {:get {:handler #'speaker-handlers/handle-speakers}}] ;; INTENT: NAV-003
     ["/events/:slug/speakers/filter" {:post {:handler #'speaker-handlers/handle-speakers-filter}}]
     ["/events/:slug/speakers/:person-id" {:get {:handler #'speaker-handlers/handle-speaker-detail}}]
     ["/events/:slug/files" {:get {:handler #'file-handlers/handle-files}}] ;; INTENT: NAV-005
     ["/events/:slug/files.zip" {:get {:handler #'file-handlers/handle-files-zip}}]
     ["/events/:slug/files/:file-id/download" {:get {:handler #'file-handlers/handle-organizer-download}}]
     ["/api/events/:slug/speakers" {:post {:handler #'speaker-handlers/handle-add-speaker}}]
     ["/api/events/:slug/speaker-fields"
      {:post {:handler #'speaker-handlers/handle-define-custom-field}}]
     ["/api/events/:slug/speakers/import/preview" {:post {:handler #'speaker-handlers/handle-import-preview}}]
     ["/api/events/:slug/speakers/import" {:post {:handler #'speaker-handlers/handle-import-speakers}}]
     ["/api/events/:slug/speakers/:person-id/status" {:post {:handler #'speaker-handlers/handle-speaker-status}}]
     ["/api/events/:slug/speakers/:person-id/portal-invite"
      {:post {:handler #'speaker-handlers/handle-portal-invite}}]
     ["/api/events/:slug/speakers/:person-id" {:post {:handler #'handle-update-speaker}}]
     ["/events/:slug/form" {:get {:handler #'form-handlers/handle-form-builder}}]
     ["/events/:slug/settings" {:get {:handler #'integration-handlers/handle-settings}}]
     ["/events/:slug/comms" {:get {:handler #'communication-handlers/handle-comms}}]
     ["/events/:slug/replay" {:get {:handler #'replay-handlers/handle-replay-page}}]
     ["/events/:slug/capture" {:get {:handler #'communication-handlers/handle-capture-page}}]
     ["/events/:slug/schedule" {:get {:handler #'schedule-handlers/handle-schedule}}]
     ["/events/:slug/resources" {:get {:handler #'resource-page-handlers/handle-organizer-pages}}]
     ["/agenda/:slug" {:get {:handler #'public-widget-handlers/handle-agenda}}]
     ["/agenda/:slug/sessions" {:get {:handler #'public-widget-handlers/handle-public-sessions}}]
     ["/agenda/:slug/sessions/:submission-id" {:get {:handler #'public-widget-handlers/handle-public-session}}]
     ["/agenda/:slug/speakers" {:get {:handler #'public-widget-handlers/handle-public-speakers}}]
     ["/agenda/:slug/directory" {:get {:handler #'public-widget-handlers/handle-public-directory}}]
     ["/agenda/:slug/speakers/filter" {:post {:handler #'public-widget-handlers/handle-public-speakers-filter}}]
     ["/agenda/:slug/speakers/:person-id" {:get {:handler #'public-widget-handlers/handle-public-speaker}}]
     ["/agenda/:slug/itinerary" {:get {:handler #'public-widget-handlers/handle-public-itinerary}}]
     ["/agenda/:slug/my" {:get {:handler #'public-widget-handlers/handle-my-schedule}}]
     ["/agenda/:slug/my.ics" {:post {:handler #'public-widget-handlers/handle-my-schedule-ics}}]
     ["/program/:slug/resources" {:get {:handler #'resource-page-handlers/handle-public-index}}]
     ["/program/:slug/resources/:page-slug" {:get {:handler #'resource-page-handlers/handle-public-page}}]
     ;; NOTE the missing trailing segment: this is the organizer-gated HTML index.
     ;; /events/:slug/exports/<file> below are the PUBLIC raw files.
     ["/events/:slug/exports" {:get {:handler #'export-handlers/handle-exports-page}}]
     ["/events/:slug/embed" {:get {:handler #'export-handlers/handle-embed-builder}}]
     ["/events/:slug/exports/sessions.json" {:get {:handler #'export-handlers/handle-export-sessions}}]
     ["/events/:slug/exports/speakers.json" {:get {:handler #'export-handlers/handle-export-speakers}}]
     ["/events/:slug/exports/review-results.csv" {:get {:handler #'export-handlers/handle-export-review-results-csv}}]
     ["/events/:slug/exports/review-results.json" {:get {:handler #'export-handlers/handle-export-review-results-json}}]
     ["/events/:slug/exports/calendar.ics" {:get {:handler #'export-handlers/handle-export-ics}}]
     ["/llms.txt" {:get {:handler #'export-handlers/handle-site-llms-txt}}] ; ROOT agent index
     ["/events/:slug/llms.txt" {:get {:handler #'export-handlers/handle-llms-txt}}]
     ["/events/:slug/mcp" {:post {:handler #'agent-handlers/handle-mcp}}]
     ;; /api/v1 is public by construction; handlers decide what a token widens.
     ;; Both index spellings are routed because the auth pattern includes the slash.
     ["/api/v1/" {:get {:handler #'public-api-handlers/handle-api-index}}]
     ["/api/v1" {:get {:handler #'public-api-handlers/handle-api-index}}]
     ["/api/v1/events/:slug" {:get {:handler #'public-api-handlers/handle-api-event}}]
     ["/api/v1/events/:slug/docs" {:get {:handler #'public-api-handlers/handle-api-docs}}]
     ["/api/v1/events/:slug/review-policy"
      {:get {:handler #'public-api-handlers/handle-api-review-policy-get}
       :put {:handler #'public-api-handlers/handle-api-review-policy-put}}]
     ["/api/v1/events/:slug/sessions" {:get {:handler #'public-api-handlers/handle-api-sessions}}]
     ["/api/v1/events/:slug/speakers" {:get {:handler #'public-api-handlers/handle-api-speakers}}]
     ["/api/v1/events/:slug/speakers/:person-id" {:get {:handler #'public-api-handlers/handle-api-speaker}}]
     ["/api/v1/events/:slug/speakers/:person-id/publish"
      {:post {:handler #'public-api-handlers/handle-api-speaker-publish}}]
     ["/api/v1/events/:slug/speakers/:person-id/unpublish"
      {:post {:handler #'public-api-handlers/handle-api-speaker-unpublish}}]
     ["/api/v1/events/:slug/schedule" {:get {:handler #'public-api-handlers/handle-api-schedule}}]
     ["/api/v1/events/:slug/rooms" {:get {:handler #'public-api-handlers/handle-api-rooms}}]
     ["/api/v1/events/:slug/changes" {:get {:handler #'public-api-handlers/handle-api-changes}}]
     ["/api/v1/events/:slug/submissions" {:get {:handler #'public-api-handlers/handle-api-submissions}}]
     ["/api/v1/events/:slug/submissions/:submission-id" {:get {:handler #'public-api-handlers/handle-api-submission}}]
     ["/api/v1/events/:slug/submissions/:submission-id/reviews"
      {:post {:handler #'public-api-handlers/handle-api-review-create}}]
     ["/portal" {:get {:handler #'portal-handlers/handle-portal}}]
     ;; The portal's live lane. Under /portal, not /api/*, because that prefix is
     ;; what auth/speaker-prefixes already opens to a speaker (see the handlers).
     ["/portal/stream" {:get {:handler #'portal-handlers/handle-portal-stream}}]
     ["/portal/draft" {:post {:handler #'portal-handlers/handle-portal-draft}}]
     ["/headshots/:file-id" {:get {:handler #'file-handlers/handle-headshot}}]
     ["/events/:slug/log" {:get {:handler #'dashboard-handlers/handle-event-log}}]
     ["/events/:slug/people/:person-id" {:get {:handler #'dashboard-handlers/handle-person-detail}}]
     ["/cfp/:slug" {:get {:handler #'public-cfp-handlers/handle-public-cfp}}]
     ["/cfp/:slug/submitted/:submission-id" {:get {:handler #'public-cfp-handlers/handle-cfp-submitted}}]
     ["/api/cfp/:slug/submit" {:post {:handler #'public-cfp-handlers/handle-cfp-submit}}]
     ["/api/cfp/:slug/import-sessionize" {:post {:handler #'public-cfp-handlers/handle-cfp-import}}]
     ["/api/cfp/:slug/import-live" {:post {:handler #'public-cfp-handlers/handle-cfp-import-live}}]
     ;; Speaker live lanes stay public because submitters have no account yet.
     ["/api/cfp/:slug/draft" {:post {:handler #'public-cfp-handlers/handle-cfp-draft}}]
     ["/api/cfp/:slug/draft/reset"
      {:post {:handler #'public-cfp-handlers/handle-cfp-draft-reset}}]
     ["/api/cfp/live" {:get {:muuntaja false
                             :handler #'public-cfp-handlers/handle-cfp-live}}]
     ["/api/cfp/:slug/stream" {:get {:handler #'public-cfp-handlers/handle-cfp-stream}}]
     ["/api/telemetry/beacon" {:post {:handler #'dev-handlers/handle-telemetry-beacon}}]
     ["/api/events/create" {:post {:handler #'event-handlers/handle-create-event}}]
     ["/api/events/demo" {:post {:handler #'event-handlers/handle-create-demo-event}}]
     ;; Organizer-side but belongs to NO event — see auth/unscoped-paths.
     ["/api/events/preview" {:post {:handler #'event-handlers/handle-events-preview}}]
     ["/api/events/archive" {:post {:handler #'event-handlers/handle-event-archive}}]
     ["/api/events/:slug/unarchive" {:post {:handler #'event-handlers/handle-event-unarchive}}]
     ["/api/events/:slug/default" {:post {:handler #'event-handlers/handle-default-event}}]
     ["/api/events/draft-pref" {:post {:handler #'event-handlers/handle-draft-pref}}]
     ["/api/events/:slug/cfp/open" {:post {:handler #'event-handlers/handle-cfp-open}}]
     ["/api/events/:slug/cfp/close" {:post {:handler #'event-handlers/handle-cfp-close}}]
     ["/api/events/:slug/cfp/close-date" {:post {:handler #'event-handlers/handle-cfp-close-date}}]
     ["/api/committees/:committee-id/members/add" {:post {:handler #'dashboard-handlers/handle-add-member}}]
     ["/api/committees/:committee-id/members/:person-id/scope" {:post {:handler #'dashboard-handlers/handle-member-scope}}]
     ["/api/memberships/:membership-id/remove" {:post {:handler #'dashboard-handlers/handle-remove-member}}]
     ["/login" {:get {:handler #'auth-handlers/handle-login-page}}]
     ["/api/login" {:post {:handler #'auth-handlers/handle-login}}]
     ;; /auth/google MUST precede /auth/:token or "google" is read as a token.
     ["/auth/google" {:get {:handler #'auth-google/handle-start}}]
     ["/auth/google/callback" {:get {:handler #'auth-google/handle-callback}}]
     ["/auth/:token" {:get {:handler #'auth-handlers/handle-auth-token}}]
     ["/api/demo-login" {:post {:handler #'auth-handlers/handle-demo-login}}]
     ["/logout" {:post {:handler #'auth-handlers/handle-logout}}]
     ["/api/sse" {:get {:handler #'sse/handle-sse}}]
     ["/api/events/:slug/board/sort" {:post {:handler #'board-handlers/handle-board-sort}}]
     ["/api/submissions/:submission-id/rate" {:post {:handler #'board-handlers/handle-rate}}]
     ["/api/submissions/:submission-id/comment" {:post {:handler #'board-handlers/handle-comment}}]
     ["/api/submissions/:submission-id/reviewers/:person-id/assign"
      {:post {:handler #'board-handlers/handle-assign-reviewer}}]
     ["/api/submissions/:submission-id/reviewers/:person-id/unassign"
      {:post {:handler #'board-handlers/handle-unassign-reviewer}}]
     ["/api/submissions/:submission-id/recuse"
      {:post {:handler #'board-handlers/handle-recuse-reviewer}}]
     ["/api/submissions/:submission-id/unrecuse"
      {:post {:handler #'board-handlers/handle-unrecuse-reviewer}}]
     ["/api/events/:slug/reviewer-nudges/draft"
      {:post {:handler #'board-handlers/handle-reviewer-nudge-draft}}]
     ["/api/events/:slug/reviewer-nudges/record"
      {:post {:handler #'board-handlers/handle-reviewer-nudge-record}}]
     ["/api/events/:slug/reviewers/distribute/preview" {:post {:handler #'board-handlers/handle-reviewer-distribution-preview}}]
     ["/api/events/:slug/reviewers/distribute" {:post {:handler #'board-handlers/handle-reviewer-distribution-confirm}}]
     ["/api/events/:slug/review-plan/presenter-visibility"
      {:post {:handler #'review-plan-handlers/handle-presenter-visibility}}]
     ["/api/submissions/:submission-id/status" {:post {:handler #'board-handlers/handle-status}}]
     ["/api/submissions/:submission-id/content-status" {:post {:handler #'board-handlers/handle-content-status}}]
     ["/api/submissions/:submission-id/priority" {:post {:handler #'board-handlers/handle-priority}}]
     ["/api/submissions/:submission-id/inform" {:post {:handler #'communication-handlers/handle-inform-one}}]
     ["/api/events/:slug/deliverables/:submission-id/:task-key/chase"
      {:post {:handler #'speaker-task-handlers/handle-record-chase}}]
     ["/api/events/:slug/speaker-chases/draft"
      {:post {:handler #'speaker-task-handlers/handle-draft-chases}}]
     ["/api/events/:slug/speaker-chases/send"
      {:post {:handler #'speaker-task-handlers/handle-send-chases}}]
     ["/api/events/:slug/files/requests" {:post {:handler #'file-handlers/handle-request-file}}]
     ["/api/events/:slug/files/:file-id/comment"
      {:post {:handler #'file-handlers/handle-organizer-comment}}]
     ["/api/submissions/:submission-id/answers" {:post {:handler #'portal-handlers/handle-portal-answers}}]
     ["/api/submissions/:submission-id/task" {:post {:handler #'portal-handlers/handle-portal-task}}]
     ["/api/submissions/:submission-id/files/:task-key/upload"
      {:post {:handler #'file-handlers/handle-speaker-upload}}]
     ["/api/submissions/:submission-id/files/:file-id/comment"
      {:post {:handler #'file-handlers/handle-speaker-comment}}]
     ["/api/submissions/:submission-id/files/:file-id/download"
      {:get {:handler #'file-handlers/handle-speaker-download}}]
     ["/api/submissions/:submission-id/headshot"
      {:post {:handler #'file-handlers/handle-profile-headshot}}]
     ["/api/events/:slug/inform-all" {:post {:handler #'communication-handlers/handle-inform-all}}]
     ["/api/profile" {:post {:handler #'portal-handlers/handle-portal-profile}}]
     ["/api/events/:slug/speaker-custom-values"
      {:post {:handler #'portal-handlers/handle-portal-custom-values}}]
     ["/api/events/:slug/schedule/place" {:post {:handler #'schedule-handlers/handle-schedule-place}}]
     ["/api/events/:slug/resources" {:post {:handler #'resource-page-handlers/handle-page-save}}]
     ["/api/events/:slug/schedule/clear" {:post {:handler #'schedule-handlers/handle-schedule-clear}}]
     ["/api/events/:slug/schedule/room-add" {:post {:handler #'schedule-handlers/handle-room-add}}]
     ["/api/events/:slug/schedule/room-remove" {:post {:handler #'schedule-handlers/handle-room-remove}}]
     ["/api/events/:slug/schedule/track-add" {:post {:handler #'schedule-handlers/handle-track-add}}]
     ["/api/events/:slug/schedule/track-rename" {:post {:handler #'schedule-handlers/handle-track-rename}}]
     ["/api/events/:slug/schedule/track-retire" {:post {:handler #'schedule-handlers/handle-track-retire}}]
     ["/api/events/:slug/schedule/block-add" {:post {:handler #'schedule-handlers/handle-block-add}}]
     ["/api/events/:slug/schedule/block-remove" {:post {:handler #'schedule-handlers/handle-block-remove}}]
     ["/api/events/:slug/schedule/lock" {:post {:handler #'schedule-handlers/handle-schedule-lock}}]
     ["/api/events/:slug/schedule/unlock" {:post {:handler #'schedule-handlers/handle-schedule-unlock}}]
     ["/api/events/:slug/schedule/publish" {:post {:handler #'schedule-handlers/handle-schedule-publish}}]
     ["/api/events/:slug/schedule/suggest" {:post {:handler #'schedule-handlers/handle-schedule-suggest}}]
     ["/api/events/:slug/form/preview" {:post {:handler #'form-handlers/handle-form-preview}}]
     ["/api/events/:slug/form/add" {:post {:handler #'form-handlers/handle-form-add}}]
     ["/api/events/:slug/form/update" {:post {:handler #'form-handlers/handle-form-update}}]
     ["/api/events/:slug/form/move" {:post {:handler #'form-handlers/handle-form-move}}]
     ["/api/events/:slug/form/retire-ask" {:post {:handler #'form-handlers/handle-form-retire-ask}}]
     ["/api/events/:slug/form/retire" {:post {:handler #'form-handlers/handle-form-retire}}]
     ["/api/events/:slug/form/retire-cancel" {:post {:handler #'form-handlers/handle-form-retire-cancel}}]
     ["/api/events/:slug/form/restore" {:post {:handler #'form-handlers/handle-form-restore}}]
     ["/api/events/:slug/form/reviewed" {:post {:handler #'form-handlers/handle-form-reviewed}}]
     ["/api/events/:slug/capture" {:post {:handler #'communication-handlers/handle-capture}}]
     ["/api/replay/start-demo" {:post {:handler #'replay-handlers/handle-replay-start-demo}}]
     ["/api/events/:slug/replay/play" {:post {:handler #'replay-handlers/handle-replay-play}}]
     ["/api/events/:slug/replay/pause" {:post {:handler #'replay-handlers/handle-replay-pause}}]
     ["/api/events/:slug/replay/skip" {:post {:handler #'replay-handlers/handle-replay-skip}}]
     ["/api/events/:slug/airtable/set" {:post {:handler #'integration-handlers/handle-airtable-set}}]
     ["/api/events/:slug/airtable/remove" {:post {:handler #'integration-handlers/handle-airtable-remove}}]
     ["/api/events/:slug/slack/set" {:post {:handler #'integration-handlers/handle-slack-set}}]
     ["/api/events/:slug/slack/remove" {:post {:handler #'integration-handlers/handle-slack-remove}}]
     ["/api/events/:slug/slack/test" {:post {:handler #'integration-handlers/handle-slack-test}}]
     ["/api/events/:slug/notice/dismiss" {:post {:handler #'board-handlers/handle-notice-dismiss}}]
     ["/api/events/:slug/api-keys/create" {:post {:handler #'integration-handlers/handle-api-key-create}}]
     ["/api/events/:slug/api-keys/revoke" {:post {:handler #'integration-handlers/handle-api-key-revoke}}]
     ["/api/events/:slug/webhooks/add" {:post {:handler #'integration-handlers/handle-webhook-add}}]
     ["/api/events/:slug/webhooks/remove" {:post {:handler #'integration-handlers/handle-webhook-remove}}]
     ["/dev/reload-check" {:get {:handler #'dev-handlers/handle-reload-check}}]
     ["/dev/sse-state" {:get {:handler #'dev-handlers/handle-sse-state}}]]))

(defn- make-ring-handler []
  (let [router
        ;; /events/new deliberately overlaps /events/:slug. The mixed router prefers
        ;; static segments; :conflicts nil declares that intent (covered by server-test).
        (ring/router (make-routes) {:conflicts nil
                                    :data {:middleware [middleware/wrap-require-login
                                                        event-surface-authorization/wrap-declared-event-authorization
                                                        middleware/wrap-remember-working-event
                                                        public-api-handlers/wrap-token-authenticated-no-store
                                                        telemetry/wrap-route-template]}})]
    (ring/ring-handler router (web-not-found/fallback router))))

(defn create-app []
  (let [is-dev? (= "dev" (System/getenv "ENV"))
        handler (if is-dev?
                  (ring/reloading-ring-handler #'make-ring-handler)
                  (make-ring-handler))]
    (middleware/wrap-app handler is-dev?)))

(defn create-app-dev []
  (let [app (create-app)]
    (cond-> app
      ;; browser-reload and ring-devel in SEPARATE try blocks
      ;; so one missing dep doesn't break the other
      true (as-> handler
                 (try
                   (let [wrap-reload-script (requiring-resolve 'browser-reload.core/wrap-reload-script)]
                     (wrap-reload-script handler))
                   (catch Exception e
                     (log/warn :browser-reload-unavailable :msg (.getMessage e))
                     handler)))
      true (as-> handler
                 (try
                   (let [wrap-reload (requiring-resolve 'ring.middleware.reload/wrap-reload)]
                     (wrap-reload handler {:dirs ["src" "resources"]
                                           :reload-compile-errors? true}))
                   (catch Exception e
                     (log/warn :ring-reload-unavailable :msg (.getMessage e))
                     handler))))))

(defn start-server!
  ([] (start-server! (Integer/parseInt (or (System/getenv "PORT") "3001"))))
  ([port]
   (let [is-dev? (= "dev" (System/getenv "ENV"))
         ;; If a snapshot bucket is configured and this box has no local log
         ;; yet (a fresh container), pull it down BEFORE folding. Refuses to
         ;; overwrite an existing local log — see sinks/restore-from-snapshot!.
         _ (try (sinks/restore-from-snapshot!)
                (catch Exception e
                  (log/warn :gcs-restore-error :msg (.getMessage e))))
         ;; Replay the log — dev folds behind the warming page (INTENT: 168j)
         _ (dev-warmup/boot-load! is-dev? store/loaded? store/load!)
         _ (telemetry/start!)
         app     (if is-dev? (dev-warmup/wrap-warmup (create-app-dev)) (create-app))
         stop-fn (http/run-server app {:port port :thread 8})]
     (log/info :server-started :port port :dev is-dev?)
     (when is-dev?
       ;; Guarded like the middleware above it: ENV=dev without the :dev alias
       ;; is a legitimate way to run (dev behaviour, no reload tooling), and it
       ;; used to take the whole server down here while the middleware calls
       ;; degraded gracefully three lines earlier.
       (try
         (when-let [start-watcher (requiring-resolve 'browser-reload.core/start-file-watcher!)]
           (start-watcher ["src" "resources"] #{"clj" "css" "js"}))
         (catch Exception e
           (log/warn :file-watcher-unavailable :msg (.getMessage e)))))
     (reset! server stop-fn)
     stop-fn)))

(defn stop-server! []
  (telemetry/stop!)
  (when-let [stop-fn @server]
    (stop-fn)
    (when-let [stop-watcher (requiring-resolve 'browser-reload.core/stop-file-watcher!)]
      (stop-watcher))
    (reset! server nil)
    (log/info :server-stopped)))

(defn -main [& _args] (start-server!))
