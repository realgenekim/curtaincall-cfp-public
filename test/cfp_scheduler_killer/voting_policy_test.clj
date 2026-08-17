(ns cfp-scheduler-killer.voting-policy-test
  "BLIND REVIEW voting policy — the pure presenter-visible? truth table, plus a
   render test proving the reviewer board/detail actually blinds the speaker
   name when the setting is on and shows it when off.

   Doctrine held here: blind hides the AUTHOR from reviewers; reviewer
  identities are NEVER hidden (the open table). Both settings default OFF."
  (:require
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.views.avatar :as avatar]
   [cfp-scheduler-killer.views.organizer-layout :as organizer-layout]
   [cfp-scheduler-killer.views.review :as review]
   [cfp-scheduler-killer.views.submission-row :as submission-row]
   [cfp-scheduler-killer.voting-policy :as vp]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [hiccup2.core :as h]))

(defn- render [hiccup] (str (h/html hiccup)))

(def ^:private open-event         {:slug "e" :settings {}})
(def ^:private hide-only-event    {:slug "e" :settings {:hide-presenter-info true}})
(def ^:private reveal-event       {:slug "e" :settings {:hide-presenter-info true
                                                        :reveal-after-vote true}})
;; reveal-after-vote ON but hide OFF must still be fully visible (reveal is only
;; meaningful when hiding is on).
(def ^:private reveal-no-hide     {:slug "e" :settings {:hide-presenter-info false
                                                        :reveal-after-vote true}})

(def ^:private a-submission {:id "sub1"})
(def ^:private a-reviewer   {:id "rev1"})

;; --- The truth table --------------------------------------------------------

(deftest presenter-visible-truth-table-test
  (testing "both off -> presenter visible (today's open table)"
    (is (true? (vp/presenter-visible? open-event a-submission a-reviewer false)))
    (is (true? (vp/presenter-visible? open-event a-submission a-reviewer true))))

  (testing "hide on, reveal off -> hidden ALWAYS, regardless of rating"
    (is (false? (vp/presenter-visible? hide-only-event a-submission a-reviewer false)))
    (is (false? (vp/presenter-visible? hide-only-event a-submission a-reviewer true))))

  (testing "hide on, reveal-after-vote on -> hidden BEFORE this reviewer rated"
    (is (false? (vp/presenter-visible? reveal-event a-submission a-reviewer false))))

  (testing "hide on, reveal-after-vote on -> visible AFTER this reviewer rated"
    (is (true? (vp/presenter-visible? reveal-event a-submission a-reviewer true))))

  (testing "reveal-after-vote on but hide OFF -> visible (reveal needs hide on)"
    (is (true? (vp/presenter-visible? reveal-no-hide a-submission a-reviewer false)))
    (is (true? (vp/presenter-visible? reveal-no-hide a-submission a-reviewer true)))))

(deftest presenter-visibility-mode-test
  (is (= "visible" (vp/presenter-visibility-mode open-event)))
  (is (= "visible" (vp/presenter-visibility-mode reveal-no-hide)))
  (is (= "hidden" (vp/presenter-visibility-mode hide-only-event)))
  (is (= "reveal-after-vote" (vp/presenter-visibility-mode reveal-event))))

(deftest sidebar-always-renders-review-process-link-test
  ;; 3cdbcbf (Gene-accepted 2026-08-17): the no-policy fallback renders the
  ;; same canonical sentences as the policy-bearing form — the legacy
  ;; "Blind/Not Blind Review Process (Click to Change)" labels are retired.
  (doseq [[event mode expected] [[open-event "visible" "Open review · Presenter identity is visible to reviewers."]
                                 [hide-only-event "blind" "Blind review · Presenter identity is hidden throughout review."]
                                 [reveal-event "blind" "Blind review · Presenter identity is hidden throughout review."]]]
    (let [html (render (organizer-layout/presenter-policy-link event))]
      (is (str/includes? html expected) mode)
      (is (str/includes? html "committee#presenter-visibility") mode)
      (is (str/includes? html (str "data-policy-mode=\"" mode "\"")) mode)))
  (doseq [[event mode expected] [[open-event "visible" "Open review · Presenter identity is visible to reviewers."]
                                 [hide-only-event "hidden" "Blind review · Presenter identity is hidden throughout review."]
                                 [reveal-event "reveal-after-vote" "Blind until rated · Presenter identity appears after a reviewer submits their first rating."]]]
    (let [policy {:mode mode :version 4 :summary expected}
          html (render (organizer-layout/presenter-policy-link event policy))]
      (is (str/includes? html expected) mode)
      (is (str/includes? html "Click to change →") mode)
      (is (str/includes? html "sb-policy-summary") mode)
      (is (str/includes? html (str "data-policy-mode=\"" mode "\"")) mode)
      (is (str/includes? html "data-policy-version=\"4\"") mode))))

(deftest presenter-hidden-is-negation-test
  (is (= (not (vp/presenter-visible? reveal-event a-submission a-reviewer false))
         (vp/presenter-hidden? reveal-event a-submission a-reviewer false))))

(deftest reveal-is-per-reviewer-test
  ;; The reveal decision is driven ENTIRELY by the has-this-reviewer-rated?
  ;; argument the caller supplies per reviewer — same event, same submission,
  ;; different reviewers see different things.
  (testing "reviewer who rated sees the presenter; reviewer who hasn't does not"
    (is (true?  (vp/presenter-visible? reveal-event a-submission {:id "who-rated"} true)))
    (is (false? (vp/presenter-visible? reveal-event a-submission {:id "who-didnt"} false)))))

(deftest field-or-hidden-test
  (is (= "Ann Speaker" (vp/field-or-hidden true "Ann Speaker")))
  (is (= "Name and org withheld" vp/hidden-marker))
  (is (= "Submission #3" (vp/hidden-presenter-label {:submission-number 3})))
  (is (= vp/hidden-marker (vp/field-or-hidden false "Ann Speaker"))))

;; --- Render tests: the board row and the detail page ------------------------

(def ^:private speaker-name "Ann Speaker")
(def ^:private speaker-org  "BigCo Insurance")

(defn- enriched-row [reviewer-id rated?]
  "A minimally enriched submission row the review views can render."
  {:id "sub1"
   :submission-number 3
   :speakers [{:person-id "sp1" :name speaker-name :org speaker-org
               :title "VP of Platform" :email "ann@bigco.example"
               :bio "A long and identifying bio."
               :headshot-url "https://secret.example/speaker.jpg"}]
   :answers {:talk-title "Rewiring the Enterprise" :session-format "Experience Report"
             :org-size ">10,000" :track "Leadership"}
   :ratings (if rated? [{:person-id reviewer-id :person-name "Rev One" :stars 4.0}] [])
   :comments []
   :n (if rated? 1 0) :mean (when rated? 4.0) :split? false})

(deftest explicit-visibility-context-uses-current-reviewer-entitlement-test
  (let [reviewer {:id "rev1"}
        current-rows [(enriched-row "rev1" true)
                      (assoc (enriched-row "rev1" false) :id "sub2")]
        context (vp/visibility-context reveal-event current-rows reviewer)]
    (is (= reviewer (:reviewer-person context)))
    (is (= #{"sub1"} (:rated-submission-ids context)))
    (is (true? (vp/presenter-visible-in-context? context {:id "sub1"})))
    (is (false? (vp/presenter-visible-in-context? context {:id "sub2"})))
    (is (true? (vp/presenter-visible-in-context?
                (vp/visibility-context open-event current-rows reviewer)
                {:id "sub2"})))))

(deftest blind-board-header-describes-submissions-not-speakers-test
  (let [props {:rows [(enriched-row "rev1" false)]
               :coverage {:target 2}
               :sort-key "voted"
               :q ""
               :status ""
               :status-counts {}
               :person {:id "rev1"}
               :sort-presets []
               :total 1
               :uncommunicated 0
               :notice nil
               :track nil
               :track-counts {}}
        blind-html (render (review/board-region hide-only-event props))
        visible-html (render (review/board-region open-event props))]
    (is (str/includes? blind-html "Submission"))
    (is (str/includes? blind-html "sort=submission"))
    (is (not (str/includes? blind-html "Speaker Fname")))
    (is (str/includes? visible-html "Speaker Fname"))))

(deftest blind-board-search-and-identity-sort-are-not-oracles-test
  (let [row-a {:answers {:talk-title "Zulu talk"}
               :speakers [{:name "Aaron A" :org "Aaa" :title "VP"}]
               :n 5 :mean 5.0
               :created-at (java.time.Instant/parse "2026-01-01T00:00:00Z")}
        row-b {:answers {:talk-title "Alpha talk"}
               :speakers [{:name "Zebedee Z" :org "Zzz" :title "CTO"}]
               :n 1 :mean 1.0
               :created-at (java.time.Instant/parse "2026-01-02T00:00:00Z")}
        rows [row-b row-a]]
    (testing "blind search consults talk title, never hidden identity"
      (is (false? (reviews/matches-query? row-a "Aaron" true)))
      (is (false? (reviews/matches-query? row-a "Aaa" true)))
      (is (true? (reviews/matches-query? row-a "Zulu" true))))
    (testing "hand-typed identity sorts fall back to the neutral queue"
      (is (= (reviews/sort-board rows reviews/default-sort 2 true)
             (reviews/sort-board rows "speaker-first" 2 true)))
      (is (= (reviews/sort-board rows reviews/default-sort 2 true)
             (reviews/sort-board rows "org-desc" 2 true))))))

(deftest board-row-blinds-speaker-name-test
  (let [person {:id "rev1"}]
    (testing "settings OFF -> the speaker name renders on the reviewer board"
      (let [html (render (submission-row/board-row open-event (enriched-row "rev1" false) person))]
        (is (str/includes? html speaker-name))
        (is (str/includes? html speaker-org))
        (is (str/includes? html "https://secret.example/speaker.jpg"))
        (is (not (str/includes? html vp/hidden-marker)))))

    (testing "hide ON -> no identity or human-face source reaches the board HTML"
      (let [html (with-redefs [avatar/pool-face
                               (fn [& _] (throw (ex-info "blind avatar oracle" {})))]
                   (render (submission-row/board-row hide-only-event
                                                     (enriched-row "rev1" false) person)))]
        (is (str/includes? html "Submission #3"))
        (is (str/includes? html "blind-avatar"))
        (is (not (str/includes? html "<img")))
        (is (not (str/includes? html "https://secret.example/speaker.jpg")))
        (is (not (str/includes? html speaker-name)))
        (is (not (str/includes? html speaker-org)))))))

(deftest board-row-reveal-after-vote-test
  (let [person {:id "rev1"}]
    (testing "reveal-after-vote ON, this reviewer has NOT rated -> hidden"
      (let [html (render (submission-row/board-row reveal-event (enriched-row "rev1" false) person))]
        (is (str/includes? html "Submission #3"))
        (is (not (str/includes? html "https://secret.example/speaker.jpg")))
        (is (not (str/includes? html speaker-name)))))

    (testing "reveal-after-vote ON, this reviewer HAS rated -> the name returns"
      (let [html (render (submission-row/board-row reveal-event (enriched-row "rev1" true) person))]
        (is (str/includes? html speaker-name))
        (is (str/includes? html "https://secret.example/speaker.jpg"))
        (is (not (str/includes? html "Submission #3")))))))

(deftest reviewer-identity-never-hidden-test
  ;; Even fully blinded, the REVIEWER who commented/rated stays named — the
  ;; open table is untouched.
  (let [person {:id "rev1"}
        row (assoc (enriched-row "rev1" true)
                   :comments [{:id "c1" :person-id "revX" :person-name "Reviewer Xavier"
                               :body "Strong opener." :stars 4.0}]
                   :ratings [{:person-id "revX" :person-name "Reviewer Xavier" :stars 4.0}])
        html (render (submission-row/board-row hide-only-event row person))]
    (is (str/includes? html "Reviewer Xavier")
        "a reviewer's name must survive even under blind review")
    (is (str/includes? html "Submission #3")
        "the presenter is still blinded")))

(deftest submission-detail-blinds-speaker-test
  (let [person {:id "rev1"}
        opts {:person person :coverage-target 2 :notice nil}]
    (testing "settings OFF -> the detail page shows the speaker name and email"
      (let [html (render (review/submission-detail-page
                          open-event (enriched-row "rev1" false) opts))]
        (is (str/includes? html speaker-name))
        (is (str/includes? html "ann@bigco.example"))
        (is (not (str/includes? html vp/hidden-marker)))))

    (testing "hide ON -> detail receives neither identity fields nor a human face"
      (let [html (with-redefs [avatar/pool-face
                               (fn [& _] (throw (ex-info "blind avatar oracle" {})))]
                   (render (review/submission-detail-page
                            hide-only-event (enriched-row "rev1" false) opts)))]
        (is (str/includes? html "Submission #3"))
        (is (str/includes? html "blind-avatar"))
        (is (not (str/includes? html "https://secret.example/speaker.jpg")))
        (is (not (str/includes? html speaker-name)))
        (is (not (str/includes? html speaker-org)))
        (is (not (str/includes? html "ann@bigco.example")))
        (is (not (str/includes? html "A long and identifying bio.")))))))
