(ns cfp-scheduler-killer.submissions-test
  "Submission slice. The validation tests matter most: they prove the schema is
   DERIVED from the field defs, so adding a form field cannot leave validation
   behind."
  (:require
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.seed :as seed]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as sub]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.test :refer [deftest is testing use-fixtures]])
  (:import
   (java.time Instant LocalDate LocalDateTime)))

(use-fixtures :each with-temp-store)

(def form-fields seed/eais-charlotte-form)

(defn- make-event!
  ([] (make-event! {}))
  ([overrides]
   (events/create-eais-event!
     (merge {:name "CFP Test Summit"
             :slug "cfp-test"
             :tz "America/New_York"
             :starts-on (LocalDate/of 2026 10 14)
             :ends-on (LocalDate/of 2026 10 15)
             :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
             :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)
             :support-email "support@example.com"}
            overrides)
     "kaocha")))

(def good-params
  {:answer-talk-title "Scaling AI at BigCo"
   :answer-abstract "How we did it, and what broke."
   :answer-session-format "Experience Report"
   :answer-track "Developer Practices"
   :answer-org-size ">10,000"
   :answer-industry "Insurance"
   :answer-ai-transformation-history "Started 2023; three systems in production."
   :answer-measurable-outcomes "40% faster cycle time, $2M saved."
   :answer-notes-to-committee "Please don't schedule me first thing."
   :speaker-name "Ann Perry"
   :speaker-email "Ann@Example.COM"
   :speaker-title "VP Engineering"
   :speaker-org "BigCo"
   :speaker-bio "Ann runs platform engineering at BigCo."})

(defn- answers-of [params] (sub/parse-answers form-fields params))
(defn- speaker-of [params] (sub/parse-speaker params))

(defn- submit! [event params]
  (sub/create-submission! event (answers-of params) (speaker-of params) "form" "speaker"))

;; --- Field-def derivation ---------------------------------------------------

(deftest form-shape-test
  (testing "the session fields and the speaker group are separated"
    (is (= 12 (count (sub/session-fields form-fields))))
    (is (= :speakers (:id (sub/speaker-group form-fields)))))

  (testing "the private field is excluded from the public projection"
    (is (= 11 (count (sub/public-fields form-fields))))
    (is (not-any? #(= :notes-to-committee (:id %)) (sub/public-fields form-fields)))
    (is (some #(= :notes-to-committee (:id %)) (sub/session-fields form-fields))
        "…but it is still collected from the speaker")))

(deftest conditional-field-algebra-test
  (let [conditional {:id :panel-context
                     :type :textarea
                     :label "Panel expertise"
                     :required true
                     :show-when {:field-id "session-format" :equals "Panel"}}
        chained {:id :advanced-context
                 :type :text
                 :label "Advanced context"
                 :show-when {:field-id "panel-context" :equals "Expert"}}
        fields (conj (vec form-fields) conditional)
        chained-fields (conj fields chained)
        hidden-params (assoc good-params :answer-session-format "Experience Report"
                             :answer-panel-context "stale hidden value")
        visible-params (assoc good-params :answer-session-format "Panel")
        hidden-answers (sub/parse-answers fields hidden-params)
        visible-answers (sub/parse-answers fields visible-params)
        speaker (speaker-of good-params)]
    (testing "one predicate determines both visibility and answer collection"
      (is (false? (sub/field-visible? conditional hidden-answers)))
      (is (true? (sub/field-visible? conditional visible-answers)))
      (is (not (contains? hidden-answers :panel-context))
          "a stale hidden browser value is discarded")
      (is (contains? visible-answers :panel-context)))
    (testing "chained conditions cannot see answers from hidden controllers"
      (let [answers (sub/parse-answers
                      chained-fields
                      (assoc hidden-params
                             :answer-panel-context "Expert"
                             :answer-advanced-context "stale downstream value"))]
        (is (not (contains? answers :panel-context)))
        (is (not (contains? answers :advanced-context)))))
    (testing "a hidden required field is not an impossible validation blocker"
      (is (nil? (sub/validation-errors fields hidden-answers speaker)))
      (is (contains? (sub/validation-errors fields visible-answers speaker)
                     :panel-context)))
    (testing "the visible answer validates and survives parsing"
      (let [answers (sub/parse-answers fields
                                       (assoc visible-params :answer-panel-context
                                              "I have moderated three panels."))]
        (is (= "I have moderated three panels." (:panel-context answers)))
        (is (nil? (sub/validation-errors fields answers speaker)))))))

(deftest validation-derives-from-field-defs-test
  (testing "every required field reports its own label when blank"
    (let [errs (sub/validation-errors form-fields (answers-of {}) (speaker-of {}))]
      (is (= ["Talk title is required."] (:talk-title errs)))
      (is (= ["Abstract is required."] (:abstract errs)))
      (is (contains? errs :session-format))
      (is (contains? errs :track))
      (is (contains? errs :org-size))
      (is (contains? errs :industry))
      (testing "and NOT malli's raw 'missing required key'"
        (is (not-any? #(= "missing required key" %) (mapcat val errs))))))

  (testing "optional fields are silent when blank"
    (let [errs (sub/validation-errors form-fields (answers-of good-params) (speaker-of good-params))]
      (is (nil? errs))))

  (testing "a select only accepts its declared options"
    (let [errs (sub/validation-errors
                 form-fields
                 (answers-of (assoc good-params :answer-session-format "Keynote"))
                 (speaker-of good-params))]
      (is (contains? errs :session-format))))

  (testing "a url field rejects a bare domain"
    (is (contains? (sub/validation-errors
                     form-fields
                     (answers-of (assoc good-params :answer-prior-talk-video "youtube.com/x"))
                     (speaker-of good-params))
                   :prior-talk-video)))

  (testing "max-length is reported with the count the speaker actually typed"
    (let [errs (sub/validation-errors
                 form-fields
                 (answers-of (assoc good-params :answer-talk-title (apply str (repeat 300 "x"))))
                 (speaker-of good-params))]
      (is (= ["Talk title is 300 characters — we have room for 255. Trim it a little."] (:talk-title errs)))))

  (testing "a NEW required field on the form is validated with no code change"
    (let [extended (conj form-fields {:id :new-question :type :text
                                      :label "Your favourite colour" :required true})
          errs (sub/validation-errors extended (sub/parse-answers extended good-params)
                                      (speaker-of good-params))]
      (is (= ["Your favourite colour is required."] (:new-question errs))))))

(deftest speaker-validation-test
  (testing "speaker errors are namespaced so they can't collide with form fields"
    (let [errs (sub/validation-errors form-fields (answers-of good-params) (speaker-of {}))]
      (is (contains? errs :speaker-name))
      (is (contains? errs :speaker-email))
      (is (contains? errs :speaker-bio))
      (is (not (contains? errs :name)))
      (is (not (contains? errs :bio)))))

  (testing "the email is lowercased on the way in"
    (is (= "ann@example.com" (:email (speaker-of good-params))))))

;; --- CFP window -------------------------------------------------------------

(deftest cfp-state-test
  (let [t #(Instant/parse %)]
    (testing "before the window opens"
      (let [e (make-event! {:cfp-opens-at (LocalDateTime/of 2026 8 10 0 0)
                            :cfp-closes-at (LocalDateTime/of 2026 9 15 0 0)})]
        (is (= :not-open-yet (sub/cfp-state e (t "2026-08-01T00:00:00Z"))))
        (is (= :open (sub/cfp-state e (t "2026-08-20T00:00:00Z"))))
        (is (= :closed (sub/cfp-state e (t "2026-10-01T00:00:00Z"))))))

    (testing "no window at all means open — a missing date is not a rejection"
      (let [e (make-event! {:slug "no-window" :cfp-opens-at nil :cfp-closes-at nil})]
        (is (= :open (sub/cfp-state e)))
        (is (true? (sub/accepting? e)))))))

(deftest closed-cfp-refuses-submissions-test
  (let [event (make-event! {:cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
                            :cfp-closes-at (LocalDateTime/of 2020 2 1 0 0)})
        thrown (try (submit! event good-params) nil
                    (catch clojure.lang.ExceptionInfo e e))]
    (testing "a closed CFP throws rather than quietly accepting"
      (is (= :cfp-closed (:type (ex-data thrown)))))
    (testing "and nothing was appended"
      (is (zero? (sub/count-for-event (:id event)))))))

;; --- Creating ---------------------------------------------------------------

(deftest create-submission-test
  (let [event (make-event!)
        s (submit! event good-params)]

    (testing "the submission carries the answers, keyed by field id"
      (is (= "Scaling AI at BigCo" (get-in s [:answers :talk-title])))
      (is (= "Experience Report" (get-in s [:answers :session-format])))
      (is (= "Pending" (:status s)))
      (is (= "form" (:source s)))
      (is (false? (:priority s)))
      (is (nil? (:notified-at s))))

    (testing "the form defs are SNAPSHOT onto the submission"
      (is (= (count form-fields) (count (:form-snapshot s))))
      (is (= "talk-title" (name (:id (first (:form-snapshot s)))))))

    (testing "the speaker block is stored as submitted, with a person id"
      (let [sp (first (:speakers s))]
        (is (= "Ann Perry" (:name sp)))
        (is (= "ann@example.com" (:email sp)))
        (is (= "BigCo" (:org sp)))
        (is (some? (:person-id sp)))
        (is (= 0 (:position sp)))))

    (testing "a person was created for the speaker"
      (is (some? (store/person-by-email "ann@example.com"))))

    (testing "the events appended are person.created then submission.created"
      ;; …followed by the confirmation letter, which the comms slice added.
      (is (= ["event.created" "committee.created" "form.installed"
              "person.created" "submission.created" "email.queued"]
             (mapv :type (events/log-for-event (:id event))))))

    (testing "the speaker got exactly one confirmation, recorded not claimed sent"
      (let [c (last (events/log-for-event (:id event)))]
        (is (= "email.queued" (:type c)))
        (is (= "ann@example.com" (get-in c [:payload :to])))))

    (testing "and it all survives a reload — the log IS the record"
      (store/load!)
      (let [reloaded (first (sub/for-event (:id event)))]
        (is (= (:id s) (:id reloaded)))
        (is (= "Scaling AI at BigCo" (get-in reloaded [:answers :talk-title])))
        (is (= (count form-fields) (count (:form-snapshot reloaded))))
        (is (= "ann@example.com" (:email (first (:speakers reloaded)))))))))

(deftest form-snapshot-is-immune-to-later-edits-test
  (let [event (make-event!)
        s (submit! event good-params)
        original-count (count (:form-snapshot s))]
    (testing "editing the live form does not rewrite an existing submission"
      ;; Install a new form version on the same event.
      (store/append! {:type "form.installed"
                      :actor "kaocha"
                      :payload {:id (store/new-id)
                                :event-id (:id event)
                                :fields (conj form-fields
                                              {:id :brand-new :type :text
                                               :label "Brand new" :required true})
                                :created-at (store/now-iso)}})
      (is (= (inc original-count) (count (:fields (events/form-for-event (:id event))))))
      (is (= original-count (count (:form-snapshot (sub/by-id (:id s)))))
          "the submitted talk still validates and renders against the form it saw"))))

(deftest submission-cap-test
  (let [event (make-event!)]
    (testing "the cap comes from the event's settings"
      (is (= 3 (sub/submission-cap event))))

    (dotimes [i 3]
      (submit! event (assoc good-params :answer-talk-title (str "Talk " i))))

    (testing "three submissions are allowed"
      (is (= 3 (sub/count-for-event (:id event))))
      (is (= 3 (sub/submission-count-for-email (:id event) "ann@example.com"))))

    (testing "the fourth is refused with a typed error"
      (let [thrown (try (submit! event good-params) nil
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :cap-reached (:type (ex-data thrown))))
        (is (= 3 (:cap (ex-data thrown))))))

    (testing "and the cap is PER PERSON, not per event"
      (is (some? (submit! event (assoc good-params
                                       :speaker-email "someone-else@example.com"))))
      (is (= 4 (sub/count-for-event (:id event))))
      (is (= 2 (sub/unique-speaker-count (:id event)))))))

(deftest repeat-speaker-reuses-person-test
  (let [event (make-event!)
        a (submit! event good-params)
        b (submit! event (assoc good-params :answer-talk-title "Second talk"
                                :speaker-name "A. Perry"))]
    (testing "the same email is the same person across submissions"
      (is (= (:person-id (first (:speakers a)))
             (:person-id (first (:speakers b)))))
      (is (= 1 (count (:people (store/snapshot))))))
    (testing "person.created is appended only once"
      (is (= 1 (count (filter #(= "person.created" (:type %))
                              (events/log-for-event (:id event)))))))
    (testing "but each submission keeps the name AS SUBMITTED that day"
      (is (= "Ann Perry" (:name (first (:speakers a)))))
      (is (= "A. Perry" (:name (first (:speakers b))))))))

;; --- Sinks ------------------------------------------------------------------

(deftest submission-fires-sinks-test
  (let [captured (atom [])]
    (reset! store/runtime-sinks [{:type :test :captured captured
                                  :events ["submission.created"]}])
    (let [event (make-event!)
          s (submit! event good-params)]
      (Thread/sleep 350)
      (testing "a sink subscribed to submission.created receives it"
        (is (= 1 (count @captured)))
        (is (= "submission.created" (:type (first @captured)))))

      (testing "the delivered event carries the COMPLETE record"
        (let [p (:payload (first @captured))]
          (is (= (:id s) (:id p)))
          (is (= "Scaling AI at BigCo" (get-in p [:answers :talk-title])))
          (is (= "ann@example.com" (:email (first (:speakers p)))))
          (is (seq (:form-snapshot p)))
          (is (= "form" (:source p)))))

      (testing "and the Slack sentence reads like a human wrote it"
        (is (= "New submission: Scaling AI at BigCo from Ann Perry (BigCo)"
               (store/slack-text (first @captured))))))))

(deftest multi-speaker-roles-validation-and-persistence-test
  (let [params (merge good-params
                      {:speaker-2-role "Co-speaker"
                       :speaker-2-name "Rae Coauthor"
                       :speaker-2-email "RAE@example.com"
                       :speaker-2-title "Chief Architect"
                       :speaker-2-org "PartnerCo"
                       :speaker-2-bio "Rae led the technical implementation."})
        speakers (sub/parse-speakers params)]
    (testing "the public vocabulary becomes an ordered, explicitly labeled vector"
      (is (= ["Primary speaker" "Co-speaker"] (mapv :role speakers)))
      (is (= ["ann@example.com" "rae@example.com"] (mapv :email speakers)))
      (is (nil? (sub/validation-errors-for-speakers
                  form-fields (answers-of params) speakers))))
    (testing "an incomplete or duplicate additional identity fails at its own fields"
      (let [incomplete (sub/parse-speakers (dissoc params :speaker-2-bio))
            duplicate (sub/parse-speakers (assoc params :speaker-2-email "ANN@example.com"))]
        (is (contains? (sub/validation-errors-for-speakers
                         form-fields (answers-of params) incomplete)
                       :speaker-2-bio))
        (is (contains? (sub/validation-errors-for-speakers
                         form-fields (answers-of params) duplicate)
                       :speaker-2-email))))
    (testing "one domain command creates both people and one complete submission fact"
      (let [event (make-event!)
            submission (sub/create-submission!
                         event (answers-of params) speakers "form" "speaker")]
        (is (= 2 (count (:speakers submission))))
        (is (= [0 1] (mapv :position (:speakers submission))))
        (is (= ["Primary speaker" "Co-speaker"]
               (mapv :role (:speakers submission))))
        (is (= #{"ann@example.com" "rae@example.com"}
               (set (map :email (:speakers submission)))))
        (is (some? (store/person-by-email "ann@example.com")))
        (is (some? (store/person-by-email "rae@example.com")))
        (is (= ["person.created" "person.created" "submission.created"]
               (->> (events/log-for-event (:id event))
                    (map :type)
                    (filter #(contains? #{"person.created" "submission.created"} %))
                    vec)))
        (store/load!)
        (is (= ["Ann Perry" "Rae Coauthor"]
               (mapv :name (:speakers (sub/by-id (:id submission))))))))))
