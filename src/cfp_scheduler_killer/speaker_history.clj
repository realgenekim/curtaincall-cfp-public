(ns cfp-scheduler-killer.speaker-history
  "The 'Previously at IT Revolution' video-library moat.

   Loads the curated speaker-history corpus (resources/vl-speaker-history.edn)
   once and answers `history-for` by NORMALIZED name — lowercase, trimmed,
   internal whitespace collapsed — so 'Steve  Yegge ' matches 'steve yegge'.

   Pure and side-effect-free at call time (the EDN is read once, at load).
   Data provenance and the :history-url convention live in the EDN header."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def ^:private resource-name "vl-speaker-history.edn")

(defn normalize
  "Canonicalize quotes, lowercase, trim, and collapse whitespace — the match key."
  [name]
  (-> (or name "")
      (str/replace #"[“”]" "\"")
      (str/replace #"[‘’]" "'")
      str/trim
      str/lower-case
      (str/replace #"\s+" " ")))

(defn- load-corpus
  "Read the curated EDN into a {normalized-name -> record} index, or {} when the
   resource is absent (so the feature degrades to a no-op, never a crash)."
  []
  (if-let [res (io/resource resource-name)]
    (->> (edn/read-string (slurp res))
         (reduce (fn [m record]
                   (assoc m (normalize (or (:name-key record) (:name record)))
                          record))
                 {}))
    {}))

(def ^:private index
  "Normalized-name -> history record. Computed once at namespace load."
  (delay (load-corpus)))

(defn history-for
  "The history record for `name` (matched on normalized name), or nil."
  [name]
  (get @index (normalize name)))
