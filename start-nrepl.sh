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


echo "Checking for existing nREPL servers..."

# Check if there's already a working nREPL server
if existing_port=$(detect_existing_nrepl); then
    echo "Found existing nREPL server on port: $existing_port"
    echo "$existing_port" > "$PORT_FILE"
    echo "Port written to: $PORT_FILE"
    echo "nREPL server is ready for testing (reusing existing server)"
    exit 0
else
    echo "No existing nREPL server found. Starting new Babashka nREPL server..."
    
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
fi