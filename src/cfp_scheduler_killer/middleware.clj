(ns cfp-scheduler-killer.middleware
  "Web server with http-kit, reitit routing, and dev auto-reload.

   Handler convention (CLAUDE.md): every handler is a named `defn handle-*`
   referenced as `#'var` in the route table, so REPL redefinition takes effect
   without a restart."
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.secrets :as secrets]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.telemetry :as telemetry]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [ring.middleware.content-type :refer [wrap-content-type]]
   [ring.middleware.keyword-params :refer [wrap-keyword-params]]
   [ring.middleware.multipart-params :refer [wrap-multipart-params]]
   [ring.middleware.params :refer [wrap-params]]
   [ring.middleware.session :refer [wrap-session]]
   [ring.middleware.session.cookie :refer [cookie-store]]
   [taoensso.timbre :as log])
  (:gen-class))

(defn cloud-run-service? []
  (boolean (System/getenv "K_SERVICE")))

(def ^:private config
  "resources/config.edn, read once. Missing file = all defaults."
  (delay (or (some-> (io/resource "config.edn") slurp edn/read-string)
             {})))

(defn cookie-key-bytes [secret]
  (let [key-bytes (.getBytes (str/trim (or secret ""))
                             java.nio.charset.StandardCharsets/UTF_8)
        byte-count (alength key-bytes)]
    (when-not (= 16 byte-count)
      (throw (ex-info "The session cookie key must contain exactly 16 UTF-8 bytes."
                      {:type :invalid-session-cookie-key
                       :byte-count byte-count})))
    key-bytes))

(defn wrap-remember-working-event
  "Router-level: a signed-in GET of any /events/:slug page marks that event as
   the person's working event, so the sidebar's spine follows them onto pages
   with no event in the URL (docs/design/nav-elements.md)."
  [handler]
  (fn [req]
    (when (and (= :get (:request-method req))
               (str/starts-with? (:uri req) "/events/"))
      (when-let [slug (get-in req [:path-params :slug])]
        (when-let [person (auth/current-person req)]
          (when-let [event (events/event-by-slug slug)]
            (events/remember-working-event! (:id person) (:id event))))))
    (handler req)))

(defn wrap-store-refresh
  "Re-fold the log if another process appended to it (e.g. `make seed-demo`
   while the dev server is running). One File.length() per request."
  [handler]
  (fn [req]
    (store/refresh-if-changed!)
    (handler req)))

(defn demo-mode?
  "Is this instance flagged as a public demo? (bd -o42 — gates the one-click
   demo sign-in.) The env var WINS in both directions — DEMO_MODE=false must
   be able to turn a baked-in config default OFF, or a production service
   built from the same image would ship with impersonation buttons."
  []
  (case (System/getenv "DEMO_MODE")
    "true"  true
    "false" false
    (boolean (:demo-mode @config))))

(defn make-session-cookie-store []
  (if (cloud-run-service?)
    (cookie-store
      {:key (cookie-key-bytes
              (or (secrets/load-secret "session-cookie-key"
                                       "secrets/session-cookie-key.txt")
                  (throw (ex-info "Required session-cookie-key secret is unavailable."
                                  {:type :missing-session-cookie-key}))))})
    (if-let [secret (secrets/load-secret-or-nil "session-cookie-key"
                                                "secrets/session-cookie-key.txt")]
      (cookie-store {:key (cookie-key-bytes secret)})
      (do
        (log/warn :session-cookie-key-fallback
                  :scope :local
                  :msg "using a process-local random cookie key")
        (cookie-store)))))

(defonce ^:private persistent-session-store
  (delay (make-session-cookie-store)))

(defn wrap-require-login
  "Router-level authorization wrapper; delegates to the live auth gate."
  [handler]
  (auth/wrap-require-login handler))

(defn wrap-app
  "Apply the production middleware policy in its load-bearing order.

   Authorization is router middleware so genuinely missing GETs reach the 404
   boundary. Declared routes still inherit the gate automatically."
  [handler is-dev?]
  (-> handler
      wrap-keyword-params
      wrap-params
      wrap-multipart-params
      ;; Session must be outside the router auth gate because it reads :session.
      ;; SameSite=Lax preserves OAuth state across the cross-site callback.
      (wrap-session {:store @persistent-session-store
                     :cookie-attrs {:http-only true
                                    :same-site :lax
                                    :secure (not is-dev?)}})
      wrap-store-refresh
      wrap-content-type
      telemetry/wrap-telemetry))
