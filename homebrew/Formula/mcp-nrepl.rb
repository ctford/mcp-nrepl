class McpNrepl < Formula
  desc "MCP server bridge to Clojure's nREPL"
  homepage "https://github.com/ctford/mcp-nrepl"
  license "EPL-1.0"

  # Using the main branch for now; will be updated to point to releases
  url "https://github.com/ctford/mcp-nrepl.git",
      branch: "main"

  depends_on "babashka"

  def install
    bin.install "mcp-nrepl.bb" => "mcp-nrepl"
  end

  test do
    system "#{bin}/mcp-nrepl", "--help"
  end
end
