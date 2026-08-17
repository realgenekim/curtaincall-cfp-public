(ns cfp-scheduler-killer.cospeaker-assignment-contract-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.exports :as exports]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.speakers :as speakers]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock]))

(use-fixtures :each with-temp-store)

(defn- signed-in
  [handler email]
  (let [token (auth/issue-token! email)
        response (handler (mock/request :get (str "/auth/" token)))
        cookie (first (str/split (first (get-in response [:headers "Set-Cookie"])) #";"))]
    (fn [request]
      (handler (mock/header request "cookie" cookie)))))

(defn- create-event!
  [organizer slug]
  (organizer
    (mock/request :post "/api/events/create"
                  {"name" "Co-speaker Contract Summit"
                   "slug" slug
                   "starts-on" "2026-10-14"
                   "ends-on" "2026-10-15"
                   "presenter-visibility-mode" "visible"}))
  (events/event-by-slug slug))

(defn- seed-session!
  [event n]
  (let [person-id (str "primary-" n)
        submission-id (str "session-" n)]
    (store/append-all!
      [{:type "person.created"
        :actor "fixture"
        :event-id (:id event)
        :payload {:id person-id
                  :name (str "Primary " n)
                  :email (str "primary-" n "@example.com")
                  :profile {}
                  :created-at (store/now-iso)}}
       {:type "submission.created"
        :actor "fixture"
        :event-id (:id event)
        :payload {:id submission-id
                  :event-id (:id event)
                  :answers {:talk-title (str "Session " n)}
                  :speakers [{:person-id person-id
                              :name (str "Primary " n)
                              :email (str "primary-" n "@example.com")
                              :role "Primary speaker"
                              :position 0}]
                  :status "Accepted"
                  :notified-at (store/now-iso)
                  :source "fixture"
                  :created-at (store/now-iso)}}])
    submission-id))

(defn- post-assignment
  [organizer slug submission-id person-id]
  (organizer
    (mock/request :post
                  (str "/events/" slug "/submissions/" submission-id "/speakers")
                  {"person-id" person-id})))

(defn- post-removal
  [organizer slug submission-id person-id]
  (organizer
    (mock/request
      :post
      (str "/events/" slug "/submissions/" submission-id
           "/speakers/" person-id "/remove"))))

(deftest organizer-can-assign-remove-and-reassign-cospeakers-as-an-event-sourced-family
  (let [handler (server/create-app)
        organizer (signed-in handler "organizer@example.com")
        slug "cospeaker-contract"
        event (create-event! organizer slug)
        submission-ids (mapv #(seed-session! event %) (range 3))
        candidate-ids
        (mapv (fn [n]
                (:person-id
                  (speakers/add!
                    (:id event)
                    {:name (str "Candidate " n)
                     :email (str "candidate-" n "@example.com")
                     :status "Confirmed"
                     :actor "organizer@example.com"})))
              (range 3))]
    (testing "the organizer surface names the session-level workflow"
      (let [body (:body (organizer
                          (mock/request :get (str "/events/" slug "/speakers"))))]
        (is (str/includes? body "Assign or reassign co-speakers"))
        (doseq [n (range 3)]
          (is (str/includes? body (str "Session " n)))
          (is (str/includes? body (str "Candidate " n))))))

    (testing "independent session assignments survive a non-sorted interleaving"
      (doseq [n [2 0 1]]
        (let [submission-id (nth submission-ids n)
              person-id (nth candidate-ids n)
              response (post-assignment organizer slug submission-id person-id)]
          (is (= 303 (:status response)))
          (is (= 1 (count (get-in (store/snapshot)
                                  [:submissions submission-id :speakers])))
              "the submitted snapshot stays frozen")
          (is (= 2 (count (:speakers (store/submission-by-id submission-id)))))
          (is (= 2 (count (exports/speaker-ids
                            (store/submission-by-id submission-id)))))
          (is (= [(str "Session " n)]
                 (:talks
                   (some #(when (= person-id (:person-id %)) %)
                         (speakers/roster-for-event (:id event)))))))))

    (testing "retrying an assignment is idempotent"
      (let [before (count (:log (store/snapshot)))
            response (post-assignment organizer slug
                                      (first submission-ids)
                                      (first candidate-ids))]
        (is (= 303 (:status response)))
        (is (= before (count (:log (store/snapshot)))))))

    (testing "co-speakers can be removed and replaced without touching primaries"
      (doseq [n [1 2 0]]
        (is (= 303 (:status
                     (post-removal organizer slug
                                   (nth submission-ids n)
                                   (nth candidate-ids n)))))
        (is (= 1 (count (:speakers
                          (store/submission-by-id (nth submission-ids n)))))))
      (doseq [n [0 1 2]]
        (is (= 303 (:status
                     (post-assignment organizer slug
                                      (nth submission-ids n)
                                      (nth candidate-ids (mod (inc n) 3)))))))
      (is (= [2 2 2]
             (mapv #(count (:speakers (store/submission-by-id %)))
                   submission-ids))))

    (testing "replaying the append log derives the same assignment projection"
      (let [live (:submission-speaker-assignments (store/snapshot))
            replayed (:submission-speaker-assignments
                       (store/fold (store/read-events)))]
        (is (= live replayed))))))
