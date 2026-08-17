(ns cfp-scheduler-killer.io.blob
  "Provider-neutral blob port. Domain code speaks only put/read/copy; local and
   GCS mechanics stay behind this namespace and can be replaced in tests."
  (:require
   [cfp-scheduler-killer.io.blob.gcs :as gcs]
   [cfp-scheduler-killer.io.blob.local :as local]
   [clojure.string :as str]))

(defn- upload-bucket []
  (some-> (System/getenv "UPLOAD_GCS_BUCKET") str/trim not-empty))

(defn- upload-root []
  (or (some-> (System/getProperty "cfp.upload-root") str/trim not-empty)
      "data/uploads"))

(defn- safe-storage-key! [storage-key]
  (let [segments (str/split (str storage-key) #"/")]
    (when (or (str/blank? (str storage-key))
              (some #{"" "." ".."} segments))
      (throw (ex-info "Unsafe storage key." {:storage-key storage-key})))
    storage-key))

(defn- bucket-destination [bucket storage-key]
  (str "gs://" bucket "/" storage-key))

(defn default-put! [source storage-key]
  (safe-storage-key! storage-key)
  (if-let [bucket (upload-bucket)]
    (gcs/put! source (bucket-destination bucket storage-key))
    (local/put! (upload-root) source storage-key)))

(defn default-read-bytes! [location]
  (if (str/starts-with? (str location) "gs://")
    (gcs/read-bytes! location)
    (local/read-bytes! location)))

(defn default-copy! [src dest]
  (if (or (gcs/parse-gs-uri src) (gcs/parse-gs-uri dest))
    (gcs/copy! src dest)
    (local/copy! src dest)))

(def ^:dynamic *put-fn* default-put!)
(def ^:dynamic *read-bytes-fn* default-read-bytes!)
(def ^:dynamic *copy-fn* default-copy!)

(defn put! [source storage-key]
  (*put-fn* source storage-key))

(defn read-bytes! [location]
  (*read-bytes-fn* location))

(defn copy! [src dest]
  (*copy-fn* src dest))
