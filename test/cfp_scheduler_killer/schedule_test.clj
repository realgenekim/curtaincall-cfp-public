(ns cfp-scheduler-killer.schedule-test
  "The blocking-sheet scheduler.

   The tests that matter are the ones that prove the DRAFT-FIRST rules hold:
   a placement with no room is legal, a conflict never blocks a save, and the
   tray only ever contains talks whose speakers have been told."
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.exports :as exports]
   [cfp-scheduler-killer.inform :as inform]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.schedule :as schedule]
   [cfp-scheduler-killer.schedule-suggestions :as schedule-suggestions]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submission-content :as submission-content]
   [cfp-scheduler-killer.submissions :as sub]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock])
  (:import
   (java.time LocalDate LocalDateTime)))

(use-fixtures :each with-temp-store (fn [f] (reset! auth/tokens {}) (f)))

(def day1 "2026-10-14")
(def day2 "2026-10-15")

;; The hold-the-date banner VEVENT always leads calendar-ics (Gene ratified
;; 2026-08-11) — a stable UID ending in this suffix, independent of any
;; session. Assertions about SESSION vevents filter it out by that UID.
;; RFC 5545 folds lines at 75 octets (CRLF + a leading space), and the UID
;; line is long enough to fold mid-suffix — so unfold before matching.
(def ^:private hold-the-date-suffix "-hold-the-date@cfp-scheduler-killer.local")

(defn- session-vevent-count [ics]
  (->> (str/split (str/replace ics "\r\n " "") #"(?=BEGIN:VEVENT)")
       (filter #(str/starts-with? % "BEGIN:VEVENT"))
       (remove #(str/includes? % hold-the-date-suffix))
       count))

(defn- setup!
  "An event over two days with four accepted+informed talks (one speaker has
   TWO of them, so a speaker conflict is constructible) and one untold talk."
  []
  (let [event (events/create-eais-event!
                {:name "Schedule Test Summit" :slug "sched-test" :tz "America/New_York"
                 :starts-on (LocalDate/of 2026 10 14) :ends-on (LocalDate/of 2026 10 15)
                 :location "Charlotte, NC"
                 :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
                 :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
                "kaocha")
        cid (:id (first (events/committees-for-event (:id event))))
        _ (committees/add-member! cid {:name "Gene Kim" :email "gene@example.com"
                                       :role "chair"} "kaocha")
        ff (:fields (events/form-for-event (:id event)))
        mk (fn [title email name* fmt]
             (let [params {:answer-talk-title title :answer-abstract "Abstract."
                           :answer-session-format fmt
                           :answer-org-size ">10,000" :answer-industry "Insurance"
                           :answer-ai-transformation-history "2023."
                           :answer-measurable-outcomes "Numbers."
                           :answer-notes-to-committee "PC only."
                           :speaker-name name* :speaker-email email
                           :speaker-title "VP" :speaker-org "Meridian" :speaker-bio "Bio."}]
               (sub/create-submission! event (sub/parse-answers ff params)
                                       (sub/parse-speaker params) "form" "kaocha")))
        a (mk "Talk A" "marcus@example.com" "Marcus Devlin" "Experience Report")
        b (mk "Talk B" "marcus@example.com" "Marcus Devlin" "Panel")
        c (mk "Talk C" "priya@example.com" "Priya Raghavan" "Experience Report")
        d (mk "Talk D" "dana@example.com" "Dana Whitfield" "Experience Report")
        untold (mk "Untold talk" "untold@example.com" "Nobody Told" "Experience Report")]
    (doseq [s [a b c d untold]]
      (reviews/set-status! (:id s) "Accepted" "gene@example.com"))
    (doseq [s [a b c d]]
      (inform/inform! event (store/submission-by-id (:id s)) "gene@example.com"))
    {:event (events/event-by-slug "sched-test") :a a :b b :c c :d d :untold untold}))

(defn- room! [event nm] (schedule/add-room! event nm "gene@example.com"))

;; --- Time + day helpers -----------------------------------------------------

(deftest time-helpers-test
  (testing "times preserve the exact minute the organizer entered"
    (is (= 540 (schedule/parse-time "09:00")))
    (is (= 555 (schedule/parse-time "09:15")))
    (is (= 560 (schedule/parse-time "09:20")) "never silently change the schedule")
    (is (nil? (schedule/parse-time "not a time")))
    (is (nil? (schedule/parse-time ""))))

  (testing "display formatting is how a program is printed"
    (is (= "9:00am" (schedule/minutes->display 540)))
    (is (= "12:30pm" (schedule/minutes->display 750)))
    (is (= "1:00pm" (schedule/minutes->display 780)))
    (is (= "12:00am" (schedule/minutes->display 0)))
    (is (= "9:00am-9:30am" (schedule/time-range-display 540 570))))

  (testing "hhmm round-trips for form values"
    (is (= "09:30" (schedule/minutes->hhmm 570)))))

(deftest event-days-test
  (let [{:keys [event]} (setup!)]
    (testing "days come from the event's dates"
      (is (= [day1 day2] (schedule/event-days event))))
    (testing "labels match ai.engineer's shape"
      (is (= "Day 1 — Oct 14" (schedule/day-label event day1)))
      (is (= "Day 2 — Oct 15" (schedule/day-label event day2))))
    (testing "bounds"
      (is (schedule/in-bounds? event day1))
      (is (not (schedule/in-bounds? event "2026-10-20"))))
    (testing "an event with no dates yields no days rather than inventing Day 1"
      (is (empty? (schedule/event-days
                    (events/create-event! {:name "Undated" :slug "undated" :tz "UTC"}
                                          "kaocha")))))))

(deftest durations-test
  (let [{:keys [event a b]} (setup!)]
    (testing "duration defaults come from the session format"
      (is (= 25 (schedule/duration-for event (store/submission-by-id (:id a)))))
      (is (= 45 (schedule/duration-for event (store/submission-by-id (:id b))))))
    (testing "an event can override them in settings"
      (is (= 60 (schedule/duration-for
                  (assoc-in event [:settings :default-durations] {:default 60})
                  (store/submission-by-id (:id a))))))))

;; --- Folds round-trip -------------------------------------------------------

(deftest rooms-fold-test
  (let [{:keys [event]} (setup!)
        main (room! event "Main Stage")
        track-a (room! event "Track A")]
    (testing "rooms are added in order"
      (is (= ["Main Stage" "Track A"] (mapv :name (store/rooms-for-event (:id event))))))

    (testing "renaming keeps the id"
      (schedule/rename-room! event (:id main) "Grand Ballroom" "gene@example.com")
      (is (= "Grand Ballroom" (:name (first (store/rooms-for-event (:id event))))))
      (is (= (:id main) (:id (first (store/rooms-for-event (:id event)))))))

    (testing "removing a room does NOT unplace its sessions — they go unroomed"
      (let [{:keys [a]} {:a (first (schedule/tray (:id event)))}]
        (schedule/place! event (:id a) {:day day1 :start "09:00" :room-id (:id track-a)}
                         "gene@example.com")
        (is (= (:id track-a) (:room-id (store/slot-for (:id a)))))
        (schedule/remove-room! event (:id track-a) "gene@example.com")
        (is (= 1 (count (store/rooms-for-event (:id event)))))
        (is (some? (store/slot-for (:id a))) "the placement survived")))

    (testing "it all survives a reload"
      (store/load!)
      (is (= ["Grand Ballroom"] (mapv :name (store/rooms-for-event (:id event))))))))

(deftest placement-fold-test
  (let [{:keys [event a]} (setup!)
        main (room! event "Main Stage")]

    (testing "a placement records day, start, computed end and room"
      (schedule/place! event (:id a) {:day day1 :start "09:00" :room-id (:id main)}
                       "gene@example.com")
      (let [slot (store/slot-for (:id a))]
        (is (= day1 (:day slot)))
        (is (= 540 (:start slot)))
        (is (= 565 (:end slot)) "25-minute default matches the conference's real sessions")
        (is (= (:id main) (:room-id slot)))))

    (testing "placing again MOVES it — one placement per talk, never two"
      (schedule/place! event (:id a) {:day day2 :start "14:00" :room-id nil}
                       "gene@example.com")
      (is (= 1 (count (store/slots-for-event (:id event)))))
      (is (= day2 (:day (store/slot-for (:id a)))))
      (is (nil? (:room-id (store/slot-for (:id a))))))

    (testing "UNROOMED is a real state, not a failure"
      (is (some? (store/slot-for (:id a))))
      (is (= 1 (:unroomed (schedule/stats event)))))

    (testing "the log kept every move"
      (is (= 2 (count (filter #(= "slot.assigned" (:type %)) (store/read-events))))))

    (testing "clearing takes it back to the tray"
      (schedule/clear-slot! event (:id a) "gene@example.com")
      (is (nil? (store/slot-for (:id a))))
      (is (some #(= (:id a) (:id %)) (schedule/tray (:id event)))))

    (testing "and a reload reproduces all of it"
      (schedule/place! event (:id a) {:day day1 :start "10:30" :room-id (:id main)}
                       "gene@example.com")
      (let [before (store/slot-for (:id a))]
        (store/load!)
        (is (= before (store/slot-for (:id a))))))))

(deftest blocks-fold-test
  (let [{:keys [event]} (setup!)
        block (schedule/add-block! event {:day day1 :start "12:00" :duration "60"
                                          :label "Lunch"} "gene@example.com")]
    (testing "a block is a placeholder, not a submission"
      (is (= "Lunch" (:label block)))
      (is (= 720 (:start block)))
      (is (= 780 (:end block)))
      (is (= 1 (count (store/blocks-for-event (:id event))))))

    (testing "blocks show in the per-day arithmetic"
      (is (= 1 (:blocks (first (:per-day (schedule/stats event)))))))

    (testing "removing works and survives a reload"
      (schedule/remove-block! event (:id block) "gene@example.com")
      (store/load!)
      (is (empty? (store/blocks-for-event (:id event)))))))

;; --- The tray ---------------------------------------------------------------

(deftest tray-test
  (let [{:keys [event a untold]} (setup!)]
    (testing "the tray is accepted-AND-informed, minus placed"
      (is (= 4 (count (schedule/tray (:id event)))))
      (is (not-any? #(= (:id untold) (:id %)) (schedule/tray (:id event)))
          "a talk whose speaker has not been told is NOT schedulable"))

    (testing "placing one removes it from the tray"
      (schedule/place! event (:id a) {:day day1 :start "09:00"} "gene@example.com")
      (is (= 3 (count (schedule/tray (:id event)))))
      (is (not-any? #(= (:id a) (:id %)) (schedule/tray (:id event)))))

    (testing "informing the last one makes it schedulable"
      (inform/inform! event (store/submission-by-id (:id untold)) "gene@example.com")
      (is (= 4 (count (schedule/tray (:id event))))))))

(deftest empty-tray-tells-the-truth-test
  ;; The bug: an organizer accepts talks, informs nobody, opens the scheduler
  ;; and is told "Everything accepted has a place." Nothing schedules, so
  ;; nothing publishes, so every public surface is empty — for a reason the
  ;; page actively hid. The GATE is right (the agenda follows the promise);
  ;; the COPY was lying about which kind of empty this is.
  (let [{:keys [event a b c d untold]} (setup!)
        handler (server/create-app)
        token (auth/issue-token! "gene@example.com")
        login (handler (mock/request :get (str "/auth/" token)))
        cookie (first (str/split (first (get-in login [:headers "Set-Cookie"])) #";"))
        page (fn [] (:body (handler (-> (mock/request :get "/events/sched-test/schedule")
                                        (mock/header "cookie" cookie)))))]

    (testing "accepted-but-untold is counted, and is NOT quietly folded into accepted"
      (is (= [(:id untold)] (mapv :id (schedule/awaiting-inform (:id event)))))
      (let [s (schedule/stats event)]
        (is (= 1 (:awaiting-inform s)))
        (is (= 4 (:accepted s)) "the accepted count stays the schedulable pool")))

    (doseq [[i s] (map-indexed vector [a b c d])]
      (schedule/place! event (:id s)
                       {:day day1 :start (format "%02d:00" (+ 9 i))}
                       "gene@example.com"))

    (testing "tray empty with an untold acceptance: the page names the real blocker"
      (is (empty? (schedule/tray (:id event))))
      (let [html (page)]
        (is (not (str/includes? html "Everything accepted has a place"))
            "the tray is empty because a speaker has not been told, not because
             every acceptance is placed")
        (is (str/includes? html "1 accepted talk cannot be scheduled yet"))
        (is (str/includes? html "accepted, not yet informed")
            "the status bar carries the count too")
        (is (str/includes? html "/events/sched-test/inform")
            "and it points at the one action that unblocks the schedule")))

    (testing "informing it moves it into the tray — no gate was loosened"
      (inform/inform! event (store/submission-by-id (:id untold)) "gene@example.com")
      (is (= [(:id untold)] (mapv :id (schedule/tray (:id event)))))
      (is (zero? (:awaiting-inform (schedule/stats event))))
      (is (str/includes? (page) "Untold talk")))

    (testing "and once it is placed the good-news copy is the honest one"
      (schedule/place! event (:id untold) {:day day1 :start "14:00"} "gene@example.com")
      (is (empty? (schedule/tray (:id event))))
      (is (str/includes? (page) "Everything accepted has a place")))))

;; --- Conflicts --------------------------------------------------------------

(deftest speaker-conflict-test
  (let [{:keys [event a b]} (setup!)
        main (room! event "Main Stage")
        track-a (room! event "Track A")]

    (testing "the same speaker in two rooms at once is a conflict"
      ;; Marcus owns both Talk A and Talk B.
      (schedule/place! event (:id a) {:day day1 :start "10:30" :room-id (:id main)}
                       "gene@example.com")
      (schedule/place! event (:id b) {:day day1 :start "10:30" :room-id (:id track-a)}
                       "gene@example.com")
      (let [cs (schedule/conflicts event)
            speaker-conflicts (filter #(= :speaker (:type %)) cs)]
        (is (= 1 (count speaker-conflicts)))
        (is (str/includes? (:message (first speaker-conflicts)) "Marcus Devlin"))
        (is (str/includes? (:message (first speaker-conflicts)) "10:30am"))
        (is (str/includes? (:message (first speaker-conflicts)) "Track A")
            "it names where the OTHER one is")))

    (testing "BUT the save was never blocked — both placements exist"
      (is (some? (store/slot-for (:id a))))
      (is (some? (store/slot-for (:id b))))
      (is (= 2 (:placed (schedule/stats event)))))

    (testing "conflicts are counted in the status bar"
      (is (pos? (:conflicts (schedule/stats event)))))

    (testing "both sides are offered as the fix"
      (let [c (first (filter #(= :speaker (:type %)) (schedule/conflicts event)))]
        (is (some? (:a c)))
        (is (some? (:b c)))))

    (testing "moving one of them resolves it"
      (schedule/place! event (:id b) {:day day1 :start "13:00" :room-id (:id track-a)}
                       "gene@example.com")
      (is (empty? (filter #(= :speaker (:type %)) (schedule/conflicts event)))))

    (testing "adjacent, non-overlapping sessions are NOT a conflict"
      (schedule/place! event (:id b) {:day day1 :start "11:00" :room-id (:id track-a)}
                       "gene@example.com")
      (is (empty? (filter #(= :speaker (:type %)) (schedule/conflicts event)))
          "A ends at 11:00 and B starts at 11:00 — back to back is fine"))

    (testing "different days never collide"
      (schedule/place! event (:id b) {:day day2 :start "10:30" :room-id (:id track-a)}
                       "gene@example.com")
      (is (empty? (filter #(= :speaker (:type %)) (schedule/conflicts event)))))))

(deftest room-conflict-test
  (let [{:keys [event a c]} (setup!)
        main (room! event "Main Stage")]
    (schedule/place! event (:id a) {:day day1 :start "09:00" :room-id (:id main)}
                     "gene@example.com")
    (schedule/place! event (:id c) {:day day1 :start "09:15" :room-id (:id main)}
                     "gene@example.com")

    (testing "two sessions in one room overlap"
      (let [cs (filter #(= :room (:type %)) (schedule/conflicts event))]
        (is (= 1 (count cs)))
        (is (str/includes? (:message (first cs)) "Main Stage"))))

    (testing "UNROOMED sessions cannot double-book a room"
      (schedule/place! event (:id c) {:day day1 :start "09:15" :room-id nil}
                       "gene@example.com")
      (is (empty? (filter #(= :room (:type %)) (schedule/conflicts event)))
          "two unroomed sessions at the same time are a legitimate draft state"))))

(deftest bounds-conflict-test
  (let [{:keys [event a]} (setup!)]
    (schedule/place! event (:id a) {:day "2026-10-20" :start "09:00"} "gene@example.com")
    (testing "a session outside the event's days is flagged, not refused"
      (let [cs (filter #(= :bounds (:type %)) (schedule/conflicts event))]
        (is (= 1 (count cs)))
        (is (str/includes? (:message (first cs)) "outside the event dates"))
        (is (some? (store/slot-for (:id a))) "it still saved")))))

;; --- Slot arithmetic --------------------------------------------------------

(deftest stats-test
  (let [{:keys [event a b c]} (setup!)
        main (room! event "Main Stage")]
    (testing "before placing anything"
      (let [s (schedule/stats event)]
        (is (= 4 (:accepted s)))
        (is (= 0 (:placed s)))
        (is (= 4 (:unplaced s)))
        (is (= 0 (:unroomed s)))
        (is (= 1 (:rooms s)))))

    (schedule/place! event (:id a) {:day day1 :start "09:00" :room-id (:id main)} "g")
    (schedule/place! event (:id b) {:day day1 :start "11:00"} "g")
    (schedule/place! event (:id c) {:day day2 :start "09:00" :room-id (:id main)} "g")
    (schedule/add-block! event {:day day1 :start "12:00" :duration "60" :label "Lunch"} "g")

    (testing "the arithmetic Jess and Ann did by hand"
      (let [s (schedule/stats event)]
        (is (= 3 (:placed s)))
        (is (= 1 (:unplaced s)))
        (is (= 1 (:unroomed s)) "Talk B has no room, and that is fine")
        (is (= [{:day day1 :label "Day 1 — Oct 14" :sessions 2 :blocks 1}
                {:day day2 :label "Day 2 — Oct 15" :sessions 1 :blocks 0}]
               (:per-day s)))))))

;; --- Lock -------------------------------------------------------------------

(deftest lock-test
  (let [{:keys [event a]} (setup!)]
    (testing "a fresh schedule is unlocked"
      (is (not (schedule/locked? event))))

    (schedule/lock! event "gene@example.com")
    (let [locked (events/event-by-slug "sched-test")]
      (testing "locking stamps a version"
        (is (schedule/locked? locked))
        (is (= "v1" (schedule/lock-label locked))))

      (testing "every mutation is refused while locked"
        (doseq [f [#(schedule/place! locked (:id a) {:day day1 :start "09:00"} "g")
                   #(schedule/clear-slot! locked (:id a) "g")
                   #(schedule/add-room! locked "Nope" "g")
                   #(schedule/add-block! locked {:day day1 :start "12:00" :label "X"} "g")]]
          (is (= :schedule-locked
                 (:type (ex-data (try (f) nil (catch clojure.lang.ExceptionInfo e e))))))))

      (testing "and nothing was written"
        (is (empty? (store/slots-for-event (:id locked))))))

    (testing "unlocking restores editing, and a second lock bumps the version"
      (let [unlocked (do (schedule/unlock! (events/event-by-slug "sched-test") "g")
                         (events/event-by-slug "sched-test"))]
        (is (not (schedule/locked? unlocked)))
        (is (some? (schedule/place! unlocked (:id a) {:day day1 :start "09:00"} "g")))
        (schedule/lock! (events/event-by-slug "sched-test") "g")
        (is (= "v2" (schedule/lock-label (events/event-by-slug "sched-test"))))))))

;; --- Exports pick up placement ----------------------------------------------

(deftest exports-reflect-placement-test
  (let [{:keys [event a]} (setup!)
        main (room! event "Main Stage")]

    (testing "an unplaced session exports empty schedule fields"
      (let [s (first (filter #(= "Talk A" (get % "title"))
                             (get (exports/sessions-json-data event) "sessions")))]
        (is (= "" (get s "day")))
        (is (= "" (get s "time")))
        (is (= "" (get s "room")))))

    (schedule/place! event (:id a) {:day day1 :start "09:00" :room-id (:id main)}
                     "gene@example.com")

    (testing "once placed, sessions.json carries day/time/room in ai.engineer's shape"
      (let [s (first (filter #(= "Talk A" (get % "title"))
                             (get (exports/sessions-json-data event) "sessions")))]
        (is (= "Day 1 — Oct 14" (get s "day")))
        (is (= "9:00am-9:25am" (get s "time")))
        (is (= "Main Stage" (get s "room")))))

    (testing "the ics uses the real time and names the room"
      (let [ics (exports/calendar-ics event)]
        (is (str/includes? ics "DTSTART:20261014T090000"))
        (is (str/includes? ics "DTEND:20261014T092500"))
        (is (str/includes? ics "LOCATION:Main Stage\\, Charlotte\\, NC"))))

    (testing "MOVING a session bumps SEQUENCE but keeps the UID — amend, never duplicate"
      (let [uid (exports/ics-uid (store/submission-by-id (:id a)))
            seq1 (exports/ics-sequence (store/submission-by-id (:id a)))]
        (schedule/place! event (:id a) {:day day2 :start "14:00" :room-id (:id main)}
                         "gene@example.com")
        (let [ics (exports/calendar-ics event)
              seq2 (exports/ics-sequence (store/submission-by-id (:id a)))]
          (is (> seq2 seq1))
          (is (str/includes? ics (str "UID:" uid)))
          (is (str/includes? ics "DTSTART:20261015T140000"))
          ;; Session vevents only — the hold-the-date banner also leads this
          ;; feed and is excluded from the count.
          (is (= 4 (session-vevent-count ics))
              "still one VEVENT per accepted talk, not one per move"))))))

;; --- The public agenda ------------------------------------------------------

(deftest agenda-test
  (let [{:keys [event a c untold]} (setup!)
        main (room! event "Main Stage")]
    (schedule/place! event (:id a) {:day day1 :start "09:00" :room-id (:id main)} "g")
    (schedule/place! event (:id c) {:day day1 :start "10:00"} "g")
    (schedule/add-block! event {:day day1 :start "12:00" :duration "60" :label "Lunch"} "g")

    (let [days (schedule/agenda (events/event-by-slug "sched-test"))
          d1 (first (filter #(= day1 (:day %)) days))]

      (testing "items are time-ordered, sessions and blocks interleaved"
        (is (= ["Talk A" "Talk C" "Lunch"] (mapv :title (:items d1))))
        (is (= [:session :session :block] (mapv :kind (:items d1)))))

      (testing "rooms appear when known, and their absence is simply absent"
        (is (= "Main Stage" (:room (first (:items d1)))))
        (is (nil? (:room (second (:items d1))))))

      (testing "speakers are named"
        (is (= ["Marcus Devlin"] (:speakers (first (:items d1))))))

      (testing "an un-informed talk is NOT on the agenda even if placed"
        ;; force a placement for the untold talk and confirm the gate still holds
        (schedule/place! (events/event-by-slug "sched-test") (:id untold)
                         {:day day1 :start "15:00"} "g")
        (let [items (:items (first (filter #(= day1 (:day %))
                                           (schedule/agenda (events/event-by-slug "sched-test")))))]
          (is (not-any? #(= "Untold talk" (:title %)) items)))))))

;; --- Routes -----------------------------------------------------------------

(deftest schedule-routes-test
  (let [{:keys [event a]} (setup!)
        handler (server/create-app)
        cookie (let [t (auth/issue-token! "gene@example.com")
                     r (handler (mock/request :get (str "/auth/" t)))]
                 (first (str/split (first (get-in r [:headers "Set-Cookie"])) #";")))
        as (fn [req] (mock/header req "cookie" cookie))
        page #(:body (handler (as (mock/request :get "/events/sched-test/schedule"))))]

    (testing "the page renders the tray, the status bar and the day tabs"
      (let [body (page)]
        (is (str/includes? body "Accepted (unscheduled): 4"))
        (is (str/includes? body "id=\"schedule-status\""))
        (is (str/includes? body "Day 1 — Oct 14"))
        (is (str/includes? body "Day 2 — Oct 15"))
        (is (str/includes? body "Nothing placed on this day yet")
            "an empty day says so rather than drawing an empty lattice")
        (is (= (count (re-seq #"name=\"start\"" body))
               (count (re-seq #"name=\"start\"[^>]*step=\"60\"" body)))
            "every session and block start accepts exact minutes")
        (is (= (count (re-seq #"name=\"duration\"" body))
               (count (re-seq #"name=\"duration\"[^>]*step=\"5\"" body)))
            "every session and block duration accepts five-minute increments")))

    (testing "Publish is an explicit event-sourced handoff"
      (is (str/includes? (page) ">Publish<"))
      (let [resp (handler (as (mock/request :post "/api/events/sched-test/schedule/publish")))
            current (events/event-by-slug "sched-test")]
        (is (= 303 (:status resp)))
        (is (schedule/published? current))
        (is (str/includes? (page) "Published ✓"))
        (is (= "agenda.published"
               (:type (last (store/log-for-event (:id event))))))))

    (testing "content approval is separate from the submission decision"
      (let [detail #(-> (handler (as (mock/request
                                       :get
                                       (str "/events/sched-test/submissions/" (:id a)))))
                        :body)]
        (is (str/includes? (detail) "Approved"))
        (is (= 303
               (:status (handler (as (mock/request
                                       :post
                                       (str "/api/submissions/" (:id a) "/content-status")
                                       {"status" "Approved"}))))))
        (let [current (store/submission-by-id (:id a))]
          (is (= "Approved" (sub/content-status current)))
          (is (= "Accepted" (:status current))
              "editorial approval never changes the 8-value decision"))
        (is (= "submission.content-status-changed"
               (:type (last (store/log-for-event (:id event))))))))

    (testing "placing through the form works"
      (handler (as (mock/request :post "/api/events/sched-test/schedule/room-add"
                                 {"name" "Main Stage"})))
      (let [room-id (:id (first (store/rooms-for-event (:id event))))
            resp (handler (as (mock/request :post "/api/events/sched-test/schedule/place"
                                            {"submission-id" (:id a)
                                             "day" day1 "start" "09:20"
                                             "duration" "25" "room-id" room-id})))]
        (is (= 303 (:status resp)))
        (is (= 560 (:start (store/slot-for (:id a)))))
        (is (= 585 (:end (store/slot-for (:id a)))))
        (let [body (page)]
          (is (str/includes? body "Accepted (unscheduled): 3"))
          (is (str/includes? body "Unroomed") "the unroomed column appears with the grid")
          (is (str/includes? body "Main Stage")))))

    (testing "placing with NO room is a first-class action, not an error"
      (is (= 303 (:status (handler (as (mock/request
                                         :post "/api/events/sched-test/schedule/place"
                                         {"submission-id" (:id a)
                                          "day" day1 "start" "11:00" "room-id" ""}))))))
      (is (nil? (:room-id (store/slot-for (:id a))))))

    (testing "track CRUD edits the canonical CFP taxonomy"
      (is (str/includes? (page) "Track management"))
      (is (str/includes? (page) "Add track"))
      (is (= 303
             (:status
               (handler
                 (as (mock/request :post "/api/events/sched-test/schedule/track-add"
                                   {"label" "Reliability"}))))))
      (let [options (->> (:fields (events/form-for-event (:id event)))
                         (filter #(= "track" (name (:id %))))
                         first
                         :options)]
        (is (some #{"Reliability"} options))
        (is (= "form.updated"
               (:type (last (store/log-for-event (:id event)))))))
      (is (= 422
             (:status
               (handler
                 (as (mock/request :post "/api/events/sched-test/schedule/track-add"
                                   {"label" "reliability"}))))))
      (is (= 303
             (:status
               (handler
                 (as (mock/request :post "/api/events/sched-test/schedule/track-rename"
                                   {"old-label" "Reliability"
                                    "new-label" "Resilience"}))))))
      (is (str/includes? (page) "Resilience"))
      (is (= 303
             (:status
               (handler
                 (as (mock/request :post "/api/events/sched-test/schedule/track-retire"
                                   {"label" "Resilience"}))))))
      (let [options (->> (:fields (events/form-for-event (:id event)))
                         (filter #(= "track" (name (:id %))))
                         first
                         :options)]
        (is (not (some #{"Reliability" "Resilience"} options)))
        (is (not (str/includes? (page) "Resilience")))
        (is (= 3
               (count
                 (filter #(= "form.updated" (:type %))
                         (store/log-for-event (:id event))))))))

    (testing "locking hides the mutation forms and 409s a POST"
      (handler (as (mock/request :post "/api/events/sched-test/schedule/lock")))
      (let [body (page)]
        (is (str/includes? body "Locked — v1"))
        (is (not (str/includes? body "Add room")))
        (is (not (str/includes? body "Accepted (unscheduled)"))))
      (let [resp (handler (as (mock/request :post "/api/events/sched-test/schedule/place"
                                            {"submission-id" (:id a)
                                             "day" day2 "start" "09:00"})))]
        (is (= 409 (:status resp)))
        (is (str/includes? (str (:body resp)) "locked"))
        (is (= day1 (:day (store/slot-for (:id a)))) "and nothing moved")))

    (testing "unlocking brings the forms back"
      (handler (as (mock/request :post "/api/events/sched-test/schedule/unlock")))
      (is (str/includes? (page) "Accepted (unscheduled)")))))

(deftest schedule-mutations-refuse-foreign-event-submissions
  (let [{event-a :event submission-a :a} (setup!)
        event-b (events/create-event!
                  {:name "Other Schedule Summit" :slug "sched-other"
                   :tz "America/New_York"
                   :starts-on (LocalDate/of 2026 10 14)
                   :ends-on (LocalDate/of 2026 10 15)}
                  "kaocha")
        committee-b (:id (first (events/committees-for-event (:id event-b))))
        _ (committees/add-member! committee-b
                                  {:name "Gene Kim" :email "gene@example.com"
                                   :role "chair"}
                                  "kaocha")
        handler (server/create-app)
        cookie (let [token (auth/issue-token! "gene@example.com")
                     response (handler (mock/request :get (str "/auth/" token)))]
                 (first (str/split
                          (first (get-in response [:headers "Set-Cookie"])) #";")))
        as-gene #(mock/header % "cookie" cookie)
        event-b-slot-facts #(filter (fn [fact]
                                      (#{"slot.assigned" "slot.cleared"}
                                         (:type fact)))
                                    (store/log-for-event (:id event-b)))]
    (testing "Event B cannot place Event A's proposal"
      (let [response (handler
                       (as-gene
                         (mock/request
                           :post "/api/events/sched-other/schedule/place"
                           {"submission-id" (:id submission-a)
                            "day" day1 "start" "09:00"})))]
        (is (= 404 (:status response)))
        (is (nil? (store/slot-for (:id submission-a))))
        (is (empty? (event-b-slot-facts)))
        (doseq [foreign ["Talk A" "Marcus Devlin" "marcus@example.com"]]
          (is (not (str/includes? (str (:body response)) foreign))))))

    (testing "Event B cannot clear Event A's existing placement"
      (schedule/place! event-a (:id submission-a)
                       {:day day1 :start "10:00"} "gene@example.com")
      (let [before (store/slot-for (:id submission-a))
            response (handler
                       (as-gene
                         (mock/request
                           :post "/api/events/sched-other/schedule/clear"
                           {"submission-id" (:id submission-a)})))]
        (is (= 404 (:status response)))
        (is (= before (store/slot-for (:id submission-a))))
        (is (= (:id event-a) (:event-id (store/slot-for (:id submission-a)))))
        (is (empty? (event-b-slot-facts)))))))

(deftest public-agenda-route-test
  ;; /agenda/:slug 302-redirects onto the canonical /program/:slug (Gene
  ;; ratified 2026-08-11: "/program is canon"); the agenda content itself now
  ;; lives there.
  (let [{:keys [event a]} (setup!)
        handler (server/create-app)
        main (room! event "Main Stage")]
    (schedule/place! event (:id a) {:day day1 :start "09:00" :room-id (:id main)} "g")

    (testing "/agenda/:slug redirects to /program/:slug"
      (let [resp (handler (mock/request :get "/agenda/sched-test"))]
        (is (= 302 (:status resp)))
        (is (= "/program/sched-test" (get-in resp [:headers "Location"])))))

    (testing "the program page is PUBLIC — no login, like the CFP page"
      (let [resp (handler (mock/request :get "/program/sched-test"))]
        (is (= 200 (:status resp)))
        (is (str/includes? (:body resp) "Talk A"))
        (is (str/includes? (:body resp) "Main Stage"))
        (is (str/includes? (:body resp) "9:00am"))))

    (testing "it is speaker chrome — no organizer sidebar"
      (let [body (:body (handler (mock/request :get "/program/sched-test")))]
        (is (not (str/includes? body "class=\"sidebar\"")))
        (is (not (str/includes? body "Review Board")))))

    (testing "private fields never appear"
      (is (not (str/includes? (:body (handler (mock/request :get "/program/sched-test")))
                              "PC only."))))

    (testing "unplaced accepted talks are not on the agenda"
      (let [body (:body (handler (mock/request :get "/program/sched-test")))]
        (is (not (str/includes? body "Talk C")))))

    (testing "an unknown slug 404s"
      (is (= 404 (:status (handler (mock/request :get "/agenda/nope"))))))))

(deftest content-approval-gates-the-public-program-test
  (let [{:keys [event a b]} (setup!)
        handler (server/create-app)
        main (room! event "Main Stage")
        actor "gene@example.com"]
    (schedule/place! event (:id a) {:day day1 :start "09:00" :room-id (:id main)} actor)
    (schedule/place! event (:id b) {:day day1 :start "10:00" :room-id (:id main)} actor)
    (let [updated (submission-content/update-answers!
                   (:id a) {:answer-talk-title "Approved, updated title"
                            :answer-track "Leadership & Organizational Change"}
                   actor)]
      (is (:ok updated) (pr-str updated)))
    (sub/set-content-status! (:id a) "Approved" actor)
    (sub/set-content-status! (:id b) "In review" actor)

    (testing "the organizer control explains every independent public gate"
      (let [token (auth/issue-token! "gene@example.com")
            sign-in (handler (mock/request :get (str "/auth/" token)))
            cookie (first (str/split (first (get-in sign-in [:headers "Set-Cookie"])) #";"))
            detail (-> (mock/request :get (str "/events/sched-test/submissions/" (:id a)))
                       (mock/header "cookie" cookie)
                       handler
                       :body)]
        (is (str/includes? detail "Public program gate"))
        (is (str/includes? detail "Accepted"))
        (is (str/includes? detail "Notified"))
        (is (str/includes? detail "Preview public program"))))

    (testing "approved updated copy appears while unapproved copy stays private"
      (let [body (:body (handler (mock/request :get "/program/sched-test")))]
        (is (str/includes? body "Approved, updated title"))
        (is (not (str/includes? body "Talk B")))))))

;; --- The public gate on conflicts (bd 31b, ratified 2026-08-09) --------------

;; INTENT-TEST: SCHED-002
(deftest conflict-withheld-from-public-test
  ;; A KNOWN conflict is not a partial state — it is known wrong, so it leaves
  ;; every public surface until resolved (withhold, loudly). The organizer side
  ;; deliberately still sees everything: you cannot fix what you cannot see.
  (let [{:keys [event a b]} (setup!)
        main (room! event "Main Stage")
        track-a (room! event "Track A")]
    (schedule/place! event (:id a) {:day day1 :start "10:30" :room-id (:id main)}
                     "gene@example.com")
    (schedule/place! event (:id b) {:day day1 :start "10:30" :room-id (:id track-a)}
                     "gene@example.com")

    (testing "both conflicted sessions leave sessions.json and the count stays consistent"
      (let [data (exports/sessions-json-data event)
            titles (set (map #(get % "title") (get data "sessions")))]
        (is (not (contains? titles "Talk A")))
        (is (not (contains? titles "Talk B")))
        (is (= (count (get data "sessions")) (get data "totalSessions")))))

    (testing "the ics and the public agenda drop them too"
      (is (not (str/includes? (exports/calendar-ics event) "Talk A")))
      (let [titles (set (map :title (mapcat :items (schedule/agenda event))))]
        (is (not (contains? titles "Talk A")))
        (is (not (contains? titles "Talk B")))))

    (testing "the organizer pool still sees everything"
      (is (= 2 (:placed (schedule/stats event))))
      (is (some #(= (:id a) (:id %)) (schedule/schedulable (:id event)))))

    (testing "resolving the conflict republishes both sides"
      (schedule/clear-slot! event (:id b) "gene@example.com")
      (let [titles (set (map #(get % "title")
                             (get (exports/sessions-json-data event) "sessions")))]
        (is (contains? titles "Talk A"))
        (is (contains? titles "Talk B")
            "the cleared side has no slot but that is a PARTIAL state — it publishes")))))

(deftest conservative-schedule-suggestion-test
  (let [{:keys [event a b c d]} (setup!)
        room-a (room! event "Main Stage")
        _room-b (room! event "Breakout")]
    (schedule/place! event (:id c)
                     {:day day1 :start "10:00" :room-id (:id room-a)}
                     "gene@example.com")
    (schedule/add-block! event
                         {:day day1 :start "09:00" :duration "60"
                          :label "Opening keynote"}
                         "gene@example.com")

    (testing "the pure plan never moves placed work or creates known conflicts"
      (let [plan (schedule-suggestions/suggestions event)]
        (is (= #{(:id a) (:id b) (:id d)}
               (set (map :submission-id plan))))
        (is (every? #(>= (:start %) (* 10 60)) plan)
            "the global 09:00 block is honored in every room")
        (is (= 600 (:start (store/slot-for (:id c)))))))

    (testing "apply uses one slot.assigned fact per suggestion"
      (let [before (count (filter #(= "slot.assigned" (:type %))
                                  (store/log-for-event (:id event))))
            applied (schedule-suggestions/apply! event "gene@example.com")
            after (count (filter #(= "slot.assigned" (:type %))
                                 (store/log-for-event (:id event))))]
        (is (= 3 (count applied)))
        (is (= 3 (- after before)))
        (is (empty? (schedule/conflicts event)))
        (is (empty? (schedule/tray (:id event))))
        (is (empty? (schedule-suggestions/apply! event "gene@example.com")))))

    (testing "the real organizer route exposes and applies the explicit action"
      (doseq [submission [a b d]]
        (schedule/clear-slot! event (:id submission) "gene@example.com"))
      (let [handler (server/create-app)
            token (auth/issue-token! "gene@example.com")
            login (handler (mock/request :get (str "/auth/" token)))
            cookie (first (str/split (first (get-in login [:headers "Set-Cookie"])) #";"))
            as (fn [req] (mock/header req "cookie" cookie))
            page (:body (handler (as (mock/request :get "/events/sched-test/schedule"))))
            response (handler
                       (as (mock/request
                             :post "/api/events/sched-test/schedule/suggest")))]
        (is (str/includes? page "Suggest schedule"))
        (is (str/includes? page "Fill open 09:00–17:00 room slots"))
        (is (str/includes? page "without moving anything already placed"))
        (is (= 303 (:status response)))
        (is (= 600 (:start (store/slot-for (:id c)))))
        (is (empty? (schedule/conflicts event)))))))

(deftest schedule-suggestions-honor-programming-hours-test
  (let [{:keys [event]} (setup!)
        _ (room! event "Main Stage")
        _ (room! event "Breakout")]
    (testing "a non-hour programming window bounds every suggested session"
      (let [bounded-event (assoc event :settings {:day-start "10:10" :day-end "11:40"})
            plan (schedule-suggestions/suggestions bounded-event)]
        (is (= 4 (count plan)))
        (is (every? #(<= 610 (:start %) (:end %) 700) plan))))
    (testing "the canonical default ends at 17:00"
      (let [plan (schedule-suggestions/suggestions event)]
        (is (= 4 (count plan)))
        (is (every? #(<= (* 9 60) (:start %) (:end %) (* 17 60)) plan))))
    (testing "malformed and reversed legacy bounds fail safely"
      (doseq [[start end] [["not-a-time" "17:00"] ["18:00" "09:00"]]]
        (is (empty? (schedule-suggestions/suggestions
                      (assoc event :settings {:day-start start :day-end end}))))))))
