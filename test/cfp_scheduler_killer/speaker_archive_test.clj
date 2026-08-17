(ns cfp-scheduler-killer.speaker-archive-test
  (:require
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.inform :as inform]
   [cfp-scheduler-killer.public-catalog :as public-catalog]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock])
  (:import
   (java.time LocalDate LocalDateTime)
   (org.jsoup Jsoup)))

(use-fixtures :each with-temp-store)

(defn- submission!
  [event title speaker email headshot]
  (let [fields (:fields (events/form-for-event (:id event)))
        params {:answer-talk-title title
                :answer-abstract (str title " abstract")
                :answer-session-format "Talk"
                :answer-track "Engineering"
                :speaker-name speaker
                :speaker-email email
                :speaker-bio (str speaker " builds dependable systems.")
                :speaker-headshot-url headshot}]
    (submissions/create-submission!
      event
      (submissions/parse-answers fields params)
      (submissions/parse-speaker params)
      "form"
      "kaocha")))

(deftest public-speaker-archive-shows-accepted-speaker-cards-test
  (let [event (events/create-event!
                {:name "Speaker Archive Summit"
                 :slug "speaker-archive"
                 :tz "America/New_York"
                 :starts-on (LocalDate/of 2026 10 14)
                 :ends-on (LocalDate/of 2026 10 14)
                 :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
                 :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
                "kaocha")
        accepted (submission! event "Systems That Heal" "Amara Devlin"
                              "amara@example.com" "https://images.example.com/amara.jpg")
        declined (submission! event "Private Proposal" "Declined Speaker"
                              "declined@example.com" nil)]
    (reviews/set-status! (:id accepted) "Accepted" "kaocha")
    (inform/inform! event (store/submission-by-id (:id accepted)) "kaocha")
    (reviews/set-status! (:id declined) "Declined" "kaocha")
    (inform/inform! event (store/submission-by-id (:id declined)) "kaocha")
    (let [handler (server/create-app)
          response (handler (mock/request :get "/agenda/speaker-archive/gallery"))
          body (:body response)
          detail-path "/agenda/speaker-archive/speakers/amara-devlin"
          detail-body (:body (handler (mock/request :get detail-path)))]
      (testing "the archive is public and contains the complete accepted-speaker card"
        (is (= 200 (:status response)))
        (is (str/includes? body "Speakers"))
        (is (str/includes? body "Amara Devlin"))
        (is (str/includes? body "https://images.example.com/amara.jpg"))
        (is (str/includes? body detail-path))
        (is (str/includes? detail-body "Systems That Heal"))
        (is (str/includes? detail-body "builds dependable systems")))
      (testing "non-accepted speakers never enter the public archive"
        (is (not (str/includes? body "Declined Speaker")))
        (is (not (str/includes? body "Private Proposal")))))))

;; INTENT-TEST: EMB-003
(deftest every-public-speaker-bio-link-resolves-test
  (let [event (events/create-event!
                {:name "Resolvable Speaker Summit"
                 :slug "resolvable-speakers"
                 :tz "America/New_York"
                 :starts-on (LocalDate/of 2026 10 14)
                 :ends-on (LocalDate/of 2026 10 14)
                 :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
                 :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
                "kaocha")
        accepted (submission! event "Systems That Heal" "Amara Devlin"
                              "amara@example.com" nil)
        _ (reviews/set-status! (:id accepted) "Accepted" "kaocha")
        _ (inform/inform! event (store/submission-by-id (:id accepted)) "kaocha")
        _ (events/announce-speaker!
            event
            {:name "Jondavid \"JD\" Black"
             :org "Northrop Grumman"
             :title "CIDO Chief Engineer"
             :headshot-url "https://events.example.com/jondavid-jd-black.jpg"}
            "kaocha")
        ;; Legacy announcement facts predate durable ids. Their public handle
        ;; must still be content-derived rather than assigned by render order.
        _ (store/append! {:type "event.speaker-announced"
                          :actor "legacy-import"
                          :event-id (:id event)
                          :payload {:event-id (:id event)
                                    :name "Legacy Speaker"
                                    :org "Archive Inc"
                                    :title "Historian"
                                    :headshot-url ""
                                    :at (store/now-iso)}})
        event (events/event-by-slug "resolvable-speakers")
        handler (server/create-app)
        response (handler (mock/request :get "/agenda/resolvable-speakers/gallery"))
        document (Jsoup/parse (:body response))
        cards (vec (.select document ".cfp-featured-card"))
        links (mapv #(-> % (.selectFirst ".cfp-featured-name a") (.attr "href"))
                    cards)
        jd-card (some #(when (str/includes? (.text %) "Jondavid") %) cards)
        jd-link (some-> jd-card (.selectFirst ".cfp-featured-name a") (.attr "href"))
        manual-handles (fn [event*]
                         (->> (public-catalog/public-speakers event*)
                              (filter :manual?)
                              (map (juxt :name #(or (:slug %) (:id %))))
                              (into {})))
        reordered-event (update-in event [:settings :announced-speakers]
                                   #(vec (reverse %)))]
    (testing "every listed speaker receives a stable non-positional handle"
      (is (= 200 (:status response)))
      (is (= (count (public-catalog/public-speakers event)) (count links)))
      (is (every? #(and (not (str/blank? %))
                        (not (str/includes? % "/speakers/?")))
                  links))
      (is (every? #(not (str/blank? %)) (vals (manual-handles event))))
      (is (= (manual-handles event) (manual-handles event))
          "the same announcement content derives the same handles on every render")
      (is (= (manual-handles event) (manual-handles reordered-event))
          "changing insertion order cannot change a speaker's identity"))
    (testing "every Read bio link resolves"
      (doseq [href links]
        (is (= 200 (:status (handler (mock/request :get href)))) href)))
    (testing "Jondavid's link resolves to Jondavid's matching profile"
      (is (some? jd-card))
      (is (not (str/blank? jd-link)))
      (is (not (str/includes? jd-link "/speakers/?")))
      (let [profile (handler (mock/request :get jd-link))
            profile-text (.text (Jsoup/parse (:body profile)))]
        (is (= 200 (:status profile)))
        (is (str/includes? profile-text "Jondavid \"JD\" Black"))
        (is (str/includes? profile-text "Northrop Grumman"))))))
