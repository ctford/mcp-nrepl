#!/bin/bash

# Unified test runner for mcp-nrepl
# Runs unit tests, end-to-end tests, and misuse tests

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

# Run misuse tests
echo "→ Running misuse tests (error handling)..."
bb test/misuse_test.bb
echo

echo "================================================================"
echo "✅ All tests passed!"
echo "================================================================"
