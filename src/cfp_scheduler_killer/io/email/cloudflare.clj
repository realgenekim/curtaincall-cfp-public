(ns cfp-scheduler-killer.io.email.cloudflare
  (:require
   [clojure.data.json :as json]))

(defn- attachment [{:keys [ics ics-filename]}]
  (when ics
    {:filename (or ics-filename "invite.ics")
     :type "text/calendar" :disposition "attachment"
     :content (.encodeToString (java.util.Base64/getEncoder)
                               (.getBytes ^String ics "UTF-8"))}))

(defn payload [cfg {:keys [from to subject body reply-to] :as message}]
  (cond-> {:from (or from (:from cfg)) :to to :subject subject :text body}
    reply-to (assoc :reply_to reply-to)
    (:ics message) (assoc :attachments [(attachment message)])))

(defn default-http-post! [cfg message]
  (let [url (str "https://api.cloudflare.com/client/v4/accounts/"
                 (:account-id cfg) "/email/sending/send")
        request (-> (java.net.http.HttpRequest/newBuilder (java.net.URI/create url))
                    (.header "Authorization" (str "Bearer " (:api-token cfg)))
                    (.header "Content-Type" "application/json")
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
                  (catch Exception _ {}))
        result (or (:result body) body)]
    (if (< status 300)
      {:ok true :provider :cloudflare
       :message-id (or (:id result) (:message_id result)) :raw body}
      {:ok false :provider :cloudflare
       :error (or (some-> body :errors first :message) (str "HTTP " status))
       :raw body})))
