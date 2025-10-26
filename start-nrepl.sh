#!/bin/bash

# Simple script to start an nREPL server for testing MCP-nREPL
# Starts a Babashka nREPL server and writes the port to .nrepl-port

set -e

PORT_FILE=".nrepl-port"
PID_FILE=".nrepl-pid"

# Function to cleanup on exit
cleanup() {
    echo "Cleaning up..."
    if [ -f "$PID_FILE" ]; then
        PID=$(cat "$PID_FILE")
        if kill -0 "$PID" 2>/dev/null; then
            echo "Stopping nREPL server (PID: $PID)"
            kill "$PID"
        fi
        rm -f "$PID_FILE"
    fi
    rm -f "$PORT_FILE"
    exit 0
}

# Set up signal handlers
trap cleanup SIGINT SIGTERM EXIT

# Clean up any existing files
rm -f "$PORT_FILE" "$PID_FILE"

echo "Starting Babashka nREPL server..."

# Start nREPL server in background and capture its PID
bb nrepl-server > /tmp/nrepl-output.log 2>&1 &
NREPL_PID=$!

# Save the PID
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
    else
        echo "Failed to extract port from nREPL output"
        cat /tmp/nrepl-output.log
        exit 1
    fi
else
    echo "Failed to find nREPL output log"
    exit 1
fi

echo "nREPL server is ready for testing"
echo "Press Ctrl+C to stop the server"

# Wait for the nREPL process to finish or be interrupted
wait "$NREPL_PID"