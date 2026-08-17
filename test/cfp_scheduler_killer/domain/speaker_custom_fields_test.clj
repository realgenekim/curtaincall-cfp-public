(ns cfp-scheduler-killer.domain.speaker-custom-fields-test
  (:require
   [cfp-scheduler-killer.domain.speaker-custom-fields :as custom]
   [cfp-scheduler-killer.folds :as folds]
   [clojure.test :refer [deftest is testing]]))

(def base-state
  (assoc folds/empty-state
         :events {"summit" {:id "event-1" :slug "summit"}}
         :people {"person-1" {:id "person-1" :name "Priya" :email "p@example.com"}}
         :speaker-participations
         {["event-1" "person-1"] {:event-id "event-1"
                                  :person-id "person-1"
                                  :status "Confirmed"}}))

(deftest custom-speaker-field-decisions-are-event-scoped
  (let [definition (custom/decide-define
                     base-state
                     {:event-id "event-1"
                      :label "Dietary or accessibility needs"
                      :type "textarea"
                      :required true
                      :actor "organizer@example.com"
                      :at "2026-08-10T12:00:00Z"})
        state-with-field (folds/fold-event base-state (first (:facts definition)))
        field (first (custom/fields-for-event state-with-field "event-1"))]
    (testing "definition is one durable field fact with a stable id"
      (is (= "dietary-or-accessibility-needs" (:id field)))
      (is (= "speaker.custom-field-defined" (get-in definition [:facts 0 :type])))
      (is (:required field)))

    (testing "a speaker can fill the field and the value folds under event + person"
      (let [decision (custom/decide-update-values
                       state-with-field
                       {:event-id "event-1"
                        :person-id "person-1"
                        :values {(:id field) "Vegetarian"}
                        :actor "p@example.com"
                        :at "2026-08-10T12:01:00Z"})
            folded (folds/fold-event state-with-field (first (:facts decision)))]
        (is (= "speaker.custom-values-updated" (get-in decision [:facts 0 :type])))
        (is (= {(:id field) "Vegetarian"}
               (custom/values-for folded "event-1" "person-1")))))

    (testing "required, identity, and tenancy checks fail closed with zero facts"
      (is (= :required-fields
             (get-in (custom/decide-update-values
                       state-with-field
                       {:event-id "event-1" :person-id "person-1"
                        :values {(:id field) ""} :actor "p@example.com"})
                     [:rejected :reason])))
      (is (= :speaker-not-found
             (get-in (custom/decide-update-values
                       state-with-field
                       {:event-id "event-1" :person-id "other-person"
                        :values {(:id field) "Vegetarian"} :actor "x@example.com"})
                     [:rejected :reason])))
      (is (= :unknown-field
             (get-in (custom/decide-update-values
                       state-with-field
                       {:event-id "event-1" :person-id "person-1"
                        :values {"field-from-other-event" "secret"}
                        :actor "p@example.com"})
                     [:rejected :reason]))))))
