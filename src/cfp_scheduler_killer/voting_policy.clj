(ns cfp-scheduler-killer.voting-policy
  "BLIND REVIEW as configuration data — the pure seam an LLM (or a form
   toggle) can later set. Two per-event settings live in the event's
   :settings map:

     :hide-presenter-info   on/off — hide ALL speaker identity everywhere it
                            is exposed TO REVIEWERS, replacing each hidden
                            value with `hidden-marker` so the reviewer knows
                            the info exists but is withheld.
     :reveal-after-vote     on/off — when hide is on, a reviewer sees the
                            speaker identity for a submission ONLY AFTER that
                            reviewer has cast their rating on it. Per-reviewer.

   Doctrine (kept intact): blind hides the AUTHOR from reviewers; reviewers
   still see EACH OTHER and each other's names/scores — the OPEN TABLE. This
   namespace only ever governs PRESENTER visibility; it never touches reviewer
   identity. Both settings default OFF: an event with them off behaves exactly
   as today (open table, presenter visible).

   Pure. No store, no I/O. The caller supplies whether this reviewer has
   already rated the submission (a fact the board already computes as `mine`)."
  (:require
   [clojure.string :as str]))

(def hidden-marker
  "The clear placeholder shown to a reviewer in place of a blinded value, so
   the reviewer knows the information exists but is withheld."
  "Name and org withheld")

(defn hidden-presenter-label
  "Stable event-local pseudonym for a blinded submission."
  [submission]
  (if-let [number (:submission-number submission)]
    (str "Submission #" number)
    hidden-marker))

(defn hide-presenter-info?
  "Is presenter-hiding turned ON for this event? Default OFF."
  [event]
  (boolean (get-in event [:settings :hide-presenter-info])))

(defn reveal-after-vote?
  "Is reveal-after-vote turned ON for this event? Default OFF. Only meaningful
   when hide-presenter-info? is also on."
  [event]
  (boolean (get-in event [:settings :reveal-after-vote])))

(defn visibility-context
  "Current, exogenous view policy. Data may be folded at any historical time;
   this value says what the current viewer may see now."
  [event rows reviewer-person]
  {:hide-presenter-info? (hide-presenter-info? event)
   :reveal-after-vote? (reveal-after-vote? event)
   :reviewer-person reviewer-person
   :rated-submission-ids
   (if reviewer-person
     (into #{}
           (keep (fn [row]
                   (when (some #(= (:id reviewer-person) (:person-id %))
                               (:ratings row))
                     (:id row))))
           rows)
     #{})})

(defn presenter-visible-in-context?
  "Pure visibility decision from an explicit current view context."
  [{:keys [hide-presenter-info? reveal-after-vote? rated-submission-ids]}
   submission]
  (or (not hide-presenter-info?)
      (and reveal-after-vote?
           (contains? rated-submission-ids (:id submission)))))

(defn presenter-visibility-mode
  "Canonical three-mode presenter-identity policy derived from legacy flags."
  [event]
  (cond
    (not (hide-presenter-info? event)) "visible"
    (reveal-after-vote? event) "reveal-after-vote"
    :else "hidden"))

(defn presenter-visible?
  "Should the PRESENTER's identity (name/company/title/profile fields) be
   visible to THIS reviewer, for THIS submission, right now?

   Truth table:
     hide off                         -> true  (open table; today's behavior)
     hide on, reveal off              -> false (blinded always, to reviewers)
     hide on, reveal-after-vote on    -> (this reviewer has already rated?)

   `has-this-reviewer-rated?` is the fact the board already knows as `mine`:
   true iff this reviewer person has cast a rating on this submission. It is
   only consulted when reveal-after-vote is on.

   Arguments other than the event/flags are accepted for a stable, honest
   call shape (the seam a caller passes what it has); `submission` and
   `reviewer-person` are not needed by the pure rule itself — the caller has
   already reduced the per-reviewer fact into `has-this-reviewer-rated?`."
  [event _submission _reviewer-person has-this-reviewer-rated?]
  (cond
    (not (hide-presenter-info? event)) true
    (reveal-after-vote? event)         (boolean has-this-reviewer-rated?)
    :else                              false))

(defn presenter-hidden?
  "Convenience negation of presenter-visible? — reads well at call sites that
   branch on hiding."
  [event submission reviewer-person has-this-reviewer-rated?]
  (not (presenter-visible? event submission reviewer-person has-this-reviewer-rated?)))

(defn field-or-hidden
  "The value to actually render for a presenter field: `value` when the
   presenter is visible, else the `hidden-marker`. Blank/nil values that were
   going to render as nothing still render nothing when visible; when hidden
   they always show the marker so the reviewer sees the info is withheld."
  [visible? value]
  (if visible?
    value
    hidden-marker))

(defn blank->nil
  "Small helper mirroring the views' not-blank guard, kept pure here so tests
   don't reach into a view ns."
  [s]
  (when-not (str/blank? (str s)) s))
