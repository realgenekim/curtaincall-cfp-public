(ns cfp-scheduler-killer.manifesto-test
  "Manifesto page rendering tests: key phrases present, no client JS."
  (:require
   [cfp-scheduler-killer.views.manifesto :as manifesto]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(defn- render []
  (manifesto/manifesto-page))

(deftest manifesto-key-phrases-present-test
  (let [html (render)]
    (testing "headline — the core thesis"
      (is (str/includes? html "built it for a table")))
    (testing "box-and-wire rant — verbatim beat"
      (is (str/includes? html "box-and-wire")))
    (testing "workflow tagline — verbatim beat"
      (is (str/includes? html "Your workflow, your way")))
    (testing "the cube section present"
      (is (str/includes? html "The cube")))
    (testing "the table section present"
      (is (str/includes? html "The table")))
    (testing "route-around quote present"
      (is (str/includes? html "route around your review tool")))
    (testing "agent-operable section present"
      (is (str/includes? html "Point your own LLM")))
    (testing "BusyConf spiritual-successor mention"
      (is (str/includes? html "BusyConf")))
    (testing "CTA link points at root"
      (is (str/includes? html "href=\"/\"")))
    (testing "blind review toggle mentioned"
      (is (str/includes? html "Blind review")))
    (testing "config-as-data claim present"
      (is (str/includes? html "configuration-as-data")))
    (testing "the review product refusal is explicit"
      (is (str/includes? html
                         "Formal review rounds. Trusted reviewers do not need Round 1 → Round 2 machinery"))
      (is (str/includes? html "One score, many reasons."))
      (is (str/includes? html "Quick Rate is the canonical review surface.")))
    (testing "the refusal records its authority"
      (is (str/includes? html "Rich Hickey famously said"))
      (is (str/includes? html "you never need more than Stars and comments")))))

(deftest manifesto-no-script-tags-test
  (let [html (render)]
    (testing "page contains no <script> tags"
      ;; The shell normally loads fomantic and datastar-kit scripts — we must
      ;; confirm none of our NEW content added inline or event-handler script.
      ;; Count <script occurrences to verify the manifesto view itself adds zero.
      ;; (The shell's CDN scripts are permitted; what we ban is any JS payload
      ;; we authored — so we assert the page is free of inline script bodies
      ;; by checking that no <script> block contains our own code.)
      ;;
      ;; Stricter assertion: the manifesto view adds NO <script> tags at all
      ;; beyond what page-shell provides. Since we don't call datastar-script,
      ;; the shell still loads jQuery/Fomantic (needed for layout) but the
      ;; manifesto content itself is pure HTML. We assert the total count of
      ;; <script tags is exactly the number page-shell adds (3: jquery, fomantic,
      ;; datastar-kit) — not more.
      ;;
      ;; Actually: simplest contract is "the page has no <script> tag containing
      ;; any of our authored inline JS". We verify by asserting the rendered HTML
      ;; contains no <script> element that encloses a function body or event
      ;; listener — i.e., no additional scripts beyond the shell's CDN ones.
      ;; The simplest defensible test: no <script> tag appears after </head>.
      (let [after-head (second (str/split html #"</head>" 2))]
        (is (not (str/includes? after-head "<script"))
            "no <script> tags in the page body — manifesto is pure static HTML")))))

(deftest manifesto-html-structure-test
  (let [html (render)]
    (testing "page has an <h1> tag"
      (is (str/includes? html "<h1")))
    (testing "page title contains product name"
      (is (str/includes? html "Curtain Call")))
    (testing "page has a link to sign in / create event"
      (is (str/includes? html "href=\"/\"")))))
