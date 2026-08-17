(ns cfp-scheduler-killer.views.avatar
  "Leaf person-image presentation shared across organizer surfaces."
  (:require
   [clojure.string :as str]))

(defn initials
  "\"Gene Kim\" → \"GK\" — the avatar disc's two letters (pre-pool fallback)."
  [nm]
  (->> (str/split (str nm) #"\s+")
       (keep #(some-> % first str str/upper-case))
       (take 2)
       (apply str)))

(def ^:private face-pool-size
  "How many pNN.jpg live in resources/public/images/people. Bump when the
   pool grows (bd 9ot)."
  48)

(defn pool-face
  "Deterministic demo headshot for anyone WITHOUT an uploaded one: the same
   id (or name) always hashes to the same face, with zero data changes — a
   real :headshot-url always wins at the call site (bd 9ot, Gene 2026-08-10).
   The faces are AI-generated people who do not exist."
  [id]
  (format "/images/people/p%02d.jpg"
          (inc (mod (Math/abs (long (hash (str id)))) face-pool-size))))
