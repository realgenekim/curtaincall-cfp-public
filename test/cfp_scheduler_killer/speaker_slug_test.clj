(ns cfp-scheduler-killer.speaker-slug-test
  "The speaker permalink: /agenda/:event/speakers/mik-kersten/announce.

   A share URL is the one thing a speaker copies into a post that outlives the
   conference, so it must read like a person and never change. The slug is
   minted ONCE — at inform time, the publish moment — and is forever after: a
   rename never re-derives it. The person UUID stays canonical internally and
   never stops resolving; it 301s to the slug once one exists."
  (:require
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.inform :as inform]
   [cfp-scheduler-killer.people :as people]
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
   (java.time LocalDate LocalDateTime)))

(use-fixtures :each with-temp-store)

(defn- make-submission! [event fields {:keys [title speaker email company]}]
  (let [params {:answer-talk-title title
                :answer-abstract (str title " is a detailed public description of the work "
                                      "and the measurable result for attendees.")
                :answer-session-format "Experience Report"
                :answer-track "Developer Practices"
                :answer-org-size ">10,000"
                :answer-industry "Developer Practices"
                :answer-ai-transformation-history "Since 2023"
                :answer-measurable-outcomes "Measured outcomes"
                :answer-notes-to-committee "Never public"
                :speaker-name speaker :speaker-email email
                :speaker-title "VP of AI" :speaker-org company
                :speaker-bio (str speaker " has led enterprise AI programs for a decade.")
                :speaker-headshot-url "https://images.example.com/headshot.jpg"}]
    (submissions/create-submission!
      event
      (submissions/parse-answers fields params)
      (submissions/parse-speaker params)
      "form"
      "kaocha")))

(defn- create-event! []
  (let [event (events/create-event!
                {:name "Enterprise AI Summit Charlotte"
                 :slug "slug-demo"
                 :tz "America/New_York" :location "Charlotte, NC"
                 :website-url "https://example.com/summit"
                 :starts-on (LocalDate/of 2026 10 14)
                 :ends-on (LocalDate/of 2026 10 15)
                 :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
                 :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
                "kaocha")
        committee-id (:id (first (events/committees-for-event (:id event))))]
    (committees/add-member! committee-id
                            {:name "Gene Kim" :email "gene@example.com" :role "chair"}
                            "kaocha")
    event))

(defn- submit! [event opts]
  (make-submission! event (:fields (events/form-for-event (:id event))) opts))

(defn- accept-and-inform! [event submission]
  (reviews/set-status! (:id submission) "Accepted" "gene@example.com")
  (inform/inform! event (store/submission-by-id (:id submission)) "gene@example.com"))

(defn- slug-facts []
  (filterv #(= "person.slug-set" (:type %)) (store/read-events)))

(defn- person-id-for [email] (:id (people/by-email email)))

(defn- get-page [handler path] (handler (mock/request :get path)))

;; --- Minting ----------------------------------------------------------------

(deftest ensure-slug-mints-once-and-is-forever-test
  (let [event (create-event!)
        _ (submit! event {:title "Flow Metrics at Scale" :speaker "Mik Kersten"
                          :email "mik@example.com" :company "Tasktop"})
        person-id (person-id-for "mik@example.com")]

    (testing "an un-slugged person has no slug until someone mints one"
      (is (nil? (:slug (store/person-by-id person-id))))
      (is (zero? (count (slug-facts)))))

    (testing "the slug is derived from the person's name"
      (is (= "mik-kersten" (people/ensure-slug! person-id "kaocha")))
      (is (= "mik-kersten" (:slug (store/person-by-id person-id))))
      (is (= 1 (count (slug-facts)))))

    (testing "minting again returns the SAME slug and appends NO new fact"
      (is (= "mik-kersten" (people/ensure-slug! person-id "kaocha")))
      (is (= 1 (count (slug-facts)))))

    (testing "the slug is findable by itself — a global, cross-event address"
      (is (= person-id (:id (store/person-by-slug "mik-kersten"))))
      (is (nil? (store/person-by-slug "no-such-person"))))))

(deftest ensure-slug-suffixes-collisions-test
  (let [event (create-event!)
        _ (submit! event {:title "Flow Metrics at Scale" :speaker "Mik Kersten"
                          :email "mik@tasktop.example" :company "Tasktop"})
        _ (submit! event {:title "A Different Talk Entirely" :speaker "Mik Kersten"
                          :email "mik@planview.example" :company "Planview"})
        first-id (person-id-for "mik@tasktop.example")
        second-id (person-id-for "mik@planview.example")]

    (testing "two different humans share a name; they never share an address"
      (is (not= first-id second-id))
      (is (= "mik-kersten" (people/ensure-slug! first-id "kaocha")))
      (is (= "mik-kersten-2" (people/ensure-slug! second-id "kaocha")))
      (is (= first-id (:id (store/person-by-slug "mik-kersten"))))
      (is (= second-id (:id (store/person-by-slug "mik-kersten-2")))))

    (testing "each of them keeps their own slug on a repeat call"
      (is (= "mik-kersten" (people/ensure-slug! first-id "kaocha")))
      (is (= "mik-kersten-2" (people/ensure-slug! second-id "kaocha")))
      (is (= 2 (count (slug-facts)))))))

(deftest ensure-slug-without-a-name-mints-nothing-test
  (let [event (create-event!)
        _ (submit! event {:title "An Anonymous Talk" :speaker "!!!"
                          :email "anon@example.com" :company "Nowhere"})
        person-id (person-id-for "anon@example.com")]
    (testing "a name that slugifies to nothing yields no slug and no fact —
              the UUID URL keeps working, which is why it stays canonical"
      (is (nil? (people/ensure-slug! person-id "kaocha")))
      (is (zero? (count (slug-facts)))))))

(deftest inform-mints-the-slug-test
  (let [event (create-event!)
        submission (submit! event {:title "Flow Metrics at Scale" :speaker "Mik Kersten"
                                   :email "mik@example.com" :company "Tasktop"})
        person-id (person-id-for "mik@example.com")]
    (testing "before the publish moment there is no public address"
      (is (nil? (:slug (store/person-by-id person-id)))))

    (accept-and-inform! event submission)

    (testing "informing IS the publish moment — the speaker gets their permalink"
      (is (= "mik-kersten" (:slug (store/person-by-id person-id))))
      (is (= 1 (count (slug-facts)))))

    (testing "the public catalog carries the slug alongside the UUID"
      (let [speaker (first (catalog/public-speakers (events/event-by-slug "slug-demo")))]
        (is (= "Mik Kersten" (:name speaker)))
        (is (= "mik-kersten" (:slug speaker)))
        (is (= person-id (:id speaker)))))))

;; --- The URLs ---------------------------------------------------------------

(deftest announce-page-serves-the-slug-url-test
  (let [event (create-event!)
        submission (submit! event {:title "Flow Metrics at Scale" :speaker "Mik Kersten"
                                   :email "mik@example.com" :company "Tasktop"})
        _ (accept-and-inform! event submission)
        handler (server/create-app)
        person-id (person-id-for "mik@example.com")
        response (get-page handler "/agenda/slug-demo/speakers/mik-kersten/announce")
        body (:body response)]

    (testing "the friendly URL is a 200 with the hero on it"
      (is (= 200 (:status response)))
      (is (str/includes? body "Mik Kersten"))
      (is (str/includes? body "Flow Metrics at Scale")))

    (testing "og:url advertises the SLUG — that is the link people copy"
      (is (str/includes? body "/speakers/mik-kersten/announce"))
      (is (not (str/includes? body person-id))))))

(deftest uuid-urls-redirect-to-the-slug-test
  (let [event (create-event!)
        submission (submit! event {:title "Flow Metrics at Scale" :speaker "Mik Kersten"
                                   :email "mik@example.com" :company "Tasktop"})
        _ (accept-and-inform! event submission)
        handler (server/create-app)
        person-id (person-id-for "mik@example.com")]

    (testing "the old announce permalink 301s to the friendly one"
      (let [response (get-page handler (str "/agenda/slug-demo/speakers/"
                                            person-id "/announce"))]
        (is (= 301 (:status response)))
        (is (= "/agenda/slug-demo/speakers/mik-kersten/announce"
               (get-in response [:headers "Location"])))))

    (testing "so does the speaker detail page"
      (let [response (get-page handler (str "/agenda/slug-demo/speakers/" person-id))]
        (is (= 301 (:status response)))
        (is (= "/agenda/slug-demo/speakers/mik-kersten"
               (get-in response [:headers "Location"])))))

    (testing "the canonical slug URLs serve, never redirect"
      (is (= 200 (:status (get-page handler "/agenda/slug-demo/speakers/mik-kersten"))))
      (is (= 200 (:status (get-page handler
                                    "/agenda/slug-demo/speakers/mik-kersten/announce")))))

    (testing "a stranger is still an honest 404, not a redirect"
      (is (= 404 (:status (get-page handler
                                    "/agenda/slug-demo/speakers/nobody-at-all/announce")))))))

(deftest un-slugged-speaker-still-serves-on-the-uuid-test
  ;; The production roster predates slugs. Those URLs must keep working: no
  ;; 404, no redirect loop — just the page, on the UUID, until someone mints.
  (let [event (create-event!)
        submission (submit! event {:title "Flow Metrics at Scale" :speaker "Mik Kersten"
                                   :email "mik@example.com" :company "Tasktop"})]
    (reviews/set-status! (:id submission) "Accepted" "gene@example.com")
    ;; Notified WITHOUT the mint hook — exactly the shape of an older record.
    (store/append! {:type "submission.notified" :actor "kaocha" :event-id (:id event)
                    :payload {:submission-id (:id submission)
                              :status-at-notify "Accepted"
                              :to "mik@example.com"
                              :subject "You're in"
                              :at (store/now-iso)}})
    (let [handler (server/create-app)
          person-id (person-id-for "mik@example.com")]
      (is (nil? (:slug (store/person-by-id person-id))))
      (is (= 200 (:status (get-page handler (str "/agenda/slug-demo/speakers/"
                                                 person-id "/announce")))))
      (is (= 200 (:status (get-page handler (str "/agenda/slug-demo/speakers/"
                                                 person-id))))))))
