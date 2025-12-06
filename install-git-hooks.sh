#!/bin/bash
# Install git hooks for mcp-nrepl development

echo "Installing git hooks..."

# Configure Git to use .githooks directory for hooks
git config core.hooksPath .githooks

echo "✅ Git hooks installed successfully!"
echo ""
echo "Configured Git to use hooks from .githooks/ directory."
echo "Pre-commit hook will now run tests before each commit."
echo "To skip the hook for a specific commit, use: git commit --no-verify"
