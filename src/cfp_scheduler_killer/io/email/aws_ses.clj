(ns cfp-scheduler-killer.io.email.aws-ses
  (:require
   [cfp-scheduler-killer.io.email.smtp :as smtp]))

(defn send! [cfg message]
  (assoc (smtp/send! (merge {:port 587 :tls true} cfg) message)
         :provider :aws-ses))
