(ns cfp-scheduler-killer.domain.files
  "Pure decisions and projections for uploaded conference files.

   A file is a stable aggregate. Every upload appends an immutable version;
   comments accumulate on the aggregate. Byte storage is deliberately absent
   from this namespace."
  (:require
   [clojure.string :as str]))

(def max-upload-bytes (* 25 1024 1024))

(def file-kinds
  #{"Presentation" "Poster" "Handout" "Headshot" "Other"})

(def allowed-content-types
  #{"application/pdf"
    "application/vnd.apple.keynote"
    "application/vnd.ms-powerpoint"
    "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "application/octet-stream"
    "image/jpeg"
    "image/png"})

(defn- rejection [type message data]
  {:rejected (merge {:type type :message message} data)})

(defn latest-version [file]
  (peek (:versions file)))

(defn file-for-task [state submission-id task-key]
  (some (fn [file]
          (when (and (= submission-id (:submission-id file))
                     (= task-key (:task-key file)))
            file))
        (vals (:files state))))

(defn headshot-for-person [state person-id]
  (some (fn [file]
          (when (and (= person-id (:person-id file))
                     (= "Headshot" (:kind file)))
            file))
        (vals (:files state))))

(defn files-for-submission [state submission-id]
  (->> (:files state)
       vals
       (filter #(= submission-id (:submission-id %)))
       (sort-by (comp :uploaded-at latest-version) #(compare %2 %1))
       vec))

(defn files-for-event [state event-id]
  (->> (:files state)
       vals
       (filter #(= event-id (:event-id %)))
       (sort-by (comp :uploaded-at latest-version) #(compare %2 %1))
       vec))

(defn upload-errors
  "Validate only stable metadata. The handler owns authorization and the blob
   adapter owns the bytes."
  [{:keys [filename content-type size kind]}]
  (cond-> {}
    (str/blank? filename)
    (assoc :filename "Choose a file to upload.")

    (not (contains? file-kinds kind))
    (assoc :kind "Choose Presentation, Poster, Handout, Headshot, or Other.")

    (or (not (integer? size)) (not (pos? size)))
    (assoc :size "The upload is empty.")

    (and (integer? size) (> size max-upload-bytes))
    (assoc :size "Files must be 25 MB or smaller.")

    (not (contains? allowed-content-types (or content-type "application/octet-stream")))
    (assoc :content-type "Use PDF, PowerPoint, Keynote, PNG, or JPEG.")

    (and (= "Headshot" kind)
         (not (#{"image/png" "image/jpeg"} content-type)))
    (assoc :content-type "Headshots must be PNG or JPEG.")))

(defn- target-error
  [state {:keys [event-id submission-id task-key person-id kind file-id]}]
  (let [submission (get-in state [:submissions submission-id])
        task (get-in state [:tasks [submission-id task-key]])
        task-type (or (:task-type task) (:type task))
        upload-task? (or (= "file" task-type)
                         ;; Default checklists created before file tasks became
                         ;; first-class used :type "url", but declared the
                         ;; intended upload kind. Keep those durable facts
                         ;; usable without opening arbitrary URL/check tasks to
                         ;; file completion.
                         (and (= "url" task-type) (not (str/blank? (:file-kind task)))))
        person (get-in state [:people person-id])
        existing (get-in state [:files file-id])]
    (cond
      (and submission-id
           (or (nil? submission) (not= event-id (:event-id submission))))
      (rejection :submission-not-found "Submission does not belong to this event."
                 {:submission-id submission-id :event-id event-id})

      (and submission-id task-key (nil? task))
      (rejection :task-not-found "File request does not exist."
                 {:submission-id submission-id :task-key task-key})

      (and submission-id task-key (not upload-task?))
      (rejection :invalid-file-task "That speaker task does not accept an upload."
                 {:submission-id submission-id :task-key task-key})

      (and submission-id task-key (not (str/blank? (:file-kind task)))
           (not= (:file-kind task) kind))
      (rejection :file-kind-mismatch "The upload kind does not match this file request."
                 {:submission-id submission-id :task-key task-key
                  :expected-kind (:file-kind task) :kind kind})

      (and person-id (nil? person))
      (rejection :person-not-found "Speaker does not exist." {:person-id person-id})

      (and person-id (not= "Headshot" kind))
      (rejection :invalid-profile-file "A profile upload must be a Headshot."
                 {:person-id person-id :kind kind})

      (and (nil? submission-id) (nil? person-id))
      (rejection :target-required "A file must belong to a submission or speaker." {})

      (and existing
           (not= (select-keys existing [:event-id :submission-id :task-key :person-id :kind])
                 {:event-id event-id :submission-id submission-id :task-key task-key
                  :person-id person-id :kind kind}))
      (rejection :target-mismatch "A new version cannot move a file to another target."
                 {:file-id file-id})

      :else nil)))

(defn decide-add-version
  "Return one file.version-added fact, a rejection, or an idempotent no-op.

   The caller supplies ids, checksum, storage key and timestamp so this stays a
   total deterministic function."
  [state {:keys [file-id version-id event-id submission-id task-key person-id
                 kind filename content-type size sha256 storage-key actor at
                 force-version?]
          :as command}]
  (let [errors (upload-errors command)
        target-rejection (target-error state command)
        existing (get-in state [:files file-id])
        latest (latest-version existing)
        all-versions (mapcat :versions (vals (:files state)))
        version-id-conflict? (some #(= version-id (:id %)) all-versions)
        storage-key-conflict? (some #(= storage-key (:storage-key %)) all-versions)]
    (cond
      (str/blank? actor)
      (rejection :actor-required "A human actor is required." {:file-id file-id})

      (or (str/blank? file-id) (str/blank? version-id)
          (str/blank? storage-key) (str/blank? sha256))
      (rejection :identity-required "File, version, checksum, and storage identity are required." {})

      (seq errors)
      {:rejected {:type :invalid-upload :message "The file cannot be uploaded." :errors errors}}

      target-rejection target-rejection

      (and (not force-version?) (= sha256 (:sha256 latest)))
      {:facts [] :file existing}

      version-id-conflict?
      (rejection :version-id-conflict
                 "A file version must have a globally unique identity."
                 {:file-id file-id :version-id version-id})

      storage-key-conflict?
      (rejection :storage-key-conflict
                 "A file version cannot overwrite an immutable storage location."
                 {:file-id file-id :storage-key storage-key})

      :else
      {:facts
       [{:type "file.version-added"
         :actor actor
         :event-id event-id
         :payload
         {:file-id file-id
          :event-id event-id
          :submission-id submission-id
          :task-key task-key
          :person-id person-id
          :kind kind
          :version {:id version-id
                    :number (inc (count (:versions existing)))
                    :filename filename
                    :content-type (or content-type "application/octet-stream")
                    :size size
                    :sha256 sha256
                    :storage-key storage-key
                    :uploaded-by actor
                    :uploaded-at at}}}]})))

(defn decide-add-comment
  [state {:keys [file-id comment-id body actor at]}]
  (let [file (get-in state [:files file-id])
        body (some-> body str/trim)
        existing-comment (some #(when (= comment-id (:id %)) %)
                               (:comments file))]
    (cond
      (nil? file)
      (rejection :file-not-found "File does not exist." {:file-id file-id})

      (str/blank? actor)
      (rejection :actor-required "A human actor is required." {:file-id file-id})

      (str/blank? comment-id)
      (rejection :comment-id-required "A stable comment identity is required."
                 {:file-id file-id})

      (str/blank? body)
      (rejection :comment-required "Write a comment first." {:file-id file-id})

      (> (count body) 2000)
      (rejection :comment-too-long "Comments must be 2,000 characters or fewer."
                 {:file-id file-id})

      (and existing-comment
           (= {:body body :actor actor}
              (select-keys existing-comment [:body :actor])))
      {:facts [] :file file}

      existing-comment
      (rejection :comment-id-conflict
                 "A comment identity cannot be reused for different content or actor."
                 {:file-id file-id :comment-id comment-id})

      :else
      {:facts [{:type "file.comment-added"
                :actor actor
                :event-id (:event-id file)
                :payload {:file-id file-id
                          :comment {:id comment-id :body body :actor actor :at at}}}]})))
