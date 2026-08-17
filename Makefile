# Default target when running 'make' without arguments
.DEFAULT_GOAL := help

# Add ~/bin to PATH for Clojure CLI tools
export PATH := $(HOME)/bin:$(PATH)

# Server port (default: 20500)
PORT ?= 20500
JVM_MIN_HEAP ?= 64m
JVM_MAX_HEAP ?= 512m

# Start nREPL server (auto-assigns port, writes to .nrepl-port)
nrepl:
	# One database: the REPL lives in the same reality as dev and prod (Gene 2026-08-10)
	# Guard (bd -u04): stale jar-build output must not shadow src/resources.
	rm -rf target/classes
	STORE_BACKEND=postgres clojure -M:nrepl

# Configure clj-kondo with library configs (run once after adding dependencies)
clj-kondo-config:
	@echo "🔧 Configuring clj-kondo with library configs..."
	clj-kondo --lint "$$(clojure -Spath)" --dependencies --parallel --copy-configs
	@echo "✅ clj-kondo configs updated in .clj-kondo/"

# ========================================
# MCP Server Configuration
# ========================================

# Configure MCP server in Claude Code (dynamically uses current directory)
mcp-configure:
	@echo "🔧 Configuring MCP server in Claude Code..."
	@echo ""
	@echo "Adding Clojure MCP (project-specific tools)..."
	claude mcp add clojure-mcp -- /bin/sh -c 'cd $(shell pwd) && clojure -Tmcp start :config-profile :cli-assist'
	@echo ""
	@echo "✅ MCP server configured!"

# Remove MCP server
mcp-remove:
	@echo "🗑️  Removing MCP server..."
	-claude mcp remove clojure-mcp
	@echo "✅ MCP server removed!"

# Run Clojure MCP server locally (for testing)
mcp-run:
	@echo "🚀 Starting Clojure MCP server..."
	@echo "   Reading port from: $(shell pwd)/.nrepl-port"
	cd $(shell pwd) && clojure -Tmcp start :config-profile :cli-assist

# ========================================
# Testing
# ========================================

# Run tests with kaocha - watch mode (fast :unit suite only)
runtests:
	@echo "Running unit tests with watcher..."
	bin/kaocha unit --watch --reporter kaocha.report.progress/report

# Watch mode for AGENTS: persistent 512 MB JVM, warm re-runs on every save,
# stamped output to 00TESTLOG.txt (gitignored). The bounded log is an
# implementation detail; `make test-verdict` is the only supported read path.
runtests-log:
	@if [ -s .testwatch.pid ] && kill -0 "$$(cat .testwatch.pid)" 2>/dev/null; then \
		echo "test watcher already running as pid $$(cat .testwatch.pid)"; exit 1; \
	fi
	@: > 00TESTLOG.txt
	@rm -f .testwatch-verdict
	@echo "Warm test watcher started (read with: make test-verdict)"
	@set -o pipefail; \
		echo $$$$ > .testwatch.pid; \
		trap 'rm -f .testwatch.pid' EXIT INT TERM; \
		bin/kaocha unit --watch --reporter kaocha.report.progress/report 2>&1 \
		  | VERDICT_STAMP=.testwatch-verdict bin/testlog-stamp.sh >> 00TESTLOG.txt

# Foreground watcher for a DISPLAY seat (the cfp2 pane experiment, 2026-08-16):
# identical to runtests-log — same pid file, same verdict stamp, same log — but
# ALSO streams every line to the terminal so the pane IS the fleet's live
# test display. Machines still query `make test-verdict`, never the pane.
runtests-watch:
	@if [ -s .testwatch.pid ] && kill -0 "$$(cat .testwatch.pid)" 2>/dev/null; then \
		echo "test watcher already running as pid $$(cat .testwatch.pid)"; exit 1; \
	fi
	@: > 00TESTLOG.txt
	@rm -f .testwatch-verdict
	@echo "Warm test watcher started, streaming (verdict: make test-verdict)"
	@set -o pipefail; \
		echo $$$$ > .testwatch.pid; \
		trap 'rm -f .testwatch.pid' EXIT INT TERM; \
		bin/kaocha unit --watch --reporter kaocha.report.progress/report 2>&1 \
		  | VERDICT_STAMP=.testwatch-verdict bin/testlog-stamp.sh | tee -a 00TESTLOG.txt

test-verdict:
	@bin/test-verdict.sh

# Run the fast unit suite once, fail-fast — THE inner-loop command.
# Skips anything tagged ^:e2e / ^:slow / ^:pg (see tests.edn).
runtests-once:
	@echo "Running fast unit tests (fail-fast)..."
	bin/kaocha unit --fail-fast

# Focus one namespace or test id without bypassing the Makefile execution lane.
# Example: make runtests-focus FOCUS=cfp-scheduler-killer.default-event-test
runtests-focus:
	@test -n "$(FOCUS)" || { echo "FOCUS is required"; exit 2; }
	bin/kaocha unit --focus "$(FOCUS)" --fail-fast

# Tool-dependent architecture inventory. CI runs this before every staging deploy.
runtests-ci:
	@echo "Running CI architecture guards..."
	bin/kaocha ci --fail-fast

# The slow/live lane, kept OFF the primary path: tagged clojure tests, then
# the Python driver against a running server. Run before a deploy, not on save.
runtests-e2e:
	@echo "Running e2e/slow clojure tests..."
	-bin/kaocha e2e
	@echo "Running the live HTTP driver (needs a server on :20500)..."
	python3 bin/e2e_drive.py

# ========================================
# Development
# ========================================

# Canonical name makes the backend, data authority, access, and runtime visible
# in terminal history. Short names remain compatibility aliases.
server: server-postgres-productiondata-readwrite-devhotreload
server-dev: server-postgres-productiondata-readwrite-devhotreload

# Seed a fresh clone with a working demo world (Charlotte event + committee).
# data/ is gitignored, so a clone starts empty; this rebuilds the world through
# the REAL code paths. Safe to run twice — it detects an existing event.
# The running dev server picks it up on the next request (no restart needed).
seed-demo:
	@echo "🌱 Seeding demo data into data/store/events.jsonl..."
	clojure -M -m cfp-scheduler-killer.seed-demo

# Judge Sandbox: a committed golden append log is copied to a disposable
# runtime log on every boot. Persona actions can never touch the normal local
# store, and a restart restores the exact same 500-submission world.
JUDGE_SANDBOX_GOLDEN := resources/judge-sandbox/events.jsonl
JUDGE_SANDBOX_RUNTIME := data/store/judge-sandbox/events.jsonl

preflight-local:
	@bin/ensure-worktree-secrets

regenerate-judge-sandbox: preflight-local
	@echo "🏗  Regenerating the immutable Judge Sandbox fixture through domain verbs..."
	@STORE_BACKEND=jsonl clj -X cli.judge-sandbox/generate

reset-judge-sandbox:
	@test -s $(JUDGE_SANDBOX_GOLDEN) || (echo "Judge Sandbox fixture missing: run make regenerate-judge-sandbox" >&2; exit 1)
	@mkdir -p $(dir $(JUDGE_SANDBOX_RUNTIME))
	@cp $(JUDGE_SANDBOX_GOLDEN) $(JUDGE_SANDBOX_RUNTIME)
	@cmp -s $(JUDGE_SANDBOX_GOLDEN) $(JUDGE_SANDBOX_RUNTIME)
	@echo "↺ Judge Sandbox restored from its golden fixture"

judge-sandbox: preflight-local reset-judge-sandbox
	@echo "🧪 Starting Judge Sandbox on port $(PORT)..."
	@STORE_PATH=$(JUDGE_SANDBOX_RUNTIME) $(MAKE) server-jsonl PORT=$(PORT)

# One-shot local Judge Sandbox: restore the immutable fixture, replace the
# JSONL server, wait for readiness, and prove all three persona sessions.
reset-jsonl-server:
	@PORT=$(PORT) JVM_MIN_HEAP=$(JVM_MIN_HEAP) JVM_MAX_HEAP=$(JVM_MAX_HEAP) bin/reset-jsonl-server

# Resolve the newest successful deployment, then run stateful isolated area
# lanes on anvil with exact code/data/auth provenance.
reset-anvil-fleet:
	@FLEET_SLOTS=$(or $(FLEET_SLOTS),8) bin/reset-anvil-fleet start

resume-anvil-fleet:
	@bin/reset-anvil-fleet resume

anvil-fleet-status:
	@bin/reset-anvil-fleet status

anvil-fleet-stop:
	@bin/reset-anvil-fleet stop

# Self-driving staging -> isolated Codex fleet -> score -> next-target loop.
# `once` is idempotent by completed deployment SHA; `loop` debounces deploys.
hill-climb-autoscore:
	@bin/hill-climb-autoscore once

hill-climb-autoscore-loop:
	@bin/hill-climb-autoscore loop

hill-climb-autoscore-start:
	@bin/hill-climb-autoscore start

hill-climb-autoscore-stop:
	@bin/hill-climb-autoscore stop

hill-climb-autoscore-status:
	@bin/hill-climb-autoscore status

# Native tester-owned scoring primitive. The caller pins one successful deploy;
# kernel locking and immutable receipts make overlap and stale state visible.
hill-climb-score-one:
	@bin/hill-climb-score-one run "$(FLEET_SHA)" "$(FLEET_DEPLOYMENT_RUN_URL)"

hill-climb-score-status:
	@bin/hill-climb-score-one status

hill-climb-score-recover:
	@bin/hill-climb-score-one recover "$(FLEET_RUN)" "$(FLEET_STATE_SHA256)"

fleet-tools-test:
	@bin/test-capture-lineage
	@bin/test-hill-climb-fleet
	@bin/test-consolidate-hill-climb
	@bin/test-hill-climb-judgement-fold
	@bin/test-consolidate-hill-climb-sampling
	@bin/test-hill-climb-autoscore
	@bin/test-hill-climb-score-one

# Fast, hermetic control-plane feedback. This deliberately does not start the
# application suite or contact GitHub.
merger-controller-test:
	@bin/test-merger-batch-controller
	@bin/test-merger-async-deploy
	@bin/test-merge-queue-helper

# Hermetic release-control contract: no gcloud or production access.
promotion-control-test:
	@bin/test-promote-staging-to-production

# Merge the current anvil run, judge each area in a fresh context, and publish
# one comparable score plus ranked insights.
consolidate-anvil-fleet:
	@bin/reset-anvil-fleet score

# Wipe the local event log. Destructive — this is the whole database.
store-reset:
	@echo "⚠️  Removing data/store/events.jsonl (the entire local store)..."
	rm -f data/store/events.jsonl
	@echo "✅ Store cleared. Run 'make seed-demo' to rebuild the demo world."

# Download a validated local checkpoint of the Postgres event log.
download-cache:
	STORE_BACKEND=postgres clojure -M -m cfp-scheduler-killer.store-checkpoint

# Dev mode: guardrails enabled, auto-reload
# DEV == PRODUCTION (Gene's ruling, 2026-08-10): the dev server runs against
# the SAME production Cloud SQL database (connection from secrets/db.edn,
# socket factory over ADC). What you make locally IS what judges see.
server-postgres-productiondata-readwrite-devhotreload: preflight-local
	@echo "🚀 Starting DEV server (port $(PORT), PRODUCTION database, guardrails ON, auto-reload)..."
	@bin/server-mode preflight $(PORT) postgres-productiondata-readwrite-devhotreload
	@# Guard (bd -u04): a jar build leaves stale source+asset copies in
	@# target/classes that shadow src/ and resources/ on the classpath —
	@# "served but didn't render" CSS, three times on 2026-08-09/10.
	rm -rf target/classes
	ENV=dev PORT=$(PORT) STORE_BACKEND=postgres clojure -J-Xms$(JVM_MIN_HEAP) -J-Xmx$(JVM_MAX_HEAP) -J-Dguardrails.enabled=true -A:dev -M -m cfp-scheduler-killer.core

server-status:
	@bin/server-mode status $(PORT)

# The SANDBOX: local JSONL store, for coding agents and e2e drives — their
# junk events must never reach the shared production database.
server-jsonl: preflight-local
	@echo "🧪 Starting SANDBOX server (port $(PORT), local JSONL store)..."
	ENV=dev PORT=$(PORT) clojure -J-Xms$(JVM_MIN_HEAP) -J-Xmx$(JVM_MAX_HEAP) -J-Dguardrails.enabled=true -A:dev -M -m cfp-scheduler-killer.core

# Production mode: no guardrails, no auto-reload
server-prod: preflight-local
	@echo "🚀 Starting server (port $(PORT), production mode)..."
	PORT=$(PORT) clojure -J-Xms$(JVM_MIN_HEAP) -J-Xmx$(JVM_MAX_HEAP) -M -m cfp-scheduler-killer.core

# Stop server running on configured port
stop:
	lsof -ti :$(PORT) | xargs kill -9 || true

# Fast, deterministic compilation check for the complete web-app dependency
# graph. Keep it unpiped so the shell cannot hide Clojure's exit code.
compile-check:
	@echo "Compiling application namespaces..."
	@clojure -M -e "(require 'cfp-scheduler-killer.server)"
	@echo "✓ Application namespaces compiled successfully"

server-test-run: compile-check

# Start REPL
repl:
	clj

# Clean compiled artifacts
clean:
	rm -rf .cpcache/ .nrepl-port target/

# ========================================
# Build & Deploy
# ========================================

# GCP Configuration
GCP_PROJECT := swyx-cfp-saas-killer
GCP_REGION := us-west1
SERVICE_NAME := swyx-cfp-saas-killer
CURTAINCALL_SERVICE_NAME := curtaincallcfp
PROMOTION_SERVICES := $(SERVICE_NAME) $(CURTAINCALL_SERVICE_NAME)
IMAGE_URL := $(GCP_REGION)-docker.pkg.dev/$(GCP_PROJECT)/cloud-run-source-deploy/swyx-cfp-saas-killer:latest

# Bake the exact artifact identity into classpath resources before packaging.
# The page footer reads these files; the git fallback is only for local dev.
stamp-release-identity:
	@sha=$$(git rev-parse --short=7 HEAD 2>/dev/null || true); \
	if [ -z "$$sha" ]; then echo "✗ no git SHA available" >&2; exit 1; \
	else printf '%s\n' "$$sha" > resources/build-sha.txt; \
	  date -u +%Y-%m-%dT%H:%M:%SZ > resources/build-time.txt; fi

# Build thin jar
build: stamp-release-identity
	@echo "Building thin JAR + deps..."
	clojure -T:build thin-build
	@echo "✓ Built: target/cfp-scheduler-killer.jar (+ target/lib/)"

# Run the standalone uberjar
# The thin JAR needs its deps on the classpath — `java -jar` alone cannot work,
# and this is exactly how the container entrypoint runs it.
runuberjar: uberjar-run

uberjar-run:
	@echo "Running the built JAR (prod mode: ENV unset, auth on)..."
	PORT=$(PORT) java -cp "target/cfp-scheduler-killer.jar:target/lib/*" cfp_scheduler_killer.core

# Build, boot, hit it, stop. The smoke test that catches a broken artifact
# before a deploy does.
uberjar-smoke: build
	@echo "🔥 Smoke-testing the built JAR on port 20601..."
	@PORT=20601 java -cp "target/cfp-scheduler-killer.jar:target/lib/*" cfp_scheduler_killer.core & \
	  APP_PID=$$!; \
	  sleep 18; \
	  echo "  /login  -> $$(curl -s -o /dev/null -w '%{http_code}' http://localhost:20601/login)"; \
	  echo "  /events -> $$(curl -s -o /dev/null -w '%{http_code}' http://localhost:20601/events) (302 = auth on, correct for prod)"; \
	  kill $$APP_PID 2>/dev/null; \
	  echo "✓ Smoke test done"

# Build and push container image with Jib
# Guard: jib ships target/ artifacts — always rebuild first so a push cannot ship a stale jar
jib-deploy: build
	@echo "🚀 Building and pushing container with Jib..."
	time clojure -T:jib-deploy jib-deploy
	@echo "✓ Container pushed to $(IMAGE_URL)"

# Retired direct-production entrypoint. Production must only receive an exact,
# already-verified staging revision through promote-staging-to-production.
cloudrundeploy:
	@echo "✗ direct production deploy is retired" >&2
	@echo "  Stage through GitHub Actions, inspect it, then run:" >&2
	@echo "  make promote-staging-to-production EXPECTED_SHA=<sha> PROMOTE=YES" >&2
	@exit 2

# Retired compatibility name; fail before building or pushing anything.
deploy-all:
	@$(MAKE) --no-print-directory cloudrundeploy

# Scaffold a new project from this template
# Usage: make scaffold NAME=my-project [DIR=~/src.local/my-project]
scaffold:
ifndef NAME
	@echo "Usage: make scaffold NAME=my-project [DIR=~/src.local/my-project]"
	@echo ""
	@echo "Creates a new project from this template with all placeholders replaced."
	@echo "REFUSES to overwrite an existing directory."
	@exit 1
endif
	bin/scaffold $(NAME) $(DIR)

# Help
help:
	@echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
	@echo "  Clojure Project Template - Make Commands"
	@echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
	@echo ""
	@echo "🔧 Setup:"
	@echo "  make nrepl                - Start nREPL server (auto-port, writes to .nrepl-port)"
	@echo "  make clj-kondo-config     - Configure clj-kondo with library configs"
	@echo "  make mcp-configure        - Configure MCP server in Claude Code"
	@echo "  make mcp-run              - Run MCP server (for testing)"
	@echo "  make mcp-remove           - Remove MCP server from Claude Code"
	@echo ""
	@echo "🧪 Testing:"
	@echo "  make runtests             - Run tests with watcher"
	@echo "  make runtests-watch       - Warm kaocha watcher in FOREGROUND (fleet display pane;"
	@echo "                              streams every line, stamps verdict; one instance via pid file)"
	@echo "  make test-verdict         - rc 0 green / 1 red / 2 warming-or-stale (reads watcher stamp)"
	@echo "  make runtests-once        - Run fast tests once with fail-fast"
	@echo "  make runtests-focus FOCUS=<test-id> - Run one focused Kaocha test id"
	@echo "  make runtests-ci          - Run tool-dependent architecture guards"
	@echo ""
	@echo "  make server-test-run      - Test application compilation without starting it"
	@echo "  make promotion-control-test - Test staged verification, promotion, and rollback guards"
	@echo ""
	@echo "🌱 Data (zero-setup local store — no database required):"
	@echo "  make seed-demo            - Seed the demo world (Charlotte event + committee)"
	@echo "  make store-reset          - Delete the local event log (destructive)"
	@echo ""
	@echo "📦 Build & Deploy:"
	@echo "  make build                - Build thin JAR + deps (target/cfp-scheduler-killer.jar)"
	@echo "  make uberjar-run          - Run the built JAR (prod mode)"
	@echo "  make uberjar-smoke        - Build, boot, curl, stop (pre-deploy check)"
	@echo "  make jib-deploy           - Build and push container with Jib"
	@echo "  make deploy-staging       - Build/push and stage the primary Cloud Run service"
	@echo "  make deploy-curtaincall-staging - Stage the same image on the judge-facing service"
	@echo "  make verify-staging-release EXPECTED_SHA=<sha> - Read-only staged release proof"
	@echo "  make promote-staging-to-production EXPECTED_SHA=<sha> PROMOTE=YES - Release exact staging revisions"
	@echo "  make rollback-production-promotion ROLLBACK=YES - Restore the latest promotion receipt"
	@echo "  make cloudrundeploy/deploy-all - RETIRED; direct production deploys fail closed"
	@echo ""
	@echo "🚀 Development:"
	@echo "  make server-postgres-productiondata-readwrite-devhotreload"
	@echo "                            - production Postgres; read/write; dev guardrails + hot reload"
	@echo "  make server               - Start dev server (default, guardrails ON)"
	@echo "  make server-dev           - Compatibility alias for the canonical target"
	@echo "  make server-status        - Identify the listener and its runtime authority"
	@echo "  make server-prod          - Start production server (no guardrails)"
	@echo "  make judge-sandbox        - Seed + start isolated swyx/Maya/Amara demo"
	@echo "  make reset-jsonl-server   - Restore + verify detached local Judge Sandbox"
	@echo "  make reset-anvil-fleet    - Fresh production-parity full-score fleet on anvil"
	@echo "  make resume-anvil-fleet   - Resume only missing journeys in the current anvil run"
	@echo "  make anvil-fleet-status   - Read current anvil receipts and progress"
	@echo "  make hill-climb-autoscore - Score the newest stable staging deploy once"
	@echo "  make hill-climb-autoscore-loop - Continuously score stable staging deploys"
	@echo "  make hill-climb-autoscore-start - Start the detached self-driving score loop"
	@echo "  make hill-climb-autoscore-stop - Stop only the detached score controller"
	@echo "  make hill-climb-autoscore-status - JSON status for the autoscore controller"
	@echo "  make hill-climb-score-one FLEET_SHA=... FLEET_DEPLOYMENT_RUN_URL=... - Score one exact deploy"
	@echo "  make hill-climb-score-status - JSON score progress derived from live receipts"
	@echo "  make merger-controller-test - Fast hermetic merger + async-deploy contracts"
	@echo "  make hill-climb-score-recover FLEET_RUN=/absolute/run FLEET_STATE_SHA256=... - Certify judge-only recovery"
	@echo "  make preflight-local      - Verify/link required ignored local secrets"
	@echo "  make stop                 - Stop server running on configured port"
	@echo "  make repl                 - Start basic REPL"
	@echo "  make clean                - Clean compiled artifacts"
	@echo "  make help                 - Show this help"
	@echo ""
	@echo "🏗️  Scaffolding:"
	@echo "  make scaffold NAME=foo    - Create new project from this template"
	@echo ""
	@echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

.PHONY: uberjar-run uberjar-smoke reset-jsonl-server reset-anvil-fleet resume-anvil-fleet anvil-fleet-status anvil-fleet-stop hill-climb-autoscore hill-climb-autoscore-loop hill-climb-autoscore-start hill-climb-autoscore-stop hill-climb-autoscore-status hill-climb-score-one hill-climb-score-status hill-climb-score-recover fleet-tools-test merger-controller-test promotion-control-test consolidate-anvil-fleet seed-demo preflight-local regenerate-judge-sandbox reset-judge-sandbox judge-sandbox store-reset download-cache nrepl clj-kondo-config mcp-configure mcp-remove mcp-run runtests runtests-once runtests-focus runtests-ci compile-check server server-dev server-prod stop server-test-run repl clean stamp-release-identity build runuberjar jib-deploy cloudrundeploy deploy-all deploy-staging deploy-curtaincall-staging verify-staging-release promote-staging-to-production promote-staging rollback-production-promotion rollback-staging traffic scaffold help

# ========================================
# Database admin (the clj -X operator CLI — src/cfp_scheduler_killer/admin.clj)
# ========================================

# Back up the Postgres event log to ./backups/<utc-stamp>-events-pg.jsonl,
# verbatim lines. Uses ADC locally (Cloud SQL IAM — no secret exists).
# Connection comes from secrets/db.edn (runtime-loaded; Cloud SQL socket
# factory over ADC — no proxy, no psql, per Gene 2026-08-09).
backup-db:
	@mkdir -p backups
	clojure -X cfp-scheduler-killer.admin/backup-db

# Replay the LOCAL dev event log into the production database, verbatim.
# Refuses a non-empty destination unless FORCE=true (then it APPENDS).
promote-db:
	clojure -X cfp-scheduler-killer.admin/promote-local $(if $(FORCE),:force true,)

## ---- Staging (tagged no-traffic revision; bridge 2026-08-11) ----
# Staging = a SECOND live revision of the SAME Cloud Run service. It serves 0%
# of main-URL traffic but is directly reachable at its tag URL
# (https://staging---<service>-<hash>.<region>.run.app). Production traffic
# keeps hitting the previously-deployed revision untouched until promote-staging.

# Deploy the current build as the staging revision (no production traffic moves)
deploy-staging: preflight-deploy build jib-deploy
	@echo "🧪 Deploying STAGING revision (0% traffic, tag=staging)..."
	time gcloud run deploy $(SERVICE_NAME) \
		--project=$(GCP_PROJECT) \
		--image $(IMAGE_URL) \
		--region $(GCP_REGION) \
		--platform managed \
		--memory 2G \
		--max-instances 1 \
		--concurrency 300 \
		--allow-unauthenticated \
		--no-traffic \
		--tag staging
	@$(MAKE) --no-print-directory staging-url

# Deploy the same verified image to the judge-facing service as a tagged,
# no-production-traffic revision. GitHub Actions calls this after deploy-staging.
deploy-curtaincall-staging:
	@echo "🧪 Deploying CURTAIN CALL STAGING revision (0% traffic, tag=staging)..."
	time gcloud run deploy $(CURTAINCALL_SERVICE_NAME) \
		--project=$(GCP_PROJECT) \
		--image $(IMAGE_URL) \
		--region $(GCP_REGION) \
		--platform managed \
		--memory 2G \
		--max-instances 1 \
		--concurrency 300 \
		--allow-unauthenticated \
		--no-traffic \
		--tag staging

# Preflight: verify every boot-time prerequisite BEFORE building. Fails loud
# in seconds instead of a cryptic container-start death after a 4-minute build.
# Born 2026-08-10: two staging revisions died on (a) session-cookie-key secret
# not existing, (b) GCP_PROJECT env var missing so secrets.clj silently fell
# back to project does2020, (c) a 64-byte cookie key where exactly 16 is required.
preflight-deploy:
	@echo "── preflight: service=$(SERVICE_NAME) project=$(GCP_PROJECT) ──"
	@test "$$(gcloud secrets versions access latest --secret=session-cookie-key --project=$(GCP_PROJECT) 2>/dev/null | wc -c | tr -d ' ')" = "16" \
		|| { echo "✗ session-cookie-key: missing, unreadable, or not EXACTLY 16 bytes (server.clj cookie-key-bytes requires 16)"; exit 1; }
	@echo "✓ session-cookie-key present, 16 bytes"
	@gcloud secrets versions access latest --secret=google-oauth-client --project=$(GCP_PROJECT) >/dev/null 2>&1 \
		|| { echo "✗ google-oauth-client secret unreadable in $(GCP_PROJECT)"; exit 1; }
	@echo "✓ google-oauth-client readable"
	@gcloud run services describe $(SERVICE_NAME) --project=$(GCP_PROJECT) --region=$(GCP_REGION) \
		--format='value(spec.template.spec.containers[0].env)' 2>/dev/null | grep -q "GCP_PROJECT" \
		|| { echo "✗ service $(SERVICE_NAME) lacks GCP_PROJECT env var — secrets.clj will silently look in does2020 (bead 0a1)"; exit 1; }
	@echo "✓ GCP_PROJECT env var set on service"
	@echo "── preflight OK ──"

# Print the staging tag URL
staging-url:
	@gcloud run services describe $(SERVICE_NAME) \
		--project=$(GCP_PROJECT) --region=$(GCP_REGION) \
		--format="value(status.traffic.filter(tag='staging').extract(url).flatten())" \
		| head -1

# Open the staging URL in the default browser (macOS `open`, Linux xdg-open)
open-staging:
	@URL=$$($(MAKE) --no-print-directory staging-url); \
	echo "Opening $$URL"; \
	(command -v open >/dev/null && open "$$URL") || xdg-open "$$URL"

# Read-only proof that BOTH staging tag URLs serve the expected artifact and
# pass the judge-facing smoke paths. This command never changes traffic.
verify-staging-release:
	@test -n "$(EXPECTED_SHA)" || { echo "Usage: make verify-staging-release EXPECTED_SHA=<git-sha>" >&2; exit 2; }
	@GCP_PROJECT=$(GCP_PROJECT) GCP_REGION=$(GCP_REGION) SERVICE_NAMES="$(PROMOTION_SERVICES)" \
		bin/promote-staging-to-production verify "$(EXPECTED_SHA)"

# THE ONLY PRODUCTION PROMOTION. It pins the exact already-verified staging
# revisions, requires typed intent, records both rollback points, and restores
# the first service automatically if the second service fails.
promote-staging-to-production:
	@test -n "$(EXPECTED_SHA)" || { echo "Usage: make promote-staging-to-production EXPECTED_SHA=<git-sha> PROMOTE=YES" >&2; exit 2; }
	@GCP_PROJECT=$(GCP_PROJECT) GCP_REGION=$(GCP_REGION) SERVICE_NAMES="$(PROMOTION_SERVICES)" PROMOTE="$(PROMOTE)" \
		bin/promote-staging-to-production promote "$(EXPECTED_SHA)"

# Compatibility alias. It retains the old name but uses the guarded,
# two-service transaction; an unqualified call now fails closed.
promote-staging: promote-staging-to-production

rollback-production-promotion:
	@GCP_PROJECT=$(GCP_PROJECT) GCP_REGION=$(GCP_REGION) SERVICE_NAMES="$(PROMOTION_SERVICES)" ROLLBACK="$(ROLLBACK)" \
		bin/promote-staging-to-production rollback "$(or $(RECEIPT),.promotion-receipts/latest)"

# Compatibility alias for the receipt-backed two-service rollback.
rollback-staging: rollback-production-promotion

# Show current traffic split (who is actually serving production)
traffic:
	@gcloud run services describe $(SERVICE_NAME) \
		--project=$(GCP_PROJECT) --region=$(GCP_REGION) \
		--format="table(status.traffic.revisionName, status.traffic.percent, status.traffic.tag, status.traffic.url)"

## ── Keepalive lane (ccn, 2026-08-12): Cloud Scheduler warm pings ─────────────
# A cold start during judging is self-inflicted. Two jobs, every 5 minutes,
# GET / on each service (the landing page is public-200 everywhere; switch
# --uri to /ping once a deploy carries the /ping public-allowlist entry —
# today /ping 302s behind the anonymous gate and Scheduler would count that
# as failure). Idempotent: update-or-create. All three targets read-only-safe
# except keepalive-create/delete which mutate Scheduler jobs only.

WARM_SVCS := curtaincallcfp swyx-cfp-saas-killer
warm_url  = https://$(1)-109637679549.us-west1.run.app/

keepalive-create: ## create/update the 5-min warm pings for both services
	@for s in $(WARM_SVCS); do \
	  uri="https://$$s-109637679549.us-west1.run.app/"; \
	  if gcloud scheduler jobs describe warm-$$s --location=$(GCP_REGION) --project=$(GCP_PROJECT) >/dev/null 2>&1; then \
	    gcloud scheduler jobs update http warm-$$s --schedule="*/5 * * * *" --uri="$$uri" --http-method=GET --location=$(GCP_REGION) --project=$(GCP_PROJECT) --quiet >/dev/null && echo "✓ updated warm-$$s → $$uri"; \
	  else \
	    gcloud scheduler jobs create http warm-$$s --schedule="*/5 * * * *" --uri="$$uri" --http-method=GET --attempt-deadline=30s --location=$(GCP_REGION) --project=$(GCP_PROJECT) --quiet >/dev/null && echo "✓ created warm-$$s → $$uri"; \
	  fi; \
	done

keepalive-status: ## show both warm jobs + last attempt state
	@gcloud scheduler jobs list --location=$(GCP_REGION) --project=$(GCP_PROJECT) --filter="name~warm-" --format="table(ID,SCHEDULE,STATE,LAST_ATTEMPT_TIME,status.code)"

keepalive-run-now: ## force one immediate run of both jobs (prove the meter)
	@for s in $(WARM_SVCS); do gcloud scheduler jobs run warm-$$s --location=$(GCP_REGION) --project=$(GCP_PROJECT) && echo "✓ ran warm-$$s"; done

keepalive-delete: ## remove both warm jobs (disabling is a decision — see tripwire rule)
	@for s in $(WARM_SVCS); do gcloud scheduler jobs delete warm-$$s --location=$(GCP_REGION) --project=$(GCP_PROJECT) --quiet && echo "✗ deleted warm-$$s"; done

# One-shot public snapshot (Gene, 2026-08-17): allowlist export -> scrub
# meters -> commit in the export repo. Push remains a human/Mayor act.
export-public:
	bash bin/export_public.sh
	@cd $$HOME/src.local/curtaincall-cfp-public && \
	  ! grep -rIqiE 'sydney|75\.139\.|2a01:|ghp_[A-Za-z0-9]{20}|xox[bapos]-|BEGIN [A-Z ]*PRIVATE KEY' . && \
	  echo "scrub meters GREEN" && git add -A && \
	  git -c user.name="Gene Kim" -c user.email="genek@itrevolution.com" \
	    commit -m "Public snapshot refresh from $$(git -C $(CURDIR) rev-parse --short origin/staging)" && \
	  echo "committed — push with: git -C ~/src.local/curtaincall-cfp-public push"

# EMERGENCY LOCAL CI (Gene, 2026-08-17, GitHub Actions major outage):
# simulate the CI test job in /tmp from the pinned staging tip — clean clone,
# same two kaocha invocations as .github/workflows/build-and-deploy.yml.
# Verdict lands in /tmp/ci-local/<sha>/VERDICT (GREEN/RED). Deploy remains a
# separate explicit step: make deploy-staging, then the guarded promote.
ci-local:
	@SHA=$$(git rev-parse origin/staging); DIR=/tmp/ci-local/$$SHA; \
	rm -rf $$DIR && mkdir -p $$DIR && \
	git clone -q --no-local . $$DIR/repo && \
	git -C $$DIR/repo checkout -q $$SHA && \
	mkdir -p $$DIR/repo/.clj-kondo && \
	cd $$DIR/repo && \
	( bin/kaocha unit && bin/kaocha ci --fail-fast ) > $$DIR/ci.log 2>&1 && \
	  echo "GREEN $$SHA" > $$DIR/VERDICT || echo "RED $$SHA" > $$DIR/VERDICT; \
	cat $$DIR/VERDICT; tail -1 $$DIR/ci.log
