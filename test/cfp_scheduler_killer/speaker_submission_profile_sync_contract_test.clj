(ns cfp-scheduler-killer.speaker-submission-profile-sync-contract-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.portal :as portal]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.speakers :as speakers]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock]))

(use-fixtures :each with-temp-store (fn [f] (reset! auth/tokens {}) (f)))

(defn- login-cookie [handler email]
  (let [token (auth/issue-token! email)
        response (handler (mock/request :get (str "/auth/" token)))]
    (first (str/split (first (get-in response [:headers "Set-Cookie"])) #";"))))

(defn- as [cookie request]
  (mock/header request "cookie" cookie))

(deftest existing-person-submission-synchronizes-one-authoritative-profile-test
  (let [event (events/create-event!
                {:name "Profile Authority Summit"
                 :slug "profile-authority"
                 :tz "America/New_York"
                 :presenter-visibility-mode "visible"}
                "organizer@example.com")
        committee-id (:id (first (events/committees-for-event (:id event))))
        _ (committees/add-member! committee-id
                                  {:name "Organizer"
                                   :email "organizer@example.com"
                                   :role "chair"}
                                  "organizer@example.com")
        existing (speakers/add!
                   (:id event)
                   {:name "Priya Existing"
                    :email "priya-existing@example.com"
                    :status "Invited"
                    :actor "organizer@example.com"})
        person-id (:person-id existing)
        stored-org "Stored Organization"
        stored-website "https://priya.example.com"
        _ (portal/update-profile!
            person-id
            {:org stored-org :website-url stored-website}
            "priya-existing@example.com")
        fields (:fields (events/form-for-event (:id event)))
        submitted-headshot "https://images.example.test/priya-submitted.png"
        submitted-linkedin "https://linkedin.example.test/priya-submitted"
        params {:answer-talk-title "Profiles have one authority"
                :answer-abstract "Submission identity reaches the maintained profile."
                :answer-session-format "Experience Report"
                :answer-track "Developer Practices"
                :answer-org-size ">10,000"
                :answer-industry "Technology"
                :answer-ai-transformation-history "2025."
                :answer-measurable-outcomes "No silent profile loss."
                :speaker-name "Priya Existing"
                :speaker-email "priya-existing@example.com"
                :speaker-title "Submitted VP Engineering"
                :speaker-org ""
                :speaker-bio "Submitted biography."
                :speaker-headshot-url submitted-headshot
                :speaker-linkedin submitted-linkedin}
        submission (submissions/create-submission!
                     event
                     (submissions/parse-answers fields params)
                     (submissions/parse-speaker params)
                     "form"
                     "priya-existing@example.com")
        profile (:profile (store/person-by-id person-id))
        profile-fact (last (filter #(= "person.profile-updated" (:type %))
                                   (store/read-events)))
        handler (server/create-app)
        speaker-cookie (login-cookie handler "priya-existing@example.com")
        organizer-cookie (login-cookie handler "organizer@example.com")]
    (testing "supplied CFP identity populates the existing person's profile"
      (is (= {:tagline "Submitted VP Engineering"
              :org stored-org
              :bio "Submitted biography."
              :headshot-url submitted-headshot
              :linkedin-url submitted-linkedin
              :website-url stored-website}
             (select-keys profile
                          [:tagline :org :bio :headshot-url
                           :linkedin-url :website-url])))
      (is (= person-id (get-in profile-fact [:payload :person-id])))
      (is (= (:id event) (:event-id profile-fact)))
      (is (= #{"tagline" "bio" "headshot-url" "linkedin-url"}
             (set (get-in profile-fact [:payload :changed])))))

    (testing "blank submission fields cannot erase maintained values"
      (is (nil? (get-in submission [:speakers 0 :org])))
      (is (= stored-org (:org profile)))
      (is (= stored-website (:website-url profile))))

    (testing "the portal form and rendered media read the authoritative profile"
      (let [body (:body (handler (as speaker-cookie (mock/request :get "/portal"))))]
        (is (re-find
              #"name=\"tagline\"[^>]*value=\"Submitted VP Engineering\""
              body))
        (is (str/includes? body "Submitted biography."))
        (is (str/includes? body submitted-linkedin))
        (is (str/includes? body (str "src=\"" submitted-headshot "\"")))
        (is (str/includes? body "alt=\"Priya Existing headshot\""))))

    (testing "the organizer reads the same profile and valid headshot image"
      (let [detail-body (:body
                          (handler
                            (as organizer-cookie
                                (mock/request
                                  :get
                                  (str "/events/profile-authority/speakers/" person-id)))))
            roster-body (:body
                          (handler
                            (as organizer-cookie
                                (mock/request :get "/events/profile-authority/speakers"))))]
        (is (str/includes? detail-body "Submitted VP Engineering"))
        (is (str/includes? detail-body "Submitted biography."))
        (is (str/includes? roster-body (str "src=\"" submitted-headshot "\"")))
        (is (str/includes? roster-body "alt=\"Priya Existing headshot\""))))

    (testing "saving one loaded field never clears values the form omitted"
      (is (= 303
             (:status
               (handler
                 (as speaker-cookie
                     (mock/request :post "/api/profile"
                                   {"tagline" "Edited after submission"}))))))
      (store/load!)
      (let [reloaded (:profile (store/person-by-id person-id))]
        (is (= "Edited after submission" (:tagline reloaded)))
        (is (= stored-org (:org reloaded)))
        (is (= "Submitted biography." (:bio reloaded)))
        (is (= submitted-headshot (:headshot-url reloaded)))
        (is (= submitted-linkedin (:linkedin-url reloaded)))
        (is (= stored-website (:website-url reloaded)))))))
