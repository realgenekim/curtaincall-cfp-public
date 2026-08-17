(ns cfp-scheduler-killer.dev-warmup-test
  (:require
   [cfp-scheduler-killer.dev-warmup :as dev-warmup]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each
  (fn [test-fn]
    (reset! dev-warmup/load-state :ready)
    (try
      (test-fn)
      (finally
        (reset! dev-warmup/load-state :ready)))))

(defn- ok-handler [request]
  {:status 200 :body (:uri request)})

(deftest bead-168j-wrap-warmup-state-test
  (let [handler (dev-warmup/wrap-warmup ok-handler)]
    (testing "loading returns a self-refreshing 503 page"
      (reset! dev-warmup/load-state :loading)
      (let [response (handler {:request-method :get :uri "/"})]
        (is (= 503 (:status response)))
        (is (re-find #"Store warming — this page reloads automatically" (:body response)))
        (is (re-find #"<meta http-equiv=\"refresh\" content=\"2\">" (:body response)))))

    (testing "a load failure returns a fail-loud 500 page"
      (reset! dev-warmup/load-state (ex-info "fold exploded" {}))
      (let [response (handler {:request-method :get :uri "/"})]
        (is (= 500 (:status response)))
        (is (re-find #"fold exploded" (:body response)))))

    (testing "ready passes through"
      (reset! dev-warmup/load-state :ready)
      (is (= {:status 200 :body "/events"}
             (handler {:request-method :get :uri "/events"}))))

    (testing "/ping passes through while loading"
      (reset! dev-warmup/load-state :loading)
      (is (= {:status 200 :body "/ping"}
             (handler {:request-method :get :uri "/ping"}))))))

(deftest bead-168j-wrap-warmup-gates-posts-test
  (let [called? (atom false)
        handler (dev-warmup/wrap-warmup
                  (fn [_request]
                    (reset! called? true)
                    {:status 200}))]
    (reset! dev-warmup/load-state :loading)
    (is (= 503 (:status (handler {:request-method :post :uri "/events"}))))
    (is (false? @called?))))

(deftest bead-168j-start-async-load-state-test
  (testing "successful load flips loading to ready"
    (let [release (promise)
          worker (dev-warmup/start-async-load! #(deref release))]
      (is (= :loading @dev-warmup/load-state))
      (deliver release true)
      @worker
      (is (= :ready @dev-warmup/load-state))))

  (testing "failed load retains the Throwable"
    (let [failure (ex-info "load failed" {:bead "168j"})
          worker (dev-warmup/start-async-load! #(throw failure))]
      @worker
      (is (identical? failure @dev-warmup/load-state)))))

;; INTENT-TEST: 168j
(deftest bead-168j-boot-load-decision-test
  (testing "dev without DEMO_MODE loads asynchronously"
    (let [loaded (promise)
          fut (dev-warmup/boot-load! true (constantly false) #(deliver loaded :yes))]
      (is (future? fut))
      (is (= :yes (deref loaded 5000 :timeout)))
      @fut
      (is (= :ready @dev-warmup/load-state))))
  (testing "non-dev loads synchronously when not yet loaded"
    (let [calls (atom 0)]
      (dev-warmup/boot-load! false (constantly false) #(swap! calls inc))
      (is (= 1 @calls))))
  (testing "non-dev skips the load when the seed path already folded"
    (let [calls (atom 0)]
      (dev-warmup/boot-load! false (constantly true) #(swap! calls inc))
      (is (= 0 @calls)))))
