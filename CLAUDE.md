# MCP-nREPL Development Guide

This document provides instructions for developing and testing the MCP-nREPL bridge.

## Quick Start

1. **Run all tests**: `./run-tests.sh` (automatically starts nREPL if needed)
2. **Evaluate code**: `./eval-clojure.sh "(+ 1 2 3)"`
3. **Start nREPL manually** (optional): `./start-nrepl.sh`

## Starting the nREPL Server

To start or connect to a Babashka nREPL server:

```bash
./start-nrepl.sh
```

This script will:
- **Detect existing nREPL servers** on common ports (1667, 7888, 7889)
- **Reuse existing server** if found and responsive
- **Start new Babashka nREPL server** if none exists
- **Write the port number** to `.nrepl-port` 
- **Run in the foreground** until stopped with Ctrl+C

**Server Reuse Benefits:**
- Faster startup (no new process needed)
- Shared session state across tools
- Resource efficient development workflow

The server (existing or new) must be running before testing the MCP-nREPL bridge functionality.

## Running Tests

Run the complete test suite:
```bash
./run-tests.sh
```

The test suite includes:

### Unit Tests (Pure Functions, ~1 second)
- ✅ Pure functions only - no side effects, I/O, or state mutations
- ✅ Tests: MCP handlers, data transformation, error builders
- ✅ Perfect for rapid development feedback

### End-to-End Tests (Full Integration, ~5 seconds)
- ✅ Tests MCP protocol initialization
- ✅ Defines and invokes Clojure functions with `defn`
- ✅ Verifies error handling with real exceptions
- ✅ Tests file loading, namespace switching, symbol search
- ✅ Tests direct eval mode and persistent connections
- ✅ Colorized output with clear success/failure indicators

**Multi-Backend Testing:**
The E2E tests automatically work with any nREPL server:
```bash
# Test against Babashka (auto-detected)
bb nrepl-server localhost:1667 &
./run-tests.sh

# Test against Leiningen (auto-detected)
lein repl :headless :port 1667 &
./run-tests.sh

# Test against Clojure CLI (auto-detected)
clj -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.0.0"}}}' -X nrepl.cmdline/server :port 1667 &
./run-tests.sh
```

**Test Implementation:**
Both test suites are written in Babashka for consistency and maintainability:
- `test/unit_test.bb` - Pure function tests
- `test/e2e_test.bb` - Integration tests with automatic nREPL setup
  - Detects existing nREPL server (via `.nrepl-port`)
  - Starts new server on random port if needed
  - Zero bash dependencies - pure Babashka

## Dependencies

No external dependencies required! The script uses only built-in Babashka libraries:
- `cheshire.core` - JSON parsing and generation (built-in)
- `bencode.core` - Bencode encoding/decoding for nREPL communication (built-in)

## Usage

### Port Configuration
The script supports flexible port configuration:

```bash
# Command-line argument (preferred)
bb mcp-nrepl.bb --nrepl-port 1667

# .nrepl-port file (fallback)
echo "1667" > .nrepl-port
bb mcp-nrepl.bb

# Show help
bb mcp-nrepl.bb --help
```

### Quick Evaluation

**Direct eval mode (--eval flag, fastest):**
```bash
# Start server first
./start-nrepl.sh

# Direct evaluation (~28ms per call)
bb mcp-nrepl.bb --eval "(+ 1 2 3)"
bb mcp-nrepl.bb -e "(defn greet [name] (str \"Hello, \" name \"!\"))"
bb mcp-nrepl.bb -e "(greet \"World\")"
```

**Wrapper script (eval-clojure.sh):**
```bash
# Alternative using wrapper script
./eval-clojure.sh "(+ 1 2 3)"
./eval-clojure.sh "(defn greet [name] (str \"Hello, \" name \"!\"))"
./eval-clojure.sh "(greet \"World\")"
```

### MCP Protocol Usage
```bash
# Send JSON-RPC messages directly
echo '{"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2024-11-05", "capabilities": {}}}' | bb mcp-nrepl.bb --nrepl-port 1667

# Use the eval-clojure tool
echo '{"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": "eval-clojure", "arguments": {"code": "(+ 1 2 3)"}}}' | bb mcp-nrepl.bb --nrepl-port 1667

# Load a Clojure file
echo '{"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "load-file", "arguments": {"file-path": "src/my-file.clj"}}}' | bb mcp-nrepl.bb --nrepl-port 1667

# Switch namespace
echo '{"jsonrpc": "2.0", "id": 4, "method": "tools/call", "params": {"name": "set-ns", "arguments": {"namespace": "my.namespace"}}}' | bb mcp-nrepl.bb --nrepl-port 1667

# Search for symbols matching a pattern
echo '{"jsonrpc": "2.0", "id": 5, "method": "tools/call", "params": {"name": "apropos", "arguments": {"query": "map"}}}' | bb mcp-nrepl.bb --nrepl-port 1667

# Get current namespace
echo '{"jsonrpc": "2.0", "id": 6, "method": "resources/read", "params": {"uri": "clojure://session/current-ns"}}' | bb mcp-nrepl.bb --nrepl-port 1667
```

## Tools and Resources

### Available Tools
MCP-nREPL provides four tools for interacting with the nREPL session:

- **`eval-clojure`** - Evaluate Clojure code expressions
  - Parameters: `code` (string) - The Clojure code to evaluate
  - Returns: Evaluation result, output, and any errors

- **`load-file`** - Load and evaluate a complete Clojure file
  - Parameters: `file-path` (string) - Path to the Clojure file to load
  - Returns: Success message or evaluation output
  - Handles file existence validation

- **`set-ns`** - Switch to a different namespace in the REPL session
  - Parameters: `namespace` (string) - The namespace to switch to
  - Returns: Confirmation of namespace switch
  - Creates namespace if it doesn't exist

- **`apropos`** - Search for symbols matching a pattern
  - Parameters: `query` (string) - Search pattern to match against symbol names
  - Returns: List of matching symbols with their fully-qualified names
  - Searches both built-in and user-defined symbols

### Available Resources
MCP-nREPL provides several resources for session introspection:

- **`clojure://session/vars`** - List currently defined variables in the session
- **`clojure://session/namespaces`** - List all loaded namespaces
- **`clojure://session/current-ns`** - Get the current default namespace
- **`clojure://doc/{symbol}`** - Get documentation for a symbol
- **`clojure://source/{symbol}`** - Get source code for a symbol

## Project Structure

- `mcp-nrepl.bb` - Main MCP-nREPL bridge implementation
- `eval-clojure.sh` - Convenience script for command-line evaluation
- `test/unit_test.bb` - Pure function unit tests (Babashka)
- `test/e2e_test.bb` - End-to-end integration tests (Babashka)
- `start-nrepl.sh` - Script to start nREPL server
- `run-tests.sh` - Unified test runner (runs both unit and E2E tests)

## Performance

MCP-nREPL is significantly faster than traditional Clojure tooling:
- **~0.4s per evaluation** (vs ~3.5s for lein repl :connect)
- **~9x faster** than Leiningen-based evaluation  
- **Minimal overhead** over direct Babashka execution
- **Zero dependencies** - no external libs to download

## Development Tips

### Test-Driven Development
```bash
# Run complete test suite (unit + E2E)
./run-tests.sh

# Or run individual test files for faster feedback
bb test/unit_test.bb    # Just unit tests
bb test/e2e_test.bb     # Just E2E tests
```

### Debugging
```bash
# Check nREPL connectivity
echo "1667" > .nrepl-port
./eval-clojure.sh "(+ 1 1)"

# Test MCP protocol directly
echo '{"jsonrpc": "2.0", "id": 1, "method": "tools/list"}' | bb mcp-nrepl.bb --nrepl-port 1667
```

### Code Quality
- **Unit tests** verify pure function correctness
- **E2E tests** ensure real-world functionality  
- **Zero external dependencies** for easy distribution
- **some-> threading** eliminates nested when-let chains
- **Keyword destructuring** for cleaner state access