(ns cfp-scheduler-killer.views.people
  "Organizer-facing person detail views."
  (:require
   [cfp-scheduler-killer.views.avatar :as avatar]
   [cfp-scheduler-killer.views.format :as format]
   [cfp-scheduler-killer.views.organizer-layout :as organizer-layout]
   [cfp-scheduler-killer.views.submission-row :as submission-row]))

(defn- profile-links
  "Render imported links alongside the canonical links maintained in the
   speaker portal."
  [{:keys [links linkedin-url website-url]}]
  (let [links (cond-> (vec links)
                (format/not-blank linkedin-url)
                (conj {:label "LinkedIn" :url linkedin-url})

                (format/not-blank website-url)
                (conj {:label "Website" :url website-url}))]
    (when (seq links)
      [:div {:style "margin-top:0.6em;"}
       (for [{:keys [label url]} links]
         [:a.ui.mini.basic.button {:href url :target "_blank" :rel "noopener"}
          (or (format/not-blank label) url)])])))

(defn person-page
  "One person, seen through the lens of one event: who they are, what profile we
   hold, and which of this event's committees they sit on.

   The reviews/comments sections are deliberately present and deliberately
   empty — an honest 'not yet' beats a section that quietly doesn't exist."
  [event {:keys [person memberships chair? review-summary]}]
  (let [{:keys [name email profile created-at]} person
        {:keys [headshot-url tagline org bio location links
                linkedin-url website-url]} profile]
    (organizer-layout/organizer-shell
      (str name " — " (:name event))
      {:event event :active :committee :crumb "Person"}
      (organizer-layout/header name
                               (or (format/not-blank tagline) email)
                               [:a.ui.basic.button {:href (str "/events/" (:slug event))}
                                "Back to " (:name event)])

      [:div.ui.stackable.two.column.grid
       [:div.column
        [:div.ui.segment
         [:h4.ui.header "Person"
          (when chair? [:span.ui.mini.blue.label {:style "margin-left:0.5em;"} "chair"])]
         [:img.ui.small.rounded.image {:src (or (format/not-blank headshot-url) (avatar/pool-face (:id person)))
                                       :alt name
                                       :style "margin-bottom:1em; max-width:160px; border-radius:10px;"}]
         [:dl.facts
          [:dt "Email"] [:dd [:a {:href (str "mailto:" email)} email]]
          (when (format/not-blank tagline) (list [:dt "Tagline"] [:dd tagline]))
          (when (format/not-blank org) (list [:dt "Organization"] [:dd org]))
          (when (format/not-blank location) (list [:dt "Location"] [:dd location]))
          [:dt "Known since"]
          [:dd (or (format/fmt-when created-at (:tz event))
                   [:span.field-hint "unknown"])]]
         (when (format/not-blank bio)
           [:div [:h5.ui.header "Bio"] [:p {:style "white-space:pre-wrap;"} bio]])
         (profile-links profile)
         (when-not (or (format/not-blank bio) (format/not-blank tagline)
                       (format/not-blank org) (format/not-blank headshot-url) (seq links)
                       (format/not-blank linkedin-url) (format/not-blank website-url))
           [:div.field-hint
            "No profile details yet — these fill in when they import a Sessionize "
            "profile or submit a talk."])]]

       [:div.column
        [:div.ui.segment
         [:h4.ui.header "Committees on this event"]
         (if (seq memberships)
           [:div.member-list
            (for [m memberships]
              [:div.member-row {}
               [:div.member-who
                [:span.member-name (:committee-name m)]
                (when (= "chair" (:role m)) [:span.ui.mini.blue.label "chair"])
                [:div.member-email
                 "member since " (or (format/fmt-when (:created-at m) (:tz event)) "—")]]])]
           [:p.field-hint "Not on any committee for this event."])]

        [:div.ui.segment
         [:h4.ui.header "Their reviews"
          (when (pos? (:rated-count review-summary))
            [:span.b-facts {:style "margin-left:0.6em; font-weight:400;"}
             (:rated-count review-summary) " of " (:total-submissions review-summary)])]
         (if (seq (:ratings review-summary))
           [:div
            ;; Their average against the COMMITTEE's average on the same talks.
            ;; A whole-event average would be arithmetic, not insight.
            [:div.field-hint {:style "margin-bottom:0.7em;"}
             "Their mean " [:strong (or (submission-row/fmt-mean (:mean review-summary)) "—")]
             " · committee mean on the same talks "
             [:strong (or (submission-row/fmt-mean (:committee-mean review-summary)) "—")]
             (when-let [d (when (and (:mean review-summary) (:committee-mean review-summary))
                            (- (:mean review-summary) (:committee-mean review-summary)))]
               (cond
                 (>= d 0.5) " — rates higher than the room"
                 (<= d -0.5) " — rates lower than the room"
                 :else " — in line with the room"))]
            (for [r (:ratings review-summary)]
              [:div.member-row {}
               [:div.member-who
                [:a {:href (str "/events/" (:slug event) "/submissions/" (:submission-id r))}
                 (:title r)]
                [:div.member-email
                 "committee mean " (or (submission-row/fmt-mean (:committee-mean r)) "—")]]
               [:div.op-stars {:style "font-weight:700;"} "★" (submission-row/fmt-stars (:stars r))]])]
           [:div.empty-state "They haven't rated anything on this event yet."])]

        [:div.ui.segment
         [:h4.ui.header "Their comments"]
         (if (seq (:comments review-summary))
           (for [c (:comments review-summary)]
             [:div.sub-row {}
              [:div.sub-title
               [:a {:href (str "/events/" (:slug event) "/submissions/" (:submission-id c))}
                (:title c)]]
              [:div {:style "margin-top:0.2em;"} (:body c)]])
           [:div.empty-state "No comments on this event yet."])]]])))
