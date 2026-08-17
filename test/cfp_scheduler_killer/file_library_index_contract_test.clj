(ns cfp-scheduler-killer.file-library-index-contract-test
  (:require
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [cfp-scheduler-killer.views.files :as view-files]
   [clojure.string :as str]
   [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :each with-temp-store)

(deftest central-library-index-keeps-aggregate-file-metadata-visible-test
  (let [event {:id "event-1" :slug "files-summit" :name "Files Summit" :tz "UTC"}
        html (str
               (view-files/files-page
                 event
                 {:person nil :submissions [] :requests [] :message nil
                  :filters {:q nil :status "all" :kind "all"
                            :sort "due-asc" :file-sort "uploaded-newest"}
                  :files [{:id "slides-file" :kind "Presentation"
                           :session-title "Taming 40-Minute CI"
                           :speaker-names "Priya Raman"
                           :versions [{:id "v1" :number 1 :filename "slides.pdf"
                                       :content-type "application/pdf" :size 11
                                       :uploaded-at "2026-08-14T00:00:00Z"
                                       :uploaded-by "Priya Raman"}
                                      {:id "v2" :number 2 :filename "slides.pdf"
                                       :content-type "application/pdf" :size 12
                                       :uploaded-at "2026-08-15T01:00:00Z"
                                       :uploaded-by "Priya Raman"}]}]}))]
    (is (str/includes? html "Central files library"))
    (is (str/includes? html "1 uploaded file · 2 immutable versions across every session and speaker profile"))
    (is (str/includes? html "<th>Session</th><th>Speaker</th><th>Latest upload</th><th>Versions</th>"))
    (is (re-find
          #"(?s)<tr><td><strong>slides\.pdf</strong>.*?</td><td>Taming 40-Minute CI</td><td>Priya Raman</td><td>.*?Uploaded by Priya Raman.*?</td><td><strong>2 versions</strong><div class=\"field-hint\">v2 current</div>"
          html))
    (is (str/includes? html
                       "download=\"download\" href=\"/events/files-summit/files/slides-file/download\">Download current"))
    (is (str/includes? html "href=\"#file-slides-file\">Open file details"))
    (is (str/includes? html "id=\"file-slides-file\""))))
