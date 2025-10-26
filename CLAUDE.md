# MCP-nREPL Development Guide

This document provides instructions for developing and testing the MCP-nREPL bridge.

## Starting the nREPL Server

To start a Babashka nREPL server for testing:

```bash
./start-nrepl.sh
```

This script will:
- Start a Babashka nREPL server on `127.0.0.1:1667`
- Write the port number to `.nrepl-port`
- Run in the foreground until stopped with Ctrl+C

The server must be running before testing the MCP-nREPL bridge functionality.

## Running Tests

To run the test suite:

```bash
./run-tests.sh
```

This will execute all unit tests in `test/mcp_nrepl_test.bb` using Babashka.

## Dependencies

No external dependencies required! The script uses only built-in Babashka libraries:
- `cheshire.core` - JSON parsing and generation (built-in)
- `bencode.core` - Bencode encoding/decoding for nREPL communication (built-in)

## Using MCP-nREPL

The script supports flexible port configuration:

```bash
# Use command-line argument
./mcp-nrepl.bb --nrepl-port 1667

# Use .nrepl-port file (fallback)
echo "1667" > .nrepl-port
./mcp-nrepl.bb

# Show help
./mcp-nrepl.bb --help
```

For quick evaluation:
```bash
./eval-clojure.sh "(+ 1 2 3)"
```

## Project Structure

- `mcp-nrepl.bb` - Main MCP-nREPL bridge implementation (executable)
- `eval-clojure.sh` - Convenience script for command-line evaluation
- `test/mcp_nrepl_test.bb` - Test suite
- `start-nrepl.sh` - Script to start nREPL server
- `run-tests.sh` - Script to run tests

## Performance

MCP-nREPL is significantly faster than traditional Clojure tooling:
- ~0.4s per evaluation (vs ~3.5s for lein repl :connect)
- ~9x faster than Leiningen-based evaluation
- Minimal overhead over direct Babashka execution