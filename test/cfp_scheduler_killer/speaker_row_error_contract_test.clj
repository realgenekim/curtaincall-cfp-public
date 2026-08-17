(ns cfp-scheduler-killer.speaker-row-error-contract-test
  (:require
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [cfp-scheduler-killer.views.speakers :as view-speakers]
   [clojure.string :as str]
   [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :each with-temp-store)

(defn- speaker-row [html person-id]
  (let [matches (re-seq
                  (re-pattern
                    (str "(?s)<tbody[^>]*id=\"speaker-" person-id "\"[^>]*>.*?</tbody>"))
                  html)]
    (is (= 1 (count matches))
        (str "expected one rendered row for speaker " person-id))
    (first matches)))

(deftest roster-status-is-read-only-and-editing-leaves-the-ledger-test
  (let [html (str
               (view-speakers/speakers-page
                 {:id "event-1" :slug "summit" :name "Summit"}
                 {:person nil :query "Priya & Team" :status "Confirmed"
                  :custom-fields []
                  :speakers [{:person-id "p-1" :name "Priya & Team"
                              :email "priya@example.com" :status "Confirmed"
                              :talks [] :profile-complete? true
                              :lifecycle {:status "Confirmed"
                                          :pending-tasks []
                                          :history [{:type "speaker.status-changed"
                                                     :status "Confirmed"}]}}
                             {:person-id "p-2" :name "Dana Other"
                              :email "dana@example.com" :status "Confirmed"
                              :talks [] :profile-complete? true}]}))
        priya-row (speaker-row html "p-1")
        dana-row (speaker-row html "p-2")]
    (is (str/includes? priya-row ">Confirmed</span>"))
    (is (str/includes? priya-row "Updates:"))
    (is (str/includes? priya-row "Speaker status changed to Confirmed"))
    (is (str/includes? priya-row
                       "href=\"/events/summit/speakers/p-1\">Edit Speaker Details</a>"))
    (is (not (str/includes? priya-row "name=\"status\"")))
    (is (not (str/includes? priya-row ">Update</button>")))
    (is (not (str/includes? priya-row "name=\"profile-edit\"")))
    (is (not (str/includes? dana-row "Status changed to Confirmed")))))
