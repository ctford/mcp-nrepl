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

Dependencies are managed via `bb.edn`:
- `org.clojure/data.json` - JSON parsing and generation
- `bencode/bencode` - Bencode encoding/decoding for nREPL communication

## Project Structure

- `mcp-nrepl.bb` - Main MCP-nREPL bridge implementation
- `test/mcp_nrepl_test.bb` - Test suite
- `start-nrepl.sh` - Script to start nREPL server
- `run-tests.sh` - Script to run tests
- `bb.edn` - Babashka dependencies configuration