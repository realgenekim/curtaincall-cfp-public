# Testing Guide

Comprehensive guide to testing in this Clojure template using Kaocha.

## Philosophy: Test-Driven Development (TDD)

For this application's event-sourced domain mutations, also follow
[Algebraic domain decisions](design/algebraic-domain-decisions.md). Domain rules
are tested first as pure `state + command -> facts | rejection` decisions;
temporary JSONL tests prove the thin append shell; Ring and browser tests prove
wiring and user journeys.

### The TDD Cycle

```
1. Write a failing test
2. Write minimal code to make it pass
3. Refactor
4. Repeat
```

**Benefits:**
- ✅ Catches bugs early
- ✅ Documents intended behavior
- ✅ Enables fearless refactoring
- ✅ Fast feedback (< 1 second per test run)

## Running Tests

### Watch Mode (Recommended)

```bash
make runtests
```

Tests re-run automatically when you save any file. **Keep this running while you develop.**

### Single Run

```bash
make runtests-once
```

Runs all tests once with fail-fast (stops at first failure).

### Custom Kaocha Options

```bash
# Run tests with profiling
bin/kaocha --plugin kaocha.plugin/profiling

# Run specific namespace
bin/kaocha --focus myapp.core-test

# Run with verbose output
bin/kaocha --reporter documentation
```

## Test Organization

### File Structure

Tests mirror source files:

```
src/myapp/core.clj       → test/myapp/core_test.clj
src/myapp/util/string.clj → test/myapp/util/string_test.clj
src/myapp/db/query.clj    → test/myapp/db/query_test.clj
```

### Naming Conventions

- Test namespace: `myapp.core-test` (source ns + `-test`)
- Test function: `function-name-test` (function + `-test`)
- Use `testing` blocks for grouping related assertions

## Writing Tests

### Basic Test Structure

```clojure
(ns myapp.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [myapp.core :as core]))

(deftest function-name-test
  (testing "description of what we're testing"
    (is (= expected-value (core/function-name input)))
    (is (predicate? (core/function-name input)))))
```

### Example: Testing a Simple Function

**Source (`src/myapp/core.clj`):**
```clojure
(ns myapp.core)

(defn greet
  "Return a greeting for the given name."
  [name]
  (str "Hello, " name "!"))
```

**Test (`test/myapp/core_test.clj`):**
```clojure
(ns myapp.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [myapp.core :as core]))

(deftest greet-test
  (testing "greets with proper punctuation"
    (is (= "Hello, World!" (core/greet "World")))
    (is (= "Hello, Alice!" (core/greet "Alice"))))

  (testing "handles edge cases"
    (is (= "Hello, !" (core/greet "")))
    (is (= "Hello, 123!" (core/greet "123")))))
```

### Testing Exceptions

```clojure
(deftest divide-test
  (testing "divides two numbers"
    (is (= 2 (math/divide 4 2))))

  (testing "throws on division by zero"
    (is (thrown? ArithmeticException
                 (math/divide 4 0)))))
```

### Testing with Test Fixtures

Use fixtures for setup/teardown:

```clojure
(ns myapp.db-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [myapp.db :as db]))

(defn setup-test-db [f]
  (db/create-test-db!)
  (f)
  (db/destroy-test-db!))

(use-fixtures :each setup-test-db)

(deftest user-query-test
  (testing "finds user by id"
    (db/insert-user! {:id 1 :name "Alice"})
    (is (= "Alice" (:name (db/find-user 1))))))
```

## Common Testing Patterns

### 1. Testing Pure Functions (Easy!)

```clojure
(defn add [a b]
  (+ a b))

(deftest add-test
  (is (= 5 (add 2 3)))
  (is (= 0 (add -1 1)))
  (is (= -5 (add -2 -3))))
```

**Best practice:** Test edge cases (zero, negative, nil).

### 2. Testing Stateful Code

Use atoms/refs for state:

```clojure
;; Source
(defn counter []
  (let [count (atom 0)]
    {:inc! #(swap! count inc)
     :dec! #(swap! count dec)
     :get #(deref count)}))

;; Test
(deftest counter-test
  (let [c (counter)]
    (is (= 0 ((:get c))))
    ((:inc! c))
    (is (= 1 ((:get c))))
    ((:dec! c))
    (is (= 0 ((:get c))))))
```

### 3. Testing Web Handlers

Use `ring-mock`:

```clojure
(ns myapp.web.handlers-test
  (:require [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [myapp.web.handlers :as handlers]))

(deftest home-handler-test
  (testing "GET / returns 200"
    (let [response (handlers/home (mock/request :get "/"))]
      (is (= 200 (:status response)))
      (is (= "text/html" (get-in response [:headers "Content-Type"]))))))

(deftest api-handler-test
  (testing "POST /api/users creates user"
    (let [response (handlers/create-user
                     (-> (mock/request :post "/api/users")
                         (mock/json-body {:name "Alice" :email "alice@example.com"})))]
      (is (= 201 (:status response))))))
```

### 4. Testing Asynchronous Code

Use promises or `core.async`:

```clojure
(ns myapp.async-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.core.async :as async]))

(deftest async-computation-test
  (let [result-chan (async/chan)
        _ (async/go
            (async/>! result-chan (expensive-computation)))
        result (async/<!! result-chan)]
    (is (= expected-value result))))
```

### 5. Property-Based Testing

Use `test.check` for generative testing:

```clojure
(ns myapp.properties-test
  (:require [clojure.test :refer [deftest]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]))

(defspec reverse-twice-is-identity 100
  (prop/for-all [v (gen/vector gen/int)]
    (= v (reverse (reverse v)))))
```

## Test Data Management

### 1. Inline Test Data (Simple Tests)

```clojure
(deftest parse-date-test
  (is (= {:year 2024 :month 1 :day 15}
         (parse-date "2024-01-15"))))
```

### 2. `def` at Namespace Level (Shared Data)

```clojure
(def sample-user
  {:id 1
   :name "Alice"
   :email "alice@example.com"})

(deftest validate-user-test
  (is (valid? sample-user)))

(deftest serialize-user-test
  (is (= "{\"id\":1,...}" (to-json sample-user))))
```

### 3. Fixtures (Setup/Teardown)

```clojure
(def test-users (atom []))

(defn load-test-users [f]
  (reset! test-users [{:id 1 :name "Alice"}
                      {:id 2 :name "Bob"}])
  (f)
  (reset! test-users []))

(use-fixtures :each load-test-users)
```

### 4. EDN Files (Large Test Data)

`test/data/users.edn`:
```clojure
[{:id 1 :name "Alice" :email "alice@example.com"}
 {:id 2 :name "Bob" :email "bob@example.com"}
 ...]
```

`test/myapp/user_test.clj`:
```clojure
(def test-users
  (clojure.edn/read-string (slurp "test/data/users.edn")))

(deftest process-users-test
  (is (= 2 (count (filter active? test-users)))))
```

## Kaocha Configuration

### `tests.edn`

```clojure
#kaocha/v1
{:tests [{:id          :unit
          :test-paths  ["test" "src" "resources"]
          :ns-patterns [".*"]}]
 :reporter kaocha.report.progress/report
 :plugins [:kaocha.plugin/profiling
           :kaocha.plugin/notifier]}
```

### Available Reporters

```bash
# Progress bar (default)
bin/kaocha --reporter kaocha.report.progress/report

# Detailed documentation-style output
bin/kaocha --reporter documentation

# Print each assertion
bin/kaocha --reporter kaocha.report.documentation/report
```

## Best Practices

### ✅ DO

- **Write tests first** (TDD)
- **Test edge cases** (nil, empty, zero, negative)
- **Use descriptive test names** (`parse-date-handles-invalid-input-test`)
- **Group related tests** with `testing` blocks
- **Keep tests fast** (< 1 second for most tests)
- **Test behavior, not implementation** (test outputs, not internals)

### ❌ DON'T

- **Don't test private functions** (test public API only)
- **Don't test library code** (trust `clojure.string/split` works)
- **Don't write slow tests** (use fixtures to speed up setup)
- **Don't couple tests** (each test should be independent)
- **Don't ignore failing tests** (fix or remove them)

## Debugging Failed Tests

### 1. Read the Failure Message

```
FAIL in (parse-date-test) (core_test.clj:15)
expected: {:year 2024, :month 1, :day 15}
  actual: {:year 2024, :month 1, :day "15"}
          ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
```

The diff shows `"15"` (string) vs `15` (int).

### 2. Add `println` for Debugging

```clojure
(deftest failing-test
  (let [result (process-data input)]
    (println "DEBUG result:" result)  ; Temporary debug
    (is (= expected result))))
```

### 3. Use REPL to Reproduce

```clojure
;; In REPL
(require '[myapp.core-test :as t])
(t/failing-test)  ; Run the specific test

;; Or test the function directly
(myapp.core/process-data input)
```

### 4. Isolate the Problem

```clojure
;; Comment out passing assertions
(deftest big-test
  #_(is (= 1 1))  ; This passes
  (is (= 2 (failing-function)))  ; Focus on this
  #_(is (= 3 3)))  ; This passes
```

## Performance Testing

### Timing Tests

```clojure
(deftest performance-test
  (testing "processes 1000 items in < 100ms"
    (let [items (range 1000)
          start (System/nanoTime)
          _ (process-items items)
          elapsed-ms (/ (- (System/nanoTime) start) 1000000.0)]
      (is (< elapsed-ms 100)))))
```

### Profiling with Kaocha Plugin

```bash
bin/kaocha --plugin kaocha.plugin/profiling
```

Shows which tests are slow.

## Resources

- [Kaocha Documentation](https://cljdoc.org/d/lambdaisland/kaocha/)
- [clojure.test Guide](https://clojure.org/guides/test)
- [test.check](https://github.com/clojure/test.check) - Property-based testing
- [Expectations](https://github.com/clojure-expectations/expectations) - Alternative test library

## Next Steps

- Read [02-development-workflow.md](./02-development-workflow.md) for REPL workflow
- Check [troubleshooting.md](./troubleshooting.md) for common test issues
