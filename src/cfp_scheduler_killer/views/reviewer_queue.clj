(ns cfp-scheduler-killer.views.reviewer-queue
  "A reviewer-first landing page: the work assigned to this person, without
  organizer-only navigation or setup controls."
  (:require
   [cfp-scheduler-killer.views.organizer-layout :as organizer-layout]))

(defn queue-page
  [event {:keys [person rows progress]}]
  (let [assigned (or (:assigned progress) 0)
        completed (or (:completed progress) 0)
        remaining (or (:remaining progress) 0)]
    (organizer-layout/organizer-shell
      (str "Assigned review queue — " (:name event))
      {:event event
       :active :review-queue
       :person person
       :crumb "Assigned review queue"
       :reviewer-mode? true}
      (organizer-layout/header
        "Assigned review queue"
        "Assigned to you. Only submissions explicitly assigned to you appear here; the shared Review Board remains available."
        [:a.ui.primary.button
         {:href (str "/events/" (:slug event) "/board?assigned=1")}
         (str "Assigned to you (" remaining " remaining)")]
        [:a.ui.basic.button.review-board-return
         {:href (str "/events/" (:slug event) "/board?all=1")
          :aria-label "Open shared review board"}
         "Open shared Review Board"])
      [:div.ui.segment.reviewer-progress
       [:div.ui.horizontal.list
        [:div.item {:data-progress "assigned"} [:strong "Assigned"] " " assigned]
        [:div.item {:data-progress "completed"} [:strong "Completed"] " " completed]
        [:div.item {:data-progress "remaining"} [:strong "Remaining"] " " remaining]]
       [:div.field-hint (str completed " of " assigned " reviews complete")]]
      (if (seq rows)
        [:div.ui.relaxed.divided.list.reviewer-queue
         (for [row rows
               :let [speaker (first (:speakers row))
                     title (get-in row [:answers :talk-title])]]
           [:a.item {:href (str "/events/" (:slug event) "/submissions/" (:id row))
                     :data-submission-id (:id row)}
            [:div.content
             [:div.header title]
             [:div.description
              (str (:name speaker)
                   (when-let [org (:org speaker)] (str " · " org))
                   " · " (:status row))]]])]
        [:div.ui.message
         [:div.header "No assigned reviews"]
         [:p "No submissions are currently assigned to you. You can still review every submission from the shared Review Board."]]))))
