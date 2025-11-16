# MCP-nREPL

A minimal, fast Model Context Protocol (MCP) server implementation for nREPL using Babashka.

## Overview

This project provides a bridge between the Model Context Protocol and nREPL, allowing AI assistants and other MCP clients to execute Clojure code through an existing nREPL server. The implementation is designed to be minimal, fast, and readable while providing robust functionality.

## Features

- **Dual Mode Operation**: MCP server mode + direct eval mode (`--eval` flag)
- **Minimal Dependencies**: Single Babashka script with no external dependencies
- **Fast Execution**: ~28ms per evaluation in direct mode
- **MCP Compliant**: Implements core MCP protocol with tools and resources
- **nREPL Integration**: Connects to existing nREPL servers via TCP
- **Auto-Discovery**: Reads nREPL port from `.nrepl-port` file
- **Rich Tooling**: Code eval, file loading, namespace switching, symbol search
- **Session Introspection**: Resources for vars, namespaces, docs, and source
- **Comprehensive Testing**: Pure function unit tests + full E2E integration tests

## Requirements

- [Babashka](https://babashka.org/) installed and available as `bb`
- An nREPL server running locally (for actual usage)

## Quick Start

### 1. Start an nREPL Server

Use the provided helper script to start a test nREPL server:

```bash
./start-nrepl.sh
```

This will start a Babashka nREPL server and write the port to `.nrepl-port`.

### 2a. Direct Eval Mode (Fastest)

Evaluate Clojure code directly from the command line:

```bash
./mcp-nrepl.bb --eval "(+ 1 2 3)"
# Output: 6

./mcp-nrepl.bb -e "(str \"Hello\" \" \" \"World\")"
# Output: "Hello World"
```

### 2b. MCP Server Mode

Run as an MCP server for AI assistants and other MCP clients:

```bash
./mcp-nrepl.bb
```

The server will read from stdin and write to stdout using JSON-RPC 2.0 protocol.

Example initialization message:
```json
{"jsonrpc":"2.0","method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{}},"id":1}
```

Example tool call:
```json
{"jsonrpc":"2.0","method":"tools/call","params":{"name":"eval-clojure","arguments":{"code":"(+ 1 2 3)"}},"id":2}
```

## Development

### Running Tests

The project has two test suites:

**Unit Tests (Pure Functions, ~1 second)**:
```bash
./run-unit-tests.sh
```

Tests pure functions with no side effects - MCP handlers, data transformation, error builders.

**End-to-End Tests (Full Integration, ~5 seconds)**:
```bash
./run-e2e-test.sh
```

Tests complete workflows including MCP protocol, nREPL eval, resources, and direct eval mode.

### Project Structure

```
mcp-nrepl/
├── mcp-nrepl.bb          # Main MCP server implementation (executable)
├── start-nrepl.sh        # Helper script to start nREPL server
├── eval-clojure.sh       # Convenience wrapper for evaluation
├── run-unit-tests.sh     # Unit test runner
├── run-e2e-test.sh       # End-to-end test runner
├── test/
│   ├── unit_test.bb      # Pure function unit tests
│   └── mcp_nrepl_test.bb # Legacy tests (deprecated)
├── README.md             # User documentation
├── CLAUDE.md             # Development guide
└── .gitignore            # Git ignore patterns
```

## Protocol Support

### MCP Methods Implemented

- **initialize**: Protocol handshake and capability negotiation
- **tools/list**: Returns available tools
- **tools/call**: Executes tool operations
- **resources/list**: Returns available resources
- **resources/read**: Reads resource data

### Available Tools

**eval-clojure** - Evaluate Clojure code expressions
- Parameters: `code` (string, required)
- Returns: Evaluation result, output, and any errors

**load-file** - Load and evaluate a Clojure file
- Parameters: `file-path` (string, required)
- Returns: Success message or evaluation output
- Validates file existence before loading

**set-ns** - Switch to a different namespace
- Parameters: `namespace` (string, required)
- Returns: Confirmation of namespace switch
- Creates namespace if it doesn't exist

**apropos** - Search for symbols matching a pattern
- Parameters: `query` (string, required)
- Returns: List of matching symbols with fully-qualified names
- Searches both built-in and user-defined symbols

### Available Resources

**clojure://session/vars** - List currently defined variables in the session

**clojure://session/namespaces** - List all loaded namespaces

**clojure://session/current-ns** - Get the current default namespace

**clojure://doc/{symbol}** - Get documentation for a symbol (e.g., `clojure://doc/map`)

**clojure://source/{symbol}** - Get source code for a symbol (e.g., `clojure://source/map`)

## Architecture

The implementation consists of several key components:

1. **JSON-RPC Transport**: Handles stdin/stdout communication using newline-delimited JSON
2. **MCP Protocol Handler**: Implements core MCP methods and message routing
3. **nREPL Client**: Manages connection and communication with nREPL server using BEncode
4. **Message Translation**: Converts between MCP requests and nREPL operations

## Error Handling

- Graceful handling of nREPL connection failures
- Proper error responses for malformed requests
- Logging to stderr for debugging
- Timeout handling for nREPL operations

## Limitations

- **Single Session**: No support for parallel nREPL sessions
- **Local Only**: Only connects to localhost nREPL servers
- **Synchronous**: No async operation support
- **Basic nREPL**: Uses core eval operations, not advanced middleware ops like `info` or `complete`

## Contributing

This is a minimal implementation focused on core functionality. Contributions should maintain the simplicity and readability goals of the project.

## License

Eclipse Public License v1.0 - See LICENSE file for details