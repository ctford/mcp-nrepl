#!/bin/bash

# Unit test runner script for mcp-nrepl
# Tests pure functions only - no side effects, no I/O, no state mutations

set -e

echo "Running mcp-nrepl unit tests..."

# Check if test directory exists
if [ ! -d "test" ]; then
    echo "Error: test directory not found"
    exit 1
fi

# Check if unit test file exists
if [ ! -f "test/unit_test.bb" ]; then
    echo "Error: test/unit_test.bb not found"
    exit 1
fi

# Run the unit tests using Babashka
echo "Executing unit test suite..."
bb test/unit_test.bb

echo "All unit tests completed successfully!"