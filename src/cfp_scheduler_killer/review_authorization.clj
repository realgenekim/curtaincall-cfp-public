(ns cfp-scheduler-killer.review-authorization
  "Sealed authority for writing review evidence to one exact submission.

   A reviewer seeing a proposal is not proof that it is their work. The only
   constructors in this namespace consult the folded assignment projection;
   downstream rating functions consume the resulting sealed value instead of
   accepting live submission/person identifiers separately."
  (:require
   [cfp-scheduler-killer.domain.committees :as committees]
   [clojure.string :as str]))

(def ^:private proof-seal (Object.))

(deftype ^:private ReviewWriteProof
         [submission-id person-id authority seal])

(defn- refuse!
  [type message data]
  (throw (ex-info message
                  (merge {:type type :user-message message} data))))

;; INTENT: AUTHZ-002 — committee membership authorizes review evidence;
;; assignment and track scope shape default queues, never write permission.
(defn require-write-proof!
  "Issue sealed authority for `person-id` to write evidence on `submission-id`.

   Any active committee member may contribute to any submission in the event.
   The proof is opaque and bound to both literal identifiers."
  [state submission-id person-id]
  (let [submission (get-in state [:submissions submission-id])
        event-id (:event-id submission)
        role (when submission
               (committees/role-on-event state event-id person-id))
        recused? (boolean
                   (get-in state [:review-recusals [submission-id person-id]]))]
    (cond
      (nil? submission)
      (refuse! :no-such-submission
               (str "No such submission: " submission-id)
               {:submission-id submission-id})

      (nil? role)
      (refuse! :not-on-review-committee
               "Only a review committee member for this event can do that."
               {:event-id event-id :person-id person-id})

      recused?
      (refuse! :reviewer-recused
               "Restore this review before participating in it."
               {:submission-id submission-id :person-id person-id})

      :else
      (ReviewWriteProof. submission-id person-id :committee proof-seal))))

(defn proof-context
  "Return the literal identifiers sealed into a genuine write proof."
  [proof]
  (if (and (instance? ReviewWriteProof proof)
           (identical? proof-seal (.-seal ^ReviewWriteProof proof)))
    {:submission-id (.-submission-id ^ReviewWriteProof proof)
     :person-id (.-person-id ^ReviewWriteProof proof)
     :authority (.-authority ^ReviewWriteProof proof)}
    (refuse! :invalid-review-write-proof
             "Review evidence requires a committee proof."
             {})))

(defn require-current-context!
  "Consume a genuine proof only while its literal authority is still current.

   This closes the narrow concurrent-revocation window: retaining a proof does
   not retain committee membership after the chair removes it."
  [state proof]
  (let [{:keys [submission-id person-id]} (proof-context proof)]
    (proof-context (require-write-proof! state submission-id person-id))))
