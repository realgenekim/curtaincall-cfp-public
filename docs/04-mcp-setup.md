# MCP (Model Context Protocol) Setup Guide

This guide shows how to set up Clojure MCP for enhanced Claude Code integration.

## What is MCP?

**Model Context Protocol (MCP)** allows Claude Code to interact with your Clojure project using specialized tools:

- ✅ **Eval code** in your running REPL
- ✅ **Look up function definitions** from any namespace
- ✅ **Navigate project structure** efficiently
- ✅ **Read/edit code** with Clojure awareness

This makes Claude much more effective at helping with Clojure development.

## Prerequisites

1. **Clojure MCP must be installed in your global Clojure config** (`~/.clojure/deps.edn`)
2. **Claude Code CLI** (`claude` command available)
3. **nREPL running** in your project

## Step 1: Install Clojure MCP Globally

Add to `~/.clojure/deps.edn`:

```clojure
{:aliases
 {:mcp {:extra-deps {io.github.cldwalker/clojure-mcp
                     {:git/sha "eae7b5e1869be9d8ea08d3d6dfb87e3afd4ce5f4"}}
        :exec-fn clojure-mcp.core/start
        :exec-args {:port nil}}}}
```

**Note:** The `:mcp` alias is in your global config, NOT in this project's `deps.edn`. This allows you to use the same MCP setup across all your Clojure projects.

### Verify Installation

```bash
clojure -X:mcp :port 12345
# Should start without errors
```

Press `Ctrl+C` to stop it.

## Step 2: Start nREPL in Your Project

```bash
cd /path/to/your/project
make nrepl
```

This creates a `.nrepl-port` file with the nREPL port number.

**Example `.nrepl-port` contents:**
```
54321
```

## Step 3: Configure MCP in Claude Code

```bash
make mcp-configure
```

This runs:
```bash
claude mcp add clojure-mcp -- /bin/sh -c 'PORT=$(cat /path/to/project/.nrepl-port); cd /path/to/project && clojure -X:mcp:dev:test :port $PORT'
```

**What this does:**
1. Reads the nREPL port from `.nrepl-port`
2. Changes to your project directory
3. Starts Clojure MCP with the `:mcp`, `:dev`, and `:test` aliases
4. Connects to your running nREPL server

## Step 4: Verify MCP is Working

### Test MCP Server Manually

```bash
make mcp-run
```

You should see:
```
🚀 Starting Clojure MCP server...
   Reading port from: /path/to/project/.nrepl-port
Clojure MCP server running on port 54321...
```

Press `Ctrl+C` to stop it.

### Test in Claude Code

Ask Claude:

```
Can you eval (+ 1 2 3) in my Clojure REPL?
```

Claude should use the MCP tool to eval the code and return `6`.

## Usage Examples

Once MCP is configured, Claude can:

### 1. Eval Code

**You:** "What's the result of `(map inc [1 2 3])` in my REPL?"

**Claude:** Uses MCP eval tool → returns `(2 3 4)`

### 2. Look Up Definitions

**You:** "Show me the definition of the `greet` function"

**Claude:** Uses MCP to find `greet` in your project namespaces

### 3. Navigate Namespaces

**You:** "What namespaces are in my project?"

**Claude:** Lists all namespaces from `src/` and `test/`

### 4. Test Code Quickly

**You:** "Test if `(parse-date \"2024-01-15\")` works"

**Claude:** Evals the code and shows the result

## Project-Specific MCP Configuration

The `mcp-configure` command uses the **current project directory** dynamically:

```makefile
mcp-configure:
	claude mcp add clojure-mcp -- /bin/sh -c 'PORT=$$(cat $(shell pwd)/.nrepl-port); cd $(shell pwd) && clojure -X:mcp:dev:test :port $$PORT'
```

**This means:**
- Each project can have its own MCP configuration
- The configuration automatically uses the correct directory and port
- You can switch between projects without reconfiguring MCP

## Multiple Projects

To use MCP with multiple projects:

1. **Start nREPL in project A:**
   ```bash
   cd ~/projects/project-a
   make nrepl
   ```

2. **Configure MCP for project A:**
   ```bash
   make mcp-configure
   ```

3. **Switch to project B:**
   ```bash
   cd ~/projects/project-b
   make nrepl
   ```

4. **Reconfigure MCP for project B:**
   ```bash
   make mcp-configure
   ```

**Or:** Keep both nREPL servers running on different ports and configure MCP for the project you're currently working on.

## Troubleshooting

### Problem: `claude mcp add` fails with "command not found"

**Solution:** Install Claude Code CLI:
```bash
# Follow instructions at https://claude.ai/code
```

### Problem: MCP can't connect to nREPL

**Check:**
1. Is nREPL running? → `cat .nrepl-port` should show a port number
2. Can you connect manually? → `telnet localhost $(cat .nrepl-port)`
3. Are you in the right directory? → `pwd` should show your project path

**Fix:**
```bash
# Restart nREPL
pkill -f "clojure.*nrepl"
make nrepl

# Reconfigure MCP
make mcp-configure
```

### Problem: MCP uses wrong project

MCP remembers the last configuration. Reconfigure it:

```bash
cd /path/to/correct/project
make mcp-configure
```

### Problem: "No such alias: :mcp"

The `:mcp` alias is in `~/.clojure/deps.edn`, not your project's `deps.edn`.

**Check:**
```bash
cat ~/.clojure/deps.edn | grep -A 5 ":mcp"
```

If it's missing, add it (see Step 1).

### Problem: MCP works but can't find my namespaces

Make sure you're using the `:dev` and `:test` aliases:

```bash
# This is what mcp-configure does:
clojure -X:mcp:dev:test :port $(cat .nrepl-port)
```

This adds `dev/` and `test/` to the classpath so MCP can see all your code.

## Removing MCP

```bash
make mcp-remove
```

This removes the MCP server from Claude Code's configuration.

## Advanced: Multiple MCP Servers

You can configure multiple MCP servers (e.g., Clojure + Chrome DevTools):

```bash
# Add Clojure MCP
make mcp-configure

# Add Chrome DevTools MCP
claude mcp add chrome-devtools npx -- -y chrome-devtools-mcp@latest --isolated=true
```

Claude can now use both!

## How MCP Works (Technical Details)

```
┌─────────────┐         ┌──────────────┐         ┌──────────────┐
│ Claude Code │ ◄─────► │ Clojure MCP  │ ◄─────► │ nREPL Server │
│             │  JSON   │   Server     │  nREPL  │ (your REPL)  │
└─────────────┘         └──────────────┘         └──────────────┘
```

1. **Claude** sends MCP requests (e.g., "eval this code")
2. **Clojure MCP** translates to nREPL commands
3. **nREPL** executes in your running REPL
4. **Results** flow back to Claude

This happens automatically when you use this template!

## Resources

- [Clojure MCP GitHub](https://github.com/cldwalker/clojure-mcp)
- [MCP Specification](https://modelcontextprotocol.io/)
- [nREPL Documentation](https://nrepl.org/)

## Next Steps

- Try asking Claude to eval code in your REPL
- Use Claude to explore your project structure
- Ask Claude to explain unfamiliar functions using MCP lookups
