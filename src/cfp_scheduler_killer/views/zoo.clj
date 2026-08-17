(ns cfp-scheduler-killer.views.zoo
  "Internal element-gallery ('zoo') pages — an organizer-only surface that
   PROVES how our pages behave rather than just describing it, with the
   living documentation sitting right next to the proof (bead
   sessionize-sched-killer-92x). /admin/zoo/social-sharing is room one:
   LinkedIn/Slack unfurl previews of a real page on this host, plus the
   sharing tips."
  (:require
   [cfp-scheduler-killer.views.organizer-layout :as organizer-layout]))

(defn- domain-of [url]
  (when url
    (try (.getHost (java.net.URI. url))
         (catch Exception _ nil))))

(defn- meta-table
  "The raw og:*/twitter:* tags this page found, in document order."
  [tags]
  [:table.ui.celled.table.zoo-meta-table
   [:thead [:tr [:th "property"] [:th "content"]]]
   [:tbody
    (if (seq tags)
      (for [{:keys [property content]} tags]
        [:tr [:td [:code property]] [:td content]])
      [:tr [:td {:colspan 2} [:span.field-hint "No og:*/twitter:* tags found."]]])]])

(defn- linkedin-card
  "A large bordered card: image on top, bold title, gray description, source
   domain — the shape LinkedIn renders when og:image clears the big-card bar."
  [{:keys [meta image-url]}]
  (let [title (get meta "og:title")
        desc (get meta "og:description")
        page-url (get meta "og:url")
        domain (or (domain-of page-url) (domain-of image-url))]
    [:div.zoo-card-linkedin
     (when image-url [:img.zc-li-image {:src image-url :alt ""}])
     [:div.zc-li-body
      [:div.zc-li-title (or title "(no og:title)")]
      [:div.zc-li-desc (or desc "(no og:description)")]
      [:div.zc-li-domain (or domain "")]]]))

(defn- slack-card
  "Readable Slack attachment with the complete large-card artwork below copy."
  [{:keys [meta image-url]}]
  (let [title (or (get meta "twitter:title") (get meta "og:title"))
        desc (or (get meta "twitter:description") (get meta "og:description"))
        page-url (get meta "og:url")
        domain (domain-of page-url)]
    [:div.zoo-card-slack
     [:div.zc-sl-body
      (when domain [:div.zc-sl-domain domain])
      [:div.zc-sl-title (or title "(no title)")]
      [:div.zc-sl-desc (or desc "(no description)")]
      (when image-url [:img.zc-sl-image {:src image-url :alt ""}])]]))

(defn- x-card
  "X summary_large_image presentation using the same projected metadata."
  [{:keys [meta image-url]}]
  (let [title (or (get meta "twitter:title") (get meta "og:title"))
        desc (or (get meta "twitter:description") (get meta "og:description"))
        domain (domain-of (get meta "og:url"))]
    [:div.zoo-card-x
     [:div.zc-x-card-type (or (get meta "twitter:card") "summary")]
     (when image-url [:img.zc-x-image {:src image-url :alt ""}])
     [:div.zc-x-body
      [:div.zc-x-title (or title "(no title)")]
      [:div.zc-x-desc (or desc "(no description)")]
      (when domain [:div.zc-x-domain domain])]]))

(defn- metadata-preview [metadata preview-image-url]
  {:meta {"og:title" (:title metadata)
          "og:description" (:description metadata)
          "og:url" (:url metadata)
          "og:image" (:image metadata)
          "twitter:title" (:title metadata)
          "twitter:description" (:description metadata)
          "twitter:card" (:twitter-card metadata)}
   :image-url (or preview-image-url (:image metadata))})

(defn- specimen [{:keys [id label metadata preview-image-url]}]
  [:section.zoo-section.zoo-specimen {:id id}
   [:h2 label]
   [:div.zoo-preview-row
    [:div.zoo-preview-col [:h3 "LinkedIn-style"]
     (linkedin-card (metadata-preview metadata preview-image-url))]
    [:div.zoo-preview-col [:h3 "Slack-style"]
     (slack-card (metadata-preview metadata preview-image-url))]
    [:div.zoo-preview-col [:h3 "X-style"]
     (x-card (metadata-preview metadata preview-image-url))]]])

(defn- dims-line [{:keys [dims verdict]}]
  [:p.zoo-dims
   (if dims
     (str "og:image measured " (:width dims) "×" (:height dims) " px — " verdict)
     verdict)])

(defn- preview-result
  "nil with no ?url=; a quiet warning on a refused/failed fetch; otherwise
   both card previews, the dimension verdict, and the raw tag table."
  [{:keys [target preview]}]
  (cond
    (nil? target) nil

    (:error preview)
    [:div.ui.warning.message (:error preview)]

    :else
    (list
      [:div.zoo-preview-row
       [:div.zoo-preview-col
        [:h3 "LinkedIn-style"]
        (linkedin-card preview)]
       [:div.zoo-preview-col
        [:h3 "Slack-style"]
        (slack-card preview)]]
      (dims-line preview)
      [:h3 "Raw tags"]
      (meta-table (:tags preview)))))

(defn- sharing-tips
  "Living documentation, not a wiki page nobody opens — right next to the
   proof it describes."
  []
  [:section.zoo-section
   [:h2 "Sharing tips"]
   [:ul.zoo-tips
    [:li "LinkedIn gives the big landscape card only when "
     [:code "og:image"] " is ≥1200×627 — smaller or square images render as "
     "a small square thumb."]
    [:li "Preview and cache-bust with the "
     [:a {:href "https://www.linkedin.com/post-inspector/"
          :target "_blank" :rel "noopener"} "LinkedIn Post Inspector"]
     " — LinkedIn caches unfurls for about a week."]
    [:li "localhost URLs never unfurl on a real network — always share the "
     "prod URL."]
    [:li "Slack reads the same " [:code "og:"] " tags; "
     [:code "twitter:card summary_large_image"] " controls X's layout."]
    [:li [:code "og:image:width"] " / " [:code "og:image:height"]
     " meta helps first-share renders (no re-fetch needed to size the card)."]
    [:li "The announce page's share strip pre-writes the post text — "
     "\"Copy post text\" is the fastest path to a good post."]]])

(defn- zoo-index-stub []
  [:aside.zoo-index-stub
   [:h2 "Zoo index"]
   [:p "This is the first zoo room. Planned rooms (not yet built):"]
   [:ul
    [:li "Tiles"]
    [:li "Section headers"]
    [:li "Footers"]]])

(defn social-sharing-page
  [{:keys [person base-url target preview specimens]}]
  (organizer-layout/organizer-shell
    "Zoo — Social sharing"
    {:active :zoo :person person}
    (organizer-layout/header
      "Zoo — Social sharing"
      "Proof of how our pages unfurl on LinkedIn and Slack, plus the sharing tips as living documentation.")
    [:div.zoo-page
     [:nav.zoo-jump {:aria-label "Jump to social-sharing specimen"}
      [:span "Jump to:"]
      (for [[href label] [["#main-homepage" "Main homepage"]
                          ["#cfp" "CFP"]
                          ["#speaker-brag" "Speaker brag"]
                          ["#event-program" "Event/program"]
                          ["#custom-url-inspector" "Custom URL inspector"]]]
        [:a {:href href} label])]
     [:section.zoo-section
      [:h2 "Production unfurl specimens"]
      [:p.field-hint "These cards use the same metadata projections as the public pages."]]
     (for [item specimens] (specimen item))
     [:section#custom-url-inspector.zoo-section
      [:h2 "Unfurl preview"]
      [:form.ui.form {:method "get" :action "/admin/zoo/social-sharing"}
       [:div.field
        [:label {:for "zoo-url"} "URL to preview (must be on this host)"]
        [:input {:type "text" :id "zoo-url" :name "url"
                 :value (or target "")
                 :placeholder (str base-url "/agenda/<slug>/speakers/<id>/announce")}]]
       [:button.ui.primary.button {:type "submit"} "Preview"]]
      (when-not target
        [:p.field-hint
         "Try this: "
         [:a {:href (str "/admin/zoo/social-sharing?url=" base-url
                         "/agenda/enterprise-ai-summit-charlotte-2026/speakers/mik-kersten/announce")}
          "the live Mik Kersten announce page"]])
      (preview-result {:target target :preview preview})]
     (sharing-tips)
     (zoo-index-stub)]))
