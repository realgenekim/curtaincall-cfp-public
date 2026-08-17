(ns cfp-scheduler-killer.public-cfp-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.handlers.public-cfp :as public-cfp]
   [cfp-scheduler-killer.live-validation :as live-validation]
   [cfp-scheduler-killer.mail :as mail]
   [cfp-scheduler-killer.people :as people]
   [cfp-scheduler-killer.portal :as portal]
   [cfp-scheduler-killer.public-catalog :as public-catalog]
   [cfp-scheduler-killer.review-plan :as review-plan]
   [cfp-scheduler-killer.sessionize-import :as sessionize-import]
   [cfp-scheduler-killer.sse :as sse]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.test-helpers :as test-helpers]
   [cfp-scheduler-killer.views.public-cfp :as public-cfp-view]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [datastar-live.core :as live]
   [hiccup2.core :as h]))

(def conditional-fields
  [{:id :session-format
    :type :select
    :label "Session format"
    :options ["Talk" "Panel"]}
   {:id :panel-context
    :type :textarea
    :label "Panel context"
    :required true
    :show-when {:field-id "session-format" :equals "Panel"}}])

(deftest conditional-questions-join-the-live-form-without-a-reload
  (let [event {:id "event-conditional" :slug "conditional-cfp"}
        refreshes (atom [])
        request {:path-params {:slug (:slug event)}
                 :session {:viewer-id "viewer-1"}
                 :params {:answer-session-format "Panel"}}]
    (reset! public-cfp/cfp-drafts {})
    (reset! public-cfp/cfp-validation-errors {})
    (with-redefs [events/event-by-slug (constantly event)
                  events/form-for-event (constantly {:fields conditional-fields})
                  live/refresh! (fn [_view scope]
                                  (swap! refreshes conj scope)
                                  1)]
      (is (= 204 (:status (public-cfp/handle-cfp-draft request))))
      (public-cfp/handle-cfp-draft
        (assoc request :params {:answer-session-format "Panel"
                                :answer-panel-context "Still typing"})))
    (is (= [["viewer-1" "conditional-cfp"]
            ["viewer-1" "conditional-cfp"]]
           @refreshes))
    (let [html (str (h/html
                      (public-cfp-view/cfp-session-fields
                        conditional-fields
                        {:answer-session-format "Panel"}
                        {} {} {:answered 1 :total 2} true)))]
      (is (str/includes? html "name=\"answer-panel-context\""))
      (is (str/includes? html "data-star-show")))))

(deftest multiline-answers-advertise-the-configured-limit
  (doseq [field-type ["textarea" :markdown]]
    (let [field {:id :abstract :type field-type :label "Abstract"
                 :max-length 120}
          html (str (h/html
                      (public-cfp-view/cfp-session-fields
                        [field] {} {} {} {:answered 0 :total 1} false)))]
      (testing (str (name field-type) " matches the submit-time limit")
        (is (re-find #"<textarea(?=[^>]*name=\"answer-abstract\")(?=[^>]*maxlength=\"120\")"
                     html))))))

(deftest answer-controls-advertise-the-implicit-safety-limit
  (doseq [[field-type tag cap] [["text" "input" 300]
                                ["url" "input" 500]
                                ["email" "input" 255]
                                ["textarea" "textarea" 4000]
                                ["markdown" "textarea" 20000]]]
    (let [field {:id :details :type field-type :label "Details"}
          html (str (h/html
                      (public-cfp-view/cfp-session-fields
                        [field] {} {} {} {:answered 0 :total 1} false)))
          control-pattern
          (re-pattern (str "<" tag
                           "(?=[^>]*name=\"answer-details\")"
                           "(?=[^>]*maxlength=\"" cap "\")"))]
      (testing (str field-type " matches its submit-time safety cap")
        (is (re-find control-pattern html))))))

(deftest live-speaker-validation-matches-submit-rules
  (let [notes (live-validation/cfp-live-notes
                conditional-fields
                {:speaker-email "not-an-email"
                 :speaker-title (apply str (repeat 181 "x"))
                 :speaker-2-email "also-not-an-email"
                 :speaker-2-bio (apply str (repeat 2001 "y"))
                 :speaker-2-headshot-url "not-a-link"
                 :speaker-2-linkedin "also-not-a-link"})]
    (testing "email mistakes are visible while they are still easy to fix"
      (is (= :warn (get-in notes [:speaker-email :level])))
      (is (= :warn (get-in notes [:speaker-2-email :level]))))
    (testing "primary and additional speaker limits use the submit-time caps"
      (is (str/includes? (get-in notes [:speaker-title :text]) "181 characters"))
      (is (str/includes? (get-in notes [:speaker-2-bio :text]) "2001 characters")))
    (testing "additional-speaker links receive the same early warning"
      (is (= :warn (get-in notes [:speaker-2-headshot-url :level])))
      (is (= :warn (get-in notes [:speaker-2-linkedin :level]))))
    (testing "Curtain Call image paths stay valid in the live lane"
      (let [accepted (live-validation/cfp-live-notes
                       conditional-fields
                       {:speaker-headshot-url "/images/primary.png"
                        :speaker-2-headshot-url "/images/additional.png"})]
        (is (nil? (:speaker-headshot-url accepted)))
        (is (nil? (:speaker-2-headshot-url accepted)))))
    (testing "a repeated normalized email is caught before submit"
      (let [duplicate (live-validation/cfp-live-notes
                        conditional-fields
                        {:speaker-email "SPEAKER@example.com"
                         :speaker-2-email "speaker@example.com"})]
        (is (= :warn (get-in duplicate [:speaker-2-email :level])))
        (is (= "Each speaker must use a different email address."
               (get-in duplicate [:speaker-2-email :text])))))))

(deftest custom-email-questions-receive-live-submit-parity
  (let [fields [{:id :contact-email :type "email" :label "Contact email"}]
        invalid (live-validation/cfp-live-notes
                  fields {:answer-contact-email "not-an-email"})
        corrected (live-validation/cfp-live-notes
                    fields {:answer-contact-email "person@example.com"})]
    (testing "a malformed custom email is called out before submit"
      (is (= :warn (get-in invalid [:answer-contact-email :level])))
      (is (str/includes? (or (get-in invalid [:answer-contact-email :text]) "")
                         "complete email address")))
    (testing "correcting the same field clears the live warning"
      (is (nil? (:answer-contact-email corrected))))))

(deftest live-url-guidance-matches-the-submit-protocols
  (let [fields [{:id :reference-url :type "url" :label "Reference URL"}]
        invalid (live-validation/cfp-live-notes
                  fields {:answer-reference-url "not-a-link"
                          :speaker-linkedin "also-not-a-link"})
        portal-invalid (live-validation/portal-live-notes
                         "profile" {:website-url "still-not-a-link"} nil)
        valid-http (live-validation/cfp-live-notes
                     fields {:answer-reference-url "http://example.com"
                             :speaker-linkedin "http://example.com"})]
    (testing "warnings name both protocols accepted by submit validation"
      (doseq [text [(get-in invalid [:answer-reference-url :text])
                    (get-in invalid [:speaker-linkedin :text])
                    (get-in portal-invalid [:website-url :text])]]
        (is (str/includes? (or text "") "http:// or https://"))))
    (testing "plain HTTP links remain accepted in the live lane"
      (is (nil? (:answer-reference-url valid-http)))
      (is (nil? (:speaker-linkedin valid-http))))))

(deftest sessionize-profile-live-and-submit-validation-agree
  (let [base {:speaker-name "Speaker One"
              :speaker-email "speaker@example.com"
              :speaker-title "Engineering Leader"
              :speaker-org "Example Corp"
              :speaker-bio "Speaker One leads engineering."}
        errors-for (fn [profile]
                     (submissions/validation-errors
                       [] {}
                       (submissions/parse-speaker
                         (assoc base :speaker-sessionize-url profile))))
        invalid "https://example.com/not-sessionize"]
    (testing "the submit rule rejects the same malformed profile the live lane warns about"
      (is (= :warn
             (get-in (live-validation/cfp-live-notes
                       [] {:speaker-sessionize-url invalid})
                     [:speaker-sessionize-url :level])))
      (is (str/includes? (str (get-in (errors-for invalid)
                                      [:speaker-sessionize-url 0]))
                         "Sessionize profile")))
    (testing "both supported input shapes remain valid at submit time"
      (is (nil? (errors-for "realgenekim")))
      (is (nil? (errors-for "https://sessionize.com/realgenekim/"))))))

(deftest valid-sessionize-profile-input-is-canonicalized-for-storage
  (doseq [input ["realgenekim"
                 "http://www.sessionize.com/realgenekim"]]
    (is (= "https://sessionize.com/realgenekim/"
           (:sessionize-url
             (submissions/parse-speaker {:speaker-sessionize-url input})))
        input))
  (testing "malformed input remains available for submit validation to reject"
    (is (= "https://example.com/not-sessionize"
           (:sessionize-url
             (submissions/parse-speaker
               {:speaker-sessionize-url "https://example.com/not-sessionize"}))))))

(deftest live-import-uses-the-form-state-from-the-click
  (let [event {:id "event-import-click" :slug "import-click-cfp"}
        imported-url (atom ::not-called)
        request {:path-params {:slug (:slug event)}
                 :session {:viewer-id "viewer-import-click"}
                 :params {:speaker-sessionize-url "fresh-profile"
                          :speaker-name "Latest typed name"}}
        response
        (do
          (reset! public-cfp/cfp-drafts {})
          (with-redefs [events/event-by-slug (constantly event)
                        events/form-for-event (constantly {:fields []})
                        sessionize-import/import-profile
                        (fn [url]
                          (reset! imported-url url)
                          {:ok false :message "Profile unavailable"})
                        sse/person-connection-count (constantly 1)
                        sse/push-to-person! (fn [& _])]
            (public-cfp/handle-cfp-import-live request)))
        draft (public-cfp/cfp-draft-for request event)
        about-you (pr-str
                    (public-cfp-view/cfp-about-you event {} {} {} nil))]
    (testing "the button posts the enclosing form rather than an empty request"
      (is (str/includes? about-you "{contentType: 'form'}")))
    (testing "the handler imports and retains the values present at click time"
      (is (= 204 (:status response)))
      (is (= "fresh-profile" @imported-url))
      (is (= {:speaker-sessionize-url "fresh-profile"
              :speaker-name "Latest typed name"}
             draft)))))

(deftest failed-full-page-import-remains-in-the-viewer-draft
  (let [event {:id "event-import-failure" :slug "import-failure-cfp"}
        params {:speaker-sessionize-url "bad-profile"
                :speaker-name "Keep This Name"
                :answer-talk-title "Keep This Talk"}
        request {:path-params {:slug (:slug event)}
                 :session {:viewer-id "viewer-import-failure"}
                 :params params}
        response
        (do
          (reset! public-cfp/cfp-drafts {})
          (with-redefs [events/event-by-slug (constantly event)
                        sessionize-import/import-profile
                        (constantly {:ok false
                                     :message "Profile could not be imported"})
                        public-cfp/render-cfp
                        (fn [_req _event extra status]
                          {:status status :body (:import-message extra)})]
            (public-cfp/handle-cfp-import request)))
        draft (public-cfp/cfp-draft-for request event)]
    (testing "the failure stays a human, non-destructive round trip"
      (is (= 200 (:status response)))
      (is (str/includes? (:body response) "could not be imported")))
    (testing "a refresh can recover everything present when import failed"
      (is (= params draft)))))

(deftest full-page-import-without-a-cookie-acquires-a-recoverable-draft
  (let [event {:id "event-import-new-viewer" :slug "import-new-viewer-cfp"}
        params {:speaker-sessionize-url "bad-profile"
                :speaker-name "Keep Anonymous Typing"
                :answer-talk-title "Keep Anonymous Talk"}
        rendered-request (atom nil)
        response
        (do
          (reset! public-cfp/cfp-drafts {})
          (with-redefs [events/event-by-slug (constantly event)
                        sessionize-import/import-profile
                        (constantly {:ok false :message "Profile unavailable"})
                        public-cfp/render-cfp
                        (fn [req _event _extra status]
                          (reset! rendered-request req)
                          {:status status})]
            (public-cfp/handle-cfp-import
              {:path-params {:slug (:slug event)}
               :params params})))]
    (is (= 200 (:status response)))
    (testing "the import establishes the identity used by its draft stash"
      (is (string? (get-in @rendered-request [:session :viewer-id])))
      (is (= params (public-cfp/cfp-draft-for @rendered-request event))))))

(deftest returning-event-speaker-profile-prefill-includes-organization
  (let [event {:id "event-first-submission" :slug "first-submission-cfp"}
        person {:id "person-returning"
                :name "Returning Speaker"
                :email "returning@example.com"
                :profile {:tagline "VP Engineering"
                          :org "Example Corp"
                          :bio "A profile already maintained in Curtain Call."
                          :headshot-url "https://example.com/headshot.jpg"
                          :linkedin-url "https://example.com/returning"}}
        rendered (atom nil)
        request {:session {:viewer-id "viewer-first-submission"}
                 :headers {"host" "summit.example"}}]
    (reset! public-cfp/cfp-drafts {})
    (with-redefs [events/form-for-event (constantly {:fields []})
                  auth/current-person (constantly person)
                  portal/submission-for-event (constantly {:id "prior-submission"})
                  public-catalog/public-speakers (constantly [])
                  review-plan/presenter-visibility-policy (constantly :blind)
                  public-cfp-view/cfp-page
                  (fn [_event opts]
                    (reset! rendered opts)
                    [:div])]
      (is (= 200 (:status (public-cfp/render-cfp request event)))))
    (testing "the eligible returning speaker receives their complete profile"
      (is (= {:speaker-name "Returning Speaker"
              :speaker-email "returning@example.com"
              :speaker-title "VP Engineering"
              :speaker-org "Example Corp"
              :speaker-bio "A profile already maintained in Curtain Call."
              :speaker-headshot-url "https://example.com/headshot.jpg"
              :speaker-linkedin "https://example.com/returning"}
             (:values @rendered))))))

(deftest corrected-speaker-errors-clear-without-morphing-controls
  (let [event {:id "event-speaker-errors" :slug "speaker-errors"}
        state-key ["viewer-errors" (:id event)]
        refreshed (atom nil)
        request {:path-params {:slug (:slug event)}
                 :session {:viewer-id "viewer-errors"}
                 :params {:speaker-name "Gene Kim"
                          :speaker-email "genek@itrevolution.net"
                          :speaker-title "Author"
                          :speaker-org "IT Revolution"
                          :speaker-bio ""}}]
    (reset! public-cfp/cfp-drafts {})
    (reset! public-cfp/cfp-validation-errors
            {state-key {:speaker-email ["stale email error"]
                        :speaker-org ["stale organization error"]}})
    (with-redefs [events/event-by-slug (constantly event)
                  events/form-for-event (constantly {:fields conditional-fields})
                  live/refresh! (fn [_view scope]
                                  (reset! refreshed scope)
                                  1)]
      (is (= 204 (:status (public-cfp/handle-cfp-draft request))))
      (is (= ["viewer-errors" "speaker-errors"] @refreshed))
      (let [signals ((:signals public-cfp/public-cfp-live-view) @refreshed)
            fragment ((:render public-cfp/public-cfp-live-view) @refreshed)]
        (is (str/includes? signals "\"validationspeakeremail\":\"\""))
        (is (str/includes? signals "\"validationspeakerorg\":\"\""))
        (is (str/includes? signals "\"cfpnotespeakeremailtext\":\"\""))
        (is (str/includes? signals "A short bio is required."))
        (is (not (str/includes? (pr-str fragment) ":input")))
        (is (not (str/includes? (pr-str fragment) ":textarea")))))))

;; INTENT-TEST: CFP-003
(deftest live-view-signals-carry-guidance-and-submit-errors
  (let [event {:id "event-live-signals" :slug "live-signals"}
        scope ["viewer-live-signals" (:slug event)]
        state-key ["viewer-live-signals" (:id event)]]
    (reset! public-cfp/cfp-drafts
            {state-key {:speaker-email "ddd" :speaker-org ""}})
    (reset! public-cfp/cfp-validation-errors
            {state-key {:speaker-org ["Your organization is required."]}})
    (with-redefs [events/event-by-slug (constantly event)
                  events/form-for-event (constantly {:fields conditional-fields})]
      (let [signals ((:signals public-cfp/public-cfp-live-view) scope)]
        (is (str/includes? signals
                           "That doesn't look like a complete email address yet."))
        (is (str/includes? signals "Your organization is required."))))))

(deftest primary-speaker-fields-render-once-with-truthful-limits
  (let [html (str (h/html
                    (public-cfp-view/cfp-about-you
                      {:slug "content-summit"} {} {} {} nil)))]
    (testing "the title control is not duplicated"
      (is (= 1 (count (re-seq #"name=\"speaker-title\"" html)))))
    (testing "the browser and the server advertise the same bounded fields"
      (is (re-find #"<input(?=[^>]*name=\"speaker-title\")(?=[^>]*maxlength=\"180\")"
                   html))
      (is (re-find #"<textarea(?=[^>]*name=\"speaker-bio\")(?=[^>]*maxlength=\"2000\")"
                   html)))
    (testing "every live speaker rule has a stable, non-editable landing pad"
      (doseq [param ["speaker-name" "speaker-email" "speaker-title" "speaker-org"
                     "speaker-bio" "speaker-2-name" "speaker-2-email"
                     "speaker-2-title" "speaker-2-org" "speaker-2-bio"
                     "speaker-2-headshot-url" "speaker-2-linkedin"]]
        (is (str/includes? html (str "id=\"cfp-note-" param "\"")) param)))))

(deftest every-public-cfp-control-is-bound-to-its-posted-value
  (let [html (str (h/html
                    (public-cfp-view/cfp-about-you
                      {:slug "bound-cfp"} {} {} {} nil)))]
    (doseq [param ["speaker-name" "speaker-email" "speaker-title" "speaker-org"
                   "speaker-bio" "speaker-headshot-url" "speaker-linkedin"
                   "speaker-sessionize-url" "speaker-role" "speaker-2-name"
                   "speaker-2-email" "speaker-2-title" "speaker-2-org"
                   "speaker-2-bio" "speaker-2-headshot-url"
                   "speaker-2-linkedin" "speaker-2-role"]]
      (let [signal (str "cfp" (str/replace param #"[^A-Za-z0-9]" ""))]
        (is (re-find (re-pattern
                       (str "<(?:input|select|textarea)(?=[^>]*name=\"" param
                            "\")(?=[^>]*data-star-bind:" signal ")"))
                     html)
            (str param " must participate in Datastar form state"))))))

(deftest complete-tenant-draft-survives-a-sparse-final-submit
  (test-helpers/with-temp-store
    (fn []
      (let [event {:id "event-draft-submit" :slug "draft-submit-cfp"
                   :name "Draft Submit Summit"}
            form {:fields [{:id :talk-title :type :text :label "Talk title"
                            :required true}]}
            saved {:answer-talk-title "The proposal that must not disappear"
                   :speaker-name "Draft Speaker"
                   :speaker-email "draft-speaker@example.com"
                   :speaker-title "Engineering Leader"
                   :speaker-org "Example Corp"
                   :speaker-bio "Draft Speaker leads engineering."}
            request {:path-params {:slug (:slug event)}
                     :session {:viewer-id "viewer-draft-submit"}
                     :headers {"host" "summit.example"}
                     ;; Reproduces a Datastar/final-request payload that omitted
                     ;; controls even though the complete server draft exists.
                     :params {}}
            _ (reset! public-cfp/cfp-drafts {})
            _ (public-cfp/stash-cfp-draft! request event saved)
            response
            (with-redefs [events/event-by-slug (constantly event)
                          events/form-for-event (constantly form)
                          store/forms-for-event (constantly [form])
                          auth/mint-token!
                          (constantly "22000000-0000-0000-0000-000000000002")
                          mail/send! (fn [& _] true)]
              (public-cfp/handle-cfp-submit request))
            submission (first (submissions/for-event (:id event)))]
        (is (= 303 (:status response)))
        (is (= 1 (submissions/count-for-event (:id event))))
        (is (= "The proposal that must not disappear"
               (get-in submission [:answers :talk-title])))
        (is (= "draft-speaker@example.com"
               (get-in submission [:speakers 0 :email])))))))

(deftest optional-additional-speaker-drafts-remain-visible-after-refresh
  (doseq [[param value] [[:speaker-2-headshot-url "https://example.com/photo.jpg"]
                         [:speaker-2-linkedin "https://example.com/speaker"]]]
    (let [html (str (h/html
                      (public-cfp-view/cfp-about-you
                        {:slug "content-summit"} {param value} {} {} nil)))]
      (testing (str (name param) " keeps the recovered disclosure open")
        (is (re-find #"<details(?=[^>]*additional-speaker)(?=[^>]*open)" html))
        (is (str/includes? html value))))))

(deftest successful-submit-retains-only-the-primary-speaker-draft
  (let [event {:id "event-next-talk" :slug "next-talk-cfp" :name "Next Talk Summit"}
        submission {:id "70000000-0000-0000-0000-000000000007"
                    :event-id (:id event)
                    :speakers [{:name "Primary Speaker" :email "primary@example.com"
                                :person-id "person-primary"}
                               {:name "Prior Co-speaker" :email "prior-co@example.com"
                                :person-id "person-co"}]}
        request {:path-params {:slug (:slug event)}
                 :session {:viewer-id "viewer-next-talk"}
                 :headers {"host" "summit.example"}
                 :params {:answer-talk-title "First talk"
                          :speaker-name "Primary Speaker"
                          :speaker-email "primary@example.com"
                          :speaker-title "Engineering Leader"
                          :speaker-org "Example Corp"
                          :speaker-bio "A reusable primary profile"
                          :speaker-2-name "Prior Co-speaker"
                          :speaker-2-email "prior-co@example.com"
                          :speaker-2-role "Co-speaker"
                          :speaker-2-bio "Only relevant to the first talk"}}
        response
        (do
          (reset! public-cfp/cfp-drafts {})
          (public-cfp/stash-cfp-draft! request event (:params request))
          (with-redefs [events/event-by-slug (constantly event)
                        events/form-for-event (constantly {:fields []})
                        submissions/accepting? (constantly true)
                        submissions/parse-answers (fn [& _] {})
                        submissions/parse-speakers (fn [& _] (:speakers submission))
                        submissions/validation-errors-for-speakers (fn [& _] nil)
                        submissions/create-submission! (fn [& _] submission)
                        people/by-id (fn [person-id] {:id person-id})
                        auth/mint-token! (fn [& _] "80000000-0000-0000-0000-000000000008")
                        mail/send! (fn [& _] true)]
            (public-cfp/handle-cfp-submit request)))
        draft (public-cfp/cfp-draft-for request event)]
    (is (= 303 (:status response)))
    (testing "the next talk starts with only the submitter's reusable identity"
      (is (= {:speaker-name "Primary Speaker"
              :speaker-email "primary@example.com"
              :speaker-title "Engineering Leader"
              :speaker-org "Example Corp"
              :speaker-bio "A reusable primary profile"}
             draft)))))

(deftest successful-submit-without-a-cookie-retains-the-next-talk-profile
  (let [event {:id "event-submit-new-viewer" :slug "submit-new-viewer-cfp"
               :name "New Viewer Summit"}
        submission {:id "90000000-0000-0000-0000-000000000009"
                    :event-id (:id event)
                    :speakers [{:name "New Viewer"
                                :email "new-viewer@example.com"
                                :person-id "person-new-viewer"}]}
        request {:path-params {:slug (:slug event)}
                 :headers {"host" "summit.example"}
                 :params {:answer-talk-title "First talk"
                          :speaker-name "New Viewer"
                          :speaker-email "new-viewer@example.com"
                          :speaker-bio "Reusable profile"}}
        response
        (do
          (reset! public-cfp/cfp-drafts {})
          (with-redefs [events/event-by-slug (constantly event)
                        events/form-for-event (constantly {:fields []})
                        submissions/accepting? (constantly true)
                        submissions/parse-answers (fn [& _] {})
                        submissions/parse-speakers (fn [& _] (:speakers submission))
                        submissions/validation-errors-for-speakers (fn [& _] nil)
                        submissions/create-submission! (fn [& _] submission)
                        people/by-id (constantly {:id "person-new-viewer"})
                        auth/mint-token! (constantly "a0000000-0000-0000-0000-00000000000a")
                        mail/send! (fn [& _] true)]
            (public-cfp/handle-cfp-submit request)))
        viewer-id (get-in response [:session :viewer-id])
        draft (public-cfp/cfp-draft-for
                (assoc request :session (:session response)) event)]
    (is (= 303 (:status response)))
    (testing "the redirect establishes the same viewer used for retained profile data"
      (is (string? viewer-id))
      (is (= {:speaker-name "New Viewer"
              :speaker-email "new-viewer@example.com"
              :speaker-bio "Reusable profile"}
             draft)))))

(deftest committed-submission-survives-invite-queue-outage
  (let [event {:id "event-outbox" :slug "outbox-cfp" :name "Outbox Summit"}
        submission {:id "10000000-0000-0000-0000-000000000001"
                    :event-id (:id event)
                    :answers {:talk-title "Durable submission"}
                    :speakers [{:name "Speaker One"
                                :email "speaker@example.com"
                                :person-id "person-1"}]}
        request {:path-params {:slug (:slug event)}
                 :session {:viewer-id "viewer-outbox"}
                 :headers {"host" "summit.example"}
                 :params {:speaker-name "Speaker One"
                          :speaker-email "speaker@example.com"}}
        response
        (with-redefs [events/event-by-slug (constantly event)
                      events/form-for-event (constantly {:fields []})
                      submissions/accepting? (constantly true)
                      submissions/parse-answers (fn [& _] {})
                      submissions/parse-speakers (fn [& _] (:speakers submission))
                      submissions/validation-errors-for-speakers (fn [& _] nil)
                      submissions/create-submission! (fn [& _] submission)
                      people/by-id (constantly {:id "person-1"})
                      auth/mint-token! (constantly "20000000-0000-0000-0000-000000000002")
                      mail/send! (fn [& _]
                                   (throw (ex-info "outbox unavailable"
                                                   {:type :outbox-unavailable})))]
          (public-cfp/handle-cfp-submit request))]
    (testing "the durable write still redirects to its confirmation"
      (is (= 303 (:status response)))
      (is (str/includes? (get-in response [:headers "Location"])
                         (str "/cfp/outbox-cfp/submitted/" (:id submission)))))
    (testing "the redirect carries honest invite state and the usable portal token"
      (is (str/includes? (get-in response [:headers "Location"])
                         "portal-token=20000000-0000-0000-0000-000000000002"))
      (is (str/includes? (get-in response [:headers "Location"])
                         "invite=unavailable")))
    (testing "the confirmation never claims the failed email was queued"
      (let [confirmation
            (with-redefs [events/event-by-slug (constantly event)
                          submissions/by-id (constantly submission)]
              (public-cfp/handle-cfp-submitted
                {:path-params {:slug (:slug event)
                               :submission-id (:id submission)}
                 :params {:portal-token "20000000-0000-0000-0000-000000000002"
                          :invite "unavailable"}
                 :headers {"host" "summit.example"}}))
            html (:body confirmation)]
        (is (= 200 (:status confirmation)))
        (is (str/includes? html "confirmation email could not be queued"))
        (is (str/includes? html "/auth/20000000-0000-0000-0000-000000000002"))
        (is (not (str/includes? html "confirmation email is queued")))))))

(deftest committed-submission-survives-portal-token-outage
  (let [event {:id "event-token-outage" :slug "token-outage-cfp"
               :name "Token Outage Summit"}
        submission {:id "21000000-0000-0000-0000-000000000002"
                    :event-id (:id event)
                    :speakers [{:name "Speaker One"
                                :email "speaker@example.com"
                                :person-id "person-1"}]}
        mail-called? (atom false)
        response
        (with-redefs [events/event-by-slug (constantly event)
                      events/form-for-event (constantly {:fields []})
                      submissions/accepting? (constantly true)
                      submissions/parse-answers (fn [& _] {})
                      submissions/parse-speakers (fn [& _] (:speakers submission))
                      submissions/validation-errors-for-speakers (fn [& _] nil)
                      submissions/create-submission! (fn [& _] submission)
                      people/by-id (constantly {:id "person-1"})
                      auth/mint-token! (fn [& _]
                                         (throw (ex-info "token store unavailable"
                                                         {:type :store-unavailable})))
                      mail/send! (fn [& _] (reset! mail-called? true))]
          (public-cfp/handle-cfp-submit
            {:path-params {:slug (:slug event)}
             :session {:viewer-id "viewer-token-outage"}
             :headers {"host" "summit.example"}
             :params {:speaker-name "Speaker One"
                      :speaker-email "speaker@example.com"}}))
        location (get-in response [:headers "Location"])]
    (testing "the committed talk still reaches an honest confirmation"
      (is (= 303 (:status response)))
      (is (str/includes? location (str "/submitted/" (:id submission))))
      (is (str/includes? location "invite=unavailable")))
    (testing "email is not attempted without a usable private token"
      (is (false? @mail-called?)))))

(deftest committed-submission-survives-receipt-queue-outage
  (test-helpers/with-temp-store
    (fn []
      (let [event {:id "event-receipt" :slug "receipt-cfp" :name "Receipt Summit"}
            form {:fields []}
            queued-kinds (atom [])
            request {:path-params {:slug (:slug event)}
                     :session {:viewer-id "viewer-receipt"}
                     :headers {"host" "summit.example"}
                     :params {:speaker-name "Speaker One"
                              :speaker-email "speaker@example.com"
                              :speaker-title "Engineering Leader"
                              :speaker-org "Example Corp"
                              :speaker-bio "Speaker One leads engineering."}}
            response
            (with-redefs [events/event-by-slug (constantly event)
                          events/form-for-event (constantly form)
                          store/forms-for-event (constantly [form])
                          auth/mint-token!
                          (constantly "30000000-0000-0000-0000-000000000003")
                          mail/send!
                          (fn [_message context]
                            (swap! queued-kinds conj (:kind context))
                            (when (= "submission-confirmation" (:kind context))
                              (throw (ex-info "receipt outbox unavailable"
                                              {:type :outbox-unavailable}))))]
              (public-cfp/handle-cfp-submit request))
            saved (first (submissions/for-event (:id event)))]
        (testing "the persisted talk is still acknowledged exactly once"
          (is (= 303 (:status response)))
          (is (= 1 (submissions/count-for-event (:id event))))
          (is (= (:id saved)
                 (second (re-find #"/submitted/([^?]+)"
                                  (get-in response [:headers "Location"]))))))
        (testing "the portal handoff continues after the failed receipt"
          (is (= ["submission-confirmation" "portal-invite"] @queued-kinds))
          (is (str/includes? (get-in response [:headers "Location"])
                             "portal-token=30000000-0000-0000-0000-000000000003"))
          (is (not (str/includes? (get-in response [:headers "Location"])
                                  "invite=unavailable"))))))))

(deftest every-submitted-speaker-receives-private-portal-access
  (let [event {:id "event-multi-invite" :slug "multi-invite-cfp"
               :name "Multi-speaker Summit"}
        submission {:id "40000000-0000-0000-0000-000000000004"
                    :event-id (:id event)
                    :answers {:talk-title "A shared session"}
                    :speakers [{:name "Primary Speaker"
                                :email "primary@example.com"
                                :person-id "person-primary"}
                               {:name "Co-speaker"
                                :email "co@example.com"
                                :person-id "person-co"}]}
        minted (atom [])
        queued (atom [])
        tokens {"primary@example.com" "50000000-0000-0000-0000-000000000005"
                "co@example.com" "60000000-0000-0000-0000-000000000006"}
        response
        (with-redefs [events/event-by-slug (constantly event)
                      events/form-for-event (constantly {:fields []})
                      submissions/accepting? (constantly true)
                      submissions/parse-answers (fn [& _] {})
                      submissions/parse-speakers (fn [& _] (:speakers submission))
                      submissions/validation-errors-for-speakers (fn [& _] nil)
                      submissions/create-submission! (fn [& _] submission)
                      people/by-id (fn [person-id] {:id person-id})
                      auth/mint-token!
                      (fn [email person]
                        (swap! minted conj [email (:id person)])
                        (get tokens email))
                      mail/send!
                      (fn [message context]
                        (swap! queued conj [(:to message) (:person-id context)]))]
          (public-cfp/handle-cfp-submit
            {:path-params {:slug (:slug event)}
             :session {:viewer-id "viewer-multi-invite"}
             :headers {"host" "summit.example"}
             :params {:speaker-name "Primary Speaker"
                      :speaker-email "primary@example.com"}}))
        location (get-in response [:headers "Location"])]
    (testing "every submitted identity receives its own private handoff"
      (is (= 303 (:status response)))
      (is (= [["primary@example.com" "person-primary"]
              ["co@example.com" "person-co"]]
             @minted))
      (is (= [["primary@example.com" "person-primary"]
              ["co@example.com" "person-co"]]
             @queued)))
    (testing "the browser receives only the submitter's private token"
      (is (str/includes? location (get tokens "primary@example.com")))
      (is (not (str/includes? location (get tokens "co@example.com")))))))

(deftest per-person-cap-counts-co-speaker-appearances
  (let [event {:id "event-person-cap"
               :settings {:submissions-per-person-cap 2}}
        existing [{:speakers [{:email "first@example.com"}
                              {:email "person@example.com"}]}
                  {:speakers [{:email "PERSON@example.com"}]}]]
    (with-redefs [store/submissions-for-event (constantly existing)]
      (testing "every appearance counts toward the advertised per-person limit"
        (is (= 2 (submissions/submission-count-for-email
                   (:id event) "person@example.com")))
        (is (true? (submissions/cap-reached? event "person@example.com")))))
    (testing "a capped co-speaker cannot be added to another talk"
      (let [error
            (with-redefs [store/forms-for-event (constantly [{:fields []}])
                          submissions/cap-reached?
                          (fn [_event email] (= "person@example.com" email))
                          people/find-or-new
                          (fn [& _]
                            (throw (ex-info "speaker persistence was reached"
                                            {:type :unexpected-persistence})))]
              (try
                (submissions/create-submission!
                  event {} [{:email "primary@example.com"}
                            {:email "person@example.com"}])
                nil
                (catch clojure.lang.ExceptionInfo e e)))]
        (is (= :cap-reached (:type (ex-data error))))
        (is (= "person@example.com" (:email (ex-data error))))))))

(deftest unique-speaker-count-includes-co-speakers
  (let [submissions [{:speakers [{:email "primary-a@example.com"}
                                 {:email "co-speaker@example.com"}]}
                     {:speakers [{:email "primary-b@example.com"}
                                 {:email "co-speaker@example.com"}]}]]
    (with-redefs [store/submissions-for-event (constantly submissions)]
      (testing "each person is counted once regardless of speaker position"
        (is (= 3 (submissions/unique-speaker-count "event-speaker-count")))))))

(deftest submission-projection-preserves-editorial-content-status
  (let [projected (submissions/row->submission
                    {:id "submission-approved-content"
                     :event-id "event-content-status"
                     :content-status "Approved"})]
    (is (= "Approved" (:content-status projected)))
    (is (= "Approved" (submissions/content-status projected)))))

(deftest capped-co-speaker-refusal-identifies-the-capped-person
  (let [event {:id "event-co-cap" :slug "co-cap-cfp" :name "Cap Summit"}
        response
        (with-redefs [events/event-by-slug (constantly event)
                      events/form-for-event (constantly {:fields []})
                      submissions/accepting? (constantly true)
                      submissions/parse-answers (fn [& _] {})
                      submissions/parse-speakers
                      (fn [& _] [{:email "primary@example.com"}
                                 {:email "capped-co@example.com"}])
                      submissions/validation-errors-for-speakers (fn [& _] nil)
                      submissions/create-submission!
                      (fn [& _]
                        (throw (ex-info "submission cap reached"
                                        {:type :cap-reached
                                         :cap 2
                                         :email "capped-co@example.com"})))
                      public-cfp/render-cfp
                      (fn [_req _event extra status]
                        {:status status :body (:message extra)})]
          (public-cfp/handle-cfp-submit
            {:path-params {:slug (:slug event)}
             :session {:viewer-id "viewer-co-cap"}
             :params {:speaker-email "primary@example.com"}}))]
    (testing "the 422 names the submitted speaker whose cap blocked the talk"
      (is (= 422 (:status response)))
      (is (str/includes? (:body response) "capped-co@example.com"))
      (is (str/includes? (:body response) "limit of 2")))
    (testing "the refusal is explicit that the attempted talk was not saved"
      (is (str/includes? (:body response) "Nothing was recorded"))
      (is (not (str/includes? (:body response) "You've reached"))))))
