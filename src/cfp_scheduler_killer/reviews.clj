(ns cfp-scheduler-killer.reviews
  "Ratings, comments, status and the board's arithmetic.

   The doctrine this implements (docs/research/cfp-flow-comparison.md): CFP
   review is a conversation among trusted peers about a shared table of
   proposals. So:
     - a rating is person × submission, authorized by that exact assignment
       (or the event chair's explicit exemption);
     - every score and every comment is visible to the whole committee;
     - the two default sorts ARE the two work queues, not a preference.

   Nothing here gates visibility. The committee remains an open conversation."
  (:require
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.mail :as mail]
   [cfp-scheduler-killer.review-authorization :as review-authorization]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.voting-policy :as voting-policy]
   [clojure.string :as str]
   [com.fulcrologic.guardrails.core :refer [=> >defn ?]]
   [taoensso.timbre :as log]))

;; --- Stars ------------------------------------------------------------------

(def min-stars 1.0)
(def max-stars 5.0)

(def star-steps
  "1.0 … 5.0 in halves. Halves came from the fulcro app — reviewers wanted to
   say 'better than a 4 but I'm not championing it'."
  (mapv #(/ % 2.0) (range 2 11)))

(def rateable-statuses
  "Submission states in which committee review evidence may still change."
  #{"Pending" "Accept Queue" "Decline Queue"})

(defn rateable-status?
  "True when a submission still accepts ratings and review input."
  [submission]
  (contains? rateable-statuses (:status submission)))

(defn board-visible?
  "Drafts are organizer work-in-progress, not Review Board submissions."
  [submission]
  (not= "Draft" (:status submission)))

(defn valid-stars?
  [x]
  (and (number? x)
       (<= min-stars (double x) max-stars)
       (zero? (mod (Math/round (* 2.0 (double x))) 1))
       (== (double x) (/ (Math/round (* 2.0 (double x))) 2.0))))

(defn parse-stars
  "Parse a star value from a form param. Returns nil when it isn't a legal step."
  [s]
  (let [n (cond
            (number? s) (double s)
            (string? s) (try (Double/parseDouble (str/trim s)) (catch Exception _ nil))
            :else nil)]
    (when (and n (valid-stars? n)) n)))

(def star-scale-help
  "The scale, in the words a human would use. Kept next to the scale itself so
   the message and the rule can never drift apart."
  "1 to 5, halves allowed — 1, 1.5, 2, 2.5, 3, 3.5, 4, 4.5, 5")

;; --- Arithmetic -------------------------------------------------------------

(defn mean
  "Arithmetic mean, or nil for no values — nil means 'nobody has said', which is
   a different fact from 0 and must never render as one."
  [xs]
  (when (seq xs)
    (/ (reduce + 0.0 (map double xs)) (count xs))))

(defn stddev
  "POPULATION standard deviation of the scores actually given. Population, not
   sample: these are all the opinions there are, not a sample of a larger pool."
  [xs]
  (when (> (count xs) 1)
    (let [m (mean xs)
          n (count xs)]
      (Math/sqrt (/ (reduce + 0.0 (map #(let [d (- (double %) m)] (* d d)) xs)) n)))))

(def split-threshold
  "SPREAD (max − min) at or above which the committee is genuinely split.

   This began as a σ threshold of 1.2 and was wrong: with three reviewers,
   population σ tops out around 0.95 for a full two-star disagreement, so the
   flag never fired on exactly the rows it was meant to catch — including the
   'someone said 2 and someone said 4' example in its own docstring. Spread is
   also what a human means by split, and what a chair can verify at a glance."
  2.0)

(defn spread
  "max − min. nil for fewer than two opinions, because one person cannot
   disagree with themselves."
  [xs]
  (when (> (count xs) 1)
    (- (apply max (map double xs)) (apply min (map double xs)))))

(defn split?
  [xs]
  (when-let [d (spread xs)]
    (>= d split-threshold)))

(defn submission-stats
  "Vote distribution and signed Stars for one submission."
  [submission-id]
  (let [state (store/snapshot)
        ratings (->> (store/ratings-for-submission submission-id)
                     (remove #(get-in state [:review-recusals
                                             [submission-id (:person-id %)]]))
                     vec)
        comments (->> (store/comments-for-submission submission-id)
                      (remove #(get-in state [:review-recusals
                                              [submission-id (:person-id %)]]))
                      vec)
        stars (map :stars ratings)]
    {:n (count ratings)
     :mean (mean stars)
     :stddev (stddev stars)
     :spread (spread stars)
     :split? (boolean (split? stars))
     :ratings ratings
     :comments comments}))

(defn coverage
  "How much of the event has been reviewed enough to decide.

   `target` is the committee's coverage-target (Gallimore's 2-review rule). The
   denominator is every submission in scope, so the number can only go up by
   doing the work — never by narrowing the question."
  [event-id target]
  (let [subs (store/submissions-for-event event-id)
        total (count subs)
        covered (count (filter (fn [s]
                                 (>= (:n (submission-stats (:id s))) target))
                               subs))]
    {:total total
     :covered covered
     :target target
     :pct (if (pos? total) (double (* 100 (/ covered total))) 0.0)}))

(defn coverage-for-rows
  "Coverage for an already-selected board slice. The caller owns the slice;
   this function only counts its human review totals."
  [rows target]
  (let [total (count rows)
        covered (count (filter #(>= (or (:n %) 0) target) rows))]
    {:total total
     :covered covered
     :target target
     :pct (if (pos? total) (double (* 100 (/ covered total))) 0.0)}))

;; --- Enrichment for the board ------------------------------------------------

(defn- person-name [person-id]
  (or (:name (store/person-by-id person-id)) "Someone"))

(defn enrich
  "A submission plus everything the board shows about it, with reviewer names
   resolved. One pass per row; the board is a few hundred rows at most."
  [submission]
  (let [{:keys [n mean stddev split? ratings comments]}
        (submission-stats (:id submission))]
    (assoc submission
           :n n
           :mean mean
           :stddev stddev
           :split? split?
           :ratings (mapv #(assoc % :person-name (person-name (:person-id %))) ratings)
           :comments (mapv #(assoc % :person-name (person-name (:person-id %))) comments))))

(defn enriched-for-event [event-id]
  (->> (store/submissions-for-event event-id)
       (sort-by (fn [submission]
                  [(or (:created-at submission) java.time.Instant/EPOCH)
                   (str (:id submission))]))
       (map-indexed (fn [index submission]
                      (assoc (enrich submission) :submission-number (inc index))))
       vec))

;; --- The two work queues ----------------------------------------------------

(def sort-presets
  [{:key "needs-reviews" :label "Needs reviews"
    :help "Fewest ratings first — the coverage worklist."}
   {:key "ready-to-decide" :label "Ready to decide"
    :help "Highest mean stars first; review count breaks ties — the decision queue."}])

(def default-sort "needs-reviews")

(def ^:private speaker-identity-columns
  #{"speaker" "speaker-first" "speaker-last" "org"})

(defn hide-presenter-sort?
  "True when board search/sort must not consult presenter identity."
  [event]
  (voting-policy/hide-presenter-info? event))

(def ^:private org-size-order
  "Small → large, however the option is spelled."
  {"<1,000" 0 "1,000–10,000" 1 ">10,000" 2})

(defn- column-keyfn
  "The LEDGER's sortable columns. nil for a name that is not a column."
  [col]
  (case col
    "title" #(str/lower-case (str (get-in % [:answers :talk-title])))
    "speaker" #(str/lower-case (str (:name (first (:speakers %)))))
    "speaker-first" #(str/lower-case (str (:name (first (:speakers %)))))
    "speaker-last" #(str/lower-case
                      (str (last (str/split
                                   (str (:name (first (:speakers %)))) #"\s+"))))
    "org" #(str/lower-case (str (:org (first (:speakers %)))))
    "submission" :submission-number
    "org-size" #(get org-size-order (get-in % [:answers :org-size]) 9)
    "voted" :n
    "avg" #(or (:mean %) -1.0)
    nil))

(defn sort-board
  "The TWO presets are the two jobs. `needs-reviews` = ascending rating count
   then newest first. `ready-to-decide` = descending mean Stars, then
   descending review count. Everything else is a sortable ledger column."
  ([rows preset target] (sort-board rows preset target false))
  ([rows preset target hide?]
   (case preset
     "ready-to-decide"
     (vec (sort-by (fn [r]
                     [(- (or (:mean r) -1.0))
                      (- (or (:n r) 0))])
                   rows))

     "needs-reviews"
     (vec (sort-by (fn [r]
                     [(:n r)
                      (- (inst-ms (or (:created-at r)
                                      (java.time.Instant/EPOCH))))])
                   rows))

     (let [[_ col dir] (re-matches #"(.+?)(-desc)?" (str preset))
           kf (column-keyfn col)]
       (if (and kf (not (and hide? (contains? speaker-identity-columns col))))
         (let [sorted (vec (sort-by kf rows))]
           (if dir (vec (rseq sorted)) sorted))
         (sort-board rows default-sort target hide?))))))

;; --- Search + filter --------------------------------------------------------

(defn matches-query?
  "Search across title, speaker name and org — the three things an organizer
   actually remembers about a talk."
  ([row q] (matches-query? row q false))
  ([row q hide?]
   (if (str/blank? q)
     true
     (let [q (str/lower-case (str/trim q))
           sp (first (:speakers row))
           fields (if hide?
                    [(get-in row [:answers :talk-title])]
                    [(get-in row [:answers :talk-title])
                     (:name sp) (:org sp) (:title sp)])
           hay (str/lower-case (str/join " " (remove nil? fields)))]
       (str/includes? hay q)))))

(defn status-counts
  [rows]
  (reduce (fn [m r] (update m (:status r) (fnil inc 0))) {} rows))

(defn track-counts
  "How many rows sit in each track — nil answers count under nil, so the
   facet row can show an honest \"(no track)\" bucket for submissions that
   predate the field (form snapshots make that a first-class state)."
  [rows]
  (reduce (fn [m r] (update m (get-in r [:answers :track]) (fnil inc 0)))
          {} rows))

(defn filter-board
  "Filter the board by search, status, and track. `:track` is an EXPLICIT
   single-track facet the reviewer clicked. `:tracks` is a SET — the reviewer's
   committee-scope default (their rooms); a row shows if its track is in the set.
   Both are pure filters and never gate rating; the caller decides whether to
   apply the committee default or let the reviewer see everything."
  [rows {:keys [q status track tracks hide?]}]
  (cond->> rows
    (not (str/blank? q)) (filter #(matches-query? % q hide?))
    (not (str/blank? status)) (filter #(= status (:status %)))
    ;; \"(none)\" selects the untracked bucket explicitly.
    (not (str/blank? track)) (filter #(= (if (= "(none)" track) nil track)
                                         (get-in % [:answers :track])))
    ;; Committee-scope default: keep only the reviewer's rooms. An explicit
    ;; single-track click above takes precedence; both may narrow together.
    (seq tracks) (filter #(contains? tracks (get-in % [:answers :track])))
    true vec))

;; --- Mutations --------------------------------------------------------------
;;
;; Be liberal in what you accept; be LOUD about what you refuse.
;;
;; Every mutation below used to have a silent escape hatch: a star value that
;; didn't parse, a comment that was all spaces, a status the event had never
;; heard of — each one quietly did nothing while the HTTP layer answered 303, so
;; an integrator posting `status=accepted` in lowercase lost the write and was
;; told it succeeded (bd sessionize-sched-killer-xmf). It even manufactured a
;; false pass in our own e2e driver.
;;
;; So: liberal, meaning a case variant of a status the event DOES have resolves
;; to the event's own spelling. Loud, meaning anything else throws a typed
;; ex-info carrying a :user-message the web layer can show verbatim — never a
;; no-op, never a nil return that a caller has to remember to check.

(defn- refuse!
  "Throw the one shape the web layer knows how to turn into a 422 + a notice.
   `:user-message` is written for the reviewer, not for a log."
  [type message data]
  (throw (ex-info message (merge {:type type :user-message message} data))))

(defn- require-active-review!
  [submission-id person-id]
  (when (get-in (store/snapshot) [:review-recusals [submission-id person-id]])
    (refuse! :reviewer-recused
             "Restore this review before participating in it."
             {:submission-id submission-id :person-id person-id})))

(defn- require-event-committee-member! [submission person-id]
  (when-not (committees/role-on-event (:event-id submission) person-id)
    (refuse! :not-on-review-committee
             "Only a review committee member for this event can do that."
             {:event-id (:event-id submission) :person-id person-id})))

(>defn set-rating!
       "Record one reviewer's current opinion of one talk.

   Appended, never updated: the projection upserts so the board shows the
   latest, and the log keeps every version — 'Ann moved from 2 to 4 after
   reading Gene's comment' is the most interesting fact in the system."
       [submission-id person-id stars actor]
       [string? string? number? string? => map?]
       (when-not (valid-stars? stars)
         (throw (ex-info (str "Not a legal star value: " stars)
                         {:type :invalid-stars :stars stars})))
       (let [submission (store/submission-by-id submission-id)]
         (when-not submission
           (throw (ex-info (str "No such submission: " submission-id)
                           {:type :no-such-submission :submission-id submission-id})))
         (when-not (rateable-status? submission)
           (refuse! :submission-not-rateable
                    (str (:status submission)
                         " submissions are read-only. Move it to Pending, Accept Queue, or Decline Queue to rate it.")
                    {:submission-id submission-id
                     :status (:status submission)
                     :allowed rateable-statuses}))
         (require-event-committee-member! submission person-id)
         (require-active-review! submission-id person-id)
         (let [previous (store/rating-by submission-id person-id)
               stars (double stars)]
           (if (= stars (:stars previous))
             previous
             (do
               (store/append! {:type "rating.set" :actor actor
                               :event-id (:event-id submission)
                               :payload {:submission-id submission-id
                                         :person-id person-id
                                         :stars stars
                                         :previous-stars (:stars previous)
                                         :at (store/now-iso)}})
               (log/info :rating-set :submission-id submission-id :person-id person-id
                         :stars stars :previous (:stars previous))
               (store/rating-by submission-id person-id))))))

(defn rate!
  "Rate from a RAW form/API value — the star scale enforced at the edge.

   Separate from `set-rating!` on purpose: `set-rating!` takes a number and is
   what the rest of the system calls, while this is the one place that has to
   cope with whatever an HTTP client typed. `\"99\"`, `\"abc\"`, `\"-3\"` and
   `\"\"` are four different mistakes and all four now say so.

   EMPTY IS A REFUSAL, NOT A CLEAR (decision, 2026-08-09). Clearing a rating
   would be a real event — `rating.cleared` — with its own fold, its own effect
   on every mean and on the coverage denominator, and no control anywhere in the
   product that asks for it. Silently treating a missing param as 'withdraw my
   opinion' would be a destructive write triggered by a typo, so we say 'pick a
   rating' instead. If clearing is ever wanted, it gets its own verb."
  [write-proof raw-stars actor]
  (let [{:keys [submission-id person-id]}
        (review-authorization/require-current-context!
          (store/snapshot) write-proof)
        raw (str raw-stars)]
    (cond
      (str/blank? raw)
      (refuse! :missing-stars
               (str "Pick a rating before you save. Ratings are " star-scale-help
                    ". (Clearing a rating isn't something this tool does yet.)")
               {:allowed star-steps})

      :else
      (if-let [stars (parse-stars raw)]
        (set-rating! submission-id person-id stars actor)
        (refuse! :invalid-stars
                 (str "\"" (str/trim raw) "\" isn't a rating on this scale. "
                      "Ratings are " star-scale-help ".")
                 {:stars raw-stars :allowed star-steps})))))

(>defn add-comment!
       "Add one comment. Comments accumulate; nothing is ever edited away.

   A comment of nothing but whitespace is REFUSED rather than dropped: it used
   to return nil and the board answered 303, so a reviewer who hit Say with an
   empty box was told their thought had been recorded."
       [submission-id person-id body actor]
       [string? string? (? string?) string? => (? map?)]
       (let [body (some-> body str/trim not-empty)
             submission (store/submission-by-id submission-id)]
         (when-not submission
           (throw (ex-info (str "No such submission: " submission-id)
                           {:type :no-such-submission :submission-id submission-id})))
         (require-event-committee-member! submission person-id)
         (require-active-review! submission-id person-id)
         (when-not body
           (refuse! :empty-comment
                    "Say something — an empty comment isn't a comment."
                    {}))
         (when body
           (let [id (store/new-id)]
             (store/append! {:type "comment.added" :actor actor
                             :event-id (:event-id submission)
                             :payload {:id id
                                       :submission-id submission-id
                                       :person-id person-id
                                       :body body
                                       :at (store/now-iso)}})
             (log/info :comment-added :submission-id submission-id :person-id person-id)
             (get-in (store/snapshot) [:comments id])))))

;; --- Mentions: "hey, can you look at this one?" -----------------------------
;;
;; A mention is a shoulder-tap between committee people — an INVITATION, never an
;; assignment. It rides alongside commenting: same append-only path, same "add a
;; fact, project it, never gate anything" discipline as a comment or a rating.
;; Two properties are load-bearing and tested:
;;   - CROSS-ROOM: the recipient may be any committee member of the EVENT, pulled
;;     from any track — the picker is committees/members-for-event, never the
;;     mentioner's own committee. An expert two rooms over is exactly the point.
;;   - PURELY ADDITIVE: a mention changes no status, no rating, no coverage. The
;;     recipient can ignore it forever with no consequence; it is a soft nudge.

(defn mentionable-people
  "The cross-room pool a mention may reach: every committee member of the event,
   deduplicated and name-sorted. Optionally drops `exclude-person-id` (usually
   the mentioner — you don't tap your own shoulder)."
  ([event-id] (mentionable-people event-id nil))
  ([event-id exclude-person-id]
   (->> (committees/members-for-event event-id)
        (remove #(and exclude-person-id (= exclude-person-id (:person-id %))))
        vec)))

(>defn mention!
       "Record one mention: `from` taps `to` on the shoulder about `submission-id`,
   with an optional note. Appends `submission.mention` and returns the folded
   fact. Optionally fires a soft email nudge THROUGH mail/send! — the firewall
   path that renders-not-sends without SMTP and honors the event mute; the
   in-app shelf is the primary surface, so a mail failure never matters here.

   The recipient is validated against the EVENT roster, not the mentioner's
   committee, which is what makes a mention cross-room. Refuses a self-mention
   and a recipient who isn't on the event — both as typed :user-message refusals
   the web layer already knows how to surface."
       [submission-id from-person-id to-person-id note actor]
       [string? string? (? string?) (? string?) string? => (? map?)]
       (let [submission (store/submission-by-id submission-id)]
         (when-not submission
           (throw (ex-info (str "No such submission: " submission-id)
                           {:type :no-such-submission :submission-id submission-id})))
         (require-event-committee-member! submission from-person-id)
         (require-active-review! submission-id from-person-id)
         (let [to (some-> to-person-id str/trim not-empty)
               note (some-> note str/trim not-empty)
               event-id (:event-id submission)
               roster (mentionable-people event-id)
               recipient (some #(when (= to (:person-id %)) %) roster)]
           (when-not to
             (refuse! :no-mention-recipient
                      "Pick a colleague to ask before you send." {}))
           (when (= to from-person-id)
             (refuse! :self-mention
                      "You're already looking at this one — pick a colleague." {}))
           (when-not recipient
             (refuse! :not-on-event
                      "That person isn't a committee member on this event." {}))
           (when (get-in (store/snapshot) [:review-recusals [submission-id to]])
             (refuse! :mention-recipient-recused
                      "That colleague is recused from this submission."
                      {:submission-id submission-id :person-id to}))
           (let [id (store/new-id)]
             (store/append! {:type "submission.mention" :actor actor
                             :event-id event-id
                             :payload {:id id
                                       :submission-id submission-id
                                       :from-person-id from-person-id
                                       :to-person-id to
                                       :note note
                                       :at (store/now-iso)}})
             (log/info :submission-mention :submission-id submission-id
                       :from from-person-id :to to)
             ;; Optional, additive email nudge — always through the firewall.
             (when-let [to-email (:email (store/person-by-id to))]
               (let [from-name (or (:name (store/person-by-id from-person-id)) "A colleague")
                     title (or (get-in submission [:answers :talk-title]) "a submission")]
                 (mail/send!
                   {:to to-email
                    :subject (str from-name " asked you to look at a talk")
                    :body (str from-name " asked if you could take a look at:\n\n"
                               "    \"" title "\"\n\n"
                               (when note (str "\"" note "\"\n\n"))
                               "This is an invitation, not an assignment — look if "
                               "you can, ignore it if you can't. It's also waiting "
                               "for you on your review board under \"You were asked "
                               "to look at these\".\n")}
                   {:event-id event-id :kind "mention-nudge" :actor actor
                    :submission-id submission-id :person-id to})))
             (get-in (store/snapshot) [:mentions id])))))

(defn mentions-shelf
  "The recipient's shelf: every 'look at this one?' addressed to `person-id` on
   THIS event, each joined to its submission (title) and the person who asked.
   Cross-room submissions are included — a mention is scoped to the person, so a
   talk from any track shows up. Ignorable by nature; nothing here is a to-do."
  [event-id person-id]
  (let [state (store/snapshot)
        subs-by-id (into {} (map (juxt :id identity)) (store/submissions-for-event event-id))]
    (->> (store/mentions-to-person person-id)
         (filter #(contains? subs-by-id (:submission-id %)))
         (remove (fn [{:keys [submission-id from-person-id]}]
                   (or (get-in state [:review-recusals [submission-id person-id]])
                       (get-in state [:review-recusals [submission-id from-person-id]]))))
         (mapv (fn [m]
                 (let [s (get subs-by-id (:submission-id m))]
                   (assoc m
                          :title (get-in s [:answers :talk-title])
                          :from-name (or (:name (store/person-by-id (:from-person-id m)))
                                         "Someone"))))))))

(defn canonical-status
  "Resolve a submitted status against a vocabulary, ignoring case and
   surrounding whitespace. Returns the VOCABULARY'S OWN spelling, or nil.

   Case-insensitive because the vocabulary is prose an organizer typed
   ('Accept Queue'), and demanding that an integrator reproduce our
   capitalization exactly is a rule with no purpose but to lose writes. The
   canonical spelling is what gets stored, so the log never learns a second way
   to say Accepted."
  [statuses to]
  (let [t (some-> to str str/trim str/lower-case not-empty)]
    (when t
      (first (filter #(= t (str/lower-case (str %))) statuses)))))

(>defn set-status!
       "Move a submission through the status vocabulary. The vocabulary lives in the
   event's settings, so an organizer can add 'Waitlisted' without a migration.

   `to` is matched case-insensitively (see `canonical-status`); a value that is
   genuinely not on this event throws :invalid-status carrying the whole list,
   so the caller can be TOLD what the choices are instead of guessing."
       [submission-id to actor]
       [string? (? string?) string? => map?]
       (let [submission (store/submission-by-id submission-id)]
         (when-not submission
           (throw (ex-info (str "No such submission: " submission-id)
                           {:type :no-such-submission :submission-id submission-id})))
         (let [event (store/get-event-by-id (:event-id submission))
               allowed (vec (get-in event [:settings :statuses]))
               raw to
               to (canonical-status allowed raw)]
           (when-not to
             (refuse! :invalid-status
                      (if (str/blank? (str raw))
                        (str "Choose a status. This event uses: "
                             (str/join ", " allowed) ".")
                        (str "\"" (str/trim (str raw)) "\" isn't a status on this event. "
                             "It uses: " (str/join ", " allowed) "."))
                      {:status raw :allowed allowed}))
           (let [from (:status submission)]
             (if (= from to)
               (do
                 (log/info :status-unchanged :submission-id submission-id :status to)
                 submission)
               (do
                 (store/append! {:type "submission.status-changed" :actor actor
                                 :event-id (:event-id submission)
                                 :payload {:submission-id submission-id
                                           :from from :to to :actor actor
                                           :at (store/now-iso)}})
                 (log/info :status-changed :submission-id submission-id :from from :to to)
                 (store/submission-by-id submission-id)))))))

(>defn toggle-priority!
       "The 🔥 flag — 'talk about this one on the call'."
       [submission-id actor]
       [string? string? => map?]
       (let [submission (store/submission-by-id submission-id)]
         (when-not submission
           (throw (ex-info (str "No such submission: " submission-id)
                           {:type :no-such-submission :submission-id submission-id})))
         (let [next? (not (boolean (:priority submission)))]
           (store/append! {:type "submission.priority-toggled" :actor actor
                           :event-id (:event-id submission)
                           :payload {:submission-id submission-id
                                     :priority next?
                                     :at (store/now-iso)}})
           (store/submission-by-id submission-id))))

;; --- By-reviewer pivot (the person page) ------------------------------------

(defn reviewer-summary
  "One reviewer's signed Stars and comments for one event, with their mean and
   the committee's mean over the same talks."
  [event-id person-id]
  (let [state (store/snapshot)
        submissions (store/submissions-for-event event-id)
        subs-by-id (into {} (map (juxt :id identity)) submissions)
        their-ratings (->> (store/ratings-by-person person-id)
                           (filter #(contains? subs-by-id (:submission-id %)))
                           (remove #(get-in state [:review-recusals
                                                   [(:submission-id %) person-id]]))
                           (mapv (fn [rating]
                                   (let [submission (get subs-by-id
                                                         (:submission-id rating))]
                                     (assoc rating
                                            :title (get-in submission
                                                           [:answers :talk-title])
                                            :submission submission
                                            :committee-mean
                                            (:mean (submission-stats
                                                     (:submission-id rating))))))))
        their-comments (->> (store/comments-by-person person-id)
                            (filter #(contains? subs-by-id (:submission-id %)))
                            (remove #(get-in state [:review-recusals
                                                    [(:submission-id %) person-id]]))
                            (mapv (fn [comment]
                                    (assoc comment
                                           :title
                                           (get-in (get subs-by-id
                                                        (:submission-id comment))
                                                   [:answers :talk-title])))))
        their-mean (mean (map :stars their-ratings))
        peer-mean (mean (keep :committee-mean their-ratings))]
    {:ratings their-ratings
     :comments their-comments
     :mean their-mean
     :committee-mean peer-mean
     :rated-count (count their-ratings)
     :total-submissions (count subs-by-id)}))

(comment
  (store/load!)
  (let [e (store/get-event-by-slug "enterprise-ai-summit-charlotte")]
    (coverage (:id e) 2)
    (sort-board (enriched-for-event (:id e)) "ready-to-decide" 2)))
