(ns cfp-scheduler-killer.event-surface-authorization-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.event-surface-authorization :as surface-auth]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock])
  (:import
   (java.time LocalDateTime)))

(use-fixtures :each with-temp-store)

;; Independent oracle.  This deliberately does not derive from the production
;; policy map: adding a route requires an explicit authorization decision in
;; both the server manifest and its test witness.
(def expected-policies
  {[:get "/events/new"]                                      :event-creator

   [:get "/events/:slug"]                                   :speaker
   [:get "/events/:slug/details"]                           :speaker

   [:get "/events/:slug/fragment"]                          :reviewer
   [:get "/events/:slug/committee"]                         :reviewer
   [:get "/events/:slug/submissions/:submission-id"]        :reviewer
   [:get "/events/:slug/board"]                             :reviewer
   [:get "/events/:slug/board/review-scores.csv"]           :reviewer
   [:get "/events/:slug/board/fragment"]                    :reviewer
   [:get "/events/:slug/people/:person-id"]                 :reviewer

   [:get "/events/:slug/exports/sessions.json"]             :public
   [:get "/events/:slug/exports/speakers.json"]             :public
   [:get "/events/:slug/exports/calendar.ics"]              :public
   [:get "/events/:slug/llms.txt"]                          :public

   [:get "/events/:slug/announce"]                          :organizer
   [:get "/events/:slug/speakers/new"]                      :organizer
   [:get "/events/:slug/submissions/:submission-id/manage"] :organizer
   [:get "/events/:slug/review"]                            :organizer
   [:get "/events/:slug/reviewer-progress"]                :organizer
   [:get "/events/:slug/deliverables"]                      :organizer
   [:get "/events/:slug/speakers"]                          :organizer
   [:post "/events/:slug/speakers/filter"]                  :organizer
   [:get "/events/:slug/speakers/:person-id"]               :organizer
   [:post "/events/:slug/submissions/:submission-id/speakers"] :organizer
   [:post "/events/:slug/submissions/:submission-id/speakers/:person-id/remove"] :organizer
   [:get "/events/:slug/files"]                             :organizer
   [:get "/events/:slug/files.zip"]                         :organizer
   [:get "/events/:slug/files/:file-id/download"]           :organizer
   [:post "/events/:slug/mcp"]                              :public
   [:get "/events/:slug/submissions"]                       :organizer
   [:get "/events/:slug/log/fragment"]                      :organizer
   [:get "/events/:slug/inform"]                            :organizer
   [:get "/events/:slug/form"]                              :organizer
   [:get "/events/:slug/settings"]                          :organizer
   [:get "/events/:slug/comms"]                             :organizer
   [:get "/events/:slug/replay"]                            :organizer
   [:get "/events/:slug/capture"]                           :organizer
   [:get "/events/:slug/resources"]                         :organizer
   [:get "/events/:slug/schedule"]                          :organizer
   [:get "/events/:slug/exports"]                           :organizer
   [:get "/events/:slug/exports/review-results.csv"]        :organizer
   [:get "/events/:slug/exports/review-results.json"]       :organizer
   [:get "/events/:slug/embed"]                             :organizer
   [:get "/events/:slug/log"]                               :organizer})

(defn- make-world!
  []
  (let [event (events/create-event!
                {:name "Authorization Summit" :slug "authz-summit"
                 :tz "America/New_York"
                 :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
                 :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
                "kaocha")
        committee-id (:id (first (events/committees-for-event (:id event))))
        organizer-membership (committees/add-member!
                               committee-id
                               {:name "Olivia Organizer"
                                :email "organizer@example.com"
                                :role "chair"}
                               "kaocha")
        reviewer-membership (committees/add-member!
                              committee-id
                              {:name "Riley Reviewer"
                               :email "reviewer@example.com"
                               :role "reviewer"}
                              "organizer@example.com")
        fields (:fields (events/form-for-event (:id event)))
        submission (submissions/create-submission!
                     event
                     (submissions/parse-answers
                       fields
                       {:answer-talk-title "Fail closed"
                        :answer-abstract "Authorization declarations are data."
                        :answer-session-format "Experience Report"
                        :answer-org-size ">10,000"
                        :answer-industry "Software"
                        :answer-ai-transformation-history "2026."
                        :answer-measurable-outcomes "Every route is classified."})
                     (submissions/parse-speaker
                       {:speaker-name "Sam Speaker"
                        :speaker-email "speaker@example.com"
                        :speaker-title "Engineer"
                        :speaker-org "Example"
                        :speaker-bio "Builds secure systems."})
                     "form" "kaocha")]
    {:event event
     :organizer (store/live-person-by-id (:person-id organizer-membership))
     :reviewer (store/live-person-by-id (:person-id reviewer-membership))
     :speaker (store/live-person-by-id
                (get-in submission [:speakers 0 :person-id]))}))

(defn- router-event-route-keys
  []
  (into #{}
        (for [[template methods] (server/make-routes)
              [method _] methods
              :when (surface-auth/event-workspace-route? template)]
          [method template])))

(def granted-personas
  {:public #{:anonymous :organizer :reviewer :speaker}
   :speaker #{:organizer :reviewer :speaker}
   :reviewer #{:organizer :reviewer}
   :organizer #{:organizer}
   :event-creator #{:organizer}})

(defn- policy-request
  [method template person]
  (cond-> {:request-method method
           :uri template
           :reitit.core/match {:template template}
           :path-params {:slug "authz-summit"
                         :submission-id "submission-id"
                         :person-id "person-id"
                         :file-id "file-id"}}
    person (assoc :session {:person-id (:id person)})))

(defn- session-for
  [handler email]
  (let [token (auth/issue-token! email)
        response (handler (mock/request :get (str "/auth/" token)))
        cookie (first (str/split
                        (first (get-in response [:headers "Set-Cookie"])) #";"))]
    (fn [request]
      (handler (mock/header request "cookie" cookie)))))

;; INTENT-TEST: AUTHZ-001
;; INTENT-TEST: AUTHZ-003
(deftest event-surface-policy-inventory-and-persona-matrix-test
  (let [{:keys [organizer reviewer speaker]} (make-world!)
        personas {:anonymous nil
                  :organizer organizer
                  :reviewer reviewer
                  :speaker speaker}
        guarded (surface-auth/wrap-declared-event-authorization
                  (constantly {:status 204}))]
    (testing "the live router and the independent authorization oracle are exact"
      (is (= (set (keys expected-policies)) (router-event-route-keys)))
      (is (= expected-policies surface-auth/declared-event-surface-policies)))

    (testing "every persona is evaluated against every declared surface"
      (doseq [[[method template] policy] expected-policies
              [persona person] personas]
        (let [expected (if (contains? (get granted-personas policy) persona) 204 403)
              actual (:status (guarded (policy-request method template person)))]
          (is (= expected actual)
              (str persona " " method " " template " policy=" policy)))))

    (testing "missing and unknown authorization signals deny"
      (is (= 403
             (:status
               (guarded (policy-request :get "/events/:slug/future-surface"
                                        organizer)))))
      (is (= 403
             (:status
               (guarded (assoc (policy-request :get "/events/:slug/settings" nil)
                               :session {:person-id "missing-person"})))))))

  (testing "the manifest is wired at the server boundary"
    (let [app (server/create-app)
          organizer-session (session-for app "organizer@example.com")
          speaker-session (session-for app "speaker@example.com")]
      (is (= 200 (:status (organizer-session
                            (mock/request :get "/events/authz-summit/settings")))))
      (is (= 403 (:status (speaker-session
                            (mock/request :get "/events/authz-summit/settings")))))
      (with-redefs [surface-auth/declared-event-surface-policies {}]
        (is (= 403 (:status (organizer-session
                              (mock/request :get "/events/authz-summit/settings")))))))))
