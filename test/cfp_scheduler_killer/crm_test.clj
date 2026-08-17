(ns cfp-scheduler-killer.crm-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.events :as events]
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

(defn- add-speaker! [event email name organization]
  (let [result (speakers/add! (:id event)
                              {:email email :name name :status "Invited"
                               :actor "organizer@example.com"})
        person-id (:person-id result)]
    (speakers/edit! (:id event) person-id {:organization organization}
                    "organizer@example.com")
    person-id))

(defn- add-foreign-world! []
  (let [event-id "foreign-event"
        committee-id "foreign-committee"
        organizer-id "foreign-organizer"]
    (store/append-all!
      [{:type "event.created" :actor "foreign@example.com" :event-id event-id
        :payload {:id event-id :slug "foreign" :name "Foreign Event"
                  :created-at (store/now-iso)}}
       {:type "person.created" :actor "foreign@example.com" :event-id event-id
        :payload {:id organizer-id :email "foreign@example.com" :name "Foreign Organizer"
                  :profile {} :created-at (store/now-iso)}}
       {:type "committee.created" :actor "foreign@example.com" :event-id event-id
        :payload {:id committee-id :event-id event-id :name "Program Committee"
                  :created-at (store/now-iso)}}
       {:type "member.added" :actor "foreign@example.com" :event-id event-id
        :payload {:id "foreign-membership" :committee-id committee-id
                  :event-id event-id :person-id organizer-id :role "chair"
                  :email "foreign@example.com" :name "Foreign Organizer"
                  :created-at (store/now-iso)}}])
    (let [person-id (:person-id
                      (speakers/add! event-id
                                     {:email "private@example.com" :name "Private Person"
                                      :status "Invited" :actor "foreign@example.com"}))]
      {:event-id event-id :person-id person-id})))

(deftest organization-level-directory-search-and-tenancy
  (let [handler (organizer-app)
        event-one (create-event! handler "One" "one")
        event-two (create-event! handler "Two" "two")
        repeat-id (add-speaker! event-one "repeat@example.com" "Repeat Speaker" "Acme")
        _ (speakers/add! (:id event-two)
                         {:email "repeat@example.com" :name "Repeat Speaker"
                          :status "Confirmed" :actor "organizer@example.com"})
        {:keys [person-id]} (add-foreign-world!)
        response (handler (mock/request :get "/people"))
        filtered (handler (mock/request :get "/people?q=Acme"))]
    (testing "CRM-01 and CRM-02 are literal, navigable screens"
      (is (= 200 (:status response)))
      (is (str/includes? (:body response) "One canonical relationship history"))
      (is (str/includes? (:body response) "Repeat Speaker"))
      (is (str/includes? (:body response) "repeat@example.com"))
      (is (str/includes? (:body response) "Repeat relationship"))
      (is (str/includes? (:body response) "All organizations"))
      (is (str/includes? (:body response) "All relationships"))
      (is (str/includes? (:body response) "All events"))
      (is (str/includes? (:body filtered) "Repeat Speaker")))
    (testing "another organizer's contacts never leak"
      (is (not (str/includes? (:body response) "Private Person")))
      (is (= 404 (:status
                   (handler (mock/request :get (str "/people/" person-id)))))))
    (testing "detail carries cross-event history"
      (let [detail (handler (mock/request :get (str "/people/" repeat-id)))]
        (is (= 200 (:status detail)))
        (is (str/includes? (:body detail) "Events and roles"))
        (is (str/includes? (:body detail) "Activity history"))
        (is (str/includes? (:body detail) "Push contact into event"))))))

(deftest notes-tags-and-push-are-named-event-sourced-verbs
  (let [handler (organizer-app)
        event-one (create-event! handler "One" "one")
        event-two (create-event! handler "Two" "two")
        person-id (add-speaker! event-one "dana@example.com" "Dana" "Northwind")
        path (str "/people/" person-id)]
    (testing "an internal note appends one scoped fact"
      (let [before (count (:log (store/snapshot)))
            response (handler
                       (mock/request :post (str "/api/people/" person-id "/notes")
                                     {"event-id" (:id event-one)
                                      "body" "Strong prior speaker."}))]
        (is (= 303 (:status response)))
        (is (= (inc before) (count (:log (store/snapshot)))))
        (is (= "crm.note-added" (:type (last (:log (store/snapshot))))))
        (is (str/includes? (:body (handler (mock/request :get path)))
                           "Strong prior speaker."))))
    (testing "tag add/remove are facts and filtering sees the projection"
      (is (= 303 (:status
                   (handler
                     (mock/request :post (str "/api/people/" person-id "/tags/add")
                                   {"event-id" (:id event-one) "tag" "Approval Risk"})))))
      (is (str/includes? (:body (handler (mock/request :get "/people?tag=approval+risk")))
                         "Dana"))
      (is (= 303 (:status
                   (handler
                     (mock/request :post (str "/api/people/" person-id "/tags/remove")
                                   {"event-id" (:id event-one) "tag" "approval risk"})))))
      (is (= ["crm.tag-added" "crm.tag-removed"]
             (->> (:log (store/snapshot)) (map :type) (filter #(str/starts-with? % "crm.tag")) vec))))
    (testing "push reuses the same identity and becomes idempotent"
      (let [people-before (count (:people (store/snapshot)))
            response (handler
                       (mock/request :post (str "/api/people/" person-id "/events/add")
                                     {"event-id" (:id event-two)}))]
        (is (= 303 (:status response)))
        (is (= people-before (count (:people (store/snapshot)))))
        (is (= person-id (:person-id (first (speakers/roster-for-event (:id event-two))))))
        (let [before (count (:log (store/snapshot)))]
          (is (= 303 (:status
                       (handler
                         (mock/request :post (str "/api/people/" person-id "/events/add")
                                       {"event-id" (:id event-two)})))))
          (is (= before (count (:log (store/snapshot))))))))
    (testing "cross-tenant push is rejected without a fact"
      (let [{:keys [event-id]} (add-foreign-world!)
            before (count (:log (store/snapshot)))
            response (handler
                       (mock/request :post (str "/api/people/" person-id "/events/add")
                                     {"event-id" event-id}))]
        (is (= 422 (:status response)))
        (is (= before (count (:log (store/snapshot)))))))))

(deftest csv-import-previews-deduplicates-and-keeps-event-provenance
  (let [handler (organizer-app)
        event (create-event! handler "Import Event" "import-event")
        person-id (add-speaker! event "dana@example.com" "Dana" "Northwind")
        csv-text (str "Name,Email,Company,Status\n"
                      "Dana,dana@example.com,Northwind,Confirmed\n"
                      "New Person,new@example.com,Acme,Invited\n")]
    (testing "preview is read-only and names the email-dedupe behavior"
      (let [before (count (:log (store/snapshot)))
            response (handler
                       (mock/request :post "/api/people/import/preview"
                                     {"event-id" (:id event) "csv-text" csv-text}))]
        (is (= 200 (:status response)))
        (is (str/includes? (:body response) "Import preview"))
        (is (str/includes? (:body response) "Existing emails reuse the canonical person"))
        (is (= before (count (:log (store/snapshot)))))))
    (testing "confirmation adds one identity and reuses Dana"
      (let [people-before (count (:people (store/snapshot)))
            response (handler
                       (mock/request :post "/api/people/import"
                                     {"event-id" (:id event) "csv-text" csv-text}))]
        (is (= 303 (:status response)))
        (is (= (inc people-before) (count (:people (store/snapshot)))))
        (is (= person-id (:person-id (some #(when (= "dana@example.com" (:email %)) %)
                                           (speakers/roster-for-event (:id event))))))
        (is (= #{"dana@example.com" "new@example.com"}
               (set (map :email (speakers/roster-for-event (:id event))))))))
    (testing "an inaccessible target event refuses the entire import"
      (let [{:keys [event-id]} (add-foreign-world!)
            before (count (:log (store/snapshot)))
            response (handler
                       (mock/request :post "/api/people/import"
                                     {"event-id" event-id "csv-text" csv-text}))]
        (is (= 422 (:status response)))
        (is (= before (count (:log (store/snapshot)))))))))

(deftest pipeline-segments-and-human-reviewed-outreach
  (let [handler (organizer-app)
        event (create-event! handler "Relationship Event" "relationships")
        dana-id (add-speaker! event "dana@example.com" "Dana" "Northwind")
        priya-id (add-speaker! event "priya@example.com" "Priya" "Acme")]
    (testing "the CRM dashboard exposes pipeline, saved segments, and outreach"
      (let [body (:body (handler (mock/request :get "/people")))]
        (is (str/includes? body "Sourcing pipeline"))
        (is (str/includes? body "Saved segments"))
        (is (str/includes? body "Human-reviewed outreach"))
        (is (str/includes? body "Compose outreach"))))
    (testing "saving and removing a segment are explicit facts"
      (is (= 303 (:status
                   (handler
                     (mock/request :post "/api/people/segments"
                                   {"event-id" (:id event) "name" "Acme people"
                                    "organization" "Acme"})))))
      (let [segment (first (vals (:crm-segments (store/snapshot))))]
        (is (= "Acme people" (:name segment)))
        (is (= {:organization "Acme"} (:filters segment)))
        (is (str/includes? (:body (handler (mock/request :get "/people")))
                           "Acme people"))
        (is (= 303 (:status
                     (handler
                       (mock/request :post
                                     (str "/api/people/segments/" (:id segment) "/remove"))))))
        (is (empty? (:crm-segments (store/snapshot))))))
    (testing "preview resolves every recipient and appends nothing"
      (let [before (count (:log (store/snapshot)))
            params {"event-id" (:id event)
                    "person-id" [dana-id priya-id]
                    "subject" "Invitation for {name}"
                    "body" "Hello {name} at {organization} ({email})"}
            response (handler (mock/request :post "/api/people/outreach/preview" params))]
        (is (= 200 (:status response)))
        (is (str/includes? (:body response) "Resolved recipient previews"))
        (is (str/includes? (:body response) "Invitation for Dana"))
        (is (str/includes? (:body response) "Hello Priya at Acme (priya@example.com)"))
        (is (str/includes? (:body response) "There is still no send action"))
        (is (= before (count (:log (store/snapshot)))))
        (testing "recording captures one draft fact and never a sent fact"
          (let [recorded (handler (mock/request :post "/api/people/outreach/record" params))
                types (map :type (:log (store/snapshot)))]
            (is (= 303 (:status recorded)))
            (is (= "crm.outreach-drafted" (last types)))
            (is (not-any? #{"comms.sent"} types))
            (is (= #{dana-id priya-id}
                   (set (:recipient-ids (first (vals (:crm-outreach-drafts
                                                       (store/snapshot))))))))))))
    (testing "the composer is explicit about human control"
      (let [body (:body (handler (mock/request :get "/people/outreach")))]
        (is (str/includes? body "Outreach composer"))
        (is (str/includes? body "never sends mail automatically"))
        (is (str/includes? body "Preview every recipient"))))))
