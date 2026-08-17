(ns cfp-scheduler-killer.seed-demo-crm-test
  (:require
   [cfp-scheduler-killer.committees :as committees]
   [cfp-scheduler-killer.crm :as crm]
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.seed-demo :as seed-demo]
   [cfp-scheduler-killer.speakers :as speakers]
   [cfp-scheduler-killer.test-helpers :refer [with-temp-store]]
   [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :each with-temp-store)

(deftest seeded-crm-proves-the-directory-to-event-roster-walk
  (let [source (seed-demo/seed!)
        viewer (committees/person-by-email "genek@itrevolution.net")
        directory (crm/directory-for (:id viewer) {:tag "crm-demo"})
        contact (first (:contacts directory))
        detail (crm/detail-for (:id viewer) (:person-id contact))
        target (events/event-by-slug seed-demo/crm-target-slug)]
    (is (= seed-demo/charlotte-slug (:slug source)))
    (is (= "CRM demo outreach candidates"
           (:name (first (:segments directory)))))
    (is (= "Seeded CRM proof: review this speaker, then push them into Austin."
           (:body (first (:notes detail)))))
    (is (= (:person-id contact)
           (:person-id (first (speakers/roster-for-event (:id target))))))))
