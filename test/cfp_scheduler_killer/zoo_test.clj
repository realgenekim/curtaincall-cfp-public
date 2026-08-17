(ns cfp-scheduler-killer.zoo-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.handlers.zoo :as zoo-handlers]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [cfp-scheduler-killer.views.auth :as auth-view]
   [cfp-scheduler-killer.views.manifesto :as manifesto-view]
   [cfp-scheduler-killer.views.shell :as shell]
   [cfp-scheduler-killer.views.zoo :as zoo-view]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock]))

(use-fixtures :each with-temp-store)

(defn- signed-in-app []
  (let [raw (server/create-app)
        token (auth/issue-token! "zoo-router@example.com")
        login (raw (mock/request :get (str "/auth/" token)))
        cookie (first (str/split (first (get-in login [:headers "Set-Cookie"])) #";"))]
    (fn [req] (raw (mock/header req "cookie" cookie)))))

(def specimens
  (mapv (fn [[id label kind]]
          {:id id :label label
           :metadata shell/homepage-social-metadata
           :preview-image-url (str "http://localhost:20502/admin/zoo/social-sharing/cards/" kind)})
        [["main-homepage" "Main homepage" "homepage"] ["cfp" "CFP" "cfp"]
         ["speaker-brag" "Speaker brag" "speaker"]
         ["event-program" "Event/program" "event"]]))

(deftest social-sharing-zoo-renders-four-real-card-specimens-test
  (let [html (zoo-view/social-sharing-page
               {:base-url "https://curtaincallcfp.com"
                :specimens specimens})]
    (doseq [label ["Main homepage" "CFP" "Speaker brag" "Event/program"]]
      (is (str/includes? html (str ">" label "</h2>")) label))
    (doseq [id ["main-homepage" "cfp" "speaker-brag" "event-program"
                "custom-url-inspector"]]
      (is (str/includes? html (str "href=\"#" id "\"")) id)
      (is (str/includes? html (str "id=\"" id "\"")) id))
    (is (< (str/index-of html "id=\"event-program\"")
           (str/index-of html "id=\"custom-url-inspector\""))
        "the free-form inspector follows every ratified specimen")
    (testing "Slack uses a complete wide image, never the old square thumbnail"
      (is (str/includes? html "zc-sl-image"))
      (is (not (str/includes? html "zc-sl-thumb"))))
    (testing "every permanent specimen adds X from the same projection and preview"
      (is (= 4 (count (re-seq #"class=\"zoo-card-x\"" html))))
      (is (= 4 (count (re-seq #">X-style</h3>" html))))
      (is (= 4 (count (re-seq #">summary_large_image</div>" html))))
      (doseq [{:keys [preview-image-url]} specimens]
        (is (= 3 (count (re-seq (re-pattern
                                  (java.util.regex.Pattern/quote preview-image-url))
                                html)))
            preview-image-url)))))

(deftest permanent-specimens-have-self-contained-renderable-preview-images-test
  (let [event {:slug "enterprise-ai-summit-charlotte-2026"
               :name "Enterprise AI Summit — Charlotte"
               :location "Charlotte, NC"
               :cfp-intro "A gathering for people building enterprise AI."
               :settings {:hero-image-url "/images/eais-charlotte-hero.jpg"}}
        hero {:id "speaker" :slug "speaker" :name "Featured speaker"
              :sessions [{:title "A story worth sharing"}]}
        projected (zoo-handlers/zoo-specimens "http://localhost:20502" event hero)
        html (zoo-view/social-sharing-page
               {:base-url "http://localhost:20502" :specimens projected})]
    (is (= 4 (count projected)))
    (is (= 4 (count (re-seq #"class=\"zoo-card-x\"" html))))
    (doseq [{:keys [metadata preview-image-url]} projected]
      (is (str/starts-with? preview-image-url
                            "http://localhost:20502/admin/zoo/social-sharing/cards/"))
      (is (not (str/blank? preview-image-url)))
      (is (not= (:image metadata) preview-image-url))
      (is (<= 3 (count (re-seq (re-pattern
                                 (java.util.regex.Pattern/quote preview-image-url))
                               html)))))
    (doseq [kind ["homepage" "event" "speaker"]]
      (let [response (zoo-handlers/handle-zoo-preview-card
                       {:path-params {:kind kind}})]
        (is (= 200 (:status response)))
        (is (= "image/png" (get-in response [:headers "Content-Type"])))
        (is (> (alength ^bytes (:body response)) 1000))))))

(deftest real-router-serves-every-zoo-preview-card-test
  (let [app (signed-in-app)]
    (doseq [kind ["homepage" "event" "speaker"]]
      (let [response (app (mock/request
                            :get (str "/admin/zoo/social-sharing/cards/" kind)))]
        (is (= 200 (:status response)) kind)
        (is (= "image/png" (get-in response [:headers "Content-Type"])) kind)
        (is (> (alength ^bytes (:body response)) 1000) kind)))))

(deftest root-and-manifesto-share-homepage-social-contract-test
  (doseq [html [(auth-view/landing-page nil nil false nil)
                (manifesto-view/manifesto-page)]]
    (doseq [literal ["Curtain Call — calls for papers, without the paperwork"
                     "The CFP tool organizers dreamed of for fifteen years, built in a weekend on a dare. Zero to open CFP in ten minutes."
                     "https://curtaincallcfp.com/card.png"
                     "href=\"https://curtaincallcfp.com/\" rel=\"canonical\""
                     "content=\"website\" property=\"og:type\""
                     "content=\"summary_large_image\" name=\"twitter:card\""]]
      (is (str/includes? html literal) literal))))
