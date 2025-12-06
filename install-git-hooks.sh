#!/bin/bash
# Install git hooks for mcp-nrepl development

echo "Installing git hooks..."

# Create pre-commit hook
cat > .git/hooks/pre-commit << 'EOF'
#!/bin/bash
# Pre-commit hook for mcp-nrepl
# Runs the full test suite before allowing a commit

echo "Running pre-commit tests..."
echo ""

# Run the test suite
if ! ./run-tests.sh; then
  echo ""
  echo "❌ Tests failed! Commit aborted."
  echo "Fix the failing tests or use 'git commit --no-verify' to skip this check."
  exit 1
fi

echo ""
echo "✅ All tests passed! Proceeding with commit."
exit 0
EOF

# Make it executable
chmod +x .git/hooks/pre-commit

echo "✅ Git hooks installed successfully!"
echo ""
echo "Pre-commit hook will now run tests before each commit."
echo "To skip the hook for a specific commit, use: git commit --no-verify"
