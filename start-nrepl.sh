#!/bin/bash

# Script to start or reuse an nREPL server for testing MCP-nREPL
# Detects existing nREPL servers and reuses them, or starts a new one if needed

set -e

PORT_FILE=".nrepl-port"
PID_FILE=".nrepl-pid"

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --help)
            echo "Usage: $0 [--help]"
            echo "  --help          Show this help message"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
done



# Check if we have an existing .nrepl-port file - just trust it
if [ -f "$PORT_FILE" ]; then
    existing_port=$(cat "$PORT_FILE")
    if [ -n "$existing_port" ]; then
        echo "Found existing .nrepl-port file with port: $existing_port"
        echo "Assuming nREPL server is running (tests will fail fast if not)"
        exit 0
    fi
fi

echo "No .nrepl-port file found. Starting new Babashka nREPL server..."

# Clean up any existing files
rm -f "$PORT_FILE" "$PID_FILE"

# Start nREPL server in background and capture its PID
bb nrepl-server > /tmp/nrepl-output.log 2>&1 &
NREPL_PID=$!

# Save the PID for reference
echo "$NREPL_PID" > "$PID_FILE"

echo "nREPL server started with PID: $NREPL_PID"

# Wait a moment for the server to start up
sleep 2

# Extract port from the log output
if [ -f /tmp/nrepl-output.log ]; then
    # Look for port in the log file (format: "127.0.0.1:1667")
    PORT=$(grep -o "127\.0\.0\.1:[0-9]*" /tmp/nrepl-output.log | head -1 | cut -d':' -f2)
    
    if [ -n "$PORT" ]; then
        echo "$PORT" > "$PORT_FILE"
        echo "nREPL server listening on port: $PORT"
        echo "Port written to: $PORT_FILE"
        echo "nREPL server is ready for testing"
        exit 0
    else
        echo "Failed to extract port from nREPL output"
        cat /tmp/nrepl-output.log
        exit 1
    fi
else
    echo "Failed to find nREPL output log"
    exit 1
fi