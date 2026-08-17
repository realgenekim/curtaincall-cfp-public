(ns cfp-scheduler-killer.routes-additive
  "Routes delivered on main after the deployed staging router diverged.

   This is deliberately the non-overlapping set. The reconstruction server
   keeps staging's newer routes first and appends these completed capabilities."
  (:require
   [cfp-scheduler-killer.handlers.agent :as agent-handlers]
   [cfp-scheduler-killer.handlers.announce :as announce-handlers]
   [cfp-scheduler-killer.handlers.board :as board-handlers]
   [cfp-scheduler-killer.handlers.communications :as communication-handlers]
   [cfp-scheduler-killer.handlers.crm :as crm-handlers]
   [cfp-scheduler-killer.handlers.events :as event-handlers]
   [cfp-scheduler-killer.handlers.files :as file-handlers]
   [cfp-scheduler-killer.handlers.health :as health-handlers]
   [cfp-scheduler-killer.handlers.integrations :as integration-handlers]
   [cfp-scheduler-killer.handlers.manifesto :as manifesto-handlers]
   [cfp-scheduler-killer.handlers.portal :as portal-handlers]
   [cfp-scheduler-killer.handlers.public-cfp :as public-cfp-handlers]
   [cfp-scheduler-killer.handlers.public-widgets :as public-widget-handlers]
   [cfp-scheduler-killer.handlers.review-plan :as review-plan-handlers]
   [cfp-scheduler-killer.handlers.schedule :as schedule-handlers]
   [cfp-scheduler-killer.handlers.speaker-tasks :as speaker-task-handlers]
   [cfp-scheduler-killer.handlers.speakers :as speaker-handlers]
   [cfp-scheduler-killer.handlers.zoo :as zoo-handlers]
   [cfp-scheduler-killer.session-invites :as session-invites]))

(def routes
  [["/card.png" {:get {:handler #'public-widget-handlers/handle-homepage-card}}]
   ["/program/:slug" {:get {:handler #'public-widget-handlers/handle-program}}]
   ["/program/:slug/announce" {:get {:handler #'public-widget-handlers/handle-organizer-brag}}]
   ["/program/:slug/card.png" {:get {:handler #'public-widget-handlers/handle-event-card}}]
   ["/cfps" {:get {:handler #'public-widget-handlers/handle-cfps}}]
   ["/agenda/:slug/speakers/:person-id/announce"
    {:get {:handler #'public-widget-handlers/handle-announce}}]
   ["/agenda/:slug/speakers/:person-id/card.png"
    {:get {:handler #'public-widget-handlers/handle-speaker-card}}]
   ["/agenda/:slug/gallery"
    {:get {:handler #'public-widget-handlers/handle-public-gallery}}]
   ["/manifesto" {:get {:handler #'manifesto-handlers/handle-manifesto}}]
   ["/organizers/:slug" {:get {:handler #'event-handlers/handle-organizer-page}}]
   ["/welcome" {:get {:handler #'event-handlers/handle-welcome}}]
   ["/events/:slug/announce" {:get {:handler #'announce-handlers/handle-announce-page}}]
   ["/events/:slug/speakers/new"
    {:get {:handler #'announce-handlers/handle-create-speaker-page}}]
   ["/events/:slug/submissions/:submission-id/manage"
    {:get {:handler #'board-handlers/handle-manage-speaker}}]
   ["/admin/zoo/social-sharing" {:get {:handler #'zoo-handlers/handle-zoo-social-sharing}}]
   ["/admin/zoo/social-sharing/cards/:kind"
    {:get {:handler #'zoo-handlers/handle-zoo-preview-card}}]
   ["/ping" {:get {:handler #'health-handlers/handle-ping}}]
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

   ["/events/:slug/review" {:get {:handler #'board-handlers/handle-reviewer-queue}}]
   ["/events/:slug/reviewer-progress" {:get {:handler #'board-handlers/handle-reviewer-progress}}]
   ["/api/events/:slug/submissions/:submission-id/content"
    {:post {:handler #'board-handlers/handle-session-content}}]
   ["/api/events/:slug/submissions/:submission-id/history/:log-index/restore"
    {:post {:handler #'board-handlers/handle-session-content-restore}}]
   ["/api/submissions/:submission-id/mention"
    {:post {:handler #'board-handlers/handle-mention}}]
   ["/api/events/:slug/submissions/:submission-id/manage"
    {:post {:handler #'board-handlers/handle-manage-speaker-save}}]
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
   ["/api/events/:slug/reviewers/distribute/preview"
    {:post {:handler #'board-handlers/handle-reviewer-distribution-preview}}]
   ["/api/events/:slug/reviewers/distribute"
    {:post {:handler #'board-handlers/handle-reviewer-distribution-confirm}}]

   ["/api/events/:slug/review-plan/presenter-visibility"
    {:post {:handler #'review-plan-handlers/handle-presenter-visibility}}]

   ["/api/events/:slug/comms/approve-all"
    {:post {:handler #'communication-handlers/handle-email-approve-all}}]
   ["/api/events/:slug/comms/:email-id/approve"
    {:post {:handler #'communication-handlers/handle-email-approve}}]
   ["/api/events/:slug/comms/:email-id/discard"
    {:post {:handler #'communication-handlers/handle-email-discard}}]

   ["/events/:slug/deliverables" {:get {:handler #'speaker-task-handlers/handle-deliverables}}]
   ["/api/events/:slug/reminder-schedule"
    {:post {:handler #'speaker-task-handlers/handle-reminder-schedule}}]
   ["/api/events/:slug/deliverables/:submission-id/:task-key/chase"
    {:post {:handler #'speaker-task-handlers/handle-record-chase}}]
   ["/api/events/:slug/speaker-chases/draft"
    {:post {:handler #'speaker-task-handlers/handle-draft-chases}}]
   ["/api/events/:slug/speaker-chases/send"
    {:post {:handler #'speaker-task-handlers/handle-send-chases}}]

   ["/events/:slug/speakers" {:get {:handler #'speaker-handlers/handle-speakers}}]
   ["/events/:slug/speakers/filter"
    {:post {:handler #'speaker-handlers/handle-speakers-filter}}]
   ["/events/:slug/speakers/:person-id"
    {:get {:handler #'speaker-handlers/handle-speaker-detail}}]
   ["/events/:slug/submissions/:submission-id/speakers"
    {:post {:handler #'speaker-handlers/handle-assign-session-speaker}}]
   ["/events/:slug/submissions/:submission-id/speakers/:person-id/remove"
    {:post {:handler #'speaker-handlers/handle-unassign-session-speaker}}]
   ["/api/events/:slug/speakers" {:post {:handler #'speaker-handlers/handle-add-speaker}}]
   ["/api/events/:slug/speaker-fields"
    {:post {:handler #'speaker-handlers/handle-define-custom-field}}]
   ["/api/events/:slug/speakers/import/preview"
    {:post {:handler #'speaker-handlers/handle-import-preview}}]
   ["/api/events/:slug/speakers/import"
    {:post {:handler #'speaker-handlers/handle-import-speakers}}]
   ["/api/events/:slug/speakers/:person-id/status"
    {:post {:handler #'speaker-handlers/handle-speaker-status}}]
   ["/api/events/:slug/speakers/:person-id/portal-invite"
    {:post {:handler #'speaker-handlers/handle-portal-invite}}]
   ["/api/events/:slug/speakers/create"
    {:post {:handler #'announce-handlers/handle-create-speaker}}]
   ["/api/events/:slug/speakers/adopt"
    {:post {:handler #'announce-handlers/handle-adopt-speaker}}]
   ["/api/events/:slug/speakers/adopt-all"
    {:post {:handler #'announce-handlers/handle-adopt-all}}]

   ["/events/:slug/files" {:get {:handler #'file-handlers/handle-files}}]
   ["/events/:slug/files.zip" {:get {:handler #'file-handlers/handle-files-zip}}]
   ["/events/:slug/files/:file-id/download"
    {:get {:handler #'file-handlers/handle-organizer-download}}]
   ["/headshots/:file-id" {:get {:handler #'file-handlers/handle-headshot}}]
   ["/api/events/:slug/files/requests"
    {:post {:handler #'file-handlers/handle-request-file}}]
   ["/api/events/:slug/files/:file-id/comment"
    {:post {:handler #'file-handlers/handle-organizer-comment}}]
   ["/api/submissions/:submission-id/files/:task-key/upload"
    {:post {:handler #'file-handlers/handle-speaker-upload}}]
   ["/api/submissions/:submission-id/files/:file-id/comment"
    {:post {:handler #'file-handlers/handle-speaker-comment}}]
   ["/api/submissions/:submission-id/files/:file-id/download"
    {:get {:handler #'file-handlers/handle-speaker-download}}]
   ["/api/submissions/:submission-id/headshot"
    {:post {:handler #'file-handlers/handle-profile-headshot}}]

   ["/agenda/:slug/my" {:get {:handler #'public-widget-handlers/handle-my-schedule}}]
   ["/agenda/:slug/my.ics" {:post {:handler #'public-widget-handlers/handle-my-schedule-ics}}]
   ["/agenda/:slug/sessions/:submission-id/invite.ics"
    {:get {:handler #'session-invites/handle-session-invite}}]

   ["/events/:slug/mcp" {:post {:handler #'agent-handlers/handle-mcp}}]
   ["/api/cfp/:slug/draft/reset"
    {:post {:handler #'public-cfp-handlers/handle-cfp-draft-reset}}]
   ["/api/events/:slug/api-keys/copy"
    {:post {:handler #'integration-handlers/handle-api-key-copy}}]

   ["/api/events/:slug/speaker-custom-values"
    {:post {:handler #'portal-handlers/handle-portal-custom-values}}]

   ["/api/events/:slug/schedule/track-add"
    {:post {:handler #'schedule-handlers/handle-track-add}}]
   ["/api/events/:slug/schedule/track-rename"
    {:post {:handler #'schedule-handlers/handle-track-rename}}]
   ["/api/events/:slug/schedule/track-retire"
    {:post {:handler #'schedule-handlers/handle-track-retire}}]
   ["/api/events/:slug/schedule/suggest"
    {:post {:handler #'schedule-handlers/handle-schedule-suggest}}]])

(defn with-additive
  "Compose a legacy/core route vector under the extracted owners. A method/path
   has exactly one authority; additive handlers win during migration."
  [core-routes]
  (let [owned (set (for [[path methods] routes
                         method (keys methods)]
                     [path method]))]
    (into routes
          (keep (fn [[path methods]]
                  (let [unowned (into {}
                                      (remove (fn [[method _]]
                                                (contains? owned [path method])))
                                      methods)]
                    (when (seq unowned) [path unowned]))))
          core-routes)))
