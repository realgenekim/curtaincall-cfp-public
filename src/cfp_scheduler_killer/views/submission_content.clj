(ns cfp-scheduler-killer.views.submission-content
  "Organizer controls for canonical session content and its append-only history."
  (:require
   [cfp-scheduler-killer.people :as people]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.views.form-controls :as form-controls]
   [cfp-scheduler-killer.views.format :as format]
   [clojure.string :as str]))

(defn- answer-values
  [answers overrides]
  (merge (into {}
               (map (fn [[k v]] [(keyword (str "answer-" (name k))) v]))
               answers)
         overrides))

(defn- field-label [field-id]
  (-> field-id name (str/replace #"-" " ") str/capitalize))

(defn- display-value [value]
  (let [rendered (str value)]
    (if (str/blank? rendered) "—" rendered)))

(defn- actor-label
  [actor]
  (let [person (when (and (string? actor) (str/includes? actor "@"))
                 (people/by-email actor))
        person-name (some-> (:name person) str/trim not-empty)]
    (if (and person-name (not= person-name actor))
      (str person-name " · " actor)
      actor)))

(defn editor
  [event submission errors values]
  [:section.ui.segment
   [:h2 "Session editing"]
   [:p.field-hint
    "Organizer edits update the canonical session used by the board, exports, API, and public agenda."]
   (when (:_ errors)
     [:div.ui.negative.message (str/join " " (:_ errors))])
   [:form.ui.form
    {:method "post"
     :action (str "/api/events/" (:slug event) "/submissions/"
                  (:id submission) "/content")
     :novalidate "novalidate"}
    (for [field (submissions/session-fields (:form-snapshot submission))]
      (form-controls/answer-input field
                                  (answer-values (:answers submission) values)
                                  errors))
    [:button.ui.primary.button {:type "submit"} "Save session changes"]]])

(defn history-section
  [event submission revisions]
  [:section.ui.segment
   [:h2 "History"]
   [:p.field-hint
    "Content version history is append-only. Restore creates a new change; it never deletes history."]
   [:div.ui.secondary.segment
    [:h3.ui.small.header "Current content"]
    [:p [:strong (or (get-in submission [:answers :talk-title]) "Untitled session")]]
    [:p (display-value (get-in submission [:answers :abstract]))]
    [:div.ui.label "Content status · " (submissions/content-status submission)]]
   (if (empty? revisions)
     [:p "No session edits yet."]
     [:div.ui.divided.items
      (for [{:keys [kind log-index at actor changed before after before-status
                    after-status restored-from-log-index]}
            (reverse revisions)]
        [:div.item {:data-log-index log-index}
         [:div.content
          [:div.header
           (format/fmt-when at (:tz event)) " · " (actor-label actor)]
          [:div.meta
           (cond
             (= :content-status kind) "Content status changed"
             restored-from-log-index (str "Restored revision " restored-from-log-index)
             :else (str "Changed " (str/join ", " (map field-label changed))))]
          [:div.description
           (if (= :content-status kind)
             [:div.ui.small.message
              [:div.header "Content status"]
              [:div (str "Before: " (display-value before-status))]
              [:div (str "After: " (display-value after-status))]]
             (for [field-id changed
                   :let [k (keyword field-id)]]
               [:div.ui.small.message
                [:div.header (field-label field-id)]
                [:div (str "Before: " (display-value (get before k)))]
                [:div (str "After: " (display-value (get after k)))]]))]
          [:p.field-hint
           (if (= :content-status kind)
             "Restores the editorial status from immediately before this transition and creates a new transition; history is never deleted."
             "Restores the complete content state from immediately before this edit and creates a new revision; history is never deleted.")]
          [:form {:method "post"
                  :action (str "/api/events/" (:slug event) "/submissions/"
                               (:id submission) "/history/" log-index "/restore")}
           [:button.ui.small.basic.button
            {:type "submit"
             :title (if (= :content-status kind)
                      "Restore the status before this transition"
                      "Restore this version by creating a new revision from the state before its edit")}
            (if (= :content-status kind)
              "Restore previous status"
              "Restore state before this edit")]]]])])])

(defn organizer-controls
  [event submission {:keys [history errors values]}]
  [:div
   (editor event submission errors values)
   (history-section event submission history)])
