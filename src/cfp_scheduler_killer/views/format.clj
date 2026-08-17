(ns cfp-scheduler-killer.views.format
  "Shared date, time, URL, Markdown, and display-value presentation."
  (:require
   [cfp-scheduler-killer.events :as events]
   [clojure.string :as str]
   [hiccup2.core :as h])
  (:import
   (java.time LocalDate ZoneId)
   (java.time.format DateTimeFormatter)
   (org.jsoup Jsoup)
   (org.jsoup.safety Safelist)))

(defn ->instant
  "Coerce whatever a timestamp arrived as — ISO string, Instant, sql Timestamp —
   into an Instant. nil for anything unparseable, so a bad value renders as a
   dash rather than throwing on a page."
  [x]
  (cond
    (nil? x) nil
    (instance? java.time.Instant x) x
    (instance? java.sql.Timestamp x) (.toInstant ^java.sql.Timestamp x)
    (string? x) (try (java.time.Instant/parse x) (catch Exception _ nil))
    :else nil))

(defn ->local-date
  "Coerce a java.sql.Date / LocalDate / nil into a LocalDate."
  [d]
  (cond
    (nil? d) nil
    (instance? LocalDate d) d
    (instance? java.sql.Date d) (.toLocalDate ^java.sql.Date d)
    :else nil))

(defn cfp-public-url
  "The public CFP address we show organizers. `host` comes from the request."
  [host slug]
  (str host "/cfp/" slug))

(def ^:private date-fmt (DateTimeFormatter/ofPattern "MMM d, yyyy"))

(def ^:private datetime-fmt (DateTimeFormatter/ofPattern "MMM d, yyyy h:mm a"))

(def ^:private short-date-fmt (DateTimeFormatter/ofPattern "MMM d"))

(def ^:private iso-date-fmt (DateTimeFormatter/ofPattern "yyyy-MM-dd"))

(defn not-blank [s] (when-not (str/blank? s) s))

(defn relative-when
  "\"just now\" / \"5m ago\" / \"2h ago\" / \"3d ago\", or nil once it stops being
   useful. Rendered on the SERVER at request time — a JS clock would be a second
   source of truth ticking against the page."
  [inst now]
  (when (and inst now)
    (when-not (.isAfter ^java.time.Instant inst ^java.time.Instant now)
      (let [secs (.between java.time.temporal.ChronoUnit/SECONDS inst now)]
        (cond
          (< secs 60) "just now"
          (< secs 3600) (str (quot secs 60) "m ago")
          (< secs 86400) (str (quot secs 3600) "h ago")
          (< secs (* 7 86400)) (str (quot secs 86400) "d ago")
          :else nil)))))

(def ^:private when-fmt (DateTimeFormatter/ofPattern "MMM d, h:mm a"))

(defn fmt-date [d]
  (some-> (->local-date d) (.format date-fmt)))

(defn fmt-instant
  "Render a timestamptz in the event's own time zone."
  [ts tz]
  (when ts
    (let [inst (cond
                 (instance? java.sql.Timestamp ts) (.toInstant ^java.sql.Timestamp ts)
                 (instance? java.time.Instant ts) ts
                 :else nil)]
      (when inst
        (str (.format (.atZone ^java.time.Instant inst
                               (ZoneId/of (if (events/valid-timezone? tz) tz "UTC")))
                      datetime-fmt))))))

(defn fmt-instant-day
  "Render an instant as the event-local calendar day, for short prose labels."
  [ts tz]
  (when-let [inst (->instant ts)]
    (.format (.atZone ^java.time.Instant inst
                      (ZoneId/of (if (events/valid-timezone? tz) tz "UTC")))
             short-date-fmt)))

(defn fmt-close-date
  "The stored close INSTANT as the yyyy-MM-dd an <input type=date> wants, read
   back in the event's own zone. The instant is 23:59:59 local, so the date it
   round-trips to is the date the organizer originally picked."
  [ts tz]
  (when-let [inst (->instant ts)]
    (.format (.toLocalDate (.atZone ^java.time.Instant inst
                                    (ZoneId/of (if (events/valid-timezone? tz) tz "UTC"))))
             iso-date-fmt)))

(defn fmt-when
  "The one timestamp format for the whole app: \"Aug 9, 7:15 AM\" in the event's
   own zone, with a relative hint while it is still recent."
  ([x tz] (fmt-when x tz (java.time.Instant/now)))
  ([x tz now]
   (when-let [inst (->instant x)]
     (let [zone (ZoneId/of (if (events/valid-timezone? tz) tz "UTC"))
           absolute (.format (.atZone inst zone) when-fmt)]
       (if-let [rel (relative-when inst now)]
         (str absolute " · " rel)
         absolute)))))

(defn fmt-date-range
  "\"Oct 14–15, 2026\"-ish. Honest about missing halves."
  [starts ends]
  (let [s (fmt-date starts) e (fmt-date ends)]
    (cond
      (and s e (= s e)) s
      (and s e) (str s " – " e)
      s (str s " – ?")
      e (str "? – " e)
      :else nil)))

(defn fmt-cfp-window [event]
  (let [{:keys [cfp-opens-at cfp-closes-at tz]} event
        o (fmt-instant cfp-opens-at tz)
        c (fmt-instant cfp-closes-at tz)]
    (cond
      (and o c) (str o " → " c)
      o (str "opens " o)
      c (str "closes " c)
      :else nil)))

(def ^:private md-token
  "The three inline spellings md-lite understands: [text](url), **bold**,
   *emphasis*. Deliberately tiny — organizer copy, not documents."
  #"\[([^\]]+)\]\((https?://[^\s)]+)\)|\*\*([^*]+)\*\*|\*([^*]+)\*")

(defn- md-inline
  "One paragraph's inline markdown → hiccup children. Plain segments stay
   strings, so hiccup escapes them — pasted HTML renders as text, never runs."
  [s]
  (loop [s s out []]
    (if-let [m (re-find md-token s)]
      (let [whole (first m)
            idx (str/index-of s whole)
            [_ ltext lurl btext etext] m]
        (recur (subs s (+ idx (count whole)))
               (-> out
                   (conj (subs s 0 idx))
                   (conj (cond
                           ltext [:a {:href lurl :target "_blank"
                                      :rel "noopener"} ltext]
                           btext [:strong btext]
                           :else [:em etext])))))
      (conj out s))))

(defn md-lite
  "Markdown-ish plain text → hiccup: blank lines split paragraphs, plus the
   inline set in `md-token`. The fallback renderer for organizer copy."
  [s]
  (for [para (str/split (str s) #"\n\s*\n")
        :when (not (str/blank? para))]
    ;; seq, not vector — hiccup reads a vector as an ELEMENT (first item
    ;; becomes a tag), which both mangles the markup and skips escaping.
    [:p (seq (md-inline (str/trim para)))]))

(defn render-markdown
  "Organizer copy → hiccup. Uses markdown-clj (the renderer social-media-writer
   ships — server-side, never client) when it is on the classpath; until the
   JVM restarts with the new dep, `md-lite` covers the same copy. The source is
   entity-escaped FIRST either way: organizer text sells, it never scripts."
  [s]
  (let [escaped (-> (str s)
                    (str/replace "&" "&amp;")
                    (str/replace "<" "&lt;")
                    (str/replace ">" "&gt;"))]
    (if-let [md (try (requiring-resolve 'markdown.core/md-to-html-string)
                     (catch Throwable _ nil))]
      ;; markdown-clj emits raw HTML and uses quoted attributes internally.
      ;; Clean its output rather than trusting unusual link syntax to remain
      ;; text: organizer copy is public, stored content.
      (h/raw (Jsoup/clean (md escaped) (Safelist/basic)))
      (md-lite s))))
