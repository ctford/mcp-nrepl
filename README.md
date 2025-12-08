# MCP-nREPL

[![CI](https://github.com/ctford/mcp-nrepl/actions/workflows/ci.yml/badge.svg)](https://github.com/ctford/mcp-nrepl/actions/workflows/ci.yml)

This is a minimal, fast [Model Context Protocol](https://modelcontextprotocol.io/) (MCP) server implementation for nREPL using Babashka. That means your coding assistant can use the REPL like you would.

**Related Projects**: For more ambitious REPL-driven development experiences, check out Bruce Hauman's [Clojure MCP](https://github.com/bhauman/clojure-mcp) (full-featured MCP with structural editing, linting, and formatting) and [clojure-mcp-light](https://github.com/bhauman/clojure-mcp-light) (CLI tools for paren repair and LLM-friendly output).

## Table of Contents

- [Features](#features)
- [Installation](#installation)
- [Protocol Support](#protocol-support)
- [Security Considerations](#security-considerations)
- [Development](#development)
- [Built With AI](#built-with-ai)
- [Contributing](#contributing)
- [License](#license)

## Features

- **Bridge Mode** (`--bridge`): Connects to an external nREPL server, such as you might get by using `lein repl` in your project
- **Server Mode** (`--server`): Provides an embedded nREPL server, so you don't need a project to give an AI assistant the ability to execute Clojure
- **Eval Mode** (`--eval`): One-shot evaluation from the command line, works with either Bridge or Server mode, no MCP configuration needed
- **Fast Execution**: ~30ms per evaluation

Use `bb mcp-nrepl.bb --help` to see all command-line options and usage examples.

## Installation

For now, MCP-nREPL is distributed as a single, unversioned Babashka script with zero dependencies (outside of what Babashka already provides). You should be able to understand it, hack it, copy it around and check it into your dotfiles repo.

### 1. Prerequisites

- **Babashka**: Install via `brew install borkdude/brew/babashka` (macOS) or see [babashka.org](https://babashka.org/) for other platforms

### 2. Download mcp-nrepl.bb

Download the script to a permanent location:

```bash
# Create a directory for MCP servers (or use any location you prefer)
mkdir -p ~/.mcp-servers
cd ~/.mcp-servers

# Download the script
curl -O https://raw.githubusercontent.com/ctford/mcp-nrepl/main/mcp-nrepl.bb
```

### 3a. Option one: Configure Bridge Mode e.g. Claude Code

Create a `.mcp.json` file in your Clojure project root:

```json
{
  "mcpServers": {
    "mcp-nrepl": {
      "type": "stdio",
      "command": "bb",
      "args": ["/Users/yourname/.mcp-servers/mcp-nrepl.bb", "--bridge"],
      "_comment": "Replace /Users/yourname with your actual home directory path"
    }
  }
}
```

Note: The working directory is your project root, so `.nrepl-port` is automatically discovered (no need for `--nrepl-port` in typical workflows).

**Start nREPL server**: In your project directory, start an nREPL server:
```bash
cd /path/to/your/clojure/project
lein repl           # Leiningen
# or: bb nrepl-server   # Babashka
# or: clj -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.0.0"}}}' -X nrepl.cmdline/server
```

This creates a `.nrepl-port` file that mcp-nrepl will use to connect.

**Restart Claude Code** to load the MCP server.

### 3b. Option two: Configure Server Mode e.g. Claude Desktop

If you prefer to use Claude Desktop instead, edit your Claude Desktop configuration file:
- **macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`
- **Windows**: `%APPDATA%\Claude\claude_desktop_config.json`

Add the mcp-nrepl server with embedded server mode:

```json
{
  "mcpServers": {
    "mcp-nrepl": {
      "type": "stdio",
      "command": "bb",
      "args": ["/Users/yourname/.mcp-servers/mcp-nrepl.bb", "--server"],
      "_comment": "Replace /Users/yourname with your actual home directory path"
    }
  }
}
```

Note: `"type": "stdio"` is optional (stdio is the default) but included for clarity.

**Restart Claude Desktop** to load the MCP server.

### 3c. Option three: Configure Eval Mode

For quick one-shot evaluations without MCP configuration, use the `--eval` flag directly from the command line. This mode works with either Bridge or Server mode.

**With external nREPL (Bridge Mode):**
```bash
# Start your project's nREPL server first
lein repl  # or: bb nrepl-server

# Then evaluate code (auto-discovers .nrepl-port)
bb mcp-nrepl.bb --eval "(+ 1 2 3)"
# Output: 6

# Or specify port explicitly
bb mcp-nrepl.bb --nrepl-port 1667 --eval "(+ 1 2 3)"
```

**With embedded nREPL (Server Mode):**
```bash
# No external server needed - starts embedded nREPL automatically
bb mcp-nrepl.bb --server --eval "(+ 1 2 3)"
# Output: 6
```

### 4. Verify It's Working

Try asking Claude:
> "Can you evaluate (+ 1 2 3) in my Clojure REPL?"

Claude should be able to connect to your nREPL session and execute code.

## Protocol Support

### MCP Methods Implemented

- **initialize**: Protocol handshake that establishes nREPL connection
- **tools/list**: Returns available tools
- **tools/call**: Executes tool operations
- **prompts/list**: Returns available prompts
- **prompts/get**: Retrieves a specific prompt with its messages
- **resources/list**: Returns available resources (none implemented)
- **resources/read**: Reads resource data (not applicable)

Use `bb mcp-nrepl.bb --describe` to see all tools, prompts, and resources with their complete parameter schemas without needing to set up MCP.

### Available Tools

MCP-nREPL provides 12 tools:

**Code Execution:**
- **eval-clojure** - Evaluate Clojure code expressions
  - Parameters:
    - `code` (string, required) - The Clojure code to evaluate
    - `timeout-ms` (number, optional) - Timeout in milliseconds (default: 2000ms)
  - Returns: Evaluation result, output, and any errors
  - Increase timeout for long-running operations like complex computations
- **load-file** - Load and evaluate a Clojure file
  - Parameters:
    - `file-path` (string, required) - Path to the Clojure file to load
    - `timeout-ms` (number, optional) - Timeout in milliseconds (default: 2000ms)
  - Returns: Success message or evaluation output
  - Validates file existence before loading
  - Increase timeout for large files
- **set-namespace** - Switch to a different namespace
  - Parameters: `namespace` (string, required)
  - Returns: Confirmation of namespace switch
  - Creates namespace if it doesn't exist

**Macro Expansion:**
- **macroexpand-all** - Fully expand all macros in Clojure code
  - Parameters: `code` (string, required)
  - Returns: Completely expanded form using `clojure.walk/macroexpand-all`
  - Useful for understanding complete macro transformations
- **macroexpand-1** - Expand a Clojure macro one step
  - Parameters: `code` (string, required)
  - Returns: Result of a single macro expansion using `macroexpand-1`
  - Useful for understanding macro transformations incrementally

**Documentation:**
- **doc** - Get documentation for a Clojure symbol
  - Parameters: `symbol` (string, required)
  - Returns: Documentation text or "No documentation found"
- **source** - Get source code for a Clojure symbol
  - Parameters: `symbol` (string, required)
  - Returns: Source code or "No source found"
- **apropos** - Search for symbols matching a pattern
  - Parameters: `query` (string, required)
  - Returns: List of matching symbols or "No matches found"

**Session Introspection:**
- **vars** - List currently defined variables in a namespace
  - Parameters: `namespace` (string, optional) - The namespace to list vars from
  - Defaults to current namespace if not specified
  - Returns: JSON array of variable names
- **loaded-namespaces** - List all loaded namespaces
  - Returns: JSON array of namespace names
- **current-namespace** - Get the current default namespace
  - Returns: Current namespace name

**Server Management:**
- **restart-nrepl-server** - Restart the embedded nREPL server (--server mode only)
  - No parameters required
  - Stops the current server, kills any stuck threads, and starts a fresh server on a new port
  - Use this to recover from infinite sequences, stuck computations, or other hangs
  - Note: vars and namespaces persist (they're in the process memory)
  - Only works in --server mode (not --bridge mode)
  - Returns: Success message

### Available Prompts

MCP-nREPL provides 5 prompts that guide AI assistants through common REPL workflows:

- **explore-namespace** - Guide users through exploring their current REPL session state
  - Arguments: None
  - Helps discover what namespace you're in, what vars are defined, and their documentation

- **define-and-test** - Guide proper function definition and testing workflow
  - Arguments:
    - `function-name` (string, required) - The name of the function to define
    - `function-code` (string, required) - The complete function definition code
  - Ensures functions are defined and tested before use

- **load-and-explore** - Guide proper file loading and namespace exploration
  - Arguments:
    - `file-path` (string, required) - Path to the Clojure file to load
  - Walks through loading a file, switching namespaces, and exploring what's available

- **debug-error** - Guide systematic troubleshooting of errors
  - Arguments:
    - `error-code` (string, required) - The code that is producing an error
  - Provides structured approach to understanding and fixing errors

- **search-and-learn** - Guide discovering and learning about Clojure functions
  - Arguments:
    - `search-term` (string, required) - The term to search for in symbol names
  - Helps discover relevant functions and understand their usage

## Security Considerations

**MCP-nREPL grants full REPL access to AI assistants.** Before using this tool, understand the security implications:

- **Arbitrary Code Execution**: AI assistants can evaluate any Clojure code in your nREPL session with your user permissions
- **File System Access**: Code can read, write, and delete files accessible to your user account
- **Network Access**: Code can make network requests and interact with external services
- **Environment Access**: Code can read environment variables and system properties

**Recommendations**:
- Only use MCP-nREPL with trusted AI assistants and MCP clients
- Review code suggestions before accepting them, especially system operations
- Consider running in isolated environments (containers, VMs) for sensitive work
- Be cautious when working with production credentials or sensitive data
- The nREPL session shares state - one evaluation affects subsequent ones

**This tool is intended for development workflows.** It provides the same level of access you have when typing at a REPL prompt.

---

**For Development**: If you want to contribute or run tests, clone the full repository instead of just downloading the script. For convenient development, you can point Claude directly at the file location in your checkout directory.

## Development

This section is for developers who have cloned the repository and want to test mcp-nrepl locally.

### 1. Start an nREPL Server

Start a Babashka nREPL server for testing:

```bash
bb nrepl-server
```

This will start a Babashka nREPL server and write the port to `.nrepl-port`.

### 2. One-Shot Eval Mode (Fastest)

Evaluate Clojure code directly from the command line:

```bash
bb mcp-nrepl.bb --eval "(+ 1 2 3)"
# Output: 6

bb mcp-nrepl.bb -e "(str \"Hello\" \" \" \"World\")"
# Output: "Hello World"
```

### 3. MCP Server Mode

Run as an MCP server for AI assistants and other MCP clients:

```bash
bb mcp-nrepl.bb --bridge
```

The server will read from stdin and write to stdout using JSON-RPC 2.0 protocol.

Note: `--bridge` is recommended for clarity, but the flag is optional (bridge mode is the default).

Example initialization message:
```json
{"jsonrpc":"2.0","method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{}},"id":1}
```

Example tool call:
```json
{"jsonrpc":"2.0","method":"tools/call","params":{"name":"eval-clojure","arguments":{"code":"(+ 1 2 3)"}},"id":2}
```

### Testing

#### Running Tests

Run the complete test suite (unit + E2E):
```bash
./run-tests.sh
```

The test suite includes:
- **Unit Tests** (~1 second) - Pure functions with no side effects: MCP handlers, data transformation, error builders
- **End-to-End Tests** (~5 seconds) - Full integration: MCP protocol, nREPL eval, resources, and one-shot eval mode
- **Misuse Tests** (~3 seconds) - Error handling: malformed JSON, invalid requests, missing nREPL server, malformed Clojure code
- **Performance Tests** (~1 second) - Timing validation: one-shot eval mode completes under 200ms threshold

All test suites are written in Babashka for consistency and maintainability.

#### Git Hooks

To ensure code quality, install the pre-commit hook that runs tests before each commit:

```bash
./install-git-hooks.sh
```

After installation, tests will run automatically before every commit. The hook is version controlled in `.githooks/pre-commit`, so any updates automatically apply without re-installation.

To skip the hook for a specific commit (not recommended):
```bash
git commit --no-verify
```

## Built With AI

MCP-nREPL was built with [Claude Code](https://claude.com/claude-code), an AI-powered coding assistant. In fact, I adopted a creative constraint that I would never edit by hand. If you're looking for an MCP server, I assume you're comfortable with AI assistance in development.

## Contributing

MCP-nREPL is developed under a creative constraint where all code is written by Claude Code. See [CONTRIBUTING.md](CONTRIBUTING.md) for details on how to contribute ideas and proof-of-concept PRs.

Design constraints: zero dependencies, REPL access only, minimal/fast/readable code.

## License

Eclipse Public License v1.0 - See LICENSE file for details