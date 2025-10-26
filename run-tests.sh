#!/bin/bash

# Test runner script for mcp-nrepl
# Runs unit tests and reports results

set -e

echo "Running mcp-nrepl tests..."

# Check if test directory exists
if [ ! -d "test" ]; then
    echo "Error: test directory not found"
    exit 1
fi

# Check if test file exists
if [ ! -f "test/mcp_nrepl_test.bb" ]; then
    echo "Error: test/mcp_nrepl_test.bb not found"
    exit 1
fi

# Run the tests using Babashka
echo "Executing test suite..."
bb test/mcp_nrepl_test.bb

echo "All tests completed successfully!"