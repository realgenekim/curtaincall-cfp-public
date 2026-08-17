(ns cfp-scheduler-killer.handlers.zoo
  "Internal element-gallery ('zoo') handlers — proof surfaces for organizers,
   never reachable by the public (bead sessionize-sched-killer-92x).

   /admin/zoo/social-sharing is the first room: it proves how our own pages
   unfurl on LinkedIn/Slack by fetching a page ON THIS HOST, parsing its
   og:*/twitter:* meta tags, and rendering the same card shapes those
   networks would render — living documentation next to the proof."
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.og-card :as og-card]
   [cfp-scheduler-killer.views.public-widgets :as public-widgets]
   [cfp-scheduler-killer.views.shell :as shell]
   [cfp-scheduler-killer.views.zoo :as view-zoo]
   [cfp-scheduler-killer.web.http :as http]
   [clojure.string :as str]
   [taoensso.timbre :as log])
  (:import
   (java.io ByteArrayInputStream)
   (java.net URI)
   (javax.imageio ImageIO)
   (org.jsoup Jsoup)))

(def ^:private user-agent
  "Same honest-contact convention as sessionize-import's fetcher."
  "cfp-scheduler-killer/0.1 (zoo social-sharing preview; +https://github.com/realgenekim)")

(def ^:private fetch-timeout-ms 5000)

;; --- The self-fetch guard ----------------------------------------------------
;;
;; This page fetches a URL the operator TYPES, server-side. That is only safe
;; because we refuse to fetch anywhere but our own front door — never an
;; arbitrary internet URL (no SSRF-as-a-feature).

(defn- host-port
  "\"host\" or \"host:port\" for a parsed URI, lower-cased. nil when the URI
   has no host (a relative or malformed paste)."
  [^URI uri]
  (when-let [h (.getHost uri)]
    (let [p (.getPort uri)]
      (str/lower-case (if (pos? p) (str h ":" p) h)))))

(defn- allowed-target?
  "true only for our own host: the request's own Host header, the well-known
   dev port, or the production domain. Everything else is refused with a
   friendly message, never fetched."
  [own-host url]
  (try
    (let [uri (URI. (str url))
          scheme (some-> (.getScheme uri) str/lower-case)
          hp (host-port uri)
          ;; hash-set, not a #{} literal: own-host is a RUNTIME value that
          ;; frequently equals the "localhost:20501" literal already in the
          ;; set, and the reader-literal form throws "Duplicate key" in that
          ;; case (caught below as false — a same-host request silently
          ;; refused). hash-set just dedupes, like any other function call.
          allowed (hash-set "localhost:20501" "curtaincallcfp.com"
                            (some-> own-host str/lower-case))]
      (boolean (and (#{"http" "https"} scheme) hp (contains? allowed hp))))
    (catch Exception _ false)))

;; --- Fetch + parse ------------------------------------------------------------

(defn- fetch-html [url]
  (-> (Jsoup/connect ^String url)
      (.userAgent user-agent)
      (.timeout (int fetch-timeout-ms))
      (.followRedirects true)
      (.ignoreHttpErrors true)
      (.get)
      (.html)))

(defn- extract-meta-tags
  "Every og:*/twitter:* meta tag as [{:property :content}], in document
   order. Reuses the same Jsoup attribute-select approach sessionize-import
   uses for og:title/og:image — no new dependency."
  [html]
  (let [doc (Jsoup/parse ^String html)]
    (->> (.select doc "meta")
         (keep (fn [el]
                 (let [prop (or (not-empty (.attr el "property"))
                                (not-empty (.attr el "name")))
                       content (not-empty (.attr el "content"))]
                   (when (and prop content
                              (or (str/starts-with? prop "og:")
                                  (str/starts-with? prop "twitter:")))
                     {:property prop :content content}))))
         vec)))

(defn- fetch-image-dims
  "Pixel dimensions of an image URL, or nil when it can't be measured — never
   throws. The image URL comes from an og:image tag on a page we already
   trusted enough to fetch; this is the same follow-the-page-through the
   browser itself would do."
  [url]
  (try
    (let [bytes (-> (Jsoup/connect ^String url)
                    (.userAgent user-agent)
                    (.timeout (int fetch-timeout-ms))
                    (.ignoreContentType true)
                    (.execute)
                    (.bodyAsBytes))
          img (ImageIO/read (ByteArrayInputStream. bytes))]
      (when img {:width (.getWidth img) :height (.getHeight img)}))
    (catch Exception e
      (log/warn :zoo-image-measure-failed :url url :msg (.getMessage e))
      nil)))

(defn- verdict-for [dims]
  (if-let [{:keys [width height]} dims]
    (if (and (>= width 1200) (>= height 627))
      "big card eligible ✅"
      "will render as small square thumb (≥1200×627 needed for the big card)")
    "could not measure"))

(defn preview-for
  "Fetch + parse `url` into everything the page needs to render both card
   previews and the raw tag table. Never throws — a fetch/parse failure comes
   back as {:error \"...\"} so the page renders a quiet message, never a 500."
  [url]
  (try
    (let [html (fetch-html url)
          tags (extract-meta-tags html)
          meta (into {} (map (juxt :property :content)) tags)
          image-url (get meta "og:image")
          dims (when image-url (fetch-image-dims image-url))]
      {:tags tags
       :meta meta
       :image-url image-url
       :dims dims
       :verdict (verdict-for dims)})
    (catch Exception e
      (log/warn :zoo-preview-fetch-failed :url url :msg (.getMessage e))
      {:error (str "Could not fetch or parse that URL: " (.getMessage e))})))

(defn- specimen-event []
  (or (events/event-by-slug "enterprise-ai-summit-charlotte-2026")
      {:slug "enterprise-ai-summit-charlotte-2026"
       :name "Enterprise AI Summit — Charlotte"
       :cfp-intro "A gathering for people building enterprise AI."
       :settings {:hero-image-url "/images/eais-charlotte-hero.jpg"}}))

(defn- specimen-hero [event]
  (or (first (get-in event [:settings :announced-speakers]))
      {:id "speaker" :slug "speaker" :name "Featured speaker"
       :sessions [{:title "A story worth sharing"}]}))

(defn zoo-specimens
  "Production metadata paired with self-contained previews from the same card
  renderers. The zoo therefore works before deploy and with an empty store."
  [base-url event hero]
  (let [card-root (str base-url "/admin/zoo/social-sharing/cards/")]
    [{:id "main-homepage" :label "Main homepage"
      :metadata shell/homepage-social-metadata
      :preview-image-url (str card-root "homepage")}
     {:id "cfp" :label "CFP"
      :metadata (public-widgets/event-social-metadata
                  event base-url (str "/cfp/" (:slug event)) "Call for papers")
      :preview-image-url (str card-root "event")}
     {:id "speaker-brag" :label "Speaker brag"
      :metadata (public-widgets/announce-social-metadata event base-url hero)
      :preview-image-url (str card-root "speaker")}
     {:id "event-program" :label "Event/program"
      :metadata (public-widgets/event-social-metadata
                  event base-url (str "/program/" (:slug event)) "Program")
      :preview-image-url (str card-root "event")}]))

(defn handle-zoo-preview-card [req]
  (let [event (specimen-event)
        hero (specimen-hero event)
        kind (get-in req [:path-params :kind])
        bytes (case kind
                "homepage" (og-card/render-homepage-card)
                "event" (og-card/render-event-card event)
                "speaker" (og-card/render-card event hero nil)
                nil)]
    (if bytes
      {:status 200
       :headers {"Content-Type" "image/png"
                 "Cache-Control" "private, max-age=3600"}
       :body bytes}
      {:status 404 :headers {} :body ""})))

(defn handle-zoo-social-sharing
  "GET /admin/zoo/social-sharing[?url=...] — signed-in organizers only (the
   route is deliberately absent from auth/public-prefixes, so the default-deny
   gate requires a session; it is also unscoped, so any signed-in person may
   open it, matching /events and the other tenant-agnostic organizer pages)."
  [req]
  (let [person (auth/current-person req)
        own-host (get (:headers req) "host")
        base-url (http/request-host req)
        event (specimen-event)
        hero (specimen-hero event)
        specimens (zoo-specimens base-url event hero)
        target (http/not-blank (get-in req [:params :url]))
        preview (when target
                  (if (allowed-target? own-host target)
                    (preview-for target)
                    {:error (str "Refusing to fetch \"" target "\" — this page only "
                                 "previews pages on our own host, never an arbitrary URL.")}))]
    (http/html-response
      (view-zoo/social-sharing-page
        {:person person
         :base-url base-url
         :specimens specimens
         :target target
         :preview preview}))))
