(ns cfp-scheduler-killer.views.live-drafts
  "Stable live-draft fragment values shared by public CFP and portal views."
  (:require
   [clojure.string :as str]))

(defn note-signal-name [param suffix]
  (str "cfpnote"
       (str/replace (name param) #"[^A-Za-z0-9]" "")
       suffix))

(defn note-signal-values
  "Return every note signal, including empty values that clear stale guidance."
  [params notes]
  (into {}
        (mapcat (fn [param]
                  (let [note (get notes param)]
                    [[(note-signal-name param "text") (or (:text note) "")]
                     [(note-signal-name param "warn") (= :warn (:level note))]
                     [(note-signal-name param "ok") (= :ok (:level note))]])))
        (distinct params)))

(defn cfp-note
  "The live, server-pushed line under one field.

   ALWAYS rendered, even when there is nothing to say, because a Datastar push
   needs a target that already exists (CLAUDE.md #9) — an empty note is an
   invisible landing pad. `note` is {:level :warn|:ok :text \"…\"} or nil."
  ([param note]
   (cfp-note param note nil))
  ([param note {:keys [reactive?]}]
   (let [text-ref (str "$" (note-signal-name param "text"))]
     [:div.cfp-note
      (cond-> {:id (str "cfp-note-" (name param))
               :class (when note (name (:level note)))}
        reactive?
        (assoc :data-star-show text-ref
               :data-star-text text-ref
               :data-star-class:warn
               (str "$" (note-signal-name param "warn"))
               :data-star-class:ok
               (str "$" (note-signal-name param "ok"))
               :style (when (str/blank? (:text note)) "display:none;")))
      (:text note)])))

(defn cfp-draft-status
  "The one line that tells a speaker their typing is safe.

   This is the whole point of the draft stash made visible: without it the
   feature is invisible and nobody trusts the tab. Pushed on every debounced
   keystroke, so 'saved' is a fact about the last keystroke, not a promise."
  [{:keys [answered total]} saved?]
  [:div#cfp-draft-status.cfp-draft-status {:class (when saved? "saved")}
   (when (pos? (or total 0))
     [:span.cfp-progress answered " of " total " answered"])
   (when saved?
     [:span.cfp-saved "Saved. Close the tab if you like — this comes back."])])

(defn portal-draft-status
  "The portal's version of the same promise, one per open form. `scope` is
   \"profile\" or a submission id, so the two forms have separate targets and a
   keystroke in the bio never repaints the talk."
  [scope saved?]
  [:div.cfp-draft-status {:id (str "portal-status-" scope)
                          :class (when saved? "saved")}
   (when saved?
     [:span.cfp-saved "Saved as a draft — press the button below to make it real."])])
