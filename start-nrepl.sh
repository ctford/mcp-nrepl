#!/bin/bash

# Script to start or reuse an nREPL server for testing MCP-nREPL
# Detects existing nREPL servers and reuses them, or starts a new one if needed

set -e

PORT_FILE=".nrepl-port"
PID_FILE=".nrepl-pid"

# Function to test if a port is responsive
test_nrepl_connection() {
    local port=$1
    # Try to connect to the port using nc (netcat)
    if command -v nc >/dev/null 2>&1; then
        # Use netcat to test connection
        if echo "" | nc -w 1 localhost "$port" >/dev/null 2>&1; then
            return 0
        fi
    else
        # Fallback: just check if something is listening on the port
        if lsof -i ":$port" >/dev/null 2>&1; then
            return 0
        fi
    fi
    return 1
}

# Function to detect existing nREPL servers
detect_existing_nrepl() {
    # Look for processes listening on typical nREPL ports
    local lsof_output=$(lsof -i -P 2>/dev/null | grep LISTEN | grep -E ':(1667|7888|7889)' || true)
    
    if [ -n "$lsof_output" ]; then
        # Extract ports using sed to handle different lsof output formats
        local existing_ports=$(echo "$lsof_output" | sed -n 's/.*:\([0-9]*\) (LISTEN).*/\1/p' | sort -u)
        
        for port in $existing_ports; do
            if [ -n "$port" ] && test_nrepl_connection "$port"; then
                echo "$port"
                return 0
            fi
        done
    fi
    
    return 1
}

# Function to cleanup on exit (only if we started the server)
cleanup() {
    echo "Cleaning up..."
    if [ -f "$PID_FILE" ]; then
        PID=$(cat "$PID_FILE")
        if kill -0 "$PID" 2>/dev/null; then
            echo "Stopping nREPL server (PID: $PID)"
            kill "$PID"
        fi
        rm -f "$PID_FILE"
        rm -f "$PORT_FILE"
    fi
    exit 0
}

echo "Checking for existing nREPL servers..."

# Check if there's already a working nREPL server
if existing_port=$(detect_existing_nrepl); then
    echo "Found existing nREPL server on port: $existing_port"
    echo "$existing_port" > "$PORT_FILE"
    echo "Port written to: $PORT_FILE"
    echo "nREPL server is ready for testing (reusing existing server)"
    echo "Press Ctrl+C to exit (server will continue running)"
    
    # Wait for interrupt - don't set cleanup trap since we don't own the server
    trap 'echo "Exiting..."; exit 0' SIGINT SIGTERM
    
    # Keep script running until interrupted
    while true; do
        sleep 1
    done
else
    echo "No existing nREPL server found. Starting new Babashka nREPL server..."
    
    # Set up signal handlers for cleanup (only if we start the server)
    trap cleanup SIGINT SIGTERM EXIT
    
    # Clean up any existing files
    rm -f "$PORT_FILE" "$PID_FILE"
fi

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