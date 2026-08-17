(ns cfp-scheduler-killer.views.policy
  "Canonical rendering for human-readable event policies.")

(defn presenter-visibility-summary-text
  "One canonical, human-readable sentence for the effective identity policy."
  [policy]
  (case (:mode policy)
    "visible" "Open review · Presenter identity is visible to reviewers."
    "hidden" "Blind review · Presenter identity is hidden throughout review."
    "reveal-after-vote" "Blind until rated · Presenter identity appears after a reviewer submits their first rating."))

(defn presenter-visibility-summary
  "The effective identity policy as a compact standalone notice."
  [policy definition]
  (let [summary (presenter-visibility-summary-text policy)]
    [:aside.ui.info.message.presenter-visibility-policy
     (cond-> {:data-policy-mode (:mode policy)
              :data-policy-version (:version policy)}
       definition (assoc :data-policy-id (:id definition)))
     [:p summary]]))

(defn review-workflow-positioning
  "Compact, intentional refusal of rounds and configurable scorecards."
  []
  [:aside#review-workflow.ui.info.message
   [:div.header "How Curtain Call reviews"]
   [:p
    "Curtain Call does not use weighted scorecards or workflow-driven review
     rounds. Reviewers give signed Stars and comments; the committee converges
     through conversation and progressively narrowing the pool. Every submission
     stays on the shared table—no pagination, no page reloads, and no hiding what
     your fellow reviewers are doing."]])

(defn presenter-visibility-editor
  "The one configuration surface for presenter identity during review."
  [event policy _definition editable?]
  [:section#presenter-visibility.ui.segment.fb-card
   [:div.cfp-section-title "Presenter visibility"]
   [:p.field-hint
    "This policy hides only presenter identity. Signed Stars, comments, and the
     committee conversation remain visible."]
   [:form.ui.form
    {:method "post"
     :action (str "/api/events/" (:slug event)
                  "/review-plan/presenter-visibility")}
    [:fieldset.read-only-fields {:disabled (not editable?)}
     [:input {:type "hidden"
              :name "expected-version"
              :value (:version policy)}]
     (for [[mode label] [["visible" "Visible to reviewers"]
                         ["hidden" "Hidden throughout review"]
                         ["reveal-after-vote" "Hidden until each reviewer rates"]]]
       [:div.field
        [:div.ui.radio.checkbox
         [:input {:type "radio"
                  :name "mode"
                  :value mode
                  :checked (= mode (:mode policy))}]
         [:label label]]])]
    (when editable?
      [:button.ui.small.primary.button {:type "submit"}
       "Save presenter visibility"])]])
