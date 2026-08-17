(ns cfp-scheduler-killer.resource-pages-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.resource-pages :as resource-pages]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock])
  (:import
   (java.time LocalDateTime)))

(use-fixtures :each with-temp-store)

(defn- event! []
  (events/create-event!
   {:name "Resource Pages Summit"
    :slug "resource-pages"
    :tz "America/New_York"
    :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
    :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
   "kaocha"))

(defn- login! [handler]
  (let [token (auth/issue-token! "organizer@example.com")
        response (handler (mock/request :get (str "/auth/" token)))]
    (first (str/split (first (get-in response [:headers "Set-Cookie"])) #";"))))

(deftest resource-pages-are-fact-backed-published-and-embeddable-test
  (let [event (event!)
        committee-id (:id (first (events/committees-for-event (:id event))))
        _ (committees/add-member! committee-id {:name "Organizer" :email "organizer@example.com" :role "chair"} "kaocha")
        draft (:page (resource-pages/save-page!
                      event {:title "Draft travel notes" :body "Private venue notes." :published? false}
                      "organizer"))
        public (:page (resource-pages/save-page!
                       event {:title "Venue guide" :body "Arrive through the north entrance.\n\nCheck in at registration."
                              :published? true}
                       "organizer"))
        handler (server/create-app)
        request #(-> (mock/request :get %) (mock/header "host" "wiki.example"))
        index (handler (request "/program/resource-pages/resources"))
        program (handler (request "/program/resource-pages"))
        page (handler (request "/program/resource-pages/resources/venue-guide"))
        embed (handler (request "/program/resource-pages/resources/venue-guide?embed=1"))
        private-page (handler (request (str "/program/resource-pages/resources/" (:slug draft))))]
    (testing "the organizer write is an append-only fact, not a separate page store"
      (is (= [draft public]
             (sort-by :title (resource-pages/pages-for-event event))))
      (is (= [public] (resource-pages/published-pages event))))
    (testing "anonymous readers receive only published resource pages"
      (is (= 200 (:status index)))
      (is (str/includes? (:body program) "href=\"/program/resource-pages/resources\">Resources</a>"))
      (is (str/includes? (:body index) "Venue guide"))
      (is (not (str/includes? (:body index) "Draft travel notes")))
      (is (= 200 (:status page)))
      (is (str/includes? (:body page) "Arrive through the north entrance."))
      (is (= 404 (:status private-page))))
    (testing "the public page has a small read-only HTML embed surface"
      (is (= 200 (:status embed)))
      (is (str/includes? (:body embed) "resource-page"))
      (is (str/includes? (:body embed) "Check in at registration."))
      (is (not (str/includes? (:body embed) "Public resources"))))
    (testing "the organizer editor appends a page fact and offers the exact iframe"
      (let [cookie (login! handler)
            response (handler (-> (mock/request :post "/api/events/resource-pages/resources"
                                                 {:title "Accessibility" :slug "accessibility"
                                                  :body "Step-free access is available." :published "true"})
                                  (mock/header "cookie" cookie)))
            page-id (:id (resource-pages/page-by-slug event "accessibility"))
            editor (handler (-> (mock/request :get (str "/events/resource-pages/resources?page=" page-id))
                                (mock/header "host" "wiki.example")
                                (mock/header "cookie" cookie)))]
        (is (= 303 (:status response)))
        (is (= 200 (:status editor)))
        (is (str/includes? (:body editor) "HTML embed"))
        (is (str/includes? (:body editor) "resource-pages/resources/accessibility?embed=1"))))))
