(ns cfp-scheduler-killer.event-setup-views-test
  (:require
   [cfp-scheduler-killer.views.event-setup :as event-setup]
   [clojure.test :refer [deftest is testing]])
  (:import
   (java.time LocalDate)
   (org.jsoup Jsoup)))

(deftest new-event-date-defaults-move-with-today-test
  (testing "a fresh event never inherits the contest's fixed October 2026 dates"
    (let [today (LocalDate/now)
          {:keys [starts-on ends-on]} (event-setup/event-date-defaults today)
          page (str (event-setup/new-event-page "https://example.test"))
          document (Jsoup/parse page)]
      (is (= (str (.plusMonths today 2)) starts-on))
      (is (= (str (.plusDays (.plusMonths today 2) 1)) ends-on))
      (is (= starts-on (.attr (.selectFirst document "input[name=starts-on]") "value")))
      (is (= ends-on (.attr (.selectFirst document "input[name=ends-on]") "value")))))

  (testing "the whole calendar family stays future-dated with a one-day span"
    (doseq [today [(LocalDate/of 2024 2 29)
                   (LocalDate/of 2025 12 31)
                   (LocalDate/of 2026 8 15)
                   (LocalDate/of 2099 1 30)]]
      (let [{:keys [starts-on ends-on]} (event-setup/event-date-defaults today)
            start (LocalDate/parse starts-on)
            end (LocalDate/parse ends-on)]
        (is (.isAfter start today))
        (is (= (.plusMonths today 2) start))
        (is (= (.plusDays start 1) end))))))
