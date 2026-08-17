(ns cfp-scheduler-killer.seed-flood
  "`(flood! \"slug\" 50)` — drown an event in plausible submissions so the
   review board can be designed against real volume (Gene, 2026-08-09: 'have
   50 submissions come in').

   Same doctrine as seed-demo: every write goes through THE REAL CODE PATHS
   (create-submission!, set-rating!, add-comment!) so the flood and a real
   speaker's submit produce identical events. Names and companies are
   fictional-but-realistic — never a real stranger presented as having
   submitted to our conference.

   Reviews are seeded MID-FLIGHT on purpose: roughly half the pool has fewer
   than two ratings, so the coverage worklist has real work in it and the
   progress bar lands around 60% — a table that looks lived-in, not finished."
  (:require [clojure.string :as str]
            [taoensso.timbre :as log]
            [cfp-scheduler-killer.committees :as committees]
            [cfp-scheduler-killer.events :as events]
            [cfp-scheduler-killer.reviews :as reviews]
            [cfp-scheduler-killer.submissions :as submissions]))

;; --- Speaker pool -----------------------------------------------------------

(def ^:private first-names
  ["Maria" "James" "Priya" "Wei" "Sofia" "Marcus" "Ingrid" "Diego" "Amara"
   "Tomás" "Yuki" "Nadia" "Owen" "Fatima" "Lars" "Rosa" "Dmitri" "Chioma"
   "Hannah" "Rajesh" "Elena" "Kwame" "Astrid" "Pablo" "Mei"])

(def ^:private last-names
  ["Chen" "Okafor" "Lindqvist" "Ramírez" "Novak" "Patel" "Fischer" "Tanaka"
   "Osei" "Kowalski" "Bergström" "Silva" "Ivanova" "Nakamura" "Haddad"
   "Johansson" "Mbeki" "Costa" "Varga" "Ferreira"])

(def ^:private companies
  {"Financial Services" ["Meridian Capital" "Blue Harbor Bank" "Northgate Financial"]
   "Insurance"          ["Cornerstone Mutual" "Atlas Assurance" "Redwood Life"]
   "Healthcare"         ["Lakeview Health System" "Beacon Medical Group" "CarePoint Networks"]
   "Retail"             ["Hearthside Brands" "Summit Outfitters" "Pantry & Porch"]
   "Manufacturing"      ["Ironbridge Industries" "Cascade Precision" "Foundry North"]
   "Technology"         ["Verdant Systems" "Halcyon Software" "Ridgeline Labs"]
   "Telecom"            ["Skyward Communications" "Corelink Networks"]
   "Government"         ["State Revenue Office" "Metro Transit Authority"]
   "Energy"             ["Gulfstream Power" "Northwind Utilities"]
   "Transportation & Logistics" ["Crossdock Logistics" "Bluewater Freight"]})

(def ^:private roles
  ["VP Engineering" "CTO" "Head of Platform" "Chief Data Officer"
   "SVP Technology" "Director of AI Enablement" "VP Claims Technology"
   "Head of Developer Experience" "Chief Architect" "VP Digital"])

;; --- Talk generator ---------------------------------------------------------

(def ^:private title-fns
  "Each template is a fn of the company — slots can't misalign."
  [(fn [co] (format "How %s Cut %s by %d%% with AI Agents in Production"
                    co (rand-nth ["Change Lead Time" "Claims Cycle Time"
                                  "Release Cost" "Onboarding Time"])
                    (+ 25 (rand-int 60))))
   (fn [co] (format "From Pilot Purgatory to Production: %s's Two-Year AI Journey" co))
   (fn [co] (format "%d Engineers, %d Agents: Scaling AI Beyond the Demo at %s"
                    (+ 100 (rand-int 900)) (+ 8 (rand-int 60)) co))
   (fn [co] (format "What Broke When %s Gave Every Engineer an AI Pair" co))
   (fn [co] (format "The %s Playbook: AI Transformation Inside a %d-Year-Old Company"
                    co (+ 40 (rand-int 120))))
   (fn [co] (format "Killing Our Backlog: How %s Ships %d%% More with the Same Team"
                    co (+ 30 (rand-int 80))))
   (fn [co] (format "Boring AI That Works: %s's Production Lessons Nobody Tweets About" co))
   (fn [co] (format "Governance Without Gridlock: How %s Approved AI for %d Teams"
                    co (+ 10 (rand-int 50))))])

(def ^:private outcome-bits
  ["cycle time down 40%" "deploy frequency 3×" "$2.8M annualized savings"
   "claims handling time cut in half" "onboarding from 6 weeks to 4 days"
   "incident volume down 30%" "quote turnaround 5 days to 8 hours"
   "developer satisfaction up 18 points" "backlog burn rate doubled"])

(def ^:private advice-bits
  ["Start with the workflow, not the model."
   "Measure before and after — the anecdotes will lie to you."
   "Put your skeptics on the pilot team; converts evangelize better."
   "Budget for data cleanup first. It is always the data."
   "Ship to one team weekly rather than everyone quarterly."
   "Treat prompts like code: reviewed, versioned, tested."])

(defn- gen-title [company]
  ((rand-nth title-fns) company))

(defn- gen-abstract [company industry]
  (str "At " company " we spent the last two years moving AI from demos into "
       (str/lower-case industry) " production workflows. This talk covers the "
       "rollout that failed, the one that stuck, and the numbers behind both: "
       (rand-nth outcome-bits) ", " (rand-nth outcome-bits) ". "
       "We close with the three decisions we would make differently."))

(defn- gen-params
  "One plausible submission's form params, shaped exactly like a public POST.
   Select fields the generator doesn't know by name (e.g. :track) are answered
   from the FORM's own options, so a chair-invented select is auto-covered."
  [i form]
  (let [track-opts (some #(when (= "track" (name (:id %))) (:options %)) form)
        industry (rand-nth (keys companies))
        company (rand-nth (get companies industry))
        fname (rand-nth first-names)
        lname (rand-nth last-names)
        nm (str fname " " lname)
        email (str (str/lower-case fname) "." (str/lower-case lname)
                   "-" i "@example.com")]
    {:answer-talk-title (gen-title company)
     :answer-abstract (gen-abstract company industry)
     :answer-session-format (rand-nth ["Experience Report" "Experience Report"
                                       "Experience Report" "SME talk" "Panel"])
     :answer-track (when (seq track-opts) (rand-nth (vec track-opts)))
     :answer-org-size (rand-nth ["<1,000" "1,000–10,000" ">10,000" ">10,000"])
     :answer-industry industry
     :answer-ai-transformation-history
     (str "Started with pilots in " (rand-nth ["2023" "2024" "early 2024"])
          "; today " (rand-nth ["40% of PRs are agent-assisted"
                                "three business units run AI-assisted workflows in production"
                                "every engineer has an AI pair and two agent workflows run unattended"])
          ".")
     :answer-measurable-outcomes (str (rand-nth outcome-bits) "; "
                                      (rand-nth outcome-bits) ".")
     :answer-advice-to-peer (rand-nth advice-bits)
     :speaker-name nm
     :speaker-email email
     :speaker-title (rand-nth roles)
     :speaker-org company
     :speaker-bio (str nm " leads technology at " company
                       ", where the AI-assisted delivery program they run "
                       "covers " (+ 40 (rand-int 400)) " engineers.")}))

;; --- Review scatter ---------------------------------------------------------

(def ^:private comment-bits
  ["Strong numbers — exactly the kind of specificity our audience asks for."
   "Feels closer to a vendor pitch than an experience report. Second opinion?"
   "The failed-rollout section alone is worth the slot."
   "Overlaps with another submission this year — compare before deciding."
   "Would pair well with a panel on governance."
   "Abstract is thin but the outcomes line is remarkable. Leaning yes."])

(defn- scatter-reviews!
  "Mid-flight coverage: ~30%% of subs get 2 ratings, ~40%% one, the rest none.
   A sprinkle of comments so threads exist. All through the real mutations."
  [subs people]
  (doseq [sub subs]
    (let [n-ratings (rand-nth [0 0 0 1 1 1 1 2 2 2])
          raters (take n-ratings (shuffle people))]
      (doseq [p raters]
        (reviews/set-rating! (:id sub) (:id p)
                             (rand-nth [2 2.5 3 3 3.5 3.5 4 4 4.5 5])
                             (:email p)))
      (when (and (seq raters) (< (rand) 0.4))
        (let [p (first raters)]
          (reviews/add-comment! (:id sub) (:id p)
                                (rand-nth comment-bits) (:email p)))))))

;; --- Entry ------------------------------------------------------------------

(defn flood!
  "Create `n` plausible submissions on the event at `slug`, then scatter
   committee ratings/comments across them. Returns a count-first summary."
  [slug n]
  (let [event (events/event-by-slug slug)]
    (assert event (str "no event at slug " slug))
    (let [form-fields (:fields (events/form-for-event (:id event)))
          subs (mapv (fn [i]
                       (let [params (gen-params i form-fields)]
                         (submissions/create-submission!
                          event
                          (submissions/parse-answers form-fields params)
                          (submissions/parse-speaker params)
                          "form" "seed-flood")))
                     (range n))
          committee (first (events/committees-for-event (:id event)))
          people (when committee
                   (keep #(committees/person-by-email (:email %))
                         (committees/members-for-committee (:id committee))))]
      (scatter-reviews! subs (vec people))
      (let [total (submissions/count-for-event (:id event))]
        (log/info :seed-flood-done :slug slug :created (count subs) :total total)
        {:created (count subs) :total-now total
         :reviewers (count people)}))))
