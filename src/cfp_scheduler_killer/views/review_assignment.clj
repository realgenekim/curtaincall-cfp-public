(ns cfp-scheduler-killer.views.review-assignment
  "Chair controls for the optional reviewer work queue."
  (:require
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.review-assignments :as assignments]
   [cfp-scheduler-killer.views.organizer-layout :as organizer-layout]))

(defn assignment-control
  "One explicit toggle per committee reviewer. Assignments guide work; they do
   not grant or remove access to the shared board."
  [event submission]
  (let [committee (first (events/committees-for-event (:id event)))
        reviewers (if committee
                    (committees/members-for-committee (:id committee))
                    [])]
    [:div.review-assignment
     [:strong "Assign reviewers"]
     [:div.review-assignment-list
      (for [{:keys [person-id name]} reviewers
            :let [assigned? (assignments/assigned? (:id submission) person-id)]]
        [:form {:method "post"
                :action (str "/api/submissions/" (:id submission)
                             "/reviewers/" person-id
                             (if assigned? "/unassign" "/assign"))}
         [:button.ui.mini.button
          {:type "submit"
           :class (when assigned? "positive")
           :aria-pressed (str assigned?)}
          (str (if assigned? "Assigned · " "Assign · ") name)]])]]))

(defn- assignment-reason-label [reason]
  (case reason
    :already-assigned "Already assigned to one of the selected reviewers"
    :cap-reached "Per-reviewer cap reached"
    :not-in-preview "Not part of the confirmed preview"
    (name reason)))

(defn bulk-distribution-panel
  "Chair-only preview-first controls for deterministic bulk assignment."
  [event reviewer-progress track-counts]
  (when (seq reviewer-progress)
    [:details.ui.segment.bulk-reviewer-distribution {:open true}
     [:summary
      [:strong "Bulk reviewer distribution / auto-distribute"]
      " — track filter + per-reviewer cap + exact preview"]
     [:form.ui.form
      {:method "post"
       :action (str "/api/events/" (:slug event) "/reviewers/distribute/preview")}
      [:div.two.fields
       [:div.field
        [:label "Track"]
        [:select {:name "track"}
         [:option {:value ""} "All tracks"]
         (for [track (sort-by str (remove nil? (keys track-counts)))]
           [:option {:value track}
            (str track " (" (get track-counts track) ")")])
         (when (contains? track-counts nil)
           [:option {:value "(none)"}
            (str "Untracked (" (get track-counts nil) ")")])]]
       [:div.field
        [:label "Per-reviewer cap"]
        [:input {:type "number" :name "cap" :min 1 :max 500 :value 20
                 :required true}]]]
      [:div.grouped.fields
       [:label "Reviewers"]
       (for [{:keys [person-id name role assigned]} reviewer-progress]
         [:div.field
          [:div.ui.checkbox
           [:input {:type "checkbox" :name "reviewer-id" :value person-id}]
           [:label
            [:strong name]
            (str " · " (clojure.core/name role) " · " assigned " assigned")]]])]
      [:p.field-hint
       "Preview is read-only. Existing assignments count toward the cap; nothing is appended until confirmation."]
      [:button.ui.small.primary.button {:type "submit"}
       "Preview bulk distribution"]]]))

(defn bulk-distribution-preview-page
  [event {:keys [person track cap assignments unassigned reviewer-by-id
                 submission-by-id selected-reviewer-ids loads-before loads-after
                 confirmed? confirmation-ids]}]
  (let [selected-reviewers (keep reviewer-by-id selected-reviewer-ids)]
    (organizer-layout/organizer-shell
     (str (if confirmed? "Bulk distribution complete" "Preview bulk distribution")
          " — " (:name event))
     {:event event :active :board :person person :crumb "Bulk reviewer distribution"}
     (organizer-layout/header
      (if confirmed? "Bulk reviewer distribution complete" "Preview bulk distribution")
      (str (count assignments) " new assignment"
           (when (not= 1 (count assignments)) "s")
           " · " (count unassigned) " left unchanged")
      [:a.ui.basic.button {:href (str "/events/" (:slug event) "/board")}
       "← Review board"])
     [:div.ui.segment
      [:div.ui.horizontal.list
       [:div.item [:strong "Track: "] (if (seq track) track "All tracks")]
       [:div.item [:strong "Per-reviewer cap: "] cap]]
      [:div.ui.message
       {:class (if confirmed? "positive" "info")
        :role "status"}
       [:div.header (if confirmed? "Distribution confirmed" "Preview ready")]
       [:p
        (str (count assignments)
             (if confirmed? " assignments recorded" " exact assignments")
             " across " (count selected-reviewers) " reviewer"
             (when (not= 1 (count selected-reviewers)) "s"))]
       [:p (str "Track: " (if (seq track) track "All tracks")
                " · Per-reviewer cap: " cap)]
       (when-not confirmed?
         [:p "Nothing recorded yet. Confirm the exact plan below to create assignments."])]
      [:h3 (if confirmed?
             "Current assignment counts"
             "Assignment counts after confirmation")]
      [:div.ui.horizontal.list
       (for [{:keys [person-id name]} selected-reviewers]
         [:div.item
          {:data-reviewer-id person-id
           :data-assignment-count (get loads-after person-id 0)}
          [:strong name]
          (str " · " (get loads-after person-id 0) " assigned")])]
      [:table.ui.very.basic.compact.table
       [:thead [:tr [:th "Submission"] [:th "Reviewer"] [:th "Load"]]]
       [:tbody
        (for [{:keys [submission-id person-id]} assignments
              :let [submission (get submission-by-id submission-id)
                    reviewer (get reviewer-by-id person-id)]]
          [:tr
           [:td (or (get-in submission [:answers :talk-title]) (:title submission) submission-id)]
           [:td (or (:name reviewer) person-id)]
           [:td (str (get loads-before person-id 0)
                     " → " (get loads-after person-id 0))]])]]
      (when (seq unassigned)
        [:div.ui.warning.message
         [:div.header "Left unchanged"]
         [:ul
          (for [{:keys [submission-id reason]} unassigned
                :let [submission (get submission-by-id submission-id)]]
            [:li (str (or (get-in submission [:answers :talk-title]) (:title submission) submission-id)
                      " — " (assignment-reason-label reason))])]])
      (if confirmed?
        [:div.ui.positive.message
         [:div.header "Assignments recorded"]
         [:p "The result is in the event log. Repeating this confirmation is idempotent."]]
        [:form.ui.form
         {:method "post"
          :action (str "/api/events/" (:slug event) "/reviewers/distribute")}
         [:input {:type "hidden" :name "track" :value (or track "")}]
         [:input {:type "hidden" :name "cap" :value cap}]
         (for [person-id (or confirmation-ids selected-reviewer-ids)]
           [:input {:type "hidden" :name "reviewer-id" :value person-id}])
         [:div.ui.warning.message
          [:div.header "Human confirmation"]
          [:p "This receipt pins the event, track, cap, reviewers, and exact assignments shown above. Changes require a new preview."]]
         [:button.ui.primary.button {:type "submit"}
          "Confirm bulk distribution"]])])))
