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
    # Only cleanup servers we started
    if [ "$MANAGED_SERVER" = true ] && [ -n "$NREPL_PID" ] && kill -0 "$NREPL_PID" 2>/dev/null; then
        echo "Stopping nREPL server (PID: $NREPL_PID)"
        kill "$NREPL_PID"
        wait "$NREPL_PID" 2>/dev/null || true
    fi
    # Only remove files if we created them
    if [ "$MANAGED_SERVER" = true ]; then
        rm -f .nrepl-port .nrepl-pid /tmp/nrepl-output.log
    fi
}

# Parse command line arguments
EXTERNAL_PORT=""
MANAGED_SERVER=true

usage() {
    echo "Usage: $0 [OPTIONS]"
    echo "Options:"
    echo "  --nrepl-port PORT   Use existing nREPL server at specified port"
    echo "  --help             Show this help message"
    echo ""
    echo "If no port is specified, a Babashka nREPL server will be started automatically."
}

while [[ $# -gt 0 ]]; do
    case $1 in
        --nrepl-port)
            EXTERNAL_PORT="$2"
            MANAGED_SERVER=false
            shift 2
            ;;
        --help)
            usage
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            usage
            exit 1
            ;;
    esac
done

# Set up signal handlers
trap cleanup SIGINT SIGTERM EXIT

echo -e "${YELLOW}Starting end-to-end test for mcp-nrepl...${NC}"

# Step 1: Setup nREPL server connection
if [ "$MANAGED_SERVER" = true ]; then
    echo -e "${YELLOW}Step 1: Starting nREPL server...${NC}"
    bb nrepl-server localhost:0 > /tmp/nrepl-output.log 2>&1 &
    NREPL_PID=$!

    # Wait for server to start and extract port
    sleep 2
    if [ -f /tmp/nrepl-output.log ]; then
        PORT=$(grep -o "127\.0\.0\.1:[0-9]*" /tmp/nrepl-output.log | head -1 | cut -d':' -f2)
        if [ -n "$PORT" ]; then
            echo "$PORT" > .nrepl-port
            echo -e "${GREEN}nREPL server started on port: $PORT${NC}"
        else
            echo -e "${RED}Failed to extract port from nREPL output${NC}"
            cat /tmp/nrepl-output.log
            exit 1
        fi
    else
        echo -e "${RED}Failed to find nREPL output log${NC}"
        exit 1
    fi
else
    echo -e "${YELLOW}Step 1: Using existing nREPL server...${NC}"
    PORT="$EXTERNAL_PORT"
    
    # Validate port is a number
    if ! [[ "$PORT" =~ ^[0-9]+$ ]]; then
        echo -e "${RED}Invalid port number: $PORT${NC}"
        exit 1
    fi
    
    # Test connection to external server
    echo -e "${YELLOW}Testing connection to nREPL server on port $PORT...${NC}"
    if ! bash -c "echo > /dev/tcp/localhost/$PORT" 2>/dev/null; then
        echo -e "${RED}Cannot connect to nREPL server on port $PORT${NC}"
        echo -e "${RED}Make sure the server is running and accessible${NC}"
        exit 1
    fi
    
    echo -e "${GREEN}Connected to existing nREPL server on port: $PORT${NC}"
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

# 6d. List namespaces
echo -e "${YELLOW}  6d. Listing session namespaces...${NC}"
NS_MSG='{"jsonrpc": "2.0", "id": 9, "method": "resources/read", "params": {"uri": "clojure://session/namespaces"}}'
NS_RESPONSE=$(echo -e "$INIT_MSG\n$NS_MSG" | ./mcp-nrepl.bb --nrepl-port "$PORT" | tail -1)

NS_TEXT=$(echo "$NS_RESPONSE" | jq -r '.result.contents[0].text')
if echo "$NS_TEXT" | grep -q "user\|clojure.core"; then
    echo -e "${GREEN}  Session namespaces listed successfully${NC}"
else
    echo -e "${RED}  Session namespaces test failed${NC}"
    echo "  Response: $NS_RESPONSE"
    exit 1
fi

echo -e "${GREEN}✅ All end-to-end tests passed!${NC}"
echo -e "${GREEN}MCP-nREPL is working correctly with:${NC}"
echo -e "${GREEN}  - MCP protocol initialization${NC}"
echo -e "${GREEN}  - Function definition and invocation${NC}"
echo -e "${GREEN}  - Error handling${NC}"
echo -e "${GREEN}  - Resource-based session introspection${NC}"
echo -e "${GREEN}  - Documentation lookup for defined functions${NC}"
echo -e "${GREEN}  - Namespace and variable listing${NC}"