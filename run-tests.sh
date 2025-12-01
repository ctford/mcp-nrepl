#!/bin/bash

# Unified test runner for mcp-nrepl
# Runs unit tests, end-to-end tests, and misuse tests

echo "================================================================"
echo "Running mcp-nrepl test suite"
echo "================================================================"
echo

# Clean up any existing nREPL processes and files
pkill -f "bb nrepl-server" 2>/dev/null || true
rm -f .nrepl-port .nrepl-pid /tmp/nrepl-output.log

# Start a single nREPL server for all tests
echo "→ Starting nREPL server for test suite..."
bb nrepl-server localhost:0 > /tmp/nrepl-output.log 2>&1 &
nrepl_pid=$!
echo "Started nREPL server with PID: $nrepl_pid"

# Wait for server to start and extract port
sleep 2
if [ -f /tmp/nrepl-output.log ]; then
  nrepl_port=$(grep -oE '127\.0\.0\.1:([0-9]+)' /tmp/nrepl-output.log | grep -oE '[0-9]+$' | head -1)
  if [ -n "$nrepl_port" ]; then
    echo "$nrepl_port" > .nrepl-port
    echo "nREPL server listening on port: $nrepl_port"
    export NREPL_PORT=$nrepl_port
  else
    echo "❌ Failed to extract nREPL port from log"
    cat /tmp/nrepl-output.log
    kill $nrepl_pid 2>/dev/null || true
    exit 1
  fi
else
  echo "❌ nREPL log file not created"
  kill $nrepl_pid 2>/dev/null || true
  exit 1
fi
echo

# Trap to ensure nREPL server is killed on exit
trap "kill $nrepl_pid 2>/dev/null || true" EXIT

# Track overall test status
overall_status=0

# Run unit tests
echo "→ Running unit tests (pure functions)..."
if ! bb test/unit_test.bb; then
  overall_status=1
fi
echo

# Run E2E tests
echo "→ Running end-to-end tests (full integration)..."
if ! bb test/e2e_test.bb; then
  overall_status=1
fi
echo

# Run misuse tests
echo "→ Running misuse tests (error handling)..."
if ! bb test/misuse_test.bb; then
  overall_status=1
fi
echo

# Run performance tests
echo "→ Running performance tests (timing validation)..."
if ! bb test/performance_test.bb; then
  overall_status=1
fi
echo

echo "================================================================"
if [ $overall_status -eq 0 ]; then
  echo "✅ All tests passed!"
else
  echo "❌ Some tests failed"
fi
echo "================================================================"

# Clean up
kill $nrepl_pid 2>/dev/null || true
rm -f .nrepl-port .nrepl-pid

exit $overall_status
