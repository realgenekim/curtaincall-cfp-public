(ns cfp-scheduler-killer.portal-onboarding-anchor-contract-test
  (:require
   [cfp-scheduler-killer.views.portal :as view]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(defn- portal-html [visible-status informed? tasks]
  (view/portal-page
    {:person {:id "person-1" :name "Ada Speaker" :email "ada@example.com"}
     :submissions [{:submission {:id "submission-1"
                                 :answers {:talk-title "Algebraic Programs"}}
                    :event {:id "event-1" :name "Summit"}
                    :visible-status visible-status
                    :informed? informed?
                    :editable? false
                    :tasks tasks
                    :progress {:done 0 :total (count tasks)}}]}))

(deftest every-advertised-onboarding-anchor-has-a-truthful-target-test
  (testing "accepted speakers with no tasks land on an explicit empty state"
    (let [html (portal-html "Accepted" true [])]
      (is (str/includes? html "id=\"onboarding-submission-1\""))
      (is (str/includes? html "0 of 0 done"))
      (is (str/includes? html "No onboarding tasks have been assigned yet."))))
  (testing "pre-confirmation speakers retain the stable anchor without seeing tasks"
    (let [html (portal-html "In review" false [])]
      (is (str/includes? html "id=\"onboarding-submission-1\""))
      (is (str/includes? html
                         "Onboarding tasks will appear here when your participation is confirmed.")))))
