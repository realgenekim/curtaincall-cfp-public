# Jib Containerization for Clojure Projects

## Overview

This guide covers containerizing Clojure applications using [Google Jib](https://github.com/GoogleContainerTools/jib), a daemonless container builder that creates optimized Docker images without requiring Docker installed.

## Why Jib?

- **No Docker daemon required** - Works in CI/CD environments without Docker
- **Reproducible builds** - Same source always produces same image
- **Fast incremental builds** - Caches layers, only rebuilds what changed
- **Optimized layering** - Dependencies and application code in separate layers
- **Direct registry push** - Pushes to container registry without local Docker daemon

## Prerequisites

1. **Google Cloud SDK** installed and authenticated:
   ```bash
   gcloud auth login
   gcloud config set project YOUR_PROJECT_ID
   ```

2. **Container registry access**:
   ```bash
   # Configure Docker credential helper for Artifact Registry
   gcloud auth configure-docker us-west1-docker.pkg.dev

   # Configure for gcr.io (required for pulling base images)
   gcloud auth configure-docker gcr.io
   ```

3. **Artifact Registry repository** created:
   ```bash
   gcloud artifacts repositories create YOUR_REPO_NAME \
     --repository-format=docker \
     --location=us-west1 \
     --description="Docker repository"
   ```

## Project Setup

### 1. Add Jib Dependencies

In your `deps.edn`, add the `:jib-deploy` alias:

```clojure
{:aliases
 {:jib-deploy {:deps {io.github.clojure/tools.build {:git/tag "v0.10.5" :git/sha "2a21b7a"}
                      com.google.cloud.tools/jib-core {:mvn/version "0.25.0"}}
               :ns-default jib}}}
```

### 2. Create `jib.clj`

Create `jib.clj` in your project root:

```clojure
(ns jib
  (:import
   (com.google.cloud.tools.jib.api Jib
                                   RegistryImage
                                   Containerizer
                                   ImageReference)
   (com.google.cloud.tools.jib.api.buildplan AbsoluteUnixPath)
   (com.google.cloud.tools.jib.frontend CredentialRetrieverFactory)
   (java.util.function Consumer)
   (java.nio.file Paths)
   (java.io File)
   (java.util List ArrayList)))

(defn- get-path [filename]
  (Paths/get (.toURI (File. ^String filename))))

(defn- into-list
  [& args]
  (ArrayList. ^List args))

(defn- to-imgref [image-config]
  (ImageReference/parse image-config))

(defn make-logger [verbose]
  (reify Consumer
    (accept [this log-event]
      (when verbose
        (println (.getMessage log-event))))))

(def logger (make-logger true))

;; ⚠️ CONFIGURE THESE FOR YOUR PROJECT
(def image-name "us-west1-docker.pkg.dev/YOUR_PROJECT/YOUR_REPO/YOUR_SERVICE:latest")
(def local-standalone-jar-path "./target/YOUR_SERVICE-standalone.jar")

;; ⚠️ CRITICAL: Use correct registry for base image credentials
;; This is the #1 cause of timeout issues - see troubleshooting below
(def base-image-with-creds
  (-> (RegistryImage/named "gcr.io/distroless/java21")
      (.addCredentialRetriever
       (-> (CredentialRetrieverFactory/forImage
            (to-imgref "gcr.io/distroless/java21")  ; ✅ MUST match base image registry
            logger)
           (.dockerConfig)))))

(def app-layer [(into-list (get-path local-standalone-jar-path))
                (AbsoluteUnixPath/get "/")])

(def entrypoint ["java" "-jar" "/YOUR_SERVICE-standalone.jar"])

(defn jib-deploy [_]
  (println "Building and pushing container image with Jib...")
  (println (str "  Image: " image-name))
  (println (str "  JAR: " local-standalone-jar-path))
  (time (-> (Jib/from base-image-with-creds)
            (.addLayer (first app-layer) (second app-layer))
            (.setEntrypoint (apply into-list entrypoint))
            (.setProgramArguments (into-list local-standalone-jar-path))
            (.containerize
             (Containerizer/to
              (->
               (RegistryImage/named
                (to-imgref image-name))
               (.addCredentialRetriever
                (-> (CredentialRetrieverFactory/forImage
                     (to-imgref image-name)  ; ✅ This is correct - target registry
                     logger)
                    (.dockerConfig))))))))
  (println "✓ Container image built and pushed successfully!")
  (println (str "  Image: " image-name)))
```

### 3. Update `build.clj`

Ensure your `build.clj` includes the `:dev` alias:

```clojure
(ns build
  (:require [clojure.tools.build.api :as b]))

(def basis (b/create-basis {:project "deps.edn" :aliases [:dev]}))  ; ✅ Include :dev alias

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis basis
                  :ns-compile '[your.namespace]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis
           :main 'your.namespace}))
```

### 4. Add Makefile Target

Add to your `Makefile`:

```makefile
# Build and push container image with Jib (with 2-minute timeout)
jib-deploy:
	@echo "🚀 Building and pushing container with Jib..."
	GOOGLE_APPLICATION_CREDENTIALS=$(HOME)/src.local/secrets/YOUR_CREDENTIALS.json timeout 120 time clojure -T:jib-deploy jib-deploy
	@echo "✓ Container pushed to your registry"
```

## Usage

### Build and Deploy

```bash
# 1. Build the uberjar first
make build

# 2. Deploy to container registry
make jib-deploy
```

### Expected Output

**First build (no cache):**
```
Building and pushing container image with Jib...
  Image: us-west1-docker.pkg.dev/project/repo/service:latest
  JAR: ./target/service-standalone.jar
trying docker-credential-gcloud for us-west1-docker.pkg.dev
Using credentials from Docker config (/Users/you/.docker/config.json) for ...
"Elapsed time: 13707.261875 msecs"
✓ Container image built and pushed successfully!
       14.33 real         4.12 user         0.62 sys
```

**Subsequent builds (with cache):**
```
"Elapsed time: 1878.603125 msecs"
        2.51 real         3.29 user         0.32 sys
```

## Common Issues and Troubleshooting

### ❌ Issue #1: Timeout / Hang After "Using credentials from Docker config"

**Symptoms:**
- Build hangs for 18+ minutes after showing "Using credentials from Docker config"
- Eventually fails with: `SocketTimeoutException: Read timed out`

**Root Cause:**
Incorrect credentials configuration for base image in `jib.clj`. The most common mistake:

```clojure
;; ❌ WRONG - Uses target registry credentials for base image
(def base-image-with-creds
  (-> (RegistryImage/named "gcr.io/distroless/java21")
      (.addCredentialRetriever
       (-> (CredentialRetrieverFactory/forImage
            (to-imgref image-name)  ; ❌ BUG! This uses us-west1-docker.pkg.dev creds
            logger)
           (.dockerConfig)))))
```

**Solution:**
```clojure
;; ✅ CORRECT - Uses base image registry credentials
(def base-image-with-creds
  (-> (RegistryImage/named "gcr.io/distroless/java21")
      (.addCredentialRetriever
       (-> (CredentialRetrieverFactory/forImage
            (to-imgref "gcr.io/distroless/java21")  ; ✅ FIX: Match the base image registry
            logger)
           (.dockerConfig)))))
```

**Why This Happens:**
1. Jib needs to pull the base image (`gcr.io/distroless/java21`)
2. With the bug, it tries to use credentials for your target registry (`us-west1-docker.pkg.dev`)
3. Authentication to `gcr.io` fails or times out
4. After ~18 minutes, socket read timeout occurs
5. Build fails

**Why Some Projects Work Despite the Bug:**
- If the base image is already cached locally, Jib skips the pull
- No network access = bug never triggered
- Build succeeds using cached image

**Verification:**
```bash
# Test if you can pull the base image
docker pull gcr.io/distroless/java21

# If this times out, you likely have the credential bug
```

### ❌ Issue #2: VPN Blocking Container Registry Access

**Symptoms:**
- Timeouts when pulling base images
- Works on some networks but not others
- Inconsistent failures

**Solution:**
```bash
# Disconnect from corporate VPN
# OR configure VPN to allow gcr.io access
# OR pre-cache base image when off VPN:
docker pull gcr.io/distroless/java21
```

### ❌ Issue #3: Missing gcr.io Authentication

**Symptoms:**
- Timeout pulling base image
- Error: "unauthorized" or authentication failures

**Solution:**
```bash
# Configure Docker credential helper for gcr.io
gcloud auth configure-docker gcr.io

# Verify authentication
gcloud auth print-access-token | docker login -u oauth2accesstoken --password-stdin https://gcr.io
```

### ❌ Issue #4: JAR File Not Found

**Symptoms:**
```
FileNotFoundException: ./target/service-standalone.jar
```

**Solution:**
```bash
# Build the JAR first
make build

# Verify JAR exists
ls -lh target/*.jar
```

### ❌ Issue #5: Wrong Entrypoint Path

**Symptoms:**
- Container fails to start
- Error: "no such file or directory" when running container

**Root Cause:**
Entrypoint path doesn't match the JAR filename in the container.

**Incorrect Configuration:**
```clojure
(def local-standalone-jar-path "./target/admin-webserver-standalone.jar")
(def entrypoint ["java" "-jar" "/server2-standalone.jar"])  ; ❌ WRONG - filename mismatch
```

**Correct Configuration:**
```clojure
(def local-standalone-jar-path "./target/admin-webserver-standalone.jar")
(def entrypoint ["java" "-jar" "/admin-webserver-standalone.jar"])  ; ✅ Match the filename
```

### ❌ Issue #6: Insufficient Permissions

**Symptoms:**
- Error: "permission denied" when pushing to registry

**Solution:**
```bash
# Grant yourself push permissions
gcloud artifacts repositories add-iam-policy-binding YOUR_REPO_NAME \
  --location=us-west1 \
  --member="user:YOUR_EMAIL@example.com" \
  --role="roles/artifactregistry.writer"
```

### ❌ Issue #7: Build Timeout in Makefile

**Symptoms:**
- Makefile timeout kills the build even though Jib is still working

**Solution:**
Adjust timeout in Makefile based on your network speed:

```makefile
# For slow networks, increase timeout:
jib-deploy:
	GOOGLE_APPLICATION_CREDENTIALS=... timeout 300 time clojure -T:jib-deploy jib-deploy
	#                                          ^^^
	#                                    5 minutes instead of 2
```

## Best Practices

### 1. Always Specify Correct Base Image Credentials

```clojure
;; Pattern to follow:
(def base-image-with-creds
  (-> (RegistryImage/named "REGISTRY/IMAGE:TAG")
      (.addCredentialRetriever
       (-> (CredentialRetrieverFactory/forImage
            (to-imgref "REGISTRY/IMAGE:TAG")  ; ✅ MUST MATCH above
            logger)
           (.dockerConfig)))))
```

### 2. Pre-cache Base Images

For faster builds and reliability:

```bash
# Pre-pull base image (do this once)
docker pull gcr.io/distroless/java21

# Now Jib uses cached image (2-3 second builds!)
```

### 3. Use Specific Image Tags

Instead of `:latest`, use specific versions:

```clojure
;; ❌ Avoid
(def image-name "us-west1-docker.pkg.dev/project/repo/service:latest")

;; ✅ Better - use git SHA or semantic version
(def image-name "us-west1-docker.pkg.dev/project/repo/service:v1.2.3")
```

### 4. Separate Build and Deploy Steps

```makefile
# Build JAR
build:
	clojure -T:build uber

# Push container (requires JAR to exist)
jib-deploy:
	clojure -T:jib-deploy jib-deploy

# Full workflow
deploy-all: build jib-deploy
	@echo "✅ Deployment complete"
```

### 5. Add Logging for Debugging

```clojure
(defn jib-deploy [_]
  (println "Building and pushing container image with Jib...")
  (println (str "  Image: " image-name))
  (println (str "  JAR: " local-standalone-jar-path))
  (println "\n>>> Starting .containerize <<<")
  (flush)  ; ← Important! Force output before potentially long operation
  (time (-> (Jib/from base-image-with-creds)
            ;; ... rest of build ...
            ))
  (println ">>> Containerize completed successfully <<<"))
```

## Performance Characteristics

### Build Times

| Scenario | Time | Description |
|----------|------|-------------|
| First build (no cache) | ~14 seconds | Pulls base image from gcr.io |
| Cached base image | ~2-3 seconds | Only pushes application layers |
| Code changes only | ~2-3 seconds | Reuses dependency layers |
| Dependency changes | ~5-10 seconds | Rebuilds dependency layer |

### Layer Structure

Jib creates optimized layers:

1. **Base layer** - JVM runtime (from distroless image)
2. **Dependency layer** - All JAR dependencies (rarely changes)
3. **Application layer** - Your compiled code (changes frequently)

This means code changes only require rebuilding the small application layer!

## Advanced Configuration

### Custom Base Images

```clojure
;; Use different JVM version
(def base-image-with-creds
  (-> (RegistryImage/named "gcr.io/distroless/java17")
      (.addCredentialRetriever
       (-> (CredentialRetrieverFactory/forImage
            (to-imgref "gcr.io/distroless/java17")
            logger)
           (.dockerConfig)))))

;; Use Alpine-based image
(def base-image-with-creds
  (-> (RegistryImage/named "eclipse-temurin:21-jre-alpine")
      (.addCredentialRetriever
       (-> (CredentialRetrieverFactory/forImage
            (to-imgref "eclipse-temurin:21-jre-alpine")
            logger)
           (.dockerConfig)))))
```

### Environment Variables

```clojure
(defn jib-deploy [_]
  (time (-> (Jib/from base-image-with-creds)
            (.addLayer (first app-layer) (second app-layer))
            (.setEntrypoint (apply into-list entrypoint))
            ;; Add environment variables
            (.addEnvironmentVariable "PORT" "8080")
            (.addEnvironmentVariable "JAVA_TOOL_OPTIONS" "-Xmx512m")
            (.containerize ...))))
```

### Multi-architecture Builds

For ARM64/Apple Silicon support:

```clojure
(defn jib-deploy [_]
  (time (-> (Jib/from base-image-with-creds)
            ;; ... layers and entrypoint ...
            (.containerize
             (-> (Containerizer/to target-registry-image)
                 (.setPlatforms
                  [(Platform. "linux" "amd64")
                   (Platform. "linux" "arm64")]))))))
```

## Verification

After successful deployment:

```bash
# View image in Artifact Registry
gcloud artifacts docker images list \
  us-west1-docker.pkg.dev/YOUR_PROJECT/YOUR_REPO

# Inspect image layers
gcloud artifacts docker images describe \
  us-west1-docker.pkg.dev/YOUR_PROJECT/YOUR_REPO/YOUR_SERVICE:latest

# Pull and run locally
docker pull us-west1-docker.pkg.dev/YOUR_PROJECT/YOUR_REPO/YOUR_SERVICE:latest
docker run -p 8080:8080 us-west1-docker.pkg.dev/YOUR_PROJECT/YOUR_REPO/YOUR_SERVICE:latest
```

## Real-World Case Study

### Problem: 18-Minute Timeout

A project experienced consistent timeouts when running `make jib-deploy`:
- Hung after "Using credentials from Docker config"
- Failed with `SocketTimeoutException: Read timed out` after 18 minutes
- Identical configuration worked in another project

### Investigation

1. **Compared working vs non-working projects** - Both had identical jib.clj structure
2. **Tested network connectivity** - Could access both gcr.io and Artifact Registry
3. **Checked credentials** - Authentication worked for target registry
4. **Discovered the bug** - Line 41 used wrong registry for base image credentials

### Root Cause

```clojure
;; The bug on line 41:
(-> (CredentialRetrieverFactory/forImage
     (to-imgref image-name)  ; ❌ image-name = "us-west1-docker.pkg.dev/..."
     logger)
    (.dockerConfig))
```

When pulling `gcr.io/distroless/java21`, Jib tried to use credentials for `us-west1-docker.pkg.dev`, causing authentication to fail and timeout.

### Solution

```clojure
;; Fixed line 41:
(-> (CredentialRetrieverFactory/forImage
     (to-imgref "gcr.io/distroless/java21")  ; ✅ Match base image registry
     logger)
    (.dockerConfig))
```

### Results

- **First build:** 14.33 seconds (pulled base image successfully)
- **Second build:** 2.51 seconds (used cached base image - 85% faster!)
- Image successfully pushed to Artifact Registry

### Key Lesson

**Why did the other project work with the same bug?**

The working project had successfully built before (when network conditions were different or credentials worked differently). The base image was cached, so Jib never attempted to pull it again. The cached image masked the credential bug.

The failing project had never successfully built, so it always tried to pull the base image, exposing the bug every time.

## References

- [Google Jib Documentation](https://github.com/GoogleContainerTools/jib)
- [Jib Core API](https://github.com/GoogleContainerTools/jib/tree/master/jib-core)
- [Google Cloud Artifact Registry](https://cloud.google.com/artifact-registry/docs)
- [distroless Java Images](https://github.com/GoogleContainerTools/distroless/tree/main/java)
