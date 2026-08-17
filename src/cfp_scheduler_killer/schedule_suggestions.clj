(ns cfp-scheduler-killer.schedule-suggestions
  "Deterministic, conservative assistance for the organizer-owned schedule.

   Suggestions fill only unplaced sessions, honor rooms, global/room blocks,
   and speaker conflicts, and never move an existing placement."
  (:require
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.schedule :as schedule]
   [cfp-scheduler-killer.store :as store]
   [clojure.set :as set]))

(defn- programming-bounds
  [event]
  (let [{:keys [day-start day-end]} (events/day-hours event)
        start (schedule/parse-time day-start)
        end (schedule/parse-time day-end)]
    (when (and start end (< start end))
      [start end])))

(defn- overlap?
  [a b]
  (and (= (:day a) (:day b))
       (< (:start a) (:end b))
       (< (:start b) (:end a))))

(defn- speaker-ids
  [session]
  (set (keep :person-id (:speakers session))))

(defn- room-or-speaker-conflict?
  [candidate placed]
  (and (overlap? candidate placed)
       (or (= (:room-id candidate) (:room-id placed))
           (seq (set/intersection (speaker-ids candidate)
                                  (speaker-ids placed))))))

(defn- block-conflict?
  [candidate block]
  (and (overlap? candidate block)
       (or (nil? (:room-id block))
           (= (:room-id candidate) (:room-id block)))))

(defn- candidates
  [event rooms duration]
  (when-let [[day-start day-end] (programming-bounds event)]
    (for [day (schedule/event-days event)
          start (range day-start day-end schedule/slot-granularity-minutes)
          room (sort-by :name rooms)
          :let [end (+ start duration)]
          :when (<= end day-end)]
      {:day day :start start :end end :duration duration :room-id (:id room)})))

(defn suggestions
  "Return a deterministic conflict-free placement plan over current state."
  [event]
  (let [rooms (store/rooms-for-event (:id event))
        blocks (store/blocks-for-event (:id event))]
    (:planned
      (reduce
        (fn [{:keys [occupied planned] :as acc} submission]
          (let [duration (schedule/duration-for event submission)
                candidate (first
                            (remove
                              (fn [slot]
                                (let [candidate (assoc slot :speakers (:speakers submission))]
                                  (or (some #(room-or-speaker-conflict? candidate %)
                                            occupied)
                                      (some #(block-conflict? candidate %) blocks))))
                              (candidates event rooms duration)))]
            (if candidate
              (let [placement (merge candidate
                                     {:submission-id (:id submission)
                                      :title (get-in submission [:answers :talk-title])
                                      :speakers (:speakers submission)})]
                {:occupied (conj occupied placement)
                 :planned (conj planned placement)})
              acc)))
        {:occupied (schedule/placements (:id event)) :planned []}
        (sort-by #(get-in % [:answers :talk-title]) (schedule/tray (:id event)))))))

(defn apply!
  "Apply the current suggestion plan through the canonical placement verb."
  [event actor]
  (mapv (fn [{:keys [submission-id day start duration room-id]}]
          (schedule/place! event submission-id
                           {:day day
                            :start (schedule/minutes->hhmm start)
                            :duration duration
                            :room-id room-id}
                           actor))
        (suggestions event)))
