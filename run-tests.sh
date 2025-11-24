#!/bin/bash

# Unified test runner for mcp-nrepl
# Runs both unit tests and end-to-end tests

set -e

echo "================================================================"
echo "Running mcp-nrepl test suite"
echo "================================================================"
echo

# Run unit tests
echo "→ Running unit tests (pure functions)..."
bb test/unit_test.bb
echo

# Run E2E tests
echo "→ Running end-to-end tests (full integration)..."
bb test/e2e_test.bb
echo

echo "================================================================"
echo "✅ All tests passed!"
echo "================================================================"
