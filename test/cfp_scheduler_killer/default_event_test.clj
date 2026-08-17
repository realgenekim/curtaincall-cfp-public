(ns cfp-scheduler-killer.default-event-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.handlers.events :as event-handlers]
   [cfp-scheduler-killer.people :as people]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [cfp-scheduler-killer.views.event-setup :as event-setup]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each with-temp-store)

(defn- fact! [type payload]
  (store/append! {:type type :actor "fixture" :payload payload}))

(defn- count-substring [text needle]
  (count (re-seq (re-pattern (java.util.regex.Pattern/quote needle)) text)))

(deftest selectable-default-event-test
  (let [person {:id "p1" :email "gene@example.com" :name "Gene"}
        alpha {:id "e1" :slug "alpha" :name "Alpha" :starts-on "2030-01-01"}
        beta {:id "e2" :slug "beta" :name "Beta" :starts-on "2030-02-01"}]
    (fact! "person.created" person)
    (fact! "event.created" alpha)
    (fact! "event.created" beta)

    (testing "the domain verb appends one fact and projects the person's choice"
      (people/set-default-event! "p1" "e1" "gene@example.com")
      (is (= "e1" (:default-event-id (store/person-by-id "p1"))))
      (is (= 1 (count (filter #(= "person.default-event-set" (:type %))
                              (store/read-events)))))
      (people/set-default-event! "p1" "e1" "gene@example.com")
      (is (= 1 (count (filter #(= "person.default-event-set" (:type %))
                              (store/read-events))))
          "re-selecting the current default is a no-op"))

    (testing "the persisted default outranks a different last-visited event"
      (reset! events/working-events {})
      (events/remember-working-event! "p1" "e2")
      (is (= "e1" (:id (events/working-event "p1" "e1" [alpha beta])))))

    (testing "the POST moves the star only among events visible to this person"
      (with-redefs [auth/current-person (constantly (store/person-by-id "p1"))
                    events/events-for-person (constantly [alpha beta])]
        (is (= 303 (:status (event-handlers/handle-default-event
                              {:path-params {:slug "beta"}}))))
        (is (= "e2" (:default-event-id (store/person-by-id "p1"))))))

    (testing "the list renders one keyboard-operable filled star to the row's left"
      (let [html (event-setup/events-list-page [alpha beta]
                                               (store/person-by-id "p1"))]
        (is (= 1 (count-substring html "★")))
        (is (= 1 (count-substring html "☆")))
        (is (str/includes? html "aria-label=\"Beta is your default event\""))
        (is (str/includes? html "action=\"/api/events/beta/default\""))))

    (testing "the route is named and bound in the production route table"
      (is (= #'event-handlers/handle-default-event
             (some (fn [[path route]]
                     (when (= "/api/events/:slug/default" path)
                       (get-in route [:post :handler])))
                   (server/make-routes)))))))
