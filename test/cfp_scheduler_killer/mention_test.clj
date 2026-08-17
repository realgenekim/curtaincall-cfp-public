(ns cfp-scheduler-killer.mention-test
  "The MENTION primitive — a shoulder-tap between committee people. One
   mechanism, many uses: a reviewer/organizer @-mentions another committee
   person on a submission (\"can you look at this one?\"), that person gets a soft
   nudge on their board, and it is CROSS-ROOM — a mention can pull an expert from
   any track/committee of the event. It is an invitation, never an assignment:
   these tests pin that a mention is a pure additive fact that never gates
   visibility or rating."
  (:require
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.review-assignments :as review-assignments]
   [cfp-scheduler-killer.review-authorization :as review-authorization]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.seed :as seed]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as sub]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.test :refer [deftest is testing use-fixtures]])
  (:import
   (java.time LocalDate LocalDateTime)))

(use-fixtures :each with-temp-store)

(def form-fields seed/eais-charlotte-form)

(defn- make-event! []
  (events/create-eais-event!
    {:name "Mention Test Summit"
     :slug "mention-test"
     :tz "America/New_York"
     :starts-on (LocalDate/of 2026 10 14)
     :ends-on (LocalDate/of 2026 10 15)
     :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
     :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)
     :support-email "support@example.com"}
    "kaocha"))

(defn- second-committee!
  "A DISTINCT committee on the event — the other room, for cross-room proof."
  [event name*]
  (let [id (store/new-id)]
    (store/append! {:type "committee.created" :actor "kaocha"
                    :payload {:id id
                              :event-id (:id event)
                              :name name*
                              :scope {:all true}
                              :coverage-target 2
                              :created-at (store/now-iso)}})
    id))

(defn- add-member! [committee-id name* email]
  (:person-id (committees/add-member! committee-id
                                      {:name name* :email email :role "reviewer"}
                                      "kaocha")))

(defn- a-submission! [event]
  (:id (sub/create-submission!
         event
         (sub/parse-answers form-fields
                            {:answer-talk-title "Scaling AI at BigCo"
                             :answer-abstract "How we did it, and what broke."
                             :answer-session-format "Experience Report"
                             :answer-track "Developer Practices"})
         (sub/parse-speaker {:speaker-name "Ann Perry" :speaker-email "ann@example.com"})
         "form" "speaker")))

;; --- The core slice ---------------------------------------------------------

(deftest mention-appends-a-fact-and-projects-to-the-recipient
  (testing "a mention appends `submission.mention` and lands on the recipient's shelf"
    (let [event (make-event!)
          committee-a (:id (first (events/committees-for-event (:id event))))
          reviewer (add-member! committee-a "Reviewer One" "rev1@example.com")
          expert   (add-member! committee-a "Expert Two" "exp2@example.com")
          sub-id (a-submission! event)
          m (reviews/mention! sub-id reviewer expert "you know this space" "rev1@example.com")]
      ;; the fact is in the log, keyed as its own event type
      (is (some #(= "submission.mention" (:type %)) (events/log-for-event (:id event)))
          "the mention is an event in the append-only log")
      ;; the fold projected it
      (is (= sub-id (:submission-id m)))
      (is (= reviewer (:from-person-id m)))
      (is (= expert (:to-person-id m)))
      (is (= "you know this space" (:note m)))
      ;; the recipient's shelf finds it, joined to its submission + who asked
      (let [shelf (reviews/mentions-shelf (:id event) expert)]
        (is (= 1 (count shelf)))
        (is (= sub-id (:submission-id (first shelf))))
        (is (= "Scaling AI at BigCo" (:title (first shelf))))
        (is (= "Reviewer One" (:from-name (first shelf))))
        (is (= "you know this space" (:note (first shelf)))))
      ;; the mentioner is NOT the recipient — nothing lands on their own shelf
      (is (empty? (reviews/mentions-shelf (:id event) reviewer))))))

(deftest mention-is-cross-room
  (testing "a mention can reach a committee person in ANOTHER room/track, and they receive it"
    (let [event (make-event!)
          committee-a (:id (first (events/committees-for-event (:id event))))
          committee-b (second-committee! event "Security Track Committee")
          reviewer (add-member! committee-a "Reviewer One" "rev1@example.com")
          ;; the expert sits on a DIFFERENT committee — the other room entirely
          expert   (add-member! committee-b "Expert Two" "exp2@example.com")
          sub-id (a-submission! event)]
      ;; the picker is the WHOLE-EVENT roster, so the cross-room expert is offered
      (let [pool (reviews/mentionable-people (:id event) reviewer)
            pool-ids (set (map :person-id pool))]
        (is (contains? pool-ids expert)
            "a person in another committee is mentionable (cross-room)")
        (is (not (contains? pool-ids reviewer))
            "you don't tap your own shoulder — the mentioner is excluded"))
      ;; and the cross-room mention is delivered to that expert's shelf
      (reviews/mention! sub-id reviewer expert nil "rev1@example.com")
      (let [shelf (reviews/mentions-shelf (:id event) expert)]
        (is (= 1 (count shelf)))
        (is (= sub-id (:submission-id (first shelf)))
            "the cross-room recipient receives the mention")))))

(deftest shelf-lists-the-right-submissions
  (testing "the shelf shows exactly the submissions this person was mentioned on"
    (let [event (make-event!)
          committee-a (:id (first (events/committees-for-event (:id event))))
          reviewer (add-member! committee-a "Reviewer One" "rev1@example.com")
          expert   (add-member! committee-a "Expert Two" "exp2@example.com")
          other    (add-member! committee-a "Third Party" "third@example.com")
          sub-1 (a-submission! event)
          sub-2 (a-submission! event)]
      ;; expert is tapped on sub-1 only; other is tapped on sub-2 only
      (reviews/mention! sub-1 reviewer expert nil "rev1@example.com")
      (reviews/mention! sub-2 reviewer other  nil "rev1@example.com")
      (let [expert-shelf (set (map :submission-id (reviews/mentions-shelf (:id event) expert)))
            other-shelf  (set (map :submission-id (reviews/mentions-shelf (:id event) other)))]
        (is (= #{sub-1} expert-shelf) "expert sees only the talk they were asked about")
        (is (= #{sub-2} other-shelf)  "other sees only theirs — shelves don't bleed")))))

(deftest mention-never-grants-rating-authority
  (testing "a mention is a PURE additive nudge — it changes no status, rating, or coverage"
    (let [event (make-event!)
          committee-a (:id (first (events/committees-for-event (:id event))))
          reviewer (add-member! committee-a "Reviewer One" "rev1@example.com")
          expert   (add-member! committee-a "Expert Two" "exp2@example.com")
          sub-id (a-submission! event)
          before (store/submission-by-id sub-id)
          before-target (reviews/coverage (:id event) 2)]
      (reviews/mention! sub-id reviewer expert "look please" "rev1@example.com")
      (let [after (store/submission-by-id sub-id)]
        ;; the submission itself is byte-for-byte unchanged
        (is (= (:status before) (:status after)) "status is untouched")
        (is (= before after) "the whole submission record is unchanged by a mention"))
      ;; ratings and coverage are unmoved — a mention is not a review
      (is (empty? (store/ratings-for-submission sub-id))
          "a mention creates no rating")
      (is (= before-target (reviews/coverage (:id event) 2))
          "coverage arithmetic ignores mentions entirely")
      ;; A mention is not assignment proof. Once the chair separately assigns
      ;; the work, the expert can rate normally.
      (review-assignments/assign! sub-id expert "chair@example.com")
      (reviews/rate!
        (review-authorization/require-write-proof!
          (store/snapshot) sub-id expert)
        "4" "exp2@example.com")
      (is (= 4.0 (:stars (store/rating-by sub-id expert)))
          "the mentioned person rates freely — an invitation, not an obligation"))))

(deftest mention-refuses-self-and-off-event
  (testing "you can't mention yourself, and you can't mention a non-member"
    (let [event (make-event!)
          committee-a (:id (first (events/committees-for-event (:id event))))
          reviewer (add-member! committee-a "Reviewer One" "rev1@example.com")
          sub-id (a-submission! event)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (reviews/mention! sub-id reviewer reviewer nil "rev1@example.com"))
          "self-mention is refused")
      (is (thrown? clojure.lang.ExceptionInfo
                   (reviews/mention! sub-id reviewer "not-a-real-person-id" nil "rev1@example.com"))
          "a recipient who isn't on the event is refused"))))
