(ns cfp-scheduler-killer.io.email.resend
  (:require
   [clojure.data.json :as json]))

(defn- attachment [{:keys [ics ics-filename]}]
  (when ics
    {:filename (or ics-filename "invite.ics")
     :content (.encodeToString (java.util.Base64/getEncoder)
                               (.getBytes ^String ics "UTF-8"))}))

(defn payload [cfg {:keys [from to subject body reply-to] :as message}]
  (cond-> {:from (or from (:from cfg)) :to [to] :subject subject :text body}
    reply-to (assoc :reply_to reply-to)
    (:ics message) (assoc :attachments [(attachment message)])))

(defn default-http-post! [cfg message]
  (let [builder (-> (java.net.http.HttpRequest/newBuilder
                      (java.net.URI/create "https://api.resend.com/emails"))
                    (.header "Authorization" (str "Bearer " (:api-key cfg)))
                    (.header "Content-Type" "application/json"))
        builder (if-let [key (:idempotency-key message)]
                  (.header builder "Idempotency-Key" key)
                  builder)
        request (-> builder
                    (.POST (java.net.http.HttpRequest$BodyPublishers/ofString
                             (json/write-str (payload cfg message))))
                    (.build))
        response (.send (java.net.http.HttpClient/newHttpClient) request
                        (java.net.http.HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (.body response)}))

(def ^:dynamic *http-post!* default-http-post!)

(defn send! [cfg message]
  (let [response (*http-post!* cfg message)
        status (:status response)
        body (try (json/read-str (:body response) :key-fn keyword)
                  (catch Exception _ {}))]
    (if (< status 300)
      {:ok true :provider :resend :message-id (:id body) :raw body}
      {:ok false :provider :resend :error (or (:message body) (str "HTTP " status))
       :raw body})))
