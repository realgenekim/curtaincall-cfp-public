(ns cfp-scheduler-killer.domain.files-test
  (:require
   [cfp-scheduler-killer.domain.files :as files]
   [clojure.test :refer [deftest is testing]]))

(def state
  {:events {"aie" {:id "event-1"}}
   :submissions {"sub-1" {:id "sub-1" :event-id "event-1"}}
   :tasks {["sub-1" "slides"] {:submission-id "sub-1" :key "slides"
                               :task-type "file" :file-kind "Presentation"}}
   :people {"person-1" {:id "person-1"}}
   :files {}})

(def upload
  {:file-id "file-1" :version-id "version-1" :event-id "event-1"
   :submission-id "sub-1" :task-key "slides" :kind "Presentation"
   :filename "keynote-slides.pptx"
   :content-type "application/vnd.openxmlformats-officedocument.presentationml.presentation"
   :size 42 :sha256 "abc" :storage-key "object-1"
   :actor "speaker@example.com" :at "2026-08-10T12:00:00Z"})

(deftest versions-are-immutable-and-identical-retries-are-no-ops
  (let [v1 (files/decide-add-version state upload)
        folded (assoc-in state [:files "file-1"]
                         {:id "file-1" :event-id "event-1"
                          :submission-id "sub-1" :task-key "slides"
                          :person-id nil :kind "Presentation"
                          :versions [(get-in v1 [:facts 0 :payload :version])]
                          :comments []})]
    (is (= "file.version-added" (get-in v1 [:facts 0 :type])))
    (is (= 1 (get-in v1 [:facts 0 :payload :version :number])))
    (is (empty? (:facts (files/decide-add-version
                          folded (assoc upload :version-id "retry")))))
    (is (= 2 (get-in (files/decide-add-version
                       folded (assoc upload :version-id "version-2"
                                     :sha256 "changed" :storage-key "object-2"))
                     [:facts 0 :payload :version :number])))))

(deftest immutable-version-identities-never-alias
  (let [v1 (files/decide-add-version state upload)
        version (get-in v1 [:facts 0 :payload :version])
        with-version (assoc-in state [:files "file-1"]
                               {:id "file-1" :event-id "event-1"
                                :submission-id "sub-1" :task-key "slides"
                                :person-id nil :kind "Presentation"
                                :versions [version] :comments []})]
    (testing "changed bytes cannot reuse a version id"
      (let [result (files/decide-add-version
                     with-version
                     (assoc upload :sha256 "changed" :storage-key "object-2"))]
        (is (= :version-id-conflict (get-in result [:rejected :type])))
        (is (empty? (:facts result)))))

    (testing "changed bytes cannot overwrite an immutable storage location"
      (let [result (files/decide-add-version
                     with-version
                     (assoc upload :version-id "version-2" :sha256 "changed"))]
        (is (= :storage-key-conflict (get-in result [:rejected :type])))
        (is (empty? (:facts result)))))))

(deftest a-version-cannot-change-targets
  (let [existing {:id "file-1" :event-id "event-1" :submission-id "sub-1"
                  :task-key "slides" :person-id nil :kind "Presentation"
                  :versions [] :comments []}
        result (files/decide-add-version
                 (assoc-in state [:files "file-1"] existing)
                 (assoc upload :task-key nil :person-id "person-1" :kind "Headshot"
                        :filename "headshot.png" :content-type "image/png"))]
    (is (= :target-mismatch (get-in result [:rejected :type])))))

(deftest uploads-require-an-upload-task-and-its-declared-kind
  (testing "ordinary check and URL tasks cannot be completed with file bytes"
    (doseq [task [{:task-type "check"}
                  {:task-type "url"}]]
      (let [result (files/decide-add-version
                     (assoc-in state [:tasks ["sub-1" "slides"]]
                               (merge {:submission-id "sub-1" :key "slides"} task))
                     upload)]
        (is (= :invalid-file-task (get-in result [:rejected :type]))))))
  (testing "legacy URL tasks with a file kind remain valid upload targets"
    (let [legacy (assoc-in state [:tasks ["sub-1" "slides"]]
                           {:submission-id "sub-1" :key "slides"
                            :task-type "url" :file-kind "Presentation"})]
      (is (= ["file.version-added"]
             (mapv :type (:facts (files/decide-add-version legacy upload)))))))
  (testing "a task cannot be completed using a different deliverable kind"
    (is (= :file-kind-mismatch
           (get-in (files/decide-add-version state (assoc upload :kind "Poster"))
                   [:rejected :type])))))

(deftest upload-constraints-are-pure-data
  (testing "empty, oversized, and unsupported uploads are rejected"
    (let [errors (files/upload-errors {:filename "" :kind "Mystery"
                                       :size (inc files/max-upload-bytes)
                                       :content-type "text/html"})]
      (is (= #{:filename :kind :size :content-type} (set (keys errors)))))))

(deftest comments-are-idempotent-and-bounded
  (let [with-file (assoc-in state [:files "file-1"]
                            {:id "file-1" :event-id "event-1" :comments []})
        command {:file-id "file-1" :comment-id "comment-1" :body "Please revise slide 12."
                 :actor "organizer@example.com" :at "2026-08-10T12:00:00Z"}
        with-comment (assoc-in with-file [:files "file-1" :comments]
                               [{:id "comment-1"
                                 :body "Please revise slide 12."
                                 :actor "organizer@example.com"
                                 :at "2026-08-10T12:00:00Z"}])]
    (is (= "file.comment-added"
           (get-in (files/decide-add-comment with-file command) [:facts 0 :type])))
    (is (= :comment-required
           (get-in (files/decide-add-comment with-file (assoc command :body " "))
                   [:rejected :type])))
    (testing "an exact retry is idempotent"
      (is (empty? (:facts (files/decide-add-comment with-comment command)))))
    (testing "a comment identity cannot silently discard different content"
      (is (= :comment-id-conflict
             (get-in (files/decide-add-comment
                       with-comment
                       (assoc command :body "Please revise slide 13."))
                     [:rejected :type]))))
    (testing "a comment identity cannot be reused by another actor"
      (is (= :comment-id-conflict
             (get-in (files/decide-add-comment
                       with-comment
                       (assoc command :actor "reviewer@example.com"))
                     [:rejected :type]))))))
