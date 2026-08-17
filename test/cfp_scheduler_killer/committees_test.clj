(ns cfp-scheduler-killer.committees-test
  "Programming-committee membership tests, against the append-only store.

   The assertions that used to check 'a row exists' now check 'the right events
   were appended' — which is the same claim, made against the authority instead
   of the projection."
  (:require
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]])
  (:import
   (java.time LocalDate)))

(use-fixtures :each with-temp-store)

;; --- Fixtures ---------------------------------------------------------------

(defn- make-event!
  "A fresh event (with its auto-spawned Program Committee)."
  ([] (make-event! "committee-test"))
  ([slug]
   (events/create-event!
     {:name "Committee Test Summit"
      :slug slug
      :tz "America/New_York"
      :starts-on (LocalDate/of 2026 10 14)
      :ends-on (LocalDate/of 2026 10 15)
      :support-email "support@example.com"}
     "kaocha")))

(defn- committee-id-for [event]
  (:id (first (events/committees-for-event (:id event)))))

(defn- log-types [event-id]
  (mapv :type (events/log-for-event event-id)))

(def ^:private email "ann@example.com")

;; --- Pure -------------------------------------------------------------------

(deftest normalize-email-test
  (testing "email is lowercased and trimmed so identity survives typing"
    (is (= "gene@itrevolution.net" (committees/normalize-email "  Gene@ITRevolution.NET ")))
    (is (= "a@b.co" (committees/normalize-email "A@B.CO"))))
  (testing "blank collapses to nil rather than an empty identity"
    (is (nil? (committees/normalize-email "   ")))
    (is (nil? (committees/normalize-email nil)))))

(deftest member-validation-test
  (testing "a good draft passes"
    (is (nil? (committees/validation-errors
                {:name "Ann Perry" :email "annp@itrevolution.net" :role "member"}))))
  (testing "name is required"
    (is (contains? (committees/validation-errors
                     {:name nil :email "a@b.co" :role "member"}) :name)))
  (testing "email must look like an email"
    (doseq [bad ["nope" "a@b" "@b.co" nil]]
      (is (contains? (committees/validation-errors
                       {:name "X" :email bad :role "member"}) :email)
          (str "should reject " (pr-str bad)))))
  (testing "role is constrained to reviewer|chair"
    (is (contains? (committees/validation-errors
                     {:name "X" :email "a@b.co" :role "admin"}) :role)))
  (testing "the retired spelling \"member\" is still ACCEPTED — the log is full of it"
    (is (nil? (committees/validation-errors
                {:name "X" :email "a@b.co" :role "member"}))))
  (testing "parse-member-form lowercases the email and defaults the role"
    (let [d (committees/parse-member-form {:name " Ann Perry " :email " Ann@ITRev.NET "})]
      (is (= "Ann Perry" (:name d)))
      (is (= "ann@itrev.net" (:email d)))
      (is (= "reviewer" (:role d)))))
  (testing "and it normalises the retired spelling on the way in"
    (is (= "reviewer" (:role (committees/parse-member-form
                               {:name "X" :email "a@b.co" :role "member"}))))))

;; --- Adding -----------------------------------------------------------------

(deftest add-member-test
  (let [event (make-event!)
        cid (committee-id-for event)
        member (committees/add-member! cid {:name "Ann Perry"
                                            :email email
                                            :role "member"
                                            :tracks ["Developer Practices"]
                                            :profile {:headshot-url "/images/ann.png"}}
                                       "kaocha")]

    (testing "the membership comes back projected"
      (is (some? (:membership-id member)))
      (is (= "Ann Perry" (:name member)))
      (is (= email (:email member)))
      ;; Asked for the old spelling, stored as today's vocabulary.
      (is (= "reviewer" (:role member)))
      (is (= ["Developer Practices"] (:tracks member)))
      (is (= "/images/ann.png" (:headshot-url member))))

    (testing "a person record was created, keyed by lowercased email"
      (let [p (committees/person-by-email email)]
        (is (some? p))
        (is (= email (:email p)))
        (is (= {:headshot-url "/images/ann.png"} (:profile p)))))

    (testing "the roster shows them"
      (let [roster (committees/members-for-committee cid)]
        (is (= 1 (count roster)))
        (is (= email (:email (first roster))))))

    (testing "both person.created and member.added were appended"
      (is (= ["event.created" "committee.created" "form.installed"
              "person.created" "member.added"]
             (log-types (:id event)))))

    (testing "and the roster survives a reload"
      (store/load!)
      (is (= 1 (count (committees/members-for-committee cid))))
      (is (= "Ann Perry" (:name (first (committees/members-for-committee cid))))))))

(deftest add-member-uppercase-email-test
  (let [event (make-event!)
        cid (committee-id-for event)
        member (committees/add-member! cid {:name "Shouty Person"
                                            :email (str/upper-case email)} "kaocha")]
    (testing "an UPPERCASE address is stored lowercased"
      (is (= email (:email member)))
      (is (some? (committees/person-by-email email))))
    (testing "and re-adding the lowercase form is recognised as the same human"
      (let [thrown (try (committees/add-member! cid {:name "Shouty Person" :email email})
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :already-member (:type (ex-data thrown))))))))

(deftest duplicate-member-test
  (let [event (make-event!)
        cid (committee-id-for event)]
    (committees/add-member! cid {:name "Ann Perry" :email email :role "member"} "kaocha")

    (testing "adding the same email to the same committee is rejected"
      (let [thrown (try (committees/add-member! cid {:name "Ann Perry" :email email})
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (some? thrown) "a duplicate add must not succeed")
        (is (= :already-member (:type (ex-data thrown))))))

    (testing "and it appended NOTHING — not a person, not a membership"
      (is (= 1 (count (committees/members-for-committee cid))))
      (is (= 1 (count (:people (store/snapshot)))))
      (is (= ["event.created" "committee.created" "form.installed"
              "person.created" "member.added"]
             (log-types (:id event)))))))

(deftest person-reused-across-committees-test
  (let [event-a (make-event! "event-a")
        event-b (make-event! "event-b")
        cid-a (committee-id-for event-a)
        cid-b (committee-id-for event-b)
        m-a (committees/add-member! cid-a {:name "Gene Kim" :email email :role "chair"} "kaocha")
        ;; Same human, different committee, and the organizer typed the name
        ;; differently this time.
        m-b (committees/add-member! cid-b {:name "GENE KIM (typo)" :email email :role "member"} "kaocha")]

    (testing "one person record is reused across both committees"
      (is (= 1 (count (:people (store/snapshot)))))
      (is (= (:person-id m-a) (:person-id m-b))))

    (testing "their name is NOT overwritten by the second organizer's typing"
      (is (= "Gene Kim" (:name (committees/person-by-email email))))
      (is (= "Gene Kim" (:name m-b))))

    (testing "roles are per-membership, not per-person"
      (is (= "chair" (:role m-a)))
      (is (= "reviewer" (:role m-b))))

    (testing "the second event logged member.added but NOT person.created"
      (is (= ["event.created" "committee.created" "form.installed" "member.added"]
             (log-types (:id event-b)))))))

;; --- Removing ---------------------------------------------------------------

(deftest remove-member-test
  ;; Two reviewers, because one is now a floor: see the last-reviewer test
  ;; below. The chair here stands in for the person who created the event and
  ;; was auto-added to its committee.
  (let [event (make-event!)
        cid (committee-id-for event)
        _chair (committees/add-member! cid {:name "The Chair"
                                            :email "chair@example.com"
                                            :role "chair"} "kaocha")
        member (committees/add-member! cid {:name "Ann Perry" :email email} "kaocha")
        removed (committees/remove-member! (:membership-id member) "kaocha")]

    (testing "the membership is gone from the projection"
      (is (some? removed))
      (is (= ["chair@example.com"] (mapv :email (committees/members-for-committee cid))))
      (is (nil? (committees/membership-by-id (:membership-id member)))))

    (testing "the PERSON survives — identity persists across events"
      (is (some? (committees/person-by-email email))))

    (testing "member.removed was appended — nothing was erased"
      (is (= ["event.created" "committee.created" "form.installed"
              "person.created" "member.added"
              "person.created" "member.added" "member.removed"]
             (log-types (:id event)))))

    (testing "they can be added back, reusing the same person"
      (let [again (committees/add-member! cid {:name "Ann Perry" :email email} "kaocha")]
        (is (= (:person-id member) (:person-id again)))
        (is (= 2 (count (committees/members-for-committee cid))))))

    (testing "the whole add/remove/re-add story replays identically"
      (let [live (committees/members-for-committee cid)]
        (store/load!)
        (is (= live (committees/members-for-committee cid)))))))

(deftest cannot-remove-the-last-reviewer-test
  ;; Authorization is per-event (auth/member-of-event?), so an event with an
  ;; empty roster is an event NOBODY can open, decide or export again — and the
  ;; only way back in is hand-editing the log. The guard is the difference
  ;; between a destructive click and an unrecoverable one.
  (let [event (make-event!)
        cid (committee-id-for event)
        only (committees/add-member! cid {:name "Ann Perry" :email email} "kaocha")
        before (log-types (:id event))
        thrown (try (committees/remove-member! (:membership-id only) "kaocha")
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]

    (testing "removing the last reviewer is refused, by type"
      (is (some? thrown) "the last removal must not succeed")
      (is (= :last-reviewer (:type (ex-data thrown)))))

    (testing "and it appended NOTHING — a refusal is not a fact about the event"
      (is (= before (log-types (:id event))))
      (is (= 1 (count (committees/members-for-committee cid)))))

    (testing "with a second reviewer on the roster, the same removal succeeds"
      (committees/add-member! cid {:name "The Chair" :email "chair@example.com"
                                   :role "chair"} "kaocha")
      (is (some? (committees/remove-member! (:membership-id only) "kaocha")))
      (is (= ["chair@example.com"]
             (mapv :email (committees/members-for-committee cid)))))))

(deftest remove-unknown-membership-test
  (testing "removing a membership that isn't there returns nil, it doesn't throw"
    (is (nil? (committees/remove-member! (store/new-id) "kaocha")))))

(deftest add-to-unknown-committee-test
  (testing "an unknown committee id is a typed error, not a crash"
    (let [thrown (try (committees/add-member! (store/new-id)
                                              {:name "X" :email "x@example.com"})
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (= :no-such-committee (:type (ex-data thrown)))))))
