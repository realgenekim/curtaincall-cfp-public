(ns cfp-scheduler-killer.views.reviewer-progress
  "Chair progress, human-drafted nudges, and reviewer conflict controls."
  (:require
   [cfp-scheduler-killer.views.format :as format]
   [cfp-scheduler-killer.views.organizer-layout :as organizer-layout]))

(defn chair-panel
  ([event progress]
   (chair-panel event progress nil))
  ([event progress review-summary]
   (when (seq progress)
     (let [assigned-total (reduce + 0 (map :assigned progress))
           completed-total (reduce + 0 (map :completed progress))
           completion-percent (if (pos? assigned-total)
                                (Math/round (* 100.0 (/ completed-total assigned-total)))
                                0)
           lagging-count (count (filter #(pos? (:remaining %)) progress))]
       [:section.ui.segment.reviewer-progress-panel
        [:div.ui.info.message.reviewer-progress-summary
         {:role "status"
          :data-assigned assigned-total
          :data-completed completed-total
          :data-lagging lagging-count}
         (if review-summary
           (list
             [:strong
              (str "Review coverage: " (:covered review-summary) " of " (:total review-summary)
                   " submissions have at least " (:target review-summary) " reviews ("
                   (Math/round (double (:pct review-summary))) "%)")]
             (str " · " (:review-count review-summary) " signed reviews recorded")
             (str " · " (reduce + 0 (map :remaining progress)) " outstanding assignments"))
           (list
             [:strong (str "Review completion: " completed-total " of " assigned-total
                           " assigned reviews complete (" completion-percent "%)")]
             (str " · " lagging-count " lagging reviewer"
                  (when (not= 1 lagging-count) "s"))))]
        [:div.reviewer-progress-receipts
         (for [{:keys [name assigned review-count remaining scope-label scope-coverage]} progress
               :let [scope-label (or scope-label "All tracks")
                     scope-coverage (or scope-coverage {:covered 0 :total 0 :target 2})]]
           [:div.field-hint
            [:strong name] " — " scope-label " · " (:covered scope-coverage) "/"
            (:total scope-coverage) " covered · " review-count " reviews · "
            assigned " assigned · " remaining " remaining"])]
        [:form.ui.form {:method "post"
                        :action (str "/api/events/" (:slug event) "/reviewer-nudges/draft")}
         [:table.ui.very.basic.compact.table
          [:thead
           [:tr [:th "Nudge"] [:th "Reviewer"] [:th "Reviews"]
            [:th "Assigned"] [:th "Remaining"] [:th "Scope"] [:th "Coverage"]
            [:th "Last follow-up"]]]
          [:tbody
           (for [{:keys [person-id name role assigned review-count remaining last-nudge
                         scope-label scope-coverage]} progress
                 :let [scope-label (or scope-label "All tracks")
                       scope-coverage (or scope-coverage {:covered 0 :total 0 :target 2})]]
             [:tr
              [:td
               (when (pos? remaining)
                 [:input {:type "checkbox" :name "reviewer-id" :value person-id
                          :aria-label (str "Draft a nudge for " name)}])]
              [:td [:strong name] [:div.field-hint (clojure.core/name role)]]
              [:td review-count]
              [:td assigned]
              [:td {:class (when (pos? remaining) "warning")} remaining]
              [:td scope-label]
              [:td (str (:covered scope-coverage) "/" (:total scope-coverage)
                        " at " (:target scope-coverage) "+")]
              [:td (if last-nudge
                     (str "Recorded " (format/fmt-instant (:recorded-at last-nudge) (:tz event)))
                     "Never")]])]]
         [:p.field-hint
          "Select lagging reviewers to prepare individual drafts. Nothing is sent automatically."]
         [:button.ui.small.primary.button {:type "submit"} "Draft nudges for selected reviewers"]
         (when (pos? lagging-count)
           [:button.ui.small.basic.button
            {:type "submit" :name "audience" :value "all-lagging"}
            (str "Draft all " lagging-count " lagging reviewer"
                 (when (not= 1 lagging-count) "s"))])]]))))

(defn reviewer-progress-page
  [event {:keys [person progress review-summary]}]
  (organizer-layout/organizer-shell
    (str "Reviewer progress — " (:name event))
    {:event event :active :reviewer-progress :person person :crumb "Reviewer progress"}
    (organizer-layout/header
      "Reviewer progress"
      "Signed reviews, coverage, outstanding assignments, and the last human follow-up.")
    (chair-panel event progress review-summary)))

(defn- recipient-list [recipients]
  (when (seq recipients)
    [:ul.nudge-recipients
     (for [{:keys [person-id name email progress]} recipients]
       [:li {:data-reviewer-id person-id}
        [:strong (or name person-id "Unknown reviewer")]
        (when email (str " · " email))
        (when progress
          (str " · " (or (:completed progress) 0)
               " of " (or (:assigned progress) 0) " complete · "
               (or (:remaining progress) 0) " remaining"))])]))

(defn nudge-draft-page
  [event {:keys [person drafts recipients error outcome queued-count]}]
  (let [recipients (or (seq recipients) drafts)
        queued? (= :queued outcome)
        title (if queued? "Reviewer reminder emails queued" "Draft reviewer nudges")]
    (organizer-layout/organizer-shell
      (str title " — " (:name event))
      {:event event :active :reviewer-progress :person person :crumb "Reviewer progress"}
      (organizer-layout/header
        title
        (if queued?
          "The edited reminders are in the human-approved email outbox."
          "Review every resolved message. Nothing is queued until you approve these drafts.")
        [:a.ui.basic.button {:href (str "/events/" (:slug event) "/reviewer-progress")}
         "← Reviewer progress"])
      (when error
        [:div.ui.negative.message {:role "alert"}
         [:div.header "Reviewer nudge request rejected"]
         [:p error]
         (recipient-list recipients)])
      (when (= :drafted outcome)
        [:div.ui.info.message {:role "status"}
         [:div.header (if (= 1 (count recipients)) "Draft ready" "Drafts ready")]
         [:p "Prepared for the current assigned review queue."]
         (recipient-list recipients)])
      (when queued?
        [:div.ui.positive.message {:role "status"}
         [:div.header "Reviewer reminder emails queued"]
         [:p (str (or queued-count (count recipients)) " reminder"
                  (when (not= 1 (or queued-count (count recipients))) "s")
                  " queued for approval. Review and send each reminder from Send Emails; a successful send shows Email sent and records the recipient, subject, and timestamp in History.")]
         [:a.ui.primary.button
          {:href (str "/events/" (:slug event) "/comms?tab=send")}
          "Review and send queued reminders"]
         (recipient-list recipients)])
      (when (seq drafts)
        [:form.ui.form {:method "post"
                        :action (str "/api/events/" (:slug event) "/reviewer-nudges/record")}
         (for [{:keys [person-id name email progress body]} drafts]
           [:div.ui.segment
            [:h3 (or name person-id "Unknown reviewer")]
            [:div.field-hint
             (when email (str email " · "))
             (or (:completed progress) 0) " of " (or (:assigned progress) 0)
             " complete · " (or (:remaining progress) 0) " remaining"]
            [:div.field
             [:label (str "Message for " (or name person-id "reviewer"))]
             [:textarea {:name (str "message-" person-id) :rows 6 :required true} body]]])
         [:div.ui.warning.message
          [:strong "Human send gate"]
          [:p "Nothing is queued until you approve these drafts. Approval records the reminder and places one email per recipient in the outbox."]]
         [:button.ui.primary.button {:type "submit"}
          "Record reviewed nudges and queue emails"]]))))

(defn recusal-control
  [submission person recusal]
  (when person
    [:div.ui.secondary.segment.review-recusal
     (if recusal
       (list
         [:strong "Recused from this review"]
         [:p (:reason recusal)]
         [:p.field-hint
          "Restoring reopens review controls and returns any prior assignment to your actionable queue."]
         [:form {:method "post"
                 :action (str "/api/submissions/" (:id submission) "/unrecuse")}
          [:button.ui.small.basic.button {:type "submit"} "Restore review"]])
       [:details
        [:summary "Conflict of interest — reversible"]
        [:p.field-hint
         [:strong "Restore this review at any time. "]
         "Recusing removes this submission from any actionable assigned queue and disables new review evidence. Your prior review history is preserved. You can restore review controls later; any prior assignment returns."]
        [:form.ui.form {:method "post"
                        :action (str "/api/submissions/" (:id submission) "/recuse")}
         [:div.field
          [:label "Why are you recusing?"]
          [:textarea {:name "reason" :rows 2 :required true}]]
         [:button.ui.small.warning.button {:type "submit"}
          "Recuse from this review"]]])]))
