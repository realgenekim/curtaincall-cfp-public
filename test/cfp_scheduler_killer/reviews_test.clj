(ns cfp-scheduler-killer.reviews-test
  "Ratings, comments, status, and the board's arithmetic.

   The fold tests matter most: UPSERT-in-the-projection / append-in-the-log is
   the one rule that, if it broke, would silently lose opinions."
  (:require
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.review-work :as review-work]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.seed :as seed]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as sub]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [cfp-scheduler-killer.views.review :as review-view]
   [cfp-scheduler-killer.views.submission-row :as submission-row]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [hiccup2.core :as h])
  (:import
   (java.time LocalDateTime)))

(use-fixtures :each with-temp-store)

;; --- Fixtures ---------------------------------------------------------------

(defn- make-event! []
  (events/create-event!
    {:name "Review Test Summit" :slug "review-test" :tz "America/New_York"
     :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
     :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
    "kaocha"))

(defn- add-reviewer! [event name* email role]
  (let [cid (:id (first (events/committees-for-event (:id event))))]
    (committees/add-member! cid {:name name* :email email :role role} "kaocha")))

(defn- submit! [event title & [overrides]]
  (let [ff (:fields (events/form-for-event (:id event)))
        params (merge {:answer-talk-title title
                       :answer-abstract "Abstract."
                       :answer-session-format "Experience Report"
                       :answer-org-size ">10,000"
                       :answer-industry "Insurance"
                       :answer-ai-transformation-history "2023."
                       :answer-measurable-outcomes "Numbers."
                       :answer-notes-to-committee "A private note for the PC."
                       :speaker-name "Ann Speaker"
                       :speaker-email (str (random-uuid) "@example.com")
                       :speaker-title "VP"
                       :speaker-org "BigCo"
                       :speaker-bio "Bio."}
                      overrides)]
    (sub/create-submission! event (sub/parse-answers ff params) (sub/parse-speaker params)
                            "form" "kaocha")))

;; --- Pure arithmetic --------------------------------------------------------

(deftest stars-test
  (testing "1.0–5.0 in halves, and nothing else"
    (is (= [1.0 1.5 2.0 2.5 3.0 3.5 4.0 4.5 5.0] reviews/star-steps))
    (doseq [ok reviews/star-steps] (is (reviews/valid-stars? ok)))
    (doseq [bad [0.0 0.5 5.5 6.0 4.3 -1 nil "4"]]
      (is (not (reviews/valid-stars? bad)) (str "should reject " (pr-str bad)))))

  (testing "parsing a form value"
    (is (= 4.5 (reviews/parse-stars "4.5")))
    (is (= 3.0 (reviews/parse-stars "3")))
    (is (nil? (reviews/parse-stars "4.3")))
    (is (nil? (reviews/parse-stars "")))
    (is (nil? (reviews/parse-stars "; DROP TABLE")))))

(deftest mean-and-stddev-test
  (testing "mean of nothing is nil, NOT zero — 'nobody said' isn't 'they said 0'"
    (is (nil? (reviews/mean [])))
    (is (nil? (reviews/mean nil))))

  (testing "mean"
    (is (= 3.0 (reviews/mean [2 4])))
    (is (= 4.0 (reviews/mean [4])))
    (is (== 3.5 (reviews/mean [2.5 3.5 4.5]))))

  (testing "population stddev — one opinion has no spread"
    (is (nil? (reviews/stddev [])))
    (is (nil? (reviews/stddev [4])))
    (is (= 1.0 (reviews/stddev [2 4])))
    (is (= 0.0 (reviews/stddev [3 3 3]))))

  (testing "SPLIT fires on a spread of 2.0 stars or more"
    ;; Calibrated to SPREAD, not σ: with three reviewers a genuine two-star
    ;; disagreement only reaches σ≈0.82, so a σ threshold never fired on the
    ;; rows the flag exists to catch.
    (is (not (reviews/split? [4 4.5])) "half a star apart is agreement")
    (is (not (reviews/split? [3 4.5])) "1.5 apart is a difference of opinion")
    (is (reviews/split? [2 4]) "someone said 2 and someone said 4")
    (is (reviews/split? [2 3 4]) "the three-reviewer case that used to be missed")
    (is (reviews/split? [1.0 1.5 3.5]))
    (is (reviews/split? [5.0 2.5]))
    (is (nil? (reviews/split? [4])) "one rating can't be split")
    (is (= 2.0 (reviews/spread [2 3 4])))
    (is (nil? (reviews/spread [4])))))

;; --- The fold: UPSERT in the projection, append in the log ------------------

(deftest rating-upsert-test
  (let [event (make-event!)
        gene (add-reviewer! event "Gene Kim" "gene@example.com" "chair")
        s (submit! event "A talk")]

    (reviews/set-rating! (:id s) (:person-id gene) 2.0 "gene@example.com")
    (reviews/set-rating! (:id s) (:person-id gene) 4.5 "gene@example.com")

    (testing "the projection keeps ONE rating per person × submission — the latest"
      (let [rs (store/ratings-for-submission (:id s))]
        (is (= 1 (count rs)))
        (is (= 4.5 (:stars (first rs))))))

    (testing "but the LOG keeps both — changing your mind is the interesting part"
      (let [rating-events (filter #(= "rating.set" (:type %)) (store/read-events))]
        (is (= 2 (count rating-events)))
        (is (= [2.0 4.5] (mapv #(get-in % [:payload :stars]) rating-events)))
        (is (= [nil 2.0] (mapv #(get-in % [:payload :previous-stars]) rating-events))
            "each event records what it replaced")))

    (testing "retrying the current opinion is an idempotent no-op"
      (let [before (store/rating-by (:id s) (:person-id gene))
            event-count (count (store/read-events))
            returned (reviews/set-rating! (:id s) (:person-id gene) 4.5
                                          "gene@example.com")]
        (is (= before returned))
        (is (= event-count (count (store/read-events))))
        (is (= (:at before)
               (:at (store/rating-by (:id s) (:person-id gene)))))))

    (testing "and a reload reproduces the upsert exactly"
      (store/load!)
      (is (= 1 (count (store/ratings-for-submission (:id s)))))
      (is (= 4.5 (:stars (first (store/ratings-for-submission (:id s)))))))

    (testing "a second reviewer adds a rating, never replaces one"
      (let [ann (add-reviewer! event "Ann Perry" "ann@example.com" "member")]
        (reviews/set-rating! (:id s) (:person-id ann) 3.0 "ann@example.com")
        (is (= 2 (count (store/ratings-for-submission (:id s)))))
        (is (= #{3.0 4.5} (set (map :stars (store/ratings-for-submission (:id s))))))))

    (testing "an illegal star value is refused outright"
      (is (thrown? clojure.lang.ExceptionInfo
                   (reviews/set-rating! (:id s) (:person-id gene) 4.3 "gene@example.com"))))))

(deftest comments-accumulate-test
  (let [event (make-event!)
        gene (add-reviewer! event "Gene Kim" "gene@example.com" "chair")
        s (submit! event "A talk")]
    (reviews/add-comment! (:id s) (:person-id gene) "First thought." "gene@example.com")
    (reviews/add-comment! (:id s) (:person-id gene) "Second thought." "gene@example.com")

    (testing "comments accumulate — a second comment never replaces the first"
      (let [cs (store/comments-for-submission (:id s))]
        (is (= 2 (count cs)))
        (is (= ["First thought." "Second thought."] (mapv :body cs)))))

    (testing "a whitespace-only comment is REFUSED, not silently dropped"
      (let [thrown (try (reviews/add-comment! (:id s) (:person-id gene) "   " "gene@example.com")
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :empty-comment (:type (ex-data thrown))))
        (is (str/includes? (ex-message thrown) "Say something"))
        (is (= 2 (count (store/comments-for-submission (:id s))))
            "and nothing was appended")))))

(deftest status-and-priority-test
  (let [event (make-event!)
        s (submit! event "A talk")]
    (testing "a new submission is Pending"
      (is (= "Pending" (:status s))))

    (testing "status moves through the event's own vocabulary"
      (reviews/set-status! (:id s) "Accept Queue" "gene@example.com")
      (is (= "Accept Queue" (:status (store/submission-by-id (:id s)))))
      (let [ev (last (filter #(= "submission.status-changed" (:type %)) (store/read-events)))]
        (is (= "Pending" (get-in ev [:payload :from])))
        (is (= "Accept Queue" (get-in ev [:payload :to])))))

    (testing "a status that isn't on this event is refused"
      (let [thrown (try (reviews/set-status! (:id s) "Rejected Forever" "x") nil
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :invalid-status (:type (ex-data thrown))))
        (is (= "Accept Queue" (:status (store/submission-by-id (:id s))))
            "and it changed nothing")))

    (testing "priority toggles both ways"
      (is (false? (:priority (store/submission-by-id (:id s)))))
      (reviews/toggle-priority! (:id s) "gene@example.com")
      (is (true? (:priority (store/submission-by-id (:id s)))))
      (reviews/toggle-priority! (:id s) "gene@example.com")
      (is (false? (:priority (store/submission-by-id (:id s))))))

    (testing "all of it survives a reload"
      (reviews/set-status! (:id s) "Accepted" "gene@example.com")
      (reviews/toggle-priority! (:id s) "gene@example.com")
      (store/load!)
      (is (= "Accepted" (:status (store/submission-by-id (:id s)))))
      (is (true? (:priority (store/submission-by-id (:id s))))))))

;; --- Coverage ---------------------------------------------------------------

(deftest coverage-test
  (let [event (make-event!)
        gene (add-reviewer! event "Gene Kim" "gene@example.com" "chair")
        ann (add-reviewer! event "Ann Perry" "ann@example.com" "member")
        a (submit! event "Covered talk")
        b (submit! event "Half-covered talk")
        _c (submit! event "Untouched talk")]
    (reviews/set-rating! (:id a) (:person-id gene) 4.0 "g")
    (reviews/set-rating! (:id a) (:person-id ann) 4.0 "a")
    (reviews/set-rating! (:id b) (:person-id gene) 3.0 "g")

    (testing "coverage counts submissions at or above the target"
      (let [cov (reviews/coverage (:id event) 2)]
        (is (= 3 (:total cov)))
        (is (= 1 (:covered cov)))
        (is (< 33.0 (:pct cov) 33.4))))

    (testing "the denominator is EVERY submission — coverage can't rise by narrowing"
      (is (= 3 (:total (reviews/coverage (:id event) 5)))))

    (testing "a target of 1 counts anything with an opinion"
      (is (= 2 (:covered (reviews/coverage (:id event) 1)))))))

;; --- The two work queues ----------------------------------------------------

(deftest sort-presets-test
  (let [event (make-event!)
        gene (add-reviewer! event "Gene Kim" "gene@example.com" "chair")
        ann (add-reviewer! event "Ann Perry" "ann@example.com" "member")
        low (submit! event "Well rated, fully covered")
        mid (submit! event "Highly rated but only one review")
        zero (submit! event "Nobody has looked")]
    (reviews/set-rating! (:id low) (:person-id gene) 4.0 "g")
    (reviews/set-rating! (:id low) (:person-id ann) 4.0 "a")
    (reviews/set-rating! (:id mid) (:person-id gene) 5.0 "g")

    (let [rows (reviews/enriched-for-event (:id event))]
      (testing "'needs reviews' puts the neglected ones first — the coverage worklist"
        (let [order (mapv #(get-in % [:answers :talk-title])
                          (reviews/sort-board rows "needs-reviews" 2))]
          (is (= "Nobody has looked" (first order)))
          (is (= "Well rated, fully covered" (last order)))))

      (testing "'ready to decide' ranks by mean stars, with review count visible rather than weighted in"
        (let [order (mapv #(get-in % [:answers :talk-title])
                          (reviews/sort-board rows "ready-to-decide" 2))]
          (is (= ["Highly rated but only one review"
                  "Well rated, fully covered"
                  "Nobody has looked"]
                 order)
              "the coverage queue fixes sample size; the decision queue stays explainable")))

      (testing "an unknown preset falls back to the coverage worklist"
        (is (= (reviews/sort-board rows "needs-reviews" 2)
               (reviews/sort-board rows "nonsense" 2)))))))

(deftest search-and-filter-test
  (let [event (make-event!)
        _a (submit! event "Underwriting with LLMs" {:speaker-org "Meridian Mutual"
                                                    :speaker-name "Priya Raghavan"})
        b (submit! event "Forecasting 4,000 stores" {:speaker-org "Crestline Retail"
                                                     :speaker-name "Dana Whitfield"})
        rows (reviews/enriched-for-event (:id event))]

    (testing "search covers title, speaker name and org"
      (is (= 1 (count (reviews/filter-board rows {:q "underwriting"}))))
      (is (= 1 (count (reviews/filter-board rows {:q "crestline"}))))
      (is (= 1 (count (reviews/filter-board rows {:q "priya"}))))
      (is (= 2 (count (reviews/filter-board rows {:q ""}))))
      (is (= 0 (count (reviews/filter-board rows {:q "zzz"})))))

    (testing "search is case-insensitive"
      (is (= 1 (count (reviews/filter-board rows {:q "MERIDIAN"})))))

    (testing "status filtering, and the counts behind the chips"
      (reviews/set-status! (:id b) "Accepted" "g")
      (let [rows (reviews/enriched-for-event (:id event))]
        (is (= {"Pending" 1 "Accepted" 1} (reviews/status-counts rows)))
        (is (= 1 (count (reviews/filter-board rows {:status "Accepted"}))))
        (is (= "Forecasting 4,000 stores"
               (get-in (first (reviews/filter-board rows {:status "Accepted"}))
                       [:answers :talk-title])))))))

(deftest submission-number-is-stable-across-board-lenses-test
  (let [event (make-event!)
        _ (submit! event "First")
        _ (submit! event "Second")
        _ (submit! event "Third")
        rows (reviews/enriched-for-event (:id event))
        number-by-title (fn [xs]
                          (into {} (map (juxt #(get-in % [:answers :talk-title])
                                              :submission-number) xs)))]
    (is (= {"First" 1 "Second" 2 "Third" 3} (number-by-title rows)))
    (is (= [1 2 3]
           (mapv :submission-number (reviews/sort-board rows "submission" 2))))
    (is (= [3 2 1]
           (mapv :submission-number (reviews/sort-board rows "submission-desc" 2))))
    (is (= (number-by-title rows)
           (number-by-title (reviews/sort-board rows "speaker-last" 2))))
    (is (= 3 (:submission-number
               (first (reviews/filter-board rows {:q "Third"})))))))

;; --- Enrichment + the by-reviewer pivot -------------------------------------

(deftest enrich-test
  (let [event (make-event!)
        gene (add-reviewer! event "Gene Kim" "gene@example.com" "chair")
        alex (add-reviewer! event "Alex B-F" "alex@example.com" "member")
        s (submit! event "A talk")]
    (reviews/set-rating! (:id s) (:person-id gene) 5.0 "g")
    (reviews/set-rating! (:id s) (:person-id alex) 2.5 "a")
    (reviews/add-comment! (:id s) (:person-id gene) "Want it." "g")

    (let [row (reviews/enrich (store/submission-by-id (:id s)))]
      (testing "the row carries the stats the board renders"
        (is (= 2 (:n row)))
        (is (= 3.75 (:mean row)))
        (is (true? (:split? row))))
      (testing "and reviewer NAMES, not ids — the board names everyone"
        (is (= #{"Gene Kim" "Alex B-F"} (set (map :person-name (:ratings row)))))
        (is (= "Gene Kim" (:person-name (first (:comments row)))))))))

(deftest mean-star-results-sort-bidirectionally-test
  (let [event (make-event!)
        reviewer (add-reviewer! event "Gene Kim" "gene@example.com" "chair")
        lower (submit! event "Taming 40-Minute CI")
        higher (submit! event "Your AI Pair Programmer")]
    (reviews/set-rating! (:id lower) (:person-id reviewer) 3.0 "gene@example.com")
    (reviews/set-rating! (:id higher) (:person-id reviewer) 5.0 "gene@example.com")
    (let [rows (reviews/enriched-for-event (:id event))
          titles (fn [sorted]
                   (mapv #(get-in % [:answers :talk-title]) sorted))
          descending (reviews/sort-board rows "avg-desc" 1)]
      (testing "mean-star results order both submissions correctly in both directions"
        (is (= ["Taming 40-Minute CI" "Your AI Pair Programmer"]
               (titles (reviews/sort-board rows "avg" 1))))
        (is (= ["Your AI Pair Programmer" "Taming 40-Minute CI"]
               (titles descending)))))))

(deftest active-recusal-removes-and-unrecusal-restores-board-rating-evidence-test
  (let [event (make-event!)
        gene (add-reviewer! event "Gene Kim" "gene@example.com" "chair")
        conflict (add-reviewer! event "Rae Conflict" "rae@example.com" "member")
        submission (submit! event "Recusal-safe results")]
    (reviews/set-rating! (:id submission) (:person-id gene) 5.0 "gene@example.com")
    (reviews/set-rating! (:id submission) (:person-id conflict) 1.0 "rae@example.com")
    (testing "both active reviewers initially contribute"
      (let [stats (reviews/submission-stats (:id submission))]
        (is (= 2 (:n stats)))
        (is (= 3.0 (:mean stats)))
        (is (:split? stats))))

    (review-work/recuse! (:id submission) (:person-id conflict)
                         "Conflict of interest" "rae@example.com")
    (testing "active recusal removes the rating from every board score projection"
      (let [stats (reviews/submission-stats (:id submission))]
        (is (= 1 (:n stats)))
        (is (= 5.0 (:mean stats)))
        (is (false? (:split? stats)))
        (is (= [(:person-id gene)] (mapv :person-id (:ratings stats))))))

    (review-work/unrecuse! (:id submission) (:person-id conflict) "rae@example.com")
    (testing "unrecusal restores the preserved rating evidence"
      (let [stats (reviews/submission-stats (:id submission))]
        (is (= 2 (:n stats)))
        (is (= 3.0 (:mean stats)))
        (is (:split? stats))))))

(deftest active-recusal-removes-and-unrecusal-restores-board-comments-test
  (let [event (make-event!)
        gene (add-reviewer! event "Gene Kim" "gene@example.com" "chair")
        conflict (add-reviewer! event "Rae Conflict" "rae@example.com" "member")
        submission (submit! event "Recusal-safe comments")]
    (reviews/add-comment! (:id submission) (:person-id gene)
                          "Useful active context." "gene@example.com")
    (reviews/add-comment! (:id submission) (:person-id conflict)
                          "Conflicted context." "rae@example.com")
    (testing "both active reviewers' comments initially appear"
      (is (= #{"Useful active context." "Conflicted context."}
             (set (map :body (:comments (reviews/submission-stats (:id submission))))))))

    (review-work/recuse! (:id submission) (:person-id conflict)
                         "Conflict of interest" "rae@example.com")
    (testing "active recusal removes the conflicted comment from board evidence"
      (let [row (reviews/enrich (store/submission-by-id (:id submission)))
            html (str (submission-row/board-row event row nil true))]
        (is (= ["Useful active context."] (mapv :body (:comments row))))
        (is (str/includes? html "Useful active context."))
        (is (not (str/includes? html "Conflicted context.")))))

    (review-work/unrecuse! (:id submission) (:person-id conflict) "rae@example.com")
    (testing "unrecusal restores the preserved comment to the board"
      (let [row (reviews/enrich (store/submission-by-id (:id submission)))
            html (str (submission-row/board-row event row nil true))]
        (is (= 2 (count (:comments row))))
        (is (str/includes? html "Conflicted context."))))))

(deftest active-recusal-refuses-new-rating-and-comment-evidence-test
  (let [event (make-event!)
        reviewer (add-reviewer! event "Rae Conflict" "rae@example.com" "member")
        submission (submit! event "Recusal write boundary")]
    (review-work/recuse! (:id submission) (:person-id reviewer)
                         "Conflict of interest" "rae@example.com")
    (let [rating-rejection (try
                             (reviews/set-rating! (:id submission) (:person-id reviewer)
                                                  4.0 "rae@example.com")
                             nil
                             (catch clojure.lang.ExceptionInfo ex (ex-data ex)))
          comment-rejection (try
                              (reviews/add-comment! (:id submission) (:person-id reviewer)
                                                    "Hidden evidence" "rae@example.com")
                              nil
                              (catch clojure.lang.ExceptionInfo ex (ex-data ex)))]
      (testing "core mutations enforce recusal even without the HTTP handler"
        (is (= :reviewer-recused (:type rating-rejection)))
        (is (= :reviewer-recused (:type comment-rejection)))
        (is (nil? (store/rating-by (:id submission) (:person-id reviewer))))
        (is (empty? (store/comments-for-submission (:id submission))))))

    (review-work/unrecuse! (:id submission) (:person-id reviewer) "rae@example.com")
    (testing "restoring the review reopens both evidence paths"
      (is (= 4.0 (:stars (reviews/set-rating! (:id submission) (:person-id reviewer)
                                              4.0 "rae@example.com"))))
      (is (= "Visible evidence"
             (:body (reviews/add-comment! (:id submission) (:person-id reviewer)
                                          "Visible evidence" "rae@example.com")))))))

(deftest cross-event-person-cannot-add-review-evidence-test
  (let [event (make-event!)
        submission (submit! event "Event-scoped review evidence")
        other-event (events/create-event!
                      {:name "Other Review Summit" :slug "other-review" :tz "UTC"
                       :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
                       :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
                      "kaocha")
        outsider (add-reviewer! other-event "Other Reviewer" "other@example.com" "member")
        event-count (count (store/read-events))
        rating-rejection (try
                           (reviews/set-rating! (:id submission) (:person-id outsider)
                                                4.0 "other@example.com")
                           nil
                           (catch clojure.lang.ExceptionInfo e (ex-data e)))
        comment-rejection (try
                            (reviews/add-comment! (:id submission) (:person-id outsider)
                                                  "Cross-event comment" "other@example.com")
                            nil
                            (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (testing "both core evidence mutations enforce the submission event roster"
      (is (= :not-on-review-committee (:type rating-rejection)))
      (is (= :not-on-review-committee (:type comment-rejection))))
    (testing "refusal changes neither evidence projections nor the event log"
      (is (zero? (:n (reviews/submission-stats (:id submission)))))
      (is (empty? (:comments (reviews/submission-stats (:id submission)))))
      (is (= event-count (count (store/read-events)))))))

(deftest cross-event-person-cannot-create-review-mention-test
  (let [event (make-event!)
        recipient (add-reviewer! event "Event Reviewer" "event@example.com" "member")
        submission (submit! event "Event-scoped review mention")
        other-event (events/create-event!
                      {:name "Other Mention Summit" :slug "other-mention" :tz "UTC"
                       :cfp-opens-at (LocalDateTime/of 2020 1 1 0 0)
                       :cfp-closes-at (LocalDateTime/of 2099 1 1 0 0)}
                      "kaocha")
        outsider (add-reviewer! other-event "Other Reviewer" "other@example.com" "member")
        rejection (try
                    (reviews/mention! (:id submission) (:person-id outsider)
                                      (:person-id recipient) "Please review" "other@example.com")
                    nil
                    (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (testing "the mention sender must belong to the submission event committee"
      (is (= :not-on-review-committee (:type rejection))))
    (testing "refusal creates no in-app nudge"
      (is (empty? (reviews/mentions-shelf (:id event) (:person-id recipient)))))))

(deftest recused-reviewer-cannot-create-review-mention-test
  (let [event (make-event!)
        sender (add-reviewer! event "Recused Reviewer" "recused@example.com" "member")
        recipient (add-reviewer! event "Event Reviewer" "event@example.com" "member")
        submission (submit! event "Recusal-safe review mention")]
    (review-work/recuse! (:id submission) (:person-id sender)
                         "Conflict of interest" "recused@example.com")
    (let [rejection (try
                      (reviews/mention! (:id submission) (:person-id sender)
                                        (:person-id recipient) "Please review"
                                        "recused@example.com")
                      nil
                      (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (testing "a reviewer cannot participate by mention while recused"
        (is (= :reviewer-recused (:type rejection))))
      (testing "refusal creates no in-app nudge"
        (is (empty? (reviews/mentions-shelf (:id event) (:person-id recipient))))))))

(deftest recused-reviewer-cannot-receive-review-mention-test
  (let [event (make-event!)
        sender (add-reviewer! event "Active Reviewer" "active@example.com" "member")
        recipient (add-reviewer! event "Recused Recipient" "recused@example.com" "member")
        submission (submit! event "Recipient-safe review mention")]
    (review-work/recuse! (:id submission) (:person-id recipient)
                         "Conflict of interest" "recused@example.com")
    (let [row (reviews/enrich (store/submission-by-id (:id submission)))
          detail-html (str (review-view/submission-detail-page
                             event row {:person (store/person-by-id (:person-id sender))
                                        :coverage-target 2
                                        :chair? false}))
          rejection (try
                      (reviews/mention! (:id submission) (:person-id sender)
                                        (:person-id recipient) "Please review"
                                        "active@example.com")
                      nil
                      (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (testing "the mention picker excludes colleagues recused from this submission"
        (is (not (str/includes? detail-html "Recused Recipient"))))
      (testing "the core mutation refuses a bypassed recused recipient"
        (is (= :mention-recipient-recused (:type rejection))))
      (testing "refusal creates no in-app nudge"
        (is (empty? (reviews/mentions-shelf (:id event) (:person-id recipient))))))))

(deftest active-recusal-hides-and-unrecusal-restores-existing-mention-test
  (let [event (make-event!)
        sender (add-reviewer! event "Active Reviewer" "active@example.com" "member")
        recipient (add-reviewer! event "Mentioned Reviewer" "mentioned@example.com" "member")
        submission (submit! event "Mention shelf recusal")
        mention (reviews/mention! (:id submission) (:person-id sender)
                                  (:person-id recipient) "Please review"
                                  "active@example.com")]
    (testing "the active recipient initially sees the invitation"
      (is (= [(:id mention)]
             (mapv :id (reviews/mentions-shelf (:id event) (:person-id recipient))))))

    (review-work/recuse! (:id submission) (:person-id recipient)
                         "Conflict of interest" "mentioned@example.com")
    (testing "active recusal hides the conflicted invitation from the shelf and view"
      (let [shelf (reviews/mentions-shelf (:id event) (:person-id recipient))]
        (is (empty? shelf))
        (is (nil? (review-view/mentions-shelf event shelf)))))

    (review-work/unrecuse! (:id submission) (:person-id recipient)
                           "mentioned@example.com")
    (testing "unrecusal restores the preserved invitation"
      (is (= [(:id mention)]
             (mapv :id (reviews/mentions-shelf (:id event) (:person-id recipient))))))))

(deftest sender-recusal-hides-and-unrecusal-restores-existing-mention-test
  (let [event (make-event!)
        sender (add-reviewer! event "Mentioning Reviewer" "sender@example.com" "member")
        recipient (add-reviewer! event "Active Recipient" "recipient@example.com" "member")
        submission (submit! event "Mention sender recusal")
        mention (reviews/mention! (:id submission) (:person-id sender)
                                  (:person-id recipient) "Please review"
                                  "sender@example.com")]
    (testing "the active sender's invitation initially appears"
      (is (= [(:id mention)]
             (mapv :id (reviews/mentions-shelf (:id event) (:person-id recipient))))))

    (review-work/recuse! (:id submission) (:person-id sender)
                         "Conflict discovered" "sender@example.com")
    (testing "sender recusal hides the conflicted invitation from the recipient"
      (let [shelf (reviews/mentions-shelf (:id event) (:person-id recipient))]
        (is (empty? shelf))
        (is (nil? (review-view/mentions-shelf event shelf)))))

    (review-work/unrecuse! (:id submission) (:person-id sender)
                           "sender@example.com")
    (testing "unrecusal restores the preserved invitation"
      (is (= [(:id mention)]
             (mapv :id (reviews/mentions-shelf (:id event) (:person-id recipient))))))))

(deftest active-recusal-removes-and-unrecusal-restores-reviewer-calibration-test
  (let [event (make-event!)
        gene (add-reviewer! event "Gene Kim" "gene@example.com" "chair")
        ann (add-reviewer! event "Ann Perry" "ann@example.com" "member")
        active (submit! event "Active review")
        conflict (submit! event "Conflicted review")]
    (reviews/set-rating! (:id active) (:person-id gene) 5.0 "gene@example.com")
    (reviews/set-rating! (:id conflict) (:person-id gene) 1.0 "gene@example.com")
    (reviews/set-rating! (:id active) (:person-id ann) 3.0 "ann@example.com")
    (reviews/set-rating! (:id conflict) (:person-id ann) 3.0 "ann@example.com")
    (review-work/recuse! (:id conflict) (:person-id gene)
                         "Conflict of interest" "gene@example.com")

    (testing "calibration uses only the reviewer's active score evidence"
      (let [summary (reviews/reviewer-summary (:id event) (:person-id gene))]
        (is (= 1 (:rated-count summary)))
        (is (= ["Active review"] (mapv :title (:ratings summary))))
        (is (= 5.0 (:mean summary)))
        (is (= 4.0 (:committee-mean summary)))))

    (review-work/unrecuse! (:id conflict) (:person-id gene) "gene@example.com")
    (testing "unrecusal restores the preserved rating to calibration"
      (let [summary (reviews/reviewer-summary (:id event) (:person-id gene))]
        (is (= 2 (:rated-count summary)))
        (is (= 3.0 (:mean summary)))
        (is (= #{"Active review" "Conflicted review"}
               (set (map :title (:ratings summary)))))))))

(deftest active-recusal-removes-and-unrecusal-restores-reviewer-comments-test
  (let [event (make-event!)
        gene (add-reviewer! event "Gene Kim" "gene@example.com" "chair")
        active (submit! event "Active comment")
        conflict (submit! event "Conflicted comment")]
    (reviews/add-comment! (:id active) (:person-id gene)
                          "Useful active context." "gene@example.com")
    (reviews/add-comment! (:id conflict) (:person-id gene)
                          "Conflicted context." "gene@example.com")
    (review-work/recuse! (:id conflict) (:person-id gene)
                         "Conflict of interest" "gene@example.com")

    (testing "organizer calibration excludes comments from active recusals"
      (let [summary (reviews/reviewer-summary (:id event) (:person-id gene))]
        (is (= 1 (count (:comments summary))))
        (is (= ["Active comment"] (mapv :title (:comments summary))))))

    (review-work/unrecuse! (:id conflict) (:person-id gene) "gene@example.com")
    (testing "unrecusal restores the preserved comment context"
      (let [summary (reviews/reviewer-summary (:id event) (:person-id gene))]
        (is (= #{"Active comment" "Conflicted comment"}
               (set (map :title (:comments summary)))))))))

(deftest reviewer-summary-test
  (let [event (make-event!)
        gene (add-reviewer! event "Gene Kim" "gene@example.com" "chair")
        ann (add-reviewer! event "Ann Perry" "ann@example.com" "member")
        a (submit! event "Talk A")
        b (submit! event "Talk B")
        _c (submit! event "Talk C")]
    ;; Gene rates high, Ann rates low, on the same two talks.
    (reviews/set-rating! (:id a) (:person-id gene) 5.0 "g")
    (reviews/set-rating! (:id b) (:person-id gene) 4.0 "g")
    (reviews/set-rating! (:id a) (:person-id ann) 3.0 "a")
    (reviews/set-rating! (:id b) (:person-id ann) 2.0 "a")
    (reviews/add-comment! (:id a) (:person-id gene) "Strong." "g")

    (let [summary (reviews/reviewer-summary (:id event) (:person-id gene))]
      (testing "their ratings, with the talk they belong to"
        (is (= 2 (:rated-count summary)))
        (is (= 3 (:total-submissions summary)))
        (is (= #{"Talk A" "Talk B"} (set (map :title (:ratings summary))))))

      (testing "their mean against the committee mean ON THE SAME TALKS"
        (is (= 4.5 (:mean summary)))
        (is (= 3.5 (:committee-mean summary))
            "committee mean over the talks they rated, not the whole event"))

      (testing "their comments come along"
        (is (= 1 (count (:comments summary))))
        (is (= "Talk A" (:title (first (:comments summary))))))

      (testing "a reviewer who has done nothing gets an honest empty summary"
        (let [alex (add-reviewer! event "Alex" "alex@example.com" "member")
              empty-summary (reviews/reviewer-summary (:id event) (:person-id alex))]
          (is (zero? (:rated-count empty-summary)))
          (is (nil? (:mean empty-summary)))
          (is (empty? (:ratings empty-summary))))))))
