(ns cfp-scheduler-killer.file-owner-labels-contract-test
  (:require
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [cfp-scheduler-killer.views.files :as view-files]
   [clojure.string :as str]
   [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :each with-temp-store)

(defn- file-card [html file-id]
  (let [marker (str "id=\"file-" file-id "\"")
        start (str/index-of html marker)
        next-card (when start
                    (str/index-of html "id=\"file-" (Math/addExact (long start) 1)))]
    (is (some? start) (str "expected a file card for " file-id))
    (subs html start (or next-card (count html)))))

(deftest file-library-binds-each-owner-label-to-the-correct-card-test
  (let [event {:id "event-1" :slug "files-summit" :name "Files Summit"}
        version (fn [id filename]
                  {:id id :number 1 :filename filename
                   :content-type "application/octet-stream"
                   :size 12 :uploaded-at "2026-08-14T00:00:00Z"})
        html (str
               (view-files/files-page
                 event
                 {:person nil :submissions [] :message nil
                  :files [{:id "headshot-file" :kind "Headshot"
                           :owner-name "Ada Speaker"
                           :versions [(version "headshot-v1" "headshot.png")]}
                          {:id "slides-file" :kind "Presentation"
                           :submission-id "submission-secret-id"
                           :session-title "Algebraic Programs"
                           :versions [(version "slides-v1" "keynote-slides.pptx")]}]}))
        headshot-card (file-card html "headshot-file")
        slides-card (file-card html "slides-file")]
    (is (str/includes? headshot-card "Headshot · v1 · Ada Speaker"))
    (is (not (str/includes? headshot-card "Algebraic Programs")))
    (is (str/includes? slides-card "Presentation · v1 · Algebraic Programs"))
    (is (not (str/includes? slides-card "Ada Speaker")))))
