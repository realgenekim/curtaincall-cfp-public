(ns cfp-scheduler-killer.speaker-duplicate-test
  "CRM-06 — advisory duplicate detection on the Add-a-speaker form.

   The defect: creating a same-name contact with a different email minted a
   second person silently. The fix warns and NEVER blocks, so every test here
   pairs 'was the organizer told?' with 'could they still proceed?'."
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.domain.speakers :as domain-speakers]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.public-catalog :as public-catalog]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.speakers :as speakers]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock]))

(use-fixtures :each with-temp-store)

(defn- organizer-app []
  (let [handler (server/create-app)
        token (auth/issue-token! "organizer@example.com")
        response (handler (mock/request :get (str "/auth/" token)))
        cookie (first (str/split (first (get-in response [:headers "Set-Cookie"])) #";"))]
    (fn [request]
      (handler (mock/header request "cookie" cookie)))))

(defn- create-event! [handler name slug]
  (handler
    (mock/request :post "/api/events/create"
                  {"name" name :slug slug
                   "starts-on" "2026-10-14" "ends-on" "2026-10-15"
                   "presenter-visibility-mode" "visible"}))
  (events/event-by-slug slug))

(defn- post-speaker! [handler event params]
  (handler (mock/request :post (str "/api/events/" (:slug event) "/speakers") params)))

(defn- post-invited-speaker! [handler event params]
  (handler
    (mock/request :post
                  (str "/api/events/" (:slug event) "/speakers/create")
                  params)))

(defn- person-count [] (count (:people (store/snapshot))))
(defn- log-count [] (count (:log (store/snapshot))))

(defn- warned? [response]
  (str/includes? (str (:body response)) "Possible duplicate"))

;; A second organizer, with their own event and their own contact, who shares a
;; name with the person our organizer is about to add.
(defn- add-foreign-world! [contact-name contact-email]
  (let [event-id "foreign-event"
        committee-id "foreign-committee"
        organizer-id "foreign-organizer"]
    (store/append-all!
      [{:type "event.created" :actor "foreign@example.com" :event-id event-id
        :payload {:id event-id :slug "foreign" :name "Foreign Event"
                  :created-at (store/now-iso)}}
       {:type "person.created" :actor "foreign@example.com" :event-id event-id
        :payload {:id organizer-id :email "foreign@example.com"
                  :name "Foreign Organizer" :profile {}
                  :created-at (store/now-iso)}}
       {:type "committee.created" :actor "foreign@example.com" :event-id event-id
        :payload {:id committee-id :event-id event-id :name "Program Committee"
                  :created-at (store/now-iso)}}
       {:type "member.added" :actor "foreign@example.com" :event-id event-id
        :payload {:id "foreign-membership" :committee-id committee-id
                  :event-id event-id :person-id organizer-id :role "chair"
                  :email "foreign@example.com" :name "Foreign Organizer"
                  :created-at (store/now-iso)}}])
    (speakers/add! event-id {:email contact-email :name contact-name
                             :status "Invited" :actor "foreign@example.com"})))

;; ---------------------------------------------------------------------------
;; The defect itself
;; ---------------------------------------------------------------------------

;; INTENT-TEST: SPK-002
(deftest same-name-different-email-warns-writes-nothing-and-still-proceeds
  (let [handler (organizer-app)
        event (create-event! handler "Summit" "summit")
        _ (post-speaker! handler event {"name" "Priya Raghavan"
                                        "email" "priya@example.com"
                                        "status" "Invited"})
        people-before (person-count)
        log-before (log-count)
        ;; Same human name, whitespace/case noise, a different email.
        warning (post-speaker! handler event {"name" "  priya   RAGHAVAN "
                                              "email" "priya.r@other.com"
                                              "status" "Invited"})]
    (testing "the organizer is warned, and told what already exists"
      (is (= 200 (:status warning)))
      (is (warned? warning))
      (is (str/includes? (:body warning) "priya@example.com")))
    (testing "nothing is written while the warning is displayed"
      (is (= people-before (person-count)))
      (is (= log-before (log-count))))
    (testing "the submitted values survive so a typo can be corrected"
      (is (str/includes? (:body warning) "priya.r@other.com")))
    (testing "confirming in one click creates the separate person"
      (let [confirmed (post-speaker! handler event
                                     {"name" "  priya   RAGHAVAN "
                                      "email" "priya.r@other.com"
                                      "status" "Invited"
                                      "confirm-duplicate" "yes"})]
        (is (= 303 (:status confirmed)))
        (is (= (inc people-before) (person-count)))
        (let [roster (speakers/roster-for-event (:id event))]
          (testing "two separate people, one name, two emails"
            (is (= 2 (count roster)))
            (is (= #{"priya raghavan"}
                   (set (map (comp domain-speakers/normalize-name :name) roster))))
            (is (= #{"priya@example.com" "priya.r@other.com"}
                   (set (map :email roster))))))))))

;; INTENT-TEST: SPK-002
(deftest every-hand-entered-speaker-path-warns-before-minting-a-duplicate
  (let [handler (organizer-app)
        event (create-event! handler "Summit" "summit")
        _ (post-speaker! handler event {"name" "Priya Raghavan"
                                        "email" "priya@example.com"
                                        "status" "Invited"})
        people-before (person-count)
        log-before (log-count)
        values {"name" "  priya   RAGHAVAN "
                "email" "priya.keynote@example.com"
                "org" "Keynote Co"
                "title" "Founder"
                "announce" "1"}
        warning (post-invited-speaker! handler event values)]
    (testing "the invited-keynote path uses the same advisory identity guard"
      (is (= 200 (:status warning)))
      (is (warned? warning))
      (is (str/includes? (:body warning) "priya@example.com"))
      (is (str/includes? (:body warning) "priya.keynote@example.com")))
    (testing "showing the warning has no persistence side effects"
      (is (= people-before (person-count)))
      (is (= log-before (log-count))))
    (testing "one explicit confirmation creates the separate invited speaker"
      (let [confirmed (post-invited-speaker!
                        handler event (assoc values "confirm-duplicate" "yes"))
            created (store/person-by-email "priya.keynote@example.com")
            public-speaker (public-catalog/speaker-by-id
                             (events/event-by-id (:id event)) (:id created))]
        (is (= 303 (:status confirmed)))
        (is (= (inc people-before) (person-count)))
        (is (= {:org "Keynote Co" :tagline "Founder"}
               (select-keys (:profile created) [:org :tagline])))
        (is (= (:id created) (:id public-speaker))
            "the explicit confirmation preserves the publication choice")))))

;; ---------------------------------------------------------------------------
;; False-positive guards — a warning that fires wrongly is worse than the bug
;; ---------------------------------------------------------------------------

(deftest a-genuinely-new-contact-is-never-warned
  (let [handler (organizer-app)
        event (create-event! handler "Summit" "summit")
        _ (post-speaker! handler event {"name" "Priya Raghavan"
                                        "email" "priya@example.com"
                                        "status" "Invited"})
        people-before (person-count)
        response (post-speaker! handler event {"name" "Marcus Devlin"
                                               "email" "marcus@example.com"
                                               "status" "Invited"})]
    (is (= 303 (:status response)))
    (is (not (warned? response)))
    (is (= (inc people-before) (person-count)))))

;; INTENT-TEST: SPK-002
(deftest same-email-add-to-a-second-event-is-never-warned
  (testing "decide-add resolves the existing identity by normalized email"
    (let [handler (organizer-app)
          event-one (create-event! handler "One" "one")
          event-two (create-event! handler "Two" "two")
          _ (post-speaker! handler event-one {"name" "Dana Wu"
                                              "email" "dana@example.com"
                                              "status" "Invited"})
          people-before (person-count)
          ;; Exactly the flow the warning must not obstruct: a known speaker
          ;; being added to a second event, same email, mixed case.
          response (post-speaker! handler event-two {"name" "Dana Wu"
                                                     "email" "Dana@Example.com"
                                                     "status" "Confirmed"})]
      (is (= 303 (:status response)))
      (is (not (warned? response)))
      (testing "no second person was minted, so there was nothing to warn about"
        (is (= people-before (person-count))))
      (is (= 1 (count (speakers/roster-for-event (:id event-two)))))))
  (testing "the same guarantee at the decision level, not just through the route"
    (let [handler (organizer-app)
          event (create-event! handler "Solo" "solo")
          _ (post-speaker! handler event {"name" "Dana Wu"
                                          "email" "dana@example.com"
                                          "status" "Invited"})
          decision (domain-speakers/decide-add
                     (store/snapshot)
                     {:event-id (:id event) :person-id "would-be-new-id"
                      :email "DANA@example.com " :name "Dana Wu"
                      :status "Confirmed" :actor "organizer@example.com"
                      :at (store/now-iso)})]
      (is (empty? (filter #(= "person.created" (:type %)) (:facts decision))))
      (is (not= "would-be-new-id" (:person-id decision))))))

;; INTENT-TEST: SPK-002
(deftest another-organizers-same-name-contact-neither-warns-nor-leaks
  (let [handler (organizer-app)
        event (create-event! handler "Summit" "summit")
        _ (add-foreign-world! "Priya Raghavan" "private@example.com")
        people-before (person-count)
        response (post-speaker! handler event {"name" "Priya Raghavan"
                                               "email" "priya@example.com"
                                               "status" "Invited"})
        page (handler (mock/request :get "/events/summit/speakers"))]
    (testing "detection is scoped to contacts the viewer can already see"
      (is (= 303 (:status response)))
      (is (not (warned? response)))
      (is (= (inc people-before) (person-count))))
    (testing "the other organizer's contact is never revealed"
      (is (not (str/includes? (str (:body response)) "private@example.com")))
      (is (not (str/includes? (str (:body page)) "private@example.com"))))))
