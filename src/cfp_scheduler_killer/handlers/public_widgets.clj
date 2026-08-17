(ns cfp-scheduler-killer.handlers.public-widgets
  "Published agenda, session, speaker, and gallery handlers."
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.embed-widget :as embed-widget]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.exports :as exports]
   [cfp-scheduler-killer.og-card :as og-card]
   [cfp-scheduler-killer.personal-schedule :as personal-schedule]
   [cfp-scheduler-killer.public-catalog :as public-catalog]
   [cfp-scheduler-killer.schedule :as schedule]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.views.my-schedule :as view-my-schedule]
   [cfp-scheduler-killer.views.public-cfps :as view-public-cfps]
   [cfp-scheduler-killer.views.public-widgets :as view-public-widgets]
   [cfp-scheduler-killer.views.schedule :as view-schedule]
   [cfp-scheduler-killer.views.shell :as view-shell]
   [cfp-scheduler-killer.web.datastar :as datastar]
   [cfp-scheduler-killer.web.http :as http]
   [clojure.string :as str]
   [hiccup2.core :as h])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpClient$Redirect HttpRequest HttpResponse$BodyHandlers)
   (java.nio.charset StandardCharsets)
   (java.security MessageDigest)
   (java.time Duration)))

(defn public-widget-not-found
  ;; THE public 404 (Gene, 2026-08-11) — one editorial page for every public
  ;; not-found; the caller's message survives as the quiet detail line.
  ([message] (public-widget-not-found message nil))
  ([message event]
   (http/html-response 404 (view-shell/not-on-the-program-page message event))))

(defn- organizer-chip-data
  "The committee chair as the program page's organizer chip (mirrors the CFP
   page's event-host: chair -> person profile)."
  [event]
  (when-let [comm (first (events/committees-for-event (:id event)))]
    (when-let [chair (->> (committees/members-for-committee (:id comm))
                          (filter #(= "chair" (:role %)))
                          first)]
      (let [p (when (:email chair) (committees/person-by-email (:email chair)))]
        {:name (:name chair)
         :headshot-url (get-in p [:profile :headshot-url])
         :tagline (get-in p [:profile :tagline])
         :url (str "/organizers/" (:slug event))}))))

(defn handle-cfps [_req]
  (let [open-cfps (->> (events/list-events)
                       (remove :archived-at)
                       (remove events/unlisted?)
                       (filter submissions/accepting?)
                       (mapv (fn [event]
                               {:event event
                                :organizer (organizer-chip-data event)
                                :speakers (public-catalog/public-speakers event)})))]
    (http/html-response (view-public-cfps/cfps-page open-cfps))))

(defn- visible-event
  "The event at this slug, IF this requester may see its public face. An
   unlisted event is indistinguishable from no event (Gene, 2026-08-11) — nil
   here, so every caller falls into the 404 it already had. The committee still
   sees it, so a chair can proof the program before it is listed."
  [req slug]
  (when-let [event (events/event-by-slug slug)]
    (when-not (and (events/unlisted? event)
                   (not (auth/member-of-event? (auth/current-person req) (:id event))))
      event)))

(defn- requested-selected-ids [req param]
  (let [raw (get-in req [:params param])
        values (if (sequential? raw) raw [raw])]
    (->> values (remove nil?) (mapcat #(str/split (str %) #","))
         (keep http/clean-id) set)))

(defn- public-selected-ids [event requested]
  (set (map :id (personal-schedule/selected-submissions event requested))))

(defn handle-program
  "/program/:slug — THE canonical public face (Gene ratified 2026-08-11:
   '/program is canon — everything else redirects. we have optionality later.')"
  [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (visible-event req slug)]
      (let [host (http/request-host req)
            agenda (schedule/agenda event)
            all (public-catalog/public-speakers event)
            q (http/not-blank (get-in req [:params :q]))
            active-day (http/not-blank (get-in req [:params :day]))
            selected-ids (public-selected-ids event (requested-selected-ids req :picks))]
        (if (= embed-widget/mode (get-in req [:params :embed]))
          (http/html-response
            (embed-widget/accepted-sessions-fragment event agenda))
          (http/html-response
            (embed-widget/program-with-copy-paste
              (view-public-widgets/program-page
                event
                host
                all
                (public-catalog/filter-speakers all q)
                q
                {}
                (organizer-chip-data event)
                (view-schedule/agenda-days event agenda active-day selected-ids)
                (view-schedule/agenda-export-hint event)
                selected-ids)
              host
              event))))
      (public-widget-not-found (str "There is no event at /program/" slug ".")))))

(defn- redirect-to-program [req]
  (let [slug (get-in req [:path-params :slug])]
    (if (visible-event req slug)
      {:status 302 :headers {"Location" (str "/program/" slug)} :body ""}
      (public-widget-not-found (str "There is no event at /program/" slug ".")))))

(defn handle-agenda [req] (redirect-to-program req))

(def ^:private configurable-session-fields
  #{"description" "schedule" "speakers" "tags"})

(defn- session-widget-config [req]
  (let [raw-fields (get-in req [:params :fields])
        fields (when (some? raw-fields)
                 (->> (str/split (str raw-fields) #",")
                      (filter configurable-session-fields)
                      set))
        accent (get-in req [:params :accent])]
    {:fields fields
     :theme (if (= "compact" (get-in req [:params :theme])) "compact" "standard")
     :accent (when (and (string? accent)
                        (re-matches #"#[0-9A-Fa-f]{6}" accent))
               accent)}))

(defn- requested-widget-config [req]
  (cond-> (session-widget-config req)
    (nil? (get-in req [:params :theme])) (dissoc :theme)))

(defn handle-public-sessions [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (visible-event req slug)]
      (let [all (public-catalog/sessions event)
            q (http/not-blank (get-in req [:params :q]))
            track (http/not-blank (get-in req [:params :track]))
            format (http/not-blank (get-in req [:params :format]))
            room (http/not-blank (get-in req [:params :room]))
            config (assoc (session-widget-config req) :selected-ids
                          (public-selected-ids event (requested-selected-ids req :picks)))]
        (http/html-response
          (view-public-widgets/sessions-page
            event all (public-catalog/filter-sessions all q track format room)
            (public-catalog/tracks all) (public-catalog/formats all)
            (public-catalog/rooms all) q track format room
            config)))
      (public-widget-not-found (str "There is no event at /agenda/" slug "/sessions.")))))

(defn handle-public-session [req]
  (let [slug (get-in req [:path-params :slug])
        submission-id (get-in req [:path-params :submission-id])]
    (if-let [event (visible-event req slug)]
      (if-let [session (public-catalog/session-by-id event submission-id)]
        (http/html-response
          (view-public-widgets/session-detail-page
            event session
            (merge (select-keys (:params req)
                                [:from :day :q :track :format :room])
                   (session-widget-config req))))
        (public-widget-not-found "That session is not published." event))
      (public-widget-not-found (str "There is no event at /agenda/" slug ".")))))

(defn handle-public-speakers [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (visible-event req slug)]
      (let [all (public-catalog/public-speakers event)
            q (http/not-blank (get-in req [:params :q]))
            host (http/request-host req)
            agenda (schedule/agenda event)
            config (requested-widget-config req)]
        (http/html-response
          (embed-widget/program-with-copy-paste
            (view-public-widgets/program-page
              event host all (public-catalog/filter-speakers all q) q config
              (organizer-chip-data event)
              (view-schedule/agenda-days event agenda :all)
              (view-schedule/agenda-export-hint event))
            host
            event)))
      (public-widget-not-found (str "There is no event at /agenda/" slug "/speakers.")))))

(defn handle-public-directory [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (visible-event req slug)]
      (http/html-response
        (view-public-widgets/speaker-directory-page
          event (public-catalog/public-speakers event)))
      (public-widget-not-found (str "There is no event at /agenda/" slug "/directory.")))))

(defn handle-public-speakers-filter [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (visible-event req slug)]
      (let [all (public-catalog/public-speakers event)
            q (http/not-blank (:spkq (datastar/signals req)))
            config (requested-widget-config req)]
        (datastar/sse-fragment-response
          req "#speakers-results"
          (str (h/html
                 (view-public-widgets/speakers-region
                   event all (public-catalog/filter-speakers all q) q config)))))
      (public-widget-not-found (str "There is no event at /agenda/" slug "/speakers.")))))

(defn- speaker-in-roster
  "The roster entry addressed by the :person-id path param — which may be the
   speaker's permanent SLUG (what we publish) or their person UUID (what we
   published before slugs existed, and what an un-slugged speaker still uses).
   One resolver, so the detail page and the announce page cannot drift."
  [roster handle]
  (some #(when (or (= handle (:slug %)) (= handle (:id %))) %) roster))

(defn- canonical-speaker-redirect
  "A permanent redirect to the slug URL when the request arrived on the UUID and
   a slug exists. 301, not 302: the slug IS the address now, and the UUID form
   is history that still answers. nil when the request is already canonical (or
   when nothing better exists yet), so the caller just serves the page."
  [event speaker handle suffix]
  (when (and (:slug speaker) (not= handle (:slug speaker)))
    {:status 301
     :headers {"Location" (str "/agenda/" (:slug event)
                               "/speakers/" (:slug speaker) suffix)}
     :body ""}))

(def ^:private max-card-cache-entries 100)

(defonce ^:private card-cache (atom {}))

(defonce ^:private headshot-http-client
  (delay
    (-> (HttpClient/newBuilder)
        (.connectTimeout (Duration/ofSeconds 3))
        (.followRedirects HttpClient$Redirect/NORMAL)
        (.build))))

(defn- cached-value [key load-value]
  (let [snapshot @card-cache]
    (if (contains? snapshot key)
      (get snapshot key)
      (let [value (load-value)]
        (swap! card-cache
               (fn [entries]
                 (assoc (if (>= (count entries) max-card-cache-entries)
                          {}
                          entries)
                        key value)))
        value))))

(defn- fetch-headshot [url]
  (when-not (str/blank? url)
    (cached-value
      [:headshot url]
      (fn []
        (try
          (let [request (-> (HttpRequest/newBuilder (URI/create url))
                            (.timeout (Duration/ofSeconds 3))
                            (.GET)
                            (.build))
                response (.send ^HttpClient @headshot-http-client
                                request
                                (HttpResponse$BodyHandlers/ofByteArray))
                status (.statusCode response)
                body (.body response)]
            (when (and (<= 200 status 299)
                       body
                       (pos? (alength ^bytes body)))
              body))
          (catch Exception _ nil))))))
(defn- first-talk-title [speaker]
  (some-> (:sessions speaker) first :title not-empty))

(defn- sha-1 [value]
  (let [digest (.digest (MessageDigest/getInstance "SHA-1")
                        (.getBytes (pr-str value) StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) digest))))

(defn- speaker-card-etag [event speaker]
  (str "\""
       (sha-1 (og-card/card-version event speaker))
       "\""))

(defn- image-not-found []
  {:status 404 :headers {} :body ""})

(defn handle-public-speaker [req]
  (let [slug (get-in req [:path-params :slug])
        handle (get-in req [:path-params :person-id])]
    (if-let [event (visible-event req slug)]
      (if-let [speaker (speaker-in-roster (public-catalog/public-speakers event) handle)]
        (or (canonical-speaker-redirect event speaker handle "")
            (http/html-response
              (view-public-widgets/speaker-detail-page
                event speaker
                (merge {:from (get-in req [:params :from])
                        :q (http/not-blank (get-in req [:params :q]))
                        :base-url (http/request-host req)}
                       (requested-widget-config req)))))
        (public-widget-not-found "That speaker is not published." event))
      (public-widget-not-found (str "There is no event at /agenda/" slug ".")))))

(defn handle-announce [req]
  ;; A per-speaker HERO share page: this one accepted speaker is the star, and
  ;; the OTHER real accepted speakers of the same event fill a peer gallery.
  ;; Both the hero and the gallery are drawn from the SAME accepted/announced
  ;; roster the public /speakers page shows (public-catalog/public-speakers) — never
  ;; an invented or enrichment-table name. A non-accepted person is not a valid
  ;; hero (404), and never appears in the gallery.
  (let [slug (get-in req [:path-params :slug])
        handle (get-in req [:path-params :person-id])]
    (if-let [event (visible-event req slug)]
      (let [roster (public-catalog/public-speakers event)]
        (if-let [hero (speaker-in-roster roster handle)]
          (or
            ;; A share link is forever; the slug is the address we want copied.
            (canonical-speaker-redirect event hero handle "/announce")
            (http/html-response
              (view-public-widgets/announce-page
                event
                (http/request-host req)
                hero
                ;; …amongst these amazing people: every OTHER accepted speaker.
                (filterv #(not= (:id %) (:id hero)) roster)
                (organizer-chip-data event)
                (submissions/accepting? event)
                ;; The program detail, folded in — the SAME agenda pieces the
                ;; program page renders, so nobody has to leave the share page.
                (view-schedule/agenda-days event (schedule/agenda event) nil)
                (view-schedule/agenda-export-hint event))))
          (public-widget-not-found "That speaker is not published." event)))
      (public-widget-not-found (str "There is no event at /agenda/" slug ".")))))

(defn handle-organizer-brag [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (visible-event req slug)]
      (let [roster (public-catalog/public-speakers event)]
        (http/html-response
          (view-public-widgets/organizer-brag-page
            event (http/request-host req) roster (submissions/accepting? event))))
      (public-widget-not-found (str "There is no event at /program/" slug ".")))))

(defn handle-speaker-card [req]
  (let [slug (get-in req [:path-params :slug])
        handle (get-in req [:path-params :person-id])]
    (if-let [event (visible-event req slug)]
      (if-let [speaker (speaker-in-roster (public-catalog/public-speakers event) handle)]
        (let [etag (speaker-card-etag event speaker)
              headers {"Content-Type" "image/png"
                       "Cache-Control" "public, max-age=3600"
                       "ETag" etag}]
          (if (= etag (get-in req [:headers "if-none-match"]))
            {:status 304
             :headers (assoc headers "Content-Length" "0")
             :body ""}
            {:status 200
             :headers headers
             :body (cached-value
                     [:card etag]
                     #(og-card/render-card event speaker
                                           (fetch-headshot (:headshot speaker))))}))
        (image-not-found))
      (image-not-found))))

(defn handle-event-card [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (visible-event req slug)]
      (let [roster (public-catalog/public-speakers event)
            etag (str "\"" (sha-1 [(:id event) (:name event) (:location event)
                                   (:starts-on event) (:ends-on event)
                                   (:cfp-intro event) (count roster)]) "\"")
            headers {"Content-Type" "image/png"
                     "Cache-Control" "public, max-age=3600"
                     "ETag" etag}]
        (if (= etag (get-in req [:headers "if-none-match"]))
          {:status 304 :headers (assoc headers "Content-Length" "0") :body ""}
          {:status 200 :headers headers
           :body (cached-value [:event-card etag]
                               #(og-card/render-event-card event (count roster)))}))
      (image-not-found))))

(defn handle-homepage-card [_req]
  {:status 200
   :headers {"Content-Type" "image/png"
             "Cache-Control" "public, max-age=3600"
             "ETag" "\"curtain-call-homepage-card-v1\""}
   :body (cached-value [:homepage-card :v1] og-card/render-homepage-card)})

(defn handle-public-gallery [req]
  (handle-public-speakers req))

(defn handle-my-schedule [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (visible-event req slug)]
      (http/html-response
        (view-my-schedule/my-schedule-page
          event (public-catalog/sessions event)
          (public-selected-ids event (requested-selected-ids req :picks))))
      (public-widget-not-found (str "There is no event at /agenda/" slug ".")))))

(defn handle-public-itinerary [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (visible-event req slug)]
      (http/html-response
        (view-my-schedule/public-itinerary-page
          event (filterv #(seq (:day %)) (public-catalog/sessions event))
          (public-selected-ids event (requested-selected-ids req :picks))))
      (public-widget-not-found (str "There is no event at /agenda/" slug "/itinerary.")))))

(defn handle-my-schedule-ics [req]
  (let [slug (get-in req [:path-params :slug])]
    (if-let [event (visible-event req slug)]
      (-> (http/text-response "text/calendar; charset=utf-8"
                              (exports/calendar-ics-for event
                                                        (personal-schedule/selected-submissions
                                                          event (requested-selected-ids req :session-ids))))
          (assoc-in [:headers "Content-Disposition"]
                    (str "attachment; filename=\"" slug "-my-schedule.ics\""))
          (assoc-in [:headers "Cache-Control"] "private, no-store"))
      (public-widget-not-found (str "There is no event at /agenda/" slug ".")))))
