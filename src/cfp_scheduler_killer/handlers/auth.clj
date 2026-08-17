(ns cfp-scheduler-killer.handlers.auth
  "Magic-link, logout, and allowlisted judge-demo authentication handlers."
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.auth-google :as auth-google]
   [cfp-scheduler-killer.demo :as demo]
   [cfp-scheduler-killer.mail :as mail]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.views.auth :as view-auth]
   [cfp-scheduler-killer.web.event :as event-web]
   [cfp-scheduler-killer.web.http :as http]
   [clojure.string :as str]
   [taoensso.timbre :as log]))

(defn handle-login-page [req]
  ;; Dev-only cycle-time saver: prefill the email box with the first reviewer
  ;; on file (in the seeded world, Gene), so signing in is Enter, not typing.
  ;; Production renders an empty field — prefilling anyone's address on a
  ;; public page would leak it.
  (let [prefill (when (auth/dev?)
                  (let [snap (store/snapshot)
                        pid (some-> snap :memberships vals first :person-id)]
                    (get-in snap [:people pid :email])))]
    (http/html-response
      (view-auth/login-page {:next (get-in req [:params :next])
                             :dev? (auth/dev?)
                             :mail-live? (mail/configured?)
                             :demo? (demo/personas?)
                             :demo-personas (mapv #(select-keys % [:role :label :enabled?])
                                                  demo/personas)
                             :google? (auth-google/enabled?)
                             :prefill-email prefill}))))

(def ^:private sbek-magic-link-persona-emails
  #{"sbek-organizer@example.com"
    "sbek-speaker@example.com"
    "sbek-speaker2@example.com"
    "sbek-reviewer@example.com"})

(defn- demo-mode-value []
  (System/getenv "DEMO_MODE"))

(defn- magic-link-echo-authorized? [demo-mode email]
  (and (= "true" demo-mode)
       (string? email)
       (contains? sbek-magic-link-persona-emails
                  (-> email str/trim str/lower-case))))

(defn- magic-link-path [token next*]
  (str "/auth/" token
       (when (http/not-blank next*)
         (str "?next=" (java.net.URLEncoder/encode next* "UTF-8")))))

;; INTENT: AUTH-001 — a deployed response can contain an SBEK magic-link
;; credential only after the explicit demo-mode and persona-allowlist gate.
(defn- deployed-demo-magic-link [token email next*]
  (when (and token
             (magic-link-echo-authorized? (demo-mode-value) email))
    (magic-link-path token next*)))

(defn handle-login
  "Mint a link without turning this endpoint into a committee enumerator."
  [req]
  (let [email (get-in req [:params :email])
        next* (get-in req [:params :next])
        origin (let [h (:headers req)]
                 (str (or (get h "x-forwarded-proto") (name (or (:scheme req) :http)))
                      "://" (or (get h "x-forwarded-host") (get h "host") "localhost")))
        token (auth/issue-token! email origin)
        dev? (auth/dev?)
        demo-link (deployed-demo-magic-link token email next*)
        demo-echo? (boolean demo-link)
        ;; Local ENV=dev keeps its inbox-free development door. Every deployed
        ;; credential link, however, can now be constructed only by the single
        ;; authorization boundary above.
        link (or (when (and token dev?)
                   (magic-link-path token next*))
                 demo-link)
        smtp? (mail/configured?)
        no-mail-message (str "If " email " is on a committee, a link is on its way "
                             "when mail is configured. This deployment has no mail "
                             "configured — so no link can be sent. Ask an organizer "
                             "to send you one from the Comms page.")]
    (http/html-response
      (view-auth/login-page
        (cond-> {:sent-to email :dev? dev?
                 :demo? (demo/personas?)
                 :demo-personas (mapv #(select-keys % [:role :label]) demo/personas)
                 :demo-link? demo-echo?
                 :next next*
                 :message
                 (cond
                   (and token dev? smtp?)
                   "Found you on a committee — link below, and we emailed it too."
                   (and token dev?) "Found you on a committee."
                   demo-echo? "Demo mode — SMTP is off; use the sign-in link below."
                   (and token smtp?) (str "If " email " can sign in, a link is on "
                                          "its way — check your inbox in the next "
                                          "minute or two. It works once and expires "
                                          "in 24 hours.")
                   token no-mail-message
                   smtp? (str "If " email " is on a committee, a link is on its way.")
                   :else no-mail-message)}
          link
          (assoc :link link))))))

(defn handle-auth-token
  "Redeem a link and start a session."
  [req]
  (let [token (get-in req [:path-params :token])
        next* (get-in req [:params :next])]
    (if-let [person-id (auth/redeem-token! token)]
      (auth/sign-in {:status 303
                     :headers {"Location" (if (http/not-blank next*)
                                            next*
                                            ;; Committee members land on the
                                            ;; organizer view; speakers on their
                                            ;; own portal.
                                            (auth/home-path (store/person-by-id person-id)))}
                     :body ""}
                    person-id)
      (http/html-response
        400
        (view-auth/login-page
          {:dev? (auth/dev?)
           :message "That link has expired or was already used. Ask for a new one."})))))

(defn handle-logout [req]
  (auth/sign-out {:status 303 :headers {"Location" "/login"} :body ""}
                 (:session req)))

(defn handle-demo-login
  "POST /api/demo-login?role=organizer|reviewer|speaker — judge demo only.

   404 when the flag is off (the endpoint does not exist as far as a
   production instance is concerned); 422 when the AIE fixtures are absent."
  [req]
  (if-not (demo/personas?)
    (event-web/not-found-page "demo-login")
    (let [role (get-in req [:params :role])
          ;; The arbitrary email switcher is a local-development affordance.
          ;; A deployed judge demo accepts only the three fixed AIE roles.
          email (or (when (auth/dev?)
                      (http/not-blank (get-in req [:params :email])))
                    (get demo/role->email role))]
      (if-let [person (and (demo/persona-enabled? role)
                           email
                           (store/person-by-email email))]
        (do (log/info :demo-login :role role :person-id (:id person))
            ;; Switching identity from the dev strip returns you to the PAGE
            ;; you were on (Gene, 2026-08-10) — you switch to keep reviewing,
            ;; not to start over. The role-button path (login page) keeps its
            ;; home-path behaviour via the referer fallback.
            (auth/sign-in-demo {:status 303
                                :headers {"Location"
                                          (let [ref (get-in req [:headers "referer"])]
                                            (if (and ref (not (str/includes? ref "/login")))
                                              ref
                                              "/welcome"))}
                                :body ""}
                               (:id person) role))
        ;; A persona switch is never a logout operation. Preserve the caller's
        ;; session and return to the page they came from with an explicit
        ;; message; canned login-page buttons have no session to preserve and
        ;; still get the ordinary sign-in surface.
        (if-let [session (:session req)]
          {:status 303
           :headers {"Location"
                     (let [ref (get-in req [:headers "referer"])
                           separator (if (str/includes? (or ref "") "?") "&" "?")]
                       (str (or (http/not-blank ref) "/welcome") separator
                            "notice=That+reviewer+does+not+have+a+demo+persona.+You+are+still+signed+in."))}
           :session session
           :body ""}
          (http/html-response
            422
            (view-auth/login-page
              {:dev? (auth/dev?)
               :demo? (demo/personas?)
               :demo-personas (mapv #(select-keys % [:role :label :enabled?]) demo/personas)
               :message (str "This demo account is not ready yet. "
                             "Please try again shortly.")})))))))
