(ns cfp-scheduler-killer.views.committee
  "Committee roster and membership controls."
  (:require
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.views.avatar :as avatar]
   [cfp-scheduler-killer.views.form-controls :as form-controls]
   [cfp-scheduler-killer.views.format :as format]
   [cfp-scheduler-killer.views.organizer-layout :as organizer-layout]
   [cfp-scheduler-killer.views.policy :as policy]
   [datastar-kit.ds :as ds]))

(defn- scope-action-url [committee-id person-id action track]
  (str "/api/committees/" committee-id "/members/" person-id "/scope?action=" (name action)
       (when track
         (str "&track=" (java.net.URLEncoder/encode track "UTF-8")))))

(defn member-scope-controls
  "Server-owned work-queue chips for one committee member. Each action is
   idempotent, so a repeated Datastar POST cannot flip the scope back."
  [committee-id track-options member editable?]
  (let [scope (committees/member-scope member)
        all? (= :all scope)
        action (fn [action track]
                 (ds/post-action*
                   (scope-action-url committee-id (:person-id member) action track) {}))]
    [:div.member-scope {:id (str "member-scope-" (:membership-id member))}
     [:span.field-hint "Default work queue:"]
     [:div.board-controls {:style "margin:0.25em 0 0;gap:0.35em;"}
      (if editable?
        (list
          [:button.chip {:type "button"
                         :class (when all? "on")
                         :aria-pressed (str all?)
                         :data-star-on:click (action :all nil)}
           "All tracks"]
          (for [track track-options
                :let [on? (and (set? scope) (contains? scope track))
                      command (cond all? :only on? :remove :else :add)]]
            [:button.chip {:type "button"
                           :class (when on? "on")
                           :aria-pressed (str on?)
                           :data-star-on:click (action command track)}
             track]))
        (if all?
          [:span.chip.on "All tracks"]
          (for [track track-options :when (contains? scope track)]
            [:span.chip.on track])))]]))

(defn- member-row
  "One roster line: face, who they are, their role, and the two actions.
   Remove is a real form POST (no client JS, nothing to go stale under a
  morph); Open is a plain link to the person page."
  [event-slug committee-id track-options editable? sign-in-links m]
  (let [invite (get sign-in-links (:person-id m))
        link (if (map? invite) (:link invite) invite)]
    [:div.member-row {}
     [:img.member-avatar-img {:src (or (:headshot-url m)
                                       (avatar/pool-face (:person-id m)))
                              :alt (:name m)}]
     [:div.member-who
      [:span.member-name (:name m)]
      [:span.member-role-pill {:class (:role m)} (:role m)]
      [:div.member-email (:email m)]
      (member-scope-controls committee-id track-options m editable?)]
     (when editable?
       [:div.member-actions
        (when link
          [:div
           [:div.ui.positive.message
            [:strong "Reviewer invitation queued"]
            [:div (str "To " (:email m) " · sign-in credentials created")]]
           [:label.field-hint "One-time sign-in link"]
           [:input {:type "text" :readonly true :value link
                    :aria-label (str "One-time sign-in link for " (:name m))}]])
        [:a.ui.mini.basic.button
         {:href (str "/events/" event-slug "/people/" (:person-id m))} "Open"]
        [:form {:method "post"
                :action (str "/api/memberships/" (:membership-id m) "/remove")}
         [:button.ui.mini.basic.button {:type "submit"} "Remove"]]])]))

(defn- committee-card
  "The Program Committee roster + the inline add form. `member-form` carries the
   values the organizer typed and any server-side errors, so a rejected add
   comes back filled in with the message right next to the field."
  [event-slug committee members track-options member-form editable? sign-in-links]
  (let [{:keys [values errors message]} member-form
        v #(get values % "")
        n (count members)]
    [:div {:id "committee"}
     [:div.cfp-section-title
      (str (or (:name committee) "Program committee")
           " · " n (if (= 1 n) " member" " members"))]
     [:div.roster-card
      (if (seq members)
        [:div.member-list
         (map (partial member-row event-slug (:committee-id committee) track-options
                       editable? sign-in-links)
              members)]
        [:p.field-hint
         "Nobody on the roster yet. Add the people who will read submissions — "
         "membership sets the coverage denominator and routes their email; it "
         "never gates what anyone can see."])]

     (when (and editable? message)
       [:div.ui.negative.message {:style "margin-top:1em;"} message])

     (when editable?
       (list
         [:div.cfp-section-title "Add a reviewer"]
         (if committee
           [:form.ui.form.add-member-form.roster-card
            {:method "post"
             :action (str "/api/committees/" (:committee-id committee) "/members/add")}
            [:div.three.fields
             [:div.field {:class (when (:name errors) "error")}
              [:label "Name"]
              [:input {:type "text" :name "name" :value (v :name)
                       :placeholder "Ann Perry" :data-ghost-fill ""}]
              (form-controls/field-errors errors :name)]
             [:div.field {:class (when (:email errors) "error")}
              [:label "Email"]
              [:input {:type "email" :name "email" :value (v :email)
                       :placeholder "annp@itrevolution.net" :data-ghost-fill ""}]
              (form-controls/field-errors errors :email)]
             [:div.field {:class (when (:role errors) "error")}
              [:label "Role"]
              [:select {:name "role"}
               (let [selected (committees/normalize-role
                                (or (format/not-blank (v :role)) committees/default-role))]
                 (for [r committees/roles]
                   [:option (cond-> {:value r} (= r selected) (assoc :selected true)) r]))]
              (form-controls/field-errors errors :role)]]
            [:button.btn-go.sm {:type "submit"} "Add reviewer & send invite"]
            [:div.field-hint {:style "margin-top:0.6em;"}
             "Being on this roster is what makes someone a reviewer of "
             [:strong "this"] " event, and of no other — it never gates what "
             "anyone can see. A one-time sign-in link is queued for their email "
             "and shown once here so you can hand it to them directly."]]
           [:p.field-hint "This event has no committee — that shouldn't happen; "
            "every event spawns one at creation."])))]))

(defn committee-page
  "Step 3's own page (Gene, 2026-08-09: 'create / edit review committee' is a
   place you go, not an anchor you scroll to). The same committee-card the
   dashboard shows, with the wizard's framing around it."
  [event {:keys [committee members track-options member-form person cfp-state editable? sign-in-links
                 presenter-visibility presenter-visibility-definition]}]
  (let [n (count members)
        slug (:slug event)
        editable? (not= false editable?)]
    (organizer-layout/organizer-shell
      (str "Committee — " (:name event))
      {:event event :active :committee :person person :crumb "Committee" :datastar? true}
      (organizer-layout/header (if editable? "Create review committee" "Program committee")
                               (if editable?
                                 (str n " on the roster · everyone on it can read and rate "
                                      "every submission")
                                 (str n " on the roster · view-only context for this review"))
                               ;; The forward act, top right like every wizard page: open the
                               ;; call once the committee is real, else on to the board.
                               [:div.fb-header-acts {}
                                (if editable?
                                  (if (= :not-open-yet cfp-state)
                                    [:form {:method "post"
                                            :action (str "/api/events/" slug "/cfp/open")}
                                     [:button.btn-go {:type "submit"} "Next: open the call →"]]
                                    [:a.btn-go {:href (str "/events/" slug "/board")}
                                     "Go to the review board →"])
                                  [:a.btn-go {:href (str "/events/" slug "/board")}
                                   "Go to the review board →"])])
      (policy/review-workflow-positioning)
      [:p.field-hint
       "Tracks shape each reviewer's default work queue — a filter, not a wall: "
       "every reviewer may look at any track, rate and comment anywhere, and ask "
       "other reviewers to opine. Tracks are created on "
       [:a {:href (str "/events/" slug "/details")} "Event details"] "."]
      (when (and editable? (< n 2))
        [:div.step-banner
         [:strong "Step 3 of 3."] " Review is a conversation among trusted "
         "peers over a shared table: everyone you add here sees every "
         "submission, every score and every comment. Add at least one "
         "colleague — the 2-review coverage rule needs two readers."])
      (committee-card slug committee members track-options member-form editable? sign-in-links)
      (policy/presenter-visibility-editor
        event presenter-visibility presenter-visibility-definition editable?))))
