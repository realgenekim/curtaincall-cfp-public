(ns cfp-scheduler-killer.handlers.manifesto
  "Public manifesto page handler."
  (:require
   [cfp-scheduler-killer.views.manifesto :as view-manifesto]
   [cfp-scheduler-killer.web.http :as http]))

(defn handle-manifesto
  "GET /manifesto — the public 'cube vs table' philosophy page.
   No session required; auth/public-prefixes must include \"/manifesto\"."
  [_req]
  (http/html-response (view-manifesto/manifesto-page)))
