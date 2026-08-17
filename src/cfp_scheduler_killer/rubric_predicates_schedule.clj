(ns cfp-scheduler-killer.rubric-predicates-schedule
  "Pure, reproducible rubric predicates for captured schedule evidence.

   Every public function accepts a map of UTF-8 JSON strings. Results retain
   those strings verbatim under :examined; callers can therefore reproduce a
   verdict without consulting a model, service, or clock."
  (:require
   [clojure.data.json :as json])
  (:import
   (java.time LocalDate)
   (java.time.temporal ChronoUnit)))

(defn- examined
  [evidence names]
  (mapv (fn [evidence-name]
          {:name evidence-name :bytes (get evidence evidence-name)})
        names))

(defn- cannot-judge
  [criterion evidence names missing]
  {:criterion criterion
   :verdict :cannot-judge
   :missing (vec missing)
   :examined (examined evidence names)})

(defn- judged
  [criterion evidence names verdict]
  {:criterion criterion
   :verdict (boolean verdict)
   :examined (examined evidence names)})

(defn- parse-json
  [raw]
  (when (string? raw)
    (try
      {:value (json/read-str raw)}
      (catch Exception _
        {:invalid? true}))))

(defn- missing-fields
  [m fields prefix]
  (keep (fn [field]
          (when-not (contains? m field)
            (keyword (str prefix "/" field))))
        fields))

(def ^:private schedule-fields
  ["day" "dayNumber" "dayLabel"
   "startMinute" "endMinute" "start" "end" "roomId" "room"])

(defn- canonical-session
  [schedule session-id]
  (some (fn [day]
          (some (fn [item]
                  (when (and (= "session" (get item "kind"))
                             (= session-id (get item "sessionId")))
                    {:day day :item item}))
                (get day "items")))
        (get schedule "days")))

(defn- day-number
  [starts-on day]
  (inc (.between ChronoUnit/DAYS starts-on day)))

;; INTENT: PRED-003 — structural schedule claims require complete exact evidence.
(defn spk-11
  "Judge a speaker's captured session schedule against the captured canonical
   schedule. Evidence keys are :event-json, :canonical-schedule-json, and
   :speaker-schedule-json. The speaker JSON is an API session object containing
   sessionId/id and schedule, or the equivalent flattened schedule object."
  [evidence]
  (let [criterion :SPK-11
        names [:event-json :canonical-schedule-json :speaker-schedule-json]
        absent (keep #(when-not (string? (get evidence %)) %) names)]
    (if (seq absent)
      (cannot-judge criterion evidence names absent)
      (let [event* (parse-json (:event-json evidence))
            canonical* (parse-json (:canonical-schedule-json evidence))
            speaker* (parse-json (:speaker-schedule-json evidence))
            invalid (keep (fn [[evidence-name parsed]]
                            (when (:invalid? parsed)
                              (keyword (str (name evidence-name) "/valid-json"))))
                          [[:event-json event*]
                           [:canonical-schedule-json canonical*]
                           [:speaker-schedule-json speaker*]])]
        (if (seq invalid)
          (cannot-judge criterion evidence names invalid)
          (let [event (:value event*)
                canonical (:value canonical*)
                speaker (:value speaker*)
                session-id (or (get speaker "sessionId") (get speaker "id"))
                speaker-schedule (or (get speaker "schedule") speaker)
                base-missing (concat
                               (missing-fields event ["startsOn" "endsOn"] "event")
                               (missing-fields canonical ["days"] "canonical-schedule")
                               (when-not session-id [:speaker-schedule/sessionId])
                               (missing-fields speaker-schedule schedule-fields
                                               "speaker-schedule"))]
            (if (seq base-missing)
              (cannot-judge criterion evidence names base-missing)
              (let [match (canonical-session canonical session-id)]
                (if-not match
                  (judged criterion evidence names false)
                  (let [day-row (:day match)
                        item (:item match)
                        canonical-missing
                        (concat
                         (missing-fields day-row ["date" "label"] "canonical-day")
                         (missing-fields item
                                         ["day" "startMinute" "endMinute"
                                          "start" "end" "roomId" "room"]
                                         "canonical-session"))]
                    (if (seq canonical-missing)
                      (cannot-judge criterion evidence names canonical-missing)
                      (let [dates (try
                                    {:start (LocalDate/parse (get event "startsOn"))
                                     :end (LocalDate/parse (get event "endsOn"))
                                     :day (LocalDate/parse (get day-row "date"))}
                                    (catch Exception _ {:invalid? true}))]
                        (if (:invalid? dates)
                          (cannot-judge criterion evidence names
                                        [:event-and-canonical-schedule/valid-dates])
                          (let [{:keys [start end day]} dates
                                in-event? (and (not (.isBefore day start))
                                               (not (.isAfter day end)))
                                expected {"day" (get item "day")
                                          "dayNumber" (day-number start day)
                                          "dayLabel" (get day-row "label")
                                          "startMinute" (get item "startMinute")
                                          "endMinute" (get item "endMinute")
                                          "start" (get item "start")
                                          "end" (get item "end")
                                          "roomId" (get item "roomId")
                                          "room" (get item "room")}
                                actual (select-keys speaker-schedule schedule-fields)]
                            (judged criterion evidence names
                                    (and in-event? (= expected actual)))))))))))))))))

(def ^:private placement-fields
  ["submissionId" "title" "day" "startMinute" "endMinute" "roomId"])

(defn- placement-projection
  [placement]
  {"submissionId" (get placement "submission-id")
   "title" (get placement "title")
   "day" (get placement "day")
   "startMinute" (get placement "start")
   "endMinute" (get placement "end")
   "roomId" (get placement "room-id")})

(defn- placement-pair
  [placements]
  (when (and (vector? placements) (= 2 (count placements)))
    (let [pair (into {} (map (juxt #(get % "submissionId") identity)) placements)]
      (when (= 2 (count pair)) pair))))

(defn- conflict-pair
  [conflict]
  (let [placements (mapv placement-projection [(get conflict "a") (get conflict "b")])]
    (into {} (map (juxt #(get % "submissionId") identity)) placements)))

(defn- judge-conflict
  [criterion conflict-type identity-field message-field evidence]
  (let [names [:conflicts-json :expected-overlap-json]
        absent (keep #(when-not (string? (get evidence %)) %) names)]
    (if (seq absent)
      (cannot-judge criterion evidence names absent)
      (let [conflicts* (parse-json (:conflicts-json evidence))
            expected* (parse-json (:expected-overlap-json evidence))
            invalid (keep (fn [[evidence-name parsed]]
                            (when (:invalid? parsed)
                              (keyword (str (name evidence-name) "/valid-json"))))
                          [[:conflicts-json conflicts*]
                           [:expected-overlap-json expected*]])]
        (if (seq invalid)
          (cannot-judge criterion evidence names invalid)
          (let [conflicts-root (:value conflicts*)
                conflicts (get conflicts-root "conflicts")
                expected (:value expected*)
                required [identity-field message-field "placements"]
                missing (concat
                         (when-not (contains? conflicts-root "conflicts")
                           [:conflicts-json/conflicts])
                         (missing-fields expected required "expected-overlap"))
                expected-pair (placement-pair (get expected "placements"))
                placement-missing
                (when expected-pair
                  (mapcat #(missing-fields % placement-fields "expected-placement")
                          (get expected "placements")))]
            (cond
              (seq missing)
              (cannot-judge criterion evidence names missing)

              (not (vector? conflicts))
              (cannot-judge criterion evidence names [:conflicts-json/conflicts-array])

              (nil? expected-pair)
              (cannot-judge criterion evidence names
                            [:expected-overlap/two-distinct-placements])

              (seq placement-missing)
              (cannot-judge criterion evidence names placement-missing)

              :else
              (judged
               criterion evidence names
               (boolean
                (some (fn [conflict]
                        (and (= conflict-type (get conflict "type"))
                             (= (get expected identity-field)
                                (case conflict-type
                                  "speaker" (get conflict "person-id")
                                  "room" (get-in conflict ["a" "room-id"])))
                             (= (get expected message-field)
                                (get conflict "message"))
                             (= expected-pair (conflict-pair conflict))))
                      conflicts))))))))))

;; INTENT: PRED-003 — structural conflict claims require complete exact evidence.
(defn aia-04
  "Judge captured same-speaker conflict evidence. The expected overlap names
   personId, speakerMessage, and exactly two placement projections."
  [evidence]
  (judge-conflict :AIA-04 "speaker" "personId" "speakerMessage" evidence))

;; INTENT: PRED-003 — structural conflict claims require complete exact evidence.
(defn aia-05
  "Judge captured same-room conflict evidence. The expected overlap names
   roomId, roomMessage, and exactly two placement projections."
  [evidence]
  (judge-conflict :AIA-05 "room" "roomId" "roomMessage" evidence))
