(ns cfp-scheduler-killer.io.email.smtp)

(defn postal-message
  [cfg {:keys [from to subject body reply-to ics ics-filename]}]
  (cond-> {:from (or from (:from cfg) (:user cfg))
           :to to
           :subject subject}
    reply-to (assoc :reply-to reply-to)
    (nil? ics) (assoc :body body)
    ics (assoc :body [{:type "text/plain; charset=utf-8" :content body}
                      {:type :attachment
                       :content-type "text/calendar; method=PUBLISH; charset=utf-8"
                       :file-name (or ics-filename "invite.ics")
                       :content (.getBytes ^String ics "UTF-8")}])))

(defn send! [cfg message]
  (let [send-message (requiring-resolve 'postal.core/send-message)
        result (send-message {:host (:host cfg)
                              :port (or (:port cfg) 587)
                              :user (:user cfg)
                              :pass (:pass cfg)
                              :tls (not= false (:tls cfg))}
                             (postal-message cfg message))]
    (if (= :SUCCESS (:error result))
      {:ok true :provider :smtp
       :message-id (or (first (:message result)) (str (:code result)))
       :raw result}
      {:ok false :provider :smtp :error (str (:message result)) :raw result})))
