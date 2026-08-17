(ns cfp-scheduler-killer.mail-projection-test
  (:require
   [cfp-scheduler-killer.io.email :as email-port]
   [cfp-scheduler-killer.mail :as mail]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :each with-temp-store)

(deftest outbox-from-snapshot-matches-durable-event-replay-test
  (let [event-id "event-1"]
    (doseq [event [{:type "email.queued"
                    :event-id event-id
                    :payload {:email-id "email-1"
                              :to "ada@example.com"
                              :subject "Welcome"
                              :body "Hello, Ada."
                              :at "2026-08-17T09:00:00Z"}}
                   {:type "email.approved"
                    :event-id event-id
                    :payload {:email-id "email-1"}}
                   {:type "email.sent"
                    :event-id event-id
                    :payload {:email-id "email-1" :via "dev-log"}}
                   {:type "email.queued"
                    :event-id event-id
                    :payload {:email-id "email-2"
                              :to "grace@example.com"
                              :subject "Reminder"
                              :body "Hello, Grace."
                              :at "2026-08-17T10:00:00Z"}}
                   {:type "email.failed"
                    :event-id event-id
                    :payload {:email-id "email-2"
                              :error "connection refused"}}]]
      (store/append! event))
    (let [snapshot (store/snapshot)
          legacy-outbox (mail/outbox (assoc snapshot :log (store/read-events)) event-id)
          projected-outbox
          (with-redefs [store/read-events
                        (fn [& _]
                          (throw (ex-info "Projection reread the durable log" {})))]
            (mail/outbox snapshot event-id))]
      (is (= (pr-str legacy-outbox)
             (pr-str projected-outbox))
          "the snapshot projection must be byte-for-byte identical to durable replay"))))

(deftest send-now-records-a-synchronous-success-test
  (with-redefs [mail/provider-delivery-enabled? (constantly false)]
    (let [result (mail/send-now! {:to "ada@example.com"
                                  :subject "Sign in"
                                  :body "Magic link"}
                                 {:kind "magic-link"
                                  :actor "system"})
          events (store/read-events)]
      (is (= :sent (:mode result)))
      (is (= ["email.queued" "email.approved" "email.sent"]
             (mapv :type events)))
      (is (= "dev-log" (get-in (last events) [:payload :via]))))))

(deftest send-now-records-a-synchronous-failure-test
  (with-redefs [mail/provider-delivery-enabled? (constantly true)
                mail/config (constantly {:host "smtp.example.com"
                                         :from "curtain-call@example.com"})
                email-port/send-with-config! (fn [_ _]
                                               {:ok false
                                                :error "connection refused"})]
    (let [result (mail/send-now! {:to "grace@example.com"
                                  :subject "Sign in"
                                  :body "Magic link"}
                                 {:kind "magic-link"
                                  :actor "system"})
          events (store/read-events)]
      (is (= :failed (:mode result)))
      (is (= ["email.queued" "email.approved" "email.failed"]
             (mapv :type events)))
      (is (= "connection refused"
             (get-in (last events) [:payload :error]))))))
