# MCP-nREPL

A minimal, fast Model Context Protocol (MCP) server implementation for nREPL using Babashka. That means your coding assistant can use the REPL like you would.

## Overview

This project provides a bridge between the Model Context Protocol and nREPL, allowing AI assistants and other MCP clients to execute Clojure code through an existing nREPL server. The implementation is designed to be minimal, fast, and readable while providing robust functionality.

## Features

- **Dual Mode Operation**: MCP server mode + direct eval mode (`--eval` flag)
- **Minimal Dependencies**: Single Babashka script with no external dependencies
- **Fast Execution**: ~28ms per evaluation in direct mode
- **MCP Compliant**: Implements core MCP protocol with tools and resources
- **nREPL Integration**: Connects to existing nREPL servers (Babashka, Leiningen, Clojure CLI)
- **Auto-Discovery**: Reads nREPL port from `.nrepl-port` file
- **Rich Tooling**: Code eval, file loading, namespace switching, symbol search
- **Session Introspection**: Resources for vars, namespaces, docs, and source

## How It Works

MCP-nREPL acts as a bridge between the Model Context Protocol and your nREPL session:

- **Direct REPL Access**: Provides MCP clients (like Claude) with the ability to evaluate Clojure code in your running nREPL session
- **Shared Session**: All evaluations happen in the same nREPL session, maintaining state between calls
- **Development Tool**: Designed for interactive development workflows where you want AI assistance with Clojure code

This is equivalent to giving an MCP client access to your REPL prompt - it can define functions, load files, switch namespaces, and evaluate any Clojure expression you could run yourself.

## Installation

### 1. Prerequisites

- **Babashka**: Install via `brew install borkdude/brew/babashka` (macOS) or see [babashka.org](https://babashka.org/) for other platforms
- **nREPL Server**: Have an nREPL server running in your project (Leiningen, Babashka, Clojure CLI, etc.)

### 2. Download mcp-nrepl.bb

Download the script to a permanent location:

```bash
# Create a directory for MCP servers (or use any location you prefer)
mkdir -p ~/.mcp-servers
cd ~/.mcp-servers

# Download the script
curl -O https://raw.githubusercontent.com/ctford/mcp-nrepl/main/mcp-nrepl.bb
```

### 3. Configure Claude Desktop (for example)

Edit your Claude Desktop configuration file:
- **macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`
- **Windows**: `%APPDATA%\Claude\claude_desktop_config.json`

Add the mcp-nrepl server:

```json
{
  "mcpServers": {
    "mcp-nrepl": {
      "command": "bb",
      "args": ["/Users/yourname/.mcp-servers/mcp-nrepl.bb"],
      "_comment": "Replace /Users/yourname with your actual home directory path"
    }
  }
}
```

**Port Configuration**: By default, mcp-nrepl reads the port from `.nrepl-port` in your working directory. To specify a different port, add `"--nrepl-port", "1667"` to the args array.

### 4. Start nREPL and Restart Claude

Start an nREPL server in your project:
```bash
cd /path/to/your/clojure/project
lein repl           # Leiningen
# or: bb nrepl-server   # Babashka
# or: clj -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.0.0"}}}' -X nrepl.cmdline/server
```

Restart Claude Desktop to load the MCP server.

### 5. Verify It's Working

In Claude Desktop, try asking:
> "Can you evaluate (+ 1 2 3) in my Clojure REPL?"

Claude should be able to connect to your nREPL session and execute code.

---

**For Development**: If you want to contribute or run tests, clone the full repository instead of just downloading the script.

## Quick Start (Development & Testing)

This section is for developers who have cloned the repository and want to test mcp-nrepl locally.

### 1. Start an nREPL Server

Use the provided helper script to start a test nREPL server:

```bash
./start-nrepl.sh
```

This will start a Babashka nREPL server and write the port to `.nrepl-port`.

### 2a. Start nREPL Server (Optional)

For interactive development, start a Babashka nREPL server:

```bash
bb nrepl-server
```

The server writes its port to `.nrepl-port` for tools to auto-discover.

### 2b. Direct Eval Mode (Fastest)

Evaluate Clojure code directly from the command line:

```bash
bb mcp-nrepl.bb --eval "(+ 1 2 3)"
# Output: 6

bb mcp-nrepl.bb -e "(str \"Hello\" \" \" \"World\")"
# Output: "Hello World"
```

### 2c. MCP Server Mode

Run as an MCP server for AI assistants and other MCP clients:

```bash
bb mcp-nrepl.bb
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

Run the complete test suite (unit + E2E):
```bash
./run-tests.sh
```

The test suite includes:
- **Unit Tests** (~1 second) - Pure functions with no side effects: MCP handlers, data transformation, error builders
- **End-to-End Tests** (~5 seconds) - Full integration: MCP protocol, nREPL eval, resources, and direct eval mode

Both test suites are written in Babashka for consistency and maintainability.

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

## Limitations

- **Single Session**: No support for parallel nREPL sessions
- **Local Only**: Only connects to localhost nREPL servers
- **Synchronous**: No async operation support
- **Basic nREPL**: Uses core eval operations, not advanced middleware ops like `info` or `complete`

## Contributing

This is a minimal implementation focused on core functionality. Contributions should maintain the simplicity and readability goals of the project.

## License

Eclipse Public License v1.0 - See LICENSE file for details