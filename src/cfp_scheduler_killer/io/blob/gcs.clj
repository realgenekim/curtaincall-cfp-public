(ns cfp-scheduler-killer.io.blob.gcs
  "Google Cloud Storage implementation of the blob algebra. Cloud-specific
   auth, URLs, REST calls, and the laptop CLI fallback live only here."
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [gcp-secrets.main :as gcp]
   [taoensso.timbre :as log]))

(defn gcloud-copy!
  "Shell out to `gcloud storage cp` as a laptop fallback."
  [src dest]
  (let [{:keys [exit err]} (shell/sh "gcloud" "storage" "cp" src dest)]
    (if (zero? exit)
      {:ok true}
      (do (log/warn :gcs-copy-failed :dest dest :err (str/trim (str err)))
          {:ok false :error err}))))

(defn parse-gs-uri
  "gs://bucket/path/to/object -> {:bucket .. :object ..}; nil otherwise."
  [s]
  (when-let [[_ bucket object] (re-matches #"gs://([^/]+)/(.+)" (str s))]
    {:bucket bucket :object object}))

(defn- object-url
  [{:keys [bucket object]} & [upload?]]
  (if upload?
    (str "https://storage.googleapis.com/upload/storage/v1/b/" bucket
         "/o?uploadType=media&name=" (java.net.URLEncoder/encode ^String object "UTF-8"))
    (str "https://storage.googleapis.com/storage/v1/b/" bucket
         "/o/" (java.net.URLEncoder/encode ^String object "UTF-8") "?alt=media")))

(defn rest-copy!
  "Copy through the GCS JSON API. The deployed distroless container uses this
   path; authentication comes from ADC or the Cloud Run metadata server."
  [src dest]
  (try
    (let [upload (parse-gs-uri dest)
          download (parse-gs-uri src)]
      (if-not (or upload download)
        {:ok false :error "neither side is gs://"}
        (let [token (gcp/get-token)
              client (java.net.http.HttpClient/newHttpClient)]
          (if upload
            (let [request (-> (java.net.http.HttpRequest/newBuilder
                                (java.net.URI/create (object-url upload :upload)))
                              (.header "Authorization" (str "Bearer " token))
                              (.header "Content-Type" "application/octet-stream")
                              (.POST (java.net.http.HttpRequest$BodyPublishers/ofFile
                                       (.toPath (io/file src))))
                              (.build))
                  response (.send client request
                                  (java.net.http.HttpResponse$BodyHandlers/ofString))]
              (if (< (.statusCode response) 300)
                {:ok true}
                {:ok false :error (str "HTTP " (.statusCode response))}))
            (let [request (-> (java.net.http.HttpRequest/newBuilder
                                (java.net.URI/create (object-url download)))
                              (.header "Authorization" (str "Bearer " token))
                              (.GET)
                              (.build))
                  response (.send client request
                                  (java.net.http.HttpResponse$BodyHandlers/ofByteArray))]
              (if (< (.statusCode response) 300)
                (do (io/make-parents (io/file dest))
                    (with-open [out (io/output-stream (io/file dest))]
                      (.write out ^bytes (.body response)))
                    {:ok true})
                {:ok false :error (str "HTTP " (.statusCode response))}))))))
    (catch Exception e
      (log/warn :gcs-rest-copy-error :msg (.getMessage e))
      {:ok false :error (.getMessage e)})))

(defn copy! [src dest]
  (let [result (rest-copy! src dest)]
    (if (:ok result) result (gcloud-copy! src dest))))

(defn put! [source destination]
  (let [result (copy! (.getPath (io/file source)) destination)]
    (if (:ok result)
      destination
      (throw (ex-info "GCS upload failed."
                      (assoc result :destination destination))))))

(defn read-bytes! [location]
  (let [tmp (java.io.File/createTempFile "cfp-file-" ".blob")]
    (try
      (let [result (copy! location (.getPath tmp))]
        (when-not (:ok result)
          (throw (ex-info "GCS download failed."
                          (assoc result :location location))))
        (java.nio.file.Files/readAllBytes (.toPath tmp)))
      (finally
        (.delete tmp)))))
