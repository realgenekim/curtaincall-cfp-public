(ns cfp-scheduler-killer.sessionize-import-test
  "Parser tests run against a SAVED FIXTURE (test/fixtures/sessionize-tessak22.html,
   captured 2026-08-08 from https://sessionize.com/tessak22/). Nothing here
   touches the network: a test suite that depends on someone else's uptime is a
   test suite that fails for reasons that are not about your code."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cfp-scheduler-killer.sessionize-import :as si]))

(def fixture-path "test/fixtures/sessionize-tessak22.html")

(defn- fixture [] (slurp (io/file fixture-path)))

;; --- URL validation ---------------------------------------------------------

(deftest normalize-profile-url-test
  (testing "a profile URL is canonicalised with a trailing slash"
    (is (= "https://sessionize.com/tessak22/"
           (si/normalize-profile-url "https://sessionize.com/tessak22/")))
    (is (= "https://sessionize.com/tessak22/"
           (si/normalize-profile-url "https://sessionize.com/tessak22"))
        "a missing trailing slash is added, not rejected"))

  (testing "http, www and stray whitespace are all tolerated"
    (is (= "https://sessionize.com/tessak22/"
           (si/normalize-profile-url "  http://www.sessionize.com/tessak22  "))))

  (testing "a bare handle works too — speakers know their username, not the
            URL shape (Gene, 2026-08-09). Expansion is SSRF-safe because a
            handle can only ever become a PATH on sessionize.com."
    (is (= "https://sessionize.com/realgenekim/"
           (si/normalize-profile-url "realgenekim")))
    (is (= "https://sessionize.com/realgenekim/"
           (si/normalize-profile-url "  realgenekim  "))
        "whitespace trimmed on handles as well"))

  (testing "anything that is not a sessionize profile is refused"
    ;; This list is the SSRF allowlist, not a niceness check: /api/cfp/:slug/
    ;; import-sessionize is an UNAUTHENTICATED POST that makes the server fetch
    ;; a URL a stranger supplied. The only reason that is safe is that nothing
    ;; but a sessionize.com profile path survives this function — and the
    ;; canonical https://sessionize.com/<handle>/ it returns is what actually
    ;; gets fetched, so even the http:// spelling cannot redirect the request
    ;; anywhere else.
    (doseq [bad ["https://sessionize.com/"
                 "https://example.com/tessak22/"
                 "https://sessionize.com.evil.com/x/"
                 "https://sessionize.com/a/b/"
                 "http://169.254.169.254/latest/meta-data/"
                 "http://localhost:20500/events"
                 "file:///etc/passwd"
                 "https://user@sessionize.com/tessak22/"
                 "not a url"
                 ""
                 nil]]
      (is (nil? (si/normalize-profile-url bad))
          (str "should refuse " (pr-str bad))))))

;; --- Parsing ----------------------------------------------------------------

(deftest parse-profile-test
  (let [p (si/parse-profile (fixture))]
    (testing "the fields the speaker would otherwise retype are all extracted"
      (is (= "Tessa Kriesel" (:name p)))
      (is (= "DevRel Leader | Head of Platform DevRel at Snapchat" (:tagline p)))
      (is (= "Austin, Texas, United States" (:location p))))

    (testing "the headshot is a usable absolute URL"
      (is (str/starts-with? (:headshot-url p) "https://cdn.sessionize.com/image/"))
      (is (str/ends-with? (:headshot-url p) ".jpg")))

    (testing "the bio is the full text, not a truncated meta description"
      (is (str/starts-with? (:bio p) "Tessa Kriesel is an experienced developer"))
      (is (> (count (:bio p)) 500) "the meta description is ~160 chars; the real bio is much longer")
      (is (not (str/includes? (:bio p) "..."))
          "we take the real bio, never the ellipsised summary"))

    (testing "the bio keeps its paragraph break instead of running together"
      (is (str/includes? (:bio p) "\n"))
      (is (str/includes? (:bio p) "Tessa takes pride in engaging with her community")))

    (testing "social links are picked out by host, not by position"
      (is (= "https://www.linkedin.com/in/tessak22/" (:linkedin-url p)))
      (is (= "https://twitter.com/tessak22" (:twitter-url p)))
      (is (= "https://tessakriesel.com" (:website-url p))
          "website = the first link that is not a social profile"))

    (testing "the raw link list is deduped — the page repeats it per breakpoint"
      (is (= 4 (count (:links p))))
      (is (= (count (:links p)) (count (distinct (map :url (:links p))))))
      (is (= #{"@tessak22" "LinkedIn" "Blog" "Company"}
             (set (map :label (:links p))))))))

(deftest parse-profile-rejects-non-profiles-test
  (testing "HTML with no speaker name yields nil, so callers can say so plainly"
    (is (nil? (si/parse-profile "<html><body><h1>Some other page</h1></body></html>")))
    (is (nil? (si/parse-profile "")))
    (is (nil? (si/parse-profile nil))))

  (testing "a page with only og:title still yields a name — graceful degradation"
    (let [p (si/parse-profile
             "<html><head><meta property=\"og:title\" content=\"Ann Perry&#39;s Speaker Profile @ Sessionize\" /></head><body></body></html>")]
      (is (= "Ann Perry" (:name p)))
      (is (nil? (:tagline p)))))

  (testing "an unrelated Open Graph title is not mistaken for a speaker"
    (is (nil? (si/parse-profile
                "<html><head><meta property=\"og:title\" content=\"Sessionize — Page not found\" /></head></html>")))))

(deftest parse-profile-classifies-social-and-website-links-test
  (let [p (si/parse-profile
           "<html><body>
              <h1 class='c-s-speaker-info__name'>Ada</h1>
              <ul class='c-s-links'>
                <li><a class='c-s-links__link' href='https://x.com/ada'>X</a></li>
                <li><a class='c-s-links__link' href='https://linkedin.com/in/ada'>LinkedIn</a></li>
                <li><a class='c-s-links__link' href='https://sessionize.com/ada'>Sessionize</a></li>
                <li><a class='c-s-links__link' href='https://ada.example/first'>Site</a></li>
                <li><a class='c-s-links__link' href='https://ada.example/second'>Other</a></li>
              </ul>
            </body></html>")]
    (is (= "https://x.com/ada" (:twitter-url p))
        "X is treated as the Twitter profile")
    (is (= "https://linkedin.com/in/ada" (:linkedin-url p)))
    (is (= "https://ada.example/first" (:website-url p))
        "social and Sessionize links are excluded; the first other link wins")))

(deftest import-profile-validation-test
  (testing "a bad URL fails fast with a human message and never hits the network"
    (let [r (si/import-profile "https://example.com/nope")]
      (is (false? (:ok r)))
      (is (= :bad-url (:error r)))
      (is (str/includes? (:message r) "sessionize.com")))))
