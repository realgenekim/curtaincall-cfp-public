(ns cfp-scheduler-killer.og-card
  "Server-rendered Open Graph cards using only bundled classpath fonts."
  (:require
   [cfp-scheduler-killer.events :as events]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.awt BasicStroke Color Font Graphics2D RenderingHints)
   (java.awt.font TextAttribute)
   (java.awt.geom Ellipse2D$Double)
   (java.awt.image BufferedImage)
   (java.io ByteArrayInputStream ByteArrayOutputStream)
   (javax.imageio ImageIO)))

;; Distroless production images have no system fonts or fontconfig.
(System/setProperty "java.awt.headless" "true")

(def ^:private card-width 1200)
(def ^:private card-height 630)

;; Mirrors app.css :root tokens: --paper, --paper-deep, --ink, --muted,
;; --focus-line, and --line.
(def ^:private paper (Color/decode "#FAF7F2"))
(def ^:private paper-deep (Color/decode "#F1EADD"))
(def ^:private ink (Color/decode "#221E17"))
(def ^:private muted (Color/decode "#7C7264"))
(def ^:private kicker-gold (Color/decode "#B9924A"))
(def ^:private line (Color/decode "#E9E1D5"))

(defn- load-font [resource-path]
  (let [resource (or (io/resource resource-path)
                     (throw (ex-info "Bundled Open Graph font is missing"
                                     {:resource resource-path})))]
    (with-open [input (io/input-stream resource)]
      (Font/createFont Font/TRUETYPE_FONT input))))

(defonce ^:private fraunces-font
  (delay (load-font "fonts/Fraunces-variable.ttf")))

(defonce ^:private source-sans-font
  (delay (load-font "fonts/SourceSans3-variable.ttf")))

(defn- derived-font
  ([font-source size weight]
   (derived-font font-source size weight nil))
  ([font-source size weight posture]
   (.deriveFont
     ^Font @font-source
     (cond-> {TextAttribute/SIZE (float size)
              TextAttribute/WEIGHT weight}
       posture (assoc TextAttribute/POSTURE posture)))))

(defn- enable-antialiasing! [^Graphics2D graphics]
  (.setRenderingHint graphics
                     RenderingHints/KEY_ANTIALIASING
                     RenderingHints/VALUE_ANTIALIAS_ON)
  (.setRenderingHint graphics
                     RenderingHints/KEY_TEXT_ANTIALIASING
                     RenderingHints/VALUE_TEXT_ANTIALIAS_ON)
  (.setRenderingHint graphics
                     RenderingHints/KEY_RENDERING
                     RenderingHints/VALUE_RENDER_QUALITY))

(defn- draw-tracked-text!
  [^Graphics2D graphics text x baseline letter-spacing]
  (let [metrics (.getFontMetrics graphics)]
    (loop [characters (seq (str text))
           cursor (double x)]
      (when-let [character (first characters)]
        (let [glyph (str character)]
          (.drawString graphics glyph (float cursor) (float baseline))
          (recur (next characters)
                 (+ cursor (.stringWidth metrics glyph) letter-spacing)))))))

(defn- ellipsize [metrics text max-width]
  (let [closing-quote? (str/ends-with? text "”")
        suffix (if closing-quote? "…”" "…")
        text (if closing-quote? (subs text 0 (dec (count text))) text)
        words (vec (str/split (str/trim text) #"\s+"))]
    (loop [candidate-words words]
      (if (empty? candidate-words)
        (if (str/starts-with? text "“") (str "“" suffix) suffix)
        (let [candidate (str (str/join " " candidate-words) suffix)]
          (if (<= (.stringWidth metrics candidate) max-width)
            candidate
            (recur (pop candidate-words))))))))

(defn- wrap-lines [metrics text max-width max-lines]
  (loop [remaining (str/split (str/trim text) #"\s+")
         lines []
         current ""]
    (if-let [word (first remaining)]
      (let [candidate (if (str/blank? current) word (str current " " word))]
        (cond
          (<= (.stringWidth metrics candidate) max-width)
          (recur (next remaining) lines candidate)

          (= (count lines) (dec max-lines))
          (conj lines
                (ellipsize metrics
                           (str/join " " (concat [current] remaining))
                           max-width))

          (str/blank? current)
          (recur (next remaining) (conj lines (ellipsize metrics word max-width)) "")

          :else
          (recur remaining (conj lines current) "")))
      (cond-> lines
        (not (str/blank? current)) (conj current)))))

(defn- fit-title-layout [^Graphics2D graphics title]
  (let [full-text (str "“" (str/trim title) "”")
        candidates [{:font-size 36 :max-lines 2 :line-height 49 :first-baseline 334}
                    {:font-size 34 :max-lines 3 :line-height 45 :first-baseline 316}
                    {:font-size 32 :max-lines 3 :line-height 43 :first-baseline 318}
                    {:font-size 30 :max-lines 4 :line-height 41 :first-baseline 308}
                    {:font-size 28 :max-lines 4 :line-height 39 :first-baseline 310}
                    {:font-size 26 :max-lines 5 :line-height 36 :first-baseline 303}
                    {:font-size 24 :max-lines 5 :line-height 34 :first-baseline 306}]
        measured
        (mapv
          (fn [{:keys [font-size max-lines] :as candidate}]
            (let [font (derived-font fraunces-font font-size
                                     TextAttribute/WEIGHT_REGULAR
                                     TextAttribute/POSTURE_OBLIQUE)
                  metrics (.getFontMetrics graphics font)
                  lines (wrap-lines metrics full-text 640 max-lines)]
              (assoc candidate
                     :font font
                     :lines lines
                     :complete? (= full-text
                                   (str/join " " lines)))))
          candidates)]
    (or (some #(when (:complete? %) %) measured)
        (last measured))))

(defn- initials [name]
  (->> (str/split (str/trim (or name "")) #"\s+")
       (take 2)
       (keep first)
       (apply str)
       str/upper-case
       (#(or (not-empty %) "?"))))

(defn- decoded-image [headshot-bytes]
  (when (and headshot-bytes (pos? (alength ^bytes headshot-bytes)))
    (try
      (ImageIO/read (ByteArrayInputStream. ^bytes headshot-bytes))
      (catch Exception _ nil))))

(defn- draw-headshot!
  [^Graphics2D graphics speaker headshot-bytes]
  (let [diameter 340
        x 780
        y 145
        circle (Ellipse2D$Double. x y diameter diameter)
        photo (decoded-image headshot-bytes)]
    (if photo
      (let [old-clip (.getClip graphics)
            image-width (.getWidth photo)
            image-height (.getHeight photo)
            scale (max (/ diameter (double image-width))
                       (/ diameter (double image-height)))
            drawn-width (* image-width scale)
            drawn-height (* image-height scale)
            drawn-x (+ x (/ (- diameter drawn-width) 2.0))
            drawn-y (+ y (/ (- diameter drawn-height) 2.0))]
        (.clip graphics circle)
        (.drawImage graphics photo
                    (int drawn-x) (int drawn-y)
                    (int (Math/ceil drawn-width)) (int (Math/ceil drawn-height))
                    nil)
        (.setClip graphics old-clip))
      (do
        (.setColor graphics paper-deep)
        (.fill graphics circle)
        (.setColor graphics muted)
        (.setFont graphics
                  (derived-font fraunces-font 104 TextAttribute/WEIGHT_SEMIBOLD))
        (let [label (initials (:name speaker))
              metrics (.getFontMetrics graphics)
              label-x (+ x (/ (- diameter (.stringWidth metrics label)) 2.0))
              label-y (+ y (/ (+ diameter (.getAscent metrics)
                                 (- (.getDescent metrics)))
                              2.0))]
          (.drawString graphics label (float label-x) (float label-y)))))
    (.setColor graphics line)
    (.setStroke graphics (BasicStroke. 3.0))
    (.draw graphics circle)))

(defn- talk-title [speaker]
  (or (not-empty (:talk-title speaker))
      (some-> (:sessions speaker) first :title not-empty)))

(defn card-version
  "Content-derived cache token for the rendered PNG and its unfurl URL.
   Change the revision keyword whenever renderer geometry or styling changes."
  [event speaker]
  (Integer/toUnsignedString
    (hash [:full-title-v2
           (select-keys event [:id :name :location :starts-on :ends-on :updated-at])
           (select-keys speaker [:id :name :tagline :company :talk-title
                                 :headshot :updated-at])
           (talk-title speaker)])
    36))

(defn- fit-name-font [^Graphics2D graphics name]
  (let [font (derived-font fraunces-font 72 TextAttribute/WEIGHT_SEMIBOLD)
        width (.stringWidth (.getFontMetrics graphics font) name)]
    (if (> width 650)
      (derived-font fraunces-font
                    (max 50 (* 72.0 (/ 650.0 width)))
                    TextAttribute/WEIGHT_SEMIBOLD)
      font)))

(defn render-card
  "Render one deterministic 1200×630 PNG. Headshot fetching is deliberately
   outside this pure rendering boundary; nil or undecodable bytes use initials."
  ^bytes [event speaker headshot-bytes-or-nil]
  (let [image (BufferedImage. card-width card-height BufferedImage/TYPE_INT_RGB)
        graphics (.createGraphics image)
        speaker-name (or (not-empty (:name speaker)) "Speaker")
        role-line (str/join " · "
                            (remove str/blank?
                                    [(or (:tagline speaker) "")
                                     (or (:company speaker) "")]))
        footer (str/join " · "
                         (remove str/blank?
                                 [(str/upper-case (or (:name event) "EVENT"))
                                  (or (:location event) "")
                                  (or (events/display-dates (:starts-on event)
                                                            (:ends-on event))
                                      "")]))]
    (try
      (enable-antialiasing! graphics)
      (.setColor graphics paper)
      (.fillRect graphics 0 0 card-width card-height)

      (.setColor graphics kicker-gold)
      (.setFont graphics
                (derived-font source-sans-font 26 TextAttribute/WEIGHT_BOLD))
      (draw-tracked-text! graphics "I'M SPEAKING!" 78 76 3.2)
      (.setStroke graphics (BasicStroke. 3.0))
      (.drawLine graphics 78 95 174 95)

      (.setColor graphics ink)
      (.setFont graphics (fit-name-font graphics speaker-name))
      (.drawString graphics speaker-name (float 74) (float 188))

      (when-not (str/blank? role-line)
        (.setColor graphics muted)
        (.setFont graphics
                  (derived-font source-sans-font 30 TextAttribute/WEIGHT_SEMIBOLD))
        (.drawString graphics role-line (float 78) (float 245)))

      (when-let [title (talk-title speaker)]
        (.setColor graphics ink)
        (let [{:keys [font lines line-height first-baseline]}
              (fit-title-layout graphics title)]
          (.setFont graphics font)
          (doseq [[index text] (map-indexed vector lines)]
            (.drawString graphics text
                         (float 78)
                         (float (+ first-baseline (* index line-height)))))))

      (draw-headshot! graphics speaker headshot-bytes-or-nil)

      (.setColor graphics muted)
      (.setFont graphics
                (derived-font source-sans-font 25 TextAttribute/WEIGHT_SEMIBOLD))
      (draw-tracked-text! graphics footer 78 575 1.0)

      (.setFont graphics
                (derived-font fraunces-font 25 TextAttribute/WEIGHT_SEMIBOLD))
      (let [mark "Curtain Call"
            metrics (.getFontMetrics graphics)]
        (.drawString graphics mark (float (- 1122 (.stringWidth metrics mark))) (float 592)))
      (finally
        (.dispose graphics)))
    (with-open [output (ByteArrayOutputStream.)]
      (ImageIO/write image "png" output)
      (.toByteArray output))))

(defn event-card-copy
  ([event] (event-card-copy event (:speaker-count event)))
  ([event speaker-count]
   {:kicker "CURTAIN CALL · EVENT"
    :title (or (:name event) "Curtain Call")
    :subtitle (str/join " · "
                        (remove str/blank?
                                [(or (:location event) "")
                                 (or (events/display-dates (:starts-on event)
                                                           (:ends-on event)) "")
                                 (when (some? speaker-count)
                                   (str speaker-count " announced speakers"))]))
    :proposition (or (some-> (:cfp-intro event) (str/split #"\n\n") first)
                     "Calls for papers, without the paperwork")}))

(defn- local-event-hero [event]
  (let [path (get-in event [:settings :hero-image-url])]
    (when (and (string? path) (str/starts-with? path "/images/"))
      (try
        (some-> (io/resource (str "public" path)) ImageIO/read)
        (catch Exception _ nil)))))

(defn- draw-contained-image! [^Graphics2D graphics image x y width height]
  (let [scale (min (/ width (double (.getWidth image)))
                   (/ height (double (.getHeight image))))
        drawn-width (* (.getWidth image) scale)
        drawn-height (* (.getHeight image) scale)
        drawn-x (+ x (/ (- width drawn-width) 2.0))
        drawn-y (+ y (/ (- height drawn-height) 2.0))]
    (.drawImage graphics image (int drawn-x) (int drawn-y)
                (int drawn-width) (int drawn-height) nil)))

(def event-hero-box {:x 80 :y 180 :width 1040 :height 406})

(defn- render-brand-card [copy hero]
  (let [image (BufferedImage. card-width card-height BufferedImage/TYPE_INT_RGB)
        graphics (.createGraphics image)]
    (try
      (enable-antialiasing! graphics)
      (.setColor graphics paper)
      (.fillRect graphics 0 0 card-width card-height)
      (if hero
        (do
          (.setColor graphics kicker-gold)
          (.setFont graphics (derived-font source-sans-font 20 TextAttribute/WEIGHT_BOLD))
          (draw-tracked-text! graphics (:kicker copy) 80 36 2.5)
          (.setColor graphics ink)
          (.setFont graphics (derived-font fraunces-font 52 TextAttribute/WEIGHT_SEMIBOLD))
          (.drawString graphics (:title copy) (float 80) (float 108))
          (when-not (str/blank? (:subtitle copy))
            (.setColor graphics muted)
            (.setFont graphics (derived-font source-sans-font 27 TextAttribute/WEIGHT_SEMIBOLD))
            (.drawString graphics (:subtitle copy) (float 80) (float 154)))
          (let [{:keys [x y width height]} event-hero-box]
            (draw-contained-image! graphics hero x y width height)))
        (do
          (.setColor graphics kicker-gold)
          (.setFont graphics (derived-font source-sans-font 26 TextAttribute/WEIGHT_BOLD))
          (draw-tracked-text! graphics (:kicker copy) 78 82 3.0)
          (.setStroke graphics (BasicStroke. 3.0))
          (.drawLine graphics 78 101 174 101)
          (.setColor graphics ink)
          (.setFont graphics (derived-font fraunces-font 68 TextAttribute/WEIGHT_SEMIBOLD))
          (doseq [[index line] (map-indexed vector
                                            (wrap-lines (.getFontMetrics graphics)
                                                        (:title copy) 1030 2))]
            (.drawString graphics line (float 78) (float (+ 205 (* index 78)))))
          (when-not (str/blank? (:subtitle copy))
            (.setColor graphics muted)
            (.setFont graphics (derived-font source-sans-font 30 TextAttribute/WEIGHT_SEMIBOLD))
            (.drawString graphics (:subtitle copy) (float 80) (float 385)))
          (.setColor graphics ink)
          (.setFont graphics (derived-font source-sans-font 30 TextAttribute/WEIGHT_REGULAR))
          (doseq [[index line] (map-indexed vector
                                            (wrap-lines (.getFontMetrics graphics)
                                                        (:proposition copy) 980 2))]
            (.drawString graphics line (float 80) (float (+ 460 (* index 40)))))))
      (.setColor graphics kicker-gold)
      (.fillRect graphics 0 600 card-width 30)
      (finally (.dispose graphics)))
    (with-open [output (ByteArrayOutputStream.)]
      (ImageIO/write image "png" output)
      (.toByteArray output))))

(defn render-event-card
  "Render an honest event/CFP card with no speaker-only visual semantics."
  (^bytes [event]
   (render-event-card event (:speaker-count event)))
  (^bytes [event speaker-count]
   (render-brand-card (event-card-copy event speaker-count) (local-event-hero event))))

(defn render-homepage-card
  "Render the site's root/manifesto big-card asset at truthful OG dimensions."
  ^bytes []
  (render-brand-card
    {:kicker "CURTAIN CALL"
     :title "Calls for papers, without the paperwork"
     :subtitle "Built for organizers and speakers"
     :proposition "The CFP tool organizers dreamed of for fifteen years, built in a weekend on a dare. Zero to open CFP in ten minutes."}
    nil))
