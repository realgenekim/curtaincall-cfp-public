(ns cfp-scheduler-killer.embed-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.embedding :as embedding]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock])
  (:import
   (java.net URI)
   (java.time LocalDateTime)))

(use-fixtures :each with-temp-store)

(defn- setup!
  []
  (let [event (events/create-event!
                {:name "Embed & Summit" :slug "embed-summit"
                 :tz "America/Los_Angeles"
                 :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
                 :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
                "kaocha")
        committee-id (:id (first (events/committees-for-event (:id event))))]
    (committees/add-member!
      committee-id
      {:name "Embed Organizer" :email "organizer@example.com" :role "chair"}
      "kaocha")
    event))

(defn- login!
  [handler]
  (let [token (auth/issue-token! "organizer@example.com")
        response (handler (mock/request :get (str "/auth/" token)))]
    (first (str/split (first (get-in response [:headers "Set-Cookie"])) #";"))))

(defn- anonymous-path
  [url]
  (let [uri (URI. url)]
    (str (.getRawPath uri)
         (when-let [query (.getRawQuery uri)]
           (str "?" query)))))

(deftest embed-spec-is-a-small-total-algebra
  (let [event {:slug "sample"}
        iframe (embedding/build "https://example.test" event "sessions" "iframe")
        json (embedding/build "https://example.test" event "speakers" "json")
        xml (embedding/build "https://example.test" event "speakers" "xml")
        itinerary (embedding/build "https://example.test" event "itinerary" "ical")
        configured (embedding/build
                     "https://example.test" event "sessions" "iframe"
                     {:customize "true" :accent "#123ABC" :theme "compact"
                      :track "Developer Practices"
                      :session-format "Experience Report" :room "Main Stage"
                      :width "900" :height "640"
                      :fields ["schedule" "tags"]})
        fallback (embedding/build "https://example.test" event "unknown" "unknown")]
    (is (str/starts-with? (:public-url iframe)
                          "https://example.test/agenda/sample/sessions?"))
    (is (str/includes? (:value iframe) "<iframe"))
    (is (= "https://example.test/events/sample/exports/speakers.json"
           (:value json)))
    (is (= (:value json) (:handoff-url json)))
    (is (= "https://example.test/api/v1/events/sample/speakers?format=xml"
           (:handoff-url xml)))
    (is (= (:handoff-url xml) (:value xml)))
    (is (= (:public-url iframe) (:handoff-url iframe)))
    (is (= "https://example.test/events/sample/exports/calendar.ics" (:value itinerary)))
    (is (str/starts-with? (:public-url itinerary)
                          "https://example.test/agenda/sample/itinerary?"))
    (is (str/includes? (:public-url configured) "track=Developer+Practices"))
    (is (str/includes? (:public-url configured) "format=Experience+Report"))
    (is (str/includes? (:public-url configured) "room=Main+Stage"))
    (is (str/includes? (:public-url configured) "fields=schedule%2Ctags"))
    (is (str/includes? (:value configured) "border: 3px solid #123ABC"))
    (is (str/includes? (:value configured) "width=\"900\" height=\"640\""))
    (is (= {:accent "#123ABC" :theme "compact"
            :track "Developer Practices" :session-format "Experience Report"
            :room "Main Stage" :width "900" :height "640"
            :fields ["schedule" "tags"]}
           (:config configured)))
    (is (= ["100%" "720"]
           ((juxt (comp :width :config) (comp :height :config))
            (embedding/build "https://example.test" event "sessions" "iframe"
                             {:customize "true" :width "javascript:bad"
                              :height "99999"}))))
    (is (= ["agenda" "iframe"] [(:widget fallback) (:format fallback)]))))

(deftest every-advertised-embed-handoff-resolves-anonymously-test
  (let [event (setup!)
        handler (server/create-app)
        widgets (map :id embedding/widget-options)
        formats (map :id embedding/format-options)]
    (doseq [widget widgets
            format formats]
      (testing (str widget " / " format)
        (let [{:keys [handoff-url]} (embedding/build "http://conference.test"
                                                     event widget format)
              response (handler (mock/request :get (anonymous-path handoff-url)))]
          (is (= 200 (:status response)) handoff-url))))))

(deftest organizer-embed-builder-route-test
  (let [event (setup!)
        handler (server/create-app)
        path (str "/events/" (:slug event) "/embed")]
    (testing "the builder is organizer-gated"
      (is (= 302 (:status (handler (mock/request :get path))))))

    (testing "the signed-in page exposes exact handoff controls and a working URL"
      (let [cookie (login! handler)
            response (handler
                       (-> (mock/request :get
                                         (str path "?widget=sessions&format=iframe"))
                           (mock/header "host" "conference.test")
                           (mock/header "cookie" cookie)))
            body (:body response)]
        (is (= 200 (:status response)))
        (doseq [literal ["Embed builder" "Widget type" "Format"
                         "Styled iframe / HTML" "Basic HTML link" "JSON" "XML" "iCal"
                         "Itinerary" "Accent color"
                         "Layout density" "Track filter" "Format filter"
                         "Location filter" "Frame width" "Frame height"
                         "Fields to show"
                         "Descriptions" "Dates, times, and rooms"
                         "Speaker names and organizations" "Format and track tags"
                         "Copyable snippet" "Working preview"
                         "Organizer edits appear when the embed reloads"
                         "Social unfurls" "Public program" "Speaker profiles"
                         "Speaker announcement pages"
                         "rich card on Twitter/LinkedIn/Slack"]]
          (is (str/includes? body literal) literal))
        (testing "the organizer sidebar exposes this page and marks it active"
          (is (str/includes?
                body
                (str "class=\"sb-item active\" href=\"" path "\">Embeds &amp; widgets</a>"))))
        (is (str/includes? body
                           "http://conference.test/agenda/embed-summit/sessions"))
        (testing "configuration is reflected in the generated live URL"
          (let [configured-body
                (:body
                  (handler
                    (-> (mock/request
                          :get
                          (str path "?widget=sessions&format=iframe&customize=true"
                               "&accent=%23123456&theme=compact"
                               "&track=Developer+Practices"
                               "&session-format=Experience+Report&room=Main+Stage"
                               "&width=900&height=640"
                               "&fields=schedule&fields=tags"))
                        (mock/header "host" "conference.test")
                        (mock/header "cookie" cookie))))]
            (is (str/includes? configured-body "accent=%23123456"))
            (is (str/includes? configured-body "theme=compact"))
            (is (str/includes? configured-body "track=Developer+Practices"))
            (is (str/includes? configured-body "format=Experience+Report"))
            (is (str/includes? configured-body "room=Main+Stage"))
            (is (str/includes? configured-body "width=&quot;900&quot;"))
            (is (str/includes? configured-body "height=&quot;640&quot;"))
            (is (str/includes? configured-body "fields=schedule%2Ctags"))
            (is (re-find #"(?s)<iframe(?=[^>]*title=\"Embed preview\")(?=[^>]*width=\"900\")(?=[^>]*height=\"640\")[^>]*>"
                         configured-body)
                "the live preview uses the generated iframe dimensions")))
        (testing "machine formats verify the generated feed, not the HTML widget"
          (let [json-body (:body
                            (handler
                              (-> (mock/request
                                    :get (str path "?widget=sessions&format=json"))
                                  (mock/header "host" "conference.test")
                                  (mock/header "cookie" cookie))))]
            (is (str/includes?
                  json-body
                  "href=\"http://conference.test/events/embed-summit/exports/sessions.json\""))
            (is (not (str/includes?
                       json-body
                       ">Open public widget<")))))
        (testing "XML is a real anonymous machine handoff"
          (let [xml-response
                (handler
                  (mock/request
                    :get
                    "/api/v1/events/embed-summit/sessions?format=xml"))
                speakers-response
                (handler
                  (mock/request
                    :get
                    "/api/v1/events/embed-summit/speakers?format=xml"))]
            (is (= 200 (:status xml-response)))
            (is (= "application/xml; charset=utf-8"
                   (get-in xml-response [:headers "Content-Type"])))
            (is (str/starts-with? (:body xml-response) "<?xml version=\"1.0\""))
            (is (str/includes? (:body xml-response) "<sessionsFeed>"))
            (is (str/includes? (:body xml-response) "<slug>embed-summit</slug>"))
            (is (str/includes? (:body xml-response)
                               "<name>Embed &amp; Summit</name>"))
            (is (= "application/xml; charset=utf-8"
                   (get-in speakers-response [:headers "Content-Type"])))
            (is (str/includes? (:body speakers-response) "<speakersFeed>"))))))))
