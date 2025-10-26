#!/bin/bash

# Script to evaluate Clojure code using mcp-nrepl
# Usage: ./eval-clojure.sh "(+ 1 2 3)"

set -e

if [ $# -ne 1 ]; then
    echo "Usage: $0 '<clojure-code>'"
    echo "Example: $0 '(+ 1 2 3)'"
    exit 1
fi

CLOJURE_CODE="$1"

# Create JSON messages for MCP protocol
INIT_MSG='{"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2024-11-05", "capabilities": {}, "clientInfo": {"name": "eval-script", "version": "1.0.0"}}}'

# Escape the Clojure code for JSON
ESCAPED_CODE=$(printf '%s\n' "$CLOJURE_CODE" | sed 's/\\/\\\\/g; s/"/\\"/g')
EVAL_MSG=$(printf '{"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": "eval-clojure", "arguments": {"code": "%s"}}}' "$ESCAPED_CODE")

# Send both messages to mcp-nrepl and extract the result
(echo "$INIT_MSG"; echo "$EVAL_MSG") | ./mcp-nrepl.bb | tail -1 | jq -r '.result.content[0].text // .error.message // "No result"'