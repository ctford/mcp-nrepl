# MCP-nREPL Development Guide

This document provides instructions for developing and testing the MCP-nREPL bridge.

## Quick Start

1. **Run all tests**: `./run-tests.sh` (automatically starts nREPL if needed)
2. **Evaluate code**: `bb mcp-nrepl.bb --eval "(+ 1 2 3)"`
3. **Start nREPL manually** (optional): `bb nrepl-server`

## Starting the nREPL Server

For development work outside of tests, start a Babashka nREPL server:

```bash
bb nrepl-server
```

The server will:
- Start on a random available port
- Write the port to `.nrepl-port` file
- Run in the foreground until stopped with Ctrl+C

The E2E tests automatically detect and reuse this server if running, or start their own temporary server on a random port.

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
- ✅ Tests connectionless eval mode and persistent connections
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

**Connectionless eval mode (--eval flag):**
```bash
# Start nREPL server first (or tests will auto-start)
bb nrepl-server &

# Connectionless evaluation (~28ms per call)
bb mcp-nrepl.bb --eval "(+ 1 2 3)"
bb mcp-nrepl.bb -e "(defn greet [name] (str \"Hello, \" name \"!\"))"
bb mcp-nrepl.bb -e "(greet \"World\")"
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
echo '{"jsonrpc": "2.0", "id": 5, "method": "resources/read", "params": {"uri": "clojure://symbols/apropos/map"}}' | bb mcp-nrepl.bb --nrepl-port 1667

# Get current namespace
echo '{"jsonrpc": "2.0", "id": 6, "method": "resources/read", "params": {"uri": "clojure://session/current-ns"}}' | bb mcp-nrepl.bb --nrepl-port 1667
```

## Tools and Resources

### Available Tools
MCP-nREPL provides three tools for interacting with the nREPL session:

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

### Available Resources
MCP-nREPL provides several resources for session introspection:

- **`clojure://session/vars`** - List currently defined variables in the session
- **`clojure://session/namespaces`** - List all loaded namespaces
- **`clojure://session/current-ns`** - Get the current default namespace
- **`clojure://doc/{symbol}`** - Get documentation for a symbol
- **`clojure://source/{symbol}`** - Get source code for a symbol
- **`clojure://symbols/apropos/{query}`** - Search for symbols matching a pattern

## Project Structure

- `mcp-nrepl.bb` - Main MCP-nREPL bridge implementation
- `test/unit_test.bb` - Pure function unit tests (Babashka)
- `test/e2e_test.bb` - End-to-end integration tests (Babashka, auto-starts nREPL)
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
# Quick eval to check connectivity
bb mcp-nrepl.bb --eval "(+ 1 1)"

# Test MCP protocol directly
echo '{"jsonrpc": "2.0", "id": 1, "method": "tools/list"}' | bb mcp-nrepl.bb --nrepl-port 1667
```

### Code Quality
- **Unit tests** verify pure function correctness
- **E2E tests** ensure real-world functionality  
- **Zero external dependencies** for easy distribution
- **some-> threading** eliminates nested when-let chains
- **Keyword destructuring** for cleaner state access