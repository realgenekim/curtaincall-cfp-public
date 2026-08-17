(ns cfp-scheduler-killer.web.http
  "Shared Ring boundary mechanics. Domain decisions do not belong here."
  (:require
   [clojure.data.json :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def ^:private config
  "resources/config.edn, read once. Missing file = all defaults."
  (delay (or (some-> (io/resource "config.edn") slurp edn/read-string)
             {})))

(defn request-host
  "The browser-visible origin, honoring Cloud Run forwarding headers."
  [req]
  (or (:public-base-url @config)
      (let [headers (:headers req)
            host (or (get headers "x-forwarded-host")
                     (get headers "host")
                     "localhost")
            scheme (or (get headers "x-forwarded-proto")
                       (name (or (:scheme req) :http)))]
        (str scheme "://" host))))

(defn not-blank [s]
  (when-not (str/blank? s) s))

(def ^:private id-pattern
  #"(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

(defn clean-id
  "Return a UUID-shaped string, or nil before untrusted input reaches a lookup."
  [s]
  (when (and s (re-matches id-pattern (str s))) (str s)))

;; INTENT: EMB-002 — browser-facing public surfaces render inline HTML, never
;; attachment downloads.
(defn html-response
  ([body] (html-response 200 body))
  ([status body]
   {:status status
    :headers {"Content-Type" "text/html; charset=utf-8"}
    :body (str body)}))

(defn see-other
  "303 — the correct redirect after a successful form POST."
  [location]
  {:status 303 :headers {"Location" location} :body ""})

(defn text-response
  ([content-type body] (text-response 200 content-type body))
  ([status content-type body]
   {:status status
    :headers {"Content-Type" content-type
              "Access-Control-Allow-Origin" "*"
              "Cache-Control" "public, max-age=60"}
    :body body}))

(defn plain-not-found []
  {:status 404
   :headers {"Content-Type" "text/plain; charset=utf-8"
             "Cache-Control" "no-store"}
   :body "Not found"})

(defn json-response
  ([data] (json-response 200 data))
  ([status data]
   (text-response status
                  "application/json; charset=utf-8"
                  (json/write-str data :escape-slash false))))

(defn with-etag
  "Add a content ETag, or return 304 for a matching conditional GET."
  [req resp]
  (if (not= 200 (:status resp))
    resp
    (let [etag (or (get-in resp [:headers "ETag"])
                   (str "W/\"" (format "%08x" (hash (str (:body resp)))) "\""))]
      (if (= etag (get-in req [:headers "if-none-match"]))
        {:status 304
         :headers (assoc (:headers resp) "ETag" etag "Content-Length" "0")
         :body ""}
        (assoc-in resp [:headers "ETag"] etag)))))
