(ns cfp-scheduler-killer.live-validation
  (:require
   [cfp-scheduler-killer.forms :as forms]
   [cfp-scheduler-killer.sessionize-import :as sessionize-import]
   [cfp-scheduler-killer.submissions :as submissions]
   [clojure.string :as str]))

(def ^:private full-link-guidance
  "That needs to be a full link, starting with http:// or https://")

(defn cfp-live-notes
  "The live line under each field, computed from what is typed RIGHT NOW:
   {param {:level :warn|:ok :text \"…\"}}.

   Silence is the default. A speaker mid-sentence is not doing anything wrong,
   so a field with nothing to say says nothing — the only lines that appear are
   the two a speaker genuinely wants early: you are past the room we have, and
   that link isn't a link yet. Both are the SAME rules the 422 uses, computed by
   the same code, so the live lane can never promise something submit refuses."
  [form-fields values]
  (into {}
        (concat
          (keep (fn [f]
                  (let [param (keyword (str "answer-" (name (:id f))))
                        v (get values param)
                        cap (forms/effective-cap f)
                        t (name (or (:type f) "text"))]
                    (cond
                      (str/blank? v) nil

                      (and cap (> (count v) cap))
                      [param {:level :warn
                              :text (str (count v) " characters — we have room for "
                                         cap ". Trim it a little.")}]

                      (and (= t "url") (not (re-matches submissions/url-pattern v)))
                      [param {:level :warn
                              :text full-link-guidance}]

                      (and (= t "email")
                           (not (submissions/valid-speaker-email? v)))
                      [param {:level :warn
                              :text "That doesn't look like a complete email address yet."}]

                      (and cap (> (count v) (* 0.9 cap)))
                      [param {:level :ok
                              :text (str (- cap (count v)) " characters left")}])))
                (submissions/visible-session-fields
                  form-fields (submissions/parse-answers form-fields values)))
          (keep (fn [[param valid?]]
                  (let [v (get values param)]
                    (when (and (not (str/blank? v))
                               (not (valid? v)))
                      [param {:level :warn
                              :text full-link-guidance}])))
                [[:speaker-headshot-url submissions/valid-headshot-url?]
                 [:speaker-linkedin #(re-matches submissions/url-pattern %)]
                 [:speaker-2-headshot-url submissions/valid-headshot-url?]
                 [:speaker-2-linkedin #(re-matches submissions/url-pattern %)]])
          (keep (fn [[param field-key]]
                  (let [v (get values param)
                        cap (get submissions/speaker-max-lengths field-key)]
                    (when (and cap (string? v) (> (count v) cap))
                      [param {:level :warn
                              :text (str (count v) " characters — we have room for "
                                         cap ". Trim it a little.")}])))
                {:speaker-name :name
                 :speaker-title :title
                 :speaker-org :org
                 :speaker-bio :bio
                 :speaker-2-name :name
                 :speaker-2-title :title
                 :speaker-2-org :org
                 :speaker-2-bio :bio})
          (keep (fn [param]
                  (let [v (get values param)]
                    (when (and (not (str/blank? v))
                               (not (submissions/valid-speaker-email? v)))
                      [param {:level :warn
                              :text "That doesn't look like a complete email address yet."}])))
                [:speaker-email :speaker-2-email])
          (let [speakers (submissions/parse-speakers values)
                primary-email (:email (first speakers))
                additional-email (:email (second speakers))]
            (when (and (submissions/valid-speaker-email? primary-email)
                       (submissions/valid-speaker-email? additional-email)
                       (= primary-email additional-email))
              [[:speaker-2-email
                {:level :warn
                 :text "Each speaker must use a different email address."}]]))
          (let [v (:speaker-sessionize-url values)]
            (when (and (not (str/blank? v))
                       (nil? (sessionize-import/normalize-profile-url v)))
              [[:speaker-sessionize-url
                {:level :warn
                 :text "Paste your Sessionize profile URL — or just your username."}]])))))

(def ^:private profile-url-keys [:headshot-url :linkedin-url :website-url])

(defn portal-live-notes
  "Live feedback for one portal form. `scope` is \"profile\" or a submission id."
  [scope values submission]
  (if (= "profile" scope)
    (into {}
          (keep (fn [k]
                  (let [v (get values k)]
                    (when (and (not (str/blank? v))
                               (not (re-matches submissions/url-pattern v)))
                      [k {:level :warn
                          :text full-link-guidance}]))))
          profile-url-keys)
    (cfp-live-notes (:form-snapshot submission) values)))
