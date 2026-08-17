(ns cfp-scheduler-killer.review-assignments
  "Explicit reviewer work over the shared review board.

   Assignments answer both “what should I review next?” and whether a reviewer
   may add score evidence. They never change who may see a submission; chairs
   retain their role-specific write exemption and `:all` remains a read lens."
  (:require
   [cfp-scheduler-killer.domain.review-assignments :as decisions]
   [cfp-scheduler-killer.review-work :as review-work]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.store :as store]
   [clojure.string :as str]
   [com.fulcrologic.guardrails.core :refer [=> >defn]]
   [taoensso.timbre :as log])
  (:import
   (java.math BigInteger)
   (java.nio.charset StandardCharsets)
   (java.security MessageDigest)))

(def ^:private receipt-prefix "a8606000-")
(defonce ^:private preview-receipts (atom {}))

(defn assignments-for-submission
  "Active reviewer assignments for one submission, oldest first."
  [submission-id]
  (let [state (store/snapshot)]
    (->> (:review-assignments state)
         vals
         (filter #(and (= submission-id (:submission-id %))
                       (not (get-in state [:review-recusals
                                           [submission-id (:person-id %)]]))))
         (sort-by :assigned-at)
         vec)))

(defn assigned?
  [submission-id person-id]
  (contains? (:review-assignments (store/snapshot)) [submission-id person-id]))

(defn submissions-for-reviewer
  "The explicit work queue for one reviewer in one event."
  [event-id person-id]
  (let [state (store/snapshot)
        assigned-ids (->> (:review-assignments state)
                          keys
                          (keep (fn [[submission-id assigned-person-id]]
                                  (when (and (= person-id assigned-person-id)
                                             (not (get-in state [:review-recusals
                                                                 [submission-id person-id]])))
                                    submission-id)))
                          set)]
    (->> (store/submissions-for-event event-id)
         (filter #(contains? assigned-ids (:id %)))
         vec)))

(defn scoped-submissions
  "Apply an optional work-queue lens. `:all` is deliberately the default."
  [event-id person-id scope]
  (if (= :assigned scope)
    (submissions-for-reviewer event-id person-id)
    (store/submissions-for-event event-id)))

(defn progress-for-reviewer
  "Assigned/completed counts. A current rating completes an assigned review."
  [event-id person-id]
  (review-work/progress-for-reviewer event-id person-id))

(defn- accepted!
  [{:keys [facts result rejection]}]
  (when rejection
    (throw (ex-info (:message rejection) (dissoc rejection :message))))
  (doseq [fact facts]
    (store/append! fact))
  result)

(defn eligible-submission-ids
  "Submission ids in the selected track, preserving board order. A blank
   track means the whole event."
  [event-id track]
  (mapv :id
        (reviews/filter-board
          (reviews/enriched-for-event event-id)
          {:track (when-not (str/blank? (str track)) track)})))

(defn- distribution-decision
  [state event-id submission-ids person-ids cap actor]
  (decisions/decide-distribute
    state
    {:event-id event-id
     :submission-ids submission-ids
     :person-ids (vec (distinct person-ids))
     :cap cap
     :actor actor
     :at (store/now-iso)}))

(defn- receipt-id? [person-id]
  (str/starts-with? (str person-id) receipt-prefix))

(defn- digest [value]
  (let [message-digest (MessageDigest/getInstance "SHA-256")
        bytes (.digest message-digest
                       (.getBytes (pr-str value) StandardCharsets/UTF_8))]
    (format "%064x" (BigInteger. 1 bytes))))

(defn- receipt-id [kind value]
  (let [hash (digest value)]
    (str receipt-prefix
         kind (subs hash 0 3) "-"
         (subs hash 3 7) "-"
         (subs hash 7 11) "-"
         (subs hash 11 23))))

(defn- receipt-kind [receipt-id]
  (when (receipt-id? receipt-id)
    (nth (str receipt-id) (count receipt-prefix))))

(defn- distribution-context [event-id track person-ids cap actor]
  [:abs-06-v1 event-id (or track "") (vec person-ids) cap actor])

(defn- event-assignment-pairs [state event-id]
  (->> (:review-assignments state)
       keys
       (filter (fn [[submission-id _person-id]]
                 (= event-id
                    (get-in state [:submissions submission-id :event-id]))))
       sort
       vec))

(defn- result-fingerprint [result person-ids]
  [(mapv (juxt :submission-id :person-id) (:assignments result))
   (mapv (juxt :submission-id :reason) (:unassigned result))
   (mapv (fn [person-id]
           [person-id (get (:loads-before result) person-id 0)])
         person-ids)
   (mapv (fn [person-id]
           [person-id (get (:loads-after result) person-id 0)])
         person-ids)])

(defn- receipt-ids
  [state context submission-ids person-ids result]
  (let [state-id (receipt-id \a [context
                                 submission-ids
                                 (event-assignment-pairs state
                                                         (second context))
                                 (result-fingerprint result person-ids)])
        assignment-ids (mapv (fn [{:keys [submission-id person-id]}]
                               (receipt-id \b [context submission-id person-id]))
                             (:assignments result))
        manifest-id (receipt-id \c [context state-id assignment-ids])]
    (into [state-id manifest-id] assignment-ids)))

(defn- remember-preview! [context receipts]
  (swap! preview-receipts
         (fn [previews]
           (let [previews (if (>= (count previews) 256) {} previews)]
             (assoc previews context receipts)))))

(defn- cached-receipts [context]
  (get @preview-receipts context))

(defn- stale-preview! []
  (throw (ex-info
           "This distribution no longer matches its preview. Preview it again before confirming."
           {:type :stale-distribution-preview})))

(defn- retry-receipt?
  [state event-id context provided-receipts]
  (let [state-ids (filterv #(= \a (receipt-kind %)) provided-receipts)
        manifest-ids (filterv #(= \c (receipt-kind %)) provided-receipts)
        assignment-ids (filterv #(= \b (receipt-kind %)) provided-receipts)
        active-assignment-ids (->> (event-assignment-pairs state event-id)
                                   (map (fn [[submission-id person-id]]
                                          (receipt-id
                                            \b
                                            [context submission-id person-id])))
                                   set)
        manifest (when (and (= 1 (count state-ids))
                            (= 1 (count manifest-ids)))
                   (receipt-id \c [context (first state-ids) assignment-ids]))]
    (and (seq assignment-ids)
         (= (count provided-receipts)
            (+ (count state-ids) (count manifest-ids) (count assignment-ids)))
         (= manifest (first manifest-ids))
         (every? active-assignment-ids assignment-ids))))

(defn preview-distribution
  "Read-only preview over the current store snapshot. Appends nothing and
   returns opaque confirmation ids that pin the exact plan."
  [event-id track person-ids cap actor]
  (let [state (store/snapshot)
        person-ids (vec (distinct person-ids))
        submission-ids (eligible-submission-ids event-id track)
        context (distribution-context event-id track person-ids cap actor)
        {:keys [result rejection]} (distribution-decision
                                     state event-id submission-ids
                                     person-ids cap actor)]
    (when rejection
      (throw (ex-info (:message rejection) (dissoc rejection :message))))
    (let [receipts (receipt-ids state context submission-ids person-ids result)
          result (assoc result :confirmation-ids (into person-ids receipts))]
      (remember-preview! context receipts)
      result)))

(defn distribute!
  "Append exactly one previously previewed distribution. The opaque receipt
   pins event, track, cap, reviewers, actor, store state, and assignment plan.
   Repeating a successful confirmation appends nothing."
  [event-id track person-ids cap actor]
  (let [person-ids (vec person-ids)
        selected-person-ids (->> person-ids
                                 (remove receipt-id?)
                                 distinct
                                 vec)
        supplied-receipts (filterv receipt-id? person-ids)
        context (distribution-context event-id track selected-person-ids cap actor)
        provided-receipts (if (seq supplied-receipts)
                            supplied-receipts
                            (cached-receipts context))
        state (store/snapshot)
        submission-ids (eligible-submission-ids event-id track)
        {:keys [facts result rejection]} (distribution-decision
                                           state event-id submission-ids
                                           selected-person-ids cap actor)]
    (when rejection
      (throw (ex-info (:message rejection) (dissoc rejection :message))))
    (let [expected-receipts (receipt-ids state context submission-ids
                                         selected-person-ids result)
          fresh? (= (frequencies provided-receipts)
                    (frequencies expected-receipts))
          retry? (and (not fresh?)
                      (retry-receipt? state event-id context provided-receipts))]
      (when-not (or fresh? retry?)
        (stale-preview!))
      (when (and fresh? (seq facts))
        (store/append-all! facts)
        (log/info :reviewers-bulk-distributed
                  :event-id event-id
                  :track track
                  :reviewer-count (count selected-person-ids)
                  :assignment-count (count facts)
                  :cap cap))
      (let [result (if retry?
                     (-> result
                         (update :unassigned into
                                 (map #(assoc % :reason :not-in-preview)
                                      (:assignments result)))
                         (assoc :assignments []
                                :loads-after (:loads-before result)))
                     result)]
        (assoc result :confirmation-ids
               (into selected-person-ids provided-receipts))))))

(>defn assign!
       "Assign one event committee reviewer to one submission. Repeating the
   same assignment is idempotent and does not append another fact."
       [submission-id person-id actor]
       [string? string? string? => map?]
       (let [state (store/snapshot)
             decision (decisions/decide-assign state {:submission-id submission-id
                                                      :person-id person-id
                                                      :actor actor
                                                      :at (store/now-iso)})
             result (accepted! decision)
             result (if (seq (:facts decision))
                      (get-in (store/snapshot)
                              [:review-assignments [submission-id person-id]])
                      result)]
         (when (seq (:facts decision))
           (log/info :reviewer-assigned
                     :event-id (get-in state [:submissions submission-id :event-id])
                     :submission-id submission-id
                     :person-id person-id))
         result))

(>defn unassign!
       "Remove an active assignment by appending a fact. Missing assignments
   are idempotent no-ops."
       [submission-id person-id actor]
       [string? string? string? => map?]
       (let [state (store/snapshot)
             decision (decisions/decide-unassign state {:submission-id submission-id
                                                        :person-id person-id
                                                        :actor actor
                                                        :at (store/now-iso)})
             result (accepted! decision)]
         (when (seq (:facts decision))
           (log/info :reviewer-unassigned
                     :event-id (get-in state [:submissions submission-id :event-id])
                     :submission-id submission-id
                     :person-id person-id))
         result))
