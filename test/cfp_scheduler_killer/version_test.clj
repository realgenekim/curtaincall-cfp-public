(ns cfp-scheduler-killer.version-test
  (:require
   [cfp-scheduler-killer.version :as version]
   [cfp-scheduler-killer.views.organizer-layout :as organizer-layout]
   [cfp-scheduler-killer.views.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(deftest build-identity-renders-on-every-page-shell-test
  (let [pages {:generic (shell/page-shell "Generic" [:p "hello"])
               :share (shell/share-page-shell "Share" [:meta {:name "test" :content "share"}] [:p "hello"])
               :organizer (organizer-layout/organizer-shell
                            "Organizer" {:active :events :person nil} [:p "hello"])}
        expected-sha (str "data-build-sha=\"" version/git-sha "\"")
        expected-time (str "data-build-time=\"" (version/build-time-str) "\"")]
    (doseq [[kind html] pages]
      (testing (name kind)
        (is (str/includes? html "class=\"build-identity\""))
        (is (str/includes? html expected-sha))
        (is (str/includes? html expected-time))
        (is (str/includes? html (str "Build <code>" version/git-sha "</code>")))
        (is (str/includes? html (str "deployed " (version/build-time-str))))))))
(deftest static-asset-version-remains-cacheable-between-renders-test
  (let [first-url (shell/versioned "/css/app.css")
        second-url (shell/versioned "/css/app.css")
        dynamic-url (shell/versioned "/api/generated-card.svg")]
    (is (= first-url second-url)
        "Static assets keep a stable browser-cache key while unchanged")
    (is (re-find #"/css/app\.css\?v=\d+--?\d+" first-url))
    (Thread/sleep 2)
    (is (not= dynamic-url (shell/versioned "/api/generated-card.svg"))
        "Dynamic generated routes retain their per-render cache buster")))
