# MCP-nREPL

A minimal, fast Model Context Protocol (MCP) server implementation for nREPL using Babashka.

## Overview

This project provides a bridge between the Model Context Protocol and nREPL, allowing AI assistants and other MCP clients to execute Clojure code through an existing nREPL server. The implementation is designed to be minimal, fast, and readable while providing robust functionality.

## Features

- **Minimal Dependencies**: Single Babashka script with no external dependencies
- **Fast Startup**: Minimal overhead and quick initialization
- **MCP Compliant**: Implements core MCP protocol for tool integration
- **nREPL Integration**: Connects to existing nREPL servers via TCP
- **Auto-Discovery**: Reads nREPL port from `.nrepl-port` file
- **Comprehensive Testing**: Unit tests for message translation and protocol handling

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

### 2. Run the MCP Server

```bash
bb mcp-nrepl.bb
```

The server will read from stdin and write to stdout using JSON-RPC 2.0 protocol.

### 3. Test with a Simple MCP Client

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

Execute the test suite:

```bash
./run-tests.sh
```

Or directly with Babashka:

```bash
bb test/mcp_nrepl_test.bb
```

### Project Structure

```
mcp-nrepl/
├── mcp-nrepl.bb          # Main MCP server implementation
├── start-nrepl.sh        # Helper script to start nREPL for testing
├── run-tests.sh          # Test runner script
├── test/
│   └── mcp_nrepl_test.bb # Comprehensive unit tests
├── README.md             # This file
└── .gitignore            # Git ignore patterns
```

## Protocol Support

### MCP Methods Implemented

- **initialize**: Protocol handshake and capability negotiation
- **tools/list**: Returns available tools (eval-clojure)
- **tools/call**: Executes Clojure code via nREPL

### Tool: eval-clojure

Evaluates Clojure code using the connected nREPL server.

**Parameters:**
- `code` (string, required): The Clojure code to evaluate

**Returns:**
- Evaluation result, output, and any errors from nREPL

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
- **Basic Tools**: Currently only provides code evaluation
- **Synchronous**: No async operation support

## Contributing

This is a minimal implementation focused on core functionality. Contributions should maintain the simplicity and readability goals of the project.

## License

MIT License