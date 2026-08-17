(ns cfp-scheduler-killer.speaker-workflow-consolidation-test
  (:require
   [cfp-scheduler-killer.announce :as announce]
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.domain.speakers :as speaker-domain]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.inform :as inform]
   [cfp-scheduler-killer.public-catalog :as public-catalog]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.speakers :as speakers]
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

(defn- event! [handler]
  (handler
    (mock/request :post "/api/events/create"
                  {"name" "Unified Speaker Summit"
                   "slug" "unified-speakers"
                   "starts-on" "2026-10-14"
                   "ends-on" "2026-10-15"
                   "presenter-visibility-mode" "visible"}))
  (events/event-by-slug "unified-speakers"))

(deftest speaker-workspaces-render-data-defined-states-and-program-roster
  (let [handler (organizer-app)
        event (event! handler)
        _ (speakers/add! (:id event)
                         {:name "Needs Telling"
                          :email "needs-telling@example.com"
                          :status "Submitted"
                          :actor "organizer@example.com"})
        _ (announce/create-announced-speaker!
            (:id event)
            {:name "Public Keynote"
             :email "public@example.com"
             :org "Public Co"
             :title "Founder"
             :announce? true}
            "organizer@example.com")
        _ (announce/create-announced-speaker!
            (:id event)
            {:name "Hidden Coach"
             :email "hidden@example.com"
             :org "Hidden Co"
             :title "Coach"
             :announce? false}
            "organizer@example.com")
        roster (speakers/roster-for-event (:id event))
        pending-person-id (:person-id
                            (some #(when (= "needs-telling@example.com" (:email %)) %)
                                  roster))]
    (with-redefs [inform/pending-decisions
                  (fn [_]
                    [{:id "pending-submission"
                      :speakers [{:person-id pending-person-id}]}])]
      (testing "Inform Speakers defaults to pending decision notification"
        (let [body (:body (handler
                            (mock/request
                              :get
                              "/events/unified-speakers/speakers?view=inform")))]
          (is (str/includes? body ">Speakers<"))
          (is (str/includes? body ">Needs informing<"))
          (is (str/includes? body "Needs Telling"))
          (is (str/includes? body
                             "/events/unified-speakers/inform#decision-pending-submission"))))
      (testing "Manage defaults to the full active program roster"
        (let [body (:body (handler
                           (mock/request
                            :get
                            "/events/unified-speakers/speakers?view=manage")))
              legacy-body (:body (handler
                                  (mock/request
                                   :get
                                   "/events/unified-speakers/speakers")))]
          (is (= ["Decide" "Inform" "Manage"]
                 (mapv :label speaker-domain/speaker-workspaces)))
          (is (= "All"
                 (:default-filter (speaker-domain/speaker-workspace :manage))))
          (is (str/includes? body "2 speakers · 1 public"))
          (is (str/includes? body "Public Keynote"))
          (is (str/includes? body "Hidden Coach"))
          (is (not (str/includes? body "Needs Telling")))
          (is (str/includes? body "status=All"))
          (is (str/includes? legacy-body "Needs Telling"))
          (is (str/includes? legacy-body "speakerworkflow&quot;:&quot;roster"))
          (is (not (str/includes? body "All public profiles"))))))))

(deftest canonical-detail-form-owns-program-visibility-and-talk-context
  (let [handler (organizer-app)
        event (event! handler)
        person (announce/create-announced-speaker!
                 (:id event)
                 {:name "Manual Keynote"
                  :email "keynote@example.com"
                  :org "Keynote Co"
                  :title "Founder"
                  :bio "A keynote biography."
                  :announce? true}
                 "organizer@example.com")
        detail-path (str "/events/unified-speakers/speakers/" (:id person))
        edit-path (str "/api/events/unified-speakers/speakers/" (:id person))]
    (testing "the shared detail form contains the former Announce controls"
      (let [body (:body (handler (mock/request :get detail-path)))]
        (is (str/includes? body "PROGRAM DETAILS"))
        (is (str/includes? body "name=\"announce\""))
        (is (str/includes? body "Publish as speaker"))
        (is (str/includes? body ">Talks<"))
        (is (str/includes? body "No talk attached yet."))))
    (testing "the shared save toggles the same public-program projection"
      (let [base {"profile-edit" "true"
                  "name" "Manual Keynote"
                  "tagline" "Founder"
                  "org" "Keynote Co"
                  "bio" "A keynote biography."
                  "status" "Confirmed"}]
        (is (= 303 (:status (handler (mock/request :post edit-path base)))))
        (is (false? (:published?
                      (public-catalog/program-speaker-by-id
                        (events/event-by-id (:id event)) (:id person)))))
        (is (= 303 (:status
                     (handler (mock/request :post edit-path
                                            (assoc base "announce" "1"))))))
        (is (true? (:published?
                     (public-catalog/program-speaker-by-id
                       (events/event-by-id (:id event)) (:id person)))))))))

(deftest cfp-speaker-detail-shows-its-publication-state
  (let [handler (organizer-app)
        event (event! handler)
        _ (speakers/add! (:id event)
                         {:name "Accepted CFP Speaker"
                          :email "accepted@example.com"
                          :status "Confirmed"
                          :actor "organizer@example.com"})
        person-id (:person-id
                    (some #(when (= "accepted@example.com" (:email %)) %)
                          (speakers/roster-for-event (:id event))))]
    (with-redefs [public-catalog/program-speakers
                  (fn [_]
                    [{:id person-id
                      :name "Accepted CFP Speaker"
                      :tagline "CTO"
                      :company "Example Co"
                      :bio ""
                      :headshot ""
                      :sessions []
                      :manual? false
                      :published? true}])]
      (let [body (:body
                   (handler
                     (mock/request
                       :get
                       (str "/events/unified-speakers/speakers/" person-id))))]
        (is (str/includes? body "Publish as speaker"))
        (is (str/includes? body "checked=\"checked\""))
        (is (str/includes? body "disabled=\"disabled\""))
        (is (str/includes? body "public through their accepted, informed session"))))))

(deftest navigation-points-at-the-shared-workflows
  (let [handler (organizer-app)
        _ (event! handler)
        body (:body (handler
                      (mock/request :get
                                    "/events/unified-speakers/speakers?view=manage")))
        board-body (:body (handler
                            (mock/request :get "/events/unified-speakers/board")))]
    (is (str/includes? body
                       "href=\"/events/unified-speakers/board\""))
    (is (str/includes? body
                       "href=\"/events/unified-speakers/inform\""))
    (is (str/includes? body
                       "href=\"/events/unified-speakers/speakers?view=manage\""))
    (is (str/includes? body "pipeline-strip"))
    (is (not (str/includes? board-body "pipeline-strip")))))
