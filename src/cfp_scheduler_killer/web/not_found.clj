(ns cfp-scheduler-killer.web.not-found
  "Route-aware browser fallback.

   Declared routes are authorized by router middleware. This boundary keeps a
   genuinely unknown GET public enough to say it does not exist, while known
   paths with the wrong method and every unknown write remain default-deny."
  (:require
   [cfp-scheduler-killer.auth :as auth]
   [cfp-scheduler-killer.views.shell :as view-shell]
   [cfp-scheduler-killer.web.http :as http]
   [clojure.string :as str]
   [reitit.core :as reitit]
   [reitit.ring :as ring]))

(def ^:private static-path-prefixes
  ["/css/" "/images/" "/js/" "/vendor/"])

(defn- static-request?
  [{:keys [request-method uri]}]
  (and (#{:get :head} request-method)
       (some #(str/starts-with? uri %) static-path-prefixes)))

(defn handle-not-found
  "The one browser-facing fallback: an inline HTML 404 in the product shell."
  [req]
  (http/html-response
    404
    (view-shell/not-on-the-program-page
      (str "Nothing is published at " (:uri req) "."))))

(defn fallback
  "Serve known static namespaces, then use the route-aware default handler.

   Restricting the resource probe prevents a coincidentally named classpath
   resource from shadowing a dynamic route miss with a download response."
  [router]
  (let [resource-handler (ring/create-resource-handler {:path "/"})
        default-handler (ring/create-default-handler
                          {:not-found #'handle-not-found})
        gated-default (auth/wrap-require-login default-handler)]
    (fn [req]
      (cond
        (static-request? req)
        (or (resource-handler req) (handle-not-found req))

        (and (= :get (:request-method req))
             (nil? (reitit/match-by-path router (:uri req))))
        (handle-not-found req)

        :else
        (gated-default req)))))
