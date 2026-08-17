(ns cfp-scheduler-killer.domain.review-plan
  "Pure presenter-visibility policy decisions and read projections."
  (:require
   [cfp-scheduler-killer.domain.committees :as committees]
   [clojure.string :as str]))

(defn- event-by-id [state event-id]
  (some #(when (= event-id (:id %)) %) (vals (:events state))))

(defn- rejection [type message data]
  {:facts [] :rejection (merge {:type type :message message} data)})

(defn- chair? [state event-id person-id]
  (= "chair" (some-> (committees/role-on-event state event-id person-id)
                     name
                     str/lower-case)))

(def presenter-visibility-mode-definitions
  [{:id "visible"
    :label "Visible to reviewers"
    :meaning "Presenter identity is visible throughout review."
    :before-vote "visible"
    :after-vote "visible"
    :search-behavior "Presenter identity participates in search."
    :sort-behavior "Presenter and organization sorting are available."}
   {:id "hidden"
    :label "Hidden throughout review"
    :meaning "Reviewers do not see presenter identity while this policy remains active."
    :before-vote "hidden"
    :after-vote "hidden"
    :search-behavior "Presenter identity does not participate in search."
    :sort-behavior "Presenter and organization sorting are unavailable."}
   {:id "reveal-after-vote"
    :label "Hidden until each reviewer votes"
    :meaning "Identity is revealed independently after each reviewer submits a rating."
    :before-vote "hidden"
    :after-vote "visible"
    :reveal-trigger "first-recorded-rating"
    :search-behavior "Identity participates only for proposals this reviewer has revealed."
    :sort-behavior "Presenter and organization sorting remain unavailable while rows are hidden."}])

(def presenter-visibility-policy-definition
  {:id "review.presenter-visibility"
   :label "Presenter visibility"
   :mutable true
   :allowed-modes presenter-visibility-mode-definitions
   :guarantees
   ["Structured presenter fields are removed before reviewer rendering and search."
    "One reviewer's rating does not reveal identity to another reviewer."]
   :limitations
   ["A name written into the proposal title or abstract cannot be automatically redacted."
    "Changing back to hidden cannot make reviewers forget identities they already saw."]
   :explanation
   {:summary "Controls when reviewers can see the people behind a proposal."
    :for-reviewer "The active mode determines when presenter identity appears."
    :for-submitter "The event publishes exactly when reviewers can see your identity."
    :for-organizer "Chairs retain identity access and can set the event default or override it per round."}})

(def ^:private presenter-visibility-mode-ids
  (set (map :id presenter-visibility-mode-definitions)))

(defn- coherent-presenter-visibility-policy?
  [policy]
  (and (map? policy)
       (contains? presenter-visibility-mode-ids (:mode policy))
       (nat-int? (:version policy))))

(defn- legacy-presenter-visibility-policy
  "Read only decisions that an older event actually recorded. Ambiguous or
   contradictory settings return nil so the caller can fail closed."
  [state event-id]
  (let [review-plan (get-in state [:review-plans event-id])
        settings (:settings (event-by-id state event-id))
        blind? (:blind? review-plan)
        hide? (:hide-presenter-info settings)
        reveal? (:reveal-after-vote settings)]
    (cond
      (boolean? blind?)
      {:mode (if blind? "hidden" "visible") :version 1}

      (true? hide?)
      {:mode (if (true? reveal?) "reveal-after-vote" "hidden") :version 0}

      (and (false? hide?) (not (true? reveal?)))
      {:mode "visible" :version 0}

      :else nil)))

(defn default-presenter-visibility-policy
  "The event-wide fallback used by rounds that predate round-scoped policy.

   Identity disclosure is irreversible. A missing or incoherent policy therefore
   fails closed to hidden; only a coherent canonical policy or an explicit legacy
   decision may make presenter identity visible."
  [state event-id]
  (let [review-plan (get-in state [:review-plans event-id])
        policy (:presenter-visibility review-plan)]
    (cond
      (coherent-presenter-visibility-policy? policy) policy
      (contains? review-plan :presenter-visibility)
      {:mode "hidden"
       :version (if (nat-int? (:version policy)) (:version policy) 0)}
      :else (or (legacy-presenter-visibility-policy state event-id)
                {:mode "hidden" :version 0}))))

(defn presenter-visibility-policy
  "The event-level presenter visibility policy."
  [state event-id]
  (default-presenter-visibility-policy state event-id))

(defn- reviewer-has-rated?
  [state submission-id person-id]
  (contains? (:ratings state) [submission-id person-id]))

;; INTENT: REV-VIS-001 — under reveal-after-vote, a reviewer must not see a
;; presenter's identity until that reviewer has recorded a rating (chairs are
;; always exempt). This one predicate is the enforcement half of the policy
;; whose explanation half is presenter-visibility-policy-definition above.
(defn presenter-visible?
  [state event-id person-id submission]
  (or (chair? state event-id person-id)
      (case (:mode (presenter-visibility-policy state event-id))
        "visible" true
        "hidden" false
        "reveal-after-vote" (reviewer-has-rated? state (:id submission) person-id)
        false)))

(defn blind-review?
  [state event-id]
  (not= "visible" (:mode (presenter-visibility-policy state event-id))))

(defn- identity-answer-key? [k]
  (let [s (name k)]
    (or (str/starts-with? s "speaker-")
        (contains? #{"speakers" "business-co-presenter" "prior-talk-video"} s))))

;; INTENT: REV-VIS-002 — blind review removes structured presenter identity
;; before a reviewer or anonymous public representation can render it.
(defn blind-submission
  "Return a reviewer-safe read projection. The authoritative row is untouched;
   identity-bearing speaker fields, captured-by source, and co-speaker count
   disappear only at the presentation boundary."
  [row]
  (-> row
      (assoc :speakers [{:name "Anonymous speaker" :anonymous? true}])
      (dissoc :speaker :speaker-id :speaker-ids :source)
      (update :answers
              (fn [answers]
                (into {}
                      (remove (comp identity-answer-key? key))
                      (or answers {}))))))

(defn project-submission
  [state event-id person-id row]
  (if (presenter-visible? state event-id person-id row)
    row
    (blind-submission row)))

(defn- valid-presenter-visibility-mode? [mode]
  (contains? presenter-visibility-mode-ids mode))

(defn- command-guard [state event-id actor-person-id]
  (cond
    (nil? (event-by-id state event-id))
    (rejection :no-such-event (str "No such event: " event-id) {:event-id event-id})

    (not (chair? state event-id actor-person-id))
    (rejection :chair-required "Only an event chair can configure its review plan."
               {:event-id event-id :person-id actor-person-id})))

(defn decide-set-presenter-visibility
  [state {:keys [event-id mode expected-version actor-person-id actor at]}]
  (let [guard (command-guard state event-id actor-person-id)
        current (default-presenter-visibility-policy state event-id)
        current-version (:version current)]
    (cond
      guard guard
      (not (contains? presenter-visibility-mode-ids mode))
      (rejection :invalid-presenter-visibility
                 "Presenter visibility must be one of the advertised policy modes."
                 {:event-id event-id
                  :value mode
                  :allowed-modes (mapv :id presenter-visibility-mode-definitions)})
      (and (some? expected-version)
           (not= expected-version current-version))
      (rejection :stale-policy-version
                 "The presenter-visibility policy changed after it was read."
                 {:event-id event-id
                  :expected-version expected-version
                  :current-version current-version})
      (= mode (:mode current))
      {:facts []
       :result {:event-id event-id :policy current}}
      :else
      (let [next-policy {:mode mode :version (inc current-version)}]
        {:facts [{:type "review.presenter-visibility-set"
                  :at at
                  :actor actor
                  :event-id event-id
                  :payload {:policy-id "review.presenter-visibility"
                            :mode mode
                            :version (:version next-policy)}}]
         :result {:event-id event-id :policy next-policy}}))))
