(ns cfp-scheduler-killer.api-reviews
  "Idempotent review writes from scoped API keys.

   Human reviewer keys continue through the ordinary review verbs. Review-bot
   keys append a structurally separate fact, so replay can never accidentally
   include AI opinions in human means or coverage."
  (:require
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.store :as store]
   [clojure.string :as str]))

(def ^:private write-lock (Object.))

(defn- digest [value]
  (let [bytes (.digest (java.security.MessageDigest/getInstance "SHA-256")
                       (.getBytes (pr-str value) "UTF-8"))]
    (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder)) bytes)))

(defn- refuse! [type message data]
  (throw (ex-info message (assoc data :type type))))

(defn- normalized-idempotency-key [value]
  (let [value (some-> value str str/trim not-empty)]
    (cond
      (nil? value)
      (refuse! :missing-idempotency-key
               "idempotency-key is required; retries must be safe."
               {})

      (> (count value) 200)
      (refuse! :invalid-idempotency-key
               "idempotency-key must be 200 characters or fewer."
               {:length (count value)})

      :else value)))

(defn- normalized-review [{:keys [stars comment idempotency-key]}]
  (let [raw-stars stars
        stars (reviews/parse-stars (str raw-stars))]
    (when-not stars
      (refuse! :invalid-stars
               (str "stars must be " reviews/star-scale-help ".")
               {:stars raw-stars}))
    {:stars (double stars)
     :comment (some-> comment str str/trim not-empty)
     :idempotency-key (normalized-idempotency-key idempotency-key)}))

(defn- existing-request [event-id api-key-id idempotency-key]
  (some (fn [fact]
          (let [payload (:payload fact)]
            (when (and (#{"ai-review.recorded"
                          "api-review.idempotency-recorded"} (:type fact))
                       (= (str api-key-id) (str (:api-key-id payload)))
                       (= idempotency-key (:idempotency-key payload)))
              fact)))
        (store/log-for-event event-id)))

(defn- request-fingerprint [{:keys [submission-id stars comment]}]
  (digest (sorted-map :submission-id submission-id
                      :stars stars
                      :comment comment)))

(defn- prior-result [fact request]
  (let [payload (:payload fact)
        same? (if (= "ai-review.recorded" (:type fact))
                (= (select-keys payload [:submission-id :stars :comment])
                   (select-keys request [:submission-id :stars :comment]))
                (= (:request-fingerprint payload)
                   (request-fingerprint request)))]
    (if same?
      {:review-id (:id payload)
       :actor (:actor fact)
       :created? false}
      (refuse! :idempotency-conflict
               "That idempotency-key was already used for a different review body."
               {:review-id (:id payload)}))))

(defn- require-submission! [event-id submission-id]
  (let [submission (store/submission-by-id submission-id)]
    (when-not (and submission (= event-id (:event-id submission)))
      (refuse! :no-such-submission
               "No such submission on this event."
               {:submission-id submission-id :event-id event-id}))
    submission))

(defn- record-bot! [event-id submission-id context request actor]
  (let [review-id (store/new-id)
        generation-id (str "api:" (subs (digest [event-id (:id context)
                                                 (:idempotency-key request)])
                                        0 22))
        payload {:id review-id
                 :submission-id submission-id
                 :generation-id generation-id
                 :ai-reviewer-id (str "ai-reviewer:" event-id)
                 :stars (:stars request)
                 :comment (:comment request)
                 :idempotency-key (:idempotency-key request)
                 :api-key-id (:id context)
                 :at (store/now-iso)}]
    (store/append! {:type "ai-review.recorded"
                    :event-id event-id
                    :actor actor
                    :payload payload})
    {:review-id review-id :actor actor :created? true}))

(defn- record-human! [event-id submission-id context request actor]
  (when-not (:person-id context)
    (refuse! :reviewer-subject-required
             "This reviewer key is not bound to a committee member."
             {}))
  (reviews/set-rating! submission-id (:person-id context) (:stars request) actor)
  (when (:comment request)
    (reviews/add-comment! submission-id (:person-id context) (:comment request) actor))
  (let [review-id (store/new-id)]
    (store/append! {:type "api-review.idempotency-recorded"
                    :event-id event-id
                    :actor actor
                    :payload {:id review-id
                              :submission-id submission-id
                              :person-id (:person-id context)
                              :stars (:stars request)
                              :comment (:comment request)
                              :request-fingerprint (request-fingerprint request)
                              :idempotency-key (:idempotency-key request)
                              :api-key-id (:id context)
                              :at (store/now-iso)}})
    {:review-id review-id :actor actor :created? true}))

(defn record!
  "Record one scoped API review. The dedupe tuple is
   [event-id api-key-id idempotency-key]. Identical completed retries return the
   original review id; a reused key with a different body refuses."
  [event-id submission-id context params actor]
  (locking write-lock
    (require-submission! event-id submission-id)
    (let [request (assoc (normalized-review params) :submission-id submission-id)
          existing (existing-request event-id (:id context)
                                     (:idempotency-key request))]
      (if existing
        (prior-result existing request)
        (case (:scope context)
          :review-bot (record-bot! event-id submission-id context request actor)
          :reviewer (record-human! event-id submission-id context request actor)
          (refuse! :insufficient-scope
                   "This endpoint requires reviewer or review-bot scope."
                   {:scope (:scope context)}))))))
