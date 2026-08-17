(ns cfp-scheduler-killer.views.public-widgets
  "Logged-out sessions, unified speakers, and public detail surfaces."
  (:require
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.public-catalog :as public-catalog]
   [cfp-scheduler-killer.speaker-history :as speaker-history]
   [cfp-scheduler-killer.views.format :as format]
   [cfp-scheduler-killer.views.personal-schedule :as personal-schedule]
   [cfp-scheduler-killer.views.shell :as shell]
   [clojure.data.json :as json]
   [clojure.string :as str]
   [datastar-kit.ds :as ds])
  (:import
   (java.net URLEncoder)
   (java.nio.charset StandardCharsets)))

(defn- encoded [value]
  (URLEncoder/encode (str (or value "")) StandardCharsets/UTF_8))

(defn- public-path [event suffix]
  (str "/agenda/" (:slug event) suffix))

(defn- speaker-handle
  "The URL segment that addresses this speaker: their permanent SLUG once it is
   minted, else the person UUID. Both resolve; the UUID 301s to the slug."
  [speaker]
  (or (not-empty (str (:slug speaker))) (:id speaker)))

(defn- masthead
  "Deep-page masthead (session/speaker detail): no tabs (Gene, 2026-08-11 —
   the program is a stacked page, not an app) — one crumb back to the program."
  [event _active label]
  [:div.cfp-masthead
   [:h1.ui.header (events/display-name event)]
   [:div.cfp-meta label]
   [:div.cfp-limit
    [:a {:href (str "/program/" (:slug event))} "← The program"]
    (when (:website-url event)
      [:a {:href (:website-url event) :rel "noopener"} "Official event website ↗"])
    [:a.masthead-cfp-link {:href (str "/cfp/" (:slug event))}
     "Speak here — call for papers →"]]])

(defn- initials [name]
  (->> (str/split (str/trim (or name "")) #"\s+")
       (take 2)
       (keep first)
       (apply str)
       str/upper-case))

(defn- headshot [speaker]
  (if (str/blank? (:headshot speaker))
    [:div.public-headshot.public-headshot-fallback
     {:role "img" :aria-label (str "Photo unavailable for " (:name speaker))}
     [:span (or (not-empty (initials (:name speaker))) "?")]
     [:small "Photo unavailable"]]
    [:img.public-headshot
     {:src (:headshot speaker) :alt (str (:name speaker) " headshot")
      :loading "lazy"}]))

(defn- session-meta [session]
  [:div.public-session-meta
   (if (and (not (str/blank? (:day session)))
            (not (str/blank? (:time session))))
     [:span (str (:day session) ": " (:time session))]
     [:span "Schedule to come"])
   (when-not (str/blank? (:room session))
     [:span (if (= "Room TBA" (:room session))
              "Room TBA"
              (str "Room: " (:room session)))])])

(defn- chips [session]
  [:div.public-chips
   (when-not (str/blank? (:format session))
     [:span.public-chip (str "Format: " (:format session))])
   (when-not (str/blank? (:track session))
     [:span.public-chip (str "Track: " (:track session))])])

(def ^:private copy-preview-limit
  "Characters of an abstract/bio shown before the reader has to ask for more."
  200)

(defn- expandable-copy
  "Long copy as a preview plus a 'Show more' REMAINDER. The details body holds
   only the text the preview left off at, so expanding continues the paragraph
   instead of reprinting its opening — the old markup put the whole copy in
   BOTH the preview and the details, so an opened card showed it twice. Copy
   that already fits renders whole, with no control to expand."
  [copy]
  (let [trimmed (str/trim (or copy ""))]
    (when-not (str/blank? trimmed)
      (if (<= (count trimmed) copy-preview-limit)
        [:p.public-copy-preview trimmed]
        (let [cut (or (str/last-index-of (subs trimmed 0 copy-preview-limit) " ")
                      copy-preview-limit)]
          (list
            [:p.public-copy-preview (str (str/trimr (subs trimmed 0 cut)) "…")]
            [:details.public-show-more
             [:summary
              [:span.public-disclosure-more "Show more"]
              [:span.public-disclosure-less "Show less"]]
             [:p.public-copy-rest (str/triml (subs trimmed cut))]]))))))

(defn- speaker-line [event speaker]
  [:div.public-session-speaker
   [:a {:href (public-path event (str "/speakers/" (speaker-handle speaker)))}
    (:name speaker)]
   (when-not (str/blank? (:tagline speaker))
     [:span (:tagline speaker)])
   (when-not (str/blank? (:company speaker))
     [:span (:company speaker)])])

(defn- field-visible? [config field]
  (or (nil? (:fields config))
      (contains? (:fields config) field)))

(defn- embed-config-query [config]
  (remove str/blank?
          [(when-let [accent (:accent config)] (str "accent=" (encoded accent)))
           (when-let [theme (:theme config)] (str "theme=" (encoded theme)))
           (when (some? (:fields config))
             (str "fields=" (encoded (str/join "," (sort (:fields config))))))
           (when (seq (:selected-ids config))
             (str "picks=" (encoded (personal-schedule/selection-value (:selected-ids config)))))]))

(defn- session-list-query [q track format room config]
  (str/join "&"
            (remove str/blank?
                    [(when-not (str/blank? q) (str "q=" (encoded q)))
                     (when-not (str/blank? track) (str "track=" (encoded track)))
                     (when-not (str/blank? format) (str "format=" (encoded format)))
                     (when-not (str/blank? room) (str "room=" (encoded room)))
                     (str/join "&" (embed-config-query config))])))

(defn- session-card [event session config detail-query]
  [:article.public-session-card
   [:h3
    [:a {:href (str (public-path event (str "/sessions/" (:id session)))
                    "?from=sessions"
                    (when-not (str/blank? detail-query) (str "&" detail-query)))}
     (:title session)]]
   (when (field-visible? config "description")
     (expandable-copy (:description session)))
   (when (field-visible? config "schedule")
     (session-meta session))
   (when (field-visible? config "speakers")
     [:div.public-speakers-block
      [:strong "Speakers"]
      (for [speaker (:speakers session)] (speaker-line event speaker))])
   (when (field-visible? config "tags") (chips session))
   (personal-schedule/toggle-control event (:id session)
                                     (contains? (or (:selected-ids config) #{}) (:id session)))])

(defn sessions-page
  ([event all-sessions shown-sessions tracks formats rooms q track format room]
   (sessions-page event all-sessions shown-sessions tracks formats rooms
                  q track format room {}))
  ([event all-sessions shown-sessions tracks formats rooms q track format room config]
   (shell/page-shell
     (str (events/display-name event) " — Sessions")
     [:main.ui.container.public-widget-page
      (merge (personal-schedule/browser-state event (:selected-ids config))
             (cond-> {:data-embed-theme (or (:theme config) "standard")}
               (:accent config) (assoc :style (str "border-top: 6px solid " (:accent config) ";"))))
      (masthead event :sessions "Sessions")
      [:form.public-filter-bar {:method "get" :action (public-path event "/sessions")}
       [:input {:type "search" :name "q" :value (or q "")
                :placeholder "Search session titles or speaker names"}]
       (when-let [accent (:accent config)]
         [:input {:type "hidden" :name "accent" :value accent}])
       (when-let [theme (:theme config)]
         [:input {:type "hidden" :name "theme" :value theme}])
       (when (some? (:fields config))
         [:input {:type "hidden" :name "fields"
                  :value (str/join "," (sort (:fields config)))}])
       (when-not (str/blank? track)
         [:input {:type "hidden" :name "track" :value track}])
       [:select {:name "format" :aria-label "Format"}
        [:option {:value ""} "All formats"]
        (for [candidate formats]
          [:option {:value candidate :selected (= candidate format)} candidate])]
       [:select {:name "room" :aria-label "Location"}
        [:option {:value ""} "All locations"]
        (for [candidate rooms]
          [:option {:value candidate :selected (= candidate room)} candidate])]
       [:button.ui.button {:type "submit"} "Search"]]
      [:div.public-facets {:aria-label "Filters"}
       [:span "Track:"]
       [:a.public-facet {:class (when (str/blank? track) "active")
                         :href (str (public-path event "/sessions") "?"
                                    (str/join "&" (remove str/blank?
                                                          [(when-not (str/blank? q) (str "q=" (encoded q)))
                                                           (when-not (str/blank? format) (str "format=" (encoded format)))
                                                           (when-not (str/blank? room) (str "room=" (encoded room)))
                                                           (str/join "&" (embed-config-query config))])))}
        "All"]
       (for [candidate tracks]
         [:a.public-facet
          {:class (when (= candidate track) "active")
           :href (str (public-path event "/sessions")
                      "?track=" (encoded candidate)
                      (when-not (str/blank? q) (str "&q=" (encoded q)))
                      (when-not (str/blank? format) (str "&format=" (encoded format)))
                      (when-not (str/blank? room) (str "&room=" (encoded room)))
                      (when (seq (embed-config-query config))
                        (str "&" (str/join "&" (embed-config-query config)))))}
          candidate])]
      [:div.public-result-count
       (if (seq shown-sessions)
         (str "Sessions 1 – " (count shown-sessions) " of " (count all-sessions))
         (str "Sessions 0 – 0 of " (count all-sessions)))]
      (if (seq shown-sessions)
        [:div.public-session-list
         (for [session shown-sessions]
           (session-card event session config
                         (session-list-query q track format room config)))]
        [:div.ui.segment.empty-state "No sessions match those filters."])]
     (personal-schedule/browser-script))))

(defn itrev-history-badge
  "The 'killer' hook: for a speaker who has presented at IT Revolution before,
   a compact link to their talk history. Renders nothing for speakers without
   a curated history record — purely additive."
  [speaker]
  (when-let [h (speaker-history/history-for (:name speaker))]
    (let [n (:talk-count h)]
      [:a.public-itrev-history
       {:href (:history-url h)
        :target "_blank"
        :rel "noopener"
        :title (str (:name speaker) " — " n " talk" (when-not (= 1 n) "s")
                    " in the IT Revolution archive — watch them")}
       ;; T5v2 (Gene, 2026-08-11): super subtle — the tenure line is just
       ;; another line of the card's text stack, lowercase, title typography.
       ;; "with us since <this year>" would be silly — brand-new history falls
       ;; back to the count line.
       (let [since (:since h)]
         (if since (str "(" since " alum - see archive)") (str "(" n " talk" (when-not (= 1 n) "s") " in our archive)")))])))

(defn- itrev-history-block
  "The fuller detail-page treatment: the history link plus the individual talk
   titles (each linking to its recording). Nothing for speakers without history."
  [speaker]
  (when-let [h (speaker-history/history-for (:name speaker))]
    (let [n (:talk-count h)]
      [:section.public-itrev-history-block
       [:a.public-itrev-history
        {:href (:history-url h) :rel "noopener"}
        (if-let [since (:since h)]
          (str "With us since " since " · " n " talk" (when-not (= 1 n) "s") " ▸")
          (str n " talk" (when-not (= 1 n) "s") " in our archive ▸"))]
       ;; T6: the year-by-year record — one pill per year they showed up.
       (when-let [ys (seq (:years h))]
         [:div.pit-years
          (for [y ys] [:span.pit-year (str y)])])
       (when-let [talks (seq (:talks h))]
         [:ul.public-itrev-talks
          (for [talk talks]
            [:li
             (if (str/blank? (:url talk))
               (:title talk)
               [:a {:href (:url talk) :rel "noopener"} (:title talk)])])])])))

(defn- event-card-version
  "Cache key for the generated /program/:slug/card.png.

   Derived from the card's own content, never from the clock: one Program
   surface reached through /program/:slug, /agenda/:slug/speakers, or the
   legacy /agenda/:slug/gallery alias must render the identical URL, and a
   per-render token made those entrances differ byte-for-byte. Mirrors the
   content ETag the card route already serves, so a changed card never hides
   behind an unchanged URL."
  [event]
  (-> (str/join "|" [(:id event) (:name event) (:location event)
                     (:starts-on event) (:ends-on event) (:cfp-intro event)])
      hash
      (bit-and 0x7fffffff)
      str))

(defn event-social-metadata
  [event base-url path label]
  (let [title (str (events/display-name event) " — " label)
        raw (or (:cfp-intro event) "")
        para (first (str/split raw #"\n\n"))
        desc (-> (or para "")
                 (str/replace #"\[([^\]]*)\]\([^)]*\)" "$1")
                 (str/replace #"[*_#`]" "")
                 str/trim)
        desc (if (> (count desc) 220) (str (subs desc 0 217) "…") desc)
        img (str base-url "/program/" (:slug event) "/card.png"
                 "?v=" (event-card-version event))]
    {:title title :description desc :url (str base-url path) :image img
     :type "website" :twitter-card (if img "summary_large_image" "summary")}))

(defn event-og-meta
  "Open Graph + Twitter head meta so an event's public pages unfurl richly
   (Gene, 2026-08-11 — the link IS the pitch when it lands in Discord/Slack).
   Absolute URLs required by the OG spec: `base-url` is scheme+host, `path`
   the canonical path. Description = the first intro paragraph, markdown
   links flattened, truncated near 200 chars."
  [event base-url path label]
  (let [{:keys [title description url image twitter-card]}
        (event-social-metadata event base-url path label)]
    (list
      [:meta {:property "og:type" :content "website"}]
      [:meta {:property "og:site_name" :content "Curtain Call"}]
      [:meta {:property "og:title" :content title}]
      (when (seq description) [:meta {:property "og:description" :content description}])
      [:meta {:property "og:url" :content url}]
      (when image [:meta {:property "og:image" :content image}])
      (when image [:meta {:property "og:image:width" :content "1200"}])
      (when image [:meta {:property "og:image:height" :content "630"}])
      [:meta {:name "twitter:card" :content twitter-card}]
      [:meta {:name "twitter:title" :content title}]
      (when (seq description) [:meta {:name "twitter:description" :content description}])
      (when image [:meta {:name "twitter:image" :content image}])
      [:link {:rel "alternate"
              :type "text/markdown"
              :title (str (events/display-name event) " agent index")
              :href (public-catalog/llms-path event)}])))

(defn speaker-social-metadata
  "The single Open Graph/Twitter projection for a public speaker profile and
   its organizer-facing preview. `base-url` is empty in the organizer shell and
   absolute on the public page."
  [event base-url speaker]
  (let [handle (speaker-handle speaker)
        path (str "/agenda/" (:slug event) "/speakers/" handle)
        talk (:title (first (:sessions speaker)))
        description (str/join " · "
                              (remove str/blank?
                                      [(str (:name speaker) " is speaking at "
                                            (events/display-name event))
                                       (:tagline speaker)
                                       (:company speaker)
                                       talk]))]
    {:title (str (:name speaker) " at " (events/display-name event))
     :description description
     :url (str base-url path)
     :image (str base-url path "/card.png")
     :type "website"
     :twitter-card "summary_large_image"}))

(defn smart-quotes
  "Typographic quotes at RENDER time — the data stays verbatim.
   (Gene, 2026-08-11: straight quotes in 'Jondavid \"JD\" Black' on an
   editorial page.) Pairs of double quotes curl; apostrophes curl."
  [s]
  (when s
    (-> s
        (str/replace #"\"([^\"]*)\"" "\u201C$1\u201D")
        (str/replace "'" "\u2019"))))

(defn featured-card
  "THE speaker card — the single renderer behind the CFP featured strip, the
   landing strip, and the agenda speakers grid (Gene, 2026-08-11: any change
   to one page MUST reflect on the others; that is how DRY he expects it).
   Takes a normalized map: :name :org :title :headshot, optional :detail-url
   (links photo+name when present) and :bio (expandable where a surface opts in)."
  ;; INTENT: EMB-004
  [{:keys [name org title detail-url bio] :as sp}]
  [:div.cfp-featured-card
   (if detail-url
     [:a {:href detail-url} (headshot (assoc sp :name name))]
     (headshot sp))
   [:div.cfp-featured-name
    (let [nm (smart-quotes name)]
      (if detail-url [:a {:href detail-url} nm] nm))]
   (when-not (str/blank? org) [:div.cfp-featured-org org])
   (when-not (str/blank? title) [:div.cfp-featured-title title])
   (itrev-history-badge sp)
   (when bio (expandable-copy bio))])

(defn announced-card
  "Featured card for either a raw announced entry or a public-catalog speaker."
  [sp]
  (featured-card {:name (:name sp)
                  :org (or (:org sp) (:company sp))
                  :title (or (:title sp) (:tagline sp))
                  :headshot (or (:headshot-url sp) (:headshot sp))}))

(defn- speaker-card
  ([event speaker show-bio?] (speaker-card event speaker show-bio? nil))
  ([event speaker show-bio? detail-query]
   (featured-card {:name (:name speaker) :org (:company speaker)
                   :title (:tagline speaker) :headshot (:headshot speaker)
                   :detail-url (when-let [handle (speaker-handle speaker)]
                                 (str (public-path event (str "/speakers/" handle))
                                      detail-query))
                   :bio (when show-bio? (:bio speaker))})))

(defn speakers-region [event all-speakers shown-speakers q config]
  (let [state-query (session-list-query q nil nil nil config)
        detail-query (str "?from=speakers"
                          (when-not (str/blank? state-query)
                            (str "&" state-query)))]
    [:section#speakers-results {:aria-live "polite" :aria-atomic "true"}
     [:div.public-result-count
      (if (str/blank? q)
        (str (count all-speakers) " speakers")
        (str (count shown-speakers) " of " (count all-speakers) " speakers match"))]
     (if (seq shown-speakers)
       [:div.cfp-featured-grid.public-speaker-directory
        {:aria-label "Speakers"}
        (for [speaker shown-speakers]
          (speaker-card event speaker false detail-query))]
       [:div.ui.segment.empty-state
        (str "0 of " (count all-speakers) " speakers match that search.")])]))

(defn speaker-directory-page [event speakers]
  (shell/page-shell
    (str "Speakers — " (events/display-name event))
    [:main.ui.container.public-widget-page
     (masthead event :speakers "Speakers")
     [:section.public-speaker-list-directory {:aria-label "Speakers directory"}
      [:h2 "Speakers"]
      (if (seq speakers)
        [:ol.public-speaker-list
         (for [speaker speakers]
           [:li.public-speaker-listing
            (if-let [handle (speaker-handle speaker)]
              [:a {:href (public-path event (str "/speakers/" handle))}
               (:name speaker)]
              [:span (:name speaker)])
            (when (or (not (str/blank? (:tagline speaker)))
                      (not (str/blank? (:company speaker))))
              [:span.public-speaker-list-meta
               (str/join " · " (remove str/blank? [(:tagline speaker) (:company speaker)]))])])]
        [:p.agenda-soon "Speakers will be announced soon."])]]))

;; --- The per-speaker announce page: a personal, shareable "I'm speaking!" ---
;;
;; This is a SHARE surface built for one speaker to post about themselves.
;; The HERO is that individual speaker; the GALLERY beneath is every OTHER real
;; accepted speaker of the same event ("…amongst these amazing people"). Both
;; the hero and the gallery come from `public-catalog/public-speakers` — accepted /
;; announced only. It never invents a name and never reaches into any
;; lookup/enrichment table. The Open Graph / Twitter meta feature the
;; INDIVIDUAL so the unfurl is personal to them when THEY share it.

(defn- first-talk-title [speaker]
  (some-> (:sessions speaker) first :title not-empty))

(defn- render-cfp-intro
  "Organizer copy, as safe server-rendered Markdown hiccup."
  [intro]
  (format/render-markdown intro))

(defn announce-page-url
  "The canonical, shareable URL for this hero speaker's announce page — the
   SAME url announce-meta unfurls and the share strip links to / copies. One
   helper so the two can never drift."
  [event base-url hero]
  (str base-url "/agenda/" (:slug event) "/speakers/" (speaker-handle hero) "/announce"))

(defn- announce-post-text-core
  "The plain-text announcement sentence, without the page-url — used both as
   the X share-intent `text` param (which carries the url separately, via
   `url=`) and as the stem of the full copy-post-text string below."
  [event hero]
  (let [event-name (or (:name event) "the event")
        loc (not-empty (:location event))
        dates (events/display-dates (:starts-on event) (:ends-on event))
        talk (first-talk-title hero)]
    (str "I'm speaking at " event-name
         (when loc (str " in " loc))
         (when dates (str ", " dates))
         (when talk (str " — “" talk "”"))
         ".")))

(defn announce-post-text
  "The full text for the 'Copy post text' button: the sentence plus the
   canonical page-url, so pasting it anywhere carries a working link."
  [event hero page-url]
  (str (announce-post-text-core event hero) " Join me: " page-url))

(defn- announce-linkedin-share-url [page-url]
  (str "https://www.linkedin.com/sharing/share-offsite/?url=" (encoded page-url)))

(defn- announce-x-share-url
  "Twitter/X share-intent url. `text` is the post WITHOUT the page-url baked
   in (the url travels in its own `url=` param) so the link never doubles up."
  [page-url text]
  (str "https://twitter.com/intent/tweet?url=" (encoded page-url) "&text=" (encoded text)))

(defn- share-strip [label page-url post-text post-text-core]
  [:div.announce-share
   [:span.announce-share-label label]
   [:a {:href (announce-linkedin-share-url page-url)
        :target "_blank" :rel "noopener"} "LinkedIn"]
   [:span.dot "·"]
   [:a {:href (announce-x-share-url page-url post-text-core)
        :target "_blank" :rel "noopener"} "X"]
   [:span.dot "·"]
   [:button.announce-share-copy
    {:type "button" :data-copy page-url :onclick "copyShare(this)"}
    "Copy link"]
   [:span.dot "·"]
   [:button.announce-share-copy
    {:type "button" :data-copy post-text :onclick "copyShare(this)"}
    "Copy post text"]])

(defn announce-social-metadata
  "The shared social projection used by the public page and unfurl zoo."
  [event base-url hero]
  (let [event-name (or (:name event) "the event")
        title (str "I'm speaking at " event-name "!")
        talk (first-talk-title hero)
        description (str (:name hero)
                         (when-not (str/blank? (:company hero))
                           (str " of " (:company hero)))
                         " is speaking at " event-name
                         (when talk (str " — “" talk "”")) ".")
        page-url (announce-page-url event base-url hero)
        card-url (str base-url "/agenda/" (:slug event) "/speakers/"
                      (speaker-handle hero) "/card.png?ts="
                      (System/currentTimeMillis))]
    {:title title :description description :url page-url :image card-url
     :type "profile" :twitter-card "summary_large_image"}))

(defn- announce-meta
  "Open Graph + Twitter card head hiccup so the page unfurls, personalized to
   the HERO speaker. Both image tags use a content-versioned 1200×630 card URL
   so social-network caches refresh after card content or layout changes."
  [event base-url hero]
  (let [{:keys [title description url image]}
        (announce-social-metadata event base-url hero)]
    (list
      [:meta {:name "description" :content description}]
      [:meta {:property "og:type" :content "profile"}]
      [:meta {:property "og:title" :content title}]
      [:meta {:property "og:description" :content description}]
      [:meta {:property "og:url" :content url}]
      [:meta {:property "og:site_name" :content "Curtain Call"}]
      [:meta {:property "og:image" :content image}]
      [:meta {:property "og:image:width" :content "1200"}]
      [:meta {:property "og:image:height" :content "630"}]
      [:meta {:name "twitter:card" :content "summary_large_image"}]
      [:meta {:name "twitter:title" :content title}]
      [:meta {:name "twitter:description" :content description}]
      [:meta {:name "twitter:image" :content image}])))

(defn announce-page
  "A celebratory, shareable announcement in the HERO speaker's own voice.
   Section order is deliberate (Gene, 2026-08-11 redesign): hero -> PEERS ->
   THE EVENT -> agenda. The reader clicked a face on LinkedIn/X; the very next
   beat is the OTHER faces (the peer gallery), before the event sales pitch.
   The call-for-speakers recruiting line moves to the END of the event
   section, directly under the event's own selling proposition, since it
   reads as a response to it ('convinced? here's how you get up here too').
   `hero` is one entry from `public-catalog/public-speakers`; `peers` are the rest;
   `organizer` is the chair chip data (or nil); `cfp-open?` gates the
   speaker-recruiting line and the quiet footer's CFP link; `agenda-hiccup`/
   `export-hint` are the SAME pieces the program page renders."
  [event base-url hero peers organizer cfp-open? agenda-hiccup export-hint]
  (let [event-name (or (:name event) "the event")
        talk (first-talk-title hero)
        page-url (announce-page-url event base-url hero)
        post-text (announce-post-text event hero page-url)
        post-text-core (announce-post-text-core event hero)]
    (shell/share-page-shell
      (str (:name hero) " is speaking at " event-name)
      (announce-meta event base-url hero)
      [:main.ui.container.public-widget-page.announce-page
       [:section.announce-hero
        (if (str/blank? (:headshot hero))
          [:div.announce-hero-photo.announce-hero-fallback
           {:role "img" :aria-label (str "Photo unavailable for " (:name hero))}
           [:span (or (not-empty (initials (:name hero))) "?")]]
          [:img.announce-hero-photo
           {:src (:headshot hero) :alt (str (:name hero) " headshot")}])
        [:p.announce-kicker "I'm speaking!"]
        [:h1.announce-hero-name (:name hero)]
        (when-not (str/blank? (:tagline hero))
          [:p.announce-hero-tagline (:tagline hero)])
        (when-not (str/blank? (:company hero))
          [:p.announce-hero-company (:company hero)])
        (when talk
          [:p.announce-hero-talk "“" talk "”"])
        [:p.announce-hero-copy
         "I'm so excited to be speaking at " [:strong event-name]
         (when-let [loc (not-empty (:location event))] (str " in " loc))
         (when-let [dates (events/display-dates (:starts-on event) (:ends-on event))]
           (str " — " dates))
         ". I'd love to see you there."]
        (when (:website-url event)
          [:a.ui.primary.button.announce-hero-cta
           {:href (:website-url event) :rel "noopener"}
           "Go here to register ↗"])
        (share-strip "Share your announcement:" page-url post-text post-text-core)]
       [:section.announce-peers
        [:div.cfp-section-title "The lineup"]
        [:h2.announce-peers-head "…amongst these amazing people"]
        (if (seq peers)
          [:div.cfp-featured-grid
           (for [speaker peers] (speaker-card event speaker false))]
          [:p.announce-peers-empty
           "More of the lineup is being announced soon."])]
       [:section.announce-event
        [:div.cfp-section-title "The event"]
        (when-let [img (get-in event [:settings :hero-image-url])]
          [:img.announce-event-photo {:src img :alt event-name :loading "lazy"}])
        [:h3.announce-event-name (events/display-name event)]
        (when-let [intro (format/not-blank (:cfp-intro event))]
          [:div.cfp-intro (render-cfp-intro intro)])
        (when organizer
          [:div.announce-organizer
           [:span.rk "Organized by"]
           [:a.cfp-organizer-chip {:href (:url organizer)}
            (when-let [img (:headshot-url organizer)]
              [:img.cfp-organizer-photo {:src img :alt (:name organizer) :loading "lazy"}])
            [:span.cfp-organizer-name (:name organizer)]]
           (when-let [t (:tagline organizer)]
             [:div.cfp-rail-attr t])])
        (when cfp-open?
          [:p.announce-cfp-line
           "Want to be up here? "
           [:a {:href (str "/cfp/" (:slug event))} "The call for speakers is open →"]])]
       [:section.announce-program
        [:div.cfp-section-title "The agenda"]
        agenda-hiccup
        export-hint
        [:div.announce-pitch
         [:p.announce-pitch-line (events/display-name event)]
         (when (:website-url event)
           [:a.ui.primary.button.announce-hero-cta
            {:href (:website-url event) :rel "noopener"}
            "Go here to register ↗"])]]
       [:footer.public-foot
        [:div.pf-links
         [:a {:href (str "/program/" (:slug event))} "The program"]
         [:span.dot "·"]
         (when cfp-open?
           (list [:a {:href (str "/cfp/" (:slug event))} "Call for speakers"]
                 [:span.dot "·"]))
         (when (:website-url event)
           [:a {:href (:website-url event) :rel "noopener"} "Official event website ↗"])]
        [:div.pf-credit "Made with Curtain Call — the CFP, speaker & program tool."]]])))

(def organizer-post-text-core
  "I'm so proud that the Enterprise AI Summit Charlotte is happening. We have so many amazing announced speakers, and thanks to swyx's Kill My SaaS project, we have a CFP open to potentially round out a couple of open slots that we have.")

(defn organizer-brag-page
  "The event-level twin of the speaker announce page, composed from its shell,
   share strip, event pitch, and the canonical public program roster."
  [event base-url roster cfp-open?]
  (let [event-name (events/display-name event)
        page-path (str "/program/" (:slug event) "/announce")
        page-url (str base-url page-path)
        post-text (str organizer-post-text-core " " page-url)]
    (shell/share-page-shell
      (str "Proud to organize " event-name)
      (event-og-meta event base-url page-path "The announced lineup")
      [:main.ui.container.public-widget-page.announce-page
       [:section.announce-event
        [:div.cfp-section-title "The event"]
        (when-let [img (get-in event [:settings :hero-image-url])]
          [:img.announce-event-photo
           {:src (shell/versioned img) :alt event-name}])
        [:h1.announce-event-name event-name]
        (when-let [intro (format/not-blank (:cfp-intro event))]
          [:div.cfp-intro (render-cfp-intro intro)])
        (share-strip "Share this event:" page-url post-text organizer-post-text-core)]
       [:section.announce-peers
        [:div.cfp-section-title "The announced lineup"]
        [:h2.announce-peers-head
         (str (count roster) " amazing announced speakers")]
        [:div.cfp-featured-grid
         (for [speaker roster] (speaker-card event speaker false))]
        (when cfp-open?
          [:p.announce-cfp-line
           "A couple of open slots remain. "
           [:a {:href (str "/cfp/" (:slug event))}
            "The call for speakers is open →"]])]
       [:footer.public-foot
        [:div.pf-links
         [:a {:href (str "/program/" (:slug event))} "The program"]
         (when cfp-open?
           (list [:span.dot "·"]
                 [:a {:href (str "/cfp/" (:slug event))} "Call for speakers"]))]
        [:div.pf-credit "Made with Curtain Call — the CFP, speaker & program tool."]]])))

(defn speaker-detail-page
  ([event speaker] (speaker-detail-page event speaker {}))
  ([event speaker {:keys [from q base-url] :as state}]
   (let [config (select-keys state [:accent :theme :fields])
         state-query (session-list-query q nil nil nil config)
         back-href (str "/program/" (:slug event)
                        (when-not (str/blank? state-query)
                          (str "?" state-query))
                        "#speakers")
         social (speaker-social-metadata event (or base-url "") speaker)]
     (shell/share-page-shell
       (str (:name speaker) " — " (events/display-name event))
       (shell/social-meta social)
       [:main.ui.container.public-widget-page
        (cond-> {:data-embed-theme (or (:theme config) "standard")}
          (:accent config) (assoc :style (str "border-top: 6px solid "
                                              (:accent config) ";")))
        (masthead event :speakers "Speaker")
        [:a.public-back-link {:href back-href}
         "← Back to speakers"]
        [:article.public-speaker-detail
         (headshot speaker)
         [:div
          [:h2 (:name speaker)]
          (when-not (str/blank? (:tagline speaker)) [:p.public-speaker-title (:tagline speaker)])
          (when-not (str/blank? (:company speaker)) [:p.public-speaker-company (:company speaker)])
          (when (field-visible? config "description")
            (expandable-copy (:bio speaker)))
          (itrev-history-block speaker)]]
        [:section.public-speaker-sessions
         [:h2 (str "Sessions (" (count (:sessions speaker)) ")")]
         (for [session (:sessions speaker)]
           [:article.public-speaker-session
            [:h3
             [:a {:href (public-path event (str "/sessions/" (:id session)))} (:title session)]]
            (when (field-visible? config "schedule")
              (session-meta session))])]]))))

(defn session-detail-page
  ([event session] (session-detail-page event session {}))
  ([event session {:keys [from day q track format room] :as state}]
   (let [from-agenda? (and (= "agenda" from) (= (str (:day-key session)) day))
         from-gallery? (= "gallery" from)
         from-sessions? (= "sessions" from)
         config (select-keys state [:accent :theme :fields])
         sessions-query (session-list-query q track format room config)
         sessions-href (str (public-path event "/sessions")
                            (when-not (str/blank? sessions-query)
                              (str "?" sessions-query)))
         back-href (cond
                     from-sessions?
                     sessions-href

                     from-gallery?
                     (str (public-path event "/gallery")
                          (when-not (str/blank? q)
                            (str "?q=" (encoded q))))

                     from-agenda?
                     (str "/program/" (:slug event)
                          "?day=" (encoded day) "#agenda-day-" (encoded day))

                     :else
                     (str "/program/" (:slug event)))
         back-label (cond
                      from-sessions? "← Back to sessions"
                      from-gallery? "← Back to speaker gallery"
                      :else "← Back to agenda")]
     (shell/page-shell
       (str (:title session) " — " (events/display-name event))
       [:main.ui.container.public-widget-page
        (cond-> {:data-embed-theme (or (:theme config) "standard")}
          (:accent config) (assoc :style (str "border-top: 6px solid "
                                              (:accent config) ";")))
        (masthead event :sessions "Session Details")
        [:nav.public-detail-return {:aria-label "Return to public program"}
         [:a.public-back-link {:href back-href} back-label]
         [:span " · "]
         [:a {:href (if from-sessions? sessions-href
                        (public-path event "/sessions"))}
          "Sessions list"]]
        [:article.public-session-detail
         [:h2 (:title session)]
         [:div.public-detail-tabs
          [:span.active "Session Details"]
          [:span "Subsessions (0)"]]
         (when (field-visible? config "schedule")
           (session-meta session))
         (when (field-visible? config "description")
           (expandable-copy (:description session)))
         (when (field-visible? config "tags")
           (chips session))
         (when (field-visible? config "speakers")
           [:section.public-speakers-block
            [:h3 "Speakers"]
            (for [speaker (:speakers session)] (speaker-line event speaker))])]]))))

;; INTENT: EMB-005 — the one canonical Program surface every public entrance
;; (/program/:slug, /agenda/:slug/speakers, the legacy /agenda/:slug/gallery)
;; renders: the shared Speakers directory, its server-owned filter, the whole
;; configured-day Agenda stack, and the calendar exports.
(defn program-page
  "THE public face of an event: one stacked printed program. T3 masthead + facts rail, then SPEAKERS (the shared
   featured-card grid), then AGENDA (or the published-soon note). `agenda-days`
   and the export hint are composed in from views.schedule."
  ;; INTENT: EMB-005
  [event base-url all-speakers shown-speakers q config organizer agenda-hiccup export-hint & [selected-ids]]
  (shell/share-page-shell
    (str (events/display-name event) " — Program")
    (event-og-meta event base-url (str "/program/" (:slug event)) "Program")
    (shell/datastar-script)
    [:main.ui.container.public-widget-page
     (merge (personal-schedule/browser-state event (or selected-ids #{}))
            (cond-> {:data-embed-theme (or (:theme config) "standard")
                     :data-star-signals__ifmissing (json/write-str {:spkq (or q "")})}
              (:accent config) (assoc :style (str "border-top: 6px solid "
                                                  (:accent config) ";"))))
     [:div.cfp-masthead
      [:div.cfp-kicker "The program"]
      [:h1.masthead-title (events/display-name event)]
      ;; No thesis copy on the program page: the rail runs as a compact
      ;; horizontal row under the title instead of a lonely two-column void.
      [:div.cfp-rail.cfp-rail-row
       (when organizer
         [:div.rl
          [:span.rk "Organizer"]
          [:a.cfp-organizer-chip {:href (:url organizer)}
           (when-let [img (:headshot-url organizer)]
             [:img.cfp-organizer-photo {:src img :alt (:name organizer) :loading "lazy"}])
           [:span.cfp-organizer-name (:name organizer)]]
          (when-let [t (:tagline organizer)]
            [:div.cfp-rail-attr t])])
       [:div.rl [:span.rk "Speak"]
        [:a {:href (str "/cfp/" (:slug event))} "The call for papers is open ↗"]]
       (when (:website-url event)
         [:div.rl [:span.rk "Links"]
          [:a {:href (:website-url event) :target "_blank" :rel "noopener"}
           "Official event website ↗"]])]]
     [:nav.public-widget-nav.public-program-nav {:aria-label "Explore the program"}
      [:a.program-nav-speakers {:href (str "/program/" (:slug event) "#speakers")}
       "Speakers"]
      [:a.program-nav-agenda {:href (str "/program/" (:slug event) "#agenda")}
       "Agenda"]
      [:a.program-nav-itinerary {:href (public-path event "/itinerary")}
       "Itinerary"]
      [:a {:href (public-path event "/my") :data-my-schedule-link ""} "My schedule"]
      [:a.program-nav-resources {:href (str "/program/" (:slug event) "/resources")}
       "Resources"]]
     [:section#speakers.public-program-section
      [:h2.public-program-heading "Speakers"]
      [:a.public-speaker-directory-link {:href (public-path event "/directory")}
       "List view"]
      [:form.public-filter-bar {:method "get"
                                :action (str "/program/" (:slug event) "#speakers")}
       [:input (merge {:type "search" :name "q" :value (or q "")
                       :aria-label "Filter speakers"
                       :autofocus true
                       :placeholder "Search name, title, organization, bio, or session"
                       :data-star-on:input__debounce.50ms
                       (str "@post('" (public-path event "/speakers/filter") "')")}
                      (ds/bind :spkq))]
       (when-let [accent (:accent config)]
         [:input {:type "hidden" :name "accent" :value accent}])
       (when-let [theme (:theme config)]
         [:input {:type "hidden" :name "theme" :value theme}])
       (when (some? (:fields config))
         [:input {:type "hidden" :name "fields"
                  :value (str/join "," (sort (:fields config)))}])
       [:button.ui.button {:type "submit"} "Search"]]
      (speakers-region event all-speakers shown-speakers q config)]
     [:section#agenda.public-program-section
      [:h2.public-program-heading "Agenda"]
      agenda-hiccup
      export-hint]
     [:nav.public-program-resources {:aria-label "Program resources"}
      [:a {:href (public-catalog/llms-path event) :type "text/markdown"}
       "AI agent index"]
      " · "
      [:a {:href "/llms.txt" :type "text/markdown"} "All events index"]]]
    (personal-schedule/browser-script)))
