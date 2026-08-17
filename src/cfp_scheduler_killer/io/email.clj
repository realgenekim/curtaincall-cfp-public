(ns cfp-scheduler-killer.io.email
  "Provider-neutral outbound email port. Provider adapters return one small
   result algebra; application code never imports them directly."
  (:require
   [cfp-scheduler-killer.io.email.aws-ses :as aws-ses]
   [cfp-scheduler-killer.io.email.cloudflare :as cloudflare]
   [cfp-scheduler-killer.io.email.resend :as resend]
   [cfp-scheduler-killer.io.email.smtp :as smtp]
   [cfp-scheduler-killer.secrets :as secrets]))

(defn default-config []
  (secrets/load-secret-or-nil "email-provider" "secrets/email.edn" :parse :edn))

(def ^:dynamic *config-fn* default-config)
(def ^:dynamic *send-fn* nil)

(defn config [] (*config-fn*))
(defn configured? [] (some? (config)))

(defn provider-name [cfg]
  (name (or (:provider cfg) :smtp)))

(defn status-line []
  (if-let [cfg (config)]
    (str "Sending via " (provider-name cfg) " as "
         (or (:from cfg) (:user cfg)))
    "Email not configured — letters render below and are recorded, not sent"))

(defn send-with-config!
  "Send one provider-neutral message and return {:ok bool :message-id|:error}."
  [cfg message]
  (if *send-fn*
    (*send-fn* cfg message)
    (case (keyword (or (:provider cfg) :smtp))
      :resend (resend/send! cfg message)
      :cloudflare (cloudflare/send! cfg message)
      :aws-ses (aws-ses/send! cfg message)
      :smtp (smtp/send! cfg message)
      {:ok false :provider (:provider cfg)
       :error (str "Unknown email provider: " (:provider cfg))})))
