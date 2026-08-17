(ns cfp-scheduler-killer.files
  "Append-only application shell for conference files and versions."
  (:require
   [cfp-scheduler-killer.domain.files :as decisions]
   [cfp-scheduler-killer.domain.speaker-tasks :as task-decisions]
   [cfp-scheduler-killer.folds :as folds]
   [cfp-scheduler-killer.io.blob :as blob]
   [cfp-scheduler-killer.store :as store]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [taoensso.timbre :as log])
  (:import
   (java.math BigInteger)
   (java.security DigestInputStream MessageDigest)
   (java.util.zip ZipEntry ZipOutputStream)))

(defn- accepted! [decision]
  (if-let [rejection (:rejected decision)]
    (throw (ex-info (:message rejection) rejection))
    decision))

(defn- source-snapshot [source]
  (let [snapshot (java.io.File/createTempFile "cfp-upload-" ".blob")
        digest (MessageDigest/getInstance "SHA-256")]
    (try
      (with-open [in (DigestInputStream. (io/input-stream source) digest)
                  out (io/output-stream snapshot)]
        (let [buffer (byte-array 8192)
              size (loop [total 0]
                     (let [read (.read in buffer)]
                       (if (neg? read)
                         total
                         (let [next-size (+ total read)]
                           (if (> next-size decisions/max-upload-bytes)
                             next-size
                             (do
                               (.write out buffer 0 read)
                               (recur next-size)))))))]
          {:source snapshot
           :sha256 (format "%064x" (BigInteger. 1 (.digest digest)))
           :size size}))
      (catch Exception e
        (.delete snapshot)
        (throw e)))))

(defn- delete-snapshot! [snapshot]
  (when (and (.exists snapshot) (not (.delete snapshot)))
    (log/warn :upload-snapshot-delete-failed :path (.getPath snapshot))))

(defn for-task [submission-id task-key]
  (decisions/file-for-task (store/snapshot) submission-id task-key))

(defn for-submission [submission-id]
  (decisions/files-for-submission (store/snapshot) submission-id))

(defn for-event [event-id]
  (decisions/files-for-event (store/snapshot) event-id))

(defn by-id [file-id]
  (get-in (store/snapshot) [:files file-id]))

(defn upload!
  "Measure and validate the source, store its bytes, then atomically append the
   immutable version and task completion facts. An identical retry appends
   nothing and stores nothing."
  [{:keys [source filename content-type event-id submission-id task-key
           person-id kind actor force-version?]}]
  (let [{snapshot :source :keys [sha256 size]} (source-snapshot source)]
    (try
      (let [state (store/snapshot)
            existing (or (when (and submission-id task-key)
                           (decisions/file-for-task state submission-id task-key))
                         (when (and person-id (= "Headshot" kind))
                           (decisions/headshot-for-person state person-id)))
            file-id (or (:id existing) (store/new-id))
            version-id (store/new-id)
            at (store/now-iso)
            storage-key (str event-id "/" file-id "/" version-id)
            command {:file-id file-id :version-id version-id :event-id event-id
                     :submission-id submission-id :task-key task-key
                     :person-id person-id :kind kind :filename filename
                     :content-type content-type :size size :sha256 sha256
                     :storage-key storage-key :actor actor :at at
                     :force-version? force-version?}
            decision (accepted! (decisions/decide-add-version state command))]
        (if (empty? (:facts decision))
          existing
          (let [location (blob/put! snapshot storage-key)
                file-facts (mapv #(assoc-in % [:payload :version :storage-key] location)
                                 (:facts decision))
                state-with-file (reduce folds/fold-event state file-facts)
                task-facts (if (and submission-id task-key)
                             (:facts (accepted!
                                       (task-decisions/decide-complete
                                         state-with-file
                                         {:submission-id submission-id
                                          :task-key task-key
                                          :value file-id :actor actor :at at})))
                             [])]
            (store/append-all! (into file-facts task-facts))
            (log/info :file-version-added :file-id file-id :version-id version-id
                      :event-id event-id :kind kind :actor actor)
            (by-id file-id))))
      (finally
        (delete-snapshot! snapshot)))))

(defn add-comment! [file-id comment-id body actor]
  (let [decision (accepted!
                   (decisions/decide-add-comment
                     (store/snapshot)
                     {:file-id file-id :comment-id comment-id :body body
                      :actor actor :at (store/now-iso)}))]
    (when (seq (:facts decision))
      (store/append-all! (:facts decision))
      (log/info :file-comment-added :file-id file-id :comment-id comment-id
                :actor actor))
    (by-id file-id)))

(defn latest-bytes! [file-id]
  (when-let [version (some-> (by-id file-id) decisions/latest-version)]
    (blob/read-bytes! (:storage-key version))))

(defn version-bytes!
  "Read one immutable version's bytes. The caller owns file authorization;
   version identity is resolved only inside that already-authorized aggregate."
  [file-id version-id]
  (when-let [version (some #(when (= version-id (:id %)) %)
                           (:versions (by-id file-id)))]
    (blob/read-bytes! (:storage-key version))))

(defn- archive-owner [file]
  (or (when-let [submission-id (:submission-id file)]
        (let [submission (store/submission-by-id submission-id)]
          (or (not-empty (get-in submission [:answers :talk-title]))
              (not-empty (:title submission)))))
      (when-let [person-id (:person-id file)]
        (not-empty (:name (store/person-by-id person-id))))
      (:submission-id file)
      (:person-id file)
      (:id file)))

(defn- zip-name
  ([file version] (zip-name file version "by-session"))
  ([file version grouping]
   (let [clean (fn [value fallback]
                 (let [cleaned (-> (str value)
                                   (str/replace #"[^A-Za-z0-9._-]+" "-")
                                   (str/replace #"^-+|-+$" ""))]
                   (if (or (str/blank? cleaned) (#{"." ".."} cleaned))
                     fallback
                     cleaned)))
         owner (clean (archive-owner file) (clean (:id file) "unassigned"))]
     (str (when (= "by-session" grouping) (str owner "/"))
          (clean (:kind file) "File") "-" (clean (:id file) "file")
          "-v" (:number version) "-"
          (clean (:filename version) "download")))))

(defn event-zip-bytes! [event-id]
  (let [out (java.io.ByteArrayOutputStream.)
        entries (->> (for [file (for-event event-id)
                           version (:versions file)]
                       [(zip-name file version) version])
                     (sort-by first))]
    (with-open [zip (ZipOutputStream. out)]
      (doseq [[entry-name version] entries]
        (.putNextEntry zip (doto (ZipEntry. entry-name)
                             (.setTime 0)))
        (.write zip ^bytes (blob/read-bytes! (:storage-key version)))
        (.closeEntry zip)))
    (.toByteArray out)))

(defn event-latest-zip-bytes!
  "Build a distribution ZIP containing only the current version of each
   selected event file. An empty selection means every event file."
  [event-id file-ids grouping]
  (let [selected (set file-ids)
        out (java.io.ByteArrayOutputStream.)
        entries (->> (for [file (for-event event-id)
                           :when (or (empty? selected) (contains? selected (:id file)))
                           :let [version (decisions/latest-version file)]
                           :when version]
                       [(zip-name file version grouping) version])
                     (sort-by first))]
    (with-open [zip (ZipOutputStream. out)]
      (doseq [[entry-name version] entries]
        (.putNextEntry zip (doto (ZipEntry. entry-name)
                             (.setTime 0)))
        (.write zip ^bytes (blob/read-bytes! (:storage-key version)))
        (.closeEntry zip)))
    (.toByteArray out)))
