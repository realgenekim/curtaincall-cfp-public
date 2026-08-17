(ns cfp-scheduler-killer.speaker-csv-preview-details-contract-test
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.server :as server]
   [cfp-scheduler-killer.speaker-csv :as speaker-csv]
   [cfp-scheduler-killer.speakers :as speakers]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :as mock])
  (:import
   (org.jsoup Jsoup)))

(use-fixtures :each with-temp-store)

(def sample-values
  {:name "Ada Speaker"
   :email "ada@example.com"
   :status "Confirmed"
   :title "Distinguished Engineer"
   :organization "Analytical Engines"
   :bio "Builds reliable calculating systems"
   :headshot-url "https://images.example.test/ada-preview.png"
   :location "London"
   :notes "Needs captioning"})

(defn- login-cookie [handler email]
  (let [token (auth/issue-token! email)
        response (handler (mock/request :get (str "/auth/" token)))]
    (first (str/split (first (get-in response [:headers "Set-Cookie"])) #";"))))

(defn- table-from [response]
  (second (re-find #"(?s)(<table[^>]*>.*?</table>)" (:body response))))

(defn- table-text [table]
  (.text (Jsoup/parse table)))

(deftest csv-preview-shows-the-complete-accepted-column-family-test
  (let [parsed-value-keys (-> (set (vals speaker-csv/header-aliases))
                              (disj :first-name :last-name)
                              (conj :name))
        registry-keys (set (map first speaker-csv/accepted-columns))
        handler (server/create-app)
        cookie (login-cookie handler "organizer@example.com")
        as-organizer #(handler (mock/header % "cookie" cookie))
        _ (as-organizer
            (mock/request :post "/api/events/create"
                          {"name" "Preview Details Summit"
                           "slug" "preview-details"
                           "starts-on" "2026-10-14"
                           "ends-on" "2026-10-15"
                           "presenter-visibility-mode" "visible"}))
        event (events/event-by-slug "preview-details")
        headers (mapv #(nth % 2) speaker-csv/accepted-columns)
        row (mapv (fn [[key]] (get sample-values key))
                  speaker-csv/accepted-columns)
        csv-text (str (str/join "," headers) "\n"
                      (str/join "," row) "\n")
        before (count (store/read-events))
        response (as-organizer
                   (mock/request
                    :post
                    "/api/events/preview-details/speakers/import/preview"
                    {"csv-text" csv-text}))
        table (table-from response)
        text (table-text table)]
    (is (= 200 (:status response)))
    (is (string? table))
    (testing "the accepted-column registry drives the whole preview contract"
      (is (= parsed-value-keys registry-keys)
          "every value recognized by the parser belongs to the preview family")
      (doseq [[key label] speaker-csv/accepted-columns]
        (is (str/includes? text label) (str label " is named in the preview"))
        (is (str/includes? text (get sample-values key))
            (str label " value is visible before import"))))
    (testing "the preview remains read-only"
      (is (= before (count (store/read-events))))
      (is (empty? (speakers/roster-for-event (:id event)))))))

(deftest csv-preview-distinguishes-absent-columns-from-empty-values-test
  (let [handler (server/create-app)
        cookie (login-cookie handler "organizer@example.com")
        as-organizer #(handler (mock/header % "cookie" cookie))
        _ (as-organizer
            (mock/request :post "/api/events/create"
                          {"name" "Sparse Preview Summit"
                           "slug" "sparse-preview"
                           "starts-on" "2026-10-14"
                           "ends-on" "2026-10-15"
                           "presenter-visibility-mode" "visible"}))
        response (as-organizer
                   (mock/request
                    :post
                    "/api/events/sparse-preview/speakers/import/preview"
                    {"csv-text" (str "Name,Email,Notes\n"
                                     "Grace Hopper,grace@example.com,\n")}))
        table (table-from response)
        text (table-text table)]
    (is (= 200 (:status response)))
    (is (str/includes? text "Title: Not provided in CSV"))
    (is (str/includes? text "Notes: Empty"))
    (is (str/includes? text "Default — Status column not provided"))))
