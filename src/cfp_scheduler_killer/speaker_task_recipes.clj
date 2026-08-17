(ns cfp-scheduler-killer.speaker-task-recipes
  "Speaker onboarding recipes as immutable data.

   A form task owns the same vector of field definitions as a CFP form. The
   recipe is copied into task.installed facts when an acceptance is informed,
   so later recipe edits cannot change questions already assigned to speakers.")

(def default-recipe
  {:id "speaker-onboarding-v1"
   :label "Accepted speaker onboarding"
   :tasks
   [{:key "confirm-bio" :type "check"
     :required? true
     :due-offset-days -30
     :label "Confirm your bio and tagline are how you want them printed"}
    {:key "headshot" :type "url"
     :required? true
     :due-offset-days -30
     :file-kind "Headshot"
     :label "Upload your headshot (PNG or JPEG, 300×300 or larger)"}
    {:key "slides-url" :type "url"
     :required? true
     :due-offset-days -21
     :file-kind "Presentation"
     :label "Upload your slides (PDF, PowerPoint, or Keynote)"}
    {:key "hotel-stay" :type "form"
     :required? true
     :due-offset-days -45
     :label "Hotel stay requirements"
     :instructions "Tell the event team whether to reserve a room and what they should know."
     :fields
     [{:id :hotel-needed
       :type :select
       :label "Do you need a hotel room?"
       :required true
       :options ["Yes" "No"]}
      {:id :hotel-notes
       :type :textarea
       :label "Hotel accessibility or arrival notes"
       :help "Optional. Include arrival timing or room-access needs."
       :required false}]}
    {:key "flight-reimbursement" :type "form"
     :required? true
     :due-offset-days -45
     :label "Flight reimbursement details"
     :instructions "Share the travel details the event team needs before booking."
     :fields
     [{:id :flight-needed
       :type :select
       :label "Will you request flight reimbursement?"
       :required true
       :options ["Yes" "No"]}
      {:id :departure-city
       :type :text
       :label "Departure city"
       :help "Optional until your itinerary is known."
       :required false}]}]})

(defn recipe-for-event
  "The current recipe, with a compatibility wrapper for events created before
   named recipes existed."
  [event]
  (or (get-in event [:settings :speaker-onboarding-recipe])
      {:id "legacy-default-speaker-tasks"
       :label "Speaker onboarding"
       :tasks (get-in event [:settings :default-speaker-tasks])}))
