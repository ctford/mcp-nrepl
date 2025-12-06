#!/usr/bin/env bb

(ns misuse-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.java.shell :as shell]))

(load-file "test/test_utils.bb")
(refer 'test-utils)

;; Test utilities
(defn run-mcp-with-input [port input]
  "Send input to mcp-nrepl and capture output"
  (shell/sh "bb" "mcp-nrepl.bb" "--nrepl-port" (str port)
            :in input))

(defn run-mcp [port & messages]
  "Send JSON-RPC messages to mcp-nrepl and get parsed responses"
  (let [input (str/join "\n" (map json/generate-string messages))
        result (shell/sh "bb" "mcp-nrepl.bb" "--nrepl-port" (str port)
                         :in input)]
    (when (not= 0 (:exit result))
      (throw (ex-info "MCP command failed" result)))
    (mapv json/parse-string (str/split-lines (:out result)))))

(defn mcp-initialize []
  (json/parse-string (make-init-msg 1)))

(defn mcp-eval [id code]
  (json/parse-string (make-tool-call-msg id "eval-clojure" {"code" code})))

(defn mcp-set-namespace [id namespace]
  (json/parse-string (make-tool-call-msg id "set-namespace" {"namespace" namespace})))

(defn mcp-load-file [id file-path]
  (json/parse-string (make-tool-call-msg id "load-file" {"file-path" file-path})))

(defn mcp-get-doc [id symbol]
  (json/parse-string (make-tool-call-msg id "doc" {"symbol" symbol})))

(defn mcp-get-source [id symbol]
  (json/parse-string (make-tool-call-msg id "source" {"symbol" symbol})))

(defn mcp-apropos [id query]
  (json/parse-string (make-tool-call-msg id "apropos" {"query" query})))

;; Switch to misuse namespace for isolation from other test suites
(let [[init set-ns-resp] (run-mcp "7888"
                                  (mcp-initialize)
                                  (mcp-set-namespace 998 "misuse"))]
  (color-print :green "Switched to misuse namespace for test isolation"))

;; Misuse Tests

(deftest test-no-nrepl-server
  (testing "Graceful failure when nREPL server is not available"
    (let [bad-port "9999"  ;; Likely no server on this port
          init-msg (make-init-msg 1)
          eval-msg (make-tool-call-msg 2 "eval-clojure" {"code" "(+ 1 2 3)"})
          input (str init-msg "\n" eval-msg)
          result (run-mcp-with-input bad-port input)
          output (:out result)
          errors (:err result)]
      (color-print :green "✓ No nREPL server test completed")
      ;; Should get initialization response but eval should fail gracefully
      (is (str/includes? output "2024-11-05") "Should initialize successfully")
      ;; Error should be logged to stderr
      (is (or (str/includes? errors "Failed to connect")
              (str/includes? errors "Connection refused"))
          "Should log connection error to stderr"))))

(deftest test-malformed-json
  (testing "Handles malformed JSON gracefully"
    (let [port "7888"  ;; Doesn't matter, won't parse
          malformed-inputs ["not json at all"
                           "{incomplete json"
                           "{\"jsonrpc\": \"2.0\", \"method\": \"test\""]]  ;; missing closing brace
      (color-print :green "✓ Malformed JSON test completed")
      ;; Each should produce an error response or error message
      (doseq [input malformed-inputs]
        (let [result (run-mcp-with-input port input)
              output (:out result)
              exit-code (:exit result)]
          ;; Should either have error in output or non-zero exit
          (is (or (not= 0 exit-code)
                  (str/includes? output "error")
                  (str/includes? output "Error"))
              (str "Should handle malformed JSON with error: " input)))))))

(deftest test-missing-required-fields
  (testing "Handles JSON-RPC messages with missing required fields"
    (let [port "7888"
          ;; Missing method
          missing-method (json/generate-string
                          {"jsonrpc" "2.0"
                           "id" 1
                           "params" {}})
          ;; Missing required parameters
          missing-params (json/generate-string
                          {"jsonrpc" "2.0"
                           "id" 2
                           "method" "tools/call"
                           "params" {"name" "eval-clojure"}})  ;; Missing "arguments"
          results [(run-mcp-with-input port missing-method)
                   (run-mcp-with-input port missing-params)]]
      (color-print :green "✓ Missing required fields test completed")
      (doseq [result results]
        (let [output (:out result)]
          (is (str/includes? output "error")
              "Should return error for missing required fields"))))))

(deftest test-invalid-tool-name
  (testing "Handles requests for non-existent tools"
    (let [port "7888"
          init-msg (make-init-msg 1)
          bad-tool-msg (make-tool-call-msg 2 "nonexistent-tool" {})
          input (str init-msg "\n" bad-tool-msg)
          result (run-mcp-with-input port input)
          output (:out result)
          lines (str/split-lines output)]
      (color-print :green "✓ Invalid tool name test completed")
      ;; Second response should contain error about unknown tool
      (is (> (count lines) 1) "Should get at least 2 responses")
      (let [second-response (json/parse-string (second lines))]
        (is (or (get second-response "error")
                (get-in second-response ["result" "isError"]))
            "Should return error for unknown tool")))))

(deftest test-malformed-clojure-code
  (testing "Handles malformed Clojure code gracefully"
    (let [port "7888"
          init-msg (make-init-msg 1)
          ;; Test one representative case to avoid connection exhaustion
          bad-code "(+ 1 2"  ;; Unclosed paren
          eval-msg (make-tool-call-msg 2 "eval-clojure" {"code" bad-code})
          input (str init-msg "\n" eval-msg)
          result (run-mcp-with-input port input)
          output (:out result)
          lines (str/split-lines output)]
      (color-print :green "✓ Malformed Clojure code test completed")
      ;; Should return an error message (not crash)
      (is (> (count lines) 1) "Should get at least 2 responses")
      (when (> (count lines) 1)
        (let [response (json/parse-string (second lines))
              error-text (get-in response ["result" "content" 0 "text"])]
          (is (some? error-text)
              (str "Should return error for malformed code: " bad-code))
          (when error-text
            (is (or (str/includes? error-text "EOF")
                    (str/includes? error-text "Exception")
                    (str/includes? error-text "Error")
                    (str/includes? error-text "error"))
                (str "Error text should indicate problem: " error-text))))))))

(deftest test-empty-symbol-in-tool
  (testing "Handles empty symbol names in tool parameters"
    (let [port "7888"
          init-msg (json/generate-string
                    {"jsonrpc" "2.0"
                     "id" 1
                     "method" "initialize"
                     "params" {"protocolVersion" "2024-11-05"
                               "capabilities" {}}})
          empty-doc-msg (json/generate-string
                         {"jsonrpc" "2.0"
                          "id" 2
                          "method" "tools/call"
                          "params" {"name" "doc"
                                    "arguments" {"symbol" ""}}})  ;; Empty symbol
          empty-source-msg (json/generate-string
                            {"jsonrpc" "2.0"
                             "id" 3
                             "method" "tools/call"
                             "params" {"name" "source"
                                       "arguments" {"symbol" ""}}})
          empty-apropos-msg (json/generate-string
                             {"jsonrpc" "2.0"
                              "id" 4
                              "method" "tools/call"
                              "params" {"name" "apropos"
                                        "arguments" {"query" ""}}})
          input (str/join "\n" [init-msg empty-doc-msg empty-source-msg empty-apropos-msg])
          result (run-mcp-with-input port input)
          output (:out result)
          lines (str/split-lines output)]
      (color-print :green "✓ Empty symbol in tool parameter test completed")
      ;; Should get 4 responses (1 init + 3 errors)
      (is (>= (count lines) 4) "Should get response for each request")
      ;; Each tool call should return a tool error result (isError: true)
      (doseq [line (drop 1 lines)]  ;; Skip init response
        (let [response (json/parse-string line)]
          (is (get-in response ["result" "isError"])
              "Should return tool error result for empty symbol/query"))))))

(deftest test-missing-code-parameter
  (testing "Handles eval-clojure without code parameter"
    (let [port "7888"
          init-msg (json/generate-string
                    {"jsonrpc" "2.0"
                     "id" 1
                     "method" "initialize"
                     "params" {"protocolVersion" "2024-11-05"
                               "capabilities" {}}})
          no-code-msg (json/generate-string
                       {"jsonrpc" "2.0"
                        "id" 2
                        "method" "tools/call"
                        "params" {"name" "eval-clojure"
                                  "arguments" {}}})  ;; Missing "code"
          input (str init-msg "\n" no-code-msg)
          result (run-mcp-with-input port input)
          output (:out result)
          lines (str/split-lines output)]
      (color-print :green "✓ Missing code parameter test completed")
      (is (> (count lines) 1) "Should get at least 2 responses")
      (let [response (json/parse-string (second lines))
            error-text (get-in response ["result" "content" 0 "text"])]
        (is (get-in response ["result" "isError"])
            "Should return error response")
        (is (str/includes? error-text "required")
            "Error should mention required parameter")))))

(deftest test-invalid-tool-name
  (testing "Handles invalid tool names gracefully"
    (let [port "7888"
          init-msg (json/generate-string
                    {"jsonrpc" "2.0"
                     "id" 1
                     "method" "initialize"
                     "params" {"protocolVersion" "2024-11-05"
                               "capabilities" {}}})
          invalid-tools ["invalid-tool"
                        "unknown-tool"
                        "get-invalid"
                        "bad-tool"]
          test-case (fn [tool-name]
                     (let [msg (json/generate-string
                                {"jsonrpc" "2.0"
                                 "id" 2
                                 "method" "tools/call"
                                 "params" {"name" tool-name
                                           "arguments" {}}})
                           input (str init-msg "\n" msg)
                           result (run-mcp-with-input port input)
                           output (:out result)
                           lines (str/split-lines output)]
                       (when (> (count lines) 1)
                         (json/parse-string (second lines)))))]
      (color-print :green "✓ Invalid tool name test completed")
      (doseq [tool-name invalid-tools]
        (let [response (test-case tool-name)]
          (is (some? response)
              (str "Should return response for invalid tool: " tool-name))
          (when response
            (is (or (get response "error")
                   (get-in response ["result" "isError"]))
                (str "Should return error for invalid tool: " tool-name))))))))

(deftest test-load-nonexistent-file
  (testing "Handles load-file with non-existent file"
    (let [port "7888"
          init-msg (json/generate-string
                    {"jsonrpc" "2.0"
                     "id" 1
                     "method" "initialize"
                     "params" {"protocolVersion" "2024-11-05"
                               "capabilities" {}}})
          load-msg (json/generate-string
                    {"jsonrpc" "2.0"
                     "id" 2
                     "method" "tools/call"
                     "params" {"name" "load-file"
                               "arguments" {"file-path" "/tmp/nonexistent-file-12345.clj"}}})
          input (str init-msg "\n" load-msg)
          result (run-mcp-with-input port input)
          output (:out result)
          lines (str/split-lines output)]
      (color-print :green "✓ Load non-existent file test completed")
      (is (> (count lines) 1) "Should get at least 2 responses")
      (let [response (json/parse-string (second lines))
            error-text (get-in response ["result" "content" 0 "text"])]
        (is (get-in response ["result" "isError"])
            "Should return error response")
        (is (or (str/includes? error-text "not found")
                (str/includes? error-text "File not found"))
            "Error should mention file not found")))))

(deftest test-invalid-namespace
  (testing "Handles set-ns with invalid namespace names"
    (let [port "7888"
          init-msg (json/generate-string
                    {"jsonrpc" "2.0"
                     "id" 1
                     "method" "initialize"
                     "params" {"protocolVersion" "2024-11-05"
                               "capabilities" {}}})
          invalid-namespaces ["123invalid"  ;; starts with number
                             "foo..bar"     ;; double dots
                             "foo/bar/baz"  ;; too many slashes
                             ""]            ;; empty
          test-case (fn [ns-name]
                     (let [msg (json/generate-string
                                {"jsonrpc" "2.0"
                                 "id" 2
                                 "method" "tools/call"
                                 "params" {"name" "set-namespace"
                                           "arguments" {"namespace" ns-name}}})
                           input (str init-msg "\n" msg)
                           result (run-mcp-with-input port input)
                           output (:out result)
                           lines (str/split-lines output)]
                       (when (> (count lines) 1)
                         (let [response (json/parse-string (second lines))
                               result-text (get-in response ["result" "content" 0 "text"])]
                           {:response response
                            :text result-text}))))]
      (color-print :green "✓ Invalid namespace test completed")
      ;; Most invalid namespaces might actually be accepted by Clojure
      ;; (in-ns is permissive), but at least verify we get a response
      (doseq [ns-name invalid-namespaces]
        (let [result (test-case ns-name)]
          (is (some? result)
              (str "Should return response for namespace: " ns-name)))))))

(deftest test-request-size-limit
  (testing "Handles requests exceeding 64 KB size limit"
    (let [port "7888"
          ;; Create a large code string > 64 KB
          large-code (apply str (repeat 70000 "x"))
          init-msg (make-init-msg 1)
          large-request (make-tool-call-msg 2 "eval-clojure" {"code" large-code})
          input (str init-msg "\n" large-request)
          result (run-mcp-with-input port input)
          output (:out result)
          lines (str/split-lines output)]
      (color-print :green "✓ Request size limit test completed")
      ;; First response (init) should succeed
      (is (>= (count lines) 2) "Should get at least 2 responses")
      ;; Second response should contain size limit error
      (let [response (json/parse-string (second lines))]
        (is (get response "error") "Should return error for oversized request")
        (let [error-msg (get-in response ["error" "message"])]
          (is (str/includes? error-msg "too large") "Error should mention size")
          (is (str/includes? error-msg "load-file") "Error should suggest load-file alternative"))))))

(deftest test-tools-before-initialization
  (testing "Handles tool calls before initialize (protocol violation)"
    (let [port "7888"
          ;; Try to call a tool without initializing first
          tool-msg (make-tool-call-msg 1 "eval-clojure" {"code" "(+ 1 2 3)"})
          result (run-mcp-with-input port tool-msg)
          output (:out result)
          lines (str/split-lines output)]
      (color-print :green "✓ Tools before initialization test completed")
      ;; Should get exactly 1 error response
      (is (>= (count lines) 1) "Should get at least 1 response")
      ;; The response should be a JSON-RPC error about not being initialized
      (let [response (json/parse-string (first lines))]
        (is (get response "error") "Should return error for uninitialized server")
        (let [error-msg (get-in response ["error" "message"])]
          (is (str/includes? error-msg "not initialized")
              "Error should mention server not initialized"))))))

;; Code Injection Security Tests

(deftest test-code-injection-in-doc-tool
  (testing "Code injection attempts in doc tool parameters are safely handled"
    (let [port "7888"
          ;; Test just one representative injection case to avoid exhausting nREPL connections
          injection-symbol "map) (System/exit 0) (identity x"
          [init-resp call-resp] (run-mcp port
                                         (mcp-initialize)
                                         (mcp-get-doc 2 injection-symbol))
          has-error (or (get call-resp "error")
                       (get-in call-resp ["result" "isError"]))
          result-text (get-in call-resp ["result" "content" 0 "text"])]
      (color-print :green "✓ Code injection in doc tool test completed")
      ;; Safe if: error response OR "No documentation found" message
      (is (or has-error
              (str/includes? (str result-text) "No documentation found")
              (str/includes? (str result-text) "Symbol name cannot be empty"))
          "Should safely handle code injection attempt in doc tool parameter"))))

(deftest test-code-injection-in-source-tool
  (testing "Code injection attempts in source tool parameters are safely handled"
    (let [port "7888"
          ;; Test one representative injection case
          injection-symbol "map) (sh \"rm -rf /\") (identity x"
          [init-resp call-resp] (run-mcp port
                                         (mcp-initialize)
                                         (mcp-get-source 2 injection-symbol))
          has-error (or (get call-resp "error")
                       (get-in call-resp ["result" "isError"]))
          result-text (get-in call-resp ["result" "content" 0 "text"])]
      (color-print :green "✓ Code injection in source tool test completed")
      ;; Safe if: error response OR "No source found" message
      (is (or has-error
              (str/includes? (str result-text) "No source found")
              (str/includes? (str result-text) "Symbol name cannot be empty"))
          "Should safely handle code injection attempt in source tool parameter"))))

(deftest test-code-injection-in-apropos-tool
  (testing "Code injection attempts in apropos tool parameters are safely handled"
    (let [port "7888"
          ;; Test one representative injection case
          injection-query "map\") (System/exit 0) (str \""
          [init-resp call-resp] (run-mcp port
                                         (mcp-initialize)
                                         (mcp-apropos 2 injection-query))
          has-error (or (get call-resp "error")
                       (get-in call-resp ["result" "isError"]))
          result-text (get-in call-resp ["result" "content" 0 "text"])]
      (color-print :green "✓ Code injection in apropos tool test completed")
      ;; Safe if: error response OR search results (no "pwned" or code execution)
      (is (or has-error
              (and result-text (not (str/includes? (str result-text) "pwned")))
              (str/includes? (str result-text) "No matches found")
              (str/includes? (str result-text) "Search query cannot be empty"))
          "Should safely handle code injection attempt in apropos tool parameter"))))

(deftest test-code-injection-in-set-ns-tool
  (testing "Code injection attempts in set-ns tool are safely handled"
    (let [port "7888"
          ;; Test one representative injection case
          injection-input "user) (System/exit 0) (symbol 'user"
          [init-resp set-ns-resp] (run-mcp port
                                           (mcp-initialize)
                                           (mcp-set-namespace 2 injection-input))
          has-error (get-in set-ns-resp ["result" "isError"])
          result-text (get-in set-ns-resp ["result" "content" 0 "text"])
          error-response (get set-ns-resp "error")]
      (color-print :green "✓ Code injection in set-ns tool test completed")
      ;; Safe if: error response OR successful namespace switch (no "pwned" output)
      (is (or has-error
              error-response
              (and result-text (not (str/includes? (str result-text) "pwned"))))
          "Should safely handle code injection attempt in set-ns tool"))))

(deftest test-path-traversal-in-load-file-tool
  (testing "Path traversal and special filenames in load-file are safely handled"
    (let [port "7888"
          ;; Test one representative path traversal case
          malicious-path "../../etc/passwd"
          [init-resp load-resp] (run-mcp port
                                         (mcp-initialize)
                                         (mcp-load-file 2 malicious-path))
          has-error (get-in load-resp ["result" "isError"])
          result-text (get-in load-resp ["result" "content" 0 "text"])
          error-response (get load-resp "error")]
      (color-print :green "✓ Path traversal in load-file tool test completed")
      ;; Safe if: "File not found" error (not trying to execute shell commands)
      (is (or has-error
              error-response
              (str/includes? (str result-text) "File not found")
              (str/includes? (str result-text) "not found"))
          "Should safely handle path traversal attempt in load-file tool"))))

;; Print Limits Tests

(deftest test-print-limits-constants-defined
  (testing "Print limit constants are properly defined"
    ;; Load and check the mcp-nrepl.bb file directly
    (let [mcp-code (slurp "mcp-nrepl.bb")
          has-print-length (str/includes? mcp-code "(def PRINT-LENGTH 500)")
          has-print-level (str/includes? mcp-code "(def PRINT-LEVEL 20)")
          has-init-function (str/includes? mcp-code "(defn initialize-print-limits")
          has-init-call (str/includes? mcp-code "(initialize-print-limits)")]
      (color-print :green "✓ Print limits constants and initialization test completed")
      (is has-print-length "PRINT-LENGTH constant should be defined as 500")
      (is has-print-level "PRINT-LEVEL constant should be defined as 20")
      (is has-init-function "initialize-print-limits function should be defined")
      (is has-init-call "initialize-print-limits should be called in ensure-nrepl-connection"))))

;; Restart Tool Tests

(deftest test-restart-tool-listed
  (testing "restart-nrepl-server tool is listed in tools/list"
    (let [port "7888"
          init-msg (make-init-msg 1)
          list-msg (json/generate-string
                    {"jsonrpc" "2.0"
                     "id" 2
                     "method" "tools/list"
                     "params" {}})
          input (str init-msg "\n" list-msg)
          result (run-mcp-with-input port input)
          output (:out result)
          lines (str/split-lines output)]
      (color-print :green "✓ Restart tool listing test completed")
      (is (>= (count lines) 2) "Should get at least 2 responses")
      (let [response (json/parse-string (second lines))
            tools (get-in response ["result" "tools"])
            restart-tool (first (filter #(= "restart-nrepl-server" (get % "name")) tools))]
        (is (some? restart-tool) "restart-nrepl-server should be in tools list")
        (when restart-tool
          (is (str/includes? (get restart-tool "description") "--server mode only")
              "Tool description should mention --server mode only")
          (is (str/includes? (get restart-tool "description") "stuck threads")
              "Tool description should mention killing stuck threads"))))))

(deftest test-restart-tool-fails-in-bridge-mode
  (testing "restart-nrepl-server errors appropriately in bridge mode"
    (let [port "7888"
          restart-msg (make-tool-call-msg 2 "restart-nrepl-server" {})
          [init-resp restart-resp] (run-mcp port
                                            (mcp-initialize)
                                            (json/parse-string restart-msg))
          has-error (get-in restart-resp ["result" "isError"])
          result-text (get-in restart-resp ["result" "content" 0 "text"])]
      (color-print :green "✓ Restart tool bridge mode error test completed")
      (is has-error "Should return error when called in bridge mode")
      (is (str/includes? result-text "--server mode")
          "Error message should mention --server mode requirement"))))

(defn run-mcp-server [& messages]
  "Send JSON-RPC messages to mcp-nrepl in --server mode and get parsed responses"
  (let [input (str/join "\n" (map json/generate-string messages))
        result (shell/sh "bb" "mcp-nrepl.bb" "--server"
                         :in input)]
    (when (not= 0 (:exit result))
      (throw (ex-info "MCP command failed" result)))
    (mapv json/parse-string (str/split-lines (:out result)))))

(deftest test-restart-recovers-from-infinite-sequence
  (testing "restart-nrepl-server recovers from problematic evaluation (--server mode only)"
    ;; This test demonstrates the hang-and-recover scenario:
    ;; 1. Server gets stuck or has issues
    ;; 2. We get nil/empty responses back (the symptom)
    ;; 3. Restart fixes it
    (let [init-msg (mcp-initialize)
          ;; Try to evaluate infinite sequence
          range-msg (mcp-eval 2 "(range)")

          ;; Try another eval with short timeout
          test-msg (json/parse-string
                    (make-tool-call-msg 3 "eval-clojure"
                                       {"code" "(+ 1 2 3)"
                                        "timeout-ms" 1000}))

          ;; Restart the server to recover
          restart-msg (json/parse-string (make-tool-call-msg 4 "restart-nrepl-server" {}))

          ;; Verify we can eval again after restart
          verify-msg (mcp-eval 5 "(+ 1 2 3)")

          ;; Run all operations
          [init-resp range-resp test-resp restart-resp verify-resp]
          (run-mcp-server init-msg range-msg test-msg restart-msg verify-msg)]

      (color-print :green "✓ Restart recovery from infinite sequence test completed")

      ;; Check the responses
      (let [range-result (get-in range-resp ["result" "content" 0 "text"])
            test-result (get-in test-resp ["result" "content" 0 "text"])
            ;; Check if we're getting nil responses (sign of stuck server)
            getting-nils (or (= "nil" (str range-result))
                            (= "nil" (str test-result)))
            restart-success (not (get-in restart-resp ["result" "isError"]))
            restart-text (get-in restart-resp ["result" "content" 0 "text"])
            verify-result (get-in verify-resp ["result" "content" 0 "text"])]

        ;; Document what we observe - server might get stuck and return nils
        (when getting-nils
          (println "  → Observed nil responses (server stuck/timeout scenario)"))

        ;; Restart should work regardless
        (is restart-success "Restart should succeed in --server mode")
        (is (str/includes? (str restart-text) "restarted successfully")
            "Restart should report success")

        ;; The key assertion: After restart, server MUST be functional again
        (is (= "6" verify-result)
            "Should be able to eval after restart (this is the critical recovery test)")))))

(deftest test-restart-recovers-from-infinite-computation
  (testing "restart-nrepl-server recovers after stuck computation (--server mode only)"
    ;; Test restart works when server gets stuck with long/infinite computation
    (let [init-msg (mcp-initialize)
          ;; Long operation that may cause issues
          long-comp-msg (json/parse-string
                         (make-tool-call-msg 2 "eval-clojure"
                                            {"code" "(Thread/sleep 5000)"
                                             "timeout-ms" 500}))

          ;; Try another eval
          test-msg (json/parse-string
                    (make-tool-call-msg 3 "eval-clojure"
                                       {"code" "(* 7 7)"
                                        "timeout-ms" 1000}))

          ;; Restart the server to recover
          restart-msg (json/parse-string (make-tool-call-msg 4 "restart-nrepl-server" {}))

          ;; Verify functionality after restart
          verify-msg (mcp-eval 5 "(* 7 7)")

          [init-resp timeout-resp test-resp restart-resp verify-resp]
          (run-mcp-server init-msg long-comp-msg test-msg restart-msg verify-msg)]

      (color-print :green "✓ Restart recovery from infinite computation test completed")

      ;; Check the responses
      (let [timeout-result (get-in timeout-resp ["result" "content" 0 "text"])
            test-result (get-in test-resp ["result" "content" 0 "text"])
            ;; Check if we're getting nil responses (sign of stuck server)
            getting-nils (or (= "nil" (str timeout-result))
                            (= "nil" (str test-result)))
            restart-success (not (get-in restart-resp ["result" "isError"]))
            restart-text (get-in restart-resp ["result" "content" 0 "text"])
            verify-result (get-in verify-resp ["result" "content" 0 "text"])]

        ;; Document what we observe
        (when getting-nils
          (println "  → Observed nil responses (server stuck with long computation)"))

        ;; Restart should work
        (is restart-success "Restart should succeed in --server mode")
        (is (str/includes? (str restart-text) "restarted successfully")
            "Restart should report success with new port")

        ;; The key assertion: After restart, server MUST work again
        (is (= "49" verify-result)
            "Should be able to eval correctly after restart (critical recovery test)")))))

;; Main test runner
(defn run-all-tests []
  (color-print :yellow "Starting misuse tests for mcp-nrepl...")
  (println)
  (color-print :yellow "Note: These tests verify graceful error handling for invalid inputs.")
  (color-print :yellow "Some error messages in output are expected and indicate correct behavior.")
  (println)
  (let [results (run-tests 'misuse-test)]
    (println)
    (if (and (zero? (:fail results)) (zero? (:error results)))
      (do
        (color-print :green "✅ All misuse tests passed!")
        (color-print :green (format "   %d tests, %d assertions"
                                    (:test results)
                                    (:pass results))))
      (do
        (color-print :red "❌ Some tests failed")
        (color-print :red (format "   %d passed, %d failed, %d errors"
                                  (:pass results)
                                  (:fail results)
                                  (:error results)))))
    results))

;; Entry point
(when (= *file* (System/getProperty "babashka.file"))
  (let [results (run-all-tests)]
    (when (or (> (:fail results) 0) (> (:error results) 0))
      (System/exit 1))))
