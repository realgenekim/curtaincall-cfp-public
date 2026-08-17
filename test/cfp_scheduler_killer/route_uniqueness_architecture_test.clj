(ns cfp-scheduler-killer.route-uniqueness-architecture-test
  (:require
   [cfp-scheduler-killer.server :as server]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def ^:private http-methods
  #{:delete :get :head :options :patch :post :put :trace})

(defn- route-claims [routes]
  (for [[path route-data] routes
        method http-methods
        :when (contains? route-data method)]
    [method path]))

(defn- duplicate-route-claims [routes]
  (->> (route-claims routes)
       frequencies
       (keep (fn [[claim occurrences]]
               (when (> occurrences 1)
                 [claim occurrences])))
       (into (sorted-map))))

(defn- invalid-route-handlers [routes]
  (->> routes
       (mapcat (fn [[path route-data]]
                 (for [method http-methods
                       :when (contains? route-data method)
                       :let [handler (get-in route-data [method :handler])]
                       :when (not (and (var? handler) (bound? handler)))]
                   [method path handler])))
       vec))

(defn- ambiguous-route-shapes [routes]
  (->> (route-claims routes)
       (group-by (fn [[method path]]
                   [method (str/replace path #":[^/]+" ":_")]))
       (keep (fn [[shape claims]]
               (let [paths (set (map second claims))]
                 (when (> (count paths) 1)
                   [shape paths]))))
       (into (sorted-map))))

(deftest every-http-method-and-path-has-one-owner-test
  (testing "the fully composed production route table has no shadowed endpoints"
    (let [routes (server/make-routes)
          claims (route-claims routes)
          duplicates (duplicate-route-claims routes)]
      (is (seq claims) "the composition root must expose HTTP routes")
      (is (empty? duplicates)
          (str "each HTTP method/path pair must have exactly one owner; "
               "duplicate routes can silently shadow a handler: "
               (pr-str duplicates))))))

(deftest every-http-route-claim-has-a-bound-handler-test
  (testing "no declared HTTP endpoint is invisible to handler discovery"
    (let [invalid-handlers (invalid-route-handlers (server/make-routes))]
      (is (empty? invalid-handlers)
          (str "each HTTP method/path claim must point at one bound handler Var: "
               (pr-str invalid-handlers))))))

(deftest parameter-names-cannot-hide-ambiguous-route-shapes-test
  (testing "equivalent parameterized paths cannot shadow one another"
    (let [ambiguities (ambiguous-route-shapes (server/make-routes))]
      (is (empty? ambiguities)
          (str "HTTP paths differing only by parameter name match the same requests: "
               (pr-str ambiguities))))))
