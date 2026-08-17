(ns cfp-scheduler-killer.views.manifesto
  "Public manifesto page: the cube vs the table.
   No auth required — this is a brag page Gene posts tonight."
  (:require
   [cfp-scheduler-killer.views.shell :as shell]))

(defn manifesto-page
  "The 'built it for a table' philosophy manifesto. Pure static HTML — no JS,
   no session, no server state. A stranger may read it before signing in."
  []
  (shell/share-page-shell
    "They built software for a queue. We built it for a table. — Curtain Call"
    (shell/social-meta shell/homepage-social-metadata)
    [:div.ui.container {:style "max-width: 760px; margin-top: 3em; margin-bottom: 6em;"}

     ;; --- Kicker / brand ---
     [:div {:style "font-size: 0.85em; color: #888; letter-spacing: 0.06em;
                    text-transform: uppercase; margin-bottom: 1em;"}
      "Curtain Call · manifesto"]

      ;; --- 1. Headline ---
     [:h1 {:style "font-size: 2.4em; line-height: 1.2; margin-bottom: 0.5em;"}
      "They built software for a queue."
      [:br]
      "We built it for a table."]

     [:hr {:style "border: none; border-top: 2px solid #e0e0e0; margin: 2em 0;"}]

     ;; --- 2. THE CUBE ---
     [:section {:style "margin-bottom: 2.5em;"}
      [:h2 {:style "color: #c0392b; font-size: 1.5em; margin-bottom: 0.6em;"}
       "The cube"]
      [:p
       "Every incumbent CFP tool puts a reviewer in solitary confinement. "
       "You log in, the queue serves you a submission, you score it alone, "
       "you never see what anyone else thought. "
       "The queue is tireless, the work is thankless, the numbing is the design. "
       "Committees go delinquent. "
       "Chairs spend their evenings writing nag emails to people who were excited "
       "about reviewing six weeks ago."]
      [:p
       "One of us literally wrote his own app against Sessionize's API just to "
       "escape the queue — to shop for the submissions that actually fascinated him "
       "instead of processing whatever the tool felt like surfacing next."]
      [:blockquote {:style "border-left: 4px solid #c0392b; margin: 1.5em 0;
                            padding: 0.8em 1.2em; color: #555; font-style: italic;"}
       "When your reviewers are writing software to route around your review tool, "
       "the tool is the problem."]]

     ;; --- 3. THE TABLE ---
     [:section {:style "margin-bottom: 2.5em;"}
      [:h2 {:style "color: #27ae60; font-size: 1.5em; margin-bottom: 0.6em;"}
       "The table"]
      [:p
       "Picture a group of people who love the field, gathered around a shared table, "
       "reading submissions together. "
       "They argue. They get dazzled by what people are building out there. "
       "They learn from each other's taste — the person next to you has a completely "
       "different instinct about what makes a great talk, and you need that."]
      [:p
       "Every submission is on the table. "
       "Every score is signed. "
       "Every comment is part of a conversation, not a note dropped into a void."]
      [:p
       "You can't wait to open the next one — because it might be the talk of the year, "
       "and you get to be the person who finds it."]]

     [:hr {:style "border: none; border-top: 2px solid #e0e0e0; margin: 2em 0;"}]

     ;; --- 4. What we built ---
     [:section {:style "margin-bottom: 2.5em;"}
      [:h2 {:style "font-size: 1.4em; margin-bottom: 0.6em;"}
       "What we built"]
      [:ul {:style "line-height: 1.9;"}
       [:li "Every reviewer sees everything — no queue, no assigned pile, no blind spot."]
       [:li "Every rating is signed. Scores have names. You know whose 4.5 that is."]
       [:li "Comments are a conversation. They thread, they accumulate, they're archived."]
       [:li
        "Committees are lenses that focus your view — not walls that confine it. "
        "A committee scopes what you see and how coverage math is measured. "
        "It never locks anyone out."]]]

     ;; --- 5. Configurations are settings, not remodels ---
     [:section {:style "margin-bottom: 2.5em;"}
      [:h2 {:style "font-size: 1.4em; margin-bottom: 0.6em;"}
       "Configurations are settings, not remodels"]
      [:p
       "Blind review? A toggle. "
       "Speaker identity is hidden from you on the proposal until " [:em "you"] " have voted — "
       "then revealed. No ceremony, no re-configuration, one checkbox."]
      [:p
       "Tracks? Committees scope the view and the coverage math without gating who may look. "
       "You can have a committee per track, a committee per day, or one committee for the whole thing. "
       "None of those choices remakes the product."]]

     [:section {:style "margin-bottom: 2.5em;"}
      [:h2 {:style "font-size: 1.4em; margin-bottom: 0.6em;"}
       "What we refuse to build"]
      [:p
       "Formal review rounds. Trusted reviewers do not need Round 1 → Round 2 machinery to read, rate, and discuss submissions. Put every proposal on the shared table and let the committee do its work."]
      [:blockquote {:style "border-left: 4px solid #c0392b; margin: 1.5em 0;
                            padding: 0.8em 1.2em; color: #555; font-style: italic;"}
       "Rich Hickey famously said that no one has ever said, ‘I love filling out forms. "
       "I love filling out lots of fields.’ That is the antithesis of what we want to build."]
      [:p
       "I have reviewed thousands of submissions for my own conferences and many others. "
       "My conclusion is that you never need more than Stars and comments."]
      [:ul {:style "line-height: 1.9;"}
       [:li [:strong "One score, many reasons."]]
       [:li "Stars expresses preference; comments preserve why."]
       [:li
        "No weights, formulas, configurable Numeric/Dropdown/Free-text scorecards, "
        "or workflow-driven review rounds."]
       [:li
        "Committees converge through conversation and progressively narrowing the pool, "
        "not form completion and scorecard arithmetic."]
       [:li "Quick Rate is the canonical review surface."]]]

     ;; --- 6. Box-and-wire rant ---
     [:section {:style "background: #f8f8f8; border-radius: 6px; padding: 1.5em;
                        margin-bottom: 2.5em;"}
      [:h2 {:style "font-size: 1.4em; margin-bottom: 0.6em;"}
       "We're done with box-and-wire tools"]
      [:p
       "We're sick and tired of box-and-wire tools like WooCommerce and Ontraport "
       "that just create nightmares — a thousand dropdowns and flowchart nodes to "
       "express something you could write in a sentence."]
      [:p {:style "font-weight: bold;"}
       "Instead: beautiful configuration-as-data that your LLM can write for you. "
       "Your workflow, your way — without the stupid box-and-wire GUI stuff."]
      [:p
       "Your entire conference process is one readable, testable, forkable config document. "
       "Fork it, version it, hand it to the next chair. "
       "It's not a settings page — it's a spec."]]

     ;; --- 7. Agent-operable ---
     [:section {:style "margin-bottom: 2.5em;"}
      [:h2 {:style "font-size: 1.4em; margin-bottom: 0.6em;"}
       "Agent-operable by design"]
      [:p
       "Point your own LLM at our API — have it help your committee find the reviews "
       "that need eyes, chase coverage gaps, draft your speaker letters."]
      [:p
       "The product exposes the same API the UI uses. "
       "There is no secret backstage. "
       "The API is a first-class citizen, documented, versioned, and open."]]

     [:hr {:style "border: none; border-top: 2px solid #e0e0e0; margin: 2em 0;"}]

     ;; --- 8. Close / spiritual successor + CTA ---
     [:section {:style "margin-bottom: 2em;"}
      [:p {:style "font-size: 1.1em; line-height: 1.8;"}
       "Curtain Call is the spiritual successor to BusyConf — the tool that "
       "conference organizers actually loved, until Heroku repricing ended it in 2023. "
       "We ran our own events on BusyConf for five years. "
       "We know what it felt like to lose it. "
       "This is what we wish we'd had the whole time."]
      [:p {:style "margin-top: 1.5em;"}
       [:a.ui.primary.button {:href "/"}
        "See the live product — create your event →"]]]]))
