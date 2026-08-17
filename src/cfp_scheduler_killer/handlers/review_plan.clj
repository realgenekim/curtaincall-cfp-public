(ns cfp-scheduler-killer.handlers.review-plan
  "Named Ring adapters for presenter-visibility policy."
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.notices :as notices]
   [cfp-scheduler-killer.review-plan :as review-plan]
   [cfp-scheduler-killer.web.event :as web-event]
   [cfp-scheduler-killer.web.http :as web-http]
   [clojure.string :as str]))

(defn- with-review-plan-event [req f]
  (web-event/with-event
    req
    (fn [event]
      (let [person (auth/current-person req)
            location (str "/events/" (:slug event)
                          "/committee#presenter-visibility")]
        (if-not person
          {:status 302 :headers {"Location" "/login"} :body ""}
          (try
            (f event person)
            (notices/clear-notice! (:id event) (:id person))
            (web-http/see-other location)
            (catch clojure.lang.ExceptionInfo e
              (let [data (ex-data e)]
                (notices/set-notice! (:id event) (:id person)
                                     {:kind :error
                                      :message (or (:user-message data) (.getMessage e))})
                (web-http/see-other location)))))))))

(defn handle-presenter-visibility [req]
  (with-review-plan-event
    req
    (fn [event person]
      (let [expected-version (try
                               (Long/parseLong (str (get-in req [:params :expected-version])))
                               (catch Exception _ nil))]
        (review-plan/set-presenter-visibility!
          (:id event)
          (get-in req [:params :mode])
          expected-version
          (:id person)
          (:email person))))))
