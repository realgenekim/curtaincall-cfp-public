(ns cfp-scheduler-killer.ses
  "AWS SES (v2) transport — ported from the proven sender in the does repo
   (email/ses/src/ses/send.clj; identity itrevolution.com, verified
   2026-08-05, region us-east-1, MAIL FROM mail.itrevolution.com).

   Credentials load through the app's own secrets seam: local
   secrets/aws-ses-credentials.edn in dev, Secret Manager secret
   aws-ses-credentials (this project) on Cloud Run. EDN shape:
   {:access-key-id .. :secret-access-key .. :region \"us-east-1\"}.

   Sandbox caveat inherited from the source: until AWS grants production
   access, only verified identities receive mail — a failed send appends
   comms.failed like any other, so the send log stays honest."
  (:require [cfp-scheduler-killer.secrets :as secrets]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as credentials]))

(def ^:private from-address "CurtainCall CFP <notifications@itrevolution.com>")

(defonce ^:private creds-delay
  (delay (secrets/load-secret-or-nil
          "aws-ses-credentials" "secrets/aws-ses-credentials.edn" :parse :edn)))

(defn enabled?
  "SES transport is live only when creds exist AND we are not in the test lane.
   bin/kaocha sets CFP_MAIL_DISABLE=1 — the 2026-08-11 lesson: the fresh test
   watcher picked up the SES classpath + local creds and every suite run made
   REAL SES calls to @example.com addresses. Tests exercise the rendered path."
  []
  (and (nil? (System/getenv "CFP_MAIL_DISABLE"))
       (map? @creds-delay)))

(defonce ^:private client-delay
  (delay
    (when-let [creds @creds-delay]
      (aws/client
       {:api :sesv2
        :region (:region creds "us-east-1")
        :credentials-provider
        (credentials/basic-credentials-provider
         {:access-key-id (:access-key-id creds)
          :secret-access-key (:secret-access-key creds)})}))))

(defn send-email!
  "One SESv2 SendEmail. Returns {:ok? true :message-id ..} or
   {:ok? false :error ..}. Never throws — the caller records the fact."
  [{:keys [to subject body reply-to]}]
  (if-let [client @client-delay]
    (let [response (aws/invoke
                    client
                    {:op :SendEmail
                     :request
                     (cond-> {:FromEmailAddress from-address
                              :Destination {:ToAddresses (if (vector? to) to [to])}
                              :Content {:Simple {:Subject {:Data (str subject)}
                                                 :Body {:Text {:Data (str body)}}}}}
                       reply-to (assoc :ReplyToAddresses [reply-to]))})]
      (if (:cognitect.aws.error/code response)
        {:ok? false :error (pr-str (select-keys response [:cognitect.aws.error/code :Message :message]))}
        (if (:cognitect.anomalies/category response)
          {:ok? false :error (pr-str response)}
          {:ok? true :message-id (:MessageId response)})))
    {:ok? false :error "SES not configured"}))
