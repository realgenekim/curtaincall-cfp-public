(ns cfp-scheduler-killer.events-test
  "Event-creation slice tests, against the append-only store.

   Each test gets its own temp log file (see test-helpers), so there is no
   shared database, no cleanup, and no cross-test collision — the fixture is the
   isolation."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [cfp-scheduler-killer.events :as events]
            [cfp-scheduler-killer.seed :as seed]
            [cfp-scheduler-killer.store :as store]
            [cfp-scheduler-killer.test-helpers :refer [with-temp-store]])
  (:import (java.time LocalDate LocalDateTime)))

(use-fixtures :each with-temp-store)

(defn- test-draft
  [& [overrides]]
  (merge {:name "Kaocha Test Summit"
          :slug "kaocha-test"
          :tz "America/New_York"
          :starts-on (LocalDate/of 2026 10 14)
          :ends-on (LocalDate/of 2026 10 15)
          :cfp-opens-at (LocalDateTime/of 2026 8 10 0 0)
          :cfp-closes-at (LocalDateTime/of 2026 9 15 23 59)
          :presenter-visibility-mode "visible"
          :support-email "support@example.com"}
         overrides))

(defn- log-types [event-id]
  (mapv :type (events/log-for-event event-id)))

(defn- field-id-names [fields]
  (mapv (comp name :id) fields))

;; --- Pure: slug derivation --------------------------------------------------

(deftest slugify-test
  (testing "derives a clean slug from an event name"
    (is (= "enterprise-ai-summit-charlotte"
           (events/slugify "Enterprise AI Summit Charlotte")))
    (is (= "eais-charlotte-2026" (events/slugify "  EAIS Charlotte 2026!  ")))
    (is (= "a-b" (events/slugify "a --- b"))))

  (testing "strips accents rather than dropping the letters"
    (is (= "zurich-devops-dagen" (events/slugify "Zürich DevOps Dagen"))))

  (testing "never leaves leading/trailing hyphens"
    (is (= "hello" (events/slugify "---hello---")))
    (is (= "hello" (events/slugify "!!!hello???"))))

  (testing "degenerate input yields an empty slug, not junk"
    (is (= "" (events/slugify "")))
    (is (= "" (events/slugify nil)))
    (is (= "" (events/slugify "!!!"))))

  (testing "output always matches the slug pattern when non-empty"
    (doseq [s ["Enterprise AI Summit — Charlotte 2026!"
               "  spaces   everywhere  "
               "UPPER_and_under.scores"]]
      (is (re-matches events/slug-pattern (events/slugify s))
          (str "slug from " (pr-str s))))))

;; --- Display name: the ONE way an event is spelled --------------------------

(deftest display-name-test
  (testing "name — dates, with no location"
    (is (= "Enterprise AI Summit — Oct 7–8, 2026"
           (events/display-name {:name "Enterprise AI Summit"
                                 :starts-on (LocalDate/of 2026 10 7)
                                 :ends-on (LocalDate/of 2026 10 8)}))))

  (testing "name — location · dates"
    (is (= "Enterprise AI Summit — Charlotte, NC · Oct 7–8, 2026"
           (events/display-name {:name "Enterprise AI Summit"
                                 :location "Charlotte, NC"
                                 :starts-on (LocalDate/of 2026 10 7)
                                 :ends-on (LocalDate/of 2026 10 8)}))))

  (testing "a same-month range uses a tight en-dash; a cross-month range breathes"
    (is (= "Oct 7–8, 2026" (events/display-dates (LocalDate/of 2026 10 7)
                                                 (LocalDate/of 2026 10 8))))
    (is (= "Oct 30 – Nov 1, 2026" (events/display-dates (LocalDate/of 2026 10 30)
                                                        (LocalDate/of 2026 11 1)))))

  (testing "one day, or the same day twice, is one date"
    (is (= "Oct 7, 2026" (events/display-dates (LocalDate/of 2026 10 7)
                                               (LocalDate/of 2026 10 7))))
    (is (= "Oct 7, 2026" (events/display-dates (LocalDate/of 2026 10 7) nil))))

  (testing "a range that crosses a year says both years"
    (is (= "Dec 30, 2026 – Jan 2, 2027"
           (events/display-dates (LocalDate/of 2026 12 30) (LocalDate/of 2027 1 2)))))

  (testing "no dates yet: the name alone"
    (is (= "Enterprise AI Summit" (events/display-name {:name "Enterprise AI Summit"})))
    (is (nil? (events/display-dates nil nil))))

  (testing "no name: nil, which is the marquee's ghost state"
    (is (nil? (events/display-name {:starts-on (LocalDate/of 2026 10 7)})))
    (is (nil? (events/display-name {:name "   "}))))

  (testing "it takes the raw strings a browser posts, not just typed dates"
    (is (= "Enterprise AI Summit — Charlotte, NC · Oct 7–8, 2026"
           (events/display-name {:name "Enterprise AI Summit"
                                 :location "Charlotte, NC"
                                 :starts-on "2026-10-07" :ends-on "2026-10-08"}))))

  (testing "and it reads a real folded event, whose dates arrive as sql Dates"
    (let [event (events/create-event!
                 (test-draft {:name "Kaocha Display Summit" :slug "kaocha-display"
                              :location "Charlotte, NC"})
                 "kaocha")]
      (is (= "Kaocha Display Summit — Charlotte, NC · Oct 14–15, 2026"
             (events/display-name event))))))

;; --- Derived slugs ----------------------------------------------------------

(deftest derive-slug-test
  (testing "name + city + year"
    (is (= "enterprise-ai-summit-charlotte-2026"
           (events/derive-slug {:name "Enterprise AI Summit"
                                :location "Charlotte, NC"
                                :starts-on "2026-10-07"}))))

  (testing "the city is the FIRST comma-segment, so NC and North Carolina agree"
    (is (= (events/derive-slug {:name "X Summit" :location "Charlotte, NC"
                                :starts-on "2026-10-07"})
           (events/derive-slug {:name "X Summit" :location "Charlotte, North Carolina"
                                :starts-on "2026-10-07"}))))

  (testing "missing parts are simply left out"
    (is (= "enterprise-ai-summit-2026"
           (events/derive-slug {:name "Enterprise AI Summit" :starts-on "2026-10-07"})))
    (is (= "enterprise-ai-summit-charlotte"
           (events/derive-slug {:name "Enterprise AI Summit" :location "Charlotte, NC"})))
    (is (= "enterprise-ai-summit"
           (events/derive-slug {:name "Enterprise AI Summit"}))))

  (testing "NO NAME means NO SLUG — never an address derived from dates alone"
    ;; The bug this closes: an untouched form with prefilled dates offered
    ;; /cfp/2026, an address that names nothing.
    (is (nil? (events/derive-slug {:starts-on "2026-10-07" :location "Charlotte, NC"})))
    (is (nil? (events/derive-slug {:name "" :starts-on "2026-10-07"})))
    (is (nil? (events/derive-slug {:name "   " :location "Charlotte, NC"})))
    (is (nil? (events/derive-slug {}))))

  (testing "a distinct city or year keeps two same-named events apart"
    (is (not= (events/derive-slug {:name "AI Summit" :location "Charlotte, NC"
                                   :starts-on "2026-10-07"})
              (events/derive-slug {:name "AI Summit" :location "Austin, TX"
                                   :starts-on "2026-10-07"})))
    (is (not= (events/derive-slug {:name "AI Summit" :starts-on "2026-10-07"})
              (events/derive-slug {:name "AI Summit" :starts-on "2027-10-07"})))))

;; --- Trim assist ------------------------------------------------------------

(deftest trim-suggestion-test
  (testing "a parenthetical carrying a year"
    (is (= {:why "dates" :trimmed "Enterprise AI Summit"}
           (events/trim-suggestion "Enterprise AI Summit (Oct 2026)" nil))))

  (testing "a trailing month + digits"
    (is (= "Enterprise AI Summit"
           (:trimmed (events/trim-suggestion "Enterprise AI Summit Oct 7-8 2026" nil))))
    (is (= "EAIS" (:trimmed (events/trim-suggestion "EAIS — November 3, 2026" nil)))))

  (testing "the typed location repeated inside the name"
    (is (= {:why "location" :trimmed "Enterprise AI Summit"}
           (events/trim-suggestion "Enterprise AI Summit Charlotte" "Charlotte"))))

  (testing "ambiguity shows NOTHING — a wrong guess is worse than no assist"
    (is (nil? (events/trim-suggestion "Enterprise AI Summit" nil)))
    (is (nil? (events/trim-suggestion "MayDay Conference" nil))
        "a month word that isn't a date")
    (is (nil? (events/trim-suggestion "March Madness Summit" nil)))
    (is (nil? (events/trim-suggestion nil "Charlotte")))
    (is (nil? (events/trim-suggestion "(Oct 2026)" nil))
        "trimming to nothing is not a suggestion")
    (is (nil? (events/trim-suggestion "Charlotte" "Charlotte"))
        "the whole name IS the location — we have no better name to offer")))

;; --- Create defaults --------------------------------------------------------

(deftest apply-create-defaults-test
  (let [now (LocalDateTime/of 2026 8 9 12 0)]
    (testing "the call opens at creation, and the support email is the creator's"
      (let [d (events/apply-create-defaults
               (events/parse-form {:name "Defaults Summit" :slug "defaults"})
               "gene@example.com" now)]
        (is (= now (:cfp-opens-at d)))
        (is (= "gene@example.com" (:support-email d)))
        (is (nil? (:cfp-closes-at d)) "no close date means the call stays open")))

    (testing "an explicit support email always wins over the default"
      (let [d (events/apply-create-defaults
               (events/parse-form {:name "X" :slug "x" :support-email "ann@example.com"})
               "gene@example.com" now)]
        (is (= "ann@example.com" (:support-email d)))))

    (testing "'stays closed for now' closes the call the same instant it opens"
      ;; Zero-width window — no extra flag, no sentinel date. cfp-state reads
      ;; that pair back as :not-open-yet.
      (let [d (events/apply-create-defaults
               (events/parse-form {:name "X" :slug "x" :cfp-state "closed"
                                   :cfp-closes-on "2026-12-01"})
               "gene@example.com" now)]
        (is (= now (:cfp-opens-at d)))
        (is (= now (:cfp-closes-at d))
            "and the close date is ignored — they didn't choose when it shuts")))

    (testing "a close DATE becomes the END of that day"
      (let [d (events/apply-create-defaults
               (events/parse-form {:name "X" :slug "x" :cfp-closes-on "2026-11-20"})
               "gene@example.com" now)]
        (is (= (LocalDateTime/of 2026 11 20 23 59 59) (:cfp-closes-at d)))))

    (testing "the OLD datetime spelling still arrives, reduced to its date"
      ;; bin/e2e_drive.py and any existing integration still send this.
      (let [d (events/apply-create-defaults
               (events/parse-form {:name "X" :slug "x" :cfp-closes-at "2026-11-20T09:30"})
               "gene@example.com" now)]
        (is (= (LocalDateTime/of 2026 11 20 23 59 59) (:cfp-closes-at d)))))

    (testing "a posted cfp-opens-at is ignored — the call opens when it is created"
      (let [d (events/apply-create-defaults
               (events/parse-form {:name "X" :slug "x" :cfp-opens-at "2099-01-01T00:00"})
               "gene@example.com" now)]
        (is (= now (:cfp-opens-at d)))))))

;; --- Validation -------------------------------------------------------------

(deftest validation-test
  (testing "a well-formed draft passes"
    (is (nil? (events/validation-errors (test-draft)))))

  (testing "presenter visibility is an explicit creation decision"
    (doseq [missing-or-invalid [nil "" "sometimes" "reveal-after-vote"]]
      (is (contains? (events/validation-errors
                       (test-draft {:presenter-visibility-mode missing-or-invalid}))
                     :presenter-visibility-mode))))

  (testing "name is required"
    (is (contains? (events/validation-errors (test-draft {:name nil})) :name)))

  (testing "slug must match ^[a-z0-9-]+$"
    (doseq [bad ["Has Spaces" "UPPER" "under_score" "slash/es" "" nil]]
      (is (contains? (events/validation-errors (test-draft {:slug bad})) :slug)
          (str "should reject slug " (pr-str bad)))))

  (testing "an explicit slug obeys the same 60-character cap as a derived one"
    (is (nil? (events/validation-errors
                (test-draft {:slug (apply str (repeat 60 "a"))}))))
    (is (contains? (events/validation-errors
                     (test-draft {:slug (apply str (repeat 61 "a"))}))
                   :slug)))

  (testing "time zone must be a real zone"
    (is (contains? (events/validation-errors (test-draft {:tz "Mars/Olympus"})) :tz)))

  (testing "event dates must be ordered"
    (is (contains? (events/validation-errors
                    (test-draft {:starts-on (LocalDate/of 2026 10 15)
                                 :ends-on (LocalDate/of 2026 10 14)}))
                   :ends-on)))

  (testing "a CFP close date in the past is NOT a validation error — it means closed"
    ;; The call now opens at creation, so "must close after it opens" could only
    ;; ever reject a past close date. A past close date is a legitimate thing to
    ;; say: it is how "stays closed for now" and the Close-the-call button are
    ;; both recorded. submissions/cfp-state, not the validator, decides.
    (is (nil? (events/validation-errors
               (test-draft {:cfp-opens-at (LocalDateTime/of 2026 9 15 0 0)
                            :cfp-closes-at (LocalDateTime/of 2026 8 10 0 0)})))))

  (testing "a bad support email is caught, a blank one is fine"
    (is (contains? (events/validation-errors (test-draft {:support-email "nope"})) :support-email))
    (is (nil? (events/validation-errors (test-draft {:support-email nil})))))

  (testing "parse-form derives the slug server-side when it is left blank"
    (let [draft (events/parse-form {:name "Enterprise AI Summit Charlotte"
                                    :slug ""
                                    :tz "America/New_York"})]
      (is (= "enterprise-ai-summit-charlotte" (:slug draft)))
      (is (true? (:slug-derived? draft)))))

  (testing "parse-form keeps an explicit slug"
    (is (= "my-slug" (:slug (events/parse-form {:name "Whatever" :slug "my-slug"})))))

  (testing "parse-form marks unparseable dates rather than silently dropping them"
    (let [draft (events/parse-form {:name "X" :slug "x" :starts-on "not-a-date"})]
      (is (contains? (events/validation-errors draft) :starts-on)))))

(deftest optional-location-and-website-test
  (testing "both are optional — leaving them blank is valid"
    (is (nil? (events/validation-errors (test-draft {:location nil :website-url nil}))))
    (is (nil? (events/validation-errors (dissoc (test-draft) :location :website-url)))))

  (testing "a good website URL passes"
    (doseq [ok ["https://itrevolution.com"
                "http://example.org/path?a=b"
                "https://itrevolution.com/product/enterprise-ai-summit/"]]
      (is (nil? (events/validation-errors (test-draft {:website-url ok})))
          (str "should accept " ok))))

  (testing "a bare domain or non-http scheme is rejected"
    (doseq [bad ["itrevolution.com" "www.itrevolution.com" "ftp://x.com"
                 "javascript:alert(1)" "https://nodot" "not a url"]]
      (is (contains? (events/validation-errors (test-draft {:website-url bad})) :website-url)
          (str "should reject " bad))))

  (testing "an absurdly long location is rejected"
    (is (contains? (events/validation-errors
                    (test-draft {:location (apply str (repeat 201 "x"))}))
                   :location)))

  (testing "parse-form blanks both to nil rather than storing empty strings"
    (let [d (events/parse-form {:name "X" :slug "x" :tz "UTC"
                                :location "  " :website-url ""})]
      (is (nil? (:location d)))
      (is (nil? (:website-url d)))))

  (testing "parse-form trims what it keeps"
    (let [d (events/parse-form {:name "X" :slug "x" :tz "UTC"
                                :location "  Charlotte, NC "
                                :website-url " https://itrevolution.com "})]
      (is (= "Charlotte, NC" (:location d)))
      (is (= "https://itrevolution.com" (:website-url d))))))

;; --- Creation ---------------------------------------------------------------

(deftest create-event-test
  (let [draft (test-draft)
        event (events/create-event! draft "kaocha")
        event-id (:id event)]

    (testing "the event is written with the values we gave it"
      (is (some? event-id))
      (is (= "kaocha-test" (:slug event)))
      (is (= "Kaocha Test Summit" (:name event)))
      (is (= "America/New_York" (:tz event)))
      (is (= (LocalDate/of 2026 10 14) (:starts-on event)))
      (is (some? (:cfp-opens-at event)))
      (is (some? (:cfp-closes-at event))))

    (testing "settings round-trip as Clojure data with keyword keys"
      (is (= "open" (:review-visibility (:settings event))))
      ;; Waitlisted joined the vocabulary with the decision flow — it is one of
      ;; the three terminal decisions that have a letter.
      (is (= 8 (count (:statuses (:settings event)))))
      (is (contains? (set (:statuses (:settings event))) "Waitlisted"))
      (is (seq (:default-speaker-tasks (:settings event)))))

    (testing "a Program Committee is auto-spawned with scope :all"
      (let [committees (events/committees-for-event event-id)]
        (is (= 1 (count committees)))
        (is (= "Program Committee" (:name (first committees))))
        (is (= {:all true} (:scope (first committees))))
        (is (= 2 (:coverage-target (first committees))))))

    (testing "an ordinary event gets the broadly applicable seed form"
      (let [form (events/form-for-event event-id)
            fields (:fields form)]
        (is (some? form))
        (is (= "generic-conference" (:template form)))
        (is (= (field-id-names seed/generic-conference-form)
               (field-id-names fields)))
        (is (= "talk-title" (name (:id (first fields)))))
        (is (some #(= "speakers" (name (:id %))) fields))))

    (testing "creating an event records the explicit visibility policy atomically"
      (is (= ["event.created" "committee.created" "form.installed"
              "review.presenter-visibility-set"]
             (log-types event-id))))

    (testing "the event shows up in the list and by slug"
      (is (some #(= event-id (:id %)) (events/list-events)))
      (is (= event-id (:id (events/event-by-slug "kaocha-test")))))

    (testing "and it survives a reload from disk — the log IS the state"
      (store/load!)
      (let [reloaded (events/event-by-slug "kaocha-test")]
        (is (= event-id (:id reloaded)))
        (is (= "Kaocha Test Summit" (:name reloaded)))
        (is (= (LocalDate/of 2026 10 14) (:starts-on reloaded)))
        (is (= 1 (count (events/committees-for-event event-id))))))))

(deftest location-website-round-trip-test
  (let [event (events/create-event!
               (test-draft {:location "Charlotte, NC"
                            :website-url "https://itrevolution.com/product/enterprise-ai-summit/"})
               "kaocha")]
    (testing "both fields round-trip through the append and the projection"
      (is (= "Charlotte, NC" (:location event)))
      (is (= "https://itrevolution.com/product/enterprise-ai-summit/" (:website-url event))))
    (testing "and through a reload"
      (store/load!)
      (let [reloaded (events/event-by-slug "kaocha-test")]
        (is (= "Charlotte, NC" (:location reloaded)))
        (is (= (:website-url event) (:website-url reloaded)))))))

(deftest update-event-details-test
  (let [created (events/create-event! (test-draft) "kaocha")
        event-id (:id created)]

    (testing "an event created without them starts empty"
      (is (nil? (:location created)))
      (is (nil? (:website-url created))))

    (testing "the update writes both and returns the new projection"
      (let [updated (events/update-event-details!
                     event-id
                     {:location "Charlotte, NC" :website-url "https://itrevolution.com"}
                     "kaocha")]
        (is (= "Charlotte, NC" (:location updated)))
        (is (= "https://itrevolution.com" (:website-url updated)))))

    (testing "it appended one event.updated carrying before AND after"
      (let [row (last (events/log-for-event event-id))]
        (is (= "event.updated" (:type row)))
        (is (= #{"location" "website-url"} (set (:changed (:payload row)))))
        (is (nil? (:location (:before (:payload row)))))
        (is (= "Charlotte, NC" (:location (:changes (:payload row)))))))

    (testing "keys NOT mentioned are left alone, never blanked"
      (events/update-event-details! event-id {:location "Las Vegas, NV"} "kaocha")
      (let [e (events/event-by-slug "kaocha-test")]
        (is (= "Las Vegas, NV" (:location e)))
        (is (= "https://itrevolution.com" (:website-url e)))))

    (testing "a stray key can't reach the update"
      (is (nil? (events/update-event-details! event-id {:slug "hijacked"} "kaocha")))
      (is (= "kaocha-test" (:slug (events/event-by-slug "kaocha-test")))))

    (testing "an unknown event id is a typed error"
      (let [thrown (try (events/update-event-details! (store/new-id) {:location "X"} "kaocha")
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :no-such-event (:type (ex-data thrown))))))

    (testing "the updates survive a reload"
      (store/load!)
      (is (= "Las Vegas, NV" (:location (events/event-by-slug "kaocha-test")))))))

(deftest duplicate-slug-test
  (events/create-event! (test-draft) "kaocha")

  (testing "validation rejects a slug that is already taken"
    (let [errors (events/validation-errors (test-draft))]
      (is (contains? errors :slug))
      ;; The refusal NAMES the event holding the address — a slug is a permalink
      ;; and an ICS UID seed, so it is never silently suffixed with -2.
      (is (re-find #"That URL is taken by" (first (:slug errors))))
      (is (re-find #"Kaocha Test Summit" (first (:slug errors))))))

  (testing "and create-event! refuses it outright"
    (let [thrown (try (events/create-event! (test-draft) "kaocha")
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown) "a duplicate create must not succeed")
      (is (= :duplicate-slug (:type (ex-data thrown))))))

  (testing "the failed attempt appended NOTHING — the log has only the first create"
    (is (= 4 (count (store/read-events))))
    (is (= 1 (count (events/list-events))))))

(deftest create-demo-event-test
  (let [demo (events/create-demo-event!)]
    (testing "the demo button uses the same code path and gets a unique slug"
      (is (= "Demo Conference" (:name demo)))
      (is (re-matches #"^demo-[a-z0-9]{6}$" (:slug demo)))
      (is (= 1 (count (events/committees-for-event (:id demo)))))
      (let [form (events/form-for-event (:id demo))]
        (is (= "eais-charlotte" (:template form)))
        (is (= (field-id-names seed/eais-charlotte-form)
               (field-id-names (:fields form))))))
    (testing "two demo events don't collide"
      (let [second-demo (events/create-demo-event!)]
        (is (not= (:slug demo) (:slug second-demo)))
        (is (= 2 (count (events/list-events))))))))

(deftest generic-and-demo-seed-forms-cannot-drift-together-test
  (let [ordinary (events/create-event! (test-draft) "kaocha")
        demo (events/create-demo-event!)
        generic-fields (:fields (events/form-for-event (:id ordinary)))
        demo-fields (:fields (events/form-for-event (:id demo)))
        ids #(set (map (comp keyword name :id) %))
        generic-ids (ids generic-fields)
        demo-ids (ids demo-fields)
        generic-contract #{:talk-title :abstract :audience-level
                           :session-format :session-length :prior-talk-video
                           :av-accessibility-needs :speakers}
        eais-specific #{:track :org-size :industry :ai-transformation-history
                        :measurable-outcomes :advice-to-peer
                        :business-co-presenter}]
    (is (= generic-contract generic-ids)
        "new conferences start useful without inheriting one event's taxonomy")
    (is (empty? (set/intersection generic-ids eais-specific))
        "ordinary CFPs contain no Enterprise AI interrogation fields")
    (is (set/subset? eais-specific demo-ids)
        "the rich EAIS demo retains every event-specific field")
    (is (not= generic-ids demo-ids)
        "the generic and demo seeds must remain deliberately distinct")))

(deftest working-event-test
  ;; Pure derivation over plain maps — no store needed. The rail is always ONE
  ;; event's spine (docs/design/nav-elements.md); this pins whose.
  (testing "the remembered (last-visited) event wins"
    (reset! events/working-events {})
    (events/remember-working-event! "p1" "b")
    (is (= "b" (:id (events/working-event
                     "p1" [{:id "a" :starts-on "2030-01-01"}
                           {:id "b" :starts-on "2030-02-01"}])))))
  (testing "a remembered event no longer visible falls through to derivation"
    (reset! events/working-events {})
    (events/remember-working-event! "p1" "gone")
    (is (= "n" (:id (events/working-event
                     "p1" [{:id "n" :starts-on "2030-01-01"}])))))
  (testing "cold start picks the nearest upcoming event"
    (reset! events/working-events {})
    (is (= "n" (:id (events/working-event
                     nil [{:id "f" :starts-on "2031-06-01"}
                          {:id "p" :starts-on "2020-01-01"}
                          {:id "n" :starts-on "2030-01-01"}])))))
  (testing "all in the past -> the most recent one"
    (reset! events/working-events {})
    (is (= "l" (:id (events/working-event
                     nil [{:id "e" :starts-on "2019-01-01"}
                          {:id "l" :starts-on "2020-01-01"}])))))
  (testing "no events -> nil, never a placeholder"
    (reset! events/working-events {})
    (is (nil? (events/working-event "p1" [])))))

;; INTENT-TEST: EVENT-001
(deftest update-event-details-roundtrip-test
  ;; Regression for 2026-08-09: the FIRST date edit crashed json/write —
  ;; update-event-details! put raw LocalDates from the folded row into the
  ;; :before snapshot. Gene: "that should have been caught by an automated
  ;; test." This is that test: every whitelisted column through the
  ;; append -> fold -> read roundtrip, TWICE — the second pass is the crash
  ;; case, because only then does :before contain real temporal values.
  (let [event (events/create-event!
               {:name "Roundtrip Summit" :slug "roundtrip-test"
                :tz "America/New_York"
                :starts-on (LocalDate/of 2027 5 1)
                :ends-on (LocalDate/of 2027 5 2)
                :location "Charlotte, NC"}
               "kaocha")]
    (events/update-event-details!
     (:id event)
     {:name "Renamed Summit" :starts-on "2027-06-01" :ends-on "2027-06-02"
      :tz "Europe/London" :location "Leeds, UK"
      :website-url "https://example.test/" :support-email "help@example.test"
      :cfp-intro "The pitch that sells the conference."}
     "kaocha")
    ;; The crash case: :before now holds LocalDates folded from the log.
    (events/update-event-details!
     (:id event) {:starts-on "2027-07-01" :ends-on "2027-07-03"} "kaocha")
    (let [e (events/event-by-slug "roundtrip-test")]
      (is (= "Renamed Summit" (:name e)))
      (is (= (LocalDate/of 2027 7 1) (:starts-on e)) "dates fold back as LocalDates")
      (is (= (LocalDate/of 2027 7 3) (:ends-on e)))
      (is (= "Europe/London" (:tz e)))
      (is (= "Leeds, UK" (:location e)))
      (is (= "The pitch that sells the conference." (:cfp-intro e)))
      (is (= "roundtrip-test" (:slug e)) "the slug never moves"))
    (testing "present-with-nil clears; absent preserves"
      (events/update-event-details! (:id event) {:cfp-intro nil} "kaocha")
      (let [e (events/event-by-slug "roundtrip-test")]
        (is (nil? (:cfp-intro e)))
        (is (= "Renamed Summit" (:name e)) "unmentioned keys untouched")))))

(deftest archive-event-test
  ;; Archive is a FACT, never a deletion (2026-08-10): the event stays in the
  ;; log, listings stop leading with it, unarchive is the appended undo.
  (let [event (events/create-event!
               {:name "Archive Me" :slug "archive-me" :tz "America/New_York"}
               "kaocha")]
    (is (nil? (:archived-at (events/event-by-slug "archive-me"))))
    (events/archive-event! event "kaocha")
    (let [e (events/event-by-slug "archive-me")]
      (is (some? (:archived-at e)) "the fold marks it")
      (is (= "archive-me" (:slug e)) "nothing was deleted"))
    (testing "archived events never win the working-event derivation"
      (is (nil? (events/working-event nil [(events/event-by-slug "archive-me")]))))
    (testing "unarchive is the appended undo"
      (events/unarchive-event! event "kaocha")
      (is (nil? (:archived-at (events/event-by-slug "archive-me")))))))
