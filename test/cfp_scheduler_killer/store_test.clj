(ns cfp-scheduler-killer.store-test
  "The store is the whole system's foundation, so its fold functions are tested
   as PURE data-in/data-out — no files, no atom — plus one round-trip test that
   proves a reloaded log folds to the identical state."
  (:require
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.test-helpers :refer [temp-store-path with-temp-store]]
   [clojure.test :refer [deftest is testing use-fixtures]])
  (:import
   (java.time Instant LocalDate)))

(use-fixtures :each with-temp-store)

;; --- Pure folds -------------------------------------------------------------

(def sample-events
  [{:at "2026-08-01T10:00:00Z" :type "event.created" :actor "gene"
    :payload {:id "e1" :slug "eais" :name "EAIS" :tz "America/New_York"
              :starts-on "2026-10-14" :ends-on "2026-10-15"
              :cfp-opens-at "2026-08-10T04:00:00Z" :cfp-closes-at nil
              :support-email nil :location "Charlotte, NC" :website-url nil
              :settings {:review-visibility "open"} :created-at "2026-08-01T10:00:00Z"}}
   {:at "2026-08-01T10:00:01Z" :type "committee.created" :actor "gene"
    :payload {:id "c1" :event-id "e1" :name "Program Committee"
              :scope {:all true} :coverage-target 2 :created-at "2026-08-01T10:00:01Z"}}
   {:at "2026-08-01T10:00:02Z" :type "form.installed" :actor "gene"
    :payload {:id "f1" :event-id "e1" :fields [{:id "talk-title"}]
              :created-at "2026-08-01T10:00:02Z"}}
   {:at "2026-08-02T09:00:00Z" :type "person.created" :actor "gene"
    :payload {:id "p1" :email "ann@example.com" :name "Ann Perry" :profile {}
              :created-at "2026-08-02T09:00:00Z"}}
   {:at "2026-08-02T09:00:01Z" :type "member.added" :actor "gene"
    :payload {:id "m1" :committee-id "c1" :event-id "e1" :person-id "p1"
              :role "chair" :created-at "2026-08-02T09:00:01Z"}}])

(deftest fold-builds-state-test
  (let [s (store/fold sample-events)]
    (testing "each event type lands in its own collection"
      (is (= 1 (count (:events s))))
      (is (= 1 (count (:committees s))))
      (is (= 1 (count (:forms s))))
      (is (= 1 (count (:people s))))
      (is (= 1 (count (:memberships s)))))

    (testing "events are keyed by slug"
      (is (= "EAIS" (get-in s [:events "eais" :name]))))

    (testing "ISO strings become domain time values on the way in"
      (is (= (LocalDate/of 2026 10 14) (get-in s [:events "eais" :starts-on])))
      (is (= (LocalDate/of 2026 10 15) (get-in s [:events "eais" :ends-on])))
      (is (instance? Instant (get-in s [:events "eais" :cfp-opens-at])))
      (is (nil? (get-in s [:events "eais" :cfp-closes-at]))))

    (testing "nested JSON data survives untouched"
      (is (= {:all true} (get-in s [:committees "c1" :scope])))
      (is (= "open" (get-in s [:events "eais" :settings :review-visibility]))))

    (testing "the whole log is kept, in order"
      (is (= 5 (count (:log s))))
      (is (= ["event.created" "committee.created" "form.installed"
              "person.created" "member.added"]
             (mapv :type (:log s)))))))

(deftest fold-update-test
  (testing "event.updated merges only the keys it names"
    (let [s (store/fold (conj sample-events
                              {:at "2026-08-03T00:00:00Z" :type "event.updated" :actor "gene"
                               :payload {:id "e1" :slug "eais"
                                         :changes {:website-url "https://x.com"}}}))]
      (is (= "https://x.com" (get-in s [:events "eais" :website-url])))
      (is (= "Charlotte, NC" (get-in s [:events "eais" :location]))
          "an unmentioned field must survive the merge")
      (is (= "EAIS" (get-in s [:events "eais" :name])))))

  (testing "an update for an unknown slug is ignored, not an error"
    (is (= (store/fold sample-events)
           (update (store/fold (conj sample-events
                                     {:type "event.updated"
                                      :payload {:slug "nope" :changes {:name "X"}}}))
                   :log butlast)))))

(deftest fold-remove-test
  (testing "member.removed drops the membership but keeps the person"
    (let [s (store/fold (conj sample-events
                              {:at "2026-08-04T00:00:00Z" :type "member.removed" :actor "gene"
                               :payload {:id "m1" :committee-id "c1" :person-id "p1"}}))]
      (is (empty? (:memberships s)))
      (is (= 1 (count (:people s))) "identity survives leaving a committee")
      (is (= 6 (count (:log s))) "and the log still tells the story"))))

(deftest fold-unknown-type-test
  (testing "an event type this build doesn't know is ignored, never fatal"
    (let [s (store/fold (conj sample-events
                              {:at "2026-09-01T00:00:00Z" :type "from.the.future"
                               :payload {:whatever true}}))]
      (is (= 1 (count (:events s))))
      (is (= 6 (count (:log s))) "but it is still kept in the log"))))

(deftest fold-is-pure-test
  (testing "folding the same events twice gives the same state"
    (is (= (store/fold sample-events) (store/fold sample-events))))
  (testing "folding never touches the global atom"
    (let [before @store/state]
      (store/fold sample-events)
      (is (= before @store/state)))))

(deftest log-index-projection-is-independent-of-timestamps-test
  (let [same-time (mapv #(assoc % :at "2026-08-01T10:00:00Z") sample-events)
        backward-time (-> sample-events
                          (assoc-in [0 :at] "2026-08-02T10:00:00Z")
                          (assoc-in [1 :at] "2026-08-01T10:00:00Z"))]
    (testing "equal timestamps do not pull the next serialized fact into view"
      (let [past (store/state-at-log-index 0 same-time)]
        (is (= #{"eais"} (set (keys (:events past)))))
        (is (empty? (:committees past)))))
    (testing "a timestamp running backward does not change prefix membership"
      (let [past (store/state-at-log-index 0 backward-time)]
        (is (= #{"eais"} (set (keys (:events past)))))
        (is (empty? (:committees past)))))))

;; --- Round trip through the file --------------------------------------------

(deftest append-and-reload-round-trip-test
  (testing "what we append is what we read back"
    (doseq [e sample-events] (store/append! e))
    (let [live @store/state
          reloaded (store/fold (store/read-events))]
      (is (= 5 (count (store/read-events))))
      (is (= (:events live) (:events reloaded)))
      (is (= (:people live) (:people reloaded)))
      (is (= (:memberships live) (:memberships reloaded)))
      (is (= (mapv :type (:log live)) (mapv :type (:log reloaded))))))

  (testing "load! reproduces the live state exactly"
    (let [live @store/state]
      (store/load!)
      (is (= live @store/state)))))

(deftest append-defaults-test
  (testing "append! stamps :at and :actor when the caller omits them"
    (let [e (store/append! {:type "person.created"
                            :payload {:id "p9" :email "x@y.co" :name "X"}})]
      (is (string? (:at e)))
      (is (= "system" (:actor e)))
      (is (some? (store/person-by-email "x@y.co"))))))

(deftest missing-file-is-empty-test
  (testing "a store that has never been written reads as empty, not an error"
    (is (= [] (store/read-events (temp-store-path))))))

(deftest corrupt-line-is-skipped-test
  (testing "one unparseable line costs that line, not the whole log"
    (doseq [e (take 2 sample-events)] (store/append! e))
    (spit @store/store-path "{ this is not json\n" :append true)
    (store/append! (nth sample-events 3))
    (let [events (store/read-events)]
      (is (= 3 (count events)))
      (is (= ["event.created" "committee.created" "person.created"]
             (mapv :type events))))))

;; --- Sinks ------------------------------------------------------------------

(deftest sink-firing-test
  (let [captured (atom [])]
    (reset! store/runtime-sinks [{:type :test :captured captured}])
    (store/append! {:type "person.created" :payload {:id "p1" :email "a@b.co" :name "A"}})
    (testing "a registered sink receives the event"
      ;; sinks fire on a future — give it a moment
      (Thread/sleep 250)
      (is (= 1 (count @captured)))
      (is (= "person.created" (:type (first @captured)))))))

(deftest sink-filtering-test
  (let [wanted (atom []) everything (atom [])]
    (reset! store/runtime-sinks
            [{:type :test :captured wanted :events ["submission.created"]}
             {:type :test :captured everything}])
    (store/append! {:type "person.created" :payload {:id "p1" :email "a@b.co" :name "A"}})
    (store/append! {:type "submission.created" :payload {:id "s1"}})
    (store/await-sinks!)
    (testing "a sink with an :events list hears only those types"
      (is (= ["submission.created"] (mapv :type @wanted))))
    (testing "a sink without one hears every fact in append order"
      (is (= ["person.created" "submission.created"] (mapv :type @everything))))))

(deftest sink-failure-never-breaks-the-write-test
  (let [captured (atom [])]
    (reset! store/runtime-sinks
            [{:type :exploding}                       ; no method -> logged, ignored
             {:type :test :captured captured}])
    (testing "a broken sink does not stop the append or the other sinks"
      (is (some? (store/append! {:type "person.created"
                                 :payload {:id "p1" :email "a@b.co" :name "A"}})))
      (Thread/sleep 250)
      (is (= 1 (count (store/read-events))))
      (is (= 1 (count @captured))))))

(deftest slack-text-test
  (testing "the Slack sentence names the talk, the speaker and the org"
    (is (= "New submission: Scaling AI at BigCo from Ann Perry (BigCo)"
           (store/slack-text
             {:type "submission.created"
              :payload {:answers {:talk-title "Scaling AI at BigCo"}
                        :speakers [{:name "Ann Perry" :org "BigCo"}]}}))))
  (testing "it stays honest when fields are missing"
    (is (= "New submission: (untitled) from unknown speaker"
           (store/slack-text {:type "submission.created" :payload {}})))))
