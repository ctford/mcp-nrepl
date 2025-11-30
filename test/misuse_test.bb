#!/usr/bin/env bb

(ns misuse-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.java.shell :as shell]))

;; ANSI color codes
(def colors
  {:red "\033[0;31m"
   :green "\033[0;32m"
   :yellow "\033[1;33m"
   :nc "\033[0m"})

(defn color-print [color & args]
  (print (get colors color ""))
  (apply print args)
  (print (:nc colors))
  (println))

;; Test utilities
(defn run-mcp-with-input [port input]
  "Send input to mcp-nrepl and capture output"
  (shell/sh "bb" "mcp-nrepl.bb" "--nrepl-port" (str port)
            :in input))

;; Misuse Tests

(deftest test-no-nrepl-server
  (testing "Graceful failure when nREPL server is not available"
    (let [bad-port "9999"  ;; Likely no server on this port
          init-msg (json/generate-string
                    {"jsonrpc" "2.0"
                     "id" 1
                     "method" "initialize"
                     "params" {"protocolVersion" "2024-11-05"
                               "capabilities" {}}})
          eval-msg (json/generate-string
                    {"jsonrpc" "2.0"
                     "id" 2
                     "method" "tools/call"
                     "params" {"name" "eval-clojure"
                               "arguments" {"code" "(+ 1 2 3)"}}})
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
          init-msg (json/generate-string
                    {"jsonrpc" "2.0"
                     "id" 1
                     "method" "initialize"
                     "params" {"protocolVersion" "2024-11-05"
                               "capabilities" {}}})
          bad-tool-msg (json/generate-string
                        {"jsonrpc" "2.0"
                         "id" 2
                         "method" "tools/call"
                         "params" {"name" "nonexistent-tool"
                                   "arguments" {}}})
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
          init-msg (json/generate-string
                    {"jsonrpc" "2.0"
                     "id" 1
                     "method" "initialize"
                     "params" {"protocolVersion" "2024-11-05"
                               "capabilities" {}}})
          bad-code-cases ["(+ 1 2"  ;; Unclosed paren
                         "(defn)"    ;; Invalid defn
                         "((((("      ;; Too many parens
                         ")("         ;; Invalid structure
                         ]
          test-case (fn [bad-code]
                     (let [eval-msg (json/generate-string
                                     {"jsonrpc" "2.0"
                                      "id" 2
                                      "method" "tools/call"
                                      "params" {"name" "eval-clojure"
                                                "arguments" {"code" bad-code}}})
                           input (str init-msg "\n" eval-msg)
                           result (run-mcp-with-input port input)
                           output (:out result)
                           lines (str/split-lines output)]
                       (when (> (count lines) 1)
                         (let [response (json/parse-string (second lines))]
                           (get-in response ["result" "content" 0 "text"])))))]
      (color-print :green "✓ Malformed Clojure code test completed")
      ;; Each should return an error message (not crash)
      (doseq [bad-code bad-code-cases]
        (let [error-text (test-case bad-code)]
          (is (some? error-text)
              (str "Should return error for malformed code: " bad-code))
          (when error-text
            (is (or (str/includes? error-text "EOF")
                    (str/includes? error-text "Exception")
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
                                    (:pass results)))
        (System/exit 0))
      (do
        (color-print :red "❌ Some tests failed")
        (color-print :red (format "   %d passed, %d failed, %d errors"
                                  (:pass results)
                                  (:fail results)
                                  (:error results)))
        (System/exit 1)))))

;; Entry point
(when (= *file* (System/getProperty "babashka.file"))
  (run-all-tests))
