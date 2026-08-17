(ns cfp-scheduler-killer.domain.crm-test
  (:require
   [cfp-scheduler-killer.domain.crm :as crm]
   [cfp-scheduler-killer.folds :as folds]
   [clojure.test :refer [deftest is testing]]))

(def base-state
  (merge
    folds/empty-state
    {:events {"one" {:id "e1" :slug "one" :name "Event One" :starts-on "2026-01-01"}
              "two" {:id "e2" :slug "two" :name "Event Two" :starts-on "2027-01-01"}
              "foreign" {:id "e3" :slug "foreign" :name "Foreign Event" :starts-on "2028-01-01"}}
     :committees {"c1" {:id "c1" :event-id "e1"}
                  "c2" {:id "c2" :event-id "e2"}
                  "c3" {:id "c3" :event-id "e3"}}
     :people {"viewer" {:id "viewer" :name "Organizer" :email "organizer@example.com"}
              "repeat" {:id "repeat" :name "Repeat Speaker" :email "repeat@example.com"}
              "reviewer" {:id "reviewer" :name "Reviewer" :email "reviewer@example.com"}
              "foreign" {:id "foreign" :name "Foreign Person" :email "foreign@example.com"}}
     :memberships {"m1" {:id "m1" :committee-id "c1" :person-id "viewer" :role "chair"}
                   "m2" {:id "m2" :committee-id "c2" :person-id "viewer" :role "chair"}
                   "m3" {:id "m3" :committee-id "c2" :person-id "repeat" :role "reviewer"}
                   "m4" {:id "m4" :committee-id "c1" :person-id "reviewer" :role "reviewer"}
                   "m5" {:id "m5" :committee-id "c3" :person-id "foreign" :role "chair"}}
     :speaker-participations {["e1" "repeat"]
                              {:event-id "e1" :person-id "repeat" :status "Confirmed"
                               :organization "Acme"}
                              ["e3" "foreign"]
                              {:event-id "e3" :person-id "foreign" :status "Confirmed"
                               :organization "Other Org"}}
     :log [{:type "speaker.added-to-event" :event-id "e1"
            :actor "organizer@example.com" :at "2026-01-02T00:00:00Z"
            :payload {:event-id "e1" :person-id "repeat"}}
           {:type "speaker.added-to-event" :event-id "e3"
            :actor "foreign@example.com" :at "2026-01-03T00:00:00Z"
            :payload {:event-id "e3" :person-id "foreign"}}]}))

(deftest cross-event-directory-is-tenant-scoped
  (let [directory (crm/directory base-state "viewer" {})
        repeat-contact (some #(when (= "repeat" (:person-id %)) %) (:contacts directory))]
    (is (= ["e1" "e2"] (mapv :id (:events directory))))
    (is (= #{"viewer" "repeat" "reviewer"}
           (set (map :person-id (:contacts directory)))))
    (is (not-any? #(= "foreign" (:person-id %)) (:contacts directory)))
    (is (= 2 (:event-count repeat-contact)))
    (is (:repeat? repeat-contact))
    (is (= ["reviewer" "speaker"] (:roles repeat-contact)))
    (is (= ["Acme"] (:organizations repeat-contact)))
    (is (= 1 (count (get-in directory [:pipeline :confirmed]))))
    (is (= 2 (count (get-in directory [:pipeline :relationship]))))
    (is (= {:contacts 3 :repeat-contacts 2 :organizations 1 :events 2}
           (:stats directory)))))

(deftest directory-search-and-attribute-filters-compose
  (testing "search spans identity, organization, role, and event"
    (is (= ["repeat"]
           (mapv :person-id (:contacts (crm/directory base-state "viewer" {:q "acme"})))))
    (is (= #{"repeat" "reviewer"}
           (set (map :person-id
                     (:contacts (crm/directory base-state "viewer" {:role "reviewer"}))))))
    (is (= #{"viewer" "repeat"}
           (set (map :person-id
                     (:contacts (crm/directory base-state "viewer" {:event "e2"}))))))))

(deftest contact-detail-shows-only-authorized-history
  (let [detail (crm/contact-detail base-state "viewer" "repeat")]
    (is (= "repeat@example.com" (get-in detail [:contact :email])))
    (is (= ["speaker.added-to-event"] (mapv :type (:activity detail))))
    (is (= ["Event One"] (mapv :event-name (:activity detail))))
    (is (nil? (crm/contact-detail base-state "viewer" "foreign")))))

(deftest notes-and-tags-are-event-sourced-and-scope-checked
  (let [command {:viewer-id "viewer" :event-id "e1" :person-id "repeat"
                 :actor "organizer@example.com" :at "2026-01-04T00:00:00Z"}
        note (crm/decide-add-note base-state
                                  (assoc command :note-id "n1" :body "  Strong prior speaker.  "))
        tagged (crm/decide-add-tag base-state (assoc command :tag " Enterprise AI "))
        state-with-note (folds/fold-event base-state (first (:facts note)))
        state-with-tag (folds/fold-event state-with-note (first (:facts tagged)))
        duplicate (crm/decide-add-tag state-with-tag (assoc command :tag "enterprise ai"))
        removed (crm/decide-remove-tag state-with-tag (assoc command :tag "enterprise ai"))
        final-state (folds/fold-event state-with-tag (first (:facts removed)))]
    (is (= "Strong prior speaker." (get-in state-with-note [:crm-notes "n1" :body])))
    (is (= "enterprise ai"
           (get-in state-with-tag [:crm-tags ["e1" "repeat" "enterprise ai"] :tag])))
    (is (:unchanged? duplicate))
    (is (empty? (:facts duplicate)))
    (is (nil? (get-in final-state [:crm-tags ["e1" "repeat" "enterprise ai"]])))
    (is (= :forbidden
           (get-in (crm/decide-add-note base-state
                                        (assoc command :event-id "e3" :note-id "n2" :body "No"))
                   [:rejected :reason])))))

(deftest saved-segments-and-outreach-are-pure-reviewed-decisions
  (let [common {:viewer-id "viewer" :event-id "e1"
                :actor "organizer@example.com" :at "2026-01-05T00:00:00Z"}
        saved (crm/decide-save-segment
                base-state
                (assoc common :segment-id "s1" :name "Acme speakers"
                       :filters {:q "  acme " :role "speaker" :tag ""}))
        state-with-segment (folds/fold-event base-state (first (:facts saved)))
        prepared (crm/prepare-outreach
                   state-with-segment
                   (assoc common :recipient-ids ["repeat"]
                          :subject "Invitation for {name}"
                          :body "Hello {name} at {organization} ({email})"))
        recorded (crm/decide-record-outreach
                   state-with-segment
                   (assoc common :draft-id "d1" :recipient-ids ["repeat"]
                          :subject "Invitation for {name}"
                          :body "Hello {name}"))
        state-with-draft (folds/fold-event state-with-segment (first (:facts recorded)))
        removed (crm/decide-remove-segment
                  state-with-draft
                  {:viewer-id "viewer" :segment-id "s1"
                   :actor "organizer@example.com" :at "2026-01-06T00:00:00Z"})]
    (is (= {:q "acme" :role "speaker"}
           (get-in state-with-segment [:crm-segments "s1" :filters])))
    (is (= ["s1"] (mapv :id (:segments (crm/directory state-with-segment "viewer" {})))))
    (is (= "Invitation for Repeat Speaker" (get-in prepared [:preview 0 :subject])))
    (is (= "Hello Repeat Speaker at Acme (repeat@example.com)"
           (get-in prepared [:preview 0 :body])))
    (is (= "crm.outreach-drafted" (:type (first (:facts recorded)))))
    (is (= ["repeat"] (get-in state-with-draft [:crm-outreach-drafts "d1" :recipient-ids])))
    (is (= :forbidden
           (get-in (crm/prepare-outreach
                     base-state
                     (assoc common :event-id "e3" :recipient-ids ["repeat"]
                            :subject "No" :body "No"))
                   [:rejected :reason])))
    (is (empty? (:crm-segments
                  (folds/fold-event state-with-draft (first (:facts removed))))))))
