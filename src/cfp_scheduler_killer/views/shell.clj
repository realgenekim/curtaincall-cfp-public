(ns cfp-scheduler-killer.views.shell
  "Generic document and browser-runtime rendering mechanism."
  (:require
   [cfp-scheduler-killer.version :as version]
   [clojure.java.io :as io]
   [hiccup.page :as page]
   [hiccup2.core :as h]))

(defn versioned
  "Add a cache-busting query parameter.

   Classpath assets keep one URL until their bytes can change. Dynamic routes
   retain a per-render token because they have no resource timestamp."
  [url]
  (if-let [resource (io/resource (str "public" url))]
    (let [connection (.openConnection resource)]
      (str url "?v=" (.getLastModified connection)
           "-" (.getContentLengthLong connection)))
    (str url "?v=" (System/currentTimeMillis))))

(defn public-event-nav [slug active]
  [:nav.public-widget-nav {:aria-label "Public event pages"}
   (for [[id label suffix] [[:agenda "Agenda" ""]
                            [:sessions "Sessions" "/sessions"]
                            [:speakers "Speakers" "/speakers"]
                            [:itinerary "Itinerary" "/itinerary"]
                            [:my "My schedule" "/my"]]]
     [:a (cond-> {:class (when (= id active) "active")
                  :href (str "/agenda/" slug suffix)}
           (= id :my) (assoc :data-my-schedule-link ""))
      label])])

(def favicon-data-uri
  "data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'><text y='.9em' font-size='90'>🎤</text></svg>")

(def homepage-social-metadata
  {:title "Curtain Call — calls for papers, without the paperwork"
   :description "The CFP tool organizers dreamed of for fifteen years, built in a weekend on a dare. Zero to open CFP in ten minutes."
   :url "https://curtaincallcfp.com/"
   :image "https://curtaincallcfp.com/card.png"
   :type "website"
   :twitter-card "summary_large_image"})

(defn social-meta
  "Render the shared Open Graph/Twitter contract from one metadata projection."
  [{:keys [title description url image type twitter-card]}]
  (list
    [:link {:rel "canonical" :href url}]
    [:meta {:property "og:type" :content type}]
    [:meta {:property "og:site_name" :content "Curtain Call"}]
    [:meta {:property "og:title" :content title}]
    [:meta {:property "og:description" :content description}]
    [:meta {:property "og:url" :content url}]
    [:meta {:property "og:image" :content image}]
    [:meta {:name "twitter:card" :content twitter-card}]
    [:meta {:name "twitter:title" :content title}]
    [:meta {:name "twitter:description" :content description}]
    [:meta {:name "twitter:image" :content image}]))

(defn site-footer
  "Shared product provenance and navigation, visible on every full-page shell."
  []
  [:footer.site-footer
   [:p.site-thanks "Thanks for the dare, swyx!"]
   [:nav.site-links {:aria-label "Curtain Call"}
    [:a {:href "/manifesto"} "Manifesto"]
    [:span.dot "·"]
    [:a {:href "https://gist.github.com/realgenekim/863f20b8ea515ed8858a298f8e470e9d"
         :target "_blank" :rel "noopener"} "The story"]
    [:span.dot "·"]
    [:a {:href "/api/v1"} "API"]
    [:span.dot "·"]
    [:a {:href "/organizers/enterprise-ai-summit-charlotte-2026"} "Gene Kim"]
    [:span.dot "·"]
    [:a {:href "https://twitter.com/swyx" :target "_blank" :rel "noopener"}
     "swyx"]]])

(defn build-identity
  "Visible artifact identity shared by every full-page shell."
  []
  (let [deployed-at (version/build-time-str)]
    [:footer.build-identity
     {:data-build-sha version/git-sha
      :data-build-time deployed-at
      :title (str "Deployed commit " version/git-sha " at " deployed-at)
      :style "border-top:1px solid rgba(34,36,38,.1);color:#767676;font-size:.78rem;margin-top:1.5em;padding-top:1em;text-align:center;"}
     "Build " [:code version/git-sha]
     " · deployed " deployed-at
     " (" (version/time-ago version/build-time) ")"]))

(defn page-shell
  "HTML page skeleton with Fomantic UI. body-content is one or more hiccup forms."
  [title & body-content]
  (str
    (h/html
      (page/doctype :html5)
      [:html {:lang "en"}
       [:head
        [:meta {:charset "utf-8"}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
        [:title title]
        [:link {:rel "icon" :href favicon-data-uri}]
        [:link {:rel "stylesheet"
                :href "https://cdn.jsdelivr.net/npm/fomantic-ui@2.9.3/dist/semantic.min.css"}]
        [:script {:src "https://code.jquery.com/jquery-3.6.0.min.js"}]
        [:script {:src "https://cdn.jsdelivr.net/npm/fomantic-ui@2.9.3/dist/semantic.min.js"}]
        [:script {:src (versioned "/js/datastar-kit.js")}]
        [:script {:src (versioned "/js/keyboard.js") :defer true}]
        [:script {:src (versioned "/js/ghost-fill.js") :defer true}]
        [:script {:src (versioned "/js/telemetry-beacon.js") :defer true}]
        [:link {:rel "stylesheet" :href (versioned "/css/app.css")}]]
       [:body
        [:div.ui.container {:style "margin-top: 2em; margin-bottom: 4em;"}
         body-content
         (list (site-footer) (build-identity))]]])))

(defn not-on-the-program-page
  "THE public 404 (Gene ratified 2026-08-11). One page for every public
   not-found — a missing slug, a wrong session id, an UNLISTED event — because
   an unlisted event must be indistinguishable from no event. It speaks in the
   site's own editorial voice rather than a framework warning box. `detail` is
   an optional quiet line naming what was looked for."
  ([] (not-on-the-program-page nil nil))
  ([detail] (not-on-the-program-page detail nil))
  ([detail event]
   (page-shell
     "Not on the program"
     [:main.ui.container.public-widget-page.nf-page
      [:div.cfp-kicker "404"]
      [:h1.masthead-title "Not on the program"]
      [:p.nf-body "This page submitted a strong proposal. The committee passed."]
      [:p.nf-aside "(It happens to the best of us.)"]
      (when detail [:p.nf-detail detail])
      [:a.masthead-cfp-link
       {:href (if event (str "/program/" (:slug event)) "/events")}
       "See the talks that made it →"]
      [:p.nf-discovery
       [:a {:href "/cfps"} "Find your next stage: CFPs open now →"]]])))

(defn share-page-shell
  "Like `page-shell`, but threads extra <head> hiccup (Open Graph / Twitter
   card meta) into the document head so a public share surface unfurls in
   social clients. `head-extra` is one or more hiccup forms placed after the
   title; everything else is identical chrome to `page-shell`."
  [title head-extra & body-content]
  (str
    (h/html
      (page/doctype :html5)
      [:html {:lang "en"}
       [:head
        [:meta {:charset "utf-8"}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
        [:title title]
        head-extra
        [:link {:rel "icon" :href favicon-data-uri}]
        [:link {:rel "stylesheet"
                :href "https://cdn.jsdelivr.net/npm/fomantic-ui@2.9.3/dist/semantic.min.css"}]
        [:script {:src "https://code.jquery.com/jquery-3.6.0.min.js"}]
        [:script {:src "https://cdn.jsdelivr.net/npm/fomantic-ui@2.9.3/dist/semantic.min.js"}]
        [:script {:src (versioned "/js/datastar-kit.js")}]
        [:script {:src (versioned "/js/keyboard.js") :defer true}]
        [:script {:src (versioned "/js/ghost-fill.js") :defer true}]
        [:script {:src (versioned "/js/telemetry-beacon.js") :defer true}]
        [:script {:src (versioned "/js/share.js") :defer true}]
        [:link {:rel "stylesheet" :href (versioned "/css/app.css")}]]
       [:body
        [:div.ui.container {:style "margin-top: 2em; margin-bottom: 4em;"}
         body-content
         (list (site-footer) (build-identity))]]])))

(defn datastar-script
  "The Datastar runtime. `page-shell` deliberately doesn't load it — only the
   organizer shell does — so the two speaker-facing pages that stream bring
   their own. A module script is deferred by definition, so it runs after the
   element carrying data-star-init has been parsed."
  []
  [:script {:type "module" :src (versioned "/vendor/datastar-aliased.js")}])
