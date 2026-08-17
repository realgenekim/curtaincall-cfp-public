(ns cfp-scheduler-killer.seed-demo
  "`make seed-demo` — give a fresh clone a working world in one command.

   data/ is gitignored (the log is a runtime artifact, not source), so a clone
   starts empty. Rather than committing a fixture that silently rots, we rebuild
   the demo world by calling THE REAL CODE PATHS — the same functions the web
   UI calls. If a mutation breaks, this breaks, and we find out at seed time
   instead of in front of a judge.

   Idempotent: if the event is already there, it says so and changes nothing."
  (:require
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.crm :as crm]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.inform :as inform]
   [cfp-scheduler-killer.portal :as portal]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.schedule :as schedule]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [clojure.string :as str]
   [taoensso.timbre :as log])
  (:import
   (java.time LocalDate LocalDateTime))
  (:gen-class))

(def charlotte-slug "enterprise-ai-summit-charlotte")

(def charlotte-draft
  {:name "Enterprise AI Summit Charlotte"
   :slug charlotte-slug
   :tz "America/New_York"
   :starts-on (LocalDate/of 2026 10 14)
   :ends-on (LocalDate/of 2026 10 15)
   ;; The CFP must be OPEN in a fresh clone, or the seeded submissions can't be
   ;; created and a judge lands on a page that refuses to accept anything.
   :cfp-opens-at (LocalDateTime/of 2026 8 1 0 0)
   :cfp-closes-at (LocalDateTime/of 2026 9 15 23 59)
   :support-email "annp@itrevolution.net"
   :location "Charlotte, NC"
   :website-url "https://itrevolution.com/product/enterprise-ai-summit/"})

(def charlotte-committee
  [{:name "Gene Kim" :email "genek@itrevolution.net" :role "chair"}
   {:name "Ann Perry" :email "annp@itrevolution.net" :role "member"}
   {:name "Alex Broderick-Forster" :email "alex@itrevolution.net" :role "member"}])

(def crm-target-slug "enterprise-ai-summit-austin")

(def crm-target-draft
  (assoc charlotte-draft
         :name "Enterprise AI Summit Austin"
         :slug crm-target-slug))

(def ^:private crm-demo-tag "crm-demo")
(def ^:private crm-demo-segment "CRM demo outreach candidates")
(def ^:private crm-demo-note
  "Seeded CRM proof: review this speaker, then push them into Austin.")

;; --- Fake submissions -------------------------------------------------------
;;
;; Plausible enterprise-AI talks from the industries EAIS actually draws from —
;; a bank, an insurer, a retailer, a government agency, a manufacturer, a
;; logistics firm and a health system. Written to be BORINGLY realistic: the
;; point of demo data is that a judge reads a row and thinks "yes, that is what
;; a submission looks like," not "someone had fun with the placeholder text."

(def fake-submissions
  [{:title "Rebuilding Underwriting Around an LLM Without Rewriting the Mainframe"
    :abstract "We put a retrieval layer in front of 30 years of policy documents and let underwriters ask questions in plain English. The mainframe never moved. Here is the architecture, the two rewrites we needed, and the month we spent discovering that our documents were worse than our models."
    :format "Experience Report" :org-size ">10,000" :industry "Insurance"
    :history "Began mid-2023 with a document-retrieval pilot in commercial lines; today it serves every underwriter in North America, roughly 4,000 seats."
    :outcomes "Quote turnaround 6.2 days to 1.4 days. Underwriter capacity up 22% with no headcount change. $4.1M annualized loss-ratio improvement attributed to better precedent retrieval."
    :advice "Fix your document pipeline before you tune a single prompt. We spent four months on models and one week on OCR quality; the week mattered more."
    :notes "Happy to be scheduled opposite anything — I know I'm not the headliner. Legal has cleared the numbers above."
    :speaker {:name "Priya Raghavan" :email "priya.raghavan@meridianmutual.example.com"
              :title "VP, Underwriting Platforms" :org "Meridian Mutual"
              :bio "Priya has run underwriting technology at Meridian Mutual for nine years, through two core-system migrations and one that was cancelled."}}

   {:title "What 18 Months of AI Code Review Did to Our Change Failure Rate"
    :abstract "We put an AI reviewer on every pull request across 1,200 engineers. It caught things. It also missed things, loudly and confidently. This is the honest accounting: what improved, what got worse, and the review policy we landed on after three rewrites."
    :format "Experience Report" :org-size ">10,000" :industry "Financial Services"
    :history "Started December 2024 in one payments team; rolled to all of engineering by August 2025. Runs on every PR today."
    :outcomes "Change failure rate 14.8% to 9.1%. Median review latency 9.4 hours to 2.1 hours. But: security-relevant findings caught by humans fell 30% — reviewers started trusting the bot. That number is why we rewrote the policy."
    :advice "Measure what your humans STOP doing once the tool arrives. We only found the review-attention drop because someone went looking for it."
    :notes "The 30% number is not flattering to us and I would like to present it anyway. Please don't cut that slide."
    :speaker {:name "Marcus Devlin" :email "mdevlin@northbanc.example.com"
              :title "Distinguished Engineer" :org "Northbanc"
              :bio "Marcus works on developer productivity at Northbanc and has been arguing about code review since before it was automated."}}

   {:title "Forecasting 4,000 Stores: Replacing a Spreadsheet Nobody Understood"
    :abstract "Our demand forecast lived in a spreadsheet maintained by one person who retired. We replaced it with a model, and the hard part was never the model — it was proving to district managers that a number they could not audit deserved their trust."
    :format "Experience Report" :org-size ">10,000" :industry "Retail"
    :history "Kicked off January 2024 after the retirement. In production across all 4,000 stores since March 2025."
    :outcomes "Forecast error (WAPE) 31% to 18%. Markdown spend down $12M year over year. Out-of-stocks on promoted items down 26%."
    :advice "Budget as much time for explainability as for accuracy. Our first model was better and got rejected; the second was slightly worse and got adopted."
    :notes nil
    :speaker {:name "Dana Whitfield" :email "dana.whitfield@crestlineretail.example.com"
              :title "Senior Director, Supply Chain Analytics" :org "Crestline Retail"
              :bio "Dana leads supply chain analytics at Crestline and spent her first year there reverse-engineering a spreadsheet."}}

   {:title "AI in the Public Sector When You Cannot Send Data Anywhere"
    :abstract "Every constraint you have, we have, plus procurement and public records law. We run models entirely inside our own boundary for benefits eligibility triage. What that costs, what it buys, and the four vendor conversations that ended immediately."
    :format "Experience Report" :org-size "1,000–10,000" :industry "Government"
    :history "Authorized in 2024 after an 11-month review; first production workload (document triage for benefits applications) went live May 2025."
    :outcomes "Application backlog 47 days to 19 days. Eligibility determination accuracy unchanged at 96.8% — which was the requirement, not an improvement. Cost per determination down 31%."
    :advice "Write your data-residency requirements before your RFP, not during it. We restarted procurement once because we hadn't."
    :notes "I need to clear the final deck with our comms office, which takes about two weeks. Flagging early so it isn't a surprise."
    :speaker {:name "Alan Osei" :email "aosei@statedhs.example.gov"
              :title "Chief Data Officer" :org "State Department of Human Services"
              :bio "Alan has spent twelve years in state government technology and can explain procurement to you, slowly, if you have the time."}}

   {:title "The Copilot Rollout That Failed, and the One That Worked"
    :abstract "We deployed the same tool twice to the same company, eighteen months apart. The first rollout died at 11% weekly active. The second sits at 68%. Nothing about the tool changed. Everything about how we introduced it did."
    :format "Experience Report" :org-size ">10,000" :industry "Manufacturing"
    :history "First attempt Q1 2024, abandoned Q3. Second attempt began Q1 2025 and is now the default toolchain for 3,100 engineers."
    :outcomes "Weekly active 11% to 68%. Self-reported time saved is unreliable and I will say so on stage; the number I trust is that voluntary license renewals went from 40% to 94%."
    :advice "Stop running enablement as training. We won the second time by embedding four engineers in teams for a week each, doing the actual work."
    :notes nil
    :speaker {:name "Rebecca Lindqvist" :email "r.lindqvist@halvorsen-industrial.example.com"
              :title "Director of Engineering Effectiveness" :org "Halvorsen Industrial"
              :bio "Rebecca runs engineering effectiveness at Halvorsen and has now rolled out the same tool twice, which she says is one more time than anyone should."}}

   {:title "Reading 40,000 Bills of Lading a Day"
    :abstract "Document extraction is the least glamorous AI application and it paid for our entire program. Ninety minutes on the pipeline, the error budget, and the exceptions queue that turned out to be the whole product."
    :format "SME talk" :org-size "1,000–10,000" :industry "Transportation & Logistics"
    :history "Prototype in late 2023, production February 2024, now processing every inbound document across three regions."
    :outcomes "Manual keying reduced 78%. Exception rate 22% at launch, 6.5% today. Payback in 7 months on a $1.8M program."
    :advice "Design the exceptions queue first. It is where your humans live, and we built it last."
    :notes nil
    :speaker {:name "Tomás Herrera" :email "therrera@atlasfreight.example.com"
              :title "Head of Automation" :org "Atlas Freight"
              :bio "Tomás builds document-processing systems at Atlas Freight and has strong opinions about PDFs."}}

   {:title "Clinical Documentation: What We Refuse to Automate"
    :abstract "An honest talk about scope. We use ambient AI for clinical notes and we have drawn a hard line at anything touching diagnosis or orders. Where the line is, why it moved twice, and how we explain it to clinicians who want us to move it again."
    :format "Panel" :org-size ">10,000" :industry "Healthcare"
    :history "Ambient documentation piloted March 2024 in two specialties; expanded to twelve; deliberately not expanded further."
    :outcomes "Documentation time down 41 minutes per clinician per day. Burnout survey scores improved 0.6 points. Zero automated clinical decisions, which is the number I care most about."
    :advice "Decide what you will not automate and publish it internally. Our list is shorter than our clinicians expected and longer than our vendors wanted."
    :notes "Would prefer a panel format — I think this is better as a conversation than a lecture, and I can bring our Chief Medical Officer."
    :speaker {:name "Dr. Yolanda Bassett" :email "ybassett@riverbendhealth.example.org"
              :title "Chief Medical Information Officer" :org "Riverbend Health"
              :bio "Yolanda is a practising internist and Riverbend's CMIO, which means she reviews the notes the system writes about her own patients."}}

   ;; A SECOND talk from Marcus. Real speakers submit more than once (the cap is
   ;; three), and it is what makes a speaker double-booking demonstrable at all.
   {:title "Ten Things We Got Wrong Automating Code Review"
    :abstract "A rapid-fire companion to the main talk: ten specific mistakes, each with the metric that caught it and the fix that stuck. Bring a notebook; there is no narrative arc."
    :format "SME talk" :org-size ">10,000" :industry "Financial Services"
    :history "Same programme as our main submission — started December 2024, all of engineering by August 2025."
    :outcomes "Each item carries its own number; the aggregate is in the other talk."
    :advice "Write down what you expected BEFORE you roll out. We reconstructed our expectations afterwards and flattered ourselves."
    :notes "If you take both of ours, please don't schedule them opposite each other — I can only be in one room."
    :speaker {:name "Marcus Devlin" :email "mdevlin@northbanc.example.com"
              :title "Distinguished Engineer" :org "Northbanc"
              :bio "Marcus works on developer productivity at Northbanc and has been arguing about code review since before it was automated."}}

   ;; Two REAL repeat IT Revolution speakers (idx 8, 9), seeded accepted +
   ;; informed + scheduled so the public speakers page shows the "Previously at
   ;; IT Revolution" enrichment (resources/vl-speaker-history.edn). Matched to
   ;; their history record by speaker NAME.
   {:title "From Vibe Code to Fleet Mode: The Agentic Pivot Nobody's Ready For"
    :abstract "The whole industry sits in the same 20/60/20 adoption trap and is blind to it because the hiring freeze killed cross-company knowledge flow. What agent crews, versioned memory, and fleet mode actually look like in production — and the 90-day window to lead."
    :format "Experience Report" :org-size ">10,000" :industry "Software"
    :history "Four talks across the DevOps Enterprise / Enterprise AI Summit corpus, most recently San Jose 2026."
    :outcomes "A repeatable crew architecture (identity + versioned Beads memory) that survived real production agent workloads."
    :advice "Ditch Copilot. Move your top 20% to agentic tools now; use their learnings to pull the 60% along."
    :notes nil
    :speaker {:name "Steve Yegge" :email "steve.yegge@sourcegraph.example.com"
              :title "Distinguished Engineer" :org "Sourcegraph"
              :bio "Steve Yegge builds developer tools at Sourcegraph and has presented at IT Revolution events four times, most recently on fleet-mode agentic engineering."}}

   {:title "Production > Prototype: How a Small Team Replaced a Core Platform in 90 Days"
    :abstract "A third-party platform carried 30–40% of our revenue and was on fire. Two grumpy principal engineers, a lore repo, no procurement, and a 90-day clock. What we shipped, what broke, and why validation — not engineering — became the bottleneck."
    :format "Experience Report" :org-size "1,000–10,000" :industry "Healthcare"
    :history "Presented at Enterprise AI Summit San Jose 2026 on the 90-day core-platform replacement."
    :outcomes "75–80% of a core platform replaced in 90 days, $500K vendor spend saved, and 30–40% of revenue protected from acquisition."
    :advice "Don't wait for perfect. Ship something inside your current constraints and let the result do the political work."
    :notes nil
    :speaker {:name "Dustin Warner" :email "dustin.warner@nrchealth.example.com"
              :title "Director of Engineering" :org "NRC Health"
              :bio "Dustin Warner led the 90-day core-platform replacement at NRC Health and presented that experience report at the Enterprise AI Summit."}}])

(defn- ->params
  "The fake submission as the form params a real speaker would have typed —
   so seeding goes through EXACTLY the code path the public page uses."
  [f]
  (let [sp (:speaker f)]
    (cond-> {:answer-talk-title (:title f)
             :answer-abstract (:abstract f)
             :answer-session-format (:format f)
             :answer-org-size (:org-size f)
             :answer-industry (:industry f)
             :answer-ai-transformation-history (:history f)
             :answer-measurable-outcomes (:outcomes f)
             :answer-advice-to-peer (:advice f)
             :speaker-name (:name sp)
             :speaker-email (:email sp)
             :speaker-title (:title sp)
             :speaker-org (:org sp)
             :speaker-bio (:bio sp)}
      (:notes f) (assoc :answer-notes-to-committee (:notes f)))))

;; Ratings are scattered ON PURPOSE so both work queues have something to show:
;; two talks are unrated (the "needs reviews" queue has a job), several are at
;; or above the coverage target (the "ready to decide" queue has candidates),
;; and two are genuine splits (σ ≥ 1.2) so the SPLIT flag is not theoretical.
(def fake-reviews
  [{:idx 0 :ratings [["gene" 4.5] ["ann" 4.0] ["alex" 4.5]]
    :comments [["gene" "The loss-ratio number is the one that makes this a keynote candidate."]
               ["ann" "Legal already cleared the figures — one less thing in September."]]}
   {:idx 1 :ratings [["gene" 5.0] ["ann" 4.5]]
    :comments [["gene" "The 30% attention-drop finding is the most honest thing in this batch. Want it."]
               ["alex" "Agreed, and it pairs well with the copilot rollout talk."]]}
   {:idx 2 :ratings [["ann" 3.5]]
    :comments [["ann" "Solid, unglamorous, true. Might be a better fit for the practitioner track."]]}
   ;; A real split: Gene sees the constraint story, Alex sees a narrow audience.
   {:idx 3 :ratings [["gene" 5.0] ["ann" 3.0] ["alex" 2.0]]
    :comments [["gene" "Public-sector constraints make everyone else's excuses look thin. I'd program this."]
               ["alex" "Strong story, but how many people in our room run inside a government boundary?"]
               ["ann" "Comms-office delay is a real scheduling risk if we accept."]]}
   {:idx 4 :ratings [["gene" 4.0] ["ann" 4.5] ["alex" 4.0]]
    :comments [["ann" "'Same tool twice' is a great hook and the renewal number is credible."]]}
   ;; Deliberately left unrated — the coverage worklist needs a top.
   {:idx 5 :ratings [] :comments []}
   ;; The second split, plus only one review: shows up in BOTH queues.
   {:idx 6 :ratings [["gene" 5.0] ["alex" 2.5]]
    :comments [["gene" "'What we refuse to automate' is the talk nobody else is giving."]
               ["alex" "Panels underdeliver at our events more often than not. Sceptical of the format, not the speaker."]]}])

(defn- seed-reviews!
  "Apply the scattered ratings and comments through the REAL mutations, so the
   demo data and a real reviewer's clicks produce identical events."
  [subs people-by-key]
  (doseq [{:keys [idx ratings comments]} fake-reviews]
    (when-let [sub (nth subs idx nil)]
      (doseq [[who stars] ratings]
        (when-let [p (get people-by-key who)]
          (reviews/set-rating! (:id sub) (:id p) stars (:email p))))
      (doseq [[who body] comments]
        (when-let [p (get people-by-key who)]
          (reviews/add-comment! (:id sub) (:id p) body (:email p))))))
  ;; A spread of decisions, so the status chips aren't all one value and the
  ;; inform queue has something in it.
  (doseq [i [0 1 2 7 8 9]]
    (when-let [s (nth subs i nil)]
      (reviews/set-status! (:id s) "Accepted" "seed-demo")))
  (when-let [s (nth subs 4 nil)]
    (reviews/set-status! (:id s) "Waitlisted" "seed-demo"))
  ;; Accepted but deliberately NOT informed below — this is what keeps the
  ;; "decisions not yet communicated" banner alive for the demo.
  (when-let [s (nth subs 3 nil)]
    (reviews/set-status! (:id s) "Accepted" "seed-demo"))
  (when-let [s (nth subs 6 nil)]
    (reviews/toggle-priority! (:id s) "seed-demo")))

(defn- seed-decisions!
  "Inform ONE accepted speaker and deliberately leave the rest untold.

   That split is the demo: the informed speaker's portal shows the real status
   and a live checklist, while the dashboard still shouts about the decisions
   nobody has communicated. Both halves of the Inform pattern on screen at once."
  [event subs]
  ;; Inform TWO of the accepted talks, so the schedule has something to place
  ;; and the portal has an accepted speaker — while a third accepted decision
  ;; stays deliberately untold, keeping the Inform banner alive.
  (doseq [i [0 1 2 4 7 8 9]]
    (when-let [s (nth subs i nil)]
      (inform/inform! event (store/submission-by-id (:id s)) "seed-demo")))
  (when-let [s (nth subs 1 nil)]
    ;; …and that speaker has already started their checklist, so the portal
    ;; demonstrates partial progress rather than a pristine list.
    (portal/complete-task! (:id s) "confirm-bio" nil "seed-demo")
    (portal/update-profile!
      (:person-id (first (:speakers s)))
      {:tagline "Distinguished Engineer, Northbanc"
       :bio "Marcus works on developer productivity at Northbanc and has been arguing about code review since before it was automated."
       :linkedin-url "https://www.linkedin.com/in/example-marcus"}
      "seed-demo")))

(defn- seed-schedule!
  "Three rooms and a partly-built draft.

   Deliberately imperfect, because a finished schedule demonstrates nothing:
   one session is left UNROOMED (the first-class partial state), one Lunch block
   holds space, two of the accepted talks are still in the tray, and one speaker
   conflict is left standing so the chip has something to say. This is what an
   August afternoon actually looks like."
  [event subs]
  (let [rooms (mapv #(schedule/add-room! event % "seed-demo")
                    ["Main Stage" "Track A" "Track B"])
        [main track-a _track-b] rooms
        day1 (first (schedule/event-days event))
        informed (filterv #(some? (:notified-at (store/submission-by-id (:id %)))) subs)
        accepted (filterv #(= "Accepted" (:status (store/submission-by-id (:id %)))) informed)]
    (when day1
      (let [by-title (fn [needle]
                       (first (filter #(str/includes? (str (get-in % [:answers :talk-title]))
                                                      needle)
                                      accepted)))
            underwriting (by-title "Underwriting")
            code-review (by-title "18 Months")
            ten-things (by-title "Ten Things")
            yegge (by-title "Fleet Mode")
            warner (by-title "Production > Prototype")]
        (when underwriting
          (schedule/place! event (:id underwriting)
                           {:day day1 :start "09:30" :room-id (:id main)} "seed-demo"))
        ;; Both of Marcus's talks at 10:30 — an ENGINEERED conflict, left standing
        ;; so the chip has something true to say on a fresh clone.
        (when code-review
          (schedule/place! event (:id code-review)
                           {:day day1 :start "10:30" :room-id (:id track-a)} "seed-demo"))
        ;; …and this one is scheduled but NOT yet roomed, which is swyx's actual
        ;; workflow: "usually initial cal invite has no details then we assign
        ;; room later".
        (when ten-things
          (schedule/place! event (:id ten-things)
                           {:day day1 :start "10:30" :room-id nil} "seed-demo"))
        ;; The two real repeat IT Revolution speakers — placed so they're
        ;; published on the public speakers page and show the history badge.
        (when yegge
          (schedule/place! event (:id yegge)
                           {:day day1 :start "13:30" :room-id (:id main)} "seed-demo"))
        (when warner
          (schedule/place! event (:id warner)
                           {:day day1 :start "14:30" :room-id (:id main)} "seed-demo")))
      (schedule/add-block! event {:day day1 :start "12:00" :duration "60"
                                  :label "Lunch"} "seed-demo"))
    rooms))

(defn seed-submissions!
  "Add the fake submissions + reviews to `event`. Idempotent BY TALK TITLE:
   a fresh event gets the full corpus + reviews + decisions + schedule; an
   existing event gets a TOP-UP — only corpus entries whose talk title is
   missing are created (accepted/informed when the corpus index says so), so a
   grown demo world picks up newly-added seed speakers without duplicating
   anything. (The old any-submissions guard made the documented top-up a no-op.)"
  [event]
  (let [form-fields (:fields (events/form-for-event (:id event)))
        create! (fn [f]
                  (let [params (->params f)]
                    (submissions/create-submission!
                      event
                      (submissions/parse-answers form-fields params)
                      (submissions/parse-speaker params)
                      "form" "seed-demo")))]
    (if (pos? (submissions/count-for-event (:id event)))
      (let [existing-titles (into #{} (map #(get-in % [:answers :talk-title]))
                                  (submissions/for-event (:id event)))
            missing (into [] (keep-indexed
                               (fn [i f] (when-not (existing-titles (:title f)) [i f])))
                          fake-submissions)]
        (if (empty? missing)
          (do (println "   submissions already present — skipping") [])
          (let [created (mapv (fn [[i f]] [i (create! f)]) missing)
                subs (mapv second created)]
            (doseq [[i s] created]
              (when (#{0 1 2 7 8 9} i)
                (reviews/set-status! (:id s) "Accepted" "seed-demo"))
              (when (#{0 1 2 4 7 8 9} i)
                (inform/inform! event (store/submission-by-id (:id s)) "seed-demo")))
            (seed-schedule! (events/event-by-slug charlotte-slug) subs)
            (println "   top-up: added" (count subs) "missing submission(s)")
            subs)))
      (let [subs (mapv create! fake-submissions)
            people-by-key {"gene" (committees/person-by-email "genek@itrevolution.net")
                           "ann" (committees/person-by-email "annp@itrevolution.net")
                           "alex" (committees/person-by-email "alex@itrevolution.net")}]
        (seed-reviews! subs people-by-key)
        (seed-decisions! event subs)
        (seed-schedule! (events/event-by-slug charlotte-slug) subs)
        subs))))

(defn- ensure-crm-target! []
  (or (events/event-by-slug crm-target-slug)
      (let [event (events/create-eais-event! crm-target-draft "seed-demo")
            committee-id (:id (first (events/committees-for-event (:id event))))]
        (doseq [member charlotte-committee]
          (committees/add-member! committee-id member "seed-demo"))
        event)))

(defn- seed-crm! [event]
  (let [viewer (committees/person-by-email (:email (first charlotte-committee)))
        viewer-id (:id viewer)
        directory (crm/directory-for viewer-id {})
        contact (first (:all-contacts directory))
        person-id (:person-id contact)
        detail (when person-id (crm/detail-for viewer-id person-id))
        target (ensure-crm-target!)]
    (when person-id
      (when-not (contains? (set (:tags contact)) crm-demo-tag)
        (crm/add-tag! viewer-id person-id {:event-id (:id event)
                                           :tag crm-demo-tag
                                           :actor "seed-demo"}))
      (when-not (some #(= crm-demo-note (:body %)) (:notes detail))
        (crm/add-note! viewer-id person-id {:event-id (:id event)
                                            :body crm-demo-note
                                            :actor "seed-demo"}))
      (when-not (some #(= crm-demo-segment (:name %)) (:segments directory))
        (crm/save-segment! viewer-id (:id event) crm-demo-segment
                           {:tag crm-demo-tag} "seed-demo"))
      (crm/push-to-event! viewer-id person-id (:id target) "seed-demo"))
    target))

(defn- truncate
  "Shorten a title for the console table."
  [s n]
  (if (> (count s) n) (str (clojure.core/subs s 0 n) "…") s))

(defn report
  "Say plainly what is in there now — a seed you can't verify at a glance is a
   seed you stop trusting."
  [event]
  (let [committee-id (:id (first (events/committees-for-event (:id event))))
        subs (submissions/for-event (:id event))
        cov (reviews/coverage (:id event) 2)]
    (println "  " (:name event) "·" (:location event))
    (println "   Program Committee:")
    (doseq [r (committees/members-for-committee committee-id)]
      (println "    -" (:role r) "|" (:name r) "|" (:email r)))
    (println "   Submissions:" (count subs)
             (format "· coverage %.0f%% (%d/%d have >=2 reviews)"
                     (:pct cov) (:covered cov) (:total cov)))
    (doseq [s (reverse subs)]
      (let [st (reviews/submission-stats (:id s))]
        (println (format "    - %-4s n=%d %s %s"
                         (if (:mean st) (format "%.1f" (:mean st)) "—")
                         (:n st)
                         (if (:split? st) "SPLIT" "     ")
                         (truncate (get-in s [:answers :talk-title]) 58)))))
    (let [pending (count (inform/pending-decisions (:id event)))
          informed (inform/informed (:id event))]
      (println "   Decisions:" pending "waiting to be communicated ·"
               (count informed) "already told")
      (when-let [s (first informed)]
        (println "\n   Speaker portal demo — log in at /login as:")
        (println "    " (:email (first (:speakers s)))
                 (str "(" (:name (first (:speakers s))) ", accepted + informed)"))))
    (let [st (schedule/stats event)]
      (println "   Schedule:" (:placed st) "placed ·" (:unroomed st) "unroomed ·"
               (:unplaced st) "in the tray ·" (:conflicts st) "conflict(s)")
      (doseq [c (schedule/conflicts event)]
        (println "   " (:message c))))
    (println "\n   Organizer login: genek@itrevolution.net")
    (println "   Open http://localhost:20500/events")))

(defn seed!
  "Create the Charlotte event, its committee and its roster. Returns the event."
  []
  (store/load!)
  (if-let [existing (events/event-by-slug charlotte-slug)]
    (do (log/info :seed-demo-already-present :slug charlotte-slug)
        (println "Event already seeded:" charlotte-slug
                 "—" (count (committees/members-for-committee
                              (:id (first (events/committees-for-event (:id existing))))))
                 "committee members.")
        ;; The event may predate the submissions seed, so top it up.
        (seed-submissions! existing)
        (seed-crm! existing)
        (report existing)
        existing)
    (let [event (events/create-eais-event! charlotte-draft "seed-demo")
          committee-id (:id (first (events/committees-for-event (:id event))))]
      (doseq [m charlotte-committee]
        (committees/add-member! committee-id m "seed-demo"))
      (seed-submissions! event)
      (seed-crm! event)
      (println "Seeded" (:slug event))
      (report event)
      event)))

(defn -main [& _args]
  (seed!)
  (System/exit 0))

(comment
  (seed!))
