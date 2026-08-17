(ns cfp-scheduler-killer.io-email-test
  (:require
   [cfp-scheduler-killer.io.email :as email]
   [cfp-scheduler-killer.io.email.cloudflare :as cloudflare]
   [cfp-scheduler-killer.io.email.resend :as resend]
   [clojure.test :refer [deftest is testing]]))

(defn- response [status body]
  {:status status :body body})

(def letter
  {:from "Ann <ann@example.com>" :to "speaker@example.com"
   :reply-to "ann@example.com" :subject "Your portal" :body "Open it"
   :ics "BEGIN:VCALENDAR\nEND:VCALENDAR" :ics-filename "session.ics"})

(deftest provider-dispatch-is-one-small-algebra
  (testing "a recording fake receives config and the provider-neutral letter"
    (let [seen (atom [])]
      (binding [email/*send-fn* (fn [cfg message]
                                  (swap! seen conj [cfg message])
                                  {:ok true :message-id "recorded-1"})]
        (is (= {:ok true :message-id "recorded-1"}
               (email/send-with-config! {:provider :resend} letter)))
        (is (= [[{:provider :resend} letter]] @seen)))))
  (is (false? (:ok (email/send-with-config! {:provider :unknown} letter)))))

(deftest resend-and-cloudflare-share-the-normalized-contract
  (binding [resend/*http-post!* (fn [_ _] (response 200 "{\"id\":\"re_123\"}"))
            cloudflare/*http-post!*
            (fn [_ _] (response 200 "{\"success\":true,\"result\":{\"id\":\"cf_123\"}}"))]
    (is (= "re_123" (:message-id
                      (resend/send! {:api-key "secret" :from "ann@example.com"} letter))))
    (is (= "cf_123" (:message-id
                      (cloudflare/send! {:api-token "secret" :account-id "acct"
                                         :from "ann@example.com"} letter)))))
  (testing "provider errors are data, not exceptions"
    (binding [resend/*http-post!* (fn [_ _] (response 422 "{\"message\":\"bad sender\"}"))]
      (is (= "bad sender" (:error
                            (resend/send! {:api-key "secret" :from "ann@example.com"}
                                          letter)))))))

(deftest provider-payloads-preserve-reply-to-and-calendar
  (let [resend-payload (resend/payload {:from "fallback@example.com"} letter)
        cloudflare-payload (cloudflare/payload {:from "fallback@example.com"} letter)]
    (is (= "ann@example.com" (:reply_to resend-payload)))
    (is (= "ann@example.com" (:reply_to cloudflare-payload)))
    (is (= "session.ics" (get-in resend-payload [:attachments 0 :filename])))
    (is (= "text/calendar" (get-in cloudflare-payload [:attachments 0 :type])))))
