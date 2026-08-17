(ns cfp-scheduler-killer.views.log
  "Event history and time-travel log presentation."
  (:require
   [cfp-scheduler-killer.views.format :as format]
   [cfp-scheduler-killer.views.organizer-layout :as organizer-layout]
   [cfp-scheduler-killer.views.submission-row :as submission-row]
   [clojure.string :as str]))

(defn log-summary
  "One human line per stored event. Falls back to the type when we meet an
   event this build doesn't have a sentence for."
  [{:keys [type payload]}]
  (case type
    "event.created" (str "Created \"" (:name payload) "\" (" (:slug payload) ")")
    "event.updated" (str "Updated " (str/join ", " (:changed payload)))
    "committee.created" (str "Spawned committee \"" (:name payload) "\"")
    "form.installed" (str "Installed the " (or (:template payload) "seed")
                          " form (" (count (:fields payload)) " fields)")
    "person.created" (str "First saw " (:name payload) " <" (:email payload) ">")
    "member.added" (str "Added " (or (:name payload) (:email payload))
                        " to the committee as " (:role payload))
    "member.removed" (str "Removed " (or (:name payload) (:email payload))
                          " from the committee")
    "submission.created" (if (str/starts-with? (str (:source payload)) "on-behalf-of")
                           (str "Captured on behalf of "
                                (or (:name (first (:speakers payload))) "someone")
                                " — \"" (get-in payload [:answers :talk-title]) "\"")
                           (str "\"" (get-in payload [:answers :talk-title]) "\" submitted by "
                                (or (:name (first (:speakers payload))) "someone")))
    "comms.rendered" (str "Would send: \"" (:subject payload) "\" to " (:to payload)
                          (when (:has-ics? payload) " (with calendar invite)"))
    "comms.sent" (str "Emailed \"" (:subject payload) "\" to " (:to payload)
                      (when (:has-ics? payload) " (with calendar invite)"))
    "comms.failed" (str "FAILED to email " (:to payload) ": " (:error payload))
    "email.queued" (str "Queued email: \"" (:subject payload) "\" to " (:to payload))
    "email.approved" (str "Approved queued email " (:email-id payload))
    "email.sent" (str "Emailed \"" (:subject payload) "\" to " (:to payload))
    "email.failed" (str "FAILED to email " (:to payload) ": " (:error payload))
    "email.discarded" (str "Discarded queued email " (:email-id payload))
    "rating.set" (str (if (:previous-stars payload)
                        (str "Changed a rating from " (submission-row/fmt-stars (:previous-stars payload))
                             " to " (submission-row/fmt-stars (:stars payload)))
                        (str "Rated " (submission-row/fmt-stars (:stars payload)) " stars")))
    "comment.added" (str "Commented: " (let [b (str (:body payload))]
                                         (if (> (count b) 90)
                                           (str (subs b 0 90) "…") b)))
    "submission.status-changed" (str "Status " (:from payload) " → " (:to payload))
    "submission.priority-toggled" (if (:priority payload)
                                    "Flagged for discussion"
                                    "Unflagged")
    "submission.notified" (str "Informed " (:to payload) " — \"" (:subject payload) "\"")
    "submission.answers-updated" (str "Speaker edited " (str/join ", " (:changed payload)))
    "person.profile-updated" (str "Profile updated: " (str/join ", " (:changed payload)))
    "task.installed" (str "Task added: " (:label payload))
    "task.completed" (str "Task done: " (:key payload)
                          (when (:value payload) (str " — " (:value payload))))
    type))

(defn log-region
  "The part of the log page the time-travel slider repaints."
  [event log-entries]
  [:div#log-region
   [:div.ui.segment
    (if (empty? log-entries)
      [:div.empty-state "Nothing recorded yet."]
      (for [e (reverse log-entries)]
        [:div.log-row
         [:div.log-when (or (format/fmt-when (:at e) (:tz event)) (:at e))]
         [:div.log-type (:type e)]
         [:div.log-what (log-summary e)]
         [:div.log-actor (:actor e)]]))]
   [:div.field-hint
    "This is the actual event log the app runs on — not a report generated "
    "beside it. Every screen in this tool is derived by replaying these rows, "
    "so nothing can happen without appearing here."]])

(defn log-page
  [event log-entries person & [time-travel]]
  (organizer-layout/organizer-shell
    (str "Log — " (:name event))
    {:event event :active :log :person person :crumb "Log"}
    (organizer-layout/header "Log"
                             (str (count log-entries) " recorded event"
                                  (when (not= 1 (count log-entries)) "s") " — newest first"))

    (organizer-layout/time-travel-bar event time-travel)
    (log-region event log-entries)))
