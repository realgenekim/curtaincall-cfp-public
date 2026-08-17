(ns cfp-scheduler-killer.judge-sandbox-fixture-test
  (:require
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.seed :as seed]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [cheshire.core :as json]
   [cli.judge-sandbox :as judge-sandbox]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ring.mock.request :as mock]))

(def fixture "resources/judge-sandbox/events.jsonl")

(defn- fixture-events []
  (with-open [reader (io/reader fixture)]
    (doall (map #(json/parse-string % true) (line-seq reader)))))

(deftest judge-sandbox-pins-a-populated-public-program-test
  (let [events (fixture-events)
        accepted-notices (filter #(and (= "submission.notified" (:type %))
                                       (= "Accepted"
                                          (get-in % [:payload :status-at-notify])))
                                 events)
        slots (filter #(= "slot.assigned" (:type %)) events)
        rooms (filter #(= "room.added" (:type %)) events)]
    (testing "accepted decisions cross the real public publication boundary"
      (is (= 30 (count accepted-notices))))
    (testing "EMB agenda evidence spans both days and several room columns"
      (is (= 30 (count slots)))
      (is (= #{"2026-10-07" "2026-10-08"}
             (set (map #(get-in % [:payload :day]) slots))))
      (is (= #{"Main Stage" "Discovery Theater" "Transformation Lab"}
             (set (map #(get-in % [:payload :name]) rooms)))))))

(deftest fresh-install-seed-populates-every-core-surface-test
  (with-temp-store
    (fn []
      (let [event (judge-sandbox/seed! 12)
            submissions (submissions/for-event (:id event))
            speakers (mapcat :speakers submissions)
            facts (events/log-for-event (:id event))
            ratings (filter #(= "rating.set" (:type %)) facts)
            comments (filter #(= "comment.added" (:type %)) facts)
            notifications (filter #(= "submission.notified" (:type %)) facts)
            slots (filter #(= "slot.assigned" (:type %)) facts)
            rooms (filter #(= "room.added" (:type %)) facts)
            statuses (set (map :status submissions))
            handler (server/create-app)
            program (:body (handler (mock/request
                                      :get (str "/program/" (:slug event)))))
            cfp (:body (handler (mock/request
                                  :get (str "/cfp/" (:slug event)))))]
        (testing "the real EAIS Charlotte form is installed"
          (is (= (mapv :id seed/eais-charlotte-form)
                 (mapv (comp keyword :id)
                       (:fields (events/form-for-event (:id event))))))
          (is (str/includes? cfp "What measurable outcomes can you share?")))
        (testing "plausible submissions and speakers fill the review inputs"
          (is (= 12 (count submissions)))
          (is (= 12 (count (set (map :email speakers)))))
          (is (every? #(and (not (str/blank? (:name %)))
                            (not (str/blank? (:org %)))
                            (not (str/blank? (:bio %))))
                      speakers))
          (is (= 24 (count ratings)))
          (is (= #{3.0 4.0} (set (map #(get-in % [:payload :stars]) ratings))))
          (is (<= 12 (count comments)))
          (is (= #{"Accepted" "Declined" "Pending" "Waitlisted"} statuses)))
        (testing "published program surfaces are populated through real boundaries"
          (is (= (count (filter #(= "Accepted" (:status %)) submissions))
                 (count notifications)
                 (count slots)))
          (is (= 3 (count rooms)))
          (is (str/includes? program "Agents that remember"))
          (is (str/includes? program "Amara Devlin")))))))
