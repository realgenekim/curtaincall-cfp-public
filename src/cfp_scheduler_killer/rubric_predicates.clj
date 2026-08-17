(ns cfp-scheduler-killer.rubric-predicates
  "Pure, byte-bound verdicts for rubric facts that do not need a model."
  (:require [closed-record.core :as cr]
            [clojure.string :as str]
            [com.fulcrologic.guardrails.core :refer [>defn]])
  (:import (java.nio.charset StandardCharsets)
           (java.util Base64)))

;; INTENT: PRED-001

(def ^:private byte-array-class (Class/forName "[B"))

(defn- evidence-bytes [captured]
  (cond
    (string? captured) (.getBytes ^String captured StandardCharsets/UTF_8)
    (instance? byte-array-class captured) captured
    :else nil))

(defn- evidence-text [captured]
  (some-> (evidence-bytes captured)
          (String. StandardCharsets/UTF_8)))

(defn- base64 [captured]
  (some->> (evidence-bytes captured)
           (.encodeToString (Base64/getEncoder))))

(defn- result [verdict examined missing observed]
  (cr/closed-record {:verdict verdict
                     :examined examined
                     :missing (vec missing)
                     :observed observed}))

(defn- examined [evidence keys]
  (reduce (fn [receipt key]
            (cond
              (and (= :cfp-state key) (contains? evidence key))
              (assoc receipt key (get evidence key))

              (contains? evidence key)
              (if-let [encoded (base64 (get evidence key))]
                (assoc receipt (keyword (str (name key) "-base64")) encoded)
                receipt)

              :else receipt))
          {}
          keys))

(defn- request-answer-post? [request-text]
  (boolean
    (re-find
      #"(?s)\APOST[ \t]+/api/submissions/[^/?\s]+/answers(?:\?[^\s]*)?[ \t]+HTTP/\d+(?:\.\d+)?(?:\r?\n|$)"
      request-text)))

(defn- response-status [response-text]
  (some-> (re-find
            #"(?s)\AHTTP/\d+(?:\.\d+)?[ \t]+(\d{3})(?:[ \t]+[^\r\n]*)?(?:\r?\n|$)"
            response-text)
          second
          parse-long))

(>defn closed-cfp-answer-post-refused
       "Judge CFP-16 from one immutable raw HTTP exchange.

   Evidence is `{:cfp-state :closed :request-bytes ... :response-bytes ...}`.
   Raw captures may be byte arrays or strings. `:examined` retains a lossless
   base64 copy of each exact capture. A wrong scenario or unusable capture is a
   measurement gap; only the exact answer POST with a parsed response can pass
   or fail."
       [evidence]
       [map? => map?]
       (let [keys [:cfp-state :request-bytes :response-bytes]
             examined* (examined evidence keys)
             request-text (evidence-text (:request-bytes evidence))
             response-text (evidence-text (:response-bytes evidence))
             missing (cond-> []
                       (not= :closed (:cfp-state evidence)) (conj :closed-cfp-state)
                       (nil? request-text) (conj :request-bytes)
                       (nil? response-text) (conj :response-bytes))]
         (cond
           (seq missing)
           (result :cannot-judge examined* missing {})

           (not (request-answer-post? request-text))
           (result :cannot-judge examined* [:answer-endpoint-post] {})

           :else
           (if-let [status (response-status response-text)]
             (result (<= 400 status 499)
                     examined*
                     []
                     {:response-status status})
             (result :cannot-judge examined* [:response-status] {})))))

(defn- unfold-lines [calendar-text]
  (-> calendar-text
      (str/replace #"\r\n[ \t]" "")
      (str/replace #"\n[ \t]" "")
      (str/split #"\r\n|\n|\r")))

(defn- vevents [calendar-text]
  (loop [lines (unfold-lines calendar-text)
         current nil
         events []
         malformed? false]
    (if-let [line (first lines)]
      (cond
        (= "BEGIN:VEVENT" (str/upper-case line))
        (recur (rest lines) [] events (or malformed? (some? current)))

        (= "END:VEVENT" (str/upper-case line))
        (if (some? current)
          (recur (rest lines) nil (conj events current) malformed?)
          (recur (rest lines) nil events true))

        (some? current)
        (recur (rest lines) (conj current line) events malformed?)

        :else
        (recur (rest lines) nil events malformed?))
      {:events events :malformed? (or malformed? (some? current))})))

(defn- uid-values [event-lines]
  (->> event-lines
       (keep #(some-> (re-find #"(?i)^UID(?:;[^:]*)?:(.*)$" %) second))
       vec))

(defn- calendar-facts [calendar-text]
  (let [{:keys [events malformed?]} (vevents calendar-text)
        uid-groups (mapv uid-values events)
        uids (mapv first uid-groups)
        one-nonblank-uid? (every? #(and (= 1 (count %))
                                        (not (str/blank? (first %))))
                                  uid-groups)
        unique? (= (count uids) (count (distinct uids)))]
    {:uids uids
     :event-count (count events)
     :valid? (and (not malformed?)
                  (seq events)
                  one-nonblank-uid?
                  unique?)}))

(>defn stable-ics-uids
       "Judge design rule 8 from captured calendar bytes before and after amend.

   VEVENT order and non-UID amendments are ignored. Every captured VEVENT must
   have exactly one nonblank, unique UID and the exact UID set must be equal.
   Missing captures are measurement gaps; present malformed calendars fail."
       [evidence]
       [map? => map?]
       (let [keys [:before-ics-bytes :after-ics-bytes]
             examined* (examined evidence keys)
             before-text (evidence-text (:before-ics-bytes evidence))
             after-text (evidence-text (:after-ics-bytes evidence))
             missing (cond-> []
                       (nil? before-text) (conj :before-ics-bytes)
                       (nil? after-text) (conj :after-ics-bytes))]
         (if (seq missing)
           (result :cannot-judge examined* missing {})
           (let [before (calendar-facts before-text)
                 after (calendar-facts after-text)
                 observed {:before-uids (:uids before)
                           :after-uids (:uids after)
                           :before-valid? (:valid? before)
                           :after-valid? (:valid? after)}]
             (result (and (:valid? before)
                          (:valid? after)
                          (= (set (:uids before)) (set (:uids after))))
                     examined*
                     []
                     observed)))))
