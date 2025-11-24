#!/usr/bin/env bb

(ns unit-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; Load the main module for testing
(load-file "mcp-nrepl.bb")

;; Test pure functions only - no side effects, no I/O, no state mutations

;; Test port parsing handles valid and invalid inputs correctly
(deftest port-parsing-handles-valid-and-invalid-inputs-correctly
  (testing "Valid port numbers are parsed correctly"
    (is (= 1667 (mcp-nrepl/parse-port "1667")))
    (is (= 8080 (mcp-nrepl/parse-port "8080")))
    (is (= 1234 (mcp-nrepl/parse-port " 1234 "))))
  
  (testing "Invalid port numbers return nil"
    (is (nil? (mcp-nrepl/parse-port "abc")))
    (is (nil? (mcp-nrepl/parse-port "")))
    (is (nil? (mcp-nrepl/parse-port nil))))
  
  (testing "High port numbers are handled correctly"
    (is (= 65536 (mcp-nrepl/parse-port "65536")))
    (is (= 65535 (mcp-nrepl/parse-port "65535")))))

;; Test initialization returns correct MCP protocol response structure
(deftest initialization-returns-correct-mcp-protocol-response-structure
  (testing "Valid initialization parameters produce expected response"
    (let [params {"protocolVersion" "2024-11-05"
                  "capabilities" {}
                  "clientInfo" {"name" "test" "version" "1.0"}}
          result (mcp-nrepl/handle-initialize params)
          expected {"protocolVersion" "2024-11-05"
                    "capabilities" {"tools" {}
                                    "resources" {}}
                    "serverInfo" {:name "mcp-nrepl" :version "0.1.0"}}]
      (is (= expected result))))
  
  (testing "Missing capabilities still produces valid response"
    (let [params {"protocolVersion" "2024-11-05"}
          result (mcp-nrepl/handle-initialize params)
          expected {"protocolVersion" "2024-11-05"
                    "capabilities" {"tools" {}
                                    "resources" {}}
                    "serverInfo" {:name "mcp-nrepl" :version "0.1.0"}}]
      (is (= expected result))))
  
  (testing "Missing protocol version still produces valid response"
    (let [params {"capabilities" {}}
          result (mcp-nrepl/handle-initialize params)
          expected {"protocolVersion" "2024-11-05"
                    "capabilities" {"tools" {}
                                    "resources" {}}
                    "serverInfo" {:name "mcp-nrepl" :version "0.1.0"}}]
      (is (= expected result)))))

;; Test tools list contains all expected tools with correct schemas
(deftest tools-list-contains-all-expected-tools-with-correct-schemas
  (testing "Tools list includes eval-clojure, load-file, set-ns, and apropos with proper schemas"
    (let [result (mcp-nrepl/handle-tools-list)
          expected {"tools"
                    [{"name" "eval-clojure"
                      "description" "Evaluate Clojure code using nREPL"
                      "inputSchema"
                      {"type" "object"
                       "properties"
                       {"code" {"type" "string"
                                "description" "The Clojure code to evaluate"}}
                       "required" ["code"]}}
                     {"name" "load-file"
                      "description" "Load and evaluate a Clojure file using nREPL"
                      "inputSchema"
                      {"type" "object"
                       "properties"
                       {"file-path" {"type" "string"
                                     "description" "The path to the Clojure file to load"}}
                       "required" ["file-path"]}}
                     {"name" "set-ns"
                      "description" "Switch to a different namespace in the nREPL session"
                      "inputSchema"
                      {"type" "object"
                       "properties"
                       {"namespace" {"type" "string"
                                     "description" "The namespace to switch to"}}
                       "required" ["namespace"]}}
                     {"name" "apropos"
                      "description" "Search for symbols matching a pattern in their name or documentation"
                      "inputSchema"
                      {"type" "object"
                       "properties"
                       {"query" {"type" "string"
                                 "description" "Search pattern (string or regex) to match against symbol names"}}
                       "required" ["query"]}}]}]
      (is (= expected result)))))

;; Test resources list contains all session introspection resources
(deftest resources-list-contains-all-session-introspection-resources
  (testing "Resources list includes all resources including URI templates"
    (let [result (mcp-nrepl/handle-resources-list)
          expected {"resources"
                    [{"uri" "clojure://session/vars"
                      "name" "Session Variables"
                      "description" "Currently defined variables in the REPL session"
                      "mimeType" "application/json"}
                     {"uri" "clojure://session/namespaces"
                      "name" "Session Namespaces"
                      "description" "Currently loaded namespaces in the REPL session"
                      "mimeType" "application/json"}
                     {"uri" "clojure://session/current-ns"
                      "name" "Current Namespace"
                      "description" "The current default namespace in the REPL session"
                      "mimeType" "text/plain"}
                     {"uri" "clojure://doc/{symbol}"
                      "name" "Symbol Documentation"
                      "description" "Get documentation for any Clojure symbol (URI template - replace {symbol} with the symbol name)"
                      "mimeType" "text/plain"}
                     {"uri" "clojure://source/{symbol}"
                      "name" "Symbol Source Code"
                      "description" "Get source code for any Clojure symbol (URI template - replace {symbol} with the symbol name)"
                      "mimeType" "text/clojure"}]}]
      (is (= expected result)))))

;; Test error responses follow JSON-RPC error format correctly
(deftest error-responses-follow-jsonrpc-error-format-correctly
  (testing "Error response has correct JSON-RPC structure with id and message"
    (let [result (mcp-nrepl/handle-error 123 "Test error")
          expected {"jsonrpc" "2.0"
                    "id" 123
                    "error" {"code" -1
                             "message" "Test error"}}]
      (is (= expected result))))
  
  (testing "Nil request id is preserved in error response"
    (let [result (mcp-nrepl/handle-error nil "Error")
          expected {"jsonrpc" "2.0"
                    "id" nil
                    "error" {"code" -1
                             "message" "Error"}}]
      (is (= expected result)))))


;; Test constants have expected values for MCP protocol compliance
(deftest constants-have-expected-values-for-mcp-protocol-compliance
  (testing "MCP version string matches protocol specification"
    (is (string? mcp-nrepl/MCP-VERSION))
    (is (= "2024-11-05" mcp-nrepl/MCP-VERSION)))
  
  (testing "Server info contains name and version fields"
    (is (map? mcp-nrepl/SERVER-INFO))
    (is (= "mcp-nrepl" (:name mcp-nrepl/SERVER-INFO)))
    (is (string? (:version mcp-nrepl/SERVER-INFO)))))

;; Test JSON serialization preserves data integrity for MCP messages
(deftest json-serialization-preserves-data-integrity-for-mcp-messages
  (testing "JSON round-trip maintains data equality"
    (let [data {"test" "value" "number" 42}
          json-str (json/generate-string data)
          parsed (json/parse-string json-str)]
      (is (= data parsed))))

  (testing "MCP message structures serialize and deserialize correctly"
    (let [msg {"jsonrpc" "2.0" "id" 1 "method" "test"}
          json-str (json/generate-string msg)
          parsed (json/parse-string json-str)]
      (is (= msg parsed)))))

;; Test helper function: decode-bytes
(deftest decode-bytes-converts-bytes-to-utf8-string
  (testing "Bytes are converted to UTF-8 strings"
    (let [bytes (.getBytes "test" "UTF-8")]
      (is (= "test" (mcp-nrepl/decode-bytes bytes)))))

  (testing "UTF-8 multi-byte characters"
    (let [bytes (.getBytes "日本語" "UTF-8")]
      (is (= "日本語" (mcp-nrepl/decode-bytes bytes))))))

;; Test helper function: extract-field-from-responses
(deftest extract-field-from-responses-extracts-and-decodes
  (testing "Extracts field from multiple responses and decodes bytes"
    (let [responses [{"value" (.getBytes "42" "UTF-8")}
                     {"value" (.getBytes "hello" "UTF-8")}
                     {"other" "field"}
                     {"value" (.getBytes "123" "UTF-8")}]]
      (is (= ["42" "hello" "123"] (mcp-nrepl/extract-field-from-responses responses "value")))))

  (testing "Returns empty sequence when field not found"
    (let [responses [{"other" "field"}]]
      (is (empty? (mcp-nrepl/extract-field-from-responses responses "value"))))))

;; Test helper function: format-tool-result
(deftest format-tool-result-formats-responses-correctly
  (testing "Formats responses with values, output, and errors"
    (let [responses [{"value" (.getBytes "42" "UTF-8")}
                     {"out" (.getBytes "debug output" "UTF-8")}
                     {"err" (.getBytes "warning" "UTF-8")}]
          result (mcp-nrepl/format-tool-result responses)
          expected {"content" [{"type" "text"
                               "text" "debug output\nwarning\n42"}]}]
      (is (= expected result))))

  (testing "Uses default message when result is empty"
    (let [responses []
          result (mcp-nrepl/format-tool-result responses :default-message "Success!")
          expected {"content" [{"type" "text"
                               "text" "Success!"}]}]
      (is (= expected result))))

  (testing "Returns 'nil' when no default message and empty responses"
    (let [responses []
          result (mcp-nrepl/format-tool-result responses)
          expected {"content" [{"type" "text"
                               "text" "nil"}]}]
      (is (= expected result)))))

;; Test helper function: format-tool-error
(deftest format-tool-error-creates-error-response
  (testing "Creates properly formatted error response"
    (let [result (mcp-nrepl/format-tool-error "Test error message")
          expected {"isError" true
                    "content" [{"type" "text"
                               "text" "Test error message"}]}]
      (is (= expected result)))))

;; Main test runner
(defn run-all-tests []
  (println "Running unit tests for pure functions...")
  (let [results (run-tests 'unit-test)]
    (println (format "\nUnit Test Results: %d passed, %d failed, %d errors"
                     (:pass results)
                     (:fail results)
                     (:error results)))
    (when (or (> (:fail results) 0) (> (:error results) 0))
      (System/exit 1))))

;; Entry point
(when (= *file* (System/getProperty "babashka.file"))
  (run-all-tests))