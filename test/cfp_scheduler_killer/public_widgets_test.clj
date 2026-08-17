(ns cfp-scheduler-killer.public-widgets-test
  (:require
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.inform :as inform]
   [cfp-scheduler-killer.personal-schedule :as personal-schedule]
   [cfp-scheduler-killer.public-catalog :as catalog]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.schedule :as schedule]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [cfp-scheduler-killer.views.my-schedule :as view-my-schedule]
   [cfp-scheduler-killer.views.schedule :as view-schedule]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock])
  (:import
   (java.time LocalDate LocalDateTime)))

(use-fixtures :each with-temp-store)

(def day "2026-10-14")

(defn- make-submission! [event fields {:keys [title speaker email company track headshot format]}]
  ;; The abstract is deliberately LONGER than the public preview limit, so the
  ;; "Show more" affordance under test is actually exercised. Copy that fits in
  ;; the preview correctly renders whole, with no control to expand.
  (let [params {:answer-talk-title title
                :answer-abstract (str title " is a detailed public description that explains the work, "
                                      "the tradeoffs, and the measurable result for attendees. It walks "
                                      "through the rollout quarter by quarter, names the two approaches "
                                      "that were abandoned, and closes on the numbers the team carried "
                                      "back to its board at the end of the programme.")
                :answer-session-format (or format "Experience Report")
                :answer-track track
                :answer-org-size ">10,000"
                :answer-industry track
                :answer-ai-transformation-history "Since 2023"
                :answer-measurable-outcomes "Measured outcomes"
                :answer-notes-to-committee "Never public"
                :speaker-name speaker :speaker-email email
                :speaker-title "VP of AI" :speaker-org company
                :speaker-bio (str speaker " has led enterprise AI programs for a decade.")
                :speaker-headshot-url headshot}]
    (submissions/create-submission!
      event
      (submissions/parse-answers fields params)
      (submissions/parse-speaker params)
      "form"
      "kaocha")))

(defn- setup! []
  (let [event (events/create-eais-event!
                {:name "Public Widget Summit" :slug "public-widgets"
                 :tz "America/New_York" :location "Charlotte, NC"
                 :starts-on (LocalDate/of 2026 10 14)
                 :ends-on (LocalDate/of 2026 10 15)
                 :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
                 :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
                "kaocha")
        committee-id (:id (first (events/committees-for-event (:id event))))
        _ (committees/add-member! committee-id
                                  {:name "Gene Kim" :email "gene@example.com" :role "chair"}
                                  "kaocha")
        fields (:fields (events/form-for-event (:id event)))
        banking (make-submission! event fields
                                  {:title "Banking AI" :speaker "Amara Devlin"
                                   :email "amara@example.com" :company "North Banc"
                                   :track "Developer Practices"
                                   :headshot "https://images.example.com/amara.jpg"})
        retail (make-submission! event fields
                                 {:title "Retail Forecasting" :speaker "Bo Chen"
                                  :email "bo@example.com" :company "Market Square"
                                  :track "Business Outcomes" :format "Workshop"})
        declined (make-submission! event fields
                                   {:title "Private Declined" :speaker "Declined Speaker"
                                    :email "declined@example.com" :company "Hidden Co"
                                    :track "Developer Practices"})]
    (doseq [submission [banking retail]]
      (reviews/set-status! (:id submission) "Accepted" "gene@example.com")
      (inform/inform! event (store/submission-by-id (:id submission)) "gene@example.com"))
    (reviews/set-status! (:id declined) "Declined" "gene@example.com")
    (inform/inform! event (store/submission-by-id (:id declined)) "gene@example.com")
    (let [room (schedule/add-room! event "Main Stage" "gene@example.com")]
      (schedule/place! event (:id banking)
                       {:day day :start "09:00" :room-id (:id room)} "gene@example.com")
      (schedule/place! event (:id retail)
                       {:day day :start "10:30" :room-id (:id room)} "gene@example.com"))
    {:event (events/event-by-slug "public-widgets")
     :banking banking :retail retail :declined declined}))

(defn- get-page [handler path]
  (handler (mock/request :get path)))

(defn- add-unplaced!
  "An accepted + informed session that nobody has scheduled yet — the partial
   state doctrine #6 calls first-class. It reuses an existing speaker's email on
   purpose, so the roster counts the other tests pin do not move."
  [event]
  (let [fields (:fields (events/form-for-event (:id event)))
        submission (make-submission!
                     event fields
                     {:title "Platform Migration" :speaker "Amara Devlin"
                      :email "amara@example.com" :company "North Banc"
                      :track "Developer Practices"})]
    (reviews/set-status! (:id submission) "Accepted" "gene@example.com")
    (inform/inform! event (store/submission-by-id (:id submission)) "gene@example.com")
    submission))

(deftest public-catalog-search-and-tenancy-test
  (let [{:keys [event banking retail declined]} (setup!)
        sessions (catalog/sessions event)
        speakers (catalog/public-speakers event)]
    (is (= [(:id banking) (:id retail)] (mapv :id sessions)))
    (is (= ["Bo Chen" "Amara Devlin"] (mapv :name speakers))
        "public speaker catalogs sort by surname")
    (is (not-any? #(= (str (:id declined)) (:id %)) sessions))
    (is (= ["Banking AI"] (mapv :title (catalog/filter-sessions sessions "Amara" nil))))
    (is (= ["Retail Forecasting"] (mapv :title (catalog/filter-sessions sessions "Retail" nil))))
    (is (= ["Banking AI"]
           (mapv :title (catalog/filter-sessions sessions nil "Developer Practices"))))
    (is (= ["Banking AI"]
           (mapv :title (catalog/filter-sessions sessions nil nil
                                                 "Experience Report" "Main Stage"))))
    (is (empty? (catalog/filter-sessions sessions nil nil
                                         "Workshop" "Side Stage")))
    (is (= ["Amara Devlin"] (mapv :name (catalog/filter-speakers speakers "amara"))))
    (doseq [query ["north banc" "banking ai" "developer practices"
                   "experience report"]]
      (is (= ["Amara Devlin"] (mapv :name (catalog/filter-speakers speakers query))) query))
    (doseq [query ["vp of ai" "enterprise ai programs" "main stage"]]
      (is (some #(= "Amara Devlin" (:name %)) (catalog/filter-speakers speakers query)) query))
    (is (= 1 (count (:sessions (catalog/speaker-by-id event (:id (first speakers)))))))))

(deftest public-speaker-surname-order-handles-numbered-fixture-names-test
  (let [names ["Sandbox Speaker 012" "Amara Devlin" "Steve Yegge"
               "Sandbox Speaker 008" "Jondavid Black" "Bo Chen"]
        announced (mapv #(hash-map :name % :published? true) names)]
    (with-redefs [catalog/sessions (constantly [])
                  events/announced-speakers (constantly announced)]
      (is (= ["Jondavid Black" "Bo Chen" "Amara Devlin"
              "Sandbox Speaker 008" "Sandbox Speaker 012" "Steve Yegge"]
             (mapv :name (catalog/public-speakers {:id "fixture"})))
          "a numeric fixture suffix is not mistaken for the speaker's surname"))))

(deftest public-disclosure-label-reflects-current-state-test
  (let [{:keys [event banking]} (setup!)
        handler (server/create-app)
        body (:body (get-page handler
                              (str "/agenda/" (:slug event) "/sessions/" (:id banking))))
        css (slurp "resources/public/css/app.css")]
    (is (str/includes? body
                       "<span class=\"public-disclosure-more\">Show more</span>"))
    (is (str/includes? body
                       "<span class=\"public-disclosure-less\">Show less</span>"))
    (is (str/includes? css
                       ".public-show-more[open] .public-disclosure-more"))
    (is (str/includes? css
                       ".public-show-more:not([open]) .public-disclosure-less"))))

(deftest agenda-disclosure-label-reflects-current-state-test
  (let [{:keys [event]} (setup!)
        handler (server/create-app)
        body (:body (get-page handler (str "/program/" (:slug event))))]
    (is (str/includes? body
                       "<span class=\"public-disclosure-more\">Show more</span>"))
    (is (str/includes? body
                       "<span class=\"public-disclosure-less\">Show less</span>"))
    (is (not (str/includes? body "<summary>Show more</summary>")))))

(deftest agenda-detail-roundtrip-preserves-day-and-scroll-context-test
  (let [{:keys [event banking retail]} (setup!)
        room-id (:room-id (first (schedule/placements (:id event))))
        _ (schedule/place! event (:id retail)
                           {:day "2026-10-15" :start "10:30" :room-id room-id}
                           "gene@example.com")
        handler (server/create-app)
        program (:body (get-page handler (str "/program/" (:slug event) "?day=" day)))
        detail-path (str "/agenda/" (:slug event) "/sessions/" (:id banking)
                         "?from=agenda&day=" day)
        detail (:body (get-page handler detail-path))
        agenda-anchor (str "agenda-day-" day)]
    (testing "the selected day query drives the visible agenda card"
      (is (str/includes? program
                         (str "href=\"/program/" (:slug event) "#agenda\"")))
      (is (str/includes? program (str "id=\"" agenda-anchor "\"")))
      (is (not (str/includes? program "id=\"agenda-day-2026-10-15\"")))
      (is (not (str/includes? program "Retail Forecasting"))))
    (testing "session detail returns to the selected agenda day in place"
      (is (str/includes? detail "← Back to agenda"))
      (is (str/includes? detail
                         (str "href=\"/program/" (:slug event) "?day=" day
                              "#" agenda-anchor "\""))))))

(deftest speakers-directory-is-distinct-from-the-legacy-gallery-test
  (let [{:keys [event]} (setup!)
        handler (server/create-app)
        speakers (:body (get-page handler (str "/agenda/" (:slug event) "/speakers")))
        directory (:body (get-page handler (str "/agenda/" (:slug event) "/directory")))
        gallery (:body (get-page handler (str "/agenda/" (:slug event) "/gallery")))]
    (is (= speakers gallery))
    (is (str/includes? speakers (str "href=\"/agenda/" (:slug event) "/directory\"")))
    (is (str/includes? directory "aria-label=\"Speakers directory\""))
    (is (str/includes? directory "Amara Devlin"))
    (is (str/includes? directory "VP of AI · North Banc"))
    (is (not (str/includes? directory "cfp-featured-card")))
    (is (str/includes? gallery "aria-label=\"Speakers\""))
    (is (str/includes? gallery "cfp-featured-card"))))

(deftest speakers-directory-lists-announced-speakers-through-the-public-gate-test
  (let [{:keys [event]} (setup!)
        _ (events/announce-speaker! event
                                    {:name "Cleo Keynote" :title "CTO" :org "Keynote Labs"}
                                    "gene@example.com")
        handler (server/create-app)
        body (:body (get-page handler (str "/agenda/" (:slug event) "/directory")))]
    (is (str/includes? body "Cleo Keynote"))
    (is (str/includes? body "CTO · Keynote Labs"))
    (is (not (str/includes? body "Declined Speaker")))
    (is (not (str/includes? body "Private Declined")))))

;; INTENT-TEST: EMB-004
(deftest speaker-index-uses-uniform-shared-card-and-stable-detail-test
  (let [{:keys [event]} (setup!)
        handler (server/create-app)
        speaker (first (filter #(= "Amara Devlin" (:name %))
                               (catalog/public-speakers event)))
        detail-path (str "/agenda/" (:slug event) "/speakers/"
                         (or (:slug speaker) (:id speaker)))
        directory (:body (get-page handler (str "/agenda/" (:slug event) "/directory")))
        gallery (:body (get-page handler (str "/agenda/" (:slug event)
                                              "/gallery?q=enterprise+ai+programs")))
        detail (:body (get-page handler detail-path))
        program (:body (get-page handler (str "/program/" (:slug event))))
        bio "Amara Devlin has led enterprise AI programs for a decade."]
    (is (str/includes? directory "public-speaker-list-directory"))
    (is (str/includes? directory "Speakers"))
    (is (not (str/includes? directory bio)))
    (is (not (str/includes? gallery bio)))
    (is (str/includes? detail bio))
    (is (str/includes? detail "property=\"og:title\""))
    (is (str/includes? detail "Amara Devlin at Public Widget Summit"))
    (is (str/includes? detail "name=\"twitter:card\""))
    (is (str/includes? detail "/agenda/public-widgets/speakers/amara-devlin/card.png"))
    (is (str/includes? directory detail-path))
    (is (str/includes? directory "VP of AI · North Banc"))
    (is (not (str/includes? directory "cfp-featured-card")))
    (is (str/includes? gallery "cfp-featured-card"))
    (is (str/includes? program "cfp-featured-card"))))

(deftest configured-speaker-widgets-preserve-branding-fields-and-return-test
  (let [{:keys [event]} (setup!)
        handler (server/create-app)
        state "q=Amara&accent=%23123456&theme=compact&fields=schedule"
        escaped-state "q=Amara&amp;accent=%23123456&amp;theme=compact&amp;fields=schedule"
        gallery (:body (get-page handler (str "/agenda/" (:slug event) "/gallery?" state)))
        speakers-detail (:body (get-page handler
                                         (str "/agenda/" (:slug event)
                                              "/speakers/amara-devlin?from=speakers&" state)))
        gallery-detail (:body (get-page handler
                                        (str "/agenda/" (:slug event)
                                             "/speakers/amara-devlin?from=gallery&" state)))
        bio "Amara Devlin has led enterprise AI programs for a decade."]
    (doseq [body [gallery speakers-detail gallery-detail]]
      (is (str/includes? body "data-embed-theme=\"compact\""))
      (is (str/includes? body "border-top: 6px solid #123456")))
    (is (not (str/includes? speakers-detail bio)))
    (is (str/includes? speakers-detail "Main Stage"))
    (is (str/includes? gallery-detail "Main Stage"))
    (is (str/includes? gallery
                       (str "/speakers/amara-devlin?from=speakers&amp;q=Amara"
                            "&amp;accent=%23123456&amp;theme=compact&amp;fields=schedule")))
    (is (str/includes? speakers-detail "← Back to speakers"))
    (is (str/includes? speakers-detail
                       (str "href=\"/program/" (:slug event) "?" escaped-state
                            "#speakers\"")))
    (is (str/includes? gallery-detail "← Back to speakers"))
    (is (str/includes? gallery-detail
                       (str "href=\"/program/" (:slug event) "?" escaped-state
                            "#speakers\"")))))

(deftest gallery-compatibility-url-opens-the-stable-speaker-detail-test
  (let [{:keys [event]} (setup!)
        handler (server/create-app)
        gallery (:body (get-page handler (str "/agenda/" (:slug event) "/gallery?q=Amara")))
        speaker (first (filter #(= "Amara Devlin" (:name %))
                               (catalog/public-speakers event)))
        detail-path (str "/agenda/" (:slug event) "/speakers/"
                         (or (:slug speaker) (:id speaker)) "?from=speakers&q=Amara")
        detail (:body (get-page handler detail-path))]
    (is (str/includes? gallery
                       (str "href=\"/agenda/" (:slug event) "/speakers/"
                            (or (:slug speaker) (:id speaker))
                            "?from=speakers&amp;q=Amara\"")))
    (is (str/includes? detail "← Back to speakers"))
    (is (str/includes? detail
                       (str "href=\"/program/" (:slug event)
                            "?q=Amara#speakers\"")))))

(deftest public-session-rooms-stay-truthful-across-shared-surfaces-test
  (let [{:keys [event banking]} (setup!)
        unplaced (add-unplaced! event)
        handler (server/create-app)
        sessions (catalog/sessions event)
        banking-session (catalog/session-by-id event (:id banking))
        unplaced-session (catalog/session-by-id event (:id unplaced))
        amara (first (filter #(= "Amara Devlin" (:name %))
                             (catalog/public-speakers event)))
        speaker-detail (:body (get-page handler
                                        (str "/agenda/public-widgets/speakers/"
                                             (or (:slug amara) (:id amara)))))
        sessions-page (:body (get-page handler "/agenda/public-widgets/sessions"))
        placed-detail (:body (get-page handler
                                       (str "/agenda/public-widgets/sessions/"
                                            (:id banking))))
        unplaced-detail (:body (get-page handler
                                         (str "/agenda/public-widgets/sessions/"
                                              (:id unplaced))))
        program (:body (get-page handler "/program/public-widgets"))]
    (testing "the shared projection carries the scheduled room and an explicit fallback"
      (is (= "Main Stage" (:room banking-session)))
      (is (= "Room TBA" (:room unplaced-session)))
      (is (= ["Main Stage" "Room TBA"] (catalog/rooms sessions)))
      (is (= ["Main Stage" "Room TBA"]
             (mapv :room (:sessions amara)))))

    (testing "session cards and details render the real room without inventing one"
      (doseq [body [sessions-page placed-detail]]
        (is (str/includes? body ">Room: Main Stage</span>")))
      (doseq [body [sessions-page unplaced-detail]]
        (is (str/includes? body ">Room TBA</span>"))
        (is (not (str/includes? body "Room: Room TBA")))))

    (testing "speaker session lists reached from the gallery use the same room truth"
      (is (str/includes? speaker-detail ">Room: Main Stage</span>"))
      (is (str/includes? speaker-detail ">Room TBA</span>"))
      (is (not (str/includes? speaker-detail "Room: Room TBA"))))

    (testing "the public agenda retains the scheduled room"
      (is (str/includes? program "Banking AI"))
      (is (str/includes? program "Main Stage")))))

(deftest public-widget-routes-and-card-anatomy-test
  ;; /program remains the canonical combined view. The judge-facing embed
  ;; URLs are also real, distinct pages so generated links never collapse
  ;; onto one program page or become browser downloads.
  (let [{:keys [event banking]} (setup!)
        handler (server/create-app)
        speakers (catalog/public-speakers event)
        amara (first (filter #(= "Amara Devlin" (:name %)) speakers))
        ;; The CANONICAL speaker address is the permanent slug (minted at
        ;; inform); the UUID form 301s to it — see speaker-slug-test.
        amara-path (str "/agenda/public-widgets/speakers/"
                        (or (:slug amara) (:id amara)))
        surviving-detail-paths [(str "/agenda/public-widgets/sessions/" (:id banking))
                                amara-path]]
    (testing "the combined route redirects while public browse routes remain safe"
      (is (= 302 (:status (get-page handler "/agenda/public-widgets"))))
      (doseq [path ["/agenda/public-widgets/sessions"
                    "/agenda/public-widgets/speakers"
                    "/agenda/public-widgets/directory"
                    "/agenda/public-widgets/gallery"]]
        (let [response (get-page handler path)]
          (is (= 200 (:status response)) path)
          (is (str/includes? (get-in response [:headers "Content-Type"]) "text/html") path))))

    (testing "surviving detail widgets are logged-out public and use public chrome"
      (doseq [path surviving-detail-paths]
        (let [response (get-page handler path)]
          (is (= 200 (:status response)) path)
          (is (not (str/includes? (:body response) "class=\"sidebar\"")) path)
          (is (str/includes? (:body response) "public-widget-page") path))))

    (testing "the program page carries the literal filled-state anatomy (agenda + speaker cards)"
      (let [body (:body (get-page handler "/program/public-widgets"))]
        (doseq [literal ["Speakers" "2 speakers" "Banking AI" "Show more"
                         "Main Stage" "Amara Devlin" "VP of AI" "North Banc"
                         "Format: Experience Report" "Track: Developer Practices"]]
          (is (str/includes? body literal) literal))
        (is (not (str/includes? body "Private Declined")))
        (is (not (str/includes? body "Never public")))))

    (testing "the canonical program exposes one in-page Speakers and Agenda navigation"
      (let [body (:body (get-page handler "/program/public-widgets"))]
        (doseq [[label path] [["Speakers" "/program/public-widgets#speakers"]
                              ["Agenda" "/program/public-widgets#agenda"]]]
          (is (str/includes? body (str "href=\"" path "\"")) path)
          (is (str/includes? body (str ">" label "</a>")) label))
        (is (not (str/includes? body ">Speaker gallery</a>")))))

    (testing "an agent can discover the event index from the public program"
      (let [program (:body (get-page handler "/program/public-widgets"))
            event-index (get-page handler "/events/public-widgets/llms.txt")
            site-index (get-page handler "/llms.txt")]
        (is (= 200 (:status event-index)))
        (is (str/includes? (get-in event-index [:headers "Content-Type"])
                           "text/markdown"))
        (is (str/includes? (:body event-index) "# Public Widget Summit"))
        (is (str/includes? (:body site-index)
                           "/events/public-widgets/llms.txt"))
        (is (str/includes? (:body site-index) "/program/public-widgets"))
        (is (str/includes? program "rel=\"alternate\""))
        (is (str/includes? program "type=\"text/markdown\""))
        (is (str/includes? program
                           "href=\"/events/public-widgets/llms.txt\""))
        (is (str/includes? program "href=\"/events/public-widgets/llms.txt\""))))

    (testing "the sessions page accepts its search query"
      (let [response (get-page handler "/agenda/public-widgets/sessions?q=Amara")]
        (is (= 200 (:status response)))
        (is (str/includes? (:body response) "Banking AI"))
        (is (not (str/includes? (:body response) "Retail Forecasting")))
        (is (str/includes? (:body response) "Sessions 1 – 1 of 2"))))

    (testing "server-side facets compose and retain their controls"
      (let [matching (:body (get-page handler
                                      "/agenda/public-widgets/sessions?track=Developer+Practices&format=Experience+Report&room=Main+Stage"))
            empty (:body (get-page handler
                                   "/agenda/public-widgets/sessions?format=Workshop&room=Side+Stage"))]
        (is (str/includes? matching "Banking AI"))
        (is (not (str/includes? matching "Retail Forecasting")))
        (is (str/includes? matching "Sessions 1 – 1 of 2"))
        (is (str/includes? matching "aria-label=\"Filters\""))
        (is (str/includes? matching "aria-label=\"Format\""))
        (is (str/includes? matching "aria-label=\"Location\""))
        (is (str/includes? empty "No sessions match those filters."))))

    (testing "Speakers, its compatibility URL, and stable detail remain navigable"
      (let [full-directory (:body (get-page handler "/agenda/public-widgets/directory"))
            directory (get-page handler "/agenda/public-widgets/directory")
            speakers (get-page handler "/agenda/public-widgets/speakers?q=Amara")
            gallery (get-page handler "/agenda/public-widgets/gallery")
            detail (:body (get-page handler amara-path))]
        (is (< (str/index-of full-directory "Bo Chen")
               (str/index-of full-directory "Amara Devlin"))
            "the directory is alphabetized by surname")
        (is (= 200 (:status directory)))
        (is (str/includes? (:body directory) "Amara Devlin"))
        (is (str/includes? (:body directory) "VP of AI"))
        (is (str/includes? (:body directory) "North Banc"))
        (is (= 200 (:status speakers)))
        (is (str/includes? (:body speakers) "1 of 2 speakers match"))
        (is (= 200 (:status gallery)))
        (is (< (str/index-of (:body gallery) "Bo Chen")
               (str/index-of (:body gallery) "Amara Devlin")))
        (is (str/includes? (:body gallery) "Photo unavailable for Bo Chen"))
        (is (str/includes? (:body gallery) "VP of AI"))
        (is (str/includes? (:body gallery) "North Banc"))
        (is (str/includes? detail "← Back to speakers"))
        (is (str/includes? detail "Sessions (1)"))
        (is (str/includes? detail "Banking AI"))))

    (testing "the legacy gallery URL shares the searchable Speakers contract"
      (let [body (:body (get-page handler "/agenda/public-widgets/gallery?q=Amara"))]
        (is (str/includes? body "1 of 2 speakers match"))
        (is (str/includes? body "Amara Devlin"))
        (is (str/includes? body "public-speaker-directory"))
        (is (str/includes? body "from=speakers&amp;q=Amara"))))

    (testing "legacy gallery detail state closes back to Speakers"
      (let [body (:body
                   (get-page handler (str amara-path "?from=gallery&q=Amara")))]
        (is (str/includes? body "← Back to speakers"))
        (is (str/includes? body
                           "href=\"/program/public-widgets?q=Amara#speakers\""))
        (is (str/includes? body "Amara Devlin"))
        (is (str/includes? body "VP of AI"))
        (is (str/includes? body "North Banc"))
        (is (str/includes? body "Sessions (1)"))
        (is (str/includes? body "Banking AI"))
        (is (str/includes? body "Oct 14"))
        (is (str/includes? body "Main Stage"))))

    (testing "program agenda and session detail share title, placement, format, and track"
      (let [agenda (:body (get-page handler "/program/public-widgets"))
            detail (:body (get-page handler
                                    (str "/agenda/public-widgets/sessions/" (:id banking))))]
        (doseq [literal ["Banking AI" "Main Stage" "Format: Experience Report"
                         "Track: Developer Practices" "Show more"]]
          (is (str/includes? agenda literal) literal)
          (is (str/includes? detail literal) literal))
        (is (str/includes? agenda
                           (str "/agenda/public-widgets/sessions/" (:id banking))))
        (is (str/includes? detail "Session Details"))
        (is (str/includes? detail "Subsessions (0)"))
        (is (str/includes? detail
                           "href=\"/program/public-widgets\">← Back to agenda</a>"))
        (is (str/includes? detail
                           "href=\"/agenda/public-widgets/sessions\">Sessions list</a>"))))

    (testing "unpublished and unknown detail URLs are honest 404s"
      (is (= 404 (:status (get-page handler "/agenda/public-widgets/sessions/not-published"))))
      (is (= 404 (:status (get-page handler "/agenda/no-such-event/speakers")))))))

;; INTENT-TEST: EMB-005
(deftest public-program-keeps-speakers-agenda-and-every-configured-day-test
  (let [slug (str "three-day-program-" (events/random-suffix 8))
        event (events/create-eais-event!
                {:name "Three Day Program" :slug slug
                 :tz "America/New_York" :location "Charlotte, NC"
                 :starts-on (LocalDate/of 2026 10 14)
                 :ends-on (LocalDate/of 2026 10 16)
                 :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
                 :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
                "kaocha")
        body (:body ((server/create-app)
                     (mock/request :get (str "/program/" slug))))]
    (testing "one canonical page owns navigation, speaker search, and agenda"
      (doseq [literal [(str "href=\"/program/" slug "#speakers\"")
                       (str "href=\"/program/" slug "#agenda\"")
                       "id=\"speakers\"" "id=\"agenda\""
                       "Filter speakers" "Subscribe by calendar" "Session data"]]
        (is (str/includes? body literal) literal)))
    (testing "the selected day is visible through tabs and chevrons"
      (is (str/includes? body "agenda-day-controls"))
      (is (str/includes? body "agenda-day-tabs"))
      (is (str/includes? body (str "href=\"/program/" slug "?day=2026-10-15#agenda\"")))
      (is (str/includes? body "Next day →"))
      (is (str/includes? body "id=\"agenda-day-2026-10-14\""))
      (is (not (str/includes? body "id=\"agenda-day-2026-10-15\""))))
    (testing "the standalone agenda renderer selects the requested day"
      (let [agenda (str (view-schedule/agenda-days
                          event (schedule/agenda event) "2026-10-15"))]
        (is (str/includes? agenda "agenda-day-2026-10-15"))
        (is (not (str/includes? agenda "agenda-day-2026-10-14")))
        (is (str/includes? agenda "← Previous day"))
        (is (str/includes? agenda "Next day →"))))))

(deftest public-404-offers-only-privacy-safe-doors-test
  (let [{:keys [event declined]} (setup!)
        handler (server/create-app)
        declined-person-id (get-in declined [:speakers 0 :person-id])
        visible-404 (get-page handler
                              (str "/agenda/public-widgets/speakers/"
                                   declined-person-id))]
    (testing "an unpublished speaker on a visible event points back to its program"
      (is (= 404 (:status visible-404)))
      (is (str/includes? (:body visible-404) "/program/public-widgets"))
      (is (str/includes? (:body visible-404) "/cfps")))
    (testing "an unlisted event remains indistinguishable from no event"
      (events/set-unlisted! event true "kaocha")
      (let [hidden-name "Public Widget Summit"
            response (get-page handler
                               (str "/agenda/public-widgets/speakers/"
                                    declined-person-id))]
        (is (= 404 (:status response)))
        (is (not (str/includes? (:body response) "/program/public-widgets")))
        (is (not (str/includes? (:body response) hidden-name)))
        (is (str/includes? (:body response) "href=\"/events\""))
        (is (str/includes? (:body response) "/cfps"))))))

(deftest anonymous-cfps-lists-only-open-listed-events-test
  (let [{open-event :event} (setup!)
        closed (events/create-event!
                 {:name "Closed CFP Summit" :slug "closed-cfp-summit"
                  :tz "America/New_York" :location "Portland, OR"
                  :starts-on (LocalDate/of 2026 11 1)
                  :ends-on (LocalDate/of 2026 11 2)
                  :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
                  :cfp-closes-at (LocalDateTime/of 2021 1 1 0 0)}
                 "kaocha")
        archived (events/create-event!
                   {:name "Archived Open CFP" :slug "archived-open-cfp"
                    :tz "America/New_York" :location "Retired"
                    :starts-on (LocalDate/of 2026 12 1)
                    :ends-on (LocalDate/of 2026 12 2)
                    :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
                    :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
                   "kaocha")
        _ (events/archive-event! archived "kaocha")
        unlisted (events/create-event!
                   {:name "Secret Open CFP" :slug "secret-open-cfp"
                    :tz "America/New_York" :location "Hidden"
                    :starts-on (LocalDate/of 2026 12 1)
                    :ends-on (LocalDate/of 2026 12 2)
                    :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
                    :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
                   "kaocha")
        _ (events/set-unlisted! unlisted true "kaocha")
        response (get-page (server/create-app) "/cfps")
        body (:body response)]
    (is (= 200 (:status response)))
    (is (str/includes? body (:name open-event)))
    (is (str/includes? body "/cfp/public-widgets"))
    (is (str/includes? body "1 open call"))
    (is (str/includes? body "data-state=\"open\""))
    (is (str/includes? body "Open for proposals"))
    (is (str/includes? body "class=\"cfps-date\""))
    (is (str/includes? body "class=\"mo\">Oct"))
    (is (str/includes? body "Charlotte, NC"))
    (is (str/includes? body "Submit by"))
    (is (str/includes? body "datetime=\"2099-01-01T05:00:00Z\""))
    (is (str/includes? body "Jan 1, 2099 12:00 AM"))
    (is (str/includes? body "Submit to this CFP →"))
    (is (str/includes? body "Organized by"))
    (is (str/includes? body "Gene Kim"))
    (is (str/includes? body "/organizers/public-widgets"))
    (is (str/includes? body "Already on this program — 2 speakers"))
    (is (str/includes? body "Amara Devlin"))
    (is (str/includes? body "Bo Chen"))
    (is (str/includes? body "See all 2 speakers and the program →"))
    (is (str/includes? body "/program/public-widgets"))
    (is (= 1 (count (re-seq #"class=\"cfps-gallery\"" body))))
    (is (not (str/includes? body (:name closed))))
    (is (not (str/includes? body "/cfp/closed-cfp-summit")))
    (is (not (str/includes? body (:name archived))))
    (is (not (str/includes? body "/cfp/archived-open-cfp")))
    (is (not (str/includes? body (:name unlisted))))
    (is (not (str/includes? body "/cfp/secret-open-cfp")))))

(deftest anonymous-cfps-empty-state-is-explicit-test
  (let [response (get-page (server/create-app) "/cfps")
        body (:body response)]
    (is (= 200 (:status response)))
    (is (str/includes? body "No open calls right now"))
    (is (str/includes? body "Check back soon"))
    (is (not (str/includes? body "Open for proposals")))
    (is (not (str/includes? body "Submit to this CFP")))))

(deftest my-schedule-is-browser-owned-and-server-rendered-test
  (let [{:keys [event banking retail declined]} (setup!)
        handler (server/create-app)
        picked (str (:id banking))
        picks (str "?picks=" picked)
        body (:body (get-page handler (str "/agenda/public-widgets/my" picks)))
        ics (handler (mock/request :post "/agenda/public-widgets/my.ics"
                                   {"session-ids" (str picked "," (:id declined))}))]
    (is (str/includes? (:body (get-page handler (str "/program/public-widgets" picks)))
                       "data-my-schedule-toggle"))
    (is (str/includes? body "Banking AI"))
    (is (not (str/includes? body "Retail Forecasting")))
    (is (str/includes? body (str "data-my-schedule-selected=\"" picked "\"")))
    (is (str/includes? (slurp "resources/public/js/my-schedule.js") "localStorage"))
    (is (not (str/includes? (slurp "resources/public/js/my-schedule.js") "innerHTML")))
    (is (= "private, no-store" (get-in ics [:headers "Cache-Control"])))
    (is (str/includes? (:body ics) "BEGIN:VCALENDAR"))
    (is (not (str/includes? (:body ics) "Private Declined")))
    (is (= #{picked} (set (map :id (personal-schedule/selected-submissions event #{picked})))))))

(deftest my-schedule-cards-carry-complete-itinerary-context-test
  (let [{:keys [event banking]} (setup!)
        body (str (view-my-schedule/my-schedule-page
                    event (catalog/sessions event) #{(str (:id banking))}))]
    (is (str/includes? body "Banking AI"))
    (is (str/includes? body "Oct 14"))
    (is (str/includes? body "9:00am-9:25am"))
    (is (str/includes? body "Main Stage"))
    (is (str/includes? body "measurable result for attendees"))
    (is (str/includes? body "Amara Devlin"))
    (is (str/includes? body "VP of AI"))
    (is (str/includes? body "North Banc"))))

(deftest itinerary-is-day-grouped-and-calendar-export-is-observable-test
  (let [{:keys [event banking]} (setup!)
        sessions (catalog/sessions event)
        second-day (-> (second sessions)
                       (assoc :day "Day 2 — Oct 15"
                              :day-key "2026-10-15"
                              :time "10:30am–10:55am"))
        itinerary-body (str (view-my-schedule/public-itinerary-page
                              event [(first sessions) second-day] #{}))
        selected-body (str (view-my-schedule/my-schedule-page
                             event sessions #{(str (:id banking))}))]
    (testing "the browse surface is a chronological itinerary with day sections"
      (is (str/includes? itinerary-body "Public itinerary"))
      (is (str/includes? itinerary-body "grouped by event day"))
      (is (< (str/index-of itinerary-body "Oct 14")
             (str/index-of itinerary-body "Day 2 — Oct 15")))
      (is (< (str/index-of itinerary-body "Banking AI")
             (str/index-of itinerary-body "Retail Forecasting")))
      (doseq [literal ["Developer Practices" "Banking AI"
                       "detailed public description" "9:00am-9:25am"
                       "Main Stage" "Amara Devlin" "VP of AI" "North Banc"]]
        (is (str/includes? itinerary-body literal) literal)))

    (testing "calendar export is an explicit selected-ID POST"
      (is (str/includes? selected-body "method=\"post\""))
      (is (str/includes? selected-body "action=\"/agenda/public-widgets/my.ics\"")))))

(deftest public-itinerary-is-anonymous-and-shows-the-published-schedule-test
  (let [{:keys [event banking retail declined]} (setup!)
        late (add-unplaced! event)
        room (first (store/rooms-for-event (:id event)))
        _ (schedule/place! event (:id late)
                           {:day day :start "11:30" :room-id (:id room)} "gene@example.com")
        _ (schedule/place! event (:id retail)
                           {:day "2026-10-15" :start "10:30" :room-id (:id room)} "gene@example.com")
        handler (server/create-app)
        itinerary (:body (get-page handler "/agenda/public-widgets/itinerary"))
        program (:body (get-page handler "/program/public-widgets"))]
    (testing "anonymous visitors receive the published event itinerary, not a personal schedule"
      (is (str/includes? itinerary "Public itinerary"))
      (is (str/includes? itinerary "grouped by event day"))
      (is (str/includes? itinerary "Add to My schedule"))
      (is (not (str/includes? itinerary "Private Declined"))))
    (testing "every placed public session is grouped by day and ordered by start"
      (doseq [literal ["Day 1 — Oct 14" "Day 2 — Oct 15"
                       "Banking AI" "Retail Forecasting" "Platform Migration"
                       "9:00am-9:25am" "10:30am-12:00pm" "11:30am-11:55am"
                       "Main Stage" "Developer Practices" "Amara Devlin"]]
        (is (str/includes? itinerary literal) literal))
      (is (< (str/index-of itinerary "Day 1 — Oct 14")
             (str/index-of itinerary "Day 2 — Oct 15")))
      (is (< (str/index-of itinerary "Banking AI")
             (str/index-of itinerary "Platform Migration"))))
    (testing "the public program links to the shareable itinerary"
      (is (str/includes? program
                         "href=\"/agenda/public-widgets/itinerary\">Itinerary</a>")))))

(deftest session-widget-field-and-branding-configuration-test
  (let [{:keys [banking]} (setup!)
        handler (server/create-app)
        body (:body
               (get-page
                 handler
                 (str "/agenda/public-widgets/sessions?track=Developer+Practices"
                      "&accent=%23123456&theme=compact&fields=schedule%2Ctags")))]
    (is (str/includes? body "Banking AI"))
    (is (str/includes? body "data-embed-theme=\"compact\""))
    (is (str/includes? body "border-top: 6px solid #123456"))
    (is (str/includes? body "name=\"accent\""))
    (is (str/includes? body "value=\"#123456\""))
    (is (str/includes? body "name=\"fields\""))
    (is (str/includes? body "value=\"schedule,tags\""))
    (is (str/includes? body "accent=%23123456"))
    (is (str/includes? body "fields=schedule%2Ctags"))
    (is (str/includes? body "Main Stage"))
    (is (str/includes? body "Track: Developer Practices"))
    (is (not (str/includes? body "detailed public description")))
    (is (not (str/includes? body "Amara Devlin")))
    (is (not (str/includes? body "Retail Forecasting")))
    (is (str/includes? body (str "/sessions/" (:id banking))))))

(deftest configured-session-detail-roundtrip-preserves-widget-state-test
  (let [{:keys [event banking]} (setup!)
        handler (server/create-app)
        state "q=Banking&accent=%23123456&theme=compact&fields=schedule%2Ctags"
        sessions (:body (get-page handler (str "/agenda/" (:slug event) "/sessions?" state)))
        detail-path (str "/agenda/" (:slug event) "/sessions/" (:id banking)
                         "?from=sessions&" state)
        detail (:body (get-page handler detail-path))]
    (is (str/includes? sessions
                       (str "/sessions/" (:id banking)
                            "?from=sessions&amp;q=Banking&amp;accent=%23123456"
                            "&amp;theme=compact&amp;fields=schedule%2Ctags")))
    (is (str/includes? detail "data-embed-theme=\"compact\""))
    (is (str/includes? detail "border-top: 6px solid #123456"))
    (is (str/includes? detail "Main Stage"))
    (is (str/includes? detail "Track: Developer Practices"))
    (is (not (str/includes? detail "detailed public description")))
    (is (not (str/includes? detail "Amara Devlin")))
    (is (str/includes? detail "← Back to sessions"))
    (is (str/includes? detail
                       (str "href=\"/agenda/" (:slug event) "/sessions?"
                            "q=Banking&amp;accent=%23123456&amp;theme=compact"
                            "&amp;fields=schedule%2Ctags\"")))))

(deftest public-widgets-read-live-organizer-state-without-republishing-test
  (let [{:keys [event banking]} (setup!)
        handler (server/create-app)
        session-path (str "/agenda/public-widgets/sessions/" (:id banking))
        before (:body (get-page handler session-path))]
    (is (str/includes? before "Banking AI"))
    (is (:ok (submissions/update-session-title!
               (:id banking) "Banking AI — live update" "gene@example.com")))
    (let [sessions (:body (get-page handler "/agenda/public-widgets/sessions"))
          detail (:body (get-page handler session-path))
          program (:body (get-page handler "/program/public-widgets"))]
      (doseq [body [sessions detail program]]
        (is (str/includes? body "Banking AI — live update")))
      (is (= "Banking AI — live update"
             (:title (catalog/session-by-id event (:id banking))))))))

(deftest show-more-continues-the-copy-instead-of-reprinting-it-test
  ;; The old markup put the WHOLE abstract in the preview AND in the details
  ;; body, so an expanded card showed its opening twice.
  (let [{:keys [event banking]} (setup!)
        handler (server/create-app)
        session (catalog/session-by-id event (:id banking))
        abstract (:description session)
        body (:body (get-page handler
                              (str "/agenda/public-widgets/sessions/"
                                   (:id banking))))
        opening (subs abstract 0 60)
        occurrences (count (re-seq
                             (re-pattern (java.util.regex.Pattern/quote opening))
                             body))]
    (testing "long copy still offers the control"
      (is (> (count abstract) 200))
      (is (str/includes? body "Show more")))
    (testing "the opening is printed exactly once, not duplicated"
      (is (= 1 occurrences)))
    (testing "expanding reveals the REMAINDER, so the full text is still readable"
      (is (str/includes? body "back to its board at the end of the programme."))
      (is (str/includes? body "…")))))
