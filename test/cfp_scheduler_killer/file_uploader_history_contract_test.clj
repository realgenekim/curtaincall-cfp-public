(ns cfp-scheduler-killer.file-uploader-history-contract-test
  (:require
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [cfp-scheduler-killer.views.files :as view-files]
   [clojure.string :as str]
   [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :each with-temp-store)

(defn- version-row [html filename]
  (let [matches (->> (re-seq #"(?s)<tr>.*?</tr>" html)
                     (filter #(and (str/includes? % filename)
                                   (str/includes? % "/download?version-id=")))
                     vec)]
    (is (= 1 (count matches))
        (str "expected one immutable version row for " filename))
    (first matches)))

(deftest each-file-version-renders-its-own-uploader-test
  (let [event {:id "event-1" :slug "files-summit" :name "Files Summit" :tz "UTC"}
        html (str
               (view-files/files-page
                 event
                 {:person nil :submissions [] :message nil
                  :files [{:id "slides-file" :kind "Presentation"
                           :session-title "Algebraic Programs"
                           :versions [{:id "v1" :number 1 :filename "deck-v1.pptx"
                                       :content-type "application/octet-stream" :size 11
                                       :uploaded-at "2026-08-14T00:00:00Z"
                                       :uploaded-by "ada@example.com"}
                                      {:id "v2" :number 2 :filename "deck-v2.pptx"
                                       :content-type "application/octet-stream" :size 12
                                       :uploaded-at "2026-08-14T01:00:00Z"
                                       :uploaded-by "organizer@example.com"}]}]}))
        first-row (version-row html "deck-v1.pptx")
        second-row (version-row html "deck-v2.pptx")]
    (is (str/includes? html "<th>Uploaded by</th>"))
    (is (str/includes? first-row "<td>ada@example.com</td>"))
    (is (not (str/includes? first-row "organizer@example.com")))
    (is (str/includes? second-row "<td>organizer@example.com</td>"))
    (is (not (str/includes? second-row "ada@example.com")))))
