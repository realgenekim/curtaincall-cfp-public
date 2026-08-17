(ns cfp-scheduler-killer.predicates-cfp-test
  (:require [cfp-scheduler-killer.rubric-predicates :as predicates]
            [clojure.test :refer [deftest is testing]])
  (:import (java.nio.charset StandardCharsets)
           (java.util Base64)))

(defn- utf8-bytes [s]
  (.getBytes ^String s StandardCharsets/UTF_8))

(defn- base64 [captured]
  (.encodeToString (Base64/getEncoder)
                   (if (string? captured) (utf8-bytes captured) captured)))

(def ^:private closed-answer-request
  (str "POST /api/submissions/sub-42/answers HTTP/1.1\r\n"
       "Content-Type: application/x-www-form-urlencoded\r\n"
       "\r\n"
       "answer-talk-title=Changed"))

(defn- response [status reason]
  (str "HTTP/1.1 " status " " reason "\r\n"
       "Content-Type: text/plain\r\n"
       "\r\n"
       "captured response"))

(def ^:private calendar-before
  (str "BEGIN:VCALENDAR\r\n"
       "VERSION:2.0\r\n"
       "BEGIN:VEVENT\r\n"
       "UID:session-a@curtain-call\r\n"
       "DTSTART:20261014T130000Z\r\n"
       "SUMMARY:Opening\r\n"
       "END:VEVENT\r\n"
       "BEGIN:VEVENT\r\n"
       "UID:session-b@curtain-call\r\n"
       "DTSTART:20261014T140000Z\r\n"
       "SUMMARY:Deep dive\r\n"
       "END:VEVENT\r\n"
       "END:VCALENDAR\r\n"))

(def ^:private calendar-after-amend
  (str "BEGIN:VCALENDAR\r\n"
       "VERSION:2.0\r\n"
       "BEGIN:VEVENT\r\n"
       "UID:session-b@curtain-call\r\n"
       "DTSTART:20261014T143000Z\r\n"
       "LOCATION:Hall B\r\n"
       "SUMMARY:Deep dive — amended\r\n"
       "END:VEVENT\r\n"
       "BEGIN:VEVENT\r\n"
       "UID:session-a@curtain-call\r\n"
       "DTSTART:20261014T133000Z\r\n"
       "SUMMARY:Opening — amended\r\n"
       "END:VEVENT\r\n"
       "END:VCALENDAR\r\n"))

;; INTENT-TEST: PRED-001
(deftest closed-cfp-answer-post-predicate-test
  (let [request-bytes (utf8-bytes closed-answer-request)
        refusal-bytes (utf8-bytes (response 422 "Unprocessable Entity"))
        evidence {:cfp-state :closed
                  :request-bytes request-bytes
                  :response-bytes refusal-bytes}
        result (predicates/closed-cfp-answer-post-refused evidence)]
    (testing "the exact closed-CFP answer POST and a 4xx response pass"
      (is (true? (:verdict result)))
      (is (= 422 (get-in result [:observed :response-status])))
      (is (= (base64 request-bytes)
             (get-in result [:examined :request-bytes-base64])))
      (is (= (base64 refusal-bytes)
             (get-in result [:examined :response-bytes-base64]))))

    (testing "a success or server error is a product failure, not a refusal pass"
      (doseq [status [200 302 500]]
        (is (false? (:verdict
                      (predicates/closed-cfp-answer-post-refused
                        (assoc evidence :response-bytes
                               (utf8-bytes (response status "Captured")))))))))

    (testing "a near endpoint match does not pass as CFP-16 evidence"
      (let [result (predicates/closed-cfp-answer-post-refused
                     (assoc evidence :request-bytes
                            (utf8-bytes "POST /api/submissions/sub-42/answer HTTP/1.1\r\n\r\n")))]
        (is (= :cannot-judge (:verdict result)))
        (is (= [:answer-endpoint-post] (:missing result)))))))

;; INTENT-TEST: PRED-001
(deftest stable-uid-predicate-test
  (let [before-bytes (utf8-bytes calendar-before)
        after-bytes (utf8-bytes calendar-after-amend)
        evidence {:before-ics-bytes before-bytes
                  :after-ics-bytes after-bytes}
        result (predicates/stable-ics-uids evidence)]
    (testing "amended fields and event order do not matter when exact UIDs remain"
      (is (true? (:verdict result)))
      (is (= #{"session-a@curtain-call" "session-b@curtain-call"}
             (set (get-in result [:observed :before-uids]))))
      (is (= (base64 before-bytes)
             (get-in result [:examined :before-ics-bytes-base64])))
      (is (= (base64 after-bytes)
             (get-in result [:examined :after-ics-bytes-base64]))))

    (testing "a near UID is not equal"
      (let [changed (utf8-bytes (.replace calendar-after-amend
                                          "session-b@curtain-call"
                                          "session-b-v2@curtain-call"))]
        (is (false? (:verdict
                      (predicates/stable-ics-uids
                        (assoc evidence :after-ics-bytes changed)))))))

    (testing "present ICS evidence with missing or duplicate UIDs is a failure"
      (is (false? (:verdict
                    (predicates/stable-ics-uids
                      (assoc evidence :before-ics-bytes
                             (utf8-bytes (.replace calendar-before
                                                   "UID:session-a@curtain-call\r\n"
                                                   "")))))))
      (is (false? (:verdict
                    (predicates/stable-ics-uids
                      (assoc evidence :after-ics-bytes
                             (utf8-bytes (.replace calendar-after-amend
                                                   "UID:session-b@curtain-call"
                                                   "UID:session-a@curtain-call"))))))))))

;; INTENT-TEST: PRED-001
(deftest missing-predicate-evidence-is-cannot-judge-test
  (testing "CFP evidence names each missing capture instead of scoring false"
    (is (= {:verdict :cannot-judge
            :missing [:response-bytes]
            :examined {:cfp-state :closed
                       :request-bytes-base64 (base64 closed-answer-request)}
            :observed {}}
           (into {}
                 (predicates/closed-cfp-answer-post-refused
                   {:cfp-state :closed
                    :request-bytes closed-answer-request}))))
    (is (= [:closed-cfp-state]
           (:missing (predicates/closed-cfp-answer-post-refused
                       {:cfp-state :open
                        :request-bytes closed-answer-request
                        :response-bytes (response 422 "Unprocessable Entity")})))))

  (testing "calendar evidence names the missing side instead of scoring false"
    (is (= {:verdict :cannot-judge
            :missing [:after-ics-bytes]
            :examined {:before-ics-bytes-base64 (base64 calendar-before)}
            :observed {}}
           (into {}
                 (predicates/stable-ics-uids
                   {:before-ics-bytes calendar-before}))))))
