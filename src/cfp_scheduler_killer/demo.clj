(ns cfp-scheduler-killer.demo
  "Allowlisted judge-demo personas and their runtime gate."
  (:require
   [clojure.string :as str]))

(def personas
  [{:role "organizer" :label "Organizer · swyx" :email "swyx@ai.engineer" :enabled? true}
   {:role "reviewer" :label "Reviewer · Maya Lindholm" :email "maya.lindholm@example.com" :enabled? true}
   {:role "speaker" :label "Speaker · Amara Devlin"
    :email "amara.devlin+472@beaconloop.example.com" :enabled? true}])

(def ^:private persona-addresses
  (set (map :email personas)))

(def role->email
  (into {} (map (juxt :role :email)) personas))

(defn persona-enabled? [role]
  (boolean (some #(and (= role (:role %)) (:enabled? %)) personas)))

(defn persona-role [email]
  (let [normalized (some-> email str/trim str/lower-case)]
    (some (fn [{:keys [role email]}]
            (when (= normalized (str/lower-case email)) role))
          personas)))

(defn personas? []
  ;; One product, one login page (Gene, 2026-08-11): Google, magic link, and
  ;; the three safe Judge Sandbox doors render in every environment.
  true)

(defn persona-emails []
  (->> (str/split (or (System/getenv "DEMO_PERSONA_EMAILS") "") #",")
       (map str/trim)
       (remove str/blank?)
       (map str/lower-case)
       set))

(defn persona-email? [email]
  (let [normalized (some-> email str/trim str/lower-case)]
    (and (personas?)
         (contains? persona-addresses normalized)
         (contains? (persona-emails) normalized))))
