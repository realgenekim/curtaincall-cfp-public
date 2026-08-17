(ns cfp-scheduler-killer.speaker-csv-test
  (:require
   [cfp-scheduler-killer.speaker-csv :as csv]
   [clojure.test :refer [deftest is testing]]))

(deftest csv-decoding-preserves-quoted-content
  (is (= [["Name" "Email" "Bio"]
          ["Hickey, Rich" "rich@example.com" "Simple, not easy"]]
         (csv/rows
           "Name,Email,Bio\n\"Hickey, Rich\",rich@example.com,\"Simple, not easy\"\n")))
  (is (= [["Name" "Email" "Notes"]
          ["Ada" "ada@example.com" "She said \"yes\""]]
         (csv/rows
           "Name,Email,Notes\nAda,ada@example.com,\"She said \"\"yes\"\"\"\n"))))

(deftest common-header-aliases-form-one-valid-shape
  (let [parsed (csv/parse
                 (str "First_Name,Last-Name,Email Address,Company,Job Title,Status\n"
                      "Priya,Raghavan,PRIYA@example.com,Acme Bank,VP Engineering,Confirmed\n"))
        row (get-in parsed [:rows 0])]
    (is (:valid? parsed))
    (is (= 1 (:valid-count parsed)))
    (is (= {:email "priya@example.com"
            :organization "Acme Bank"
            :title "VP Engineering"
            :status "Confirmed"
            :name "Priya Raghavan"}
           (:values row)))))

(deftest utf8-bom-does-not-hide-the-first-header
  (let [parsed (csv/parse
                 (str "\uFEFFName,Email\n"
                      "Ada Lovelace,ada@example.com\n"))]
    (is (:valid? parsed))
    (is (= [] (:missing-headers parsed)))
    (is (= {:name "Ada Lovelace" :email "ada@example.com" :status "Invited"}
           (get-in parsed [:rows 0 :values])))))

(deftest row-errors-and-duplicates-are-explicit
  (let [parsed (csv/parse
                 (str "Name,Email,Status\n"
                      "Priya,priya@example.com,Invited\n"
                      "Other Priya,PRIYA@example.com,Confirmed\n"
                      "No Email,,Maybe\n"))]
    (is (false? (:valid? parsed)))
    (is (= 3 (:error-count parsed)))
    (is (= "Duplicate email in this file"
           (get-in parsed [:rows 0 :errors :email])))
    (is (= "Duplicate email in this file"
           (get-in parsed [:rows 1 :errors :email])))
    (is (= #{:email :status}
           (set (keys (get-in parsed [:rows 2 :errors])))))))

(deftest required-headers-are-checked-before-import
  (let [parsed (csv/parse "Company,Status\nAcme,Invited\n")]
    (is (= ["email" "name"] (:missing-headers parsed)))
    (is (false? (:valid? parsed)))))
