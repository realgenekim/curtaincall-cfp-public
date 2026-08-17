(ns cfp-scheduler-killer.api-key-security-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.exports :as exports]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock])
  (:import
   (java.time LocalDateTime)))

(use-fixtures :each with-temp-store)

(defn- make-event! []
  (events/create-event!
    {:name "API Key Security Summit"
     :slug "api-key-security"
     :tz "America/New_York"
     :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
     :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
    "kaocha"))

(defn- append-historical-key! [event row]
  (store/append! {:type "api-key.created"
                  :event-id (:id event)
                  :actor "migration-fixture"
                  :payload (merge {:id (store/new-id)
                                   :event-id (:id event)
                                   :label "Historical key"
                                   :created-at (store/now-iso)
                                   :created-by "migration-fixture"}
                                  row)}))

(deftest stored-scopes-fail-closed-with-narrow-legacy-compatibility-test
  (let [event (make-event!)
        historical-secret "historical-plaintext-secret"
        corrupt-secret "corrupt-plaintext-secret"
        corrupt-type-secret "corrupt-type-secret"
        hash-downgrade-secret "hash-downgrade-secret"]
    (append-historical-key! event {:key historical-secret})
    (append-historical-key! event {:key corrupt-secret :scope "admin"})
    (append-historical-key! event {:key corrupt-type-secret :scope {:admin true}})
    (append-historical-key! event {:key hash-downgrade-secret
                                   :key-hash "not-the-token-hash"
                                   :scope "organizer"})
    (let [event (events/event-by-slug "api-key-security")]
      (testing "only a truly absent historical scope retains organizer authority"
        (is (= :organizer (:scope (exports/api-key-context event historical-secret)))))
      (testing "a present malformed scope refuses the matching credential"
        (is (nil? (exports/api-key-context event corrupt-secret)))
        (is (nil? (exports/api-key-context event corrupt-type-secret))))
      (testing "a present hash never downgrades to the legacy plaintext arm"
        (is (nil? (exports/api-key-context event hash-downgrade-secret)))))))

(deftest key-authentication-controls-actor-and-response-cache-test
  (let [event (make-event!)
        key-row (exports/create-api-key! event "Organizer agent" :organizer nil
                                         "gene@example.com")
        secret (:key key-row)
        event (events/event-by-slug "api-key-security")
        req (-> (mock/request :get "/api/v1/events/api-key-security")
                (mock/header "authorization" (str "Bearer " secret)))
        api-actor (ns-resolve 'cfp-scheduler-killer.handlers.public-api 'api-actor)]
    (testing "the authenticated key, not an incidental signed-in session, owns the actor"
      (with-redefs [auth/current-person (constantly {:email "wrong-session@example.com"})]
        (is (= (exports/api-key-actor (exports/api-key-context event secret))
               (api-actor req event)))))
    (testing "a token-authorized JSON response can never be shared by a cache"
      (let [response ((server/create-app) req)]
        (is (= 200 (:status response)))
        (is (= "private, no-store" (get-in response [:headers "Cache-Control"])))))))
