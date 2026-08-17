# Getting Started with the Clojure Template

This guide walks you through using this template to create a new Clojure project.

## Prerequisites

- **Java 11+** (Java 17+ recommended)
- **Clojure CLI tools** (`clojure` command available)
- **make** (standard on macOS/Linux)
- **Git** (for cloning the template)

### Install Clojure

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

**Verify installation:**
```bash
clojure --version
# Should show: Clojure CLI version 1.11.x.x
```

## Creating a New Project

### Step 1: Clone the Template

```bash
# Replace 'my-awesome-project' with your project name
git clone https://github.com/your-username/mcp-clojure-template my-awesome-project
cd my-awesome-project

# Remove the template's git history
rm -rf .git
git init
```

### Step 2: Rename the Project

**Quick rename using `sed`:**

```bash
# Example: Renaming to 'awesome-app'
# Replace 'awesome-app' and 'awesome_app' with your project name

# 1. Rename directories
mv src/cfp_scheduler_killer src/awesome_app
mv test/cfp_scheduler_killer test/awesome_app

# 2. Update namespace declarations in all .clj files
# macOS/BSD:
find src test dev -name "*.clj" -type f -exec sed -i '' 's/cfp-scheduler-killer/awesome-app/g' {} +

# Linux:
# find src test dev -name "*.clj" -type f -exec sed -i 's/cfp-scheduler-killer/awesome-app/g' {} +
```

**Namespace naming rules:**
- Use **kebab-case** in `ns` declarations: `awesome-app`
- Use **snake_case** for directories: `awesome_app`
- Clojure automatically maps `awesome-app` → `awesome_app` directory

### Step 3: Verify the Setup

```bash
# Run tests to verify everything is working
make runtests-once
```

Expected output:
```
Running tests with fail-fast...
1 tests, 3 assertions, 0 failures.
```

✅ **Success!** Your project is ready to use.

## Next Steps

### Start Development

**Terminal 1 - nREPL server (for editor integration):**
```bash
make nrepl
```

**Terminal 2 - Test watcher (runs tests on every save):**
```bash
make runtests
```

### Connect Your Editor

**Emacs (CIDER):**
1. `M-x cider-connect`
2. Select `localhost`
3. Port will be read from `.nrepl-port` automatically

**VSCode (Calva):**
1. `Ctrl+Shift+P` → "Calva: Connect to a Running REPL Server"
2. Select "Generic"
3. Port will be read from `.nrepl-port` automatically

**IntelliJ (Cursive):**
1. Run → Edit Configurations
2. Add New Configuration → Clojure REPL → Remote
3. Connection type: nREPL
4. Host: localhost
5. Port: (read from `.nrepl-port` file)

### Start Coding

1. Open `src/awesome_app/core.clj` in your editor
2. Write a new function:
   ```clojure
   (defn add [a b]
     (+ a b))
   ```
3. Test it in the REPL:
   ```clojure
   (add 2 3)
   ;=> 5
   ```
4. Write a test in `test/awesome_app/core_test.clj`:
   ```clojure
   (deftest add-test
     (testing "addition"
       (is (= 5 (core/add 2 3)))
       (is (= 0 (core/add -1 1)))))
   ```
5. Save the file → tests run automatically in Terminal 2!

## Project Structure

```
my-awesome-project/
├── src/awesome_app/          # Your source code
│   └── core.clj
├── test/awesome_app/         # Your tests (mirror src/)
│   └── core_test.clj
├── dev/                      # Development utilities
│   └── user.clj              # Auto-loaded in REPL
├── resources/                # Config files, EDN data, etc.
├── bin/                      # Scripts
│   └── kaocha                # Test runner
├── deps.edn                  # Dependencies
├── tests.edn                 # Test configuration
├── Makefile                  # Common commands
└── CLAUDE.md                 # Development guidelines
```

## Common Commands

```bash
make help           # Show all available commands
make nrepl          # Start nREPL server
make runtests       # Run tests in watch mode
make runtests-once  # Run tests once
make repl           # Start basic REPL (no nREPL)
make clean          # Clean compiled artifacts
```

## What's Next?

- Read [CLAUDE.md](../CLAUDE.md) for development best practices
- Check [02-development-workflow.md](./02-development-workflow.md) for REPL-driven development
- See [03-testing-guide.md](./03-testing-guide.md) for testing patterns
- Review [04-mcp-setup.md](./04-mcp-setup.md) for Claude Code integration

## Troubleshooting

See [troubleshooting.md](./troubleshooting.md) for common issues and solutions.
