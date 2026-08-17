(ns cfp-scheduler-killer.views.dashboard-test
  (:require
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.handlers.dashboard :as dashboard-handler]
   [cfp-scheduler-killer.speaker-tasks :as speaker-tasks]
   [cfp-scheduler-killer.views.dashboard :as dashboard]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [hiccup2.core :as h])
  (:import
   (java.time Instant LocalDate)))

(deftest organizer-dashboard-shows-the-materials-chase-list
  (let [event {:id "event-1" :slug "summit" :name "Summit" :tz "UTC"}
        rows [{:submission-id "s-1"
               :key "slides"
               :speaker-name "Ada Lovelace"
               :talk-title "Algebraic Programs"
               :label "Slides draft"
               :due-on (LocalDate/of 2026 9 19)
               :status :overdue
               :days-overdue 12
               :days-outstanding 30
               :last-chased-at (Instant/parse "2026-09-28T09:00:00Z")
               :chase-count 1}]
        html (str (h/html (dashboard/speaker-materials-ledger event rows)))]
    (is (str/includes? html "Speaker materials"))
    (is (str/includes? html "Who owes what"))
    (is (str/includes? html "Ada Lovelace"))
    (is (str/includes? html "Slides draft"))
    (is (str/includes? html "30 days outstanding"))
    (is (str/includes? html "12 days overdue"))
    (is (str/includes? html "Last contacted Sep 28"))
    (is (str/includes? html "/events/summit/deliverables"))))

(deftest materials-identity-crosses-only-the-chair-dashboard-boundary
  (let [event {:id "event-1"}
        rows [{:speaker-name "Ada Lovelace"}]
        projected (fn [role]
                    (with-redefs [committees/role-on-event (fn [_ _] role)
                                  speaker-tasks/materials-chase-list-for-event
                                  (constantly rows)]
                      (#'dashboard-handler/speaker-materials-for-dashboard
                        event {:id "person-1"})))]
    (is (= rows (projected "chair")))
    (is (= [] (projected "reviewer")))
    (is (= [] (projected nil)))))
