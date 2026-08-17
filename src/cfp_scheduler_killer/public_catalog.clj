(ns cfp-scheduler-killer.public-catalog
  "Public read projection shared by sessions, speakers, gallery, and agenda detail."
  (:require
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.exports :as exports]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [clojure.string :as str]))

(defn- pick [& values]
  (first (remove str/blank? (map str (remove nil? values)))))

(defn llms-path
  "The stable public agent index for one event."
  [event]
  (str "/events/" (:slug event) "/llms.txt"))

(defn- public-speaker [speaker]
  (let [person (store/person-by-id (:person-id speaker))
        profile (:profile person)]
    {:id (str (:person-id speaker))
     ;; The public permalink, minted at inform time. nil until then — every
     ;; speaker URL falls back to the UUID, which never stops resolving.
     :slug (:slug person)
     :name (str (or (pick (:name person) (:name speaker)) ""))
     :tagline (or (pick (:tagline profile) (:title speaker)) "")
     :company (str (or (pick (:org profile) (:org speaker)) ""))
     :bio (or (pick (:bio profile) (:bio speaker)) "")
     :headshot (or (pick (:headshot-url profile) (:headshot-url speaker)) "")}))

(defn- public-session [event submission]
  (let [answers (exports/public-answers submission)
        placement (exports/placement-fields event submission)
        slot (store/slot-for (:id submission))
        room (or (not-empty (get placement "room")) "Room TBA")]
    {:id (str (:id submission))
     :title (str (:talk-title answers))
     :description (str (:abstract answers))
     :format (str (or (:session-format answers) ""))
     :track (exports/track-of answers)
     :day (get placement "day")
     :day-key (:day slot)
     :time (get placement "time")
     :room room
     :start (:start slot)
     :end (:end slot)
     :speakers (mapv #(assoc (public-speaker %) :told? true)
                     (:speakers submission))}))

(defn- public-content-visible?
  "Explicit editorial workflow states are authoritative. Rows created before
   content statuses existed have no persisted state and retain their historical
   visibility until an organizer moves them through the workflow."
  [submission]
  (or (nil? (:content-status submission))
      (= "Approved" (submissions/content-status submission))))

(defn sessions
  "Accepted, informed, editorially approved, non-conflicted sessions in stable
   program order."
  [event]
  (->> (exports/publishable-sessions event)
       (filter public-content-visible?)
       (map #(public-session event %))
       (sort-by (juxt #(or (:day-key %) "9999-99-99")
                      #(or (:start %) Integer/MAX_VALUE)
                      #(str/lower-case (:title %))
                      :id))
       vec))

(defn session-by-id [event submission-id]
  (some #(when (= submission-id (:id %)) %) (sessions event)))

(defn norm-name [n] (-> (or n "") str/trim str/lower-case (str/replace #"\s+" " ")))

(defn- surname-sort-key
  "A stable public-directory key. Seed/demo identities commonly end in a
   sequence number (for example, `Sandbox Speaker 008`); that fixture suffix
   is an identifier, not the person's surname. Conventional generational
   suffixes receive the same treatment."
  [name]
  (let [parts (str/split (norm-name name) #"\s+")
        suffix? #(boolean (re-matches #"(?:\d+|jr\.?|sr\.?|ii|iii|iv)" %))
        name-parts (vec (drop-last (count (take-while suffix? (reverse parts))) parts))]
    [(or (last name-parts) "") (str/join " " parts)]))

(defn- cfp-speakers
  "Unique accepted-and-informed CFP speakers with their visible public sessions.

   Editorial approval controls each speaker's `:published?` flag, not whether
   the speaker remains in the organizer's program roster."
  [event]
  (let [visible-session-ids (into #{} (map :id) (sessions event))]
    (->> (exports/published-sessions (:id event))
         (map #(assoc (public-session event %)
                      :published? (contains? visible-session-ids
                                             (str (:id %)))))
         (reduce (fn [by-id session]
                   (let [session-details (select-keys session
                                                      [:id :title :day :day-key
                                                       :time :room :start :end
                                                       :track :format])
                         published? (:published? session)]
                     (reduce (fn [m speaker]
                               (update m (:id speaker)
                                       (fn [current]
                                         (-> (or current (assoc speaker
                                                                :sessions []
                                                                :published? false))
                                             (update :sessions
                                                     #(cond-> % published?
                                                              (conj session-details)))
                                             (update :published?
                                                     #(or % published?))))))
                             by-id
                             (:speakers session))))
                 {})
         vals
         (map (fn [speaker]
                (update speaker :sessions
                        (fn [xs]
                          (vec (sort-by (juxt #(or (:day-key %) "9999-99-99")
                                              #(or (:start %) Integer/MAX_VALUE)
                                              :title)
                                        xs))))))
         (sort-by (comp surname-sort-key :name))
         vec)))

(defn program-speakers
  "The organizer's full program-speaker roster, including unpublished manual
   speakers. CFP speakers keep their accepted+informed roster membership while
   `:published?` continues to enforce editorial public visibility."
  [event]
  (let [cfp (map #(assoc % :manual? false)
                 (cfp-speakers event))
        taken (into #{} (map (comp norm-name :name)) cfp)
        announced (->> (events/announced-speakers event)
                       (remove #(taken (norm-name (:name %))))
                       (map (fn [{:keys [name org title headshot-url person-id]
                                  :as entry}]
                              (let [published? (not= false (:published? entry))]
                                (if person-id
                                  (assoc (public-speaker entry)
                                         :announced? true :manual? true
                                         :published? published?
                                         :told? false :sessions [])
                                  ;; INTENT: EMB-003
                                  {:slug (exports/announced-speaker-id event entry)
                                   :name name :tagline title :company org
                                   :headshot headshot-url :bio ""
                                   :announced? true :manual? true
                                   :published? published?
                                   :told? false :sessions []})))))]
    (->> (concat cfp announced)
         (sort-by (comp surname-sort-key :name))
         vec)))

(defn public-speakers
  "The full PUBLIC speaker roster: CFP-accepted speakers (with sessions) PLUS
   the event's ANNOUNCED speakers (settings :announced-speakers — the real
   invited lineup, e.g. scraped from the conference site). An adopted entry's
   person-id lights the same public identity fields as a CFP speaker; a person
   in both lists appears once, as the richer CFP record. One dataset, every
   public view."
  [event]
  (filterv :published? (program-speakers event)))

(defn announce-stats [event]
  (let [roster (program-speakers event)]
    {:lit (count (filter #(and (:id %) (:published? %)) roster))
     :total (count roster)}))

(defn program-speaker-by-id [event person-id]
  (some #(when (= (str person-id) (str (:id %))) %)
        (program-speakers event)))

(defn speaker-by-id [event person-id]
  (some #(when (= person-id (:id %)) %) (public-speakers event)))

(defn tracks [sessions*]
  (->> sessions* (map :track) (remove str/blank?) distinct sort vec))

(defn formats [sessions*]
  (->> sessions* (map :format) (remove str/blank?) distinct sort vec))

(defn rooms [sessions*]
  (->> sessions* (map :room) (remove str/blank?) distinct sort vec))

(defn filter-sessions
  ([sessions* q track] (filter-sessions sessions* q track nil nil))
  ([sessions* q track format room]
   (let [needle (str/lower-case (str/trim (or q "")))]
     (->> sessions*
          (filter (fn [session]
                    (and (or (str/blank? track) (= track (:track session)))
                         (or (str/blank? format) (= format (:format session)))
                         (or (str/blank? room) (= room (:room session)))
                         (or (str/blank? needle)
                             (str/includes? (str/lower-case (:title session)) needle)
                             (some #(str/includes? (str/lower-case (:name %)) needle)
                                   (:speakers session))))))
          vec))))

(defn- speaker-search-text [speaker]
  (->> (concat [(:name speaker) (:company speaker) (:tagline speaker) (:bio speaker)]
               (mapcat (juxt :title :track :format :room) (:sessions speaker)))
       (remove str/blank?)
       (str/join " ")
       str/lower-case))

(defn filter-speakers [speakers* q]
  (let [needle (str/lower-case (str/trim (or q "")))]
    (if (str/blank? needle)
      speakers*
      (filterv #(str/includes? (speaker-search-text %) needle) speakers*))))
