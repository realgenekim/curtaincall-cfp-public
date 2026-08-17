(ns cfp-scheduler-killer.announce-test
  "The public, per-speaker HERO announce page
   (/agenda/:slug/speakers/:person-id/announce). One accepted speaker is the
   star; the OTHER real accepted speakers of the same event fill a peer
   gallery. Only real accepted/announced speakers appear — hero AND gallery."
  (:require
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.inform :as inform]
   [cfp-scheduler-killer.public-catalog :as catalog]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock])
  (:import
   (java.io ByteArrayInputStream)
   (java.time LocalDate LocalDateTime)
   (javax.imageio ImageIO)))

(use-fixtures :each with-temp-store)

(defn- make-submission! [event fields {:keys [title speaker email company track headshot]}]
  (let [params {:answer-talk-title title
                :answer-abstract (str title " is a detailed public description of the work "
                                      "and the measurable result for attendees.")
                :answer-session-format "Experience Report"
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
  (let [event (events/create-event!
                {:name "Enterprise AI Summit Charlotte"
                 :slug "announce-demo"
                 :tz "America/New_York" :location "Charlotte, NC"
                 :website-url "https://example.com/summit"
                 :starts-on (LocalDate/of 2026 10 14)
                 :ends-on (LocalDate/of 2026 10 15)
                 :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
                 :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
                "kaocha")
        _copy (events/update-event-details!
                (:id event)
                {:cfp-intro "A practical summit for leaders building enterprise AI in production."}
                "kaocha")
        committee-id (:id (first (events/committees-for-event (:id event))))
        _chair (committees/add-member! committee-id
                                       {:name "Gene Kim" :email "gene@example.com" :role "chair"}
                                       "kaocha")
        fields (:fields (events/form-for-event (:id event)))
        yegge (make-submission! event fields
                                {:title "The LLM Coding Frontier" :speaker "Steve Yegge"
                                 :email "steve@example.com" :company "Sourcegraph"
                                 :track "Developer Practices"
                                 :headshot "https://images.example.com/yegge.jpg"})
        warner (make-submission! event fields
                                 {:title "Shipping AI at Scale" :speaker "Dustin Warner"
                                  :email "dustin@example.com" :company "Warner Labs"
                                  :track "Business Outcomes"})
        declined (make-submission! event fields
                                   {:title "Rejected Talk" :speaker "Not A Speaker"
                                    :email "no@example.com" :company "Hidden Co"
                                    :track "Developer Practices"})]
    (doseq [submission [yegge warner]]
      (reviews/set-status! (:id submission) "Accepted" "gene@example.com")
      (inform/inform! event (store/submission-by-id (:id submission)) "gene@example.com"))
    (reviews/set-status! (:id declined) "Declined" "gene@example.com")
    (inform/inform! event (store/submission-by-id (:id declined)) "gene@example.com")
    {:event (events/event-by-slug "announce-demo")}))

(defn- get-page [handler path]
  (handler (mock/request :get path)))

(defn- speaker-by-name [event name]
  (some #(when (= name (:name %)) %) (catalog/public-speakers event)))

(defn- announce-path
  "The CANONICAL share URL: the speaker's permanent slug once minted (inform
   mints it), else their UUID. The UUID form still resolves — it 301s here.
   Redirect behaviour itself is covered by speaker-slug-test."
  [event speaker]
  (str "/agenda/" (:slug event) "/speakers/"
       (or (:slug speaker) (:id speaker)) "/announce"))

(defn- card-path [event speaker]
  (str "/agenda/" (:slug event) "/speakers/"
       (or (:slug speaker) (:id speaker)) "/card.png"))

(deftest organizer-brag-page-composes-the-public-event-story-test
  (setup!)
  (let [handler (server/create-app)
        response (get-page handler "/program/announce-demo/announce")
        body (:body response)
        card-response (get-page handler "/program/announce-demo/card.png")
        card-image (ImageIO/read (ByteArrayInputStream. (:body card-response)))]
    (is (= 200 (:status response)))
    (testing "the hero is the event's existing pitch, not invented copy"
      (is (str/includes? body "Enterprise AI Summit Charlotte"))
      (is (str/includes? body "A practical summit for leaders building enterprise AI in production."))
      (is (str/includes? body "Share this event:")))
    (testing "every speaker from the public program projection has a face tile"
      (doseq [speaker (catalog/public-speakers
                        (events/event-by-slug "announce-demo"))]
        (is (str/includes? body (:name speaker))))
      (is (str/includes? body "https://images.example.com/yegge.jpg"))
      (is (str/includes? body "cfp-featured-card")))
    (testing "the open call and the organizer's seeded post are shareable"
      (is (str/includes? body "A couple of open slots remain"))
      (is (str/includes? body "/cfp/announce-demo"))
      (is (str/includes? body "I&apos;m so proud that the Enterprise AI Summit Charlotte is happening"))
      (doseq [label ["LinkedIn" "X" "Copy link" "Copy post text"]]
        (is (str/includes? body label))))
    (testing "browser and unfurl assets are cache-busted"
      (is (re-find #"/program/announce-demo/card\.png\?v=\d+" body))
      (is (re-find #"/css/app\.css\?v=\d+" body)))
    (testing "the event OG route is an exact 1200x630 PNG"
      (is (= 200 (:status card-response)))
      (is (= "image/png" (get-in card-response [:headers "Content-Type"])))
      (is (= 1200 (.getWidth card-image)))
      (is (= 630 (.getHeight card-image))))))

(deftest organizer-brag-page-hides-cfp-cta-after-close-test
  (setup!)
  (with-redefs [submissions/accepting? (constantly false)]
    (let [response (get-page (server/create-app) "/program/announce-demo/announce")]
      (is (= 200 (:status response)))
      (is (not (str/includes? (:body response) "A couple of open slots remain")))
      (is (not (str/includes? (:body response) "Call for speakers"))))))

(deftest announce-hero-page-renders-hero-and-peer-gallery-test
  (setup!)
  (let [handler (server/create-app)
        event (events/event-by-slug "announce-demo")
        yegge (speaker-by-name event "Steve Yegge")
        warner (speaker-by-name event "Dustin Warner")
        response (get-page handler (announce-path event yegge))
        body (:body response)]
    (testing "the page is a public 200 with public chrome, no auth wall"
      (is (= 200 (:status response)))
      (is (not (str/includes? body "class=\"sidebar\""))))

    (testing "the HERO speaker (Steve Yegge) is the star of the page"
      (is (str/includes? body "Steve Yegge"))
      (is (str/includes? body "The LLM Coding Frontier"))
      (is (str/includes? body "Sourcegraph"))
      ;; celebratory first-person copy in the hero's voice (Hiccup escapes ')
      (is (str/includes? body "so excited to be speaking at"))
      (is (str/includes? body "Enterprise AI Summit Charlotte")))

    (testing "the peer gallery — …amongst these amazing people — holds the OTHERS"
      (is (str/includes? body "amongst these amazing people"))
      (is (str/includes? body "Dustin Warner"))
      (is (str/includes? body "Warner Labs")))

    (testing "ACCURACY GUARD: non-accepted people never appear (hero or gallery)"
      (is (not (str/includes? body "Not A Speaker")))
      (is (not (str/includes? body "Rejected Talk")))
      ;; committee notes never leak to a public surface
      (is (not (str/includes? body "Never public"))))

    (testing "Open Graph + Twitter meta feature the INDIVIDUAL hero speaker"
      (is (str/includes? body "og:title"))
      ;; personal, first-person unfurl title (Hiccup escapes the apostrophe)
      (is (str/includes? body "speaking at Enterprise AI Summit Charlotte!"))
      (is (str/includes? body "og:description"))
      (is (str/includes? body "og:url"))
      ;; both networks get the generated, full-width speaker card
      (is (re-find (re-pattern (str (card-path event yegge) "\\?ts=\\d+"))
                   body))
      (is (str/includes? body "og:image:width"))
      (is (str/includes? body "og:image:height"))
      (is (str/includes? body "twitter:card"))
      (is (str/includes? body (announce-path event yegge))))

    (testing "the roster shown (hero + peers) matches the public catalog exactly"
      (let [names (set (map :name (catalog/public-speakers event)))]
        (is (= #{"Steve Yegge" "Dustin Warner"} names))))

    (testing "symmetry: Warner's page makes Warner the hero and Yegge the peer"
      (let [warner-body (:body (get-page handler (announce-path event warner)))]
        (is (str/includes? warner-body "Shipping AI at Scale"))
        (is (str/includes? warner-body "amongst these amazing people"))
        (is (str/includes? warner-body "Steve Yegge"))
        (is (not (str/includes? warner-body "Not A Speaker")))))))

(deftest published-speaker-card-is-public-cacheable-and-conditional-test
  (setup!)
  (let [handler (server/create-app)
        event (events/event-by-slug "announce-demo")
        warner (speaker-by-name event "Dustin Warner")
        path (card-path event warner)
        response (get-page handler path)
        image (ImageIO/read (ByteArrayInputStream. (:body response)))
        etag (get-in response [:headers "ETag"])]
    (testing "a published speaker gets a complete PNG response"
      (is (= 200 (:status response)))
      (is (= "image/png" (get-in response [:headers "Content-Type"])))
      (is (= "public, max-age=3600"
             (get-in response [:headers "Cache-Control"])))
      (is (re-matches #"\"[0-9a-f]{40}\"" etag))
      (is (= 1200 (.getWidth image)))
      (is (= 630 (.getHeight image))))

    (testing "the UUID spelling serves directly rather than redirecting"
      (is (= 200 (:status (get-page handler
                                    (str "/agenda/" (:slug event) "/speakers/"
                                         (:id warner) "/card.png"))))))

    (testing "a matching conditional request is an empty 304"
      (let [conditional (handler (-> (mock/request :get path)
                                     (mock/header "If-None-Match" etag)))]
        (is (= 304 (:status conditional)))
        (is (= "" (:body conditional)))))

    (testing "an unpublished speaker is a bare image 404"
      (let [missing (get-page handler
                              (str "/agenda/" (:slug event)
                                   "/speakers/not-published/card.png"))]
        (is (= 404 (:status missing)))
        (is (= "" (:body missing)))))))

(deftest announce-non-accepted-person-is-not-a-valid-hero-test
  (setup!)
  (let [handler (server/create-app)]
    (testing "a made-up / non-accepted person-id is an honest 404, never a hero"
      (is (= 404 (:status (get-page handler
                                    "/agenda/announce-demo/speakers/not-a-real-id/announce")))))))

(deftest announce-unknown-event-is-honest-404-test
  (setup!)
  (let [handler (server/create-app)
        event (events/event-by-slug "announce-demo")
        yegge (speaker-by-name event "Steve Yegge")]
    (is (= 404 (:status (get-page handler
                                  (str "/agenda/no-such-event/speakers/" (:id yegge) "/announce")))))))
