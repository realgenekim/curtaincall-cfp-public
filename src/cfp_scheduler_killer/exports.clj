(ns cfp-scheduler-killer.exports
  "Open-data exports — the ai.engineer seam.

   swyx doesn't publish from his program tool: ai.engineer/schedule is a custom
   Next.js site fed by open data exports (sessions.json · speakers.json ·
   llms.txt · calendar.ics). That is why requirement 9 (embeddable gallery) was
   struck from the brief — they own publishing, and they should.

   So we emit those files in THEIR shape, byte-compatible, and our tool drops
   into the pipeline they already have with zero migration. We replace
   Sessionboard exactly at its seam and leave the part they do better alone.

   ONE RULE GOVERNS EVERYTHING HERE: an export describes the PUBLISHED PROGRAM.
   A session appears only if it is Accepted AND the speaker has been informed.
   A talk the committee accepted this morning but hasn't told anyone about is
   not part of the program yet — publishing it would tell the speaker by
   accident, through a JSON file, which is the worst possible way to hear.

   And private fields NEVER leave the building. `notes-to-committee` is absent
   from every export and every API response, authenticated or not."
  (:require
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [clojure.data.json :as json]
   [clojure.string :as str]
   [taoensso.timbre :as log])
  (:import
   (java.io ByteArrayInputStream)
   (java.time LocalDate ZoneId)
   (java.time.format DateTimeFormatter)
   (java.util Base64)
   (java.util.zip ZipInputStream)
   (org.jsoup Jsoup)))

(def ^:private calendar-prodid
  "PRODID:-//Curtain Call//curtaincallcfp.com//EN")

;; Compatibility identity, not display branding. Calendar clients already hold
;; these UIDs, so changing the domain would duplicate rather than amend invites.
(def ical-domain "cfp-scheduler-killer.local")

;; --- Deterministic rubric predicates ---------------------------------------
;;
;; These consume sealed response bytes captured by the evidence runner. They
;; deliberately do not know how to fetch a route or read application state:
;; capture is an effect, judgement is a pure replay over those captured bytes.

(def ^:private byte-array-class (class (byte-array 0)))

(defn- byte-array? [value]
  (instance? byte-array-class value))

(defn- base64 [bytes]
  (when (byte-array? bytes)
    (.encodeToString (Base64/getEncoder) ^bytes bytes)))

(defn- missing-captures [evidence required]
  (->> required
       (filter #(nil? (get evidence %)))
       vec))

(defn- evidence-result [verdict examined observed & [missing]]
  (cond-> {:verdict verdict :examined examined}
    observed (assoc :observed observed)
    (seq missing) (assoc :missing missing)))

(defn- eocd-signature?
  "A ZIP must end in an end-of-central-directory record. ZipInputStream reads
   local entries without requiring that record, so checking it separately
   catches a response truncated after otherwise readable members."
  [bytes]
  (and (byte-array? bytes)
       (<= 4 (alength ^bytes bytes))
       (boolean
         (some (fn [i]
                 (and (= 0x50 (bit-and 0xff (aget ^bytes bytes i)))
                      (= 0x4b (bit-and 0xff (aget ^bytes bytes (inc i))))
                      (= 0x05 (bit-and 0xff (aget ^bytes bytes (+ i 2))))
                      (= 0x06 (bit-and 0xff (aget ^bytes bytes (+ i 3))))))
               (range 0 (- (alength ^bytes bytes) 3))))))

(defn- zip-members
  "Read every entry to EOF so ZipInputStream verifies compressed data and CRCs."
  [bytes]
  (if-not (and (byte-array? bytes) (eocd-signature? bytes))
    {:valid? false :members []}
    (try
      (with-open [zip (ZipInputStream. (ByteArrayInputStream. ^bytes bytes))]
        (loop [members []]
          (if-let [entry (.getNextEntry zip)]
            (let [buffer (byte-array 8192)]
              (loop []
                (when-not (neg? (.read zip buffer))
                  (recur)))
              (.closeEntry zip)
              (recur (conj members (.getName entry))))
            {:valid? true :members (vec (sort members))})))
      (catch Exception _
        {:valid? false :members []}))))

;; INTENT: PRED-EXPORT-001
(defn latest-files-zip-verdict
  "Decide CNT-14 from captured ZIP bytes and exact expected archive paths.

   Returns {:verdict true|false|:cannot-judge, :examined ..., ...}. The base64
   capture in :examined is an immutable, lossless record of the exact ZIP bytes
   used for the verdict."
  [evidence]
  (let [missing (missing-captures evidence [:zip-bytes :expected-members])
        bytes (:zip-bytes evidence)
        expected (:expected-members evidence)
        examined (cond-> {:expected-members expected}
                   (byte-array? bytes)
                   (assoc :zip-bytes-base64 (base64 bytes)))]
    (if (seq missing)
      (evidence-result :cannot-judge examined nil missing)
      (let [{:keys [valid? members]} (zip-members bytes)
            expected* (when (sequential? expected)
                        (vec (sort (map str expected))))
            exact? (and valid?
                        expected*
                        (= (count expected*) (count members))
                        (= expected* members))]
        (evidence-result (boolean exact?) examined
                         {:valid-zip? valid?
                          :member-count (count members)
                          :members members})))))

;; INTENT: PRED-EXPORT-001
(defn sessions-card-count-verdict
  "Decide EMB-01 by comparing captured DOM cards with captured sessions.json.

   Parsing happens only over the supplied UTF-8 bytes. No route, store, model,
   or clock is consulted during judgement."
  [evidence]
  (let [required [:sessions-html-bytes :sessions-json-bytes]
        missing (missing-captures evidence required)
        html-bytes (:sessions-html-bytes evidence)
        json-bytes (:sessions-json-bytes evidence)
        examined (cond-> {}
                   (byte-array? html-bytes)
                   (assoc :sessions-html-bytes-base64 (base64 html-bytes))
                   (byte-array? json-bytes)
                   (assoc :sessions-json-bytes-base64 (base64 json-bytes)))]
    (if (seq missing)
      (evidence-result :cannot-judge examined nil missing)
      (try
        (let [valid-types? (and (byte-array? html-bytes)
                                (byte-array? json-bytes))
              document (when valid-types?
                         (Jsoup/parse (String. ^bytes html-bytes "UTF-8")))
              payload (when valid-types?
                        (json/read-str (String. ^bytes json-bytes "UTF-8")))
              sessions (get payload "sessions")
              rendered-count (when document
                               (.size (.select document ".public-session-card")))
              json-count (when (sequential? sessions) (count sessions))
              exact? (and valid-types?
                          (sequential? sessions)
                          (= rendered-count json-count))]
          (evidence-result (boolean exact?) examined
                           {:rendered-card-count rendered-count
                            :sessions-json-count json-count}))
        (catch Exception _
          (evidence-result false examined
                           {:rendered-card-count nil
                            :sessions-json-count nil}))))))

;; --- The publication gate ---------------------------------------------------

(defn- communicated?
  [submission]
  (and (= "Accepted" (:status submission))
       (some? (:notified-at submission))))

(defn- content-workflow-active?
  "True once an organizer has persisted any editorial status for the event.
   This makes adopting the content workflow an event-level migration: legacy
   events keep publishing their status-less rows, while an event using the
  workflow must explicitly approve every row it publishes."
  [event-id]
  (boolean
    (and event-id
         (some :content-status (store/submissions-for-event event-id)))))

(defn- content-publishable?
  "Approved content publishes. A status-less legacy row publishes only until
   its event adopts the explicit editorial workflow."
  ([submission]
   (content-publishable? submission
                         (content-workflow-active? (:event-id submission))))
  ([submission workflow-active?]
   (or (= "Approved" (submissions/content-status submission))
       (and (not workflow-active?)
            (nil? (:content-status submission))))))

(defn published?
  "Accepted, communicated, and editorially publishable."
  [submission]
  (and (communicated? submission)
       (content-publishable? submission)))

(defn published-sessions
  "Accepted and communicated organizer workset. The historical name is kept
   for callers; public consumers must use publishable-sessions."
  [event-id]
  (->> (store/submissions-for-event event-id)
       (filter communicated?)
       (sort-by :created-at)
       vec))

(defn publishable-sessions
  "The PUBLIC gate: the accepted+communicated organizer workset minus explicit
   unapproved content and any session in a KNOWN scheduling conflict (Gene's
   ruling, 2026-08-09). A partial state — room TBD, time undecided — publishes
   fine (doctrine #6); a conflict is not undecided, it is known wrong, and the
   agenda is the artifact attendees plan their day around, so conflicted
   sessions are withheld until resolved. Never silent: the organizer's schedule
   page counts what is held. The organizer-side pools (schedule/schedulable,
   the authed API) deliberately still see everything — you cannot fix a conflict
   or unapproved content you cannot see.
   requiring-resolve because schedule requires exports: the conflict set is
   resolved at runtime to avoid a load cycle."
  [event]
  (let [conflicted (into #{} ((requiring-resolve
                                'cfp-scheduler-killer.schedule/conflicted-submission-ids)
                              event))
        sessions (published-sessions (:id event))
        workflow-active? (content-workflow-active? (:id event))
        approved (filterv #(content-publishable? % workflow-active?) sessions)
        public (filterv #(not (contains? conflicted (:id %))) approved)]
    (when (< (count approved) (count sessions))
      (log/debug :withholding-unapproved-content
                 :event-id (:id event) :held (- (count sessions) (count approved))))
    (when (< (count public) (count approved))
      (log/debug :withholding-conflicted-sessions
                 :event-id (:id event) :held (- (count approved) (count public))))
    public))

(defn public-answers
  "Answers with every `:private true` field removed, per the submission's own
   snapshot. Driven by the field defs so a newly-added private field is excluded
   the day it is added, with no export code to remember to change."
  [submission]
  (let [private-ids (into #{}
                          (comp (filter :private)
                                (map #(keyword (name (:id %)))))
                          (:form-snapshot submission))]
    (apply dissoc (:answers submission) private-ids)))

(defn track-of
  "One track derivation feeding every public surface (bd-1ini). Explicit
   :track wins; :industry is the fallback for forms without a track field."
  [answers]
  (str (or (:track answers) (:industry answers) "")))

;; --- Schedule version -------------------------------------------------------

(defn schedule-version
  "Monotonic version for this event: the number of stored events that touch it.

   Any change — a rating, an edit, an acceptance — bumps it, so a consumer can
   cache on this number and know a bump means 'something moved'. It is derived,
   never stored, so it cannot drift from reality (ai.engineer's own is 4945,
   i.e. republished constantly)."
  [event-id]
  (count (store/log-for-event event-id)))

;; --- Formatting helpers -----------------------------------------------------

(def ^:private date-fmt (DateTimeFormatter/ofPattern "MMM d, yyyy"))
(def ^:private day-month-fmt (DateTimeFormatter/ofPattern "MMM d"))

(defn date-range-string
  [event]
  (let [s (:starts-on event) e (:ends-on event)]
    (cond
      (and s e (= s e)) (.format ^LocalDate s date-fmt)
      (and s e (= (.getYear ^LocalDate s) (.getYear ^LocalDate e)))
      (str (.format ^LocalDate s day-month-fmt) "–"
           (.format ^LocalDate e date-fmt))
      (and s e) (str (.format ^LocalDate s date-fmt) " – " (.format ^LocalDate e date-fmt))
      s (.format ^LocalDate s date-fmt)
      :else "")))

(defn- session-type
  "ai.engineer's vocabulary is session|workshop|keynote|sponsor. Our seed form
   asks for a FORMAT (Experience Report / SME talk / Panel), which is a
   different question, so everything maps to \"session\" until an event's form
   actually distinguishes workshops and keynotes. Guessing would put wrong
   values into someone else's published site."
  [answers]
  (let [fmt (str/lower-case (str (:session-format answers)))]
    (cond
      (str/includes? fmt "workshop") "workshop"
      (str/includes? fmt "keynote") "keynote"
      :else "session")))

(def ^:private profile->speaker-key
  "The maintained person-profile fields that supersede their submitted speaker
   block on current/public read surfaces. Role, position, Sessionize evidence,
   and the submission's form/answers remain historical facts."
  {:tagline :title
   :org :org
   :bio :bio
   :headshot-url :headshot-url
   :linkedin-url :linkedin-url
   :website-url :website-url})

(defn- current-speaker-identity
  "Overlay canonical person identity onto one immutable submitted speaker block.
   Presence matters: when a speaker deliberately clears a maintained profile
   field, public projections stay blank instead of resurrecting stale submitted
   text. Legacy profiles without that key still fall back to their snapshot."
  [speaker]
  (let [person (store/person-by-id (:person-id speaker))
        profile (:profile person)
        canonical (cond-> speaker
                    (seq (:name person)) (assoc :name (:name person))
                    (seq (:email person)) (assoc :email (:email person)))]
    (reduce-kv (fn [projected profile-key speaker-key]
                 (if (contains? profile profile-key)
                   (assoc projected speaker-key (get profile profile-key))
                   projected))
               canonical
               profile->speaker-key)))

(defn- speaker-names [submission]
  (mapv #(str (:name (current-speaker-identity %))) (:speakers submission)))

;; --- Stable identity --------------------------------------------------------
;;
;; The whole reason this API exists. Ask three ITRev systems how many talks Jason
;; Cox has given and you get 6, 9 and 12 (docs/research/post-conference-corpus-
;; survey.md) — all live, none wrong, because every join in the estate is a fuzzy
;; match on a display-name string. So: an id on EVERY entity in EVERY payload,
;; and the id on BOTH sides of every join (a session carries speakerIds, a
;; speaker carries sessionIds). A consumer should never have to match "Jason Cox"
;; against "Jason Cox " to build a graph.

(defn- stable-speaker-id
  [submission speaker]
  (str (or (:person-id speaker)
           (java.util.UUID/nameUUIDFromBytes
             (.getBytes
               (str (:event-id submission) "\u0000"
                    (or (some-> (:email speaker) str str/trim str/lower-case not-empty)
                        (some-> (:name speaker) str str/trim str/lower-case not-empty)
                        (:id submission)))
               java.nio.charset.StandardCharsets/UTF_8)))))

(defn speaker-ids
  "Stable ids behind a submission's speakers. Canonical person ids win; legacy
   snapshots without one receive a deterministic event-local identity."
  [submission]
  (mapv #(stable-speaker-id submission %) (:speakers submission)))

(defn room-name
  [event-id room-id]
  (when room-id
    (:name (first (filter #(= room-id (:id %)) (store/rooms-for-event event-id))))))

(defn event-day-strings
  "Every day of the event as an ISO string. Duplicated from
   cfp-scheduler-killer.schedule for the same reason `minutes->display` is —
   schedule depends on THIS namespace for the publication gate."
  [event]
  (let [s (:starts-on event) e (or (:ends-on event) (:starts-on event))]
    (if (and s e (not (.isAfter ^LocalDate s ^LocalDate e)))
      (loop [d s acc []]
        (if (.isAfter ^LocalDate d ^LocalDate e)
          acc
          (recur (.plusDays ^LocalDate d 1) (conj acc (str d)))))
      [])))

;; --- sessions.json ----------------------------------------------------------

(defn- minutes->display
  "9:00am. Duplicated from cfp-scheduler-killer.schedule rather than required,
   because schedule depends on THIS namespace for the publication gate and a
   cycle would be a worse trade than eight lines of formatting."
  [m]
  (when m
    (let [h (int (/ m 60)) mm (int (mod m 60))
          ampm (if (< h 12) "am" "pm")
          h12 (cond (zero? h) 12 (> h 12) (- h 12) :else h)]
      (str h12 ":" (format "%02d" mm) ampm))))

(defn- day-index-label
  "\"Day 1 — Oct 14\", the shape ai.engineer publishes."
  [event day-str]
  (when-let [d (try (LocalDate/parse (str day-str)) (catch Exception _ nil))]
    (let [start (:starts-on event)
          idx (when start (inc (.between java.time.temporal.ChronoUnit/DAYS start d)))]
      (str (when (and idx (pos? idx)) (str "Day " idx " — "))
           (.format d (DateTimeFormatter/ofPattern "MMM d"))))))

(defn placement-fields
  "day/time/room for a session — EMPTY until it is actually placed. An empty
   string is honest; an invented time would propagate into someone else's
   published site."
  [event submission]
  (let [slot (store/slot-for (:id submission))
        room (room-name (:id event) (:room-id slot))]
    (if-not slot
      {"day" "" "time" "" "room" ""}
      {"day" (or (day-index-label event (:day slot)) "")
       "time" (str (minutes->display (:start slot))
                   (when (:end slot) (str "-" (minutes->display (:end slot)))))
       "room" (or room "")})))

(defn session-entry
  "One session in ai.engineer's shape — plus the two keys ai.engineer's own feed
   does not have: `id` and `speakerIds`.

   ai.engineer joins sessions to speakers on the display-name string, which is
   why we emit the names too and in the same place; extra keys are additive, so
   a consumer built against their feed keeps working byte-for-byte. But a
   consumer built against OURS never has to fuzzy-match a human being."
  [event submission]
  (let [answers (public-answers submission)]
    (merge
      (placement-fields event submission)
      {"id" (:id submission)
       "speakerIds" (speaker-ids submission)
       "title" (str (:talk-title answers))
       "description" (str (:abstract answers))
       "type" (session-type answers)
       "track" (track-of answers)
       "status" "confirmed"
       "speakers" (speaker-names submission)})))

(defn sessions-json-data
  [event]
  (let [sessions (mapv #(session-entry event %) (publishable-sessions event))]
    {"conference" (str (:name event))
     "dates" (date-range-string event)
     "location" (str (or (:location event) ""))
     "website" (str (or (:website-url event) ""))
     "scheduleVersion" (schedule-version (:id event))
     "totalSessions" (count sessions)
     "sessions" sessions}))

;; --- speakers.json ----------------------------------------------------------

(defn speaker-entry
  "The speaker as PUBLISHED: what they submitted, overlaid with anything they
   have since maintained in their profile. The profile wins because it is the
   version they keep current.

   `id` is the stable speaker id and `sessionIds` closes the join from this
   side. `speaker` is explicit because every presenter needs their own row."
  ([submission]
   (speaker-entry submission (first (:speakers submission))))
  ([submission speaker]
   (let [speaker (current-speaker-identity speaker)]
     {"id" (stable-speaker-id submission speaker)
      "sessionIds" [(:id submission)]
      "published" (published? submission)
      "name" (str (:name speaker))
      "tagline" (str (or (:title speaker) ""))
      "org" (str (or (:org speaker) ""))
      "bio" (str (or (:bio speaker) ""))
      "headshot" (str (or (:headshot-url speaker) ""))
      "links" (vec (remove nil?
                           [(when-let [u (some-> (:linkedin-url speaker)
                                                 str str/trim not-empty)]
                              {"label" "LinkedIn" "url" u})
                            (when-let [u (some-> (:website-url speaker)
                                                 str str/trim not-empty)]
                              {"label" "Website" "url" u})
                            (when-let [u (some-> (:sessionize-url speaker)
                                                 str str/trim not-empty)]
                              {"label" "Sessionize" "url" u})]))
      "sessions" [(str (:talk-title (public-answers submission)))]})))

(defn aggregate-speakers
  "One entry per PERSON, even when they have two talks — merged on the person id
   rather than on the name, which is the whole point. Every presenter receives
   a row, and insertion order keeps the payload stable between calls."
  [sessions]
  (let [session-speakers (for [submission sessions
                               speaker (:speakers submission)]
                           [submission speaker])
        order (vec (distinct (map (fn [[submission speaker]]
                                    (stable-speaker-id submission speaker))
                                  session-speakers)))
        by-person (reduce (fn [acc [submission speaker]]
                            (let [speaker-id (stable-speaker-id submission speaker)]
                              (if-let [existing (get acc speaker-id)]
                                (assoc acc speaker-id
                                       (-> existing
                                           (update "published" #(or % (published? submission)))
                                           (update "sessions" conj
                                                   (str (:talk-title
                                                          (public-answers submission))))
                                           (update "sessionIds" conj (:id submission))))
                                (assoc acc speaker-id
                                       (speaker-entry submission speaker)))))
                          {}
                          session-speakers)]
    ;; A map with more than eight keys is unordered; the order vector is how the
    ;; payload stays byte-stable between two calls with the same data.
    (mapv by-person order)))

(defn announced-speaker-id
  "Stable public identity for an invited speaker. New facts carry a real id;
   legacy facts receive a deterministic event-local UUID so existing append
   logs become addressable without a migration or name matching by consumers."
  [event speaker]
  (str (or (:person-id speaker)
           (:id speaker)
           (java.util.UUID/nameUUIDFromBytes
             (.getBytes (str (:id event) "\u0000"
                             (-> (:name speaker) str str/trim str/lower-case))
                        java.nio.charset.StandardCharsets/UTF_8)))))

(defn- speaker-publication-overrides
  "Latest explicit API publication fact by canonical event-speaker id. Reading
   the append-only log keeps this API projection replay-safe without owning the
   organizer lifecycle fold."
  [event]
  (reduce (fn [by-id {:keys [type payload]}]
            (if (#{"speaker.published" "speaker.unpublished"} type)
              (assoc by-id (str (:person-id payload))
                     (= "speaker.published" type))
              by-id))
          {}
          (store/log-for-event (:id event))))

(defn announced-roster-entries
  "The ANNOUNCED roster (settings :announced-speakers — the real invited
   lineup) shaped like speaker-entry: no person-id, no sessions, \"announced\"
   true. `taken` is the lower-cased-name set already covered by CFP records,
   which win because they are richer. Shared by speakers.json and the v1 API
   so every public speaker surface tells the same story."
  ([event taken] (announced-roster-entries event taken false))
  ([event taken include-unpublished?]
   (let [overrides (speaker-publication-overrides event)]
     (->> (events/announced-speakers event)
          (remove #(taken (-> (:name %) str str/trim str/lower-case)))
          (keep (fn [{:keys [name org title headshot-url] :as speaker}]
                  (let [id (announced-speaker-id event speaker)
                        published (get overrides id (not= false (:published? speaker)))]
                    (when (or include-unpublished? published)
                      {"id" id "sessionIds" [] "published" published
                       "name" (str name) "tagline" (str (or title ""))
                       "org" (str (or org "")) "bio" ""
                       "headshot" (str (or headshot-url ""))
                       "links" [] "sessions" [] "announced" true}))))))))

(defn speakers-json-data
  [event]
  (let [cfp (aggregate-speakers (publishable-sessions event))
        taken (into #{} (map #(-> (get % "name") str str/trim str/lower-case)) cfp)
        speakers (into (vec cfp) (announced-roster-entries event taken))]
    {"conference" (str (:name event))
     "totalSpeakers" (count speakers)
     "speakers" speakers}))

;; --- calendar.ics -----------------------------------------------------------

(defn ics-escape
  "RFC 5545 §3.3.11: backslash, semicolon, comma and newline are special."
  [s]
  (-> (str s)
      (str/replace "\\" "\\\\")
      (str/replace ";" "\\;")
      (str/replace "," "\\,")
      ;; Normalize every line-break spelling. A bare CR is invalid RFC 5545
      ;; input, but accepting it unchanged lets tolerant calendar clients treat
      ;; attacker-controlled text after it as a new content line/property.
      (str/replace #"\r\n|\r|\n" "\\\\n")))

(defn fold-line
  "RFC 5545 §3.1: lines wrap at 75 octets, continuations start with a space."
  [line]
  (if (<= (count line) 75)
    line
    (str/join "\r\n "
              (loop [s line acc []]
                (if (<= (count s) 74)
                  (conj acc s)
                  (recur (subs s 74) (conj acc (subs s 0 74))))))))

(defn- ics-date [^LocalDate d]
  (when d (.format d (DateTimeFormatter/ofPattern "yyyyMMdd"))))

(defn- ics-stamp []
  (.format (.atZone (java.time.Instant/now) (ZoneId/of "UTC"))
           (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss'Z'")))

(defn ics-uid
  "STABLE for the life of the submission. An amendment must update the existing
   calendar entry, never create a second one — the duplicate-invite problem is
   the single most-hated behaviour of every conference tool we have used."
  [submission]
  (str (:id submission) "@" ical-domain))

(defn ics-sequence
  "RFC 5545 SEQUENCE: bumps every time this session changes, so a client knows
   the invite is an amendment. Derived from the number of stored events that
   touched this submission."
  [submission]
  (count (filter (fn [e]
                   (= (:id submission) (get-in e [:payload :submission-id])))
                 (store/log-for-event (:event-id submission)))))

(defn- ics-local-datetime
  "yyyyMMddTHHmmss — a FLOATING local time. The event's zone is published in the
   calendar header; floating times mean an attendee's phone shows the program in
   the room's clock, which is what a printed agenda means."
  [^LocalDate d minutes]
  (when (and d minutes)
    (str (.format d (DateTimeFormatter/ofPattern "yyyyMMdd"))
         "T" (format "%02d%02d00" (int (/ minutes 60)) (int (mod minutes 60))))))

(defn vevent
  [event submission]
  (let [answers (public-answers submission)
        slot (store/slot-for (:id submission))
        slot-day (when slot (try (LocalDate/parse (str (:day slot))) (catch Exception _ nil)))
        room (when-let [room-id (:room-id slot)]
               (room-name (:id event) room-id))
        start (:starts-on event)
        end (:ends-on event)
        ;; DTEND is EXCLUSIVE for all-day events, so the last day needs +1.
        end+1 (some-> (or end start) (.plusDays 1))
        ;; A PLACED session gets its real time; an unplaced one falls back to
        ;; all-day across the event, so the invite still exists and still has a
        ;; stable UID to amend later.
        placed? (and slot-day (:start slot))]
    (->> [(str "BEGIN:VEVENT")
          (str "UID:" (ics-uid submission))
          (str "DTSTAMP:" (ics-stamp))
          (str "SEQUENCE:" (ics-sequence submission))
          (if placed?
            (str "DTSTART:" (ics-local-datetime slot-day (:start slot)))
            (when start (str "DTSTART;VALUE=DATE:" (ics-date start))))
          (if placed?
            (str "DTEND:" (ics-local-datetime slot-day (or (:end slot)
                                                           (+ 30 (:start slot)))))
            (when end+1 (str "DTEND;VALUE=DATE:" (ics-date end+1))))
          (str "SUMMARY:" (ics-escape (:talk-title answers)))
          (str "DESCRIPTION:" (ics-escape
                                (str (:abstract answers)
                                     "\n\nSpeaker: " (str/join ", " (speaker-names submission)))))
          ;; Room when we know it, event location otherwise — the late-room
          ;; assignment lands in the invite as an amendment, not a new event.
          (when-let [loc (or (when room (str room (when (:location event)
                                                    (str ", " (:location event)))))
                             (:location event))]
            (str "LOCATION:" (ics-escape loc)))
          (str "STATUS:CONFIRMED")
          (str "END:VEVENT")]
         (remove nil?)
         (map fold-line))))

(defn hold-the-date-uid
  "STABLE FOREVER, and independent of every session. The banner must amend
   itself when the dates move, never appear twice — so this UID is derived from
   the event id alone and is never regenerated."
  [event]
  (str (:id event) "-hold-the-date@cfp-scheduler-killer.local"))

(defn hold-the-date-sequence
  "RFC 5545 SEQUENCE for the banner. Only the facts that can change what the
   banner SAYS count — name, dates, location — so adding a session does not
   look like an amendment of the conference itself."
  [event]
  (count (filter (fn [e] (#{"event.created" "event.updated"} (:type e)))
                 (store/log-for-event (:id event)))))

(defn hold-the-date-vevent
  "The banner: ONE all-day VEVENT for the conference itself, always present.

   It is FIRST in the feed and it does not depend on a single session being
   published, so an attendee can subscribe the day the CFP opens — and then
   watch the sessions stream into the same subscription as the program fills
   in. Without it, an early subscriber adds an empty calendar and never looks
   again."
  [event]
  (let [start (:starts-on event)
        end   (:ends-on event)
        ;; DTEND is EXCLUSIVE for all-day events, so the last day needs +1.
        end+1 (some-> (or end start) (.plusDays 1))
        link  (or (not-empty (str/trim (str (:website-url event))))
                  (str "/program/" (:slug event)))]
    (->> [(str "BEGIN:VEVENT")
          (str "UID:" (hold-the-date-uid event))
          (str "DTSTAMP:" (ics-stamp))
          (str "SEQUENCE:" (hold-the-date-sequence event))
          (when start (str "DTSTART;VALUE=DATE:" (ics-date start)))
          (when end+1 (str "DTEND;VALUE=DATE:" (ics-date end+1)))
          (str "SUMMARY:" (ics-escape (or (events/display-name event)
                                          (:name event))))
          (str "DESCRIPTION:" (ics-escape
                                (str "Hold the date"
                                     (when-let [d (not-empty (date-range-string event))]
                                       (str " — " d))
                                     ".\n\nProgram and schedule: " link)))
          (when-let [loc (:location event)]
            (str "LOCATION:" (ics-escape loc)))
          (str "URL:" (ics-escape link))
          (str "STATUS:CONFIRMED")
          (str "END:VEVENT")]
         (remove nil?)
         (map fold-line))))

(defn submission-ics
  "A single-VEVENT calendar for ONE session — what gets attached to an
   acceptance letter. Deliberately built from the SAME `vevent` as the public
   feed, so the UID and SEQUENCE an attendee receives by email are byte-identical
   to the ones in the subscribed feed. That is what makes a later room
   assignment amend the invite instead of creating a second one."
  [event submission]
  (let [lines (concat ["BEGIN:VCALENDAR"
                       "VERSION:2.0"
                       calendar-prodid
                       "CALSCALE:GREGORIAN"
                       "METHOD:PUBLISH"]
                      (vevent event submission)
                      ["END:VCALENDAR"])]
    (str (str/join "\r\n" lines) "\r\n")))

(defn calendar-ics-for
  "Build a calendar from an explicit, already-authorized set of submissions."
  [event sessions]
  (let [lines (concat ["BEGIN:VCALENDAR"
                       "VERSION:2.0"
                       calendar-prodid
                       "CALSCALE:GREGORIAN"
                       "METHOD:PUBLISH"
                       (str "X-WR-CALNAME:" (ics-escape (:name event)))]
                      (mapcat #(vevent event %) sessions)
                      ["END:VCALENDAR"])]
    (str (str/join "\r\n" lines) "\r\n")))

(defn calendar-ics
  [event]
  (let [lines (concat ["BEGIN:VCALENDAR"
                       "VERSION:2.0"
                       calendar-prodid
                       "CALSCALE:GREGORIAN"
                       "METHOD:PUBLISH"
                       (str "X-WR-CALNAME:" (ics-escape (:name event)))]
                      ;; The hold-the-date banner leads, always — the feed is
                      ;; never empty, even before the first session publishes.
                      (hold-the-date-vevent event)
                      (mapcat #(vevent event %) (publishable-sessions event))
                      ["END:VCALENDAR"])]
    ;; CRLF throughout, and a trailing CRLF — some clients are strict.
    (str (str/join "\r\n" lines) "\r\n")))

;; --- llms.txt ---------------------------------------------------------------

(defn llms-txt
  "A markdown index for machines that read the web. Points at the structured
   exports rather than trying to be them."
  [event base-url]
  (let [sessions (publishable-sessions event)
        e (fn [& parts] (apply str parts))]
    (str/join
      "\n"
      (remove nil?
              [(e "# " (:name event))
               ""
               (when-let [d (not-empty (date-range-string event))] (e d))
               (when (:location event) (e (:location event)))
               (when (:website-url event) (e (:website-url event)))
               ""
               (e "> Program data for " (:name event)
                  ". " (count sessions) " confirmed session"
                  (when (not= 1 (count sessions)) "s") ".")
               ""
               "## Data"
               ""
               (e "- [sessions.json](" base-url "/events/" (:slug event) "/exports/sessions.json)"
                  ": every confirmed session — title, description, type, track, speakers.")
               (e "- [speakers.json](" base-url "/events/" (:slug event) "/exports/speakers.json)"
                  ": confirmed speakers — bio, tagline, org, headshot, links.")
               (e "- [calendar.ics](" base-url "/events/" (:slug event) "/exports/calendar.ics)"
                  ": the program as a calendar feed with stable UIDs.")
               ""
               "## API"
               ""
               (e "- [API reference](" base-url "/api/v1/events/" (:slug event) "/docs)"
                  ": every endpoint, with a curl line for each.")
               (e "- [event](" base-url "/api/v1/events/" (:slug event) ")"
                  ": ids, dates, timezone, CFP state, and links to everything below.")
               (e "- [sessions](" base-url "/api/v1/events/" (:slug event) "/sessions)"
                  ": the program with `id` and `speakerIds` on every row.")
               (e "- [speakers](" base-url "/api/v1/events/" (:slug event) "/speakers)"
                  ": one row per PERSON, with `id` and `sessionIds`.")
               (e "- [schedule](" base-url "/api/v1/events/" (:slug event) "/schedule)"
                  ": days, rooms and placements, joined by id.")
               (e "- [review policy](" base-url "/api/v1/events/" (:slug event) "/review-policy)"
                  ": the active blind-review policy and its audience-specific explanation.")
               (e "- [MCP endpoint](" base-url "/events/" (:slug event) "/mcp)"
                  ": the same event capabilities over Model Context Protocol.")
               ""
               "## Social preview cards"
               ""
               (e "- [Public program](" base-url "/program/" (:slug event) ")"
                  ": Open Graph and Twitter card metadata for the event program.")
               (e "- [Speaker profiles](" base-url "/agenda/" (:slug event) "/speakers)"
                  ": each public speaker profile carries its own Open Graph/Twitter card and generated preview image.")
               ""
               "## Call for speakers"
               ""
               (e "- [Submit a talk](" base-url "/cfp/" (:slug event) ")")
               ""
               "## Notes"
               ""
               "- Only sessions that are accepted AND whose speakers have been"
               "  informed appear here. Nothing is published before the speaker knows."
               "- Explicit editorial states publish only Approved content; In review"
               "  content stays private while organizers can continue working on it."
               "- Day, time and room are empty until the schedule is built; they will"
               "  populate in place, and `scheduleVersion` will bump."
               "- Every entity carries a stable `id`, and every join carries the id on"
               "  BOTH sides (`speakerIds` on a session, `sessionIds` on a speaker)."
               "  Never match on a name or a title — the ids do not change."
               (e "- scheduleVersion: " (schedule-version (:id event)))
               ""]))))

(defn site-events
  "The conferences an agent may be told about from the ROOT index: everything
   not archived and not unlisted. Exactly the visibility rule the public /cfps
   index uses — an unlisted event is indistinguishable from no event at all,
   and the site index must not be the one place it leaks."
  []
  (->> (events/list-events)
       (remove :archived-at)
       (remove events/unlisted?)
       vec))

(defn site-llms-txt
  "GET /llms.txt at the ROOT — the door an agent knocks on when all it has is
   the domain. A door that 404s or redirects home is a defect, not a missing
   feature, so this one always answers.

   An INDEX, not a second copy of the data: it names every public conference
   and hands off to that conference's own llms.txt, which is where the exports,
   the API and the MCP endpoint are already described in full."
  [evts base-url]
  (let [n (count evts)
        e (fn [& parts] (apply str parts))]
    (str/join
      "\n"
      (concat
        ["# Curtain Call"
         ""
         "Calls for papers, without the paperwork."
         ""
         (e "> The agent index for this instance. " n " public conference"
            (when (not= 1 n) "s") ". Each one below links to its own llms.txt,"
            " which lists that program's exports, API and MCP endpoint in full.")
         ""
         "## Conferences"
         ""]
        (if (zero? n)
          ["- None published yet. This list fills in as organizers open their calls."]
          (mapcat
            (fn [ev]
              (let [slug (:slug ev)
                    facts (remove str/blank?
                                  [(date-range-string ev) (str (:location ev))])
                    sessions (count (publishable-sessions ev))
                    cfp (case (submissions/cfp-state ev)
                          :open "open"
                          :not-open-yet "not open yet"
                          "closed")]
                [(e "- [" (:name ev) "](" base-url "/events/" slug "/llms.txt)"
                    ": " (when (seq facts) (str (str/join " · " facts) ". "))
                    sessions " confirmed session" (when (not= 1 sessions) "s")
                    "; call for speakers " cfp ".")
                 (e "  - [program](" base-url "/program/" slug ")"
                    ": the public page a person reads.")
                 (e "  - [event API](" base-url "/api/v1/events/" slug ")"
                    ": ids, dates, timezone, CFP state, and a link to every endpoint.")
                 (e "  - [submit a talk](" base-url "/cfp/" slug ")")]))
            evts))
        [""
         "## Service"
         ""
         (e "- [API index](" base-url "/api/v1)"
            ": every endpoint, its auth, and the conventions that hold across all of them.")
         (e "- [open calls](" base-url "/cfps)"
            ": only the conferences accepting proposals right now.")
         ""
         "## Notes"
         ""
         "- Start at a conference's llms.txt. This page only tells you which"
         "  conferences exist and where each one's index lives."
         "- Only sessions that are accepted AND whose speakers have been"
         "  informed appear anywhere public. Nothing is published before the"
         "  speaker knows."
         "- Every entity carries a stable `id`, and every join carries the id on"
         "  BOTH sides. Never match on a name or a title — the ids do not change."
         ""]))))

;; --- JSON ------------------------------------------------------------------

(defn ->json [data] (json/write-str data :escape-slash false))

(comment
  (store/load!)
  (let [e (store/get-event-by-slug "enterprise-ai-summit-charlotte")]
    (sessions-json-data e)
    (print (calendar-ics e))))

;; --- Webhook registration ---------------------------------------------------
;;
;; Registered webhooks are stored as events like everything else, so "who added
;; this integration, and when" is answerable from the Log.

(defn register-webhook!
  "Add a webhook to an event. `types` is a vector of event-type strings; empty
   means every event."
  [event url types actor]
  (let [url (str/trim (str url))]
    (when (re-matches #"^https?://[^\s/$.?#][^\s]*$" url)
      (let [id (store/new-id)]
        (store/append! {:type "sink.registered" :actor actor :event-id (:id event)
                        :payload {:id id
                                  :event-id (:id event)
                                  :url url
                                  :types (vec (remove str/blank? types))
                                  :created-at (store/now-iso)}})
        id))))

(defn remove-webhook!
  [event sink-id actor]
  (store/append! {:type "sink.removed" :actor actor :event-id (:id event)
                  :payload {:id sink-id :event-id (:id event)
                            :at (store/now-iso)}})
  sink-id)

(defn webhooks-for
  [event]
  (vec (vals (get-in event [:settings :webhooks]))))

;; --- REST API shapes --------------------------------------------------------
;;
;; The API is richer than the static exports (ids, statuses, full answers) but
;; obeys the SAME private-field rule. Authentication buys you unpublished rows;
;; it never buys you the committee's private notes. `notes-to-committee` was
;; promised to the speaker as PC-only, and a token doesn't change who promised.

(defn api-placement
  "Where a session sits on the grid — ids AND display strings, because a
   consumer building a table wants \"9:00am\", and a consumer building a
   calendar wants the integer. Absent placement is `placed: false` with nulls,
   never an invented time (doctrine #6: partial states are first-class)."
  [event submission]
  (let [slot (store/slot-for (:id submission))]
    (if-not slot
      {"placed" false "day" nil "dayLabel" nil
       "startMinute" nil "endMinute" nil "start" nil "end" nil
       "roomId" nil "room" nil}
      {"placed" true
       "day" (str (:day slot))
       "dayLabel" (or (day-index-label event (:day slot)) "")
       "startMinute" (:start slot)
       "endMinute" (:end slot)
       "start" (minutes->display (:start slot))
       "end" (minutes->display (:end slot))
       "roomId" (:room-id slot)
       "room" (or (room-name (:id event) (:room-id slot)) nil)})))

(defn api-session
  "One submission as the API returns it — every entity carrying its id.

   `status` and `notified` are here on purpose: the eight-valued status and the
   separate notified flag ARE the model (doctrine #3), and a scraper that can
   see them never has to infer 'is this real yet' from whether a row appeared.
   Public callers only ever reach published rows, so what they see is always
   Accepted + notified; a token is what shows you the rest of the funnel."
  ([submission] (api-session (store/get-event-by-id (:event-id submission)) submission))
  ([event submission]
   (let [answers (public-answers submission)]
     {"id" (:id submission)
      "eventId" (:event-id submission)
      "title" (str (:talk-title answers))
      "status" (:status submission)
      "published" (published? submission)
      "notified" (some? (:notified-at submission))
      "notifiedAt" (some-> (:notified-at submission) str)
      "source" (:source submission)
      "createdAt" (some-> (:created-at submission) str)
      "type" (session-type answers)
      "track" (track-of answers)
      ;; public-answers, ALWAYS — this is the authenticated path too.
      "answers" (into {} (map (fn [[k v]] [(name k) v])) answers)
      "speakerIds" (speaker-ids submission)
      "schedule" (when event (api-placement event submission))
      "speakers" (mapv (fn [snapshot]
                         (let [speaker (current-speaker-identity snapshot)]
                           {"id" (stable-speaker-id submission speaker)
                            "name" (str (:name speaker))
                            "org" (str (or (:org speaker) ""))
                            "title" (str (or (:title speaker) ""))}))
                       (:speakers submission))})))

(defn api-sessions
  "`status` nil => the program (the default a public caller gets).
   `:all` => every submission, any status (token holders only).
   A status STRING => just that status (token holders only).

   The nil default differs by caller for one reason: an unauthenticated caller
   gets `publishable-sessions` — the same conflict-withholding gate as
   sessions.json — because /api/v1 with no token IS a public surface. An
   authenticated caller gets `published-sessions`, conflicts included, because
   you cannot fix a conflict you cannot see (commit 7508ff9)."
  ([event status] (api-sessions event status false))
  ([event status authed?]
   (let [rows (cond
                (= :all status) (vec (store/submissions-for-event (:id event)))
                (string? status) (filterv #(= status (:status %))
                                          (store/submissions-for-event (:id event)))
                authed? (published-sessions (:id event))
                :else (publishable-sessions event))]
     {"event" {"id" (:id event) "slug" (:slug event) "name" (:name event)}
      "scheduleVersion" (schedule-version (:id event))
      "total" (count rows)
      ;; Everything is returned in one response at our scale; saying so beats a
      ;; consumer writing pagination that will never fire.
      "pagination" {"paginated" false
                    "note" "All results are returned in a single response."}
      "sessions" (mapv #(api-session event %) rows)})))

(defn api-speakers
  "One row per PERSON. `all?` (token holders only) widens from the published
   program to everyone who has submitted."
  ([event] (api-speakers event false))
  ([event all?]
   (let [rows (if all?
                (vec (store/submissions-for-event (:id event)))
                (publishable-sessions event))
         cfp (aggregate-speakers rows)
         taken (into #{} (map #(-> (get % "name") str str/trim str/lower-case)) cfp)
         ;; Same announced-roster merge as speakers.json — one story on every
         ;; public speaker surface.
         speakers (into (vec cfp) (announced-roster-entries event taken all?))]
     {"event" {"id" (:id event) "slug" (:slug event) "name" (:name event)}
      "total" (count speakers)
      "pagination" {"paginated" false
                    "note" "All results are returned in a single response."}
      "speakers" speakers})))

(defn api-speaker
  "One speaker by PERSON id, with every session of theirs the caller may see.
   nil when this person has nothing visible here — the handler turns that into a
  404, so an unpublished speaker is indistinguishable from an unknown one."
  [event person-id all?]
  (let [pid (str person-id)
        rows (filterv (fn [submission]
                        (some #(= pid (stable-speaker-id submission %))
                              (:speakers submission)))
                      (if all?
                        (vec (store/submissions-for-event (:id event)))
                        (publishable-sessions event)))
        cfp-speaker (some #(when (= pid (get % "id")) %)
                          (aggregate-speakers rows))
        announced (first (filter #(= pid (get % "id"))
                                 (announced-roster-entries event #{} all?)))]
    (when (or cfp-speaker announced)
      {"event" {"id" (:id event) "slug" (:slug event) "name" (:name event)}
       "speaker" (or cfp-speaker announced)})))

(defn api-rooms
  [event]
  {"event" {"id" (:id event) "slug" (:slug event) "name" (:name event)}
   "total" (count (store/rooms-for-event (:id event)))
   "rooms" (mapv (fn [r] {"id" (:id r) "name" (str (:name r)) "order" (:order r)})
                 (store/rooms-for-event (:id event)))})

(defn api-schedule
  "The grid, joined by id on every edge: an item names its `sessionId` and its
   `roomId`, and the rooms are listed once at the top. Blocks (breaks, keynote
   holds, lunch) are first-class items, not gaps to be inferred.

   Public callers see the publishable program; a token additionally shows
   placements of sessions that are not published yet — which is the whole
   difference between 'the agenda' and 'the working grid'."
  [event authed?]
  (let [event-id (:id event)
        subs (into {} (map (juxt :id identity)) (store/submissions-for-event event-id))
        visible (into #{} (map :id) (if authed?
                                      (published-sessions event-id)
                                      (publishable-sessions event)))
        slots (filterv #(contains? visible (:submission-id %))
                       (store/slots-for-event event-id))
        placed-ids (into #{} (map :submission-id) slots)
        blocks (store/blocks-for-event event-id)
        item (fn [slot]
               (let [sub (get subs (:submission-id slot))]
                 {"kind" "session"
                  "sessionId" (:submission-id slot)
                  "title" (str (:talk-title (public-answers sub)))
                  "day" (str (:day slot))
                  "startMinute" (:start slot)
                  "endMinute" (:end slot)
                  "start" (minutes->display (:start slot))
                  "end" (minutes->display (:end slot))
                  "roomId" (:room-id slot)
                  "room" (room-name event-id (:room-id slot))
                  "speakerIds" (speaker-ids sub)
                  "speakers" (speaker-names sub)}))
        block-item (fn [b]
                     {"kind" "block"
                      "blockId" (:id b)
                      "title" (str (:label b))
                      "day" (str (:day b))
                      "startMinute" (:start b)
                      "endMinute" (:end b)
                      "start" (minutes->display (:start b))
                      "end" (minutes->display (:end b))
                      "roomId" (:room-id b)
                      "room" (room-name event-id (:room-id b))})
        days (or (seq (event-day-strings event))
                 (distinct (map #(str (:day %)) slots)))]
    {"event" {"id" (:id event) "slug" (:slug event) "name" (:name event)}
     "scheduleVersion" (schedule-version event-id)
     "timezone" (:tz event)
     "locked" (boolean (get-in event [:settings :schedule-lock :locked?]))
     "rooms" (get (api-rooms event) "rooms")
     "days" (mapv (fn [d]
                    {"date" d
                     "label" (or (day-index-label event d) d)
                     "items" (->> (concat (map item (filter #(= d (str (:day %))) slots))
                                          (map block-item (filter #(= d (str (:day %))) blocks)))
                                  (sort-by (juxt #(or (get % "startMinute") 0)
                                                 #(str (get % "title"))))
                                  vec)})
                  days)
     ;; Named, not omitted: "which accepted talks still have no home" is a
     ;; question the grid should answer out loud.
     "unscheduled" (mapv (fn [id] {"sessionId" id
                                   "title" (str (:talk-title (public-answers (get subs id))))})
                         (remove placed-ids visible))}))

(defn api-changes
  "An incremental change feed — the answer to 'do I have to re-download the
   whole program to find out what moved'. `seq` is monotonic and matches
   `scheduleVersion`, so `?since=<last seq you saw>` is a complete sync
   protocol in one query parameter.

   IDS ONLY. No payload bodies, no actor emails, no answers — a change feed says
   WHAT changed and WHEN, and the caller re-reads the entity it names. That also
   means this endpoint can never become a second, unreviewed way for a private
   field to leave the building."
  [event since]
  (let [log (store/log-for-event (:id event))
        since (max 0 (long (or since 0)))
        rows (->> log
                  (map-indexed (fn [i e] [(inc i) e]))
                  (drop since))]
    {"event" {"id" (:id event) "slug" (:slug event) "name" (:name event)}
     "scheduleVersion" (count log)
     "since" since
     "total" (count rows)
     "changes" (mapv (fn [[n e]]
                       (let [p (:payload e)]
                         {"seq" n
                          "type" (:type e)
                          "at" (str (:at e))
                          "id" (:id p)
                          "submissionId" (:submission-id p)
                          "personId" (:person-id p)
                          "roomId" (:room-id p)}))
                     rows)}))

(defn api-event
  "The discovery document: ids, dates, timezone, CFP state, counts, and a link
   to every other endpoint. One GET and a consumer knows the whole surface —
   which is precisely what fifteen years of scraping Sessionize did not have."
  [event base authed?]
  (let [slug (:slug event)
        api (str base "/api/v1/events/" slug)
        ex (fn [f] (str base "/events/" slug "/exports/" f))]
    {"id" (:id event)
     "slug" slug
     "name" (str (:name event))
     "startsOn" (some-> (:starts-on event) str)
     "endsOn" (some-> (:ends-on event) str)
     "dates" (date-range-string event)
     "timezone" (:tz event)
     "location" (str (or (:location event) ""))
     "website" (str (or (:website-url event) ""))
     "scheduleVersion" (schedule-version (:id event))
     "cfp" {"state" (name (submissions/cfp-state event))
            "url" (str base "/cfp/" slug)
            "opensAt" (some-> (:cfp-opens-at event) str)
            "closesAt" (some-> (:cfp-closes-at event) str)}
     "counts" (cond-> {"publishedSessions" (count (publishable-sessions event))
                       "publishedSpeakers" (count (aggregate-speakers
                                                    (publishable-sessions event)))
                       "rooms" (count (store/rooms-for-event (:id event)))}
                ;; How many proposals an event received is the organizer's
                ;; business until the organizer says otherwise.
                authed? (assoc "submissions"
                               (count (store/submissions-for-event (:id event)))))
     "links" {"self" api
              "docs" (str api "/docs")
              "sessions" (str api "/sessions")
              "submissions" (str api "/submissions")
              "speakers" (str api "/speakers")
              "schedule" (str api "/schedule")
              "rooms" (str api "/rooms")
              "changes" (str api "/changes")
              "sessionsJson" (ex "sessions.json")
              "speakersJson" (ex "speakers.json")
              "calendarIcs" (ex "calendar.ics")
              "llmsTxt" (str base "/events/" slug "/llms.txt")
              "agenda" (str base "/agenda/" slug)}}))

(def api-endpoints
  "The endpoint table, as DATA — one source of truth for the service index, the
   docs page and the tests. A route that is not in this vector is a route the
   docs page cannot forget to document, because the docs page is generated from
   it."
  [{:method "GET" :path "/api/v1"
    :auth :public
    :summary "This index: what the service is and where the endpoints are."}
   {:method "GET" :path "/api/v1/events/{slug}"
    :auth :public
    :summary "Event detail — ids, dates, timezone, CFP state, and a link to every other endpoint."
    :notes "With a token, `counts.submissions` is included."}
   {:method "GET" :path "/api/v1/events/{slug}/docs"
    :auth :public
    :summary "This page."}
   {:method "GET" :path "/api/v1/events/{slug}/sessions"
    :auth :public
    :summary "The published program. Every row carries `id` and `speakerIds`."
    :params [["status" "all, or one of Accepted / Waitlisted / Accept Queue / Pending / Decline Queue / Declined / Withdrawn / Draft. Requires a token."]]}
   {:method "GET" :path "/api/v1/events/{slug}/submissions"
    :auth :token
    :summary "Every submission in every state, with `status`, `notified` and `notifiedAt`."
    :params [["status" "Filter to one status."]]}
   {:method "GET" :path "/api/v1/events/{slug}/submissions/{id}"
    :auth :public
    :summary "One submission in full. Unpublished rows require a token."}
   {:method "POST" :path "/api/v1/events/{slug}/submissions/{id}/reviews"
    :auth :reviewer-or-review-bot
    :summary "Record an idempotent human or AI review; key scope selects the fact type."
    :params [["idempotency-key" "Required. Identical retries return the original review."]]}
   {:method "GET" :path "/api/v1/events/{slug}/speakers"
    :auth :public
    :summary "One row per PERSON, with `id` and `sessionIds`."
    :params [["status" "all — every submitter, not just the published ones. Requires a token."]]}
   {:method "GET" :path "/api/v1/events/{slug}/speakers/{personId}"
    :auth :public
    :summary "One speaker and all of their visible sessions."}
   {:method "POST" :path "/api/v1/events/{slug}/speakers/{personId}/publish"
    :auth :token
    :summary "Publish one manually managed event speaker. Appends a speaker.published fact."}
   {:method "POST" :path "/api/v1/events/{slug}/speakers/{personId}/unpublish"
    :auth :token
    :summary "Unpublish one manually managed event speaker. Appends a speaker.unpublished fact."}
   {:method "GET" :path "/api/v1/events/{slug}/schedule"
    :auth :public
    :summary "Days, rooms, placements and blocks — joined by `sessionId` and `roomId`, plus what is still unscheduled."}
   {:method "GET" :path "/api/v1/events/{slug}/rooms"
    :auth :public
    :summary "The rooms, with stable ids."}
   {:method "GET" :path "/api/v1/events/{slug}/changes"
    :auth :token
    :summary "Incremental change feed — ids only, monotonic `seq`."
    :params [["since" "The last `seq` you processed. Default 0."]]}
   {:method "GET" :path "/events/{slug}/exports/sessions.json"
    :auth :public :summary "ai.engineer-shaped sessions feed (plus `id` and `speakerIds`)."}
   {:method "GET" :path "/events/{slug}/exports/speakers.json"
    :auth :public :summary "ai.engineer-shaped speakers feed (plus `id` and `sessionIds`)."}
   {:method "GET" :path "/events/{slug}/exports/calendar.ics"
    :auth :public :summary "The program as a calendar feed. UIDs are stable — a late room assignment amends the invite."}
   {:method "GET" :path "/events/{slug}/llms.txt"
    :auth :public :summary "A markdown index of everything above, for an agent."}])

(defn api-index
  "GET /api/v1 — the root. A scraper's first request should teach it the whole
   surface, including the parts it is not allowed to read yet."
  [base]
  {"service" "cfp-scheduler-killer"
   "apiVersion" "v1"
   "description" (str "Read and write API for conference programs. Every entity carries a stable id, "
                      "and every join carries the id on both sides. Never match on a name.")
   "auth" {"scheme" "Bearer token, or ?token="
           "header" "Authorization: Bearer <token>"
           "note" (str "A token widens what you can read (unpublished rows, statuses, the "
                       "change feed). It never unlocks private fields: answers marked private "
                       "on the event's form — notes to the committee — are absent from every "
                       "response, authenticated or not.")}
   "conventions" {"ids" "Opaque, stable, and never reused. Person ids are stable across events."
                  "pagination" "None. Everything is returned in one response at conference scale."
                  "caching" "Every response carries an ETag; send If-None-Match and get a 304."
                  "versioning" (str "`scheduleVersion` bumps on any change to the event. "
                                    "Poll it, or use /changes?since= for what moved.")
                  "publication" (str "Public responses describe the PUBLISHED program: accepted "
                                     "AND the speaker has been informed, minus anything in a known "
                                     "scheduling conflict.")}
   "endpoints" (mapv (fn [{:keys [method path auth summary]}]
                       {"method" method "path" path
                        "auth" (name auth) "summary" summary})
                     api-endpoints)})

;; --- API keys ---------------------------------------------------------------
;;
;; One token per event was fine until two things needed access: revoking the
;; Zapier integration meant revoking the conference chair's curl one-liner too,
;; so nobody revoked anything. Named keys make revocation a small act instead of
;; an outage, and the label is what turns "some key leaked" into "THAT key
;; leaked". The original [:settings :api-token] keeps working — an event created
;; before this shipped must not lose its integrations.

(def ^:private key-bytes
  "24 random bytes -> 32 base64url characters. Long enough that guessing is not
   a threat model; short enough to paste into a curl line by hand."
  24)

(def api-key-scopes
  "The grants an organizer can mint. Scope is authority, not a UI label."
  [:read :reviewer :review-bot :organizer])

(defn api-key-scope
  "Parse one scope at the HTTP/domain boundary. Unknown scopes are refusals,
   never organizer-by-typo."
  [scope]
  (let [candidate (cond
                    (keyword? scope) scope
                    (string? scope) (some-> scope str/trim not-empty keyword)
                    (symbol? scope) (keyword (name scope))
                    :else nil)]
    (when (some #{candidate} api-key-scopes)
      candidate)))

(defn new-key-material
  "A fresh secret from SecureRandom. `random-uuid` (what the legacy api-token
   used) is NOT a secret generator — java.util.UUID is not guaranteed to be
   cryptographically random beyond its version-4 bits."
  []
  (let [buf (byte-array key-bytes)]
    (.nextBytes (java.security.SecureRandom.) buf)
    (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder)) buf)))

(defn key-prefix
  "The non-secret key identifier shown in Settings. Eight leading characters
   distinguish conference-scale keys without making the credential usable."
  [k]
  (let [k (str k)]
    (str (subs k 0 (min 8 (count k))) "…")))

(defn- actor-key-prefix [k]
  (let [k (str k)]
    (subs k 0 (min 8 (count k)))))

(defn- secret-hash [secret]
  (let [bytes (.digest (java.security.MessageDigest/getInstance "SHA-256")
                       (.getBytes (str secret) "UTF-8"))]
    (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder)) bytes)))

(defn api-keys-for
  "Live (non-revoked) keys for an event, oldest first."
  [event]
  (->> (vals (get-in event [:settings :api-keys]))
       (sort-by :created-at)
       vec))

(defn create-api-key!
  "Mint a named key. Returns the key map INCLUDING :key — the only moment the
   caller ever sees the material, because the settings page shows it once and
   then only ever shows the prefix."
  ([event label actor]
   ;; Compatibility for code that minted the old unscoped, fully privileged
   ;; credential. New organizer UI always calls the scoped arity.
   (create-api-key! event label :organizer nil actor))
  ([event label scope person-id actor]
   (let [requested-scope scope
         scope (api-key-scope requested-scope)]
     (when-not scope
       (throw (ex-info "Choose a valid API key scope."
                       {:type :invalid-api-key-scope
                        :scope requested-scope
                        :allowed api-key-scopes})))
     (let [key (new-key-material)
           row {:id (store/new-id)
                :event-id (:id event)
                :label (or (not-empty (str/trim (str label))) "Unnamed key")
                :key-hash (secret-hash key)
                :prefix (actor-key-prefix key)
                :scope scope
                :person-id person-id
                :created-by actor
                :created-at (store/now-iso)}]
       (store/append! {:type "api-key.created" :actor actor :event-id (:id event)
                       :payload row})
       ;; Deliberately NOT logged with the material — an access log is the one
       ;; place a secret must never land.
       (log/info :api-key-created :event-id (:id event) :label (:label row)
                 :scope scope :key-id (:id row))
       (assoc row :key key)))))

(defn revoke-api-key!
  [event key-id actor]
  (when-let [row (get-in event [:settings :api-keys key-id])]
    (store/append! {:type "api-key.revoked" :actor actor :event-id (:id event)
                    :payload {:id key-id :event-id (:id event)
                              :label (:label row)
                              :at (store/now-iso)}})
    (log/info :api-key-revoked :event-id (:id event) :key-id key-id
              :label (:label row))
    key-id))

(defn- same-secret?
  "Constant-time compare. A token check that short-circuits on the first wrong
   byte is a token check that can be walked one byte at a time."
  [expected given]
  (java.security.MessageDigest/isEqual
    (.getBytes (str expected) "UTF-8")
    (.getBytes (str given) "UTF-8")))

(defn- stored-key-matches? [key-row token]
  (if (contains? key-row :key-hash)
    (when-let [expected-hash (:key-hash key-row)]
      (same-secret? expected-hash (secret-hash token)))
    ;; Historical facts stored plaintext material. Keep that read arm until
    ;; every live legacy key has been rotated; new facts never take it.
    (when-let [expected-key (:key key-row)]
      (same-secret? expected-key token))))

(defn- stored-key-scope [key-row]
  (if-not (contains? key-row :scope)
    :organizer
    (or (api-key-scope (:scope key-row))
        (do
          (log/error :api-key-refused
                     :type :invalid-api-key-scope
                     :key-id (:id key-row)
                     :stored-scope (:scope key-row))
          nil))))

(defn api-key-context
  "Resolve a bearer secret to its event-scoped grant without returning the
   secret. Missing scope on historical named keys preserves their old organizer
   authority; every newly minted key records an explicit scope."
  [event token]
  (let [token (str token)]
    (when-not (str/blank? token)
      (or (when-let [legacy (get-in event [:settings :api-token])]
            (when (and (not (str/blank? (str legacy)))
                       (same-secret? legacy token))
              {:id :legacy
               :scope :organizer
               :prefix (actor-key-prefix token)
               :legacy? true}))
          (some (fn [k]
                  (when (stored-key-matches? k token)
                    (when-let [scope (stored-key-scope k)]
                      {:id (:id k)
                       :scope scope
                       :prefix (actor-key-prefix token)
                       :person-id (:person-id k)
                       :legacy? (contains? k :key)})))
                (vals (get-in event [:settings :api-keys])))))))

(defn api-key-actor
  "Evidence-grade actor stamped on every API-authenticated write fact."
  [{:keys [scope prefix]}]
  (str "api:" (name scope) ":" prefix))

(defn valid-token?
  "The legacy per-event token, or any live named key. Both are secrets for the
   same event. Authorization decisions use api-key-context's scope."
  [event token]
  (boolean (api-key-context event token)))

(defn bearer-token
  "Accept either `Authorization: Bearer <t>` or `?token=<t>`."
  [req]
  (or (some-> (get-in req [:headers "authorization"])
              (->> (re-matches #"(?i)bearer\s+(.+)"))
              second
              str/trim)
      (not-empty (str (get-in req [:params :token])))))
