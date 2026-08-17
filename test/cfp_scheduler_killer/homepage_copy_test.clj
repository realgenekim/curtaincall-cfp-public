(ns cfp-scheduler-killer.homepage-copy-test
  (:require
   [cfp-scheduler-killer.views.auth :as view-auth]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(deftest homepage-copy-is-read-and-safely-rendered-on-every-request
  (let [copy-file (.toFile (java.nio.file.Files/createTempFile
                             "curtain-call-homepage-copy-"
                             ".md"
                             (make-array java.nio.file.attribute.FileAttribute 0)))
        resource-url (.toURL (.toURI copy-file))]
    (try
      (with-redefs [io/resource (fn [_] resource-url)]
        (spit copy-file "## First version\n\n- Organizer proof")
        (let [first-page (view-auth/landing-page nil nil false nil)]
          (spit copy-file "## Second version\n\n<script>alert('nope')</script>")
          (let [second-page (view-auth/landing-page nil nil false nil)]
            (testing "a file save is visible on the next homepage render"
              (is (str/includes? first-page "First version"))
              (is (not (str/includes? first-page "Second version")))
              (is (str/includes? second-page "Second version"))
              (is (not (str/includes? second-page "First version"))))
            (testing "the shared Markdown renderer preserves structure and escapes HTML"
              (is (str/includes? first-page "<ul>"))
              (is (str/includes? second-page "&lt;script&gt;"))
              (is (not (str/includes? second-page "<script>alert")))))))
      (finally
        (.delete copy-file)))))
(deftest judge-persona-guidance-stays-compact-and-above-fold-test
  (let [demo-page (view-auth/landing-page nil nil true nil)
        ordinary-page (view-auth/landing-page nil nil false nil)]
    (testing "demo mode renders one compact utility door before the hero"
      (is (= 1 (count (re-seq #"Judge demo: choose a persona →" demo-page))))
      (is (str/includes? demo-page "class=\"landing-demo-link\" href=\"/login\""))
      (is (< (.indexOf demo-page "Judge demo: choose a persona →")
             (.indexOf demo-page "Curtain Call · calls for papers")))
      (is (not (str/includes? demo-page "demo-how-to-sign-in"))))
    (testing "ordinary mode does not advertise the judge sandbox"
      (is (not (str/includes? ordinary-page "Judge demo: choose a persona →"))))))
