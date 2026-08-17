(ns cfp-scheduler-killer.file-bulk-export-contract-test
  (:require
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [cfp-scheduler-killer.views.files :as view-files]
   [clojure.string :as str]
   [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :each with-temp-store)

(defn- upload [id filename session speaker]
  {:id id :kind "Presentation" :session-title session :speaker-names speaker
   :versions [{:id (str id "-v1") :number 1 :filename filename
               :content-type "application/pdf" :size 12
               :uploaded-at "2026-08-15T01:00:00Z" :uploaded-by speaker}]})

(deftest multi-file-export-keeps-selection-grouping-and-ready-state-together-test
  (let [event {:id "event-1" :slug "files-summit" :name "Files Summit" :tz "UTC"}
        slides (upload "slides-file" "slides.pdf" "Taming 40-Minute CI" "Priya Raman")
        handout (upload "handout-file" "handout.pdf" "Agents in Production" "Marcus Okafor")
        html (str
               (view-files/files-page
                 event
                 {:person nil :submissions [] :requests [] :message nil
                  :filters {:q nil :status "all" :kind "all"
                            :sort "due-asc" :file-sort "uploaded-newest"}
                  :files [slides handout]
                  :prepared-zip {:grouping "by-session"
                                 :files [{:id "slides-file" :filename "slides.pdf"}
                                         {:id "handout-file" :filename "handout.pdf"}]}}))]
    (is (str/includes? html "Files to include"))
    (is (= 2 (count (re-seq #"name=\"file-ids\"" html))))
    (is (= 2 (count (re-seq #"checked=\"checked\"" html))))
    (is (str/includes? html "Group by session / speaker"))
    (is (str/includes? html "ZIP ready to download"))
    (is (str/includes? html "2 latest files · grouped by session / speaker"))
    (is (str/includes? html "Download prepared ZIP"))))
