(ns cfp-scheduler-killer.io-blob-test
  (:require
   [cfp-scheduler-killer.io.blob :as blob]
   [cfp-scheduler-killer.io.blob.local :as local]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]))

(deftest blob-port-is-one-small-recordable-algebra
  (let [calls (atom [])]
    (binding [blob/*put-fn* (fn [source key]
                              (swap! calls conj [:put source key])
                              "recorded://object")
              blob/*read-bytes-fn* (fn [location]
                                     (swap! calls conj [:read location])
                                     (.getBytes "payload" "UTF-8"))
              blob/*copy-fn* (fn [source destination]
                               (swap! calls conj [:copy source destination])
                               {:ok true})]
      (is (= "recorded://object" (blob/put! "source" "event/file")))
      (is (= "payload" (String. ^bytes (blob/read-bytes! "recorded://object") "UTF-8")))
      (is (= {:ok true} (blob/copy! "source" "destination")))
      (is (= [[:put "source" "event/file"]
              [:read "recorded://object"]
              [:copy "source" "destination"]]
             @calls)))))

(deftest local-provider-obeys-the-same-put-read-copy-contract
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                        "cfp-blob-test-"
                        (make-array java.nio.file.attribute.FileAttribute 0)))
        source (io/file root "source.txt")
        copy (io/file root "copies/copy.txt")]
    (spit source "hello")
    (let [location (local/put! root source "event/files/one.txt")]
      (testing "put returns the readable location"
        (is (= "hello" (String. ^bytes (local/read-bytes! location) "UTF-8"))))
      (testing "copy returns the provider-neutral outcome"
        (is (= {:ok true} (local/copy! location copy)))
        (is (= "hello" (slurp copy)))))))
