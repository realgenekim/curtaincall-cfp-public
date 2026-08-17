(ns cfp-scheduler-killer.status-workflow-test
  "Cross-surface contract for the eight submission states and the independent
   notification fact."
  (:require
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.exports :as exports]
   [cfp-scheduler-killer.inform :as inform]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [cfp-scheduler-killer.views.review :as review-view]
   [cfp-scheduler-killer.views.submission-row :as submission-row]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [hiccup2.core :as h])
  (:import
   (java.time LocalDateTime)))

(use-fixtures :each with-temp-store)

(def statuses
  ["Accepted" "Waitlisted" "Accept Queue" "Pending"
   "Decline Queue" "Declined" "Withdrawn" "Draft"])

(defn- make-event! []
  (events/create-event!
    {:name "Status Contract Summit"
     :slug "status-contract"
     :tz "UTC"
     :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
     :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
    "kaocha"))

(defn- submit! [event]
  (let [fields (:fields (events/form-for-event (:id event)))
        params {:answer-talk-title "Status contract talk"
                :answer-abstract "The same proposal walks every decision state."
                :answer-session-format "Experience Report"
                :answer-org-size "1,000–10,000"
                :answer-industry "Technology"
                :answer-ai-transformation-history "2024"
                :answer-measurable-outcomes "Measured."
                :answer-notes-to-committee "Private."
                :speaker-name "Sam Speaker"
                :speaker-email "sam@example.com"
                :speaker-title "VP"
                :speaker-org "ExampleCo"
                :speaker-bio "Bio."}]
    (submissions/create-submission!
      event
      (submissions/parse-answers fields params)
      (submissions/parse-speaker params)
      "form"
      "kaocha")))

(defn- board-row [submission-number status]
  {:id (str "submission-" submission-number)
   :submission-number submission-number
   :status status
   :speakers [{:person-id (str "speaker-" submission-number)
               :name "Sam Speaker"
               :org "ExampleCo"}]
   :answers {:talk-title (str status " talk")
             :session-format "Experience Report"}
   :ratings []
   :comments []
   :n 0
   :mean nil
   :split? false})

(deftest eight-valued-status-and-notified-contract-test
  (let [event (make-event!)
        submission (submit! event)]
    (testing "new events install the complete vocabulary in its canonical order"
      (is (= statuses (get-in event [:settings :statuses]))))

    (testing "every configured value is a real transition and notification stays separate"
      (doseq [status statuses]
        (let [updated (reviews/set-status! (:id submission) status "chair@example.com")
              api-row (exports/api-session event updated)]
          (is (= status (:status updated)) status)
          (is (nil? (:notified-at updated)) status)
          (is (= status (get api-row "status")) status)
          (is (false? (get api-row "notified")) status)))
      (is (= statuses
             (->> (store/read-events)
                  (filter #(= "submission.status-changed" (:type %)))
                  (mapv #(get-in % [:payload :to]))))))

    (testing "the board hides Draft and only the three active queues accept ratings"
      (let [rows (mapv board-row (range 1 9) statuses)
            visible-rows (filterv reviews/board-visible? rows)
            counts (reviews/status-counts visible-rows)
            board-html (str
                         (h/html
                           (review-view/board-region
                             event
                             {:rows []
                              :coverage {}
                              :sort-key reviews/default-sort
                              :q ""
                              :status ""
                              :status-counts counts
                              :person nil
                              :sort-presets []
                              :total (count visible-rows)
                              :track-counts {}})))]
        (is (= #{"Pending" "Accept Queue" "Decline Queue"}
               reviews/rateable-statuses))
        (is (= (set (remove #{"Draft"} statuses)) (set (keys counts))))
        (is (not (str/includes? board-html "Draft")))
        (doseq [[status row] (map vector (remove #{"Draft"} statuses) visible-rows)]
          (is (= [status]
                 (mapv :status (reviews/filter-board visible-rows {:status status})))
              status)
          (is (str/includes? board-html status) status)
          (let [row-html (str (h/html (submission-row/board-row event row nil)))]
            (is (str/includes? row-html
                               (str "<span class=\"ui mini label\">" status "</span>"))
                status)
            (if (contains? reviews/rateable-statuses status)
              (do
                (is (str/includes? row-html "Read &amp; rate →") status)
                (is (str/includes? row-html "Quick rate ▾") status))
              (do
                (is (str/includes? row-html "Read →") status)
                (is (not (str/includes? row-html "Quick rate ▾")) status)))))
        (is (not (reviews/board-visible? (last rows))))
        (is (empty? (filter reviews/board-visible? [(last rows)])))))

    (testing "export documentation names every state and the eight-valued model"
      (let [status-help (->> exports/api-endpoints
                             (filter #(= "/api/v1/events/{slug}/sessions" (:path %)))
                             first
                             :params
                             first
                             second)]
        (doseq [status statuses]
          (is (str/includes? status-help status) status))
        (is (str/includes? (:doc (meta #'exports/api-session)) "eight-valued"))))

    (testing "informing is a second act: it changes the flag, not the status"
      (reviews/set-status! (:id submission) "Accepted" "chair@example.com")
      (inform/inform! event (store/submission-by-id (:id submission)) "chair@example.com")
      (let [informed (store/submission-by-id (:id submission))
            api-row (exports/api-session event informed)]
        (is (= "Accepted" (:status informed)))
        (is (some? (:notified-at informed)))
        (is (= "Accepted" (get api-row "status")))
        (is (true? (get api-row "notified")))
        (is (some? (get api-row "notifiedAt")))))))
