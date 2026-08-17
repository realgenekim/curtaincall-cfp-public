(ns cfp-scheduler-killer.speaker-identity-projection-contract-test
  (:require
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.exports :as exports]
   [cfp-scheduler-killer.inform :as inform]
   [cfp-scheduler-killer.portal :as portal]
   [cfp-scheduler-killer.public-catalog :as public-catalog]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.speakers :as speakers]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.data.json :as json]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock]))

(use-fixtures :each with-temp-store)

(defn- response-json [handler path]
  (json/read-str (str (:body (handler (mock/request :get path))))))

(deftest submitted-snapshot-stays-frozen-while-public-identity-follows-profile-test
  (let [event (events/create-event!
                {:name "Identity Projection Summit"
                 :slug "identity-projection"
                 :tz "America/New_York"
                 :presenter-visibility-mode "visible"}
                "kaocha")
        fields (:fields (events/form-for-event (:id event)))
        params {:answer-talk-title "Identity without archive corruption"
                :answer-abstract "Keep historical claims and live identity separate."
                :answer-session-format "Experience Report"
                :answer-track "Developer Practices"
                :answer-org-size ">10,000"
                :answer-industry "Technology"
                :answer-ai-transformation-history "2025."
                :answer-measurable-outcomes "One identity across every public surface."
                :speaker-name "Priya Snapshot"
                :speaker-email "priya-identity@example.com"
                :speaker-title "Snapshot title"
                :speaker-org "Snapshot Org"
                :speaker-bio "Snapshot bio"
                :speaker-headshot-url "https://example.com/snapshot.jpg"
                :speaker-linkedin "https://linkedin.com/in/snapshot"
                :speaker-sessionize-url "https://sessionize.com/priya-snapshot"}
        submission (submissions/create-submission!
                     event
                     (submissions/parse-answers fields params)
                     (submissions/parse-speakers params)
                     "form"
                     "kaocha")
        person (store/person-by-email "priya-identity@example.com")
        person-id (:id person)
        _ (reviews/set-status! (:id submission) "Accepted" "organizer@example.com")
        _ (inform/inform! event (store/submission-by-id (:id submission))
                          "organizer@example.com")
        _ (speakers/rename! (:id event) person-id "Priya Current"
                            "organizer@example.com")
        _ (portal/update-profile!
            person-id
            {:tagline "Current tagline"
             :org "Current Org"
             :bio "Current bio"
             :headshot-url "https://example.com/current.jpg"
             :linkedin-url "https://linkedin.com/in/current"
             :website-url "https://current.example.com"}
            "priya-identity@example.com")
        event (events/event-by-slug "identity-projection")
        snapshot-speaker (first (:speakers (store/submission-by-id (:id submission))))
        roster-speaker (first (speakers/roster-for-event (:id event)))
        catalog-speaker (-> (public-catalog/session-by-id event (:id submission))
                            :speakers first)
        speakers-json (->> (get (exports/speakers-json-data event) "speakers")
                           (filter #(= (str person-id) (get % "id")))
                           first)
        sessions-json (first (get (exports/sessions-json-data event) "sessions"))
        handler (server/create-app)
        api-speaker (get (response-json
                           handler
                           (str "/api/v1/events/identity-projection/speakers/"
                                person-id))
                         "speaker")
        api-session-speaker (-> (response-json
                                  handler
                                  "/api/v1/events/identity-projection/sessions")
                                (get "sessions") first (get "speakers") first)]
    (testing "the submission remains the immutable statement captured at submit time"
      (is (= {:name "Priya Snapshot"
              :email "priya-identity@example.com"
              :title "Snapshot title"
              :org "Snapshot Org"
              :bio "Snapshot bio"
              :headshot-url "https://example.com/snapshot.jpg"
              :linkedin-url "https://linkedin.com/in/snapshot"
              :sessionize-url "https://sessionize.com/priya-snapshot/"
              :role "Primary speaker"
              :position 0}
             (select-keys snapshot-speaker
                          [:name :email :title :org :bio :headshot-url
                           :linkedin-url :sessionize-url :role :position]))))

    (testing "live organizer and HTML projections already follow canonical identity"
      (is (= {:name "Priya Current"
              :tagline "Current tagline"
              :organization "Current Org"
              :bio "Current bio"
              :headshot-url "https://example.com/current.jpg"
              :linkedin-url "https://linkedin.com/in/current"
              :website-url "https://current.example.com"}
             (select-keys roster-speaker
                          [:name :tagline :organization :bio :headshot-url
                           :linkedin-url :website-url])))
      (is (= {:name "Priya Current"
              :tagline "Current tagline"
              :company "Current Org"
              :bio "Current bio"
              :headshot "https://example.com/current.jpg"}
             (select-keys catalog-speaker
                          [:name :tagline :company :bio :headshot]))))

    (testing "speakers.json and its per-speaker endpoint share the live profile"
      (doseq [row [speakers-json api-speaker]]
        (is (= "Priya Current" (get row "name")))
        (is (= "Current tagline" (get row "tagline")))
        (is (= "Current Org" (get row "org")))
        (is (= "Current bio" (get row "bio")))
        (is (= "https://example.com/current.jpg" (get row "headshot")))
        (is (= #{{"label" "LinkedIn" "url" "https://linkedin.com/in/current"}
                 {"label" "Website" "url" "https://current.example.com"}
                 {"label" "Sessionize" "url" "https://sessionize.com/priya-snapshot/"}}
               (set (get row "links"))))))

    (testing "session exports use current identity without rewriting session facts"
      (is (= ["Priya Current"] (get sessions-json "speakers")))
      (is (= {"name" "Priya Current"
              "org" "Current Org"
              "title" "Current tagline"}
             (select-keys api-session-speaker ["name" "org" "title"]))))

    (testing "clearing maintained identity never resurrects stale snapshot text"
      (portal/update-profile! person-id {:org "" :bio ""}
                              "priya-identity@example.com")
      (let [fresh-snapshot (first (:speakers
                                    (store/submission-by-id (:id submission))))
            fresh-public (->> (get (exports/speakers-json-data event) "speakers")
                              (filter #(= (str person-id) (get % "id")))
                              first)]
        (is (= "Snapshot Org" (:org fresh-snapshot)))
        (is (= "Snapshot bio" (:bio fresh-snapshot)))
        (is (= "" (get fresh-public "org")))
        (is (= "" (get fresh-public "bio")))))))
