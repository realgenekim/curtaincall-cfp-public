(ns cfp-scheduler-killer.views.organizer-layout
  "Organizer navigation, page chrome, identity, and time-travel controls."
  (:require
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.forms :as forms]
   [cfp-scheduler-killer.portal :as portal]
   [cfp-scheduler-killer.public-catalog :as public-catalog]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.views.policy :as policy]
   [cfp-scheduler-killer.views.shell :as shell]
   [clojure.string :as str]
   [datastar-kit.ds :as ds]
   [hiccup.page :as page]
   [hiccup2.core :as h]))

(defn- breadcrumb
  "Events › <event> › <page>. The current segment is ink and unlinked — a
   breadcrumb whose last crumb is a link to where you already are is noise."
  [{:keys [event crumb]}]
  (when event
    [:div.crumbs
     [:a {:href "/events"} "Events"]
     [:span.sep "›"]
     [:a {:href (str "/events/" (:slug event))} (events/display-name event)]
     (when crumb (list [:span.sep "›"] [:span.here crumb]))]))

(defn event-resume-path
  "Where clicking THE EVENT lands (Gene, 2026-08-09): the first unfinished
   setup step — until a form exists, then until a chair is assigned — and the
   dashboard once the atomic setup is done. Same
   derivation as the sidebar's wizard; links must agree with the spine."
  [event]
  (let [slug (:slug event)]
    (cond
      (not (forms/configured? (:id event))) (str "/events/" slug "/form")
      (not (committees/chair-assigned? (:id event))) (str "/events/" slug "/committee")
      :else (str "/events/" slug))))

(defn header [title subtitle & right]
  [:div.app-header
   [:div
    [:h1.ui.header title]
    (when subtitle [:div.sub subtitle])]
   [:div right]])

(defn- sb-group [label & items]
  (list [:div.sb-group {} label] items))

(defn- sb-link [active? label href]
  [:a.sb-item {:class (when active? "active") :href href} label])

(defn- read-only-label [label]
  [:span.sb-read-only-label label [:span.sb-read-only-pill "View"]])

(defn presenter-policy-link
  "Always-visible event-spine link to the review-process configuration."
  ([event]
   (presenter-policy-link event nil))
  ([event policy]
   (let [blind? (boolean (get-in event [:settings :hide-presenter-info]))
         mode (or (:mode policy) (if blind? "blind" "visible"))]
     [:a.sb-policy-link
      (cond-> {:href (str "/events/" (:slug event) "/committee#presenter-visibility")
               :data-policy-mode mode}
        (some? (:version policy))
        (assoc :data-policy-version (:version policy)))
      (if-let [summary (:summary policy)]
        (list
         [:span.sb-policy-summary summary]
         [:span.sb-policy-action "Click to change →"])
        (list
         [:span.sb-policy-summary
          (policy/presenter-visibility-summary-text
           {:mode (if blind? "hidden" "visible")})]
         [:span.sb-policy-action "Click to change →"]))])))

(defn presenter-policy-note
  "Read-only presenter-visibility summary for non-managers. Reviewers must
   still SEE the effective policy even though only managers may change it —
   transparency is the point of blind review, so the copy renders for every
   committee member; only the click-to-change affordance is manager-gated."
  [event policy-map]
  (let [blind? (boolean (get-in event [:settings :hide-presenter-info]))
        mode (or (:mode policy-map) (if blind? "blind" "visible"))
        summary (or (:summary policy-map)
                    (when (:mode policy-map)
                      (policy/presenter-visibility-summary-text policy-map))
                    (policy/presenter-visibility-summary-text
                     {:mode (if blind? "hidden" "visible")}))]
    [:span.sb-policy-link.sb-policy-note {:data-policy-mode mode}
     [:span.sb-policy-summary summary]]))

(defn- sb-out
  "A door OUT of the workspace — the speaker-facing pages. The ↗ is the whole
   signal, and the new tab is the point: an organizer checking the public page
   is not navigating away from what they were doing."
  [label href]
  [:a.sb-item.sb-out {:href href :target "_blank" :rel "noopener"} label " ↗"])

(defn time-travel-bar
  "The event-sourcing party trick, made draggable.

   The slider spans the log's own first and last moments; sliding it re-folds a
   prefix into a throwaway projection and renders THAT. Nothing is copied,
   snapshotted or restored, because the log was always the truth and the screen
   was always derived from it."
  [event {:keys [as-of bounds index total base-path fragment-path]}]
  (when bounds
    (let [[first-at last-at] bounds]
      [:div.timetravel
       [:div.row1
        (if as-of
          [:div "Viewing as of " [:span.tt-when as-of]
           [:span.field-hint {:style "margin-left:0.6em;"} "read-only"]]
          [:div [:strong.tt-title "CFP Time Machine"]
           [:span.tt-sub "drag the scrub bar to watch the whole call for papers unfold"]])
        (when as-of
          [:a.ui.mini.button {:href base-path} "Return to now"])]
       ;; PURE SSE SCRUB (Gene, 2026-08-09): the old release-submit forced a
       ;; full page load the moment the handle was let go, wiping the live
       ;; patches — deleted. Dragging fires a throttled @get whose response
       ;; patches #board-region; releasing does nothing, because everything
       ;; already happened. The form remains only as the no-JS fallback.
       ;; The slider lives OUTSIDE the patched region: patching an element you
       ;; are mid-drag on cancels the gesture (the same reason joe-payne keeps
       ;; the reply box outside its SSE region).
       [:form {:method "get" :action base-path}
        [:input (merge
                 {:type "range" :name "at-index" :min 0 :max (max 0 (dec total))
                  :value (or index (max 0 (dec total)))}
                 (when fragment-path
                    ;; single-word signal on purpose — Datastar camelCases
                    ;; hyphens, so $at-index would silently become $atIndex.
                    ;; THROTTLE, not debounce: repaint continuously WHILE
                    ;; dragging — scrubbing video, not poking checkpoints.
                   (ds/live-scrub
                    :atidx
                    (str fragment-path "?at-index="))))]
        [:noscript [:button.ui.mini.button {:type "submit"} "Go"]]]
       [:div.field-hint {:style "display:flex; justify-content:space-between;"}
        [:span first-at] [:span (str total " recorded events")] [:span last-at]]])))

(defn- whoami-strip
  "Who you are signed in as, and the way out. Reviewers are NAMED — that is the
   point of the board — so the app always says whose opinions you're adding."
  [{:keys [person]}]
  (when person
    [:div.whoami
     "signed in as " [:strong (:name person)]
     [:form {:method "post" :action "/logout"}
      [:button.ui.mini.basic.button {:type "submit"} "Log out"]]]))

(defn- archived-event-banner [event person]
  (when (:archived-at event)
    (let [living (->> (if person
                        (events/events-for-person (:id person))
                        (events/list-events))
                      (remove :archived-at)
                      (remove #(= (:id %) (:id event)))
                      (sort-by :created-at #(compare %2 %1))
                      first)]
      [:div.ui.warning.message
       [:div.header "This event was archived"]
       (if living
         [:p "You may be looking for "
          [:a {:href (event-resume-path living)} (:name living)]
          "."]
         [:p "This recorded event remains viewable, but it is no longer active."])])))

(defn event-setup-done?
  "True once the wizard is over — clicking the event goes straight to the
   dashboard."
  [event]
  (= (event-resume-path event) (str "/events/" (:slug event))))

(defn event-manager? [event person]
  (boolean
   (when (and event person)
     (:chair? (committees/person-detail (:id event) (:id person))))))

(defn- may-create-events? [person]
  (boolean
   (and person
        (or (empty? (events/list-events))
            (some #(event-manager? % person) (events/list-events))))))

(defn- committee-participant? [event person]
  (boolean
   (and event person
        (seq (:memberships
              (committees/person-detail (:id event) (:id person)))))))

(defn- person-event-path [event person]
  (if (event-manager? event person)
    (event-resume-path event)
    (str "/events/" (:slug event))))

(defn- event-switcher-card [event mine person]
  [:details.sb-event-card {}
   [:summary
    [:span.sb-event-title (:name event)]
    (let [meta-line (str/join " · "
                              (remove str/blank?
                                      [(str (:location event))
                                       (str (events/display-dates
                                             (:starts-on event)
                                             (:ends-on event)))]))]
      (when-not (str/blank? meta-line)
        [:span.sb-event-meta meta-line]))
    [:span.sb-event-switch
     (when (> (count mine) 1)
       [:span.sb-event-count (count mine)])
     "⇅"]]
   [:div.sb-event-menu
    (for [e (->> mine
                 (remove #(= (:id %) (:id event)))
                 (sort-by :created-at #(compare %2 %1))
                 (take 5))]
      [:a.sb-event-opt {:href (person-event-path e person)}
       [:span.sb-opt-name (:name e)]
       (case (submissions/cfp-state e)
         :open [:span.sb-opt-state.open "CFP open"]
         :closed [:span.sb-opt-state "closed"]
         :not-open-yet [:span.sb-opt-state "not open yet"]
         nil)])
    [:a.sb-event-opt.all {:href "/events"}
     (str "All " (count mine) " events →")]]])

(defn- speaker-event-spine [event mine active person]
  (let [slug (:slug event)
        submission (portal/submission-for-event (:id person) (:id event))
        submission-id (:id submission)
        tasks (portal/tasks-for submission-id)
        outstanding (- (count tasks) (count (filter :done? tasks)))
        public-session? (portal/accepted-and-informed? submission)
        session-path (str "/agenda/" slug "/sessions/" submission-id)]
    (list
     (event-switcher-card event mine person)
     (sb-link (= active :dashboard) "Event overview" (str "/events/" slug))
     (sb-link (= active :details) (read-only-label "Event details")
              (str "/events/" slug "/details"))
     (sb-out "Public CFP page" (str "/cfp/" slug))
     (sb-group "My participation"
               (sb-link false "My proposal" (str "/portal#proposal-" submission-id))
               (sb-link false "Speaker profile" "/portal#speaker-profile")
               (sb-link false (str "Onboarding tasks (" outstanding ")")
                        (str "/portal#onboarding-" submission-id)))
     (sb-group "The show"
               (if public-session?
                 (sb-out "My session" session-path)
                 (sb-link false "My session" (str "/portal#proposal-" submission-id)))
               (sb-out "My schedule" (str "/agenda/" slug))
               (sb-out "Public agenda" (str "/agenda/" slug))))))

(defn- sidebar
  "The lifecycle spine — always exactly ONE event's spine, never a list of
   events (ratified 2026-08-09, docs/design/nav-elements.md: the rail is O(1)
   in event count; switching events is the main surface's job). On pages with
   no event in the URL the WORKING event's spine renders — last visited, else
   nearest upcoming — topped with its name when other events exist."
  [{:keys [event active person time-travel presenter-visibility-policy]}]
  (let [birth? (= active :new-event)
        ;; The switcher offers only LIVING events — archived ones stay
        ;; reachable from the events page's restore shelf, never from here
        ;; (Gene, 2026-08-10: the card said 52 when two are active).
        mine   (delay (->> (if person (events/events-for-person (:id person))
                               (events/list-events))
                           (remove :archived-at)
                           ;; Replay scratch events NEVER ride the rail — their
                           ;; home is the /events "Simulated events" shelf
                           ;; (Gene, 2026-08-11: "why so many events?"). Count
                           ;; and list stay in agreement.
                           (remove #(get-in % [:settings :replay?]))))
        current event
        event (or current
                  (when-not birth?
                    ;; Replay demos don't graduate you (Gene, 2026-08-11:
                    ;; "shouldn't it?"): the ghost map stays until a REAL
                    ;; event exists. Inside a demo event's own pages the URL
                    ;; supplies `current`, so its spine still renders there.
                    (events/working-event (some-> person :id)
                                          (:default-event-id person)
                                          @mine)))]
    [:nav.sidebar
     (when-not (and person (not birth?) (nil? event))
       ;; Newcomers get no top row (Gene picked placement A, 2026-08-11):
       ;; "+ New event" is gone and All events lives in the organizer card.
       [:div.sb-top
        [:a.sb-back {:href "/events"} "All events"]
        ;; INTENT: NAV-006 — the cross-event Speaker CRM must be reachable
        ;; from the organizer rail (field-survey ce5x: screens existed with
        ;; no nav entry, graded "schema without queries").
        [:a.sb-back {:href "/people"} "Speaker CRM"]
        (when (may-create-events? person)
          [:a.sb-new {:href "/events/new"} "+ New event"])])
     ;; The create page IS step one — the sidebar shows the wizard for the
     ;; event being born, not some other event's navigation (Gene: "grayed out
     ;; because we're in it right now" — the breadcrumb feeling).
     (when birth?
       ;; The full map stays visible while creating (Gene: "full context") —
       ;; the wizard is step 1, and every later group renders as a muted ghost
       ;; that comes alive the moment the event exists.
       (letfn [(ghost [label] [:span.sb-item.sb-step-done {} label])]
         (list
          (sb-group "Create CFP — step 1 of 3"
                    [:span.sb-item.active {}
                     [:span.sb-step [:span.sb-step-n "1"] [:span.sb-step-label "Create event"]
                      [:span.sb-step-note "you are here"]]]
                    [:span.sb-item.sb-step-done {}
                     [:span.sb-step [:span.sb-step-n "2"] [:span.sb-step-label "Create CFP form"]
                      [:span.sb-step-note "next"]]]
                    [:span.sb-item.sb-step-done {}
                     [:span.sb-step [:span.sb-step-n "3"] [:span.sb-step-label "Create review committee"]]])
          (sb-group "Review CFP proposals"
                    (ghost "Review Board") (ghost "Submissions"))
          (sb-group "Decide & tell"
                    (ghost "Manage Submissions and Speakers")
                    (ghost "Create Speaker")
                    (ghost "Speaker deliverables")
                    (ghost "Files"))
          (sb-group "The show"
                    (ghost "Schedule") (ghost "Public agenda")
                    (ghost "Exports & API"))
          (sb-group "Admin"
                    (ghost "Comms") (ghost "Log") (ghost "Settings")
                    (ghost "Manifesto")))))
     (when (and person (not birth?) (nil? event))
       ;; THE NEWCOMER GHOST MAP, sectioned BY ROLE (Gene approved the
       ;; mockup 2026-08-11: "perfect! go!"): three bold role banners with
       ;; separators — the rail answers "what do I do here as each hat?"
       ;; Every ghost row is a live door: one click creates a demo event
       ;; (corpus fast-forwarded) and lands on that section. Replay demos
       ;; don't graduate you, so this map stays until a REAL event exists.
       (letfn [(demo-item [label then]
                 [:form.sb-demo-form {:method "post"
                                      :action (str "/api/replay/start-demo?then=" then)}
                  [:button.sb-item.sb-ghost {:type "submit"} label]])
               (demo-step [n label then]
                 [:form.sb-demo-form {:method "post"
                                      :action (str "/api/replay/start-demo?then=" then)}
                  [:button.sb-item.sb-ghost {:type "submit"}
                   [:span.sb-step [:span.sb-step-n n] [:span.sb-step-label label]]]])]
         (list
           ;; V4r role CARDS (Gene, 2026-08-11: "love it, love it, love it"):
           ;; each hat is a white card with a one-line promise; the ghost box
           ;; is border-only; Review Board rides in BOTH organizer and
           ;; reviewer; the replay door goes straight to the board wearing
           ;; the red SIMULATION banner. No Comms·Log·Settings footer here
           ;; (Gene, 2026-08-11): those are event-scoped verbs — a newcomer
           ;; has no event to act on; the event spine keeps them.
          [:div.sb-sect
           [:div.sb-role
            [:span "As an organizer"]
            [:a.sb-role-see {:href "/events"} "see all events →"]
            [:small "run the whole call on one calm page"]]
           [:div.sb-ghost-box
            [:b "Your event will live here"]
            [:a.sb-ghost-create {:href "/events/new"} "Create it →"]]
           (sb-group "Create CFP — 3 steps"
                     [:a.sb-item.sb-step-item {:href "/events/new"}
                      [:span.sb-step [:span.sb-step-n "1"] [:span.sb-step-label "Create event"]]]
                     (demo-step "2" "Create CFP form" "form")
                     (demo-step "3" "Create review committee" "committee"))
            ;; The journey stays VISIBLE (Gene, 2026-08-11: "we actually want
            ;; disclosure of what the surface area is") — only Admin shrinks,
            ;; to one quiet footer word (still a live door, into the demo's
            ;; Settings). The event spine keeps the full Admin group.
           (sb-group "Review CFP proposals"
                     (demo-item "Review Board" "board"))
           (sb-group "Decide & tell"
                     (demo-item "Manage Submissions and Speakers" "inform")
                     [:span.sb-item.sb-ghost "Create Speaker"]
                     [:span.sb-item.sb-ghost "Speaker deliverables"]
                     [:span.sb-item.sb-ghost "Files"])
           (sb-group "The show"
                     (demo-item "Schedule" "schedule")
                     (demo-item "Public agenda" "agenda")
                     (demo-item "Exports & API" "exports"))
           (sb-group "Admin"
                     (demo-item "Comms" "comms")
                     (demo-item "Log" "log")
                     (demo-item "Settings" "settings")
                     (sb-link false "Manifesto" "/manifesto"))
           [:form.sb-demo-form {:method "post" :action "/api/replay/start-demo?then=board"}
            [:button.sb-item.sb-tour {:type "submit"} "▶ Replay a simulated CFP"
             [:small "opens the review board in simulation mode"]]]]
          [:div.sb-sect
           [:div.sb-role [:span "As a reviewer"]
            [:small "every score & comment, one shared table"]]
           (demo-item "Review Board" "board")]
          [:div.sb-sect
           [:div.sb-role [:span "As a speaker"]
            [:small "one profile, every conference"]]
           (sb-link false "Update your profile" "/portal")
           (sb-link false "Your submissions" "/portal")])))
     nil
     (when event
       (if (and person
                (portal/submission-for-event (:id person) (:id event))
                (not (committee-participant? event person)))
         (speaker-event-spine event @mine active person)
         (let [slug (:slug event)
               cfp-state* (submissions/cfp-state event)
               form-configured?* (forms/configured? (:id event))
               chair-assigned?* (committees/chair-assigned? (:id event))
               n-members* (committees/reviewer-count-for-event (:id event))
               {:keys [lit total]} (public-catalog/announce-stats event)
               ;; The wizard tracks SETUP, not the call state (Gene, 2026-08-09:
               ;; opening the call early must not hide the remaining steps).
               launching? (or (not form-configured?*)
                              (not chair-assigned?*)
                              (not= :open cfp-state*))]
           (list
             ;; THE EVENT MASTHEAD CARD (Gene ratified treatment A, 2026-08-09):
             ;; the rail names the room you are standing in — everything below is
             ;; THIS event's spine. A native <details> disclosure: the switcher
             ;; opens with zero JS and a morph can't break it.
            (list
             (event-switcher-card event @mine person)
             (sb-link (= active :dashboard)
                      (if (event-manager? event person)
                        "Dashboard"
                        "Event overview")
                      (str "/events/" slug)))

             ;; The transient wizard (Gene, 2026-08-09): while the call has never
             ;; been opened, these ARE serial steps and the sidebar says so —
             ;; numbered, with live ✓s and the launch act at the end. The moment
             ;; the call opens, the group relaxes into plain navigation: the
             ;; wizard exists exactly as long as the wizard is true.
            (if launching?
              (let [form-configured? form-configured?*
                     ;; The green ✓ says done — no "done" note needed; and the
                     ;; roster count read as noise, not signal (Gene, 2026-08-09).
                    steps      [{:done? true :label "Create / edit event"
                                 :href (str "/events/" slug "/details")
                                 :active? (= active :details)}
                                {:done? form-configured? :label "Create / edit CFP form"
                                 :href (str "/events/" slug "/form")
                                 :active? (= active :form)}
                                {:done? chair-assigned?*
                                 :label "Create / edit review committee"
                                 :href (str "/events/" slug "/committee")
                                 :active? (= active :committee)}]
                    step-n     (inc (count (take-while :done? steps)))]
                (sb-group (if (> step-n (count steps))
                            "Create CFP — ready to open"
                            (str "Create CFP — step " (min step-n (count steps)) " of " (count steps)))
                   ;; NO wrapper spans and NO per-row notes — every row is
                   ;; the same element with the same padding, so the
                   ;; rhythm is even (Gene, 2026-08-09: "make them even").
                          (for [{:keys [done? label href active?]} steps]
                     ;; Done → green ✓; not-yet → an EMPTY spacer so the
                     ;; grid's marker column always exists and labels
                     ;; align (Gene, 2026-08-09).
                            (let [row [:span.sb-step
                                       (if done?
                                         [:span.sb-step-n.sb-ck "✓"]
                                         [:span.sb-step-sp])
                                       [:span.sb-step-label label]]]
                              (if href
                                [:a.sb-item.sb-step-item
                                 {:href href
                                  :class (when active? "active")} row]
                                [:span.sb-item.sb-step-done.sb-step-item
                                 {} row])))
                   ;; A SIBLING of the steps (same marker column, same
                   ;; indent) — it belongs to the wizard, not below it
                   ;; (Gene, 2026-08-09).
                          [:a.sb-item.sb-out.sb-step-item
                           {:href (str "/cfp/" slug)
                            :target "_blank" :rel "noopener"}
                           [:span.sb-step (if (= :open cfp-state*)
                                            [:span.sb-step-n.sb-ck "✓"]
                                            [:span.sb-step-sp])
                            [:span.sb-step-label "View public CFP page ↗"]]]
                          (when (= :not-open-yet cfp-state*)
                            [:form.sb-open-form {:method "post"
                                                 :action (str "/api/events/" slug "/cfp/open")}
                             [:button.sb-open {:type "submit"} "Open the call →"]])))
              (sb-group (if (event-manager? event person) "The call" "The event")
                        (sb-link (= active :details)
                                 (if (event-manager? event person)
                                   [:span.sb-complete-label
                                    [:span.sb-inline-check "✓"]
                                    "Event details"
                                    (when (and (:demo? person)
                                               (= "organizer" (:persona-role person)))
                                      [:span.sb-start-pill "Start here"])]
                                   (read-only-label "Event details"))
                                 (str "/events/" slug "/details"))
                        (when (event-manager? event person)
                          (sb-link (= active :form)
                                   [:span.sb-complete-label
                                    (when form-configured?* [:span.sb-inline-check "✓"])
                                    "CFP Form"]
                                   (str "/events/" slug "/form")))
                        (sb-link (= active :committee)
                                 (if (event-manager? event person)
                                   [:span.sb-complete-label
                                    (when chair-assigned?* [:span.sb-inline-check "✓"])
                                    (str "Committee (" n-members* ")")]
                                   (read-only-label (str "Committee (" n-members* ")")))
                                 (str "/events/" slug "/committee"))
                        (sb-out (if (event-manager? event person)
                                  [:span.sb-complete-label
                                   (when (= :open cfp-state*)
                                     [:span.sb-inline-check "✓"])
                                   "Public CFP page"]
                                  "Public CFP page")
                                (str "/cfp/" slug))))

             ;; The board superseded the Submissions page (Gene, 2026-08-10:
             ;; two near-identical tables force "which one is real?"); it now
             ;; carries the count and the old route 303s here.
            (sb-group "Review CFP proposals"
                      (sb-link (= active :board)
                               (str "Review Board ("
                                    (submissions/count-for-event (:id event)) ")")
                               (str "/events/" slug "/board"))
                      (if (event-manager? event person)
                        (presenter-policy-link event presenter-visibility-policy)
                        (presenter-policy-note event presenter-visibility-policy))
                      (when (event-manager? event person)
                        (sb-link (= active :reviewer-progress)
                                 "Reviewer Progress"
                                 (str "/events/" slug "/reviewer-progress"))))

            (when (event-manager? event person)
              (sb-group "Decide & tell"
                         ;; INTENT: NAV-003 — the Speakers surface is reachable from the
                         ;; organizer sidebar through this single entry; the page's own
                         ;; [Decide]→[Inform]→[Manage] stage strip carries the workflows
                         ;; (Gene ratified 2026-08-17: one link, title
                         ;; "Manage Submissions and Speakers").
                        (sb-link (contains? #{:speakers :inform :announce} active)
                                 (str "Manage Submissions and Speakers ("
                                      total " speakers · " lit " public)")
                                 (str "/events/" slug "/speakers?view=manage"))
                        (sb-link (= active :create-speaker) "Create Speaker (Bypass CFP)"
                                 (str "/events/" slug "/speakers/new"))
                         ;; INTENT: NAV-004 — Speaker deliverables must be reachable from the organizer sidebar
                        (sb-link (= active :deliverables) "Speaker deliverables"
                                 (str "/events/" slug "/deliverables"))
                         ;; INTENT: NAV-005 — Files must be reachable from the organizer sidebar
                        (sb-link (= active :files) "Files"
                                 (str "/events/" slug "/files"))))

            (sb-group "The show"
               ;; INTENT: AUTHZ-003 — do not advertise a chair-only
               ;; workspace as a dead-end read-only reviewer link.
                      (when (event-manager? event person)
                        (sb-link (= active :schedule)
                                 "Schedule"
                                 (str "/events/" slug "/schedule")))
                      (sb-out "Public agenda" (str "/agenda/" slug))
                      (when (event-manager? event person)
                        (list
                         (sb-link (= active :exports) "Exports & API"
                                  (str "/events/" slug "/exports"))
                         (sb-link (= active :embed) "Embeds & widgets"
                                  (str "/events/" slug "/embed")))))

            (when (and (event-manager? event person)
                       (get-in event [:settings :replay?]))
              (sb-link (= active :replay) "Replay" (str "/events/" slug "/replay")))

            (when (event-manager? event person)
              (sb-group "Admin"
                 ;; The operations lane (Gene ratified 2026-08-11) — in
                 ;; Gene's world the chair decides the program and the
                 ;; admin (Ann) runs comms and settings. Promoted from
                 ;; footer decoration to a named group; bead qfp hangs
                 ;; admin-only affordances here when the role lands.
                        (sb-link (= active :comms) "Comms" (str "/events/" slug "/comms"))
                        (sb-link (= active :log) "Log" (str "/events/" slug "/log"))
                        (sb-link (= active :settings) "Settings" (str "/events/" slug "/settings"))
                        (sb-link false "Manifesto" "/manifesto")))

            nil))))]))

(defn- dev-mode? []
  (= "dev" (System/getenv "ENV")))

(defn- dev-strip
  "In-flow diagnostics for development and replay simulations. The strip
   follows the judged page instead of covering the viewport, and deliberately
   contains no persona switcher: identities are chosen only at the sandbox
   login door, never inside a user session."
  [{:keys [event time-travel]}]
  (let [dev? (dev-mode?)
        sim? (boolean (get-in event [:settings :replay?]))]
    (when (and event (or dev? sim?))
      ;; Native details keeps the timeline available without making it part of
      ;; the product surface. In document flow it cannot intercept page clicks.
      [:details.dev-strip {:open true}
       [:summary
        (when dev? [:span.sb-dev-badge "DEV"])
        (when sim? [:span.sb-dev-badge.sim-badge "SIMULATION"])]
       (when time-travel
         [:div.dev-strip-body
          [:div.dev-strip-scrub (time-travel-bar event time-travel)]])])))

(defn organizer-shell
  "Page shell for ORGANIZER pages: fixed left sidebar + content.
   The public CFP page deliberately does NOT use this — speakers get a clean
   single column with nothing to navigate."
  [title nav & body-content]
  (str
   (h/html
    (page/doctype :html5)
    [:html {:lang "en"}
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
      [:title title]
      [:link {:rel "icon" :href shell/favicon-data-uri}]
      [:link {:rel "stylesheet"
              :href "https://cdn.jsdelivr.net/npm/fomantic-ui@2.9.3/dist/semantic.min.css"}]
      [:script {:src "https://code.jquery.com/jquery-3.6.0.min.js"}]
      [:script {:src "https://cdn.jsdelivr.net/npm/fomantic-ui@2.9.3/dist/semantic.min.js"}]
      [:script {:src (shell/versioned "/js/datastar-kit.js")}]
      [:script {:src (shell/versioned "/js/keyboard.js") :defer true}]
      [:script {:src (shell/versioned "/js/telemetry-beacon.js") :defer true}]
      [:script {:src (shell/versioned "/js/ghost-fill.js") :defer true}]
      (when (:share? nav)
        [:script {:src (shell/versioned "/js/share.js") :defer true}])
        ;; Some pages need Datastar for one-shot actions without owning a
        ;; persistent stream. Keep runtime loading separate from SSE mounting so
        ;; live scrub cannot consume a browser connection for the page lifetime.
      (when (or (:datastar? nav) (:sse? nav))
        [:script {:type "module" :src (shell/versioned "/vendor/datastar-aliased.js")}])
      [:link {:rel "stylesheet" :href (shell/versioned "/css/app.css")}]]
     [:body (when-let [attrs (:body-attrs nav)] attrs)
        ;; THE RED BANNER (Gene picked option R, 2026-08-11): on a replay-
        ;; marked event a judge must never mistake simulation for reality —
        ;; red is the one color this app never uses decoratively. Renders in
        ;; EVERY environment, above everything.
      (when (get-in (:event nav) [:settings :replay?])
        [:div.sim-banner
         [:span.sim-word "SIMULATION"]
         [:span.sim-note "a demo CFP replayed onto a scratch event — nothing here is real"]
         [:a.sim-exit {:href "/events"} "Exit simulation ⨯"]])
        ;; The sidebar owns the top of the viewport (Gene, 2026-08-10: no
        ;; wasted band above it) — whoami rides the content column's first
        ;; row, sharing a line with the breadcrumb.
      [:div.ui.container.wide {:style "margin-top: 0.9em; margin-bottom: 4em;"}
       [:div.layout
        (sidebar nav)
        [:div.content
         [:div.content-top (breadcrumb nav) (whoami-strip nav)]
         (archived-event-banner (:event nav) (:person nav))
         body-content
         (list (shell/site-footer) (shell/build-identity))]]
         ;; The heartbeat target — proves the stream is alive without any JS.
       (when (:sse? nav) [:span {:id "sse-heartbeat"}])]
      (dev-strip nav)]])))
