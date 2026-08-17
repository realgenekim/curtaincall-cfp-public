(ns cfp-scheduler-killer.views.pipeline
  "The decide → tell → announce stage strip (bead 4d3, Gene ratified
   2026-08-11, the inform-screen riff). ONE component rendered on the Review
   Board, Inform, and Announce screens. Each screen keeps its own queue — the
   Submissions-page lesson: two near-identical tables force \"which one is
   real?\" — the strip only ORIENTS: the same counts on every screen, the
   current stage hot, each stage a door."
  (:require
   [cfp-scheduler-killer.domain.speakers :as speaker-domain]
   [cfp-scheduler-killer.inform :as inform]
   [cfp-scheduler-killer.public-catalog :as public-catalog]
   [cfp-scheduler-killer.store :as store]))

(defn- stage [active? href label detail]
  [:a.ps-stage {:class (when active? "ps-active") :href href}
   [:span.ps-label label]
   [:span.ps-detail detail]])

(defn- hours-ago [t]
  (try
    (let [inst (if (instance? java.time.Instant t) t (java.time.Instant/parse (str t)))
          h (.toHours (java.time.Duration/between inst (java.time.Instant/now)))]
      (cond (< h 1) "under an hour ago"
            (= h 1) "1h ago"
            (< h 48) (str h "h ago")
            :else (str (quot h 24) "d ago")))
    (catch Exception _ "")))

(defn- first-name [full]
  (or (first (clojure.string/split (str full) #"\s+")) "there"))

(defn- copy-btn [label text]
  [:button.pc-copy {:type "button" :data-copy text :onclick "copyShare(this)"} label])

(defn- pc-stage
  "One band of the cascade: number, name, BALL chip, the teaching description,
   meter line, rows, and the flow line into the next stage."
  [{:keys [n name ball desc meter rows flow link note]}]
  [:section.pc-stage
   [:header.pc-head
    [:span.pc-n n] [:h3.pc-name name]
    [:span.pc-ball ball]
    (when link [:a.pc-jump {:href (first link)} (second link)])]
   [:p.pc-desc desc]
   note
   [:div.pc-meter meter]
   (when (seq rows) [:div.pc-rows rows])
   (when flow [:div.pc-flow "▼  " flow])])

(defn pipeline-cascade
  "The vertical pipeline: five stages, each with one owner, each described in
   the product's own voice. Doubles as the Inform page's empty-state body —
   an empty queue is a handoff, never a dead end (Gene, 2026-08-11)."
  ([event] (pipeline-cascade event {}))
  ([event {:keys [mail-note]}]
   (let [slug (:slug event)
         ename (or (:name event) "the event")
         subs (store/submissions-for-event (:id event))
         undecided (count (filter #(= "Pending" (:status %)) subs))
         decided (count (filter #(#{"Accepted" "Waitlisted" "Declined"} (:status %)) subs))
         to-tell (count (inform/pending-decisions (:id event)))
         told (inform/informed (:id event))
         roster (public-catalog/public-speakers event)
         no-bio (filterv #(clojure.string/blank? (:bio %)) roster)
         no-shot (filterv #(clojure.string/blank? (:headshot %)) roster)
         {:keys [lit total]} (public-catalog/announce-stats event)
         row (fn [glyph name detail action]
               [:div.pc-row [:span.pc-glyph glyph] [:span.pc-rowname name]
                [:span.pc-detail detail] action])
         expand-rows (fn [xs render]
                       ;; First four inline; the rest behind a native <details>
                       ;; disclosure — server-rendered, zero JS, zero state.
                       (list (map render (take 4 xs))
                             (when (> (count xs) 4)
                               [:details.pc-more-details
                                [:summary.pc-more (str "…" (- (count xs) 4) " more — expand")]
                                (map render (drop 4 xs))])))]
     [:div.pipeline-cascade
      (pc-stage
        {:n "①" :name "Decide" :ball (str "committee has the ball (" undecided ")")
         :link [(str "/events/" slug "/board") "Board →"]
         :desc "The committee reads, rates, and rules. A talk leaves this stage the moment its status becomes Accepted, Waitlisted or Declined on the review board."
         :meter (str undecided " awaiting decision · " decided " decided")
         :flow (str decided (if (= 1 decided) " decision flows down" " decisions flow down"))})
      (pc-stage
        {:n "②" :name "Tell" :ball (if (pos? to-tell)
                                     (str "we have the ball (" to-tell ")")
                                     "we're clear")
         :note mail-note
         :desc "A decision isn't real until the speaker hears it. Every letter is read before it's sent, and every send is on the record."
         :meter (if (pos? to-tell)
                  (str to-tell " to tell · " (count told) " told")
                  (str (count told) " told · 0 owed — you're clear"))
         :rows (expand-rows told
                            (fn [s]
                              (let [sp (first (:speakers s))]
                                (row "✓" (list (or (:name sp) "—")
                                               (when-let [co (not-empty (:org sp))]
                                                 [:span.pc-rowco (str " · " co)]))
                                     (str "emailed " (hours-ago (:notified-at s))) nil))))
         :flow (str (count told) " told speakers flow down")})
      (pc-stage
        {:n "③" :name "Confirm" :ball (str "speakers have the ball (" (count told) ")")
         :desc "Being told isn't being booked. The speaker says \"yes, I'm coming\" — people decline acceptances, and finding out late is how programs get holes."
         :meter (str "0 of " (count told) " confirmed")
         :rows (expand-rows told
                            (fn [s]
                              (let [sp (first (:speakers s))
                                    nm (:name sp)]
                                (row "◌" (list nm (when-let [co (not-empty (:org sp))]
                                                    [:span.pc-rowco (str " · " co)]))
                                     (str "emailed " (hours-ago (:notified-at s)) ", no reply")
                                     (copy-btn "Copy nudge note"
                                               (str "Hi " (first-name nm) " — just making sure our acceptance note reached you. Can you confirm you're in? We'd love to lock the program."))))))
         :flow "confirmed speakers flow down"})
      (pc-stage
        {:n "④" :name "Materials" :ball (str "speakers have the ball (" (count no-bio) ")")
         :desc "Everything the program reprints: final title, bio, headshot. A brag page with a missing bio is a chase note that writes itself."
         :meter (str (count no-bio) " bios missing · " (count no-shot) " headshots missing")
         :rows (expand-rows no-bio
                            (fn [sp]
                              (row "◌" (list (:name sp)
                                             (when-let [co (not-empty (:company sp))]
                                               [:span.pc-rowco (str " · " co)]))
                                   "bio missing"
                                   (copy-btn "Copy chase note"
                                             (str "Hi " (first-name (:name sp)) " — your speaker page for " ename " is live and looks great, but it's missing your bio. Send a short one and we'll have it up within the day.")))))
         :flow "ready speakers flow down"})
      (pc-stage
        {:n "⑤" :name "Announce" :ball "we have the ball → then the speakers"
         :link [(str "/events/" slug "/announce") "Announce →"]
         :desc "The payoff. Their page goes live, you hand them the words, they tell the world — and every post sells your event for you."
         :meter (str lit " of " total " pages lit")})
      [:p.pc-future "⑥ (future) Approval — ball: their employer — slots between ③ and ⑤ when a speaker's comms team must sign off before announcing."]])))

(defn stage-strip
  "The data-defined Decide → Inform → Manage lifecycle with live counts."
  [event active]
  (let [slug (:slug event)
        undecided (->> (store/submissions-for-event (:id event))
                       (filter #(= "Pending" (:status %)))
                       count)
        to-tell (count (inform/pending-decisions (:id event)))
        told (count (inform/informed (:id event)))
        {:keys [lit total]} (public-catalog/announce-stats event)
        details {:decide (str undecided " pending")
                 :inform (str to-tell " to inform · " told " informed")
                 :manage (str total " speakers · " lit " public")}]
    [:nav.pipeline-strip
     (interpose
     [:span.ps-arrow "→"]
       (for [{:keys [id label href]} speaker-domain/speaker-workspaces]
         (stage (= active id)
                (str "/events/" slug href)
                label
                (get details id))))]))
