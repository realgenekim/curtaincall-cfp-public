(ns cfp-scheduler-killer.embedding
  "Pure construction of public widget handoff URLs and snippets."
  (:require
   [clojure.string :as str])
  (:import
   (java.net URLEncoder)
   (java.nio.charset StandardCharsets)))

(def widget-options
  [{:id "agenda" :label "Agenda"}
   {:id "sessions" :label "Sessions"}
   {:id "speakers" :label "Speakers"}
   {:id "gallery" :label "Speaker gallery"}
   {:id "itinerary" :label "Itinerary"}])

(def format-options
  [{:id "iframe" :label "Styled iframe / HTML"}
   {:id "link" :label "Basic HTML link"}
   {:id "json" :label "JSON"}
   {:id "xml" :label "XML"}
   {:id "ical" :label "iCal"}])

(def field-options
  [{:id "description" :label "Descriptions"}
   {:id "schedule" :label "Dates, times, and rooms"}
   {:id "speakers" :label "Speaker names and organizations"}
   {:id "tags" :label "Format and track tags"}])

(def ^:private default-fields (mapv :id field-options))

(defn- allowed-id
  [options requested fallback]
  (if (some #(= requested (:id %)) options) requested fallback))

;; INTENT: EMB-001 — each advertised HTML widget has its own public route and
;; therefore resolves to its own sessions, speakers, or gallery representation.
(defn- widget-path
  [slug widget]
  (case widget
    "agenda" (str "/program/" slug)
    "sessions" (str "/agenda/" slug "/sessions")
    "speakers" (str "/agenda/" slug "/speakers")
    "gallery" (str "/agenda/" slug "/gallery")
    "itinerary" (str "/agenda/" slug "/itinerary")
    (str "/program/" slug)))

(defn- json-path
  [slug widget]
  (case widget
    ("speakers" "gallery") (str "/events/" slug "/exports/speakers.json")
    "agenda" (str "/api/v1/events/" slug "/schedule")
    (str "/events/" slug "/exports/sessions.json")))

(defn- xml-path
  [slug widget]
  (str "/api/v1/events/" slug "/"
       (if (#{"speakers" "gallery"} widget) "speakers" "sessions")
       "?format=xml"))

(defn- encoded [value]
  (URLEncoder/encode (str value) StandardCharsets/UTF_8))

(defn- param-values [value]
  (cond
    (nil? value) []
    (sequential? value) (mapv str value)
    :else [(str value)]))

(defn- clean-filter [value]
  (some-> value str str/trim not-empty))

(defn- frame-size [value fallback]
  (let [candidate (some-> value str str/trim)]
    (if (and candidate (re-matches #"(?:100%|[3-9][0-9]{2}|1[0-5][0-9]{2}|1600)" candidate))
      candidate
      fallback)))

(defn- normalize-config [requested]
  (let [customized? (= "true" (str (:customize requested)))
        requested-fields (set (param-values (:fields requested)))
        fields (if customized?
                 (filterv #(contains? requested-fields %) default-fields)
                 default-fields)
        accent (let [candidate (str (:accent requested))]
                 (if (re-matches #"#[0-9A-Fa-f]{6}" candidate)
                   candidate
                   "#1f6feb"))]
    {:accent accent
     :theme (if (= "compact" (:theme requested)) "compact" "standard")
     :track (clean-filter (:track requested))
     :session-format (clean-filter (:session-format requested))
     :room (clean-filter (:room requested))
     :width (frame-size (:width requested) "100%")
     :height (frame-size (:height requested) "720")
     :fields fields}))

(defn- with-query [url {:keys [accent theme track session-format room fields]}]
  (str url "?"
       (str/join "&"
                 (concat [(str "accent=" (encoded accent))
                          (str "theme=" (encoded theme))
                          (str "fields=" (encoded (str/join "," fields)))]
                         (when track [(str "track=" (encoded track))])
                         (when session-format
                           [(str "format=" (encoded session-format))])
                         (when room [(str "room=" (encoded room))])))))

(defn build
  "Build one normalized, copyable handoff. No request or I/O is involved."
  ([host event requested-widget requested-format]
   (build host event requested-widget requested-format {}))
  ([host event requested-widget requested-format requested-config]
   (let [widget (allowed-id widget-options requested-widget "agenda")
         format (allowed-id format-options requested-format "iframe")
         config (normalize-config requested-config)
         base-public-url (str host (widget-path (:slug event) widget))
         public-url (with-query base-public-url config)
         handoff-url (case format
                       "json" (str host (json-path (:slug event) widget))
                       "xml" (str host (xml-path (:slug event) widget))
                       "ical" (str host "/events/" (:slug event)
                                    "/exports/calendar.ics")
                       public-url)
         value (case format
                 "link" (str "<a href=\"" public-url "\">View conference "
                             widget "</a>")
                 ("json" "xml" "ical") handoff-url
                 (str "<iframe src=\"" public-url
                      "\" title=\"Conference " widget
                      "\" loading=\"lazy\" width=\"" (:width config)
                      "\" height=\"" (:height config) "\" "
                      "style=\"border: 3px solid " (:accent config)
                      "; border-radius: 8px;\"></iframe>"))]
     {:widget widget
      :format format
      :config config
      :public-url public-url
      :handoff-url handoff-url
      :value value})))
