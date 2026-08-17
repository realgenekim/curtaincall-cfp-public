(ns cfp-scheduler-killer.views.integrations
  "Exports, API docs, credentials, and integration settings."
  (:require
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.exports :as exports]
   [cfp-scheduler-killer.submissions :as submissions]
   [cfp-scheduler-killer.views.format :as format]
   [cfp-scheduler-killer.views.organizer-layout :as organizer-layout]
   [cfp-scheduler-killer.views.review :as review]
   [cfp-scheduler-killer.views.shell :as shell]
   [clojure.string :as str]))

(defn api-docs-page
  "The API reference — PUBLIC, and served from under /api/v1 so it inherits the
   same reachability as the endpoints it documents. A reference an integrator
   has to log in to read is a reference nobody reads.

   Generated from `exports/api-endpoints`, so a new endpoint cannot ship
   undocumented: the table IS the docs, and the service index at /api/v1/ is
   rendered from the same vector.

   The framing is deliberate. This page is written for the person Gene was for
   fifteen years — someone about to write a scraper — and its first job is to
   tell them they do not need one."
  [host event]
  (let [base (str host)
        slug (:slug event)
        api (str base "/api/v1/events/" slug)
        curl (fn [path & [{:keys [token query]}]]
               (str "curl -s "
                    (when token "-H \"Authorization: Bearer $TOKEN\" ")
                    "'" base (str/replace path "{slug}" slug) (or query "") "'"))
        ;; The endpoint table is shared with the JSON service index, where
        ;; markdown backticks are the right way to mark a field name. Here they
        ;; become <code> rather than showing up as literal grave accents.
        prose (fn [s]
                (map-indexed (fn [i part]
                               (if (odd? i) [:code {} part] part))
                             (str/split (str s) #"`")))]
    (shell/page-shell
      (str (events/display-name event) " — API reference")
      [:div.ui.container {:style "max-width:860px; margin-top:2em;"}
       [:div.cfp-masthead
        [:h1.ui.header (events/display-name event)]
        [:div.cfp-meta "API reference · v1"]]

       [:div.ui.segment
        [:h3.ui.header "You do not need a scraper"]
        [:p "Everything on the public program is available as JSON, ICS and markdown, "
         "with no key and no account. A key widens what you can read — the submissions "
         "that are not public yet, the statuses, the change feed."]
        [:p [:strong "Every entity carries a stable id, and every join carries the id on "
             "both sides."] " A session lists " [:code "speakerIds"] "; a speaker lists "
         [:code "sessionIds"] ". Person ids are stable across events, so the same human at "
         "two conferences is the same id. Never match on a name — that is how one speaker "
         "becomes three."]
        [:dl.facts
         [:dt "Base URL"] [:dd [:div.cfp-url api]]
         [:dt "Start here"]
         [:dd [:a.cfp-url {:href api} api]
          [:div.field-hint "The discovery document: ids, dates, timezone, CFP state, "
           "and a link to every endpoint below."]]
         [:dt "Service index"]
         [:dd [:a.cfp-url {:href (str base "/api/v1/")} (str base "/api/v1/")]]]]

       [:div.ui.segment
        [:h3.ui.header "Authentication"]
        [:p "A key goes in a header, or in " [:code "?token="] " when a header is "
         "inconvenient:"]
        [:pre.cfp-url (curl "/api/v1/events/{slug}/submissions" {:token true})]
        [:pre.cfp-url (curl "/api/v1/events/{slug}/submissions" {:query "?token=$TOKEN"})]
        [:p.field-hint
         [:strong "A token widens; it never unlocks."] " Answers the event's form marks "
         "private — notes to the committee — are absent from every response, "
         "authenticated or not. They were promised to the committee, and a key does not "
         "change who promised."]
        [:p.field-hint "Organizers mint and revoke named keys on the event's Settings "
         "page. Revoking one key never disturbs another."]]

       [:div.ui.segment
        [:h3.ui.header "What is public"]
        [:p "Public responses describe the " [:strong "published program"] ": a session "
         "appears once it is accepted " [:em "and"] " its speaker has been informed — and "
         "not while it sits in a known scheduling conflict. Nothing is published before "
         "the speaker knows."]
        [:p "A session that has no room or time yet is still published, with "
         [:code "placed: false"] " and null times. That is honest; an invented time would "
         "propagate into someone else's site."]]

       [:div.ui.segment
        [:h3.ui.header "Endpoints"]
        (for [{:keys [method path auth summary notes params]} exports/api-endpoints]
          [:div.api-endpoint {}
           [:h4.ui.header
            [:span.cfp-url method " " (str/replace path "{slug}" slug)]
            (when (= :token auth) [:span.field-hint " · token required"])]
           [:p (prose summary)]
           (when notes [:p.field-hint (prose notes)])
           (when (seq params)
             [:dl.facts
              (for [[p desc] params]
                (list [:dt {} [:code p]]
                      [:dd {} [:span.field-hint (prose desc)]]))])
           [:pre.cfp-url (curl path (when (= :token auth) {:token true}))]])]

       [:div.ui.segment
        [:h3.ui.header "Polling without being rude"]
        [:p "Every response carries an " [:code "ETag"] ". Send it back and an unchanged "
         "program answers " [:code "304"] " with no body:"]
        [:pre.cfp-url (str "curl -s -H 'If-None-Match: \"$ETAG\"' -o /dev/null -w '%{http_code}\\n' '"
                           api "/sessions'")]
        [:p "Or read " [:code "scheduleVersion"] " — it bumps on any change to the event — "
         "and ask the change feed what moved:"]
        [:pre.cfp-url (curl "/api/v1/events/{slug}/changes" {:token true :query "?since=0"})]
        [:p.field-hint "The change feed returns ids only — what changed and when, never "
         "the contents. Re-read the entity it names."]]

       [:div.ui.segment
        [:h3.ui.header "Push, instead of polling"]
        [:p "Webhooks deliver the same facts as they happen. An organizer registers a URL "
         "on the event's Settings page and picks which event types to receive; deliveries "
         "and their responses are visible there too."]]

       [:div.field-hint {:style "margin-top:2.5em;"}
        [:a {:href (str base "/agenda/" slug)} "Public agenda"] " · "
        [:a {:href (str base "/events/" slug "/llms.txt")} "llms.txt"] " · "
        [:a {:href (str base "/cfp/" slug)} "Call for speakers"]]])))

(defn exports-page
  "Exports & API — the one page an integrator or a judge needs to FIND.

   These four files and the REST base already existed, addressable and public,
   but only Settings mentioned them and only halfway down. A capability nobody
   can find is a capability you don't have, so it gets a nav item and a page of
   its own. Nothing here is new machinery: every link is a URL that already
   worked."
  [host event {:keys [person]}]
  (let [base (str host)
        slug (:slug event)
        export (fn [f] (str base "/events/" slug "/exports/" f))]
    (organizer-layout/organizer-shell
      (str "Exports & API — " (:name event))
      {:event event :active :exports :person person :crumb "Exports & API"}
      (organizer-layout/header "Exports & API"
                               "Public URLs, no authentication, shaped like ai.engineer's own.")

      [:div.ui.segment
       [:p.field-hint
        "Every file below describes the " [:strong "published program only"] " — "
        "a session appears once it is accepted AND its speaker has been informed. "
        "Nothing is published before the speaker knows."]
       [:dl.facts
        [:dt "sessions.json"]
        [:dd [:a.cfp-url {:href (export "sessions.json") :target "_blank" :rel "noopener"}
              (export "sessions.json")]
         [:div.field-hint "Every published session: title, abstract, format, room and time."]]
        [:dt "speakers.json"]
        [:dd [:a.cfp-url {:href (export "speakers.json") :target "_blank" :rel "noopener"}
              (export "speakers.json")]
         [:div.field-hint "Every published speaker: name, org, title, bio, links."]]
        [:dt "calendar.ics"]
        [:dd [:a.cfp-url {:href (export "calendar.ics") :target "_blank" :rel "noopener"}
              (export "calendar.ics")]
         [:div.field-hint "Subscribable calendar with STABLE UIDs — a room assigned "
          "late amends the invite instead of duplicating it."]]
        [:dt "llms.txt"]
        [:dd [:a.cfp-url {:href (str base "/events/" slug "/llms.txt")
                          :target "_blank" :rel "noopener"}
              (str base "/events/" slug "/llms.txt")]
         [:div.field-hint "The whole program as plain text, for an agent to read."]]]]

      [:div.ui.segment
       [:h4.ui.header "Review results"]
       [:p.field-hint
        "Organizer-only review data, with one record per submission and the event's presenter-visibility policy applied."]
       [:dl.facts
        [:dt "review-results.csv"]
        [:dd [:a.cfp-url
              {:href (export "review-results.csv")
               :download (str slug "-review-results.csv")}
              (export "review-results.csv")]
         [:div.field-hint "Ratings and comments are encoded as JSON cells in each submission row."]]
        [:dt "review-results.json"]
        [:dd [:a.cfp-url
              {:href (export "review-results.json")
               :download (str slug "-review-results.json")}
              (export "review-results.json")]
         [:div.field-hint "Nested ratings and comments for each submission."]]]]

      [:div.ui.segment
       [:h4.ui.header "REST API"]
       [:p.field-hint
        "Same data plus unpublished rows and per-submission detail, with a key. "
        [:strong "Private fields are never returned"] " — notes to the committee "
        "stay with the committee, key or not."]
       [:dl.facts
        [:dt "Base URL"]
        [:dd [:div.cfp-url (str base "/api/v1/events/" slug)]]
        [:dt "Reference"]
        [:dd [:a.cfp-url {:href (str base "/api/v1/events/" slug "/docs")
                          :target "_blank" :rel "noopener"}
              (str base "/api/v1/events/" slug "/docs")]
         [:div.field-hint "Every endpoint, with a curl line for each. "
          "Public — you can send that link to an integrator."]]
        [:dt "Endpoints"]
        [:dd (for [{:keys [method path]} exports/api-endpoints
                   :when (str/starts-with? path "/api/v1")]
               [:div.cfp-url {}
                method " " (str/replace path "{slug}" slug)])]]
       [:div.field-hint
        "Keys are minted and revoked on "
        [:a {:href (str "/events/" slug "/settings")} "Settings → API keys"] "."]])))

(defn mask-webhook-url
  "A Slack webhook URL is a CREDENTIAL — anyone holding it can post to the
   channel — so the page proves which one is configured without handing it back
   out. Host and the first path segment stay (enough to recognise it); the two
   secret segments are dots."
  [url]
  (let [s (str url)]
    (if-let [[_ head] (re-find #"^(https?://[^/]+/services/)" s)]
      (let [parts (str/split (subs s (count head)) #"/")]
        (str head (first parts) "/••••••/••••••••••"))
      (if (> (count s) 24) (str (subs s 0 24) "…") s))))

(defn- slack-form
  "Paste-a-URL + tick-the-moments. One form, one Save — the same shape as every
   other integration on this page."
  [event slack-groups current]
  (let [chosen (if current
                 (set (map str (or (:groups current) [])))
                 (set (map :key (filter :default? slack-groups))))]
    [:form.ui.form.add-member-form
     {:method "post" :action (str "/api/events/" (:slug event) "/slack/set")}
     [:div.field
      [:label "Incoming webhook URL"]
      [:input {:type "url" :name "webhook-url" :required true
               :placeholder "https://hooks.slack.com/services/T00000000/B00000000/XXXXXXXX"}]]
     [:div.field
      [:label "Post to Slack when…"]
      (for [g slack-groups]
        [:div.field {:style "margin:0.25em 0 0.25em 0;"}
         [:label {:style "font-weight:400; cursor:pointer;"}
          [:input (cond-> {:type "checkbox" :name "groups" :value (:key g)
                           :style "margin-right:0.5em;"}
                    (chosen (:key g)) (assoc :checked true))]
          [:strong (:label g)]
          [:span.field-hint {:style "margin:0 0 0 0.5em; display:inline;"} (:help g)]]])]
     [:button.ui.small.primary.button {:type "submit"} "Save Slack settings"]]))

(defn blind-review-settings
  "Organizer-facing controls for the event's presenter-visibility policy."
  [event]
  (let [hidden? (boolean (get-in event [:settings :hide-presenter-info]))
        reveal? (boolean (get-in event [:settings :reveal-after-vote]))]
    [:div#blind-review.ui.segment
     [:h4.ui.header "Review process"]
     [:p.field-hint
      "Blind review hides presenter identity from reviewers; reviewer names, "
      "scores, and conversation stay visible to the committee."]
     [:form.ui.form {:method "post"
                     :action (str "/api/events/" (:slug event) "/blind-review")}
      [:input {:type "hidden" :name "return-to" :value "settings"}]
      [:div.field
       [:div.ui.checkbox
        [:input (cond-> {:type "checkbox" :name "hide-presenter-info" :value "1"}
                  hidden? (assoc :checked true))]
        [:label "Blind Review Process — hide presenter identity"]]]
      [:div.field
       [:div.ui.checkbox
        [:input (cond-> {:type "checkbox" :name "reveal-after-vote" :value "1"}
                  reveal? (assoc :checked true))]
        [:label "Reveal the presenter after this reviewer rates"]]
       [:div.field-hint "Only applies while blind review is enabled."]]
      [:button.ui.small.primary.button {:type "submit"} "Save review process"]]]))

(defn settings-page
  "Everything an integrator needs, in one place: the open-data URLs, the API
   token, Slack, and the webhooks. Event details are read-only here for now —
   editing them lives on the create form until there is a reason to duplicate
   it."
  [host event {:keys [person webhooks deliveries notice slack-groups
                      api-keys new-key confirming-key api-key-error]}]
  (let [base (str host)
        export (fn [f] (str base "/events/" (:slug event) "/exports/" f))
        token (get-in event [:settings :api-token])]
    (organizer-layout/organizer-shell
      (str "Settings — " (:name event))
      {:event event :active :settings :person person :crumb "Settings"}
      (review/notice-region event notice)
      (organizer-layout/header "Settings" "Exports, API access, Slack and webhooks.")

      ;; The close DATE lives here, post-create. The create form only ever asks
      ;; the one-sentence version ("…open until"); this is where an organizer
      ;; extends the deadline the week everyone always asks for it, or clears it
      ;; and leaves the call open. Opening and closing outright are the two
      ;; buttons on the dashboard — this is the scheduled half, they are the
      ;; deliberate half.
      [:div.ui.segment
       [:h4.ui.header "Call for speakers"]
       [:dl.facts
        [:dt "Right now"]
        [:dd (case (submissions/cfp-state event)
               :open "Open — accepting submissions"
               :not-open-yet "Not open yet"
               :closed "Closed to new submissions")]]
       [:form.ui.form {:method "post"
                       :action (str "/api/events/" (:slug event) "/cfp/close-date")}
        [:div.field
         [:label "Closes end of day (" (:tz event) ")"]
         [:input {:type "date" :name "cfp-closes-on"
                  :value (or (some-> (:cfp-closes-at event)
                                     (format/fmt-close-date (:tz event)))
                             "")}]
         [:div.field-hint "Leave blank to keep the call open indefinitely. "
          "A date in the past means the call is closed."]]
        [:button.ui.small.button {:type "submit"} "Save close date"]]]

      (blind-review-settings event)

      [:div.ui.segment
       [:h4.ui.header "Open data"]
       [:p.field-hint
        "Public URLs, no authentication. They describe the "
        [:strong "published program only"] " — a session appears once it is "
        "accepted AND its speaker has been informed. Nothing is published before "
        "the speaker knows."]
       [:dl.facts
        [:dt "sessions.json"]
        [:dd [:a.cfp-url {:href (export "sessions.json") :target "_blank"} (export "sessions.json")]]
        [:dt "speakers.json"]
        [:dd [:a.cfp-url {:href (export "speakers.json") :target "_blank"} (export "speakers.json")]]
        [:dt "calendar.ics"]
        [:dd [:a.cfp-url {:href (export "calendar.ics") :target "_blank"} (export "calendar.ics")]]
        [:dt "llms.txt"]
        [:dd [:a.cfp-url {:href (str base "/events/" (:slug event) "/llms.txt") :target "_blank"}
              (str base "/events/" (:slug event) "/llms.txt")]]]
       [:div.field-hint
        "These match the shapes ai.engineer already publishes, so they drop into "
        "an existing pipeline without a migration."]]

      [:div.ui.segment
       [:h4.ui.header "API token"]
       [:p.field-hint
        "Reads the same data plus unpublished rows and per-submission detail. "
        [:strong "Private fields are never returned"] " — notes to the planning "
        "committee stay with the committee, token or not."]
       [:div {:style "display:flex; gap:0.6em; align-items:center;"}
        [:span.cfp-url {:style "background:#f7f7f8; padding:0.6em 0.8em; border-radius:4px;"}
         (exports/key-prefix token)]
        [:button.ui.small.basic.button
         {:type "button"
          :data-copy-api-key (str "/api/events/" (:slug event) "/api-keys/copy")
          :onclick "copyApiKey(this)"}
         "Copy API token"]]
       [:div.field-hint {:style "margin-top:0.6em;"} "Try it:"]
       [:pre {:style "background:#f7f7f8; padding:0.7em 0.9em; border-radius:4px;
                     overflow-x:auto; font-size:0.8em;"}
        (str "curl -H 'Authorization: Bearer $CURTAIN_CALL_API_KEY' \\\n"
             "  " base "/api/v1/events/" (:slug event) "/sessions?status=all")]
       [:dl.facts {:style "margin-top:0.8em;"}
        [:dt "Endpoints"]
        [:dd [:div.cfp-url "GET /api/v1/events/" (:slug event) "/sessions"]
         [:div.cfp-url "GET /api/v1/events/" (:slug event) "/speakers"]
         [:div.cfp-url "GET /api/v1/events/" (:slug event) "/submissions/:id"]]]]

      ;; --- API keys ------------------------------------------------------
      ;;
      ;; The list shows the LAST FOUR, never the material. One shared token meant
      ;; revoking the leaked integration also revoked the chair's curl line, so
      ;; nobody revoked anything; named keys make revocation small enough to
      ;; actually do. Every control here is a plain form POST — the whole page
      ;; re-renders. Copying is an explicit authenticated request, so View Source
      ;; never contains a usable credential.
      [:div.ui.segment
       [:h4.ui.header "API keys"]
       [:p.field-hint
        "Named, scoped keys for this event, each revocable on its own. "
        [:strong "Private fields are never returned"] ", key or not."]

       (when api-key-error
         [:div.ui.negative.message api-key-error])

       (when new-key
         [:div.ui.positive.message {:style "margin-bottom:1em;"}
          [:div.header (str "Copy “" (:label new-key) "” now")]
          [:p {:style "margin:0.4em 0 0.6em 0;"}
           "The full key stays hidden. Copy it into its destination, then revoke "
           "it here whenever that integration is retired."]
          [:div {:style "display:flex; gap:0.6em; align-items:center;"}
           [:span.cfp-url {:style "background:#fff; padding:0.6em 0.8em;
                                  border-radius:4px; font-weight:600;"}
            (exports/key-prefix (:prefix new-key))]
           [:button.ui.small.basic.button
            {:type "button"
             :data-api-key-material (:key new-key)
             :data-api-key-id (:id new-key)
             :onclick "copyApiKey(this)"}
            "Copy API key"]]])

       (if (seq api-keys)
         [:div.member-list
          (for [k api-keys]
            [:div.member-row {}
             [:div.member-who
              [:span.member-name (:label k)]
              [:div.member-email
               [:span.cfp-url (exports/key-prefix (or (:prefix k) (:key k)))]
               (str " · " (if (contains? k :scope)
                            (or (some-> (:scope k) exports/api-key-scope name)
                                "invalid scope")
                            "organizer"))
               (when-let [at (:created-at k)]
                 (str " · added " (or (format/fmt-when at (:tz event)) at)))]]
             [:div {:style "display:flex; gap:0.4em; align-items:center;"}
              (when (:key k)
                [:button.ui.mini.basic.button
                 {:type "button"
                  :data-copy-api-key (str "/api/events/" (:slug event) "/api-keys/copy")
                  :data-api-key-id (:id k)
                  :onclick "copyApiKey(this)"}
                 "Copy"])
              (if (= confirming-key (:id k))
                ;; Two-step confirmation, rendered by the SERVER. No confirm()
                ;; dialog: a modal blocks the SSE stream, and "am I confirming?"
                ;; is state the browser has no business owning.
                [:div {:style "display:flex; gap:0.4em; align-items:center;"}
                 [:span.field-hint {:style "margin:0;"} "Revoke it?"]
                 [:form {:method "post"
                         :action (str "/api/events/" (:slug event) "/api-keys/revoke")}
                  [:input {:type "hidden" :name "id" :value (:id k)}]
                  [:input {:type "hidden" :name "confirm" :value "yes"}]
                  [:button.ui.mini.negative.button {:type "submit"} "Revoke"]]
                 [:a.ui.mini.basic.button {:href (str "/events/" (:slug event) "/settings")}
                  "Keep it"]]
                [:form {:method "post"
                        :action (str "/api/events/" (:slug event) "/api-keys/revoke")}
                 [:input {:type "hidden" :name "id" :value (:id k)}]
                 [:button.ui.mini.basic.button {:type "submit"} "Revoke"]])]])]
         [:p.field-hint "No named keys yet."])

       [:form.ui.form.add-member-form {:method "post"
                                       :action (str "/api/events/" (:slug event) "/api-keys/create")}
        [:div.field
         [:label "Label"]
         [:input {:type "text" :name "label"
                  :placeholder "Zapier · the schedule site · Ann's laptop"}]]
        [:div.field
         [:label "Scope"]
         [:select {:name "scope" :required true}
          (for [scope exports/api-key-scopes]
            [:option {:value (name scope)} (name scope)])]]
        [:button.ui.small.primary.button {:type "submit"} "Create key"]
        [:div.field-hint {:style "margin-top:0.5em;"}
         "Name it after where it will live. That name is what turns “a key "
         "leaked” into “that key leaked”."]]]

      (let [slack (get-in event [:settings :slack])
            chosen (set (or (:groups slack) []))]
        [:div.ui.segment
         [:h4.ui.header "Slack"]
         [:p.field-hint
          "Post to the channel your committee is already in. Paste an "
          [:a {:href "https://api.slack.com/messaging/webhooks" :target "_blank"
               :rel "noopener"} "incoming-webhook URL"]
          " and pick which moments are worth interrupting people for. "
          [:strong "Private answers are never posted"]
          " — a channel is a wider room than the programming committee, so the "
          "notes-to-committee field stays out of it. A failing post is logged and "
          "dropped; it can never fail a speaker's submission."]
         (if (format/not-blank (:webhook-url slack))
           [:div
            [:dl.facts
             [:dt "Webhook"] [:dd [:span.cfp-url (mask-webhook-url (:webhook-url slack))]]
             [:dt "Posts on"]
             [:dd (if (seq (:groups slack))
                    (str/join ", " (for [g slack-groups
                                         :when (chosen (:key g))]
                                     (:label g)))
                    "nothing selected — no messages will be sent")]]
            [:div {:style "display:flex; gap:0.5em; align-items:center;"}
             [:form {:method "post" :action (str "/api/events/" (:slug event) "/slack/test")}
              [:button.ui.small.primary.button {:type "submit"} "Send a test message"]]
             [:form {:method "post" :action (str "/api/events/" (:slug event) "/slack/remove")}
              [:button.ui.mini.basic.button {:type "submit"} "Remove"]]]
            [:div.field-hint {:style "margin-top:0.6em;"}
             "The test posts a real message to the channel, and says here whether "
             "Slack took it."]
            [:details {:style "margin-top:0.9em;"}
             [:summary {:style "cursor:pointer; font-size:0.9em; color:#666;"}
              "Change the URL or what gets posted"]
             (slack-form event slack-groups slack)]]
           (slack-form event slack-groups nil))])

      [:div.ui.segment
       [:h4.ui.header "Webhooks"]
       [:p.field-hint
        "Every stored event can be POSTed as JSON to a URL you control — "
        "Zapier, Make, n8n, or your own endpoint. A failing webhook is logged and "
        "dropped; it can never fail a speaker's submission."]
       (if (seq webhooks)
         [:div.member-list
          (for [w webhooks]
            [:div.member-row {}
             [:div.member-who
              [:span.member-name.cfp-url (:url w)]
              [:div.member-email
               (if (seq (:types w))
                 (str "only: " (str/join ", " (:types w)))
                 "every event type")]]
             [:form {:method "post"
                     :action (str "/api/events/" (:slug event) "/webhooks/remove")}
              [:input {:type "hidden" :name "id" :value (:id w)}]
              [:button.ui.mini.basic.button {:type "submit"} "Remove"]]])]
         [:p.field-hint "No webhooks yet."])

       [:form.ui.form.add-member-form {:method "post"
                                       :action (str "/api/events/" (:slug event) "/webhooks/add")}
        [:div.two.fields
         [:div.field
          [:label "URL"]
          [:input {:type "url" :name "url" :placeholder "https://hooks.example.com/cfp"}]]
         [:div.field
          [:label "Event types " [:span.optional "(optional, comma separated)"]]
          [:input {:type "text" :name "types"
                   :placeholder "submission.created, submission.notified"}]]]
        [:button.ui.small.primary.button {:type "submit"} "Add webhook"]
        [:div.field-hint {:style "margin-top:0.5em;"}
         "Leave types blank to receive everything."]]]

      [:div.ui.segment
       [:h4.ui.header "Airtable"]
       [:p.field-hint
        "Mirror submissions one-way into a base, so automations you already have "
        "keep firing. We never read Airtable back — two systems that both think "
        "they own a record is how you get a data-loss story. "
        [:strong "Private fields are never sent."]]
       (if-let [a (get-in event [:settings :airtable])]
         [:div
          [:dl.facts
           [:dt "Base"] [:dd [:span.cfp-url (:base-id a)]]
           [:dt "Table"] [:dd (:table a)]]
          [:form {:method "post" :action (str "/api/events/" (:slug event) "/airtable/remove")}
           [:button.ui.mini.basic.button {:type "submit"} "Disconnect"]]]
         [:form.ui.form.add-member-form
          {:method "post" :action (str "/api/events/" (:slug event) "/airtable/set")}
          [:div.three.fields
           [:div.field [:label "Base ID"]
            [:input {:type "text" :name "base-id" :placeholder "appXXXXXXXXXXXXXX"}]]
           [:div.field [:label "Table"]
            [:input {:type "text" :name "table" :placeholder "Submissions"}]]
           [:div.field [:label "Personal access token"]
            [:input {:type "password" :name "token" :placeholder "pat…"}]]]
          [:button.ui.small.primary.button {:type "submit"} "Connect Airtable"]])]

      [:div.ui.segment
       [:h4.ui.header "Committee push email"]
       [:p.field-hint
        "When a submission arrives, every committee member gets the "
        [:strong "whole proposal inline"] " — every answer under its label, the "
        "speaker block, and the private notes to the committee. This is BusyConf's "
        "pattern, and it is why their committees actually reviewed: the work came "
        "to the inbox instead of asking for a login."]
       [:div.field-hint
        (if (not (false? (get-in event [:settings :pc-push-enabled])))
          "Enabled for this event."
          "Disabled for this event.")
        " Delivery follows the same SMTP setting as everything else."]]

      [:div.ui.segment
       [:h4.ui.header "Recent deliveries"]
       (if (seq deliveries)
         (for [d (take 20 deliveries)]
           [:div.log-row
            [:div.log-when (or (format/fmt-when (:at d) (:tz event)) (:at d))]
            [:div.log-type (:event-type d)]
            [:div.log-what.cfp-url (:url d)
             (when (:error d) [:div.b-split (:error d)])]
            [:div.log-actor (if (:ok d) "ok" "failed") " · " (:ms d) "ms"]])
         [:div.empty-state "No deliveries yet."])
       [:div.field-hint
        "This list lives in memory and is forgotten on restart — it is a debugging "
        "aid, not a delivery record. A durable one arrives with the comms slice."]]

      (list
        [:div.ui.segment
         [:h4.ui.header "Event details"]
         [:dl.facts
          [:dt "Name"] [:dd (:name event)]
          [:dt "Slug"] [:dd (:slug event)]
          [:dt "Dates"] [:dd (or (format/fmt-date-range (:starts-on event) (:ends-on event)) "not set")]
          [:dt "Location"] [:dd (or (:location event) "not set")]
          [:dt "Time zone"] [:dd (:tz event)]
          [:dt "Speaker support email"] [:dd (or (:support-email event) "not set")]]
         [:div.field-hint
          [:a {:href (str "/events/" (:slug event) "/details")}
           "Edit event details"] " →"]]
        (let [filename (str (:slug event) "-review-scores.csv")]
          [:div.ui.segment
           [:h4.ui.header "Review data export"]
           [:p.field-hint
            "Download current signed Stars, statuses, and reviewers as CSV."]
           [:dl.facts
            [:dt "File"] [:dd [:span.cfp-url filename]]
            [:dt "Rows"] [:dd "One row per reviewer score; unrated submissions are retained"]]
           [:a.ui.primary.button
            {:href (str "/events/" (:slug event) "/board/review-scores.csv")
             :download filename
             :aria-label (str "Download review scores CSV as " filename)}
            "Download review scores CSV"]])))))
