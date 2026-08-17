(ns cfp-scheduler-killer.sse-test
  (:require
   [cfp-scheduler-killer.sse :as sse]
   [clojure.data.json :as json]
   [clojure.test :refer [deftest is testing]]
   [starfederation.datastar.clojure.api :as d*]))

(deftest heartbeat-is-transport-state-not-a-dom-patch-test
  (sse/await-pushes!)
  (reset! sse/subscribers
          {::board {:event-id "event-1" :person-id "person-1"}
           ::public-cfp {:event-id "event-1" :person-id "person-2"}})
  (let [signals (atom [])
        elements (atom [])]
    (try
      (with-redefs [d*/patch-signals! (fn [sub payload]
                                        (swap! signals conj [sub payload])
                                        true)
                    d*/patch-elements! (fn [& args]
                                         (swap! elements conj args)
                                         true)]
        (sse/send-heartbeat!)
        (sse/await-pushes!))
      (testing "every open stream receives the keepalive"
        (is (= #{::board ::public-cfp} (set (map first @signals))))
        (is (every? #(integer? (:sseHeartbeat (json/read-str (second %) :key-fn keyword)))
                    @signals)))
      (testing "the keepalive never asks the browser to find a DOM target"
        (is (empty? @elements)))
      (finally
        (reset! sse/subscribers {})))))

(deftest queued-personal-push-keeps-its-dispatch-time-audience-test
  (let [blocker (Object.)
        present-at-dispatch (Object.)
        connected-later (Object.)
        agent-blocked (promise)
        release-agent (promise)
        deliveries (atom [])]
    (reset! sse/subscribers {})
    (with-redefs [d*/patch-elements!
                  (fn [channel html opts]
                    (if (= blocker channel)
                      (do
                        (deliver agent-blocked true)
                        @release-agent
                        true)
                      (if (nil? html)
                        (throw (ex-info "A queued patch reached a later subscriber"
                                        {:channel channel :opts opts}))
                        (do
                          (swap! deliveries conj
                                 [channel html (get opts d*/selector)])
                          true))))]
      (try
        ;; Occupy the single push agent so dispatch and delivery become two
        ;; deterministic phases; no scheduler luck or sleep is involved.
        (sse/add-subscriber! blocker "barrier-event" "barrier-person")
        (sse/push-fragment! "barrier-event" "#barrier"
                            (fn [] [:div#barrier]))
        @agent-blocked
        (sse/remove-subscriber! blocker)

        (sse/add-subscriber! present-at-dispatch "event-a" "person-a")
        (sse/push-personal-fragment!
         "event-a" "#board-row"
         (fn [person-id] [:div#board-row person-id]))
        ;; This stream opened after the mutation was queued. It has not yet
        ;; received its page/targets and must not receive the older patch.
        (sse/add-subscriber! connected-later "event-a" "person-b")

        (deliver release-agent true)
        (sse/await-pushes!)
        (is (= 1 (count @deliveries)))
        (is (= present-at-dispatch (ffirst @deliveries)))
        (is (= "<div id=\"board-row\">person-a</div>"
               (second (first @deliveries))))
        (is (= 2 (sse/subscriber-count "event-a"))
            "a later healthy stream is neither patched early nor reaped as dead")
        (finally
          (deliver release-agent true)
          (sse/await-pushes!)
          (reset! sse/subscribers {}))))))

(deftest exhaustive-registration-interleavings-preserve-push-audience-test
  ;; This is an exhaustive property over every element/signal push API and the
  ;; 4×4 registration transition matrix at the dispatch/delivery boundary:
  ;; absent, target person A, target person B, or another event. It exercises
  ;; connect, disconnect, event move, and identity replacement without
  ;; depending on randomized scheduler luck.
  (let [states [:absent :person-a :person-b :other-event]
        kinds [:event-elements :personal-elements :person-elements :person-signals]
        scenarios
        (mapv (fn [index [kind initial-state final-state]]
                (let [event-id (str "event-" index)]
                  {:channel (Object.)
                   :kind kind
                   :event-id event-id
                   :selector (str "#case-" index)
                   :initial (case initial-state
                              :absent nil
                              :person-a {:event-id event-id :person-id "person-a"}
                              :person-b {:event-id event-id :person-id "person-b"}
                              :other-event {:event-id (str event-id "-other")
                                            :person-id "person-a"})
                   :final (case final-state
                            :absent nil
                            :person-a {:event-id event-id :person-id "person-a"}
                            :person-b {:event-id event-id :person-id "person-b"}
                            :other-event {:event-id (str event-id "-other")
                                          :person-id "person-a"})}))
              (range)
              (for [kind kinds initial states final states]
                [kind initial final]))
        blocker (Object.)
        agent-blocked (promise)
        release-agent (promise)
        deliveries (atom [])]
    (reset! sse/subscribers {})
    (with-redefs [d*/patch-elements!
                  (fn [channel html opts]
                    (if (= blocker channel)
                      (do
                        (deliver agent-blocked true)
                        @release-agent
                        true)
                      (if (nil? html)
                        (throw (ex-info "No render exists for this registration"
                                        {:channel channel :opts opts}))
                        (do
                          (swap! deliveries conj [channel (get opts d*/selector)])
                          true))))
                  d*/patch-signals!
                  (fn [channel payload]
                    (swap! deliveries conj
                           [channel (get (json/read-str payload) "caseSelector")])
                    true)]
      (try
        (sse/add-subscriber! blocker "barrier-event" "barrier-person")
        (sse/push-fragment! "barrier-event" "#barrier"
                            (fn [] [:div#barrier]))
        @agent-blocked
        (sse/remove-subscriber! blocker)

        (doseq [{:keys [channel kind event-id selector initial final]} scenarios]
          (when initial
            (sse/add-subscriber! channel (:event-id initial) (:person-id initial)))
          (case kind
            :event-elements
            (sse/push-fragment! event-id selector
                                (fn [] [:div {:id (subs selector 1)}]))

            :personal-elements
            (sse/push-personal-fragment!
              event-id selector
              (fn [person-id] [:div {:id (subs selector 1)} person-id]))

            :person-elements
            (sse/push-to-person! event-id "person-a" selector
                                 (fn [] [:div {:id (subs selector 1)}]))

            :person-signals
            (sse/push-signals-to-person!
              event-id "person-a" {:caseSelector selector}))
          (if final
            (sse/add-subscriber! channel (:event-id final) (:person-id final))
            (sse/remove-subscriber! channel)))

        (deliver release-agent true)
        (sse/await-pushes!)
        (let [expected-deliveries
              (set (for [{:keys [channel kind event-id selector initial final]} scenarios
                         :when (and (= initial final)
                                    (= event-id (:event-id initial))
                                    (or (not (#{:person-elements :person-signals} kind))
                                        (= "person-a" (:person-id initial))))]
                     [channel selector]))
              expected-registrations
              (into {} (keep (fn [{:keys [channel final]}]
                               (when final [channel final]))) scenarios)]
          (is (= expected-deliveries (set @deliveries))
              "only unchanged dispatch-time targets receive each queued patch")
          (is (= expected-registrations @sse/subscribers)
              "later healthy registrations survive every ordering"))
        (finally
          (deliver release-agent true)
          (sse/await-pushes!)
          (reset! sse/subscribers {}))))))
