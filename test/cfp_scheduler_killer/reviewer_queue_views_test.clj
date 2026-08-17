(ns cfp-scheduler-killer.reviewer-queue-views-test
  (:require
   [cfp-scheduler-killer.views.reviewer-queue :as reviewer-queue]
   [clojure.test :refer [deftest is testing]])
  (:import
   (org.jsoup Jsoup)))

(def event
  {:id "event-1"
   :slug "summit"
   :name "Enterprise AI Summit"})

(def assigned-rows
  [{:id "submission-1"
    :status "Pending"
    :answers {:talk-title "Fast feedback loops"}
    :speakers [{:name "Ann Speaker" :org "IT Revolution"}]}
   {:id "submission-2"
    :status "Pending"
    :answers {:talk-title "Pure domain decisions"}
    :speakers [{:name "Pat Speaker" :org "Example Co"}]}])

(defn- queue-document [rows progress]
  (Jsoup/parse
   (str (reviewer-queue/queue-page
         event
         {:person {:id "reviewer-1" :name "Sam Reviewer"}
          :rows rows
          :progress progress}))))

(defn- text-of [doc selector]
  (some-> (.selectFirst doc selector) .text))

(deftest assigned-queue-contract-test
  (let [doc (queue-document assigned-rows
                            {:assigned 3 :completed 1 :remaining 2})
        items (.select doc ".reviewer-queue [data-submission-id]")]
    (testing "the page explicitly names the assignment-limited queue"
      (is (= "Assigned review queue" (text-of doc "h1")))
      (is (re-find #"Only submissions explicitly assigned to you appear here"
                   (.text doc)))
      (is (= ["submission-1" "submission-2"]
             (mapv #(.attr % "data-submission-id") items))))
    (testing "assigned, completed, and remaining are separately exact"
      (is (= "Assigned 3" (text-of doc "[data-progress=assigned]")))
      (is (= "Completed 1" (text-of doc "[data-progress=completed]")))
      (is (= "Remaining 2" (text-of doc "[data-progress=remaining]"))))
    (testing "the reviewer can clearly return to the shared board"
      (let [link (.selectFirst doc ".review-board-return")]
        (is (= "Open shared Review Board" (.text link)))))))

(deftest empty-assigned-queue-contract-test
  (let [doc (queue-document [] {:assigned 0 :completed 0 :remaining 0})]
    (is (= "Assigned 0" (text-of doc "[data-progress=assigned]")))
    (is (= "Completed 0" (text-of doc "[data-progress=completed]")))
    (is (= "Remaining 0" (text-of doc "[data-progress=remaining]")))
    (is (re-find #"No submissions are currently assigned to you"
                 (.text doc)))
    (is (= "Open shared Review Board"
           (text-of doc ".review-board-return")))))
