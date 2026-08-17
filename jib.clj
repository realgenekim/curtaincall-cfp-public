(ns jib
  "Jib deployment — thin JAR with layer caching.

   Uses thin-jar-build library for Docker-optimized deployments.
   No Docker required — Jib builds and pushes directly.

   Usage:
     clojure -T:build thin         # Build thin JAR first
     clojure -T:jib-deploy jib-deploy

   Prerequisites:
     gcloud auth configure-docker us-west1-docker.pkg.dev"
  (:require [thin-jar.jib :as jib]))

;; Target image — where to push the container
;; Format: REGION-docker.pkg.dev/PROJECT/REPO/IMAGE:TAG
;; Must stay in sync with IMAGE_URL in the Makefile (cloudrundeploy deploys
;; exactly this tag).
(def gcp-project "swyx-cfp-saas-killer")

(def image-name
  (str "us-west1-docker.pkg.dev/" gcp-project
       "/cloud-run-source-deploy/swyx-cfp-saas-killer:latest"))

(defn jib-deploy
  "Build and push container image using thin-jar layers.

   Layer 1: /app/lib/*.jar        - Dependencies (cached, ~30 MB)
   Layer 2: /app/cfp-scheduler-killer.jar - App code (~2-5 MB)

   After first deploy, subsequent deploys only push ~2-5 MB."
  [_]
  (jib/deploy! {:target-image image-name
                :entrypoint ["java" "-cp" "/app/cfp-scheduler-killer.jar:/app/lib/*"
                             "clojure.main" "-m" "cfp-scheduler-killer.core"]
                :layers [{:type :deps
                          :lib-dir "target/lib"
                          :container-path "/app/lib"}
                         {:type :app
                          :jar-path "target/cfp-scheduler-killer.jar"
                          :container-path "/app/cfp-scheduler-killer.jar"}]}))

(comment
  (jib-deploy nil))
