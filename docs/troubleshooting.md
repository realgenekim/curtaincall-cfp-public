# Troubleshooting Guide

Common issues and solutions for this Clojure template.

## Table of Contents

- [Installation Issues](#installation-issues)
- [Testing Issues](#testing-issues)
- [nREPL Issues](#nrepl-issues)
- [MCP Issues](#mcp-issues)
- [Editor Connection Issues](#editor-connection-issues)
- [Performance Issues](#performance-issues)

---

## Installation Issues

### Clojure CLI not found

**Error:**
```
clojure: command not found
```

**Solution:**

**macOS:**
```bash
brew install clojure/tools/clojure
```

**Linux:**
```bash
curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh
chmod +x linux-install.sh
sudo ./linux-install.sh
```

**Verify:**
```bash
clojure --version
```

### Make not found

**Error:**
```
make: command not found
```

**Solution:**

**macOS:**
```bash
xcode-select --install
```

**Linux:**
```bash
# Ubuntu/Debian
sudo apt-get install build-essential

# Fedora/RHEL
sudo yum groupinstall "Development Tools"
```

---

## Testing Issues

### Tests fail with "No namespace found"

**Error:**
```
Could not locate myapp/core__init.class, myapp/core.clj or myapp/core.cljc on classpath
```

**Cause:** Namespace name doesn't match directory structure.

**Solution:**

1. Check directory structure:
   ```bash
   ls -la src/
   # Should show: myapp/ (not my_app/ or my-app/)
   ```

2. Check namespace declaration in `src/myapp/core.clj`:
   ```clojure
   (ns myapp.core)  ; Use kebab-case: myapp, not my-app or my_app
   ```

3. Verify test requires:
   ```clojure
   (ns myapp.core-test
     (:require [myapp.core :as core]))  ; Must match exactly
   ```

### Tests pass but don't run on file save

**Problem:** Watch mode not detecting changes.

**Solution:**

1. Check if Kaocha watcher is running:
   ```bash
   ps aux | grep kaocha
   ```

2. Restart watcher:
   ```bash
   # Ctrl+C in the terminal running `make runtests`
   make runtests
   ```

3. Make sure you're saving files in `src/`, `test/`, or `dev/`:
   ```bash
   # Files in these directories trigger tests:
   src/**/*.clj
   test/**/*.clj
   dev/**/*.clj
   ```

### Tests are slow (> 5 seconds)

**Diagnosis:**
```bash
bin/kaocha --plugin kaocha.plugin/profiling
```

This shows which tests are slow.

**Common causes:**
- Database setup in fixtures → Use in-memory DB or mocks
- Network requests → Mock HTTP calls
- Large data processing → Use smaller test datasets
- Missing `use-fixtures` optimization → Share setup across tests

**Example fix:**
```clojure
;; Slow: Creates DB for each test
(deftest test-1 []
  (with-db (fn [] ...)))

;; Fast: Creates DB once for all tests
(use-fixtures :once setup-db-once)
```

---

## nREPL Issues

### nREPL won't start

**Error:**
```
make nrepl
# Hangs or fails
```

**Solution:**

1. Check if another nREPL is running:
   ```bash
   cat .nrepl-port
   lsof -i :$(cat .nrepl-port)
   ```

2. Kill existing nREPL:
   ```bash
   pkill -f "clojure.*nrepl"
   rm .nrepl-port
   ```

3. Restart:
   ```bash
   make nrepl
   ```

### Can't find `.nrepl-port` file

**Error:**
```
cat: .nrepl-port: No such file or directory
```

**Cause:** nREPL hasn't finished starting.

**Solution:**

Wait a few seconds after running `make nrepl`:
```bash
make nrepl &
sleep 5
cat .nrepl-port
```

### nREPL port keeps changing

**Explanation:** This is normal! nREPL auto-assigns a free port each time it starts.

**Solution:** Always read from `.nrepl-port`:
```bash
# Good
cat .nrepl-port

# Bad - hardcoding port
# lsof -i :7888
```

Editors (Emacs CIDER, VSCode Calva, IntelliJ Cursive) automatically read `.nrepl-port`.

---

## MCP Issues

### MCP can't connect

**Error:**
```
MCP connection failed
```

**Diagnosis:**

1. Is nREPL running?
   ```bash
   cat .nrepl-port  # Should show a port number
   ```

2. Can you connect manually?
   ```bash
   telnet localhost $(cat .nrepl-port)
   # Should connect
   ```

3. Is MCP configured correctly?
   ```bash
   claude mcp list | grep clojure-mcp
   ```

**Solution:**

```bash
# 1. Restart nREPL
pkill -f "clojure.*nrepl"
make nrepl

# 2. Reconfigure MCP
make mcp-configure

# 3. Test MCP manually
make mcp-run
```

### "No such alias: :mcp"

**Error:**
```
Error building classpath. No such alias: :mcp
```

**Cause:** `:mcp` alias missing from `~/.clojure/deps.edn`.

**Solution:**

Add to `~/.clojure/deps.edn`:
```clojure
{:aliases
 {:mcp {:extra-deps {io.github.cldwalker/clojure-mcp
                     {:git/sha "eae7b5e1869be9d8ea08d3d6dfb87e3afd4ce5f4"}}
        :exec-fn clojure-mcp.core/start
        :exec-args {:port nil}}}}
```

### MCP uses wrong project

**Problem:** MCP connects to a different project's nREPL.

**Solution:**

```bash
# Reconfigure MCP in the correct project
cd /path/to/correct/project
make mcp-configure
```

---

## Editor Connection Issues

### Emacs CIDER won't connect

**Problem:** `M-x cider-connect` fails.

**Solution:**

1. Make sure nREPL is running:
   ```bash
   make nrepl
   cat .nrepl-port  # Note the port
   ```

2. In Emacs:
   ```
   M-x cider-connect
   Host: localhost
   Port: [port from .nrepl-port]
   ```

3. If it still fails, check for errors:
   ```
   M-x cider-connect
   *Messages* buffer should show errors
   ```

### VSCode Calva can't find nREPL

**Problem:** "No nREPL server found"

**Solution:**

1. Check `.nrepl-port` exists:
   ```bash
   cat .nrepl-port
   ```

2. In VSCode:
   ```
   Ctrl+Shift+P → "Calva: Connect to a Running REPL Server"
   → Select "Generic"
   → Calva should find .nrepl-port automatically
   ```

3. If not found, manually enter port:
   ```
   Host: localhost
   Port: [from .nrepl-port]
   ```

### IntelliJ Cursive connection timeout

**Solution:**

1. Start nREPL first:
   ```bash
   make nrepl
   cat .nrepl-port  # e.g., 54321
   ```

2. In IntelliJ:
   ```
   Run → Edit Configurations → + → Clojure REPL → Remote
   Connection type: nREPL
   Host: localhost
   Port: 54321  (from .nrepl-port)
   ```

---

## Performance Issues

### REPL is slow to evaluate code

**Possible causes:**

1. **Large data structures** - Don't print huge collections in REPL
   ```clojure
   ;; Bad
   (def big-data (range 1000000))
   big-data  ; Hangs trying to print

   ;; Good
   (def big-data (range 1000000))
   (count big-data)  ; Just show count
   (take 10 big-data)  ; Show sample
   ```

2. **Lazy sequences not realized** - Force realization:
   ```clojure
   (doall (map expensive-fn data))  ; Realize now, not later
   ```

3. **Too many libraries loaded** - Restart REPL periodically:
   ```bash
   pkill -f "clojure.*nrepl"
   make nrepl
   ```

### Test watcher uses too much CPU

**Solution:**

Reduce watch scope in `tests.edn`:

```clojure
#kaocha/v1
{:tests [{:id :unit
          :test-paths ["test"]  ; Only watch test/, not src/
          :ns-patterns [".*"]}]}
```

Or use manual test runs:
```bash
make runtests-once  # Instead of watch mode
```

### Code reload is slow (`refresh` takes > 5 seconds)

**Cause:** Too many namespaces or circular dependencies.

**Diagnosis:**
```clojure
(require '[clojure.tools.namespace.repl :refer [refresh]])
(time (refresh))
```

**Solutions:**

1. **Remove circular dependencies:**
   ```bash
   # Check for circular deps
   lein ns-dep-graph  # Or use tools.namespace to detect cycles
   ```

2. **Don't reload everything:**
   ```clojure
   ;; Instead of full refresh:
   (require 'myapp.core :reload)  ; Reload specific namespace
   ```

3. **Use `:reload-all` sparingly:**
   ```clojure
   (require 'myapp.core :reload)  ; Fast - reload this ns only
   (refresh)  ; Slow - reload all changed namespaces
   ```

---

## Other Issues

### `sed` command doesn't work (macOS vs Linux)

**Problem:** Rename script fails.

**Solution:**

**macOS (BSD sed):**
```bash
find src test dev -name "*.clj" -type f -exec sed -i '' 's/old/new/g' {} +
```

**Linux (GNU sed):**
```bash
find src test dev -name "*.clj" -type f -exec sed -i 's/old/new/g' {} +
```

Or use manual rename (see [01-getting-started.md](./01-getting-started.md)).

### Can't push to Git

**Problem:** Trying to push but template has original Git history.

**Solution:**

Reset Git history:
```bash
rm -rf .git
git init
git add .
git commit -m "Initial commit from template"
git remote add origin https://github.com/your-username/your-repo.git
git push -u origin main
```

### `make` commands don't work

**Problem:** `make: *** No rule to make target...`

**Solution:**

1. Make sure you're in the project root:
   ```bash
   pwd  # Should show /path/to/your-project
   ls Makefile  # Should exist
   ```

2. Check for typos:
   ```bash
   make help  # List all available commands
   ```

---

## Getting More Help

### Check Logs

**nREPL output:**
```bash
make nrepl
# Watch for errors in terminal
```

**Test output:**
```bash
make runtests-once
# Read test failures carefully
```

**MCP output:**
```bash
make mcp-run
# Watch for connection errors
```

### Ask for Help

When asking for help, include:

1. **Error message** (full output)
2. **What you tried** (steps to reproduce)
3. **Environment:**
   ```bash
   clojure --version
   java -version
   uname -a  # OS version
   ```

### Useful Resources

- [Clojure Docs](https://clojuredocs.org/)
- [Clojure Slack](https://clojurians.slack.com/)
- [Kaocha Issues](https://github.com/lambdaisland/kaocha/issues)
- [nREPL Docs](https://nrepl.org/)

---

## Emergency: Start Fresh

If everything is broken:

```bash
# 1. Clean all artifacts
make clean
rm -rf .cpcache/ .nrepl-port target/

# 2. Kill all Clojure processes
pkill -f clojure
pkill -f java

# 3. Restart nREPL
make nrepl

# 4. Run tests
make runtests-once

# 5. Reconfigure MCP
make mcp-configure
```

This solves 90% of issues!
