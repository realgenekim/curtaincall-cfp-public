(ns cfp-scheduler-killer.folds
  "Pure event-log projection: facts in, derived state out. No I/O or backend."
  (:require
   [clojure.string :as str]
   [taoensso.timbre :as log])
  (:import
   (java.time Instant LocalDate)))

(def empty-state
  "Every collection is a map keyed by its natural id. Events are keyed by SLUG
   (the natural key everything user-facing uses); the rest by uuid string."
  {:events {}             ; slug   -> event map
   :committees {}         ; id     -> committee map
   :forms {}              ; id     -> form map
   :people {}             ; id     -> person map
   :memberships {}        ; id     -> membership map
   :submissions {}        ; id     -> submission map
   :submission-speaker-assignments {} ; [submission-id person-id] -> live assignment
   :ratings {}            ; [submission-id person-id] -> rating map (UPSERT: latest wins)
   :review-assignments {} ; [submission-id person-id] -> assignment map
   :review-recusals {}    ; [submission-id person-id] -> current conflict recusal
   :reviewer-nudges {}    ; id -> human-authored reviewer nudge record
   :review-rounds {}      ; id -> optional named review round with pool + scorecard
   :review-plans {}       ; event-id -> active round + blind-review settings
   :review-criteria {}    ; id -> event-scoped scorecard criterion
   :criterion-values {}   ; [submission-id person-id criterion-id] -> latest answer
   :tasks {}              ; [submission-id task-key] -> speaker task (UPSERT)
   :rooms {}              ; id -> room
   :slots {}              ; submission-id -> placement (UPSERT: one placement per talk)
   :blocks {}             ; id -> non-session block (Lunch, Keynote TBD…)
   :agenda-selections {}  ; [event-id anonymous-viewer-id] -> #{submission-id}
   :resource-pages {}     ; id -> organizer-authored public resource page
   :crm-notes {}          ; id -> event-scoped internal contact note
   :crm-tags {}           ; [event-id person-id tag] -> active contact tag
   :crm-segments {}       ; id -> organizer-owned saved contact filter
   :crm-outreach-drafts {} ; id -> human-reviewed outreach draft, never an auto-send
   :comments {}           ; id     -> comment map (accumulate)
   :sessions {}           ; id     -> active authenticated session
   :speaker-participations {} ; [event-id person-id] -> event-local speaker data
   :speaker-custom-fields {} ; [event-id field-id] -> organizer-defined field
   :log []
   :files {}})         ; every event, in order — the story, kept for the UI

(defn effective-submission-speakers
  "Overlay explicit organizer assignments without rewriting submitted speaker
   blocks in `:submissions`. This is a pure current projection shared by store
   accessors and the organizer roster."
  [state submission]
  (let [submission-id (:id submission)
        assignments (->> (:submission-speaker-assignments state)
                         (keep (fn [[[sid person-id] assignment]]
                                 (when (= submission-id sid)
                                   [person-id assignment])))
                         (into {}))
        base (->> (:speakers submission)
                  (remove (fn [speaker]
                            (false? (:assigned?
                                      (get assignments (:person-id speaker))))))
                  vec)
        base-person-ids (into #{} (keep :person-id) base)
        added (->> assignments
                   (keep (fn [[person-id assignment]]
                           (when (and (:assigned? assignment)
                                      (not (contains? base-person-ids person-id)))
                             (:speaker assignment))))
                   (sort-by (juxt #(or (:position %) Long/MAX_VALUE)
                                  #(str (:person-id %))))
                   vec)]
    (assoc submission :speakers (into base added))))

(defn <-iso-date
  "\"yyyy-MM-dd\" -> LocalDate. Tolerates an already-parsed LocalDate."
  [s]
  (cond
    (nil? s) nil
    (instance? LocalDate s) s
    (str/blank? (str s)) nil
    :else (try (LocalDate/parse (str s))
               (catch Exception _
                 (log/warn :bad-date-in-log :value s) nil))))

(defn <-iso-instant
  [s]
  (cond
    (nil? s) nil
    (instance? Instant s) s
    (str/blank? (str s)) nil
    :else (try (Instant/parse (str s))
               (catch Exception _
                 (log/warn :bad-instant-in-log :value s) nil))))

(defn event-by-id
  "Find an event map by its id (the log references events by id, humans by slug)."
  [state event-id]
  (some (fn [e] (when (= event-id (:id e)) e)) (vals (:events state))))

(defn- update-present
  "Like `update`, but ONLY when the key is already there.

   This is load-bearing. `event.updated` carries a PARTIAL map (just the fields
   that changed), and plain `update` on a missing key inserts nil — so merging a
   parsed partial silently blanked every date the edit didn't mention. Changing
   the CFP open date erased the event's start date, end date and close date.
   The projection was wrong; the LOG still had the truth, so fixing this
   function and re-folding restored the data with nothing to migrate."
  [m k f]
  (if (contains? m k) (update m k f) m))

(defn- parse-event-row
  "ISO strings -> domain time values, for a full record OR a partial change map."
  [p]
  (-> p
      (update-present :starts-on <-iso-date)
      (update-present :ends-on <-iso-date)
      (update-present :cfp-opens-at <-iso-instant)
      (update-present :cfp-closes-at <-iso-instant)
      (update-present :created-at <-iso-instant)))

(defmulti fold-event
  "Apply one event to the state. Unknown types are ignored (forward
   compatibility: an older build must survive a log written by a newer one)."
  (fn [_state event] (:type event)))

(defmethod fold-event :default [state event]
  (log/debug :unknown-event-type :type (:type event))
  state)

(defmethod fold-event "event.created" [state {:keys [payload]}]
  (assoc-in state [:events (:slug payload)] (parse-event-row payload)))

(defmethod fold-event "event.updated" [state {:keys [payload]}]
  (let [{:keys [slug changes]} payload]
    (if (get-in state [:events slug])
      (update-in state [:events slug] merge (parse-event-row changes))
      state)))

(defmethod fold-event "resource-page.saved" [state {:keys [payload]}]
  (assoc-in state [:resource-pages (get-in payload [:page :id])] (:page payload)))

(defmethod fold-event "speaker.reminder-schedule-configured"
  [state {:keys [payload]}]
  (if (get-in state [:events (:slug payload)])
    (assoc-in state [:events (:slug payload) :settings :speaker-reminder-schedule]
              (select-keys payload [:enabled? :days-before :at]))
    state))

;; Archiving is a FACT, never a deletion (Gene, 2026-08-10): the event and its
;; whole history stay in the log; listings just stop leading with it.
(defmethod fold-event "event.archived" [state {:keys [payload]}]
  (let [{:keys [slug archived-at]} payload]
    (if (get-in state [:events slug])
      (assoc-in state [:events slug :archived-at] (<-iso-instant archived-at))
      state)))

(defmethod fold-event "event.unarchived" [state {:keys [payload]}]
  (let [{:keys [slug]} payload]
    (if (get-in state [:events slug])
      (assoc-in state [:events slug :archived-at] nil)
      state)))

(defmethod fold-event "committee.created" [state {:keys [payload]}]
  (assoc-in state [:committees (:id payload)]
            (update-present payload :created-at <-iso-instant)))

(defmethod fold-event "form.installed" [state {:keys [payload]}]
  (assoc-in state [:forms (:id payload)]
            (update-present payload :created-at <-iso-instant)))

;; The form builder's ONE write. The payload carries the COMPLETE new field
;; vector rather than a per-key delta, for two reasons: payloads must stand
;; alone (there is no other table to join against later), and REORDER is a
;; statement about the whole vector — there is no honest delta for "these
;; twelve questions are now in this order". Re-folding the log therefore
;; reproduces the edited form exactly, which is the property the whole append-
;; only design exists to buy.
(defmethod fold-event "form.updated" [state {:keys [payload]}]
  (if (get-in state [:forms (:id payload)])
    (-> state
        (assoc-in [:forms (:id payload) :fields] (:fields payload))
        (assoc-in [:forms (:id payload) :edited-at] (<-iso-instant (:at payload))))
    state))

;; "The organizer has read this form" — a deliberate second act, like informing
;; a speaker. The dashboard checklist ticks on this fact, never on a page view.
(defmethod fold-event "form.reviewed" [state {:keys [payload]}]
  (if (get-in state [:forms (:id payload)])
    (assoc-in state [:forms (:id payload) :reviewed-at] (<-iso-instant (:at payload)))
    state))

(defmethod fold-event "person.created" [state {:keys [payload]}]
  (assoc-in state [:people (:id payload)]
            (update-present payload :created-at <-iso-instant)))

(defmethod fold-event "person.default-event-set" [state {:keys [payload]}]
  (if (get-in state [:people (:person-id payload)])
    (assoc-in state [:people (:person-id payload) :default-event-id]
              (:event-id payload))
    state))

(defmethod fold-event "auth.session-started" [state {:keys [payload]}]
  (assoc-in state [:sessions (:id payload)]
            (update-present payload :created-at <-iso-instant)))

(defmethod fold-event "auth.session-ended" [state {:keys [payload]}]
  (update state :sessions dissoc (:session-id payload)))

(defmethod fold-event "member.added" [state {:keys [payload]}]
  ;; The role vocabulary changed on 2026-08-09 ("member" -> "reviewer") and the
  ;; log is append-only, so every event written before then still says "member".
  ;; It is translated HERE, on the way into the projection, which is the whole
  ;; trick: the historical events stay untouched and correct, and everything
  ;; downstream sees one vocabulary. (committees/normalize-role is the same rule;
  ;; it can't be called from here — committees requires store, not the reverse.)
  (assoc-in state [:memberships (:id payload)]
            (-> payload
                (update-present :created-at <-iso-instant)
                (update :role #(if (= "member" %) "reviewer" %)))))

(defmethod fold-event "member.removed" [state {:keys [payload]}]
  (update state :memberships dissoc (:id payload)))

(defmethod fold-event "committee.member-scoped" [state {:keys [payload]}]
  (if-let [membership-id
           (some (fn [[membership-id membership]]
                   (when (and (= (:committee-id payload) (:committee-id membership))
                              (= (:person-id payload) (:person-id membership)))
                     membership-id))
                 (:memberships state))]
    (assoc-in state [:memberships membership-id :scope] (:scope payload))
    state))

(defmethod fold-event "submission.created" [state {:keys [payload]}]
  (assoc-in state [:submissions (:id payload)]
            (update-present payload :created-at <-iso-instant)))

(defmethod fold-event "submission.speaker-assigned" [state {:keys [payload]}]
  (assoc-in state
            [:submission-speaker-assignments
             [(:submission-id payload) (:person-id payload)]]
            (assoc payload :assigned? true)))

(defmethod fold-event "submission.speaker-unassigned" [state {:keys [payload]}]
  (assoc-in state
            [:submission-speaker-assignments
             [(:submission-id payload) (:person-id payload)]]
            (assoc payload :assigned? false)))

;; A rating is UPSERT-by-(submission × person): the projection keeps only the
;; latest opinion, because a reviewer has exactly one current view of a talk.
;; The LOG keeps every version — changing your mind from 2 to 4 after reading
;; the comments is exactly the history worth having.
(defmethod fold-event "rating.set" [state {:keys [payload]}]
  (assoc-in state [:ratings [(:submission-id payload) (:person-id payload)]]
            (update-present payload :at <-iso-instant)))

;; Comments accumulate — each one is its own utterance, keyed by its own id.
(defmethod fold-event "comment.added" [state {:keys [payload]}]
  (assoc-in state [:comments (:id payload)]
            (update-present payload :at <-iso-instant)))

(defmethod fold-event "submission.status-changed" [state {:keys [payload]}]
  (if (get-in state [:submissions (:submission-id payload)])
    (assoc-in state [:submissions (:submission-id payload) :status] (:to payload))
    state))

(defmethod fold-event "submission.content-status-changed" [state {:keys [payload]}]
  (if (get-in state [:submissions (:submission-id payload)])
    (assoc-in state [:submissions (:submission-id payload) :content-status] (:to payload))
    state))

(defmethod fold-event "room.added" [state {:keys [payload]}]
  (assoc-in state [:rooms (:id payload)] (update-present payload :at <-iso-instant)))

(defmethod fold-event "room.renamed" [state {:keys [payload]}]
  (if (get-in state [:rooms (:id payload)])
    (assoc-in state [:rooms (:id payload) :name] (:name payload))
    state))

(defmethod fold-event "room.removed" [state {:keys [payload]}]
  ;; Removing a room does NOT unplace its sessions — they fall back to
  ;; "unroomed", which is a first-class state here. Silently dropping a
  ;; placement because a room was renamed out of existence would lose work.
  (update state :rooms dissoc (:id payload)))

;; A placement is UPSERT-by-submission: a talk is in exactly one place at a
;; time, and moving it is an edit of that one fact. The log keeps every move,
;; which is what makes the .ics SEQUENCE bump correct.
(defmethod fold-event "slot.assigned" [state {:keys [payload]}]
  (assoc-in state [:slots (:submission-id payload)]
            (update-present payload :at <-iso-instant)))

(defmethod fold-event "slot.cleared" [state {:keys [payload]}]
  (update state :slots dissoc (:submission-id payload)))

(defmethod fold-event "block.added" [state {:keys [payload]}]
  (assoc-in state [:blocks (:id payload)] (update-present payload :at <-iso-instant)))

(defmethod fold-event "block.removed" [state {:keys [payload]}]
  (update state :blocks dissoc (:id payload)))

;; The lock lives on the event's settings so it travels with the event and
;; shows up in every projection that reads settings.
(defmethod fold-event "schedule.locked" [state {:keys [payload]}]
  (if-let [slug (:slug (event-by-id state (:event-id payload)))]
    (assoc-in state [:events slug :settings :schedule-lock]
              {:locked? true
               :version-label (:version-label payload)
               :at (:at payload)})
    state))

(defmethod fold-event "schedule.unlocked" [state {:keys [payload]}]
  (if-let [slug (:slug (event-by-id state (:event-id payload)))]
    (assoc-in state [:events slug :settings :schedule-lock]
              {:locked? false
               :version-label (:version-label payload)
               :at (:at payload)})
    state))

(defmethod fold-event "agenda.published" [state {:keys [payload]}]
  (if-let [slug (:slug (event-by-id state (:event-id payload)))]
    (assoc-in state [:events slug :settings :agenda-publication]
              {:published-at (<-iso-instant (:at payload))})
    state))

;; Comms events are a LOG, not a projection — nothing folds into state. The
;; send history is read straight from the log, which is the honest shape: "we
;; attempted this, at this time, with this result" is a sequence of facts, not
;; a current value.
(defmethod fold-event "comms.rendered" [state _] state)
(defmethod fold-event "comms.sent" [state _] state)
(defmethod fold-event "comms.failed" [state _] state)
(doseq [event-type ["email.queued" "email.approved"
                    "email.failed" "email.discarded"]]
  (defmethod fold-event event-type [state _] state))

(defmethod fold-event "replay.marked" [state {:keys [payload]}]
  ;; Marks an event as a REPLAY TARGET. The simulator refuses to play into
  ;; anything without this, so a demo can never pollute a real conference.
  (if-let [slug (:slug (event-by-id state (:event-id payload)))]
    (assoc-in state [:events slug :settings :replay?] true)
    state))

(defmethod fold-event "sink.registered" [state {:keys [payload]}]
  ;; Webhooks live in the event's own settings, so they travel with the event
  ;; and are visible in the log like everything else.
  (if-let [slug (:slug (event-by-id state (:event-id payload)))]
    (assoc-in state [:events slug :settings :webhooks (:id payload)]
              (select-keys payload [:id :url :types :created-at]))
    state))

(defmethod fold-event "sink.removed" [state {:keys [payload]}]
  (if-let [slug (:slug (event-by-id state (:event-id payload)))]
    (update-in state [:events slug :settings :webhooks] dissoc (:id payload))
    state))

;; API keys live in the event's settings next to the webhooks, for the same
;; reason: they travel with the conference and every grant shows up in the log.
;; The key MATERIAL is in the payload — this is a single-tenant organizer tool,
;; and a key you cannot read back is a key the organizer will re-issue forever.
(defmethod fold-event "api-key.created" [state {:keys [payload]}]
  (if-let [slug (:slug (event-by-id state (:event-id payload)))]
    (assoc-in state [:events slug :settings :api-keys (:id payload)]
              (select-keys payload [:id :label :key :key-hash :prefix :scope :person-id
                                    :created-at :created-by]))
    state))

;; Revoking DROPS the key from the projection: the question a request asks is
;; "is this key live", and a live/dead flag is a second thing to get wrong. The
;; log still says it existed and when it was revoked, which is the record that
;; matters after a leak.
(defmethod fold-event "api-key.revoked" [state {:keys [payload]}]
  (if-let [slug (:slug (event-by-id state (:event-id payload)))]
    (update-in state [:events slug :settings :api-keys] dissoc (:id payload))
    state))

(defmethod fold-event "person.profile-updated" [state {:keys [payload]}]
  ;; A profile is the person's OWN description of themselves, so it merges the
  ;; keys they sent and leaves the rest alone — never a wholesale replace.
  (if (get-in state [:people (:person-id payload)])
    (update-in state [:people (:person-id payload) :profile] merge (:changes payload))
    state))

(defmethod fold-event "speaker.added-to-event" [state {:keys [payload]}]
  (assoc-in state
            [:speaker-participations [(:event-id payload) (:person-id payload)]]
            (update-present payload :added-at <-iso-instant)))

(defmethod fold-event "speaker.status-changed" [state {:keys [payload]}]
  (update-in state
             [:speaker-participations [(:event-id payload) (:person-id payload)]]
             merge
             (-> (select-keys payload [:event-id :person-id :status])
                 (assoc :status-changed-at (<-iso-instant (:changed-at payload))))))

(defmethod fold-event "speaker.details-updated" [state {:keys [payload]}]
  (update-in state
             [:speaker-participations [(:event-id payload) (:person-id payload)]]
             merge
             (assoc (:changes payload)
                    :event-id (:event-id payload)
                    :person-id (:person-id payload)
                    :details-changed-at (<-iso-instant (:changed-at payload)))))

(defmethod fold-event "speaker.custom-field-defined" [state {:keys [payload]}]
  (let [{:keys [event-id field]} payload]
    (assoc-in state [:speaker-custom-fields [event-id (:id field)]]
              (update-present field :created-at <-iso-instant))))

(defmethod fold-event "speaker.custom-values-updated" [state {:keys [payload]}]
  (let [{:keys [event-id person-id changes changed-at]} payload]
    (update-in state [:speaker-participations [event-id person-id]]
               (fn [participation]
                 (-> (merge {:event-id event-id :person-id person-id}
                            participation)
                     (update :custom-values merge changes)
                     (assoc :custom-values-changed-at (<-iso-instant changed-at)))))))

(defmethod fold-event "submission.answers-updated" [state {:keys [payload]}]
  ;; Speakers may edit after acceptance (swyx: no lock). The form SNAPSHOT is
  ;; deliberately NOT touched — the questions they answered are history; only
  ;; the answers move.
  (if (get-in state [:submissions (:submission-id payload)])
    (update-in state [:submissions (:submission-id payload) :answers]
               merge (:changes payload))
    state))

(defmethod fold-event "submission.notified" [state {:keys [payload]}]
  ;; The Inform gate: until this exists, a speaker sees "Under review" no matter
  ;; what the committee decided (Sessionize's own pattern, adopted with pride).
  (if (get-in state [:submissions (:submission-id payload)])
    (-> state
        (assoc-in [:submissions (:submission-id payload) :notified-at]
                  (<-iso-instant (:at payload)))
        (assoc-in [:submissions (:submission-id payload) :notified-status]
                  (:status-at-notify payload)))
    state))

(defmethod fold-event "task.installed" [state {:keys [payload]}]
  (assoc-in state [:tasks [(:submission-id payload) (:key payload)]]
            (-> payload
                (update-present :at <-iso-instant)
                (update-present :due-on <-iso-date))))

(defmethod fold-event "task.completed" [state {:keys [payload]}]
  ;; UPSERT onto the installed task — a speaker who pastes a new slides URL is
  ;; correcting the same task, not creating a second one.
  (let [k [(:submission-id payload) (:key payload)]]
    (if (get-in state [:tasks k])
      (update-in state [:tasks k] merge
                 {:value (:value payload)
                  :completed-at (<-iso-instant (:at payload))})
      state)))

(defn- fold-task-chase [state payload]
  (let [k [(:submission-id payload) (:key payload)]]
    (if (and (get-in state [:tasks k])
             (not (str/blank? (str (:chase-id payload))))
             (not-any? #(= (:chase-id payload) (:chase-id %))
                       (get-in state [:tasks k :chases])))
      (update-in state [:tasks k]
                 (fn [task]
                   (let [chase (update-present
                                 (select-keys payload [:chase-id :note :actor :at
                                                       :medium :subject :body
                                                       :delivery-mode])
                                 :at
                                 <-iso-instant)]
                     (-> task
                         (update :chases (fnil conj []) chase)
                         (assoc :last-chased-at (:at chase)
                                :chase-count (inc (or (:chase-count task) 0)))))))
      state)))

(defmethod fold-event "task.chase-recorded" [state {:keys [payload]}]
  (fold-task-chase state payload))

(defmethod fold-event "email.sent" [state {:keys [payload actor]}]
  ;; The chase cadence is delivery truth, not scheduling truth. Only this
  ;; provider-confirmed outbox fact advances an emailed chase; queued, approved,
  ;; failed, and discarded facts deliberately remain projection no-ops above.
  (if (and (= "speaker-chase" (:kind payload))
           (not (str/blank? (str (:chase-id payload)))))
    (fold-task-chase
      state
      {:submission-id (:submission-id payload)
       :key (:task-key payload)
       :chase-id (:chase-id payload)
       :note (str "Email sent to " (:to payload))
       :actor actor
       :at (:at payload)
       :medium "email"
       :subject (:subject payload)
       :body (:body payload)
       :delivery-mode "sent"})
    state))

(defmethod fold-event "file.version-added" [state {:keys [payload]}]
  (let [{:keys [file-id version]} payload
        parsed-version (update-present version :uploaded-at <-iso-instant)]
    (update-in state [:files file-id]
               (fn [file]
                 (-> (or file
                         (assoc (select-keys payload
                                             [:event-id :submission-id :task-key
                                              :person-id :kind])
                                :id file-id :versions [] :comments []))
                     (update :versions conj parsed-version)
                     (assoc :latest-version-id (:id parsed-version)))))))

(defmethod fold-event "file.comment-added" [state {:keys [payload]}]
  (let [file-id (:file-id payload)
        comment (update-present (:comment payload) :at <-iso-instant)]
    (if (get-in state [:files file-id])
      (update-in state [:files file-id :comments] (fnil conj []) comment)
      state)))

(defmethod fold-event "reviewer.assigned" [state {:keys [payload]}]
  (assoc-in state
            [:review-assignments [(:submission-id payload) (:person-id payload)]]
            (update-present payload :assigned-at <-iso-instant)))

(defmethod fold-event "reviewer.unassigned" [state {:keys [payload]}]
  (update state :review-assignments
          dissoc [(:submission-id payload) (:person-id payload)]))

(defmethod fold-event "reviewer.recused" [state {:keys [payload]}]
  (assoc-in state
            [:review-recusals [(:submission-id payload) (:person-id payload)]]
            (update-present payload :recused-at <-iso-instant)))

(defmethod fold-event "reviewer.unrecused" [state {:keys [payload]}]
  (update state :review-recusals
          dissoc [(:submission-id payload) (:person-id payload)]))

(defmethod fold-event "reviewer.nudge-recorded" [state {:keys [payload]}]
  (assoc-in state [:reviewer-nudges (:id payload)]
            (update-present payload :recorded-at <-iso-instant)))

(defmethod fold-event "review-round.created" [state {:keys [payload]}]
  (let [round (update-present (:round payload) :created-at <-iso-instant)]
    (assoc-in state [:review-rounds (:id round)] round)))

(defmethod fold-event "review-round.updated" [state {:keys [payload at actor]}]
  (update-in state [:review-rounds (:round-id payload)]
             merge
             (assoc (dissoc payload :round-id)
                    :updated-at at
                    :updated-by actor)))

(defmethod fold-event "review-round.pool-set" [state {:keys [payload]}]
  (assoc-in state [:review-rounds (:round-id payload) :reviewer-ids]
            (:reviewer-ids payload)))

(defmethod fold-event "review-round.scorecard-set" [state {:keys [payload]}]
  (assoc-in state [:review-rounds (:round-id payload) :criterion-ids]
            (:criterion-ids payload)))

(defmethod fold-event "review-round.activated" [state {:keys [event-id payload]}]
  (assoc-in state [:review-plans event-id :active-round-id] (:round-id payload)))

(defmethod fold-event "review-round.advanced" [state {:keys [event-id payload]}]
  (assoc-in state [:review-plans event-id :active-round-id] (:round-id payload)))

(defmethod fold-event "review-round.retired" [state {:keys [payload at actor]}]
  (update-in state [:review-rounds (:round-id payload)]
             assoc
             :retired-at at
             :retired-by actor))

(defn- apply-presenter-visibility-policy
  [state event-id mode version at actor]
  (-> state
      (assoc-in [:review-plans event-id :blind?] (not= "visible" mode))
      (assoc-in [:review-plans event-id :presenter-visibility]
                (cond-> {:mode mode :version version}
                  at (assoc :changed-at at)
                  actor (assoc :changed-by actor)))))

(defmethod fold-event "review.blind-mode-set"
  [state {:keys [event-id payload at actor]}]
  (apply-presenter-visibility-policy
    state
    event-id
    (if (:blind? payload) "hidden" "visible")
    (inc (get-in state [:review-plans event-id :presenter-visibility :version] 0))
    at
    actor))

(defmethod fold-event "review.presenter-visibility-set"
  [state {:keys [event-id payload at actor]}]
  (apply-presenter-visibility-policy
    state
    event-id
    (:mode payload)
    (or (:version payload)
        (inc (get-in state [:review-plans event-id :presenter-visibility :version] 0)))
    at
    actor))

(defmethod fold-event "scorecard.criterion-added" [state {:keys [payload]}]
  (assoc-in state [:review-criteria (:id payload)]
            (update-present payload :created-at <-iso-instant)))

(defmethod fold-event "scorecard.criterion-updated" [state {:keys [payload]}]
  (if (get-in state [:review-criteria (:id payload)])
    (assoc-in state [:review-criteria (:id payload)]
              (update-present payload :updated-at <-iso-instant))
    state))

(defmethod fold-event "scorecard.criterion-retired" [state {:keys [payload]}]
  (if (get-in state [:review-criteria (:id payload)])
    (assoc-in state [:review-criteria (:id payload)]
              (update-present payload :retired-at <-iso-instant))
    state))

(defmethod fold-event "scorecard.value-set" [state {:keys [payload]}]
  (assoc-in state
            [:criterion-values
             [(:submission-id payload) (:person-id payload) (:criterion-id payload)]]
            (update-present payload :at <-iso-instant)))

(defmethod fold-event "submission.priority-toggled" [state {:keys [payload]}]
  (if (get-in state [:submissions (:submission-id payload)])
    (assoc-in state [:submissions (:submission-id payload) :priority] (boolean (:priority payload)))
    state))

(defmethod fold-event "agenda.session-starred"
  [state {:keys [event-id payload]}]
  (update-in state [:agenda-selections [event-id (:viewer-id payload)]]
             (fnil conj #{}) (:submission-id payload)))

(defmethod fold-event "agenda.session-unstarred"
  [state {:keys [event-id payload]}]
  (update-in state [:agenda-selections [event-id (:viewer-id payload)]]
             (fnil disj #{}) (:submission-id payload)))

(defmethod fold-event "crm.note-added" [state {:keys [payload]}]
  (assoc-in state [:crm-notes (:id payload)]
            (update-present payload :at <-iso-instant)))

(defmethod fold-event "crm.tag-added" [state {:keys [payload]}]
  (assoc-in state [:crm-tags [(:event-id payload) (:person-id payload) (:tag payload)]]
            (update-present payload :at <-iso-instant)))

(defmethod fold-event "crm.tag-removed" [state {:keys [payload]}]
  (update state :crm-tags dissoc
          [(:event-id payload) (:person-id payload) (:tag payload)]))

(defmethod fold-event "crm.segment-saved" [state {:keys [payload]}]
  (assoc-in state [:crm-segments (:id payload)]
            (update-present payload :created-at <-iso-instant)))

(defmethod fold-event "crm.segment-removed" [state {:keys [payload]}]
  (update state :crm-segments dissoc (:segment-id payload)))

(defmethod fold-event "crm.outreach-drafted" [state {:keys [payload]}]
  (assoc-in state [:crm-outreach-drafts (:id payload)]
            (update-present payload :created-at <-iso-instant)))

;; Additive product facts live here—not in the storage engine. Keeping this
;; boundary one-way makes every backend replay the same domain history.
(defmethod fold-event "committee.scope-set" [state {:keys [payload]}]
  (if (get-in state [:committees (:id payload)])
    (assoc-in state [:committees (:id payload) :scope] (:scope payload))
    state))

(defmethod fold-event "submission.mention" [state {:keys [payload]}]
  (assoc-in state [:mentions (:id payload)]
            (update-present payload :at <-iso-instant)))

(defmethod fold-event "event.hero-set" [state {:keys [payload]}]
  (if-let [slug (:slug (event-by-id state (:event-id payload)))]
    (assoc-in state [:events slug :settings :hero-image-url] (:url payload))
    state))

(defmethod fold-event "event.email-notifications-set" [state {:keys [payload]}]
  (if-let [slug (:slug (event-by-id state (:event-id payload)))]
    (assoc-in state [:events slug :settings :email-notifications?]
              (boolean (:enabled? payload)))
    state))

(defmethod fold-event "event.day-hours-set" [state {:keys [payload]}]
  (if-let [slug (:slug (event-by-id state (:event-id payload)))]
    (-> state
        (assoc-in [:events slug :settings :day-start] (:day-start payload))
        (assoc-in [:events slug :settings :day-end] (:day-end payload)))
    state))

(defmethod fold-event "event.unlisted-set" [state {:keys [payload]}]
  (if-let [slug (:slug (event-by-id state (:event-id payload)))]
    (assoc-in state [:events slug :settings :unlisted?]
              (boolean (:unlisted? payload)))
    state))

(defmethod fold-event "event.blind-review-set" [state {:keys [payload]}]
  (if-let [slug (:slug (event-by-id state (:event-id payload)))]
    (-> state
        (assoc-in [:events slug :settings :hide-presenter-info]
                  (boolean (:hide-presenter-info payload)))
        (assoc-in [:events slug :settings :reveal-after-vote]
                  (boolean (:reveal-after-vote payload))))
    state))

(defmethod fold-event "event.speaker-unannounced" [state {:keys [payload]}]
  (if-let [slug (:slug (event-by-id state (:event-id payload)))]
    (update-in state [:events slug :settings :announced-speakers]
               (fn [xs] (vec (remove #(= (:name %) (:name payload)) xs))))
    state))

(defmethod fold-event "event.speaker-announced" [state {:keys [payload]}]
  (if-let [slug (:slug (event-by-id state (:event-id payload)))]
    (update-in state [:events slug :settings :announced-speakers]
               (fnil (fn [v]
                       (conj (vec (remove #(= (:name %) (:name payload)) v))
                             (select-keys payload [:id :name :org :title :headshot-url])))
                     []))
    state))

(defmethod fold-event "event.announced-speaker-adopted" [state {:keys [payload]}]
  (if-let [slug (:slug (event-by-id state (:event-id payload)))]
    (let [norm-name #(-> (or % "") str/trim str/lower-case
                         (str/replace #"\s+" " "))
          needle (norm-name (:name payload))
          entries (get-in state [:events slug :settings :announced-speakers])]
      (if (some #(= needle (norm-name (:name %))) entries)
        (assoc-in state [:events slug :settings :announced-speakers]
                  (mapv #(if (= needle (norm-name (:name %)))
                           (assoc % :person-id (:person-id payload))
                           %)
                        entries))
        state))
    state))

(defmethod fold-event "event.announced-speaker-added" [state {:keys [payload]}]
  (if-let [slug (:slug (event-by-id state (:event-id payload)))]
    (update-in state [:events slug :settings :announced-speakers]
               (fnil conj []) (:entry payload))
    state))

(defmethod fold-event "event.program-speaker-updated" [state {:keys [payload]}]
  (let [{:keys [event-id person-id entry profile]} payload]
    (if (and (:slug (event-by-id state event-id))
             (get-in state [:people person-id]))
      (let [slug (:slug (event-by-id state event-id))]
        (-> state
            (update-in [:events slug :settings :announced-speakers]
                       (fn [speakers]
                         (mapv (fn [speaker]
                                 (if (= (str person-id) (str (:person-id speaker)))
                                   (merge speaker entry)
                                   speaker))
                               speakers)))
            (assoc-in [:people person-id :name] (:name entry))
            (update-in [:people person-id :profile] merge profile)))
      state)))

(defmethod fold-event "person.slug-set" [state {:keys [payload]}]
  (if (get-in state [:people (:person-id payload)])
    (assoc-in state [:people (:person-id payload) :slug] (:slug payload))
    state))
