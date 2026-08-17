(ns cfp-scheduler-killer.io.blob.local
  "Local-filesystem implementation of the blob algebra."
  (:require
   [clojure.java.io :as io]))

(defn put!
  [root source storage-key]
  (let [destination (io/file root storage-key)]
    (io/make-parents destination)
    (with-open [in (io/input-stream source)
                out (io/output-stream destination)]
      (io/copy in out))
    (.getPath destination)))

(defn read-bytes! [location]
  (java.nio.file.Files/readAllBytes (.toPath (io/file location))))

(defn copy! [src dest]
  (try
    (io/make-parents (io/file dest))
    (with-open [in (io/input-stream src)
                out (io/output-stream dest)]
      (io/copy in out))
    {:ok true}
    (catch Exception e
      {:ok false :error (.getMessage e)})))
