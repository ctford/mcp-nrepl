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

(defn mcp-resource-read [id uri]
  (json/parse-string (make-resource-read-msg id uri)))

(defn mcp-eval [id code]
  (json/parse-string (make-tool-call-msg id "eval-clojure" {"code" code})))

(defn mcp-set-ns [id namespace]
  (json/parse-string (make-tool-call-msg id "set-ns" {"namespace" namespace})))

(defn mcp-load-file [id file-path]
  (json/parse-string (make-tool-call-msg id "load-file" {"file-path" file-path})))

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

(deftest test-empty-symbol-in-resource
  (testing "Handles empty symbol names in resource URIs"
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
                          "method" "resources/read"
                          "params" {"uri" "clojure://doc/"}})  ;; Empty symbol
          empty-source-msg (json/generate-string
                            {"jsonrpc" "2.0"
                             "id" 3
                             "method" "resources/read"
                             "params" {"uri" "clojure://source/"}})
          empty-apropos-msg (json/generate-string
                             {"jsonrpc" "2.0"
                              "id" 4
                              "method" "resources/read"
                              "params" {"uri" "clojure://symbols/apropos/"}})
          input (str/join "\n" [init-msg empty-doc-msg empty-source-msg empty-apropos-msg])
          result (run-mcp-with-input port input)
          output (:out result)
          lines (str/split-lines output)]
      (color-print :green "✓ Empty symbol in resource URI test completed")
      ;; Should get 4 responses (1 init + 3 errors)
      (is (>= (count lines) 4) "Should get response for each request")
      ;; Each resource read should return an error
      (doseq [line (drop 1 lines)]  ;; Skip init response
        (let [response (json/parse-string line)]
          (is (get response "error")
              "Should return error for empty symbol/query"))))))

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

(deftest test-invalid-resource-uri
  (testing "Handles invalid resource URIs gracefully"
    (let [port "7888"
          init-msg (json/generate-string
                    {"jsonrpc" "2.0"
                     "id" 1
                     "method" "initialize"
                     "params" {"protocolVersion" "2024-11-05"
                               "capabilities" {}}})
          invalid-uris ["clojure://invalid/foo"
                       "clojure://unknown"
                       "invalid-uri"
                       "clojure://"]
          test-case (fn [uri]
                     (let [msg (json/generate-string
                                {"jsonrpc" "2.0"
                                 "id" 2
                                 "method" "resources/read"
                                 "params" {"uri" uri}})
                           input (str init-msg "\n" msg)
                           result (run-mcp-with-input port input)
                           output (:out result)
                           lines (str/split-lines output)]
                       (when (> (count lines) 1)
                         (json/parse-string (second lines)))))]
      (color-print :green "✓ Invalid resource URI test completed")
      (doseq [uri invalid-uris]
        (let [response (test-case uri)]
          (is (some? response)
              (str "Should return response for invalid URI: " uri))
          (when response
            (is (get response "error")
                (str "Should return error for invalid URI: " uri))))))))

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
                                 "params" {"name" "set-ns"
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

(deftest test-code-injection-in-doc-resource
  (testing "Code injection attempts in doc resource URIs are safely handled"
    (let [port "7888"
          ;; Test just one representative injection case to avoid exhausting nREPL connections
          injection-uri "clojure://doc/map) (System/exit 0) (identity x"
          [init-resp read-resp] (run-mcp port
                                         (mcp-initialize)
                                         (mcp-resource-read 2 injection-uri))
          has-error (get read-resp "error")
          result-text (get-in read-resp ["result" "contents" 0 "text"])]
      (color-print :green "✓ Code injection in doc resource test completed")
      ;; Safe if: error response OR "No documentation found" message
      (is (or has-error
              (str/includes? (str result-text) "No documentation found")
              (str/includes? (str result-text) "Symbol name cannot be empty"))
          "Should safely handle code injection attempt in doc resource URI"))))

(deftest test-code-injection-in-source-resource
  (testing "Code injection attempts in source resource URIs are safely handled"
    (let [port "7888"
          ;; Test one representative injection case
          injection-uri "clojure://source/map) (sh \"rm -rf /\") (identity x"
          [init-resp read-resp] (run-mcp port
                                         (mcp-initialize)
                                         (mcp-resource-read 2 injection-uri))
          has-error (get read-resp "error")
          result-text (get-in read-resp ["result" "contents" 0 "text"])]
      (color-print :green "✓ Code injection in source resource test completed")
      ;; Safe if: error response OR "No source found" message
      (is (or has-error
              (str/includes? (str result-text) "No source found")
              (str/includes? (str result-text) "Symbol name cannot be empty"))
          "Should safely handle code injection attempt in source resource URI"))))

(deftest test-code-injection-in-apropos-resource
  (testing "Code injection attempts in apropos resource URIs are safely handled"
    (let [port "7888"
          ;; Test one representative injection case
          injection-uri "clojure://symbols/apropos/map\") (System/exit 0) (str \""
          [init-resp read-resp] (run-mcp port
                                         (mcp-initialize)
                                         (mcp-resource-read 2 injection-uri))
          has-error (get read-resp "error")
          result-text (get-in read-resp ["result" "contents" 0 "text"])]
      (color-print :green "✓ Code injection in apropos resource test completed")
      ;; Safe if: error response OR search results (no "pwned" or code execution)
      (is (or has-error
              (and result-text (not (str/includes? (str result-text) "pwned")))
              (str/includes? (str result-text) "No matches found")
              (str/includes? (str result-text) "Search query cannot be empty"))
          "Should safely handle code injection attempt in apropos resource URI"))))

(deftest test-code-injection-in-set-ns-tool
  (testing "Code injection attempts in set-ns tool are safely handled"
    (let [port "7888"
          ;; Test one representative injection case
          injection-input "user) (System/exit 0) (symbol 'user"
          [init-resp set-ns-resp] (run-mcp port
                                           (mcp-initialize)
                                           (mcp-set-ns 2 injection-input))
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
