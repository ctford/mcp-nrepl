#!/bin/bash

# End-to-end test for mcp-nrepl
# Tests complete MCP workflow: server startup, initialization, function definition, and invocation

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Cleanup function
cleanup() {
    echo -e "${YELLOW}Cleaning up...${NC}"
    # Clean up test files
    rm -f /tmp/test-file.clj
}

# Set up signal handlers
trap cleanup SIGINT SIGTERM EXIT

echo -e "${YELLOW}Starting end-to-end test for mcp-nrepl...${NC}"

# Step 1: Setup nREPL server connection using start script
echo -e "${YELLOW}Step 1: Setting up nREPL server...${NC}"

# Run the start script (it will detect existing or start new, then exit)
./start-nrepl.sh

# Read the port that was created
if [ -f .nrepl-port ]; then
    PORT=$(cat .nrepl-port)
    echo -e "${GREEN}nREPL server ready on port: $PORT${NC}"
else
    echo -e "${RED}Failed to setup nREPL server - .nrepl-port not created${NC}"
    exit 1
fi

# Step 2: Initialize MCP
echo -e "${YELLOW}Step 2: Initializing MCP protocol...${NC}"
INIT_MSG='{"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2024-11-05", "capabilities": {}, "clientInfo": {"name": "e2e-test", "version": "1.0.0"}}}'
INIT_RESPONSE=$(echo "$INIT_MSG" | ./mcp-nrepl.bb --nrepl-port "$PORT")

# Verify initialization response
if echo "$INIT_RESPONSE" | jq -e '.result.protocolVersion' > /dev/null 2>&1; then
    echo -e "${GREEN}MCP initialization successful${NC}"
else
    echo -e "${RED}MCP initialization failed${NC}"
    echo "Response: $INIT_RESPONSE"
    exit 1
fi

# Step 3: Define a function
echo -e "${YELLOW}Step 3: Defining a simple function...${NC}"
DEFINE_MSG='{"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": "eval-clojure", "arguments": {"code": "(defn square [x] (* x x))"}}}'
DEFINE_RESPONSE=$(echo -e "$INIT_MSG\n$DEFINE_MSG" | ./mcp-nrepl.bb --nrepl-port "$PORT" | tail -1)

# Verify function definition
if echo "$DEFINE_RESPONSE" | jq -e '.result.content[0].text' | grep -q "square"; then
    echo -e "${GREEN}Function definition successful${NC}"
else
    echo -e "${RED}Function definition failed${NC}"
    echo "Response: $DEFINE_RESPONSE"
    exit 1
fi

# Step 4: Invoke the function
echo -e "${YELLOW}Step 4: Invoking the defined function...${NC}"
INVOKE_MSG='{"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "eval-clojure", "arguments": {"code": "(square 7)"}}}'
INVOKE_RESPONSE=$(echo -e "$INIT_MSG\n$INVOKE_MSG" | ./mcp-nrepl.bb --nrepl-port "$PORT" | tail -1)

# Verify function invocation
RESULT=$(echo "$INVOKE_RESPONSE" | jq -r '.result.content[0].text')
if [ "$RESULT" = "49" ]; then
    echo -e "${GREEN}Function invocation successful: square(7) = $RESULT${NC}"
else
    echo -e "${RED}Function invocation failed${NC}"
    echo "Expected: 49, Got: $RESULT"
    echo "Response: $INVOKE_RESPONSE"
    exit 1
fi

# Step 5: Test error handling
echo -e "${YELLOW}Step 5: Testing error handling...${NC}"
ERROR_MSG='{"jsonrpc": "2.0", "id": 5, "method": "tools/call", "params": {"name": "eval-clojure", "arguments": {"code": "(/ 1 0)"}}}'
ERROR_RESPONSE=$(echo -e "$INIT_MSG\n$ERROR_MSG" | ./mcp-nrepl.bb --nrepl-port "$PORT" | tail -1)

# Check if response contains error information (either isError field or error text in content)
ERROR_TEXT=$(echo "$ERROR_RESPONSE" | jq -r '.result.content[0].text')
if echo "$ERROR_TEXT" | grep -q -i "error\|exception"; then
    echo -e "${GREEN}Error handling verified - caught: $(echo "$ERROR_TEXT" | head -1)${NC}"
else
    echo -e "${RED}Error handling test failed${NC}"
    echo "Response: $ERROR_RESPONSE"
    exit 1
fi

# Step 6: Test comprehensive resource workflow
echo -e "${YELLOW}Step 6: Testing comprehensive resource workflow...${NC}"

# 6a. Define a function with documentation  
echo -e "${YELLOW}  6a. Defining function with documentation...${NC}"
DEFINE_FUNC_MSG='{"jsonrpc": "2.0", "id": 6, "method": "tools/call", "params": {"name": "eval-clojure", "arguments": {"code": "(defn add-nums \"Adds two numbers together\" [x y] (+ x y))"}}}'
DEFINE_FUNC_RESPONSE=$(echo -e "$INIT_MSG\n$DEFINE_FUNC_MSG" | ./mcp-nrepl.bb --nrepl-port "$PORT" | tail -1)

if echo "$DEFINE_FUNC_RESPONSE" | jq -e '.result.content[0].text' | grep -q "add-nums"; then
    echo -e "${GREEN}  Function definition successful${NC}"
else
    echo -e "${RED}  Function definition failed${NC}"
    echo "  Response: $DEFINE_FUNC_RESPONSE"
    exit 1
fi

# 6b. List session variables
echo -e "${YELLOW}  6b. Listing session variables...${NC}"
VARS_MSG='{"jsonrpc": "2.0", "id": 7, "method": "resources/read", "params": {"uri": "clojure://session/vars"}}'
VARS_RESPONSE=$(echo -e "$INIT_MSG\n$VARS_MSG" | ./mcp-nrepl.bb --nrepl-port "$PORT" | tail -1)

VARS_TEXT=$(echo "$VARS_RESPONSE" | jq -r '.result.contents[0].text')
if echo "$VARS_TEXT" | grep -q "add-nums"; then
    echo -e "${GREEN}  Session variables listed - found add-nums${NC}"
else
    echo -e "${RED}  Session variables test failed${NC}"
    echo "  Response: $VARS_RESPONSE"
    exit 1
fi

# 6c. Get documentation for our defined function
echo -e "${YELLOW}  6c. Getting documentation for add-nums...${NC}"
DOC_MSG='{"jsonrpc": "2.0", "id": 8, "method": "resources/read", "params": {"uri": "clojure://doc/add-nums"}}'
DOC_RESPONSE=$(echo -e "$INIT_MSG\n$DOC_MSG" | ./mcp-nrepl.bb --nrepl-port "$PORT" | tail -1)

DOC_TEXT=$(echo "$DOC_RESPONSE" | jq -r '.result.contents[0].text')
if echo "$DOC_TEXT" | grep -q "Adds two numbers together"; then
    echo -e "${GREEN}  Documentation lookup verified - found docstring${NC}"
else
    echo -e "${RED}  Documentation lookup test failed${NC}"
    echo "  Response: $DOC_RESPONSE"
    exit 1
fi

# 6d. Get source for a built-in function
echo -e "${YELLOW}  6d. Getting source for clojure.core/map...${NC}"
SOURCE_MSG='{"jsonrpc": "2.0", "id": 9, "method": "resources/read", "params": {"uri": "clojure://source/map"}}'
SOURCE_RESPONSE=$(echo -e "$INIT_MSG\n$SOURCE_MSG" | ./mcp-nrepl.bb --nrepl-port "$PORT" | tail -1)

SOURCE_TEXT=$(echo "$SOURCE_RESPONSE" | jq -r '.result.contents[0].text')
if echo "$SOURCE_TEXT" | grep -q "defn map\|No source found\|Source not found"; then
    echo -e "${GREEN}  Source lookup verified - got source or expected message${NC}"
else
    echo -e "${RED}  Source lookup test failed${NC}"
    echo "  Response: $SOURCE_RESPONSE"
    exit 1
fi

# 6e. List namespaces
echo -e "${YELLOW}  6e. Listing session namespaces...${NC}"
NS_MSG='{"jsonrpc": "2.0", "id": 10, "method": "resources/read", "params": {"uri": "clojure://session/namespaces"}}'
NS_RESPONSE=$(echo -e "$INIT_MSG\n$NS_MSG" | ./mcp-nrepl.bb --nrepl-port "$PORT" | tail -1)

NS_TEXT=$(echo "$NS_RESPONSE" | jq -r '.result.contents[0].text')
if echo "$NS_TEXT" | grep -q "user\|clojure.core"; then
    echo -e "${GREEN}  Session namespaces listed successfully${NC}"
else
    echo -e "${RED}  Session namespaces test failed${NC}"
    echo "  Response: $NS_RESPONSE"
    exit 1
fi

# Step 7: Test new file loading functionality
echo -e "${YELLOW}Step 7: Testing file loading functionality...${NC}"

# 7a. Create a test file
echo -e "${YELLOW}  7a. Creating test Clojure file...${NC}"
cat > /tmp/test-file.clj << 'EOF'
(ns test-namespace)

(defn multiply-by-two [x]
  "Multiplies a number by two"
  (* x 2))

(defn hello-world []
  "Returns a greeting"
  "Hello from test file!")
EOF

# 7b. Load the file
echo -e "${YELLOW}  7b. Loading test file...${NC}"
LOAD_FILE_MSG='{"jsonrpc": "2.0", "id": 11, "method": "tools/call", "params": {"name": "load-file", "arguments": {"file-path": "/tmp/test-file.clj"}}}'
LOAD_FILE_RESPONSE=$(echo -e "$INIT_MSG\n$LOAD_FILE_MSG" | ./mcp-nrepl.bb --nrepl-port "$PORT" | tail -1)

LOAD_RESULT=$(echo "$LOAD_FILE_RESPONSE" | jq -r '.result.content[0].text')
if echo "$LOAD_RESULT" | grep -q "Successfully loaded file\|hello-world"; then
    echo -e "${GREEN}  File loading successful${NC}"
else
    echo -e "${RED}  File loading failed${NC}"
    echo "  Response: $LOAD_FILE_RESPONSE"
    exit 1
fi

# 7c. Test that functions from loaded file work (use fully qualified name)
echo -e "${YELLOW}  7c. Testing function from loaded file...${NC}"
TEST_LOADED_MSG='{"jsonrpc": "2.0", "id": 12, "method": "tools/call", "params": {"name": "eval-clojure", "arguments": {"code": "(test-namespace/multiply-by-two 5)"}}}'
TEST_LOADED_RESPONSE=$(echo -e "$INIT_MSG\n$TEST_LOADED_MSG" | ./mcp-nrepl.bb --nrepl-port "$PORT" | tail -1)

LOADED_RESULT=$(echo "$TEST_LOADED_RESPONSE" | jq -r '.result.content[0].text')
if [ "$LOADED_RESULT" = "10" ]; then
    echo -e "${GREEN}  Loaded function works: test-namespace/multiply-by-two(5) = $LOADED_RESULT${NC}"
else
    echo -e "${RED}  Loaded function test failed${NC}"
    echo "  Expected: 10, Got: $LOADED_RESULT"
    echo "  Response: $TEST_LOADED_RESPONSE"
    exit 1
fi

# Step 8: Test namespace switching functionality
echo -e "${YELLOW}Step 8: Testing namespace switching functionality...${NC}"

# 8a. Get current namespace before switch
echo -e "${YELLOW}  8a. Getting current namespace...${NC}"
CURRENT_NS_MSG='{"jsonrpc": "2.0", "id": 13, "method": "resources/read", "params": {"uri": "clojure://session/current-ns"}}'
CURRENT_NS_RESPONSE=$(echo -e "$INIT_MSG\n$CURRENT_NS_MSG" | ./mcp-nrepl.bb --nrepl-port "$PORT" | tail -1)

CURRENT_NS_TEXT=$(echo "$CURRENT_NS_RESPONSE" | jq -r '.result.contents[0].text')
echo -e "${GREEN}  Current namespace: $CURRENT_NS_TEXT${NC}"

# 8b. Switch to test namespace and verify in the same session
echo -e "${YELLOW}  8b. Switching to test-namespace and verifying...${NC}"
SET_NS_MSG='{"jsonrpc": "2.0", "id": 14, "method": "tools/call", "params": {"name": "set-ns", "arguments": {"namespace": "test-namespace"}}}'
VERIFY_NS_MSG='{"jsonrpc": "2.0", "id": 15, "method": "resources/read", "params": {"uri": "clojure://session/current-ns"}}'

# Run both commands in the same session
COMBINED_RESPONSE=$(echo -e "$INIT_MSG\n$SET_NS_MSG\n$VERIFY_NS_MSG" | ./mcp-nrepl.bb --nrepl-port "$PORT")
SET_NS_RESPONSE=$(echo "$COMBINED_RESPONSE" | sed -n '2p')
VERIFY_NS_RESPONSE=$(echo "$COMBINED_RESPONSE" | sed -n '3p')

SET_NS_RESULT=$(echo "$SET_NS_RESPONSE" | jq -r '.result.content[0].text')
if echo "$SET_NS_RESULT" | grep -q "Successfully switched to namespace\|test-namespace"; then
    echo -e "${GREEN}  Namespace switch successful${NC}"
else
    echo -e "${RED}  Namespace switch failed${NC}"
    echo "  Response: $SET_NS_RESPONSE"
    exit 1
fi

# 8c. Verify namespace switch result from same session
echo -e "${YELLOW}  8c. Verifying namespace switch...${NC}"
VERIFY_NS_TEXT=$(echo "$VERIFY_NS_RESPONSE" | jq -r '.result.contents[0].text')
if echo "$VERIFY_NS_TEXT" | grep -q "test-namespace"; then
    echo -e "${GREEN}  Namespace verification successful: now in $VERIFY_NS_TEXT${NC}"
else
    echo -e "${RED}  Namespace verification failed${NC}"
    echo "  Expected: test-namespace, Got: $VERIFY_NS_TEXT"
    echo "  Response: $VERIFY_NS_RESPONSE"
    exit 1
fi

# Step 9: Test apropos functionality
echo -e "${YELLOW}Step 9: Testing apropos functionality...${NC}"

# 9a. Search for symbols matching "map"
echo -e "${YELLOW}  9a. Searching for symbols matching 'map'...${NC}"
APROPOS_MSG='{"jsonrpc": "2.0", "id": 16, "method": "tools/call", "params": {"name": "apropos", "arguments": {"query": "map"}}}'
APROPOS_RESPONSE=$(echo -e "$INIT_MSG\n$APROPOS_MSG" | ./mcp-nrepl.bb --nrepl-port "$PORT" | tail -1)

APROPOS_TEXT=$(echo "$APROPOS_RESPONSE" | jq -r '.result.content[0].text')
if echo "$APROPOS_TEXT" | grep -q "clojure.core/map"; then
    echo -e "${GREEN}  Apropos search successful - found clojure.core/map${NC}"
else
    echo -e "${RED}  Apropos search failed${NC}"
    echo "  Response: $APROPOS_RESPONSE"
    exit 1
fi

# 9b. Search for previously defined function
echo -e "${YELLOW}  9b. Searching for our defined 'square' function...${NC}"
APROPOS_SQUARE_MSG='{"jsonrpc": "2.0", "id": 17, "method": "tools/call", "params": {"name": "apropos", "arguments": {"query": "square"}}}'
APROPOS_SQUARE_RESPONSE=$(echo -e "$INIT_MSG\n$APROPOS_SQUARE_MSG" | ./mcp-nrepl.bb --nrepl-port "$PORT" | tail -1)

APROPOS_SQUARE_TEXT=$(echo "$APROPOS_SQUARE_RESPONSE" | jq -r '.result.content[0].text')
if echo "$APROPOS_SQUARE_TEXT" | grep -q "user/square"; then
    echo -e "${GREEN}  Apropos found user-defined function: user/square${NC}"
else
    echo -e "${RED}  Apropos did not find user-defined function${NC}"
    echo "  Response: $APROPOS_SQUARE_RESPONSE"
    exit 1
fi

# Step 10: Test direct eval mode (--eval flag)
echo -e "${YELLOW}Step 10: Testing direct eval mode...${NC}"

# 10a. Test basic arithmetic
echo -e "${YELLOW}  10a. Testing basic arithmetic...${NC}"
EVAL_RESULT=$(./mcp-nrepl.bb --eval "(+ 1 2 3)" 2>&1)
if [ "$EVAL_RESULT" = "6" ]; then
    echo -e "${GREEN}  Direct eval works: (+ 1 2 3) = $EVAL_RESULT${NC}"
else
    echo -e "${RED}  Direct eval test failed${NC}"
    echo "  Expected: 6, Got: $EVAL_RESULT"
    exit 1
fi

# 10b. Test string manipulation
echo -e "${YELLOW}  10b. Testing string manipulation...${NC}"
EVAL_STRING=$(./mcp-nrepl.bb --eval '(str "Hello" " " "World")' 2>&1)
if [ "$EVAL_STRING" = '"Hello World"' ]; then
    echo -e "${GREEN}  String eval works: got $EVAL_STRING${NC}"
else
    echo -e "${RED}  String eval test failed${NC}"
    echo "  Expected: \"Hello World\", Got: $EVAL_STRING"
    exit 1
fi

# 10c. Test error handling in eval mode
echo -e "${YELLOW}  10c. Testing error handling in eval mode...${NC}"
EVAL_ERROR=$(./mcp-nrepl.bb --eval "(/ 1 0)" 2>&1)
if echo "$EVAL_ERROR" | grep -q "ArithmeticException"; then
    echo -e "${GREEN}  Error handling works in eval mode${NC}"
else
    echo -e "${RED}  Error handling test failed in eval mode${NC}"
    echo "  Response: $EVAL_ERROR"
    exit 1
fi

# Clean up test file
rm -f /tmp/test-file.clj

echo -e "${GREEN}✅ All end-to-end tests passed!${NC}"
echo -e "${GREEN}MCP-nREPL is working correctly with:${NC}"
echo -e "${GREEN}  - MCP protocol initialization${NC}"
echo -e "${GREEN}  - Function definition and invocation${NC}"
echo -e "${GREEN}  - Error handling${NC}"
echo -e "${GREEN}  - Resource-based session introspection${NC}"
echo -e "${GREEN}  - Documentation lookup for defined functions${NC}"
echo -e "${GREEN}  - Source code lookup for symbols${NC}"
echo -e "${GREEN}  - Namespace and variable listing${NC}"
echo -e "${GREEN}  - File loading functionality${NC}"
echo -e "${GREEN}  - Namespace switching${NC}"
echo -e "${GREEN}  - Current namespace resource${NC}"
echo -e "${GREEN}  - Apropos symbol search${NC}"
echo -e "${GREEN}  - Direct eval mode (--eval flag)${NC}"