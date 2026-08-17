(ns cfp-scheduler-killer.announce-admin-test
  (:require
   [cfp-scheduler-killer.announce :as announce]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.handlers.announce :as announce-handlers]
   [cfp-scheduler-killer.inform :as inform]
   [cfp-scheduler-killer.people :as people]
   [cfp-scheduler-killer.public-catalog :as public-catalog]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock])
  (:import
   (java.time LocalDate LocalDateTime)))

(use-fixtures :each with-temp-store)

(defn- event! []
  (events/create-event!
    {:name "Announce Admin Summit"
     :slug "announce-admin"
     :tz "America/New_York"
     :location "Charlotte, NC"
     :starts-on (LocalDate/of 2026 10 14)
     :ends-on (LocalDate/of 2026 10 15)
     :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
     :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
    "kaocha"))

(defn- submission!
  [event {:keys [name email title]}]
  (let [fields (:fields (events/form-for-event (:id event)))
        params {:answer-talk-title title
                :answer-abstract (str title " explains the work and its outcomes in detail.")
                :speaker-name name
                :speaker-email email
                :speaker-title "VP of AI"
                :speaker-org "Example Co"
                :speaker-bio (str name " has led enterprise AI programs for a decade.")
                :speaker-headshot-url "https://images.example.com/speaker.jpg"}]
    (submissions/create-submission!
      event
      (submissions/parse-answers fields params)
      (submissions/parse-speaker params)
      "form"
      "kaocha")))

(defn- person-created-count []
  (count (filter #(= "person.created" (:type %)) (store/read-events))))

(deftest adopt-announced-speaker-lights-public-roster-test
  (let [event (event!)]
    (events/announce-speaker!
      event
      {:name "Ann Perry"
       :org "IT Revolution"
       :title "Conference Chair"
       :headshot-url "https://images.example.com/ann.jpg"}
      "organizer@example.com")
    (let [person (announce/adopt-announced-speaker!
                   (:id event) "  ANN   PERRY " "organizer@example.com")
          fresh-event (events/event-by-id (:id event))
          entry (first (get-in fresh-event [:settings :announced-speakers]))
          roster-entry (first (public-catalog/public-speakers fresh-event))
          response ((server/create-app)
                    (mock/request :get
                                  (str "/agenda/announce-admin/speakers/"
                                       (:slug roster-entry) "/announce")))]
      (testing "adopt mints the identity and stamps the legacy wall entry"
        (is (= (:id person) (:person-id entry)))
        (is (= "ann-perry" (:slug person))))
      (testing "the one public roster now exposes the live identity"
        (is (= (str (:id person)) (:id roster-entry)))
        (is (= "ann-perry" (:slug roster-entry)))
        (is (= "https://images.example.com/ann.jpg" (:headshot roster-entry))))
      (testing "the existing public announce handler serves the adopted speaker"
        (is (= 200 (:status response)))))))

(deftest adopt-announced-speaker-is-idempotent-test
  (let [event (event!)]
    (events/announce-speaker! event {:name "Ann Perry"} "organizer@example.com")
    (let [first-person (announce/adopt-announced-speaker!
                         (:id event) "Ann Perry" "organizer@example.com")
          after-first (person-created-count)
          second-person (announce/adopt-announced-speaker!
                          (:id event) "ann perry" "organizer@example.com")]
      (is (= (:id first-person) (:id second-person)))
      (is (= after-first (person-created-count))
          "a second adopt must not append another person.created fact"))))

(deftest create-speaker-controls-program-announcement-test
  (let [event (event!)
        lit (announce/create-announced-speaker!
              (:id event)
              {:name "Keynote Speaker"
               :email "keynote@example.com"
               :org "Keynote Co"
               :title "Founder"
               :headshot-url "https://images.example.com/keynote.jpg"
               :bio "A keynote biography."
               :announce? true}
              "organizer@example.com")
        private (announce/create-announced-speaker!
                  (:id event)
                  {:name "Private Speaker"
                   :email "private@example.com"
                   :org "Quiet Co"
                   :title "CTO"
                   :bio "Not on the program yet."
                   :announce? false}
                  "organizer@example.com")
        fresh-event (events/event-by-id (:id event))
        roster (public-catalog/public-speakers fresh-event)
        program-roster (public-catalog/program-speakers fresh-event)]
    (testing "announced speakers are born lit"
      (is (= (str (:id lit)) (:id (first roster))))
      (is (= "keynote-speaker" (:slug (first roster)))))
    (testing "an unpublished created person remains editable but is absent publicly"
      (is (= (:id private) (:id (store/person-by-id (:id private)))))
      (is (not-any? #(= (:id private) (:id %)) roster))
      (is (some #(and (= (:id private) (:id %))
                      (false? (:published? %))
                      (:manual? %))
                program-roster)))))

(deftest ready-to-announce-queue-is-accepted-and-uninformed-only-test
  (let [event (event!)
        ready (submission! event {:name "Ready Speaker"
                                  :email "ready@example.com"
                                  :title "Ready Talk"})
        told (submission! event {:name "Told Speaker"
                                 :email "told@example.com"
                                 :title "Told Talk"})
        declined (submission! event {:name "Declined Speaker"
                                     :email "declined@example.com"
                                     :title "Declined Talk"})]
    (reviews/set-status! (:id ready) "Accepted" "organizer@example.com")
    (reviews/set-status! (:id told) "Accepted" "organizer@example.com")
    (reviews/set-status! (:id declined) "Declined" "organizer@example.com")
    (inform/inform! event (store/submission-by-id (:id told)) "organizer@example.com")
    (is (= [(:id ready)]
           (mapv :id (announce/ready-to-announce (:id event)))))))

(deftest announce-stats-counts-the-public-roster-test
  (let [event (event!)]
    (events/announce-speaker! event {:name "Dark Speaker"} "organizer@example.com")
    (announce/create-announced-speaker!
      (:id event) {:name "Lit Speaker" :email "lit@example.com" :announce? true}
      "organizer@example.com")
    (is (= {:lit 1 :total 2}
           (public-catalog/announce-stats (events/event-by-id (:id event)))))))

(deftest announce-admin-page-renders-queue-marquee-and-copy-controls-test
  (let [event (event!)
        ready (submission! event {:name "Ready Speaker"
                                  :email "ready@example.com"
                                  :title "Ready Talk"})]
    (reviews/set-status! (:id ready) "Accepted" "organizer@example.com")
    (events/announce-speaker! event {:name "Dark One"} "organizer@example.com")
    (events/announce-speaker! event {:name "Dark Two"} "organizer@example.com")
    (announce/create-announced-speaker!
      (:id event)
      {:name "Lit Speaker"
       :email "lit@example.com"
       :org "Planview"
       :title "CTO"
       :headshot-url "https://example.com/lit.jpg"
       :bio "A concise program bio."
       :announce? true}
      "organizer@example.com")
    (let [response (announce-handlers/handle-announce-page
                     (assoc-in (mock/request :get "/events/announce-admin/announce")
                               [:path-params :slug] "announce-admin"))
          body (:body response)]
      (is (= 200 (:status response)))
      (is (str/includes? body "1 of 3 lit"))
      (is (str/includes? body "READY TO ANNOUNCE"))
      (is (str/includes? body "Ready Speaker"))
      (is (str/includes? body "told ✗"))
      (is (str/includes? body "THE MARQUEE"))
      (is (str/includes? body "Light all 2"))
      (is (str/includes? body "Copy speaker brag page link"))
      (is (str/includes? body "Copy post text"))
      (is (str/includes? body "copyShare(this)"))
      (is (str/includes? body "/js/share.js"))
      (is (str/includes? body "Edit details ▾"))
      (is (str/includes? body "announce-edit-panel"))
      (is (str/includes? body "Save speaker"))
      (is (str/includes? body "Planview"))
      (is (str/includes? body "A concise program bio."))
      (is (str/includes? body "+ Create Speaker")))))

(deftest create-speaker-handler-validates-profile-and-redirects-test
  (let [event (event!)
        before (person-created-count)
        invalid (announce-handlers/handle-create-speaker
                  (-> (mock/request :post "/api/events/announce-admin/speakers/create")
                      (assoc :params {:name "Bad URL"
                                      :headshot-url "https://images.example.com/valid.jpg"
                                      :website-url "not-a-url"
                                      :announce "1"})
                      (assoc-in [:path-params :slug] "announce-admin")))
        valid (announce-handlers/handle-create-speaker
                (-> (mock/request :post "/api/events/announce-admin/speakers/create")
                    (assoc :params {:name "Invited Keynote"
                                    :email "KEYNOTE@EXAMPLE.COM"
                                    :headshot-url "https://images.example.com/keynote.jpg"
                                    :website-url "https://keynote.example.com/profile"
                                    :announce "1"})
                    (assoc-in [:path-params :slug] "announce-admin")))]
    (testing "the shared profile URL rule rejects without minting an orphan"
      (is (= 422 (:status invalid)))
      (is (str/includes? (:body invalid) "Enter a full URL"))
      (is (str/includes? (:body invalid) "valid email address is required"))
      (is (= (inc before) (person-created-count))
          "only the subsequent valid request creates a person"))
    (testing "success creates a lit roster entry and returns to the marquee"
      (is (= 303 (:status valid)))
      (is (= "/events/announce-admin/announce"
             (get-in valid [:headers "Location"])))
      (is (= "Invited Keynote"
             (:name (first (public-catalog/public-speakers
                             (events/event-by-id (:id event)))))))
      (is (= "keynote@example.com"
             (:email (store/person-by-email "keynote@example.com"))))
      (is (= "https://keynote.example.com/profile"
             (get-in (store/person-by-email "keynote@example.com")
                     [:profile :website-url]))))))

(deftest create-speaker-defaults-to-a-public-card-test
  (let [event (event!)
        page (announce-handlers/handle-create-speaker-page
               (assoc-in (mock/request :get "/events/announce-admin/speakers/new")
                         [:path-params :slug] "announce-admin"))
        response (announce-handlers/handle-create-speaker
                   (-> (mock/request :post "/api/events/announce-admin/speakers/create")
                       (assoc :params {:name "Joe Blow"
                                       :email "joe@example.com"
                                       :org "IT Revolution"
                                       :title "Buffet Coach"
                                       :website-url "https://joe.example.com/speaking"})
                       (assoc-in [:path-params :slug] "announce-admin")))
        fresh-event (events/event-by-id (:id event))
        private (some #(when (= "Joe Blow" (:name %)) %)
                      (public-catalog/program-speakers fresh-event))]
    (is (= 200 (:status page)))
    (is (str/includes? (:body page) "Add a Speaker (Manually, Not from CFP)"))
    (is (str/includes? (:body page) "name=\"email\""))
    (is (str/includes? (:body page) "private speaker portal"))
    (is (str/includes? (:body page) "name=\"website-url\""))
    (is (not (str/includes? (:body page) "Publish as speaker")))
    (is (str/includes? (:body page) "name=\"announce\" type=\"hidden\" value=\"1\""))
    (is (= 303 (:status response)))
    (is (:id private))
    (is (true? (:published? private)))
    (is (= "Joe Blow"
           (:name (public-catalog/speaker-by-id fresh-event (:id private)))))))

(deftest invited-speaker-email-reuses-the-cross-event-identity-test
  (let [event (event!)
        existing (people/new-person "returning@example.com" "Returning Speaker")
        _ (store/append! {:type "person.created" :actor "fixture" :payload existing})
        before (person-created-count)
        invited (announce/create-announced-speaker!
                  (:id event)
                  {:name "A duplicate display spelling"
                   :email " RETURNING@EXAMPLE.COM "
                   :title "Keynote"
                   :announce? false}
                  "organizer@example.com")]
    (is (= (:id existing) (:id invited)))
    (is (= before (person-created-count))
        "an invited keynote is attached to the existing person, not duplicated")
    (is (= "returning@example.com" (:email invited)))
    (is (some #(= (:id existing) (:id %))
              (public-catalog/program-speakers (events/event-by-id (:id event)))))))

(deftest update-speaker-handler-persists-inline-program-details-test
  (let [event (event!)
        person (announce/create-announced-speaker!
                 (:id event)
                 {:name "Before Name"
                  :email "before@example.com"
                  :org "Before Org"
                  :title "Before Title"
                  :bio "Before bio."
                  :announce? true}
                 "organizer@example.com")
        path (str "/api/events/announce-admin/speakers/" (:id person))
        response (announce-handlers/handle-update-speaker
                   (-> (mock/request :post path)
                       (assoc :params {:name "After Name"
                                       :org "After Org"
                                       :title "After Title"
                                       :headshot-url "https://images.example.com/after.jpg"
                                       :bio "After bio."
                                       :announce "1"})
                       (assoc :path-params {:slug "announce-admin"
                                            :person-id (str (:id person))})))
        fresh-person (store/person-by-id (:id person))
        fresh-speaker (public-catalog/speaker-by-id
                        (events/event-by-id (:id event)) (:id person))]
    (is (= 303 (:status response)))
    (is (= (str "/events/announce-admin/announce#edit-" (:id person))
           (get-in response [:headers "Location"])))
    (is (= "After Name" (:name fresh-person)))
    (is (= {:org "After Org"
            :tagline "After Title"
            :headshot-url "https://images.example.com/after.jpg"
            :bio "After bio."}
           (select-keys (:profile fresh-person)
                        [:org :tagline :headshot-url :bio])))
    (is (= {:name "After Name"
            :company "After Org"
            :tagline "After Title"
            :headshot "https://images.example.com/after.jpg"
            :bio "After bio."}
           (select-keys fresh-speaker
                        [:name :company :tagline :headshot :bio])))))

(deftest existing-manual-speaker-can-be-unpublished-and-republished-test
  (let [event (event!)
        person (announce/create-announced-speaker!
                 (:id event)
                 {:name "Toggle Speaker"
                  :email "toggle@example.com"
                  :org "Toggle Co"
                  :title "CTO"
                  :bio "Still editable while private."
                  :announce? true}
                 "organizer@example.com")
        path (str "/api/events/announce-admin/speakers/" (:id person))
        request #(-> (mock/request :post path)
                     (assoc :params %)
                     (assoc :path-params {:slug "announce-admin"
                                          :person-id (str (:id person))}))
        base-params {:name "Toggle Speaker"
                     :org "Toggle Co"
                     :title "CTO"
                     :bio "Still editable while private."}
        unpublished (announce-handlers/handle-update-speaker (request base-params))
        private-event (events/event-by-id (:id event))
        private-speaker (public-catalog/program-speaker-by-id private-event (:id person))
        private-page (announce-handlers/handle-announce-page
                       (assoc-in (mock/request :get "/events/announce-admin/announce")
                                 [:path-params :slug] "announce-admin"))
        private-cfp (server/render-cfp
                      (mock/request :get "/cfp/announce-admin") private-event)
        republished (announce-handlers/handle-update-speaker
                      (request (assoc base-params :announce "1")))
        public-event (events/event-by-id (:id event))
        public-cfp (server/render-cfp
                     (mock/request :get "/cfp/announce-admin") public-event)]
    (testing "off preserves identity and organizer access but removes public surfaces"
      (is (= 303 (:status unpublished)))
      (is (= (:id person) (:id (store/person-by-id (:id person)))))
      (is (false? (:published? private-speaker)))
      (is (nil? (public-catalog/speaker-by-id private-event (:id person))))
      (is (str/includes? (:body private-page) "Toggle Speaker"))
      (is (str/includes? (:body private-page) "Not published"))
      (is (= 200 (:status private-cfp)))
      (is (not (str/includes? (:body private-cfp) "Toggle Speaker"))))
    (testing "on restores the same person's public program and brag page"
      (is (= 303 (:status republished)))
      (is (= (:id person)
             (:id (public-catalog/speaker-by-id public-event (:id person)))))
      (is (str/includes? (:body public-cfp) "Toggle Speaker")))))

(deftest update-speaker-handler-rejects-invalid-input-before-writing-test
  (let [event (event!)
        person (announce/create-announced-speaker!
                 (:id event) {:name "Stable Name" :email "stable@example.com"
                              :announce? true}
                 "organizer@example.com")
        before-events (store/read-events)
        response (announce-handlers/handle-update-speaker
                   (-> (mock/request :post "/api/events/announce-admin/speakers/person")
                       (assoc :params {:name " " :headshot-url "not-a-url"})
                       (assoc :path-params {:slug "announce-admin"
                                            :person-id (str (:id person))})))]
    (is (= 422 (:status response)))
    (is (= before-events (store/read-events)))
    (is (= "Stable Name" (:name (store/person-by-id (:id person)))))))

(deftest update-speaker-handler-updates-an-accepted-cfp-speaker-everywhere-test
  (let [event (event!)
        submission (submission! event {:name "CFP Speaker"
                                       :email "cfp-speaker@example.com"
                                       :title "Accepted Talk"})
        person-id (get-in submission [:speakers 0 :person-id])]
    (reviews/set-status! (:id submission) "Accepted" "organizer@example.com")
    (inform/inform! event (store/submission-by-id (:id submission))
                    "organizer@example.com")
    (let [response (announce-handlers/handle-update-speaker
                     (-> (mock/request :post
                                       (str "/api/events/announce-admin/speakers/"
                                            person-id))
                         (assoc :params {:name "Updated CFP Speaker"
                                         :org "Updated Company"
                                         :title "Updated Tagline"
                                         :headshot-url "https://images.example.com/updated.jpg"
                                         :bio "Updated biography."})
                         (assoc :path-params {:slug "announce-admin"
                                              :person-id (str person-id)})))
          fresh-event (events/event-by-id (:id event))
          fresh-speaker (public-catalog/speaker-by-id fresh-event (str person-id))]
      (is (= 303 (:status response)))
      (is (= {:name "Updated CFP Speaker"
              :company "Updated Company"
              :tagline "Updated Tagline"
              :headshot "https://images.example.com/updated.jpg"
              :bio "Updated biography."}
             (select-keys fresh-speaker
                          [:name :company :tagline :headshot :bio]))))))
