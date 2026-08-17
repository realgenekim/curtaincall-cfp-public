(ns cfp-scheduler-killer.files-test
  (:require
   [cfp-scheduler-killer.domain.files :as decisions]
   [cfp-scheduler-killer.files :as files]
   [cfp-scheduler-killer.folds :as folds]
   [cfp-scheduler-killer.io.blob :as blob]
   [cfp-scheduler-killer.store :as store]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]))

(defn- zip-entry-names [bytes]
  (with-open [in (java.util.zip.ZipInputStream.
                   (java.io.ByteArrayInputStream. bytes))]
    (loop [names []]
      (if-let [entry (.getNextEntry in)]
        (recur (conj names (.getName entry)))
        names))))

(defn- zip-entry-bytes [bytes]
  (with-open [in (java.util.zip.ZipInputStream.
                   (java.io.ByteArrayInputStream. bytes))]
    (loop [entries {}]
      (if-let [entry (.getNextEntry in)]
        (recur (assoc entries (.getName entry) (.readAllBytes in)))
        entries))))

(def base-state
  (assoc folds/empty-state
         :events {"summit" {:id "event-1" :slug "summit"}}
         :people {"speaker-1" {:id "speaker-1" :email "ada@example.com"}}
         :submissions {"sub-1" {:id "sub-1" :event-id "event-1"
                                :speakers [{:person-id "speaker-1"}]}}
         :tasks {["sub-1" "slides"] {:submission-id "sub-1" :key "slides"
                                     :task-type "file" :file-kind "Presentation"}}))

(defn- apply-facts [state facts]
  (reduce store/fold-one state facts))

(deftest event-zip-is-a-complete-immutable-version-archive
  (let [v1 {:id "version-1" :number 1 :filename "slides-draft.pdf"
            :storage-key "memory://slides-v1"}
        v2 {:id "version-2" :number 2 :filename "slides-final.pdf"
            :storage-key "memory://slides-v2"}
        state (assoc base-state :files
                     {"file-1" {:id "file-1" :event-id "event-1"
                                :submission-id "sub-1" :kind "Presentation"
                                :versions [v1 v2]}})
        objects {"memory://slides-v1" (.getBytes "draft bytes" "UTF-8")
                 "memory://slides-v2" (.getBytes "final bytes" "UTF-8")}]
    (with-redefs [store/snapshot (constantly state)]
      (binding [blob/*read-bytes-fn* objects]
        (let [entries (zip-entry-bytes (files/event-zip-bytes! "event-1"))
              v1-name "sub-1/Presentation-file-1-v1-slides-draft.pdf"
              v2-name "sub-1/Presentation-file-1-v2-slides-final.pdf"]
          (is (= #{v1-name v2-name} (set (keys entries))))
          (is (= "draft bytes" (String. ^bytes (get entries v1-name) "UTF-8")))
          (is (= "final bytes" (String. ^bytes (get entries v2-name) "UTF-8"))))))))

(defn- bytes-sha256 [bytes]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (.update digest ^bytes bytes)
    (format "%064x" (java.math.BigInteger. 1 (.digest digest)))))

(deftest upload-snapshots-each-source-exactly-once
  (letfn [(upload-source! [source before-put]
            (let [state (atom base-state)
                  stored (atom nil)
                  put-source (atom nil)]
              (with-redefs [store/snapshot #(deref state)
                            store/new-id (let [ids (atom ["file-1" "version-1"])]
                                           #(let [id (first @ids)]
                                              (swap! ids subvec 1)
                                              id))
                            store/now-iso (constantly "2026-08-14T12:00:00Z")
                            store/append-all! #(swap! state apply-facts %)]
                (binding [blob/*put-fn*
                          (fn [upload key]
                            (reset! put-source upload)
                            (before-put)
                            (with-open [in (io/input-stream upload)]
                              (reset! stored (.readAllBytes in)))
                            (str "memory://" key))]
                  (let [file (files/upload!
                               {:source source :filename "slides.pdf"
                                :content-type "application/pdf"
                                :event-id "event-1" :submission-id "sub-1"
                                :task-key "slides" :kind "Presentation"
                                :actor "ada@example.com"})]
                    {:stored @stored
                     :put-source @put-source
                     :version (first (:versions file))})))))]
    (testing "a mutable multipart tempfile cannot change after measurement"
      (let [source (java.io.File/createTempFile "mutable-slides-" ".pdf")]
        (try
          (spit source "original deck")
          (let [{:keys [stored put-source version]}
                (upload-source! source #(spit source "changed after measurement"))]
            (is (= "original deck" (String. ^bytes stored "UTF-8")))
            (is (= (alength ^bytes stored) (:size version)))
            (is (= (bytes-sha256 stored) (:sha256 version)))
            (is (not= source put-source))
            (is (false? (.exists ^java.io.File put-source))
                "the private snapshot is deleted after storage"))
          (finally
            (.delete source)))))

    (testing "a one-shot stream is not consumed before blob storage"
      (let [source (java.io.ByteArrayInputStream.
                     (.getBytes "streamed deck" "UTF-8"))
            {:keys [stored put-source version]}
            (upload-source! source (constantly nil))]
        (is (= "streamed deck" (String. ^bytes stored "UTF-8")))
        (is (= (alength ^bytes stored) (:size version)))
        (is (= (bytes-sha256 stored) (:sha256 version)))
        (is (instance? java.io.File put-source))
        (when (instance? java.io.File put-source)
          (is (false? (.exists ^java.io.File put-source))
              "the private snapshot is deleted after storage"))))))

(deftest failed-blob-storage-deletes-the-private-snapshot
  (let [state (atom base-state)
        put-source (atom nil)
        source (java.io.ByteArrayInputStream. (.getBytes "deck bytes" "UTF-8"))]
    (with-redefs [store/snapshot #(deref state)
                  store/new-id (let [ids (atom ["file-1" "version-1"])]
                                 #(let [id (first @ids)]
                                    (swap! ids subvec 1)
                                    id))]
      (binding [blob/*put-fn*
                (fn [upload _]
                  (reset! put-source upload)
                  (throw (ex-info "blob unavailable" {})))]
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"blob unavailable"
              (files/upload!
                {:source source :filename "slides.pdf"
                 :content-type "application/pdf"
                 :event-id "event-1" :submission-id "sub-1"
                 :task-key "slides" :kind "Presentation"
                 :actor "ada@example.com"})))
        (is (instance? java.io.File @put-source))
        (is (false? (.exists ^java.io.File @put-source)))
        (is (empty? (:files @state)))))))

(deftest upload-version-comment-and-zip-are-one-purely-driven-story
  (let [state (atom base-state)
        ids (atom ["file-1" "version-1" "version-2" "version-3"])
        objects (atom {})
        source (java.io.File/createTempFile "slides-" ".pptx")]
    (try
      (spit source "version one")
      (with-redefs [store/snapshot #(deref state)
                    store/new-id #(let [id (first @ids)] (swap! ids subvec 1) id)
                    store/now-iso (constantly "2026-08-10T12:00:00Z")
                    store/append-all! #(swap! state apply-facts %)]
        (binding [blob/*put-fn*
                  (fn [file key]
                    (let [location (str "memory://" key)]
                      (swap! objects assoc location (java.nio.file.Files/readAllBytes (.toPath file)))
                      location))
                  blob/*read-bytes-fn* #(get @objects %)]
          (let [command {:source source :filename "keynote-slides.pptx"
                         :content-type "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                         :size 1 :event-id "event-1"
                         :submission-id "sub-1" :task-key "slides"
                         :kind "Presentation" :actor "ada@example.com"}
                v1 (files/upload! command)
                event-count-after-v1 (count (:log @state))
                retry (files/upload! command)]
            (testing "first upload completes the obligation in the same append batch"
              (is (= 1 (count (:versions v1))))
              (is (= (.length source) (get-in v1 [:versions 0 :size]))
                  "persisted metadata comes from the source, not a caller hint")
              (is (some? (get-in @state [:tasks ["sub-1" "slides"] :completed-at])))
              (is (= 2 event-count-after-v1)))
            (testing "identical bytes are an algebraic no-op"
              (is (= (:id v1) (:id retry)))
              (is (= event-count-after-v1 (count (:log @state))))
              (is (= 1 (count @objects))))
            (spit source "version two")
            (let [v2 (files/upload! (assoc command :size (.length source)))]
              (is (= [1 2] (mapv :number (:versions v2))))
              (is (= 2 (count @objects)))
              (files/add-comment! "file-1" "comment-1"
                                  "Please use this deck for production." "ann@example.com")
              (is (= "Please use this deck for production."
                     (get-in @state [:files "file-1" :comments 0 :body])))
              (is (= [80 75] (mapv #(bit-and 255 %) (take 2 (files/event-zip-bytes! "event-1"))))
                  "ZIP files start with PK")))))
      (finally
        (.delete source)))))

(deftest zip-paths-stay-unique-for-same-name-files
  (let [version {:number 1 :filename "slides.pdf" :storage-key "memory://slides"}
        state (assoc base-state :files
                     {"file-1" {:id "file-1" :event-id "event-1"
                                :submission-id "sub-1" :kind "Presentation"
                                :versions [version]}
                      "file-2" {:id "file-2" :event-id "event-1"
                                :submission-id "sub-1" :kind "Presentation"
                                :versions [version]}})]
    (with-redefs [store/snapshot (constantly state)]
      (binding [blob/*read-bytes-fn* (constantly (.getBytes "pdf" "UTF-8"))]
        (let [names (zip-entry-names (files/event-zip-bytes! "event-1"))]
          (is (= 2 (count names)))
          (is (= 2 (count (distinct names))))
          (is (every? #(re-find #"file-[12]" %) names)))))))

(deftest actual-byte-count-enforces-upload-limit-before-blob-write
  (let [state (atom base-state)
        put-count (atom 0)
        source (java.io.File/createTempFile "oversized-slides-" ".pptx")]
    (try
      (spit source "ninebytes")
      (with-redefs [decisions/max-upload-bytes 8
                    store/snapshot #(deref state)
                    store/new-id (let [ids (atom ["file-1" "version-1"])]
                                   #(let [id (first @ids)] (swap! ids subvec 1) id))]
        (binding [blob/*put-fn* (fn [_ _] (swap! put-count inc))]
          (is (thrown-with-msg?
                clojure.lang.ExceptionInfo
                #"file cannot be uploaded"
                (files/upload! {:source source :filename "slides.pptx"
                                :content-type "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                                :size 1 :event-id "event-1"
                                :submission-id "sub-1" :task-key "slides"
                                :kind "Presentation" :actor "ada@example.com"})))
          (is (zero? @put-count) "invalid bytes never reach blob storage")
          (is (empty? (:files @state)))))
      (finally
        (.delete source)))))

(deftest person-owned-files-have-relative-zip-paths
  (let [version {:number 1 :filename "face.png" :storage-key "memory://face"}
        state (assoc base-state :files
                     {"file-headshot" {:id "file-headshot" :event-id "event-1"
                                       :person-id "speaker-1" :kind "Headshot"
                                       :versions [version]}})]
    (with-redefs [store/snapshot (constantly state)]
      (binding [blob/*read-bytes-fn* (constantly (.getBytes "png" "UTF-8"))]
        (let [names (zip-entry-names (files/event-zip-bytes! "event-1"))]
          (is (= ["speaker-1/Headshot-file-headshot-v1-face.png"] names))
          (is (every? #(not (.startsWith % "/")) names)))))))
