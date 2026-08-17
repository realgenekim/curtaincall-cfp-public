(ns cfp-scheduler-killer.views.form-controls
  "Shared server-rendered form controls and validation messages."
  (:require
   [cfp-scheduler-killer.forms :as forms]
   [clojure.string :as str]))

(defn field-errors [errors k]
  (when-let [msgs (get errors k)]
    [:div.ui.pointing.red.basic.label (str/join " " msgs)]))

(defn req-mark [required?]
  (when required? [:span.required-mark "*"]))

(defn validation-signal-name [k]
  (str "validation"
       (str/replace (name k) #"[^A-Za-z0-9]" "")))

(defn validation-signal-values
  "Return every named validation signal, including empty values that clear a
   previously rendered error without morphing its input control."
  [error-keys errors]
  (into {}
        (map (fn [k]
               [(validation-signal-name k)
                (str/join " " (get errors k))]))
        (distinct error-keys)))

(defn reactive-field-attrs [k]
  {:data-star-class:error (str "$" (validation-signal-name k))})

(defn field-error
  ([errors k]
   (when-let [msgs (get errors k)]
     [:div.ui.pointing.red.basic.label (str/join " " msgs)]))
  ([errors k {:keys [reactive?]}]
   (if-not reactive?
     (field-error errors k)
     (let [message (str/join " " (get errors k))
           signal-ref (str "$" (validation-signal-name k))]
       [:div.ui.pointing.red.basic.label
        {:data-star-show signal-ref
         :data-star-text signal-ref}
        message]))))

(defn answer-input
  "One field def -> one form control. `values` are the raw params of a rejected
   submission, so nothing the speaker typed is ever lost."
  [{:keys [id type label help placeholder required options private widget reactive?]
    :as field} values errors]
  (let [k (keyword (name id))
        param (keyword (str "answer-" (name id)))
        v (get values param "")
        t (name type)
        cap (forms/effective-cap field)
        err (get errors k)]
    [:div.field (merge {:id (str "pv-" (name id))
                        :class (str (when err "error ")
                                    (when private "private-note"))}
                       (when reactive? (reactive-field-attrs k)))
     [:label label (req-mark required)]
     ;; No "max N" chip: length lives in the SHAPE of the field, not a
     ;; number a speaker thinks about (Gene, 2026-08-09). Server-side caps
     ;; remain and speak up honestly only when actually exceeded.
     (cond
       ;; Field defs arrive as strings from the log and as keywords from the
       ;; seed vector, so compare by NAME everywhere — never by identity.
       (and (= t "select") (= (some-> widget name) "radio"))
       [:div.grouped.fields {:style "margin:0;"}
        (for [o options]
          [:div.field {}
           [:div.ui.radio.checkbox
            [:input (cond-> {:type "radio" :name (name param) :value o}
                      (= o v) (assoc :checked true))]
            [:label o]]])]

       (= t "select")
       [:select {:name (name param)}
        [:option {:value ""} "— choose —"]
        (for [o options]
          [:option (cond-> {:value o} (= o v) (assoc :selected true)) o])]

       ;; Placeholders are ghost EXAMPLES (:placeholder on the field def) —
       ;; never the help text, which renders once below the field where it
       ;; survives typing. Tab accepts them (ghost-fill.js), same affordance
       ;; as the create page (Gene, 2026-08-09).
       (#{"textarea" "markdown"} t)
       [:textarea {:name (name param) :rows (if (= t "markdown") 8 4)
                   :class (when (= t "markdown") "prose-deep")
                   :placeholder placeholder
                   :data-ghost-fill (when placeholder "")
                   :maxlength (some-> cap str)} v]

       (= t "url")
       [:input {:type "url" :name (name param) :value v
                :placeholder (or placeholder "https://…")
                :data-ghost-fill (when placeholder "")
                :maxlength (some-> cap str)}]

       (= t "email")
       [:input {:type "email" :name (name param) :value v
                :placeholder placeholder
                :data-ghost-fill (when placeholder "")
                :maxlength (some-> cap str)}]

       :else
       [:input {:type "text" :name (name param) :value v
                :placeholder placeholder
                :data-ghost-fill (when placeholder "")
                :maxlength (some-> cap str)}])
     (when help [:div.field-hint help])
     (field-error errors k {:reactive? reactive?})]))
