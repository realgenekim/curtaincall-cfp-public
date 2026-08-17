(ns cfp-scheduler-killer.handlers.board
  "Web server with http-kit, reitit routing, and dev auto-reload.

   Handler convention (CLAUDE.md): every handler is a named `defn handle-*`
   referenced as `#'var` in the route table, so REPL redefinition takes effect
   without a restart."
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.domain.files :as file-decisions]
   [cfp-scheduler-killer.domain.review-plan :as domain-review-plan]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.exports :as exports]
   [cfp-scheduler-killer.files :as files]
   [cfp-scheduler-killer.inform :as inform]
   [cfp-scheduler-killer.notices :as notices]
   [cfp-scheduler-killer.portal :as portal]
   [cfp-scheduler-killer.review-assignments :as review-assignments]
   [cfp-scheduler-killer.review-authorization :as review-authorization]
   [cfp-scheduler-killer.review-plan :as review-plan]
   [cfp-scheduler-killer.review-policy :as review-policy]
   [cfp-scheduler-killer.review-updates :as review-updates]
   [cfp-scheduler-killer.review-work :as review-work]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.sse :as sse]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submission-content :as submission-content]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.views.review :as view-review]
   [cfp-scheduler-killer.views.review-assignment :as view-review-assignment]
   [cfp-scheduler-killer.views.reviewer-progress :as view-reviewer-progress]
   [cfp-scheduler-killer.views.reviewer-queue :as view-reviewer-queue]
   [cfp-scheduler-killer.voting-policy :as voting-policy]
   [cfp-scheduler-killer.web.datastar :as datastar]
   [cfp-scheduler-killer.web.event :as web-event]
   [cfp-scheduler-killer.web.http :as web-http]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [hiccup2.core :as h]
   [taoensso.timbre :as log])
  (:gen-class))

(defn- absorb-json-params
  "postJSON callers arrive as application/json — merge the body into :params
   so handlers read stars/body/status identically for form and fetch. Done in
   the HANDLER (not with-submission) because the handler fns close over THIS
   req; a rebind inside the wrapper never reaches them (the closure-capture
   lesson, 2026-08-10)."
  [req]
  (if (some-> (get-in req [:headers "content-type"])
              (str/includes? "application/json"))
    (update req :params merge
            (try (json/read (io/reader (:body req)) :key-fn keyword)
                 (catch Exception _ {})))
    req))

(defn board-state
  "Everything the board page needs, from the query params."
  ;; INTENT: REV-BOARD-001
  ([req event]
   (let [person (auth/current-person req)
         current-rows (reviews/enriched-for-event (:id event))]
     (board-state req event
                  (voting-policy/visibility-context
                    (review-plan/visibility-policy-event event) current-rows person)
                  current-rows)))
  ([req event visibility-context]
   (board-state req event visibility-context
                (reviews/enriched-for-event (:id event))))
  ([req event visibility-context all]
   (let [params (:params req)
         target (review-policy/coverage-target-for (:id event))
         sort-key (or (web-http/not-blank (:sort params)) reviews/default-sort)
         queue-sort-keys (into #{} (map :key) reviews/sort-presets)
         q (:q params)
         status (:status params)
         track (:track params)
         open-submission-id (web-http/clean-id (:open params))
         person (:reviewer-person visibility-context)
         role (some-> person :id (->> (committees/role-on-event (:id event))))
         chair? (= "chair" (some-> role name str/lower-case))
         visibility-context (cond-> visibility-context
                              chair? (assoc :hide-presenter-info? false
                                            :reveal-after-vote? false))
         historical-projection (->> (review-plan/project-submissions
                                      (:id event) (:id person) all)
                                    (filter reviews/board-visible?)
                                    vec)
         ;; A historical fold may contain the policy that was in force then.
         ;; Privacy is exogenous: today's reveal rights govern every rendering
         ;; of yesterday's data, including full pages and SSE fragments.
         visible-rows (mapv (fn [row]
                              (if (voting-policy/presenter-visible-in-context?
                                    visibility-context row)
                                row
                                (domain-review-plan/blind-submission row)))
                            historical-projection)
         hide? (:hide-presenter-info? visibility-context)
         my-tracks (when person
                     (committees/tracks-for-person-on-event (:id event) (:id person)))
         show-all? (boolean (web-http/not-blank (:all params)))
         default-tracks (when (and my-tracks (not show-all?) (str/blank? track))
                          my-tracks)
         scope-rows (reviews/filter-board visible-rows {:tracks default-tracks :hide? hide?})
         filtered (reviews/filter-board
                    scope-rows
                    {:q q :status status :track track :hide? hide?})
         sorted (reviews/sort-board filtered sort-key target hide?)
         filtered-total (count sorted)]
     {:rows sorted
      :total (count historical-projection)
      :filtered-total filtered-total
      :sparkline-rows all
      :coverage (reviews/coverage-for-rows scope-rows target)
      :needs-coverage (count (filter #(< (or (:n %) 0) target) scope-rows))
      :sort-key sort-key
      :active-work-queue (when (contains? queue-sort-keys sort-key) sort-key)
      :q q
      :status status
      :track track
      :open-submission-id open-submission-id
      :scoped-tracks default-tracks
      :my-tracks my-tracks
      :show-all? show-all?
      :track-counts (reviews/track-counts historical-projection)
      :status-counts (reviews/status-counts historical-projection)
      :sort-presets reviews/sort-presets
      :uncommunicated (count (inform/pending-decisions (:id event)))
      :chair? chair?
      :review-plan {:presenter-visibility
                    (review-plan/presenter-visibility-policy (:id event))
                    :presenter-visibility-definition
                    domain-review-plan/presenter-visibility-policy-definition}
      :reviewer-progress (when chair?
                           (review-work/progress-for-event (:id event) all))
      :my-review-progress (when (and person (not chair?))
                            (review-assignments/progress-for-reviewer
                              (:id event) (:id person)))
      :notice (or (notices/notice-for (:id event) (:id person))
                  (view-review/decision-notice event
                                               (:decision-result params)
                                               (:decision-status params)))
      :mentions-to-me (when-let [pid (:id person)]
                        (reviews/mentions-shelf (:id event) pid))
      :person person
      :visibility-context (assoc visibility-context :coverage-target target)})))

(defn- chair-on-event?
  "Server-side half of the chair gate (Gene, 2026-08-09: reviewers rate and
   argue; chairs decide). The view hides the control; this refuses the write."
  [event person]
  (boolean
    (when person
      (let [committee (first (events/committees-for-event (:id event)))]
        (some #(and (= (:person-id %) (:id person)) (= "chair" (:role %)))
              (when committee
                (committees/members-for-committee (:id committee))))))))

(defn- detail-page-response
  "Render one canonical submission detail page at any response status.

   Keeping the projection and its authorization-derived controls here means
   GET, validation refusals, and successful POST redirects cannot drift into
   different versions of the page."
  ([req event row status]
   (detail-page-response req event row status {}))
  ([req event row status extra]
   (let [person (auth/current-person req)
         chair? (chair-on-event? event person)
         projected (review-plan/project-submission
                     (:id event) (:id person) (reviews/enrich row))
         opts (merge
                {:person person
                 :chair? chair?
                 :coverage-target (review-policy/coverage-target-for (:id event))
                 :recusal (review-work/recusal-for (:id row) (:id person))
                 :notice (or (notices/notice-for (:id event) (:id person))
                             (view-review/decision-notice
                               event
                               (get-in req [:params :decision-result])
                               (get-in req [:params :decision-status])))
                 :blind? (true? (:anonymous? (first (:speakers projected))))}
                (when chair?
                  {:content-history
                   (submission-content/revision-history (:id row))})
                extra)]
     (web-http/html-response
       status
       (view-review/submission-detail-page event projected opts)))))

(defn handle-submission-detail [req]
  (let [slug (get-in req [:path-params :slug])
        sub-id (web-http/clean-id (get-in req [:path-params :submission-id]))
        event (events/event-by-slug slug)
        row (when sub-id (store/submission-by-id sub-id))]
    (if (and event row (= (:id event) (:event-id row)))
      (detail-page-response req (review-plan/visibility-policy-event event) row 200)
      (web-event/not-found-page slug))))

(defn- organizer-submission
  [req]
  (let [slug (get-in req [:path-params :slug])
        submission-id (web-http/clean-id
                        (get-in req [:path-params :submission-id]))
        event (events/event-by-slug slug)
        submission (when submission-id
                     (store/submission-by-id submission-id))
        person (auth/current-person req)]
    (when (and event submission
               (= (:id event) (:event-id submission)))
      {:event event :submission submission :person person})))

(defn- content-result-response
  [req event submission result]
  (if (:ok result)
    (do
      (review-updates/push-board-updates! event (:id submission))
      (web-http/see-other
        (str "/events/" (:slug event) "/submissions/" (:id submission))))
    (detail-page-response req event submission 422
                          {:edit-errors (:errors result)
                           :edit-values (:params req)})))

(defn handle-session-content
  [req]
  (if-let [{:keys [event submission person]} (organizer-submission req)]
    (if-not (chair-on-event? event person)
      {:status 403 :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body "Only an organizer may edit canonical session content."}
      (content-result-response
        req event submission
        (submission-content/update-answers!
          (:id submission) (:params req) (:email person))))
    (web-event/not-found-page (get-in req [:path-params :slug]))))

(defn handle-session-content-restore
  [req]
  (if-let [{:keys [event submission person]} (organizer-submission req)]
    (if-not (chair-on-event? event person)
      {:status 403 :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body "Only an organizer may restore canonical session content."}
      (let [raw-index (get-in req [:path-params :log-index])
            log-index (when (re-matches #"\d+" (str raw-index))
                        (Long/parseLong (str raw-index)))]
        (if (nil? log-index)
          (web-event/not-found-page (str "revision " raw-index))
          (content-result-response
            req event submission
            (submission-content/restore!
              (:id submission) log-index (:email person))))))
    (web-event/not-found-page (get-in req [:path-params :slug]))))

(defn handle-submissions-list
  "RETIRED page (Gene, 2026-08-10): the board strictly superseded it — same
   rows, more power — and two near-identical tables force every reviewer to
   ask which one is real. The route 303s so every old link keeps working."
  [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (web-http/see-other (str "/events/" slug "/board"))
      (web-event/not-found-page slug))))

(defn push-notice!
  "Repaint the notice region for everyone on this board — each of them seeing
   THEIR OWN notice, which is almost always nothing.

   Personal on purpose: a refusal is one person's business. Broadcasting one
   render would show the whole committee somebody else's mistake, the same way
   broadcasting one board row would show everyone the actor's star highlight."
  [event]
  (sse/push-personal-fragment!
    (:id event) "#validation-notice"
    (fn [person-id]
      (view-review/notice-region event (notices/notice-for (:id event) person-id)))))

(def ^:private value-rejections
  "Failures caused by a bad VALUE the caller sent — as opposed to a bug. Each
   one carries a :user-message written for a human."
  #{:invalid-status :invalid-content-status :invalid-stars :missing-stars :empty-comment
    :not-chair :reviewer-recused :submission-not-rateable
    :missing-recusal-reason :not-event-reviewer :chair-required
    :missing-nudge-body :no-review-work-remaining :submission-not-assigned})

(defn- wants-html?
  "A browser says so in Accept. curl (`*/*`) and every HTTP client we have met
   do not, and they get JSON."
  [req]
  (str/includes? (str (get-in req [:headers "accept"])) "text/html"))

(defn board-fragment-html
  "Render historical CFP facts under today's privacy policy and reveal rights."
  [req event]
  (let [tt (web-event/time-travel-context req event (str "/events/" (:slug event) "/board"))
        person (auth/current-person req)
        current-rows (reviews/enriched-for-event (:id event))
        visibility-context (voting-policy/visibility-context
                             (review-plan/visibility-policy-event event)
                             current-rows
                             person)]
    (web-event/with-as-of (:as-of tt)
      (let [rows (if (:as-of tt)
                   (reviews/enriched-for-event (:id event))
                   current-rows)]
        (str (h/html
               (view-review/board-region
                 event
                 (board-state req event visibility-context rows))))))))

(defn- require-active-review!
  [row person]
  (when (review-work/recused? (:id row) (:id person))
    (throw (ex-info "reviewer recused"
                    {:type :reviewer-recused
                     :user-message "Restore this review before adding rating or comment evidence."}))))

(defn- reviewer-queue-response [req event]
  (let [person (auth/current-person req)
        role (some-> person :id (->> (committees/role-on-event (:id event))))]
    (cond
      (nil? person)
      {:status 302 :headers {"Location" "/login"} :body ""}

      (nil? role)
      {:status 403 :headers {"Content-Type" "text/plain"}
       :body "This review queue belongs to the event committee."}

      :else
      (let [assigned-ids (->> (review-assignments/submissions-for-reviewer
                                (:id event) (:id person))
                              (map :id)
                              set)
            rows (->> (reviews/enriched-for-event (:id event))
                      (filter #(contains? assigned-ids (:id %)))
                      (review-plan/project-submissions (:id event) (:id person)))]
        (web-http/html-response
          (view-reviewer-queue/queue-page
            event
            {:person person
             :rows rows
             :blind? (review-plan/blind-review? (:id event))
             :progress (review-assignments/progress-for-reviewer
                         (:id event) (:id person))}))))))

(defn handle-board [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (let [person (auth/current-person req)
            current-rows (reviews/enriched-for-event (:id event))
            visibility-context (voting-policy/visibility-context
                                 (review-plan/visibility-policy-event event)
                                 current-rows
                                 person)
            progress (when person
                       (review-assignments/progress-for-reviewer
                         (:id event) (:id person)))]
        (if (or (= "1" (str (get-in req [:params :assigned])))
                (and (pos? (or (:assigned progress) 0))
                     (not (chair-on-event? event person))
                     (not= "1" (str (get-in req [:params :all])))))
          (reviewer-queue-response req event)
          (let [tt (web-event/time-travel-context req event (str "/events/" slug "/board"))]
            (web-event/with-as-of (:as-of tt)
              ;; Re-read the event INSIDE the binding: as of that moment it may have
              ;; had a different name, or not existed at all. Keep today's privacy
              ;; policy and reveal rights outside that historical fold.
              (let [past-event (or (events/event-by-slug slug) event)
                    rows (if (:as-of tt)
                           (reviews/enriched-for-event (:id event))
                           current-rows)]
                (web-http/html-response
                  (view-review/board-page
                    past-event
                    (assoc (board-state req past-event visibility-context rows)
                           :time-travel tt))))))))
      (web-event/not-found-page slug))))

(defn handle-notice-dismiss
  "Take the message down because the reader asked. A POST like everything else —
   no JavaScript, works with scripting off."
  [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (let [person (auth/current-person req)]
        (notices/clear-notice! (:id event) (:id person))
        (push-notice! event)
        (web-http/see-other (or (get-in req [:headers "referer"])
                                (str "/events/" slug "/board"))))
      (web-event/not-found-page slug))))

(defn- reject-value!
  "Remember the refusal, push it, and answer 422 in the caller's own language."
  [req event row person message data]
  (notices/set-notice! (:id event) (:id person)
                       {:kind :error
                        :message message
                        :submission-id (:id row)
                        :at (store/now-iso)})
  (push-notice! event)
  (log/info :write-refused :submission-id (:id row) :type (:type data)
            :actor (:email person))
  (if (wants-html? req)
    (if (str/includes? (str (get-in req [:headers "referer"]))
                       (str "/submissions/" (:id row)))
      (detail-page-response req event row 422)
      ;; Render the board from a request with NO params. The params here are the
      ;; POST body — rendering with them made `status=Bogus` a board FILTER, so
      ;; the reviewer's answer to a bad status was an empty board that said "no
      ;; submissions match that search". The board's own query state belongs to
      ;; the GET that drew it, never to the form that failed.
      (let [clean (assoc req :params {})
            base-path (str "/events/" (:slug event) "/board")]
        (web-http/html-response 422 (view-review/board-page
                                      event
                                      (assoc (board-state clean event)
                                             :time-travel (web-event/time-travel-context clean event base-path))))))
    ;; Deliberately NOT the shared `json-response` helper: that one sets
    ;; `Cache-Control: public, max-age=60` for the open-data endpoints, and a
    ;; cached refusal is a refusal you can't retry.
    {:status 422
     :headers {"Content-Type" "application/json; charset=utf-8"
               "Cache-Control" "no-store"}
     :body (exports/->json
             (cond-> {"error" message
                      "submission-id" (:id row)}
               (:allowed data) (assoc "allowed" (vec (:allowed data)))))}))

(defn handle-board-fragment
  "Patches the board region as of the slider position. The slider itself is NOT
   in this fragment — patching an element mid-drag cancels the gesture."
  [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (events/event-by-slug slug)]
      (datastar/sse-fragment-response req "#board-region" (board-fragment-html req event))
      (web-event/not-found-page slug))))

(defn handle-board-sort
  "POST /api/events/:slug/board/sort {sort q status track all} — re-sort THIS
   viewer's board and push #board-region down their open SSE stream. No
   reload; the client stamps the same state into the URL with replaceState
   (the URL is the state — Gene, 2026-08-10)."
  [req]
  (web-event/with-event req
    (fn [event]
      (let [body (try (json/read (io/reader (:body req)) :key-fn keyword)
                      (catch Exception _ {}))
            req' (update req :params merge
                         (into {} (filter (comp web-http/not-blank str val))
                               (select-keys body [:sort :q :status :track :all])))
            person (auth/current-person req)]
        (if-not person
          (log/warn :board-sort-skipped :why :no-session)
          (sse/push-to-person! (:id event) (:id person) "#board-region"
                               (fn [] (h/raw (board-fragment-html req' event)))))
        {:status 204 :headers {} :body ""}))))

(defn- with-submission
  "Resolve event + submission + signed-in person, then run `f`. Every review
   mutation needs the same three things and the same failure modes.

   `f` either does the write or throws a typed refusal (see `reviews/refuse!`).
   Success clears any standing notice — the thing the person was told about is
   now done, so the server takes the message down. That is the whole lifecycle
   of the message, and none of it is a timer in a browser."
  [req f]
  (let [sub-id (web-http/clean-id (get-in req [:path-params :submission-id]))
        row (when sub-id (store/submission-by-id sub-id))
        person (auth/current-person req)
        event (when row (events/event-by-id (:event-id row)))]
    (cond
      (nil? row) (web-event/not-found-page (str "submission " sub-id))
      (nil? person) {:status 302 :headers {"Location" "/login"} :body ""}
      :else
      (try
        (f event row person)
        (notices/clear-notice! (:id event) (:id person))
        (review-updates/push-board-updates! event sub-id (:id person))
        (push-notice! event)
        ;; postJSON callers (the board's stars + comments) navigate NOWHERE:
        ;; 204, and the SSE per-person push repaints the row. Form fallbacks
        ;; still 303 — with the row anchor re-appended, because referer
        ;; headers never carry fragments and without it the quick-rate card
        ;; folds mid-thought (Gene, 2026-08-10).
        (if (some-> (get-in req [:headers "content-type"])
                    (str/includes? "application/json"))
          {:status 204 :headers {} :body ""}
          (web-http/see-other (str (or (get-in req [:headers "referer"])
                                       (str "/events/" (:slug event) "/board"))
                                   "#sub-" sub-id)))
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (if (contains? value-rejections (:type data))
              (reject-value! req event row person
                             (or (:user-message data) (.getMessage e)) data)
              (throw e))))))))

(defn handle-comment [req*]
  (let [req (absorb-json-params req*)]
    (with-submission
      req
      (fn [_event row person]
        (require-active-review! row person)
        (when-let [stars (web-http/not-blank (get-in req [:params :stars]))]
          (reviews/rate!
            (review-authorization/require-write-proof!
              (store/snapshot) (:id row) (:id person))
            stars (:email person)))
        (reviews/add-comment! (:id row) (:id person)
                              (get-in req [:params :body]) (:email person))))))

(defn handle-content-status [req]
  (with-submission
    req
    (fn [event row person]
      (when-not (chair-on-event? event person)
        (throw (ex-info "chair only"
                        {:type :not-chair
                         :user-message "Setting content approval is the chair's act."})))
      (submissions/set-content-status! (:id row)
                                       (get-in req [:params :status])
                                       (:email person)))))

(defn handle-priority [req]
  (with-submission
    req
    (fn [_event row person]
      (reviews/toggle-priority! (:id row) (:email person)))))

(defn handle-mention [req*]
  (let [req (absorb-json-params req*)]
    (with-submission
      req
      (fn [_event row person]
        (reviews/mention! (:id row) (:id person)
                          (get-in req [:params :to-person-id])
                          (get-in req [:params :note])
                          (:email person))))))

(defn- manage-speaker-values [row]
  (let [speaker (first (:speakers row))
        person (when (:person-id speaker) (store/person-by-id (:person-id speaker)))]
    {:talk-title (get-in row [:answers :talk-title])
     :bio (get-in person [:profile :bio])
     :headshot-url (get-in person [:profile :headshot-url])}))

(defn- manage-headshot-upload [req]
  (let [upload (get-in req [:params :file])]
    (when (and (map? upload) (:tempfile upload)) upload)))

(defn- manage-headshot-errors [upload]
  (when upload
    (let [size (or (:size upload) (.length ^java.io.File (:tempfile upload)))]
      (into {}
            (map (fn [[field message]] [field [message]]))
            (file-decisions/upload-errors
              {:filename (:filename upload)
               :content-type (:content-type upload)
               :size size
               :kind "Headshot"})))))

(defn handle-manage-speaker [req]
  (let [slug (get-in req [:path-params :slug])
        submission-id (web-http/clean-id (get-in req [:path-params :submission-id]))
        event (events/event-by-slug slug)
        row (when submission-id (store/submission-by-id submission-id))]
    (if (and event row (= (:id event) (:event-id row)))
      (web-http/html-response
        (view-review/manage-speaker-page
          event (reviews/enrich row)
          {:person (auth/current-person req)
           :notice (notices/notice-for (:id event) (:id (auth/current-person req)))
           :values (manage-speaker-values row)
           :files (files/for-submission (:id row))}))
      (web-event/not-found-page slug))))

(defn handle-manage-speaker-save [req]
  (let [slug (get-in req [:path-params :slug])
        submission-id (web-http/clean-id (get-in req [:path-params :submission-id]))
        event (events/event-by-slug slug)
        row (when submission-id (store/submission-by-id submission-id))
        person (auth/current-person req)]
    (if-not (and event row (= (:id event) (:event-id row)))
      (web-event/not-found-page slug)
      (let [actor (or (:email person) "organizer")
            person-id (:person-id (first (:speakers row)))
            params (:params req)
            upload (manage-headshot-upload req)
            preflight-errors (merge
                               (when (str/blank? (:talk-title params))
                                 {:talk-title ["A session title is required."]})
                               (when-not person-id
                                 {:file ["That speaker profile no longer exists."]})
                               (manage-headshot-errors upload))
            headshot-file (when (and upload (empty? preflight-errors))
                            (files/upload! {:source (:tempfile upload)
                                            :filename (:filename upload)
                                            :content-type (:content-type upload)
                                            :event-id (:id event)
                                            :person-id person-id
                                            :kind "Headshot"
                                            :force-version? true
                                            :actor actor}))
            profile-params (cond-> {:bio (:bio params)}
                             headshot-file
                             (assoc :headshot-url
                                    (str (web-http/request-host req)
                                         "/headshots/" (:id headshot-file))))
            title-result (when (empty? preflight-errors)
                           (submissions/update-session-title!
                             submission-id (:talk-title params) actor))
            bio-result (when (and person-id (empty? preflight-errors))
                         (portal/update-profile! person-id profile-params actor))
            errors (merge preflight-errors
                          (when (and title-result (not (:ok title-result)))
                            (:errors title-result))
                          (when (and bio-result (not (:ok bio-result)))
                            (:errors bio-result)))]
        (if (seq errors)
          (do
            (notices/set-notice! (:id event) (:id person)
                                 {:kind :error
                                  :message (str/join " " (mapcat val errors))})
            (web-http/html-response
              422
              (view-review/manage-speaker-page
                event (reviews/enrich (store/submission-by-id submission-id))
                {:person person
                 :notice (notices/notice-for (:id event) (:id person))
                 :values (merge (manage-speaker-values row)
                                {:talk-title (:talk-title params) :bio (:bio params)})
                 :files (files/for-submission submission-id)})))
          (do
            (notices/set-notice! (:id event) (:id person)
                                 {:kind :ok :message "Saved."})
            (web-http/see-other
              (str "/events/" slug "/submissions/" submission-id "/manage"))))))))

(defn handle-rate [req*]
  (let [req (absorb-json-params req*)]
    (with-submission
      req
      (fn [_event row person]
        (require-active-review! row person)
        (reviews/rate!
          (review-authorization/require-write-proof!
            (store/snapshot) (:id row) (:id person))
          (get-in req [:params :stars]) (:email person))))))

(defn- with-query-params
  "Replace query params before a redirect's optional row fragment."
  [location params]
  (let [[path fragment] (str/split location #"#" 2)
        [base old-query] (str/split path #"\?" 2)
        replaced (set (map (comp name key) params))
        old-pairs (remove
                    (fn [pair]
                      (contains? replaced
                                 (java.net.URLDecoder/decode
                                   (first (str/split pair #"=" 2)) "UTF-8")))
                    (remove str/blank? (str/split (or old-query "") #"&")))
        new-pairs (map (fn [[k v]]
                         (str (name k) "="
                              (java.net.URLEncoder/encode (str v) "UTF-8")))
                       params)
        query (str/join "&" (concat old-pairs new-pairs))]
    (str base (when-not (str/blank? query) (str "?" query))
         (when fragment (str "#" fragment)))))

(defn handle-status [req]
  (let [confirmation (volatile! nil)
        response
        (with-submission
          req
          (fn [event row person]
            (when-not (chair-on-event? event person)
              (throw (ex-info "chair only"
                              {:type :not-chair
                               :user-message (str "Setting a decision is the chair's "
                                                  "act — rate and comment to make "
                                                  "the case.")})))
            (let [updated (reviews/set-status! (:id row) (get-in req [:params :status])
                                               (:email person))]
              (vreset! confirmation
                       {:decision-result (if (= (:status row) (:status updated))
                                           "unchanged"
                                           "saved")
                        :decision-status (:status updated)}))))]
    (if (and @confirmation (= 303 (:status response)))
      (update-in response [:headers "Location"] with-query-params @confirmation)
      response)))

(defn handle-reviewer-queue [req]
  (web-event/with-event req
    (fn [event] (reviewer-queue-response req event))))

(defn- require-chair! [event person user-message]
  (when-not (chair-on-event? event person)
    (throw (ex-info "chair only"
                    {:type :not-chair
                     :user-message user-message}))))

(defn handle-reviewer-progress [req]
  (web-event/with-event req
    (fn [event]
      (let [person (auth/current-person req)]
        (require-chair! event person "Reviewer progress is available to event chairs.")
        (web-http/html-response
          (view-reviewer-progress/reviewer-progress-page
            event
            {:person person
             :progress (review-work/progress-for-event (:id event))
             :review-summary
             (let [target (review-policy/coverage-target-for (:id event))
                   rows (reviews/enriched-for-event (:id event))]
               (assoc (reviews/coverage (:id event) target)
                      :review-count (reduce + 0 (map :n rows))))}))))))

(defn handle-assign-reviewer [req]
  (with-submission
    req
    (fn [event row person]
      (require-chair! event person "Assigning reviewer work is the chair's act.")
      (review-assignments/assign!
        (:id row)
        (web-http/clean-id (get-in req [:path-params :person-id]))
        (:email person)))))

(defn handle-unassign-reviewer [req]
  (with-submission
    req
    (fn [event row person]
      (require-chair! event person "Assigning reviewer work is the chair's act.")
      (review-assignments/unassign!
        (:id row)
        (web-http/clean-id (get-in req [:path-params :person-id]))
        (:email person)))))

(defn handle-recuse-reviewer [req]
  (with-submission
    req
    (fn [_event row person]
      (review-work/recuse! (:id row) (:id person)
                           (get-in req [:params :reason]) (:email person)))))

(defn handle-unrecuse-reviewer [req]
  (with-submission
    req
    (fn [_event row person]
      (review-work/unrecuse! (:id row) (:id person) (:email person)))))

(defn- param-values [x]
  (cond
    (nil? x) []
    (sequential? x) x
    :else [x]))

(defn- nudge-page-drafts [progress drafts]
  (let [progress-by-id (into {} (map (juxt :person-id identity)) progress)]
    (mapv (fn [{:keys [person-id body]}]
            (let [row (get progress-by-id person-id)]
              (merge {:person-id person-id :body body}
                     row
                     {:progress row})))
          drafts)))

(defn handle-reviewer-nudge-draft [req]
  (web-event/with-event req
    (fn [event]
      (let [person (auth/current-person req)
            _ (require-chair! event person "Drafting reviewer nudges is the chair's act.")
            all-lagging? (= "all-lagging" (get-in req [:params :audience]))
            selected (set (map web-http/clean-id
                               (param-values (get-in req [:params :reviewer-id]))))
            progress (review-work/progress-for-event (:id event))
            selected-rows (if all-lagging?
                            progress
                            (filterv #(contains? selected (:person-id %)) progress))
            rows (->> selected-rows
                      (filter #(pos? (:remaining %)))
                      vec)]
        (if (empty? rows)
          (web-http/html-response
            422
            (view-reviewer-progress/nudge-draft-page
              event {:person person
                     :drafts []
                     :recipients selected-rows
                     :outcome :rejected
                     :error "Select at least one reviewer with work remaining."}))
          (web-http/html-response
            (view-reviewer-progress/nudge-draft-page
              event {:person person
                     :outcome :drafted
                     :recipients rows
                     :drafts (mapv #(assoc % :progress %
                                           :body (review-work/nudge-body (:name event) %))
                                   rows)})))))))

(defn handle-reviewer-nudge-record [req]
  (web-event/with-event req
    (fn [event]
      (let [person (auth/current-person req)
            _ (require-chair! event person "Recording reviewer nudges is the chair's act.")
            drafts (->> (:params req)
                        (keep (fn [[k body]]
                                (let [k (name k)]
                                  (when (str/starts-with? k "message-")
                                    {:person-id (web-http/clean-id (subs k 8))
                                     :body body}))))
                        vec)
            page-drafts (nudge-page-drafts
                          (review-work/progress-for-event (:id event))
                          drafts)
            page-data {:person person
                       :drafts page-drafts
                       :recipients page-drafts}]
        (if (empty? drafts)
          (web-http/html-response
            422
            (view-reviewer-progress/nudge-draft-page
              event (assoc page-data
                           :outcome :rejected
                           :error "Include at least one reviewer message to record.")))
          (try
            (let [nudges (review-work/record-nudges!
                           (:id event) drafts (:id person) (:email person))
                  queued (review-work/queue-nudge-emails!
                           (:id event) nudges (:email person)
                           (web-http/request-host req))]
              (web-http/html-response
                (view-reviewer-progress/nudge-draft-page
                  event (assoc page-data :drafts [] :outcome :queued
                               :queued-count (count queued)))))
            (catch clojure.lang.ExceptionInfo error
              (web-http/html-response
                422
                (view-reviewer-progress/nudge-draft-page
                  event (assoc page-data
                               :outcome :rejected
                               :error (.getMessage error)))))))))))

(defn- parse-integer [raw]
  (try
    (Long/parseLong (str raw))
    (catch Exception _ nil)))

(defn- distribution-page-data
  [event person selected-reviewer-ids track result confirmed?]
  (let [submission-by-id (into {}
                               (map (juxt :id identity))
                               (reviews/enriched-for-event (:id event)))
        reviewer-by-id (into {}
                             (map (juxt :person-id identity))
                             (review-work/progress-for-event (:id event)))]
    (assoc result
           :person person
           :track track
           :selected-reviewer-ids selected-reviewer-ids
           :submission-by-id submission-by-id
           :reviewer-by-id reviewer-by-id
           :confirmed? confirmed?)))

(defn- reviewer-distribution-response [req confirmed?]
  (try
    (web-event/with-event req
      (fn [event]
        (let [person (auth/current-person req)
              _ (require-chair! event person "Bulk reviewer distribution is the chair's act.")
              selected-reviewer-ids (->> (get-in req [:params :reviewer-id])
                                         param-values
                                         (mapv web-http/clean-id))
              track (web-http/not-blank (get-in req [:params :track]))
              cap (parse-integer (get-in req [:params :cap]))
              result ((if confirmed?
                        review-assignments/distribute!
                        review-assignments/preview-distribution)
                      (:id event)
                      track
                      selected-reviewer-ids
                      cap
                      (:email person))]
          (web-http/html-response
            (view-review-assignment/bulk-distribution-preview-page
              event
              (distribution-page-data event person selected-reviewer-ids
                                      track result confirmed?))))))
    (catch clojure.lang.ExceptionInfo e
      (web-http/html-response
        (if (= :not-chair (:type (ex-data e))) 403 422)
        (.getMessage e)))))

(defn handle-reviewer-distribution-preview [req]
  (reviewer-distribution-response req false))

(defn handle-reviewer-distribution-confirm [req]
  (reviewer-distribution-response req true))
