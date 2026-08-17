# Development Workflow

This guide describes the recommended development workflow for this Clojure project template.

## The Fast Feedback Loop

The core principle: **Test your code within seconds, not minutes.**

### Setup (One-Time)

**Terminal 1: Start nREPL**
```bash
make nrepl
```
This starts an nREPL server for your editor to connect to.

**Terminal 2: Start Test Watcher**
```bash
make runtests
```
Tests automatically re-run whenever you save a file.

**Editor: Connect to nREPL**
- Emacs: `M-x cider-connect`
- VSCode: Calva "Connect to Running REPL"
- IntelliJ: Cursive Remote REPL

### The Development Cycle

```
Write function → Test in REPL → Write test → Save → Tests auto-run → Repeat
     ↓              ↓              ↓           ↓            ↓
   30 sec         5 sec         1 min      instant      instant
```

**Total time from idea to tested code: ~2 minutes**

## REPL-Driven Development (RDD)

### 1. Explore in the REPL First

Before writing production code, experiment in the REPL:

```clojure
;; In src/myapp/core.clj, in a (comment ...) block:

(comment
  ;; Exploring date manipulation
  (require '[clojure.string :as str])

  (def sample-date "2024-01-15")

  ;; Try parsing
  (str/split sample-date #"-")
  ;=> ["2024" "01" "15"]

  ;; Try different approaches
  (map #(Integer/parseInt %) (str/split sample-date #"-"))
  ;=> (2024 1 15)

  ;; Refine into a function
  (defn parse-date [date-str]
    (let [[year month day] (str/split date-str #"-")]
      {:year (Integer/parseInt year)
       :month (Integer/parseInt month)
       :day (Integer/parseInt day)}))

  ;; Test it
  (parse-date sample-date)
  ;=> {:year 2024, :month 1, :day 15}
  )
```

### 2. Move from Comment to Production Code

Once you're happy with the function:

```clojure
(ns myapp.core
  (:require [clojure.string :as str]))

(defn parse-date
  "Parse a date string in YYYY-MM-DD format."
  [date-str]
  (let [[year month day] (str/split date-str #"-")]
    {:year (Integer/parseInt year)
     :month (Integer/parseInt month)
     :day (Integer/parseInt day)}))

(comment
  ;; Keep your experiments for future reference
  (parse-date "2024-01-15")
  ;=> {:year 2024, :month 1, :day 15}

  ;; Try edge cases here before writing tests
  (parse-date "2024-12-31")
  ;=> {:year 2024, :month 12, :day 31}
  )
```

### 3. Write Tests

Now write comprehensive tests in `test/myapp/core_test.clj`:

```clojure
(ns myapp.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [myapp.core :as core]))

(deftest parse-date-test
  (testing "valid date strings"
    (is (= {:year 2024 :month 1 :day 15}
           (core/parse-date "2024-01-15")))
    (is (= {:year 2024 :month 12 :day 31}
           (core/parse-date "2024-12-31"))))

  (testing "edge cases"
    (is (= {:year 2000 :month 1 :day 1}
           (core/parse-date "2000-01-01")))))
```

### 4. Verify Tests Pass

Save the file → tests run automatically in your test watcher terminal!

## The `dev/user.clj` Pattern

The `dev/user.clj` namespace is automatically loaded when you start a REPL. Use it for:

1. **Helper functions** you use repeatedly
2. **Test data** for manual testing
3. **System state management** (start/stop components)
4. **Quick access** to frequently-used namespaces

Example `dev/user.clj`:

```clojure
(ns user
  (:require [clojure.tools.namespace.repl :refer [refresh]]
            [myapp.core :as core]
            [myapp.db :as db]
            [myapp.web.server :as server]))

;; Helper: Reload changed code
(defn reset []
  (refresh))

;; Helper: Start/stop development server
(defonce system (atom nil))

(defn start-dev-server []
  (reset! system (server/start {:port 3000}))
  (println "Server started on http://localhost:3000"))

(defn stop-dev-server []
  (when @system
    (server/stop @system)
    (reset! system nil)
    (println "Server stopped")))

;; Test data
(def sample-user
  {:id 1
   :name "Alice"
   :email "alice@example.com"
   :created-at "2024-01-15"})

(comment
  ;; Quick tests
  (core/parse-date (:created-at sample-user))

  ;; Start/stop server
  (start-dev-server)
  (stop-dev-server)

  ;; Reload code
  (reset)
  )

(println "\n=== Dev REPL Loaded ===")
(println "Commands:")
(println "  (reset)             - Reload changed namespaces")
(println "  (start-dev-server)  - Start web server")
(println "  (stop-dev-server)   - Stop web server")
(println "  sample-user         - Example user data")
```

## Code Reloading with `refresh`

The `refresh` function from `clojure.tools.namespace.repl` reloads all changed namespaces.

**When to use it:**
- After changing function signatures
- After adding new `require` statements
- After modifying defrecords or deftypes
- When REPL state seems "stale"

**How to use it:**

```clojure
;; In your REPL
user=> (reset)  ; Defined in dev/user.clj
:reloading (myapp.core myapp.core-test)
:ok
```

**Warning:** `refresh` resets all vars. If you have stateful atoms or refs, they'll be reset too!

## Debugging Workflow

### Use `tap>`

Instead of `println` for debugging:

```clojure
(defn process-data [data]
  (tap> {:debug/input data})  ; Send to tap> listeners
  (let [result (transform data)]
    (tap> {:debug/result result})
    result))
```

Connect a tap listener (Portal, Reveal, or simple println):

```clojure
;; In dev/user.clj
(add-tap println)  ; Print all tap> values

;; Or use Portal for rich data visualization
(require '[portal.api :as p])
(def portal (p/open))
(add-tap #(p/submit portal %))
```

### Use `clojure.spec` for Validation

Define specs for your data:

```clojure
(require '[clojure.spec.alpha :as s])

(s/def ::year (s/int-in 1900 2100))
(s/def ::month (s/int-in 1 13))
(s/def ::day (s/int-in 1 32))
(s/def ::date (s/keys :req-un [::year ::month ::day]))

;; In REPL:
(s/valid? ::date {:year 2024 :month 1 :day 15})
;=> true

(s/explain ::date {:year 2024 :month 13 :day 15})
;=> val: 13 fails spec: :user/month predicate: (int-in 1 13)
```

## Performance Optimization

### Measure First

```clojure
;; Quick timing
(time (process-large-data))
;=> "Elapsed time: 234.567 msecs"

;; Detailed profiling (with clj-async-profiler)
(require '[clj-async-profiler.core :as prof])

(prof/profile (process-large-data))
;; Opens flamegraph in browser
```

### Optimize Iteratively

1. Measure baseline performance
2. Identify bottleneck (profiler or `time`)
3. Make ONE change
4. Measure again
5. Repeat

**Don't optimize prematurely!** Clojure's immutable data structures are fast enough for 95% of use cases.

## Daily Workflow Summary

### Morning: Start Your Environment

```bash
# Terminal 1
make nrepl

# Terminal 2
make runtests

# Editor
# Connect to nREPL
```

### During Development

1. Write function in `(comment ...)` block
2. Eval in REPL (Emacs: `C-c C-e`, VSCode: `Ctrl+Enter`)
3. Test variations
4. Move to production code
5. Write tests
6. Save → tests auto-run
7. Repeat

### Evening: Clean Up

```bash
# Commit your work
git add .
git commit -m "Add date parsing functionality"

# Clean artifacts (optional)
make clean
```

## Resources

- [REPL-Driven Development](https://practical.li/clojure/introduction/repl-workflow/)
- [clojure.tools.namespace](https://github.com/clojure/tools.namespace)
- [Portal](https://github.com/djblue/portal) - Data visualization tool
- [Reveal](https://vlaaad.github.io/reveal/) - REPL visualization

## Next Steps

- Read [03-testing-guide.md](./03-testing-guide.md) for testing patterns
- Check [troubleshooting.md](./troubleshooting.md) for common issues
