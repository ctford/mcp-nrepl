# Homebrew Tap for MCP-nREPL

This directory contains the Homebrew formula for mcp-nrepl. 

## Installation

Once this is published as a tap, users can install with:

```bash
brew install ctford/tap/mcp-nrepl
mcp-nrepl --help
```

## Development

The formula is defined in `Formula/mcp-nrepl.rb` and points to the `main` branch of the mcp-nrepl repository. Once releases are tagged, update the formula to use specific versions.

### Creating a release

To create a release for Homebrew:

1. Tag a version: `git tag v1.0.0`
2. Push the tag: `git push origin v1.0.0`
3. Create a GitHub release with the tag
4. Update `Formula/mcp-nrepl.rb` to reference the release URL instead of the main branch
