(ns cfp-scheduler-killer.submission-content-test
  (:require
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.inform :as inform]
   [cfp-scheduler-killer.public-catalog :as public-catalog]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submission-content :as content]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [cfp-scheduler-killer.views.submission-content :as content-view]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [hiccup2.core :as h])
  (:import
   (java.time LocalDateTime)))

(use-fixtures :each with-temp-store)

(defn- setup!
  []
  (let [event (events/create-eais-event!
                {:name "Content History Summit" :slug "content-history"
                 :tz "America/Los_Angeles"
                 :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
                 :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
                "kaocha")
        fields (:fields (events/form-for-event (:id event)))
        params {:answer-talk-title "Original title"
                :answer-abstract "Original abstract"
                :answer-session-format "Experience Report"
                :answer-track "Leadership & Organizational Change"
                :answer-org-size ">10,000"
                :answer-industry "Insurance"
                :answer-ai-transformation-history "History"
                :answer-measurable-outcomes "Outcomes"
                :answer-notes-to-committee "Private"
                :speaker-name "Speaker One"
                :speaker-email "speaker@example.com"
                :speaker-title "VP"
                :speaker-org "ExampleCo"
                :speaker-bio "Biography"}
        submission (submissions/create-submission!
                     event
                     (submissions/parse-answers fields params)
                     (submissions/parse-speaker params)
                     "form"
                     "kaocha")]
    {:event event :submission submission}))

(deftest update-history-and-restore-are-one-algebra
  (let [{:keys [submission]} (setup!)
        id (:id submission)]
    (testing "partial edits preserve unsubmitted fields and emit one fact"
      (is (:ok (content/update-answers!
                 id {:answer-talk-title "Second title"} "organizer@example.com")))
      (is (:ok (content/update-answers!
                 id {:answer-abstract "Second abstract"} "organizer@example.com")))
      (is (= "Second title" (get-in (store/submission-by-id id) [:answers :talk-title])))
      (is (= "Second abstract" (get-in (store/submission-by-id id) [:answers :abstract]))))

    (let [[first-revision second-revision] (content/revision-history id)]
      (testing "history reconstructs complete versions from partial facts"
        (is (= "Original title" (get-in first-revision [:before :talk-title])))
        (is (= "Original abstract" (get-in first-revision [:before :abstract])))
        (is (= "Second title" (get-in first-revision [:after :talk-title])))
        (is (= "Second abstract" (get-in second-revision [:after :abstract]))))

      (testing "restore is another forward fact and restores the complete version"
        (is (:ok (content/restore! id (:log-index first-revision)
                                   "organizer@example.com")))
        (let [restored (store/submission-by-id id)
              facts (filter #(= "submission.answers-updated" (:type %))
                            (store/read-events))]
          (is (= "Original title" (get-in restored [:answers :talk-title])))
          (is (= "Original abstract" (get-in restored [:answers :abstract])))
          (is (= 3 (count facts)))
          (is (= (:log-index first-revision)
                 (get-in (last facts) [:payload :restored-from-log-index]))))))))

(deftest invalid-content-never-appends
  (let [{:keys [submission]} (setup!)
        before (count (store/read-events))
        result (content/update-answers!
                 (:id submission) {:answer-talk-title ""} "organizer@example.com")]
    (is (false? (:ok result)))
    (is (seq (:errors result)))
    (is (= before (count (store/read-events))))))

(deftest approved-content-is-the-only-explicit-status-visible-publicly
  (let [{:keys [event submission]} (setup!)
        submission-id (:id submission)
        actor "organizer@example.com"]
    (reviews/set-status! submission-id "Accepted" actor)
    (inform/inform! event (store/submission-by-id submission-id) actor)
    (store/await-sinks!)

    (testing "an explicit editorial status controls public program visibility"
      (submissions/set-content-status! submission-id "In review" actor)
      (is (empty? (public-catalog/sessions event)))

      (submissions/set-content-status! submission-id "Approved" actor)
      (is (= [submission-id]
             (mapv :id (public-catalog/sessions event)))))

    (testing "editing approved copy withdraws it until the revision is approved"
      (is (:ok (content/update-answers!
                 submission-id {:answer-talk-title "Revised, awaiting approval"} actor)))
      (is (= "In review"
             (submissions/content-status (store/submission-by-id submission-id))))
      (is (empty? (public-catalog/sessions event)))

      (submissions/set-content-status! submission-id "Approved" actor)
      (is (= ["Revised, awaiting approval"]
             (mapv :title (public-catalog/sessions event)))))

    (testing "restoring an older version also requires fresh approval"
      (let [revision (first (filter #(= :answers (:kind %))
                                    (content/revision-history submission-id)))]
        (is (:ok (content/restore! submission-id (:log-index revision) actor)))
        (is (= "In review"
               (submissions/content-status (store/submission-by-id submission-id))))
        (is (empty? (public-catalog/sessions event)))))))

(deftest title-revision-withdraws-public-count-not-roster-total
  (let [{:keys [event submission]} (setup!)
        submission-id (:id submission)
        actor "organizer@example.com"]
    (reviews/set-status! submission-id "Accepted" actor)
    (inform/inform! event (store/submission-by-id submission-id) actor)
    (store/await-sinks!)
    (submissions/set-content-status! submission-id "Approved" actor)
    (is (= {:lit 1 :total 1}
           (public-catalog/announce-stats event)))

    (is (:ok (content/update-answers!
               submission-id {:answer-talk-title "Title cleanup"} actor)))
    (is (= "In review"
           (submissions/content-status (store/submission-by-id submission-id))))
    (is (= {:lit 0 :total 1}
           (public-catalog/announce-stats event)))))

(deftest editorial-status-is-append-only-content-history
  (let [{:keys [event submission]} (setup!)
        submission-id (:id submission)
        actor "organizer@example.com"]
    (submissions/set-content-status! submission-id "In review" actor)
    (submissions/set-content-status! submission-id "Approved" actor)
    (let [history (content/revision-history submission-id)
          approval (last history)]
      (testing "editorial visibility transitions are part of content history"
        (is (= [:content-status :content-status] (mapv :kind history)))
        (is (= ["In review" "Approved"]
               [(:before-status approval) (:after-status approval)])))

      (testing "the organizer sees a truthful status diff and restore action"
        (let [html (str (h/html (content-view/history-section
                                  event
                                  (store/submission-by-id submission-id)
                                  history)))]
          (is (str/includes? html "Content status changed"))
          (is (str/includes? html "Before: In review"))
          (is (str/includes? html "After: Approved"))
          (is (str/includes? html "Restore previous status"))))

      (testing "restoring status appends a forward transition"
        (let [before (count (store/read-events))
              result (content/restore! submission-id (:log-index approval) actor)]
          (is (:ok result))
          (is (= "In review"
                 (submissions/content-status
                   (store/submission-by-id submission-id))))
          (is (= (inc before) (count (store/read-events))))
          (is (= "submission.content-status-changed"
                 (:type (last (store/read-events))))))))))
