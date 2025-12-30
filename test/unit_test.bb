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
                                    "resources" {}
                                    "prompts" {}}
                    "serverInfo" {:name "mcp-nrepl" :version "0.1.0"}}]
      (is (= expected result))))

  (testing "Missing capabilities still produces valid response"
    (let [params {"protocolVersion" "2024-11-05"}
          result (mcp-nrepl/handle-initialize params)
          expected {"protocolVersion" "2024-11-05"
                    "capabilities" {"tools" {}
                                    "resources" {}
                                    "prompts" {}}
                    "serverInfo" {:name "mcp-nrepl" :version "0.1.0"}}]
      (is (= expected result))))

  (testing "Missing protocol version still produces valid response"
    (let [params {"capabilities" {}}
          result (mcp-nrepl/handle-initialize params)
          expected {"protocolVersion" "2024-11-05"
                    "capabilities" {"tools" {}
                                    "resources" {}
                                    "prompts" {}}
                    "serverInfo" {:name "mcp-nrepl" :version "0.1.0"}}]
      (is (= expected result)))))

;; Test tools list contains all expected tools with correct schemas
(deftest tools-list-contains-all-expected-tools-with-correct-schemas
  (testing "Tools list includes all 12 tools (eval, load, set-namespace, get-doc, get-source, apropos, session tools, macroexpand, restart)"
    (let [result (mcp-nrepl/handle-tools-list)
          tools (get result "tools")]
      (is (= 12 (count tools)) "Should have 12 tools")
      (is (some #(= "eval-clojure" (get % "name")) tools))
      (is (some #(= "load-file" (get % "name")) tools))
      (is (some #(= "set-namespace" (get % "name")) tools))
      (is (some #(= "doc" (get % "name")) tools))
      (is (some #(= "source" (get % "name")) tools))
      (is (some #(= "apropos" (get % "name")) tools))
      (is (some #(= "vars" (get % "name")) tools))
      (is (some #(= "loaded-namespaces" (get % "name")) tools))
      (is (some #(= "current-namespace" (get % "name")) tools))
      (is (some #(= "macroexpand-all" (get % "name")) tools))
      (is (some #(= "macroexpand-1" (get % "name")) tools))
      (is (some #(= "restart-nrepl-server" (get % "name")) tools)))))

;; Test resources list is now empty (all migrated to tools)
(deftest resources-list-is-empty
  (testing "Resources list is empty - all functionality migrated to tools"
    (let [result (mcp-nrepl/handle-resources-list)
          expected {"resources" []}]
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

;; Test helper function: extract-nrepl-output
(deftest extract-nrepl-output-joins-and-trims
  (testing "Extracts 'out' field, joins lines, and trims whitespace"
    (let [responses [{"out" (.getBytes "Line 1\n" "UTF-8")}
                     {"out" (.getBytes "Line 2\n" "UTF-8")}
                     {"value" (.getBytes "ignored" "UTF-8")}
                     {"out" (.getBytes "Line 3" "UTF-8")}]]
      (is (= "Line 1\nLine 2\nLine 3" (mcp-nrepl/extract-nrepl-output responses)))))

  (testing "Trims leading and trailing whitespace"
    (let [responses [{"out" (.getBytes "  \n  text  \n  " "UTF-8")}]]
      (is (= "text" (mcp-nrepl/extract-nrepl-output responses)))))

  (testing "Returns empty string when no 'out' field"
    (let [responses [{"value" (.getBytes "42" "UTF-8")}]]
      (is (= "" (mcp-nrepl/extract-nrepl-output responses)))))

  (testing "Returns empty string for empty responses"
    (is (= "" (mcp-nrepl/extract-nrepl-output [])))))

;; Test helper function: extract-nrepl-value
(deftest extract-nrepl-value-returns-first
  (testing "Extracts 'value' field from first response with value"
    (let [responses [{"status" (.getBytes "done" "UTF-8")}
                     {"value" (.getBytes "first-value" "UTF-8")}
                     {"value" (.getBytes "second-value" "UTF-8")}]]
      (is (= "first-value" (mcp-nrepl/extract-nrepl-value responses)))))

  (testing "Decodes bytes to UTF-8 string"
    (let [responses [{"value" (.getBytes "日本語" "UTF-8")}]]
      (is (= "日本語" (mcp-nrepl/extract-nrepl-value responses)))))

  (testing "Returns nil when no 'value' field"
    (let [responses [{"out" (.getBytes "text" "UTF-8")}]]
      (is (nil? (mcp-nrepl/extract-nrepl-value responses)))))

  (testing "Returns nil for empty responses"
    (is (nil? (mcp-nrepl/extract-nrepl-value [])))))

;; Test helper function: with-required-param
(deftest with-required-param-validates-and-handles-errors
  (testing "Calls function with parameter when valid"
    (let [arguments {"code" "(+ 1 2)"}
          result (mcp-nrepl/with-required-param arguments "code" "testing"
                   (fn [code] {"success" true "code" code}))]
      (is (= {"success" true "code" "(+ 1 2)"} result))))

  (testing "Returns error when parameter is blank"
    (let [arguments {"code" ""}
          result (mcp-nrepl/with-required-param arguments "code" "testing"
                   (fn [code] {"success" true}))]
      (is (= true (get result "isError")))
      (is (= "Error: code parameter is required and cannot be empty"
             (get-in result ["content" 0 "text"])))))

  (testing "Returns error when parameter is nil"
    (let [arguments {}
          result (mcp-nrepl/with-required-param arguments "namespace" "testing"
                   (fn [ns] {"success" true}))]
      (is (= true (get result "isError")))
      (is (= "Error: namespace parameter is required and cannot be empty"
             (get-in result ["content" 0 "text"])))))

  (testing "Catches exceptions and formats error"
    (let [arguments {"code" "bad"}
          result (mcp-nrepl/with-required-param arguments "code" "evaluating code"
                   (fn [code] (throw (Exception. "Division by zero"))))]
      (is (= true (get result "isError")))
      (is (= "Error evaluating code: Division by zero"
             (get-in result ["content" 0 "text"])))))

  (testing "Preserves exception message in error context"
    (let [arguments {"file-path" "/test.clj"}
          result (mcp-nrepl/with-required-param arguments "file-path" "loading file"
                   (fn [path] (throw (Exception. "File not found"))))]
      (is (= true (get result "isError")))
      (is (= "Error loading file: File not found"
             (get-in result ["content" 0 "text"]))))))

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

;; Test code generation functions properly escape special characters
(deftest code-generation-properly-escapes-special-characters
  (testing "load-file code generation escapes quotes and special chars"
    (is (= "(load-file \"normal.clj\")"
           (mcp-nrepl/build-load-file-code "normal.clj")))
    (is (= "(load-file \"file\\\"with\\\"quotes.clj\")"
           (mcp-nrepl/build-load-file-code "file\"with\"quotes.clj")))
    (is (= "(load-file \"path/with spaces.clj\")"
           (mcp-nrepl/build-load-file-code "path/with spaces.clj"))))

  (testing "apropos code generation escapes quotes and special chars"
    (is (= "(require 'clojure.repl) (clojure.repl/apropos \"map\")"
           (mcp-nrepl/build-apropos-code "map")))
    (is (= "(require 'clojure.repl) (clojure.repl/apropos \"test\\\"quote\")"
           (mcp-nrepl/build-apropos-code "test\"quote")))
    (is (= "(require 'clojure.repl) (clojure.repl/apropos \"with spaces\")"
           (mcp-nrepl/build-apropos-code "with spaces")))))

(deftest build-macroexpand-all-code-generates-correctly
  (testing "Builds correct macroexpand-all code"
    (is (= "(require 'clojure.walk) (clojure.walk/macroexpand-all (quote (when x y)))"
           (mcp-nrepl/build-macroexpand-all-code "(when x y)"))))

  (testing "Handles nested expressions"
    (is (= "(require 'clojure.walk) (clojure.walk/macroexpand-all (quote (-> x (+ 1) (* 2))))"
           (mcp-nrepl/build-macroexpand-all-code "(-> x (+ 1) (* 2))")))))

(deftest build-macroexpand-1-code-generates-correctly
  (testing "Builds correct macroexpand-1 code"
    (is (= "(macroexpand-1 (quote (when x y)))"
           (mcp-nrepl/build-macroexpand-1-code "(when x y)"))))

  (testing "Handles threading macro"
    (is (= "(macroexpand-1 (quote (-> x (+ 1) (* 2))))"
           (mcp-nrepl/build-macroexpand-1-code "(-> x (+ 1) (* 2))")))))

;; Test prompts handlers
(deftest handle-prompts-list-returns-empty
  (testing "Returns empty prompts list"
    (let [result (mcp-nrepl/handle-prompts-list)
          prompts (get result "prompts")]
      (is (= 0 (count prompts)))
      (is (vector? prompts)))))

(deftest handle-prompts-get-returns-error
  (testing "Throws exception when prompts are not available"
    (is (thrown-with-msg? Exception #"No prompts are available"
          (mcp-nrepl/handle-prompts-get {"name" "any-prompt"})))))

;; Test CLI argument validation
(deftest validate-args-handles-bridge-flag
  (testing "--bridge flag is accepted and parsed correctly"
    (let [result (mcp-nrepl/validate-args ["--bridge"])]
      (is (= {:options {:bridge true}} result))
      (is (true? (get-in result [:options :bridge])))))

  (testing "--bridge can be combined with --nrepl-port"
    (let [result (mcp-nrepl/validate-args ["--bridge" "--nrepl-port" "1667"])]
      (is (true? (get-in result [:options :bridge])))
      (is (= 1667 (get-in result [:options :nrepl-port])))))

  (testing "--bridge and --server are mutually exclusive"
    (let [result (mcp-nrepl/validate-args ["--bridge" "--server"])]
      (is (contains? result :exit-message))
      (is (str/includes? (:exit-message result) "Cannot specify both --bridge and --server"))))

  (testing "--server and --nrepl-port are mutually exclusive"
    (let [result (mcp-nrepl/validate-args ["--server" "--nrepl-port" "1667"])]
      (is (contains? result :exit-message))
      (is (str/includes? (:exit-message result) "Cannot specify both --server and --nrepl-port"))))

  (testing "Short flags -s and -p are mutually exclusive"
    (let [result (mcp-nrepl/validate-args ["-s" "-p" "8080"])]
      (is (contains? result :exit-message))
      (is (str/includes? (:exit-message result) "Cannot specify both --server and --nrepl-port"))))

  (testing "--server alone works correctly"
    (let [result (mcp-nrepl/validate-args ["--server"])]
      (is (= {:options {:server true}} result))
      (is (true? (get-in result [:options :server])))))

  (testing "No flags (implicit bridge mode) works correctly"
    (let [result (mcp-nrepl/validate-args [])]
      (is (= {:options {}} result))))

  (testing "--help flag shows usage"
    (let [result (mcp-nrepl/validate-args ["--help"])]
      (is (contains? result :exit-message))
      (is (true? (:ok? result)))
      (is (str/includes? (:exit-message result) "mcp-nrepl")))))

;; Test comprehensive CLI flag combinations
(deftest validate-args-handles-all-flag-combinations
  (testing "Valid flag combinations"
    (testing "--bridge with --eval works"
      (let [result (mcp-nrepl/validate-args ["--bridge" "--eval" "(+ 1 2)"])]
        (is (true? (get-in result [:options :bridge])))
        (is (= "(+ 1 2)" (get-in result [:options :eval])))))

    (testing "--server with --eval works"
      (let [result (mcp-nrepl/validate-args ["--server" "--eval" "(+ 1 2)"])]
        (is (true? (get-in result [:options :server])))
        (is (= "(+ 1 2)" (get-in result [:options :eval])))))

    (testing "--bridge with --nrepl-port and --eval works"
      (let [result (mcp-nrepl/validate-args ["--bridge" "--nrepl-port" "1667" "--eval" "(+ 1 2)"])]
        (is (true? (get-in result [:options :bridge])))
        (is (= 1667 (get-in result [:options :nrepl-port])))
        (is (= "(+ 1 2)" (get-in result [:options :eval])))))

    (testing "Short flags work: -b -p PORT"
      (let [result (mcp-nrepl/validate-args ["-b" "-p" "8080"])]
        (is (true? (get-in result [:options :bridge])))
        (is (= 8080 (get-in result [:options :nrepl-port])))))

    (testing "Short flags work: -s -e CODE"
      (let [result (mcp-nrepl/validate-args ["-s" "-e" "(+ 1 1)"])]
        (is (true? (get-in result [:options :server])))
        (is (= "(+ 1 1)" (get-in result [:options :eval])))))

    (testing "--nrepl-port without --bridge works (implicit bridge)"
      (let [result (mcp-nrepl/validate-args ["--nrepl-port" "9999"])]
        (is (= 9999 (get-in result [:options :nrepl-port])))
        (is (nil? (get-in result [:options :bridge])))))

    (testing "--eval alone works (implicit bridge)"
      (let [result (mcp-nrepl/validate-args ["--eval" "(println 'test)"])]
        (is (= "(println 'test)" (get-in result [:options :eval])))
        (is (nil? (get-in result [:options :bridge]))))))

  (testing "Invalid flag combinations (mutual exclusivity)"
    (testing "--bridge and --server together fails"
      (let [result (mcp-nrepl/validate-args ["--bridge" "--server"])]
        (is (contains? result :exit-message))
        (is (str/includes? (:exit-message result) "Cannot specify both --bridge and --server"))
        (is (nil? (:ok? result)))))

    (testing "--server and --bridge together fails (reversed order)"
      (let [result (mcp-nrepl/validate-args ["--server" "--bridge"])]
        (is (contains? result :exit-message))
        (is (str/includes? (:exit-message result) "Cannot specify both --bridge and --server"))))

    (testing "--bridge --server with --eval fails"
      (let [result (mcp-nrepl/validate-args ["--bridge" "--server" "--eval" "(+ 1 2)"])]
        (is (contains? result :exit-message))
        (is (str/includes? (:exit-message result) "Cannot specify both --bridge and --server"))))

    (testing "--bridge --server with --nrepl-port fails"
      (let [result (mcp-nrepl/validate-args ["--bridge" "--server" "--nrepl-port" "1667"])]
        (is (contains? result :exit-message))
        (is (str/includes? (:exit-message result) "Cannot specify both --bridge and --server"))))

    (testing "Short flags -b -s together fails"
      (let [result (mcp-nrepl/validate-args ["-b" "-s"])]
        (is (contains? result :exit-message))
        (is (str/includes? (:exit-message result) "Cannot specify both --bridge and --server")))))

  (testing "Invalid port numbers with various flags"
    (testing "--bridge with non-numeric port fails"
      (let [result (mcp-nrepl/validate-args ["--bridge" "--nrepl-port" "not-a-number"])]
        (is (contains? result :exit-message))
        (is (str/includes? (:exit-message result) "Must be a valid port number"))))

    (testing "--server with non-numeric port fails"
      (let [result (mcp-nrepl/validate-args ["--server" "--nrepl-port" "abc"])]
        (is (contains? result :exit-message))
        (is (str/includes? (:exit-message result) "Must be a valid port number"))))

    (testing "Non-numeric port alone fails"
      (let [result (mcp-nrepl/validate-args ["--nrepl-port" "invalid"])]
        (is (contains? result :exit-message))
        (is (str/includes? (:exit-message result) "Must be a valid port number"))))

    (testing "Empty port value fails"
      (let [result (mcp-nrepl/validate-args ["--nrepl-port" ""])]
        (is (contains? result :exit-message))
        (is (str/includes? (:exit-message result) "Must be a valid port number")))))

  (testing "Unknown flags"
    (testing "Unknown flag produces error"
      (let [result (mcp-nrepl/validate-args ["--unknown-flag"])]
        (is (contains? result :exit-message))
        (is (str/includes? (:exit-message result) "Unknown option"))))

    (testing "Unknown flag with valid flags produces error"
      (let [result (mcp-nrepl/validate-args ["--bridge" "--fake-option"])]
        (is (contains? result :exit-message))
        (is (str/includes? (:exit-message result) "Unknown option"))))))

(deftest eval-clojure-validates-timeout
  (testing "eval-clojure: Valid timeout values accepted"
    (let [result (mcp-nrepl/handle-tools-call
                  {"name" "eval-clojure"
                   "arguments" {"code" "(+ 1 2)" "timeout-ms" 5000}})]
      (is (not (str/includes? (get-in result ["content" 0 "text"]) "Error")))))

  (testing "eval-clojure: Invalid timeout rejected (below minimum)"
    (let [result (mcp-nrepl/handle-tools-call
                  {"name" "eval-clojure"
                   "arguments" {"code" "(+ 1 2)" "timeout-ms" 50}})]
      (is (str/includes? (get-in result ["content" 0 "text"]) "at least 100ms"))))

  (testing "eval-clojure: Invalid timeout rejected (above maximum)"
    (let [result (mcp-nrepl/handle-tools-call
                  {"name" "eval-clojure"
                   "arguments" {"code" "(+ 1 2)" "timeout-ms" 999999}})]
      (is (str/includes? (get-in result ["content" 0 "text"]) "cannot exceed 300000ms"))))

  (testing "eval-clojure: Default timeout when not specified"
    (let [result (mcp-nrepl/handle-tools-call
                  {"name" "eval-clojure"
                   "arguments" {"code" "(+ 1 2)"}})]
      (is (not (str/includes? (get-in result ["content" 0 "text"]) "Error")))))

  (testing "load-file: Valid timeout values accepted"
    (let [test-file "/tmp/test-timeout.clj"
          _ (spit test-file "(ns test-timeout)")
          result (mcp-nrepl/handle-tools-call
                  {"name" "load-file"
                   "arguments" {"file-path" test-file "timeout-ms" 5000}})]
      (is (not (str/includes? (get-in result ["content" 0 "text"]) "Error")))))

  (testing "load-file: Default timeout when not specified"
    (let [test-file "/tmp/test-timeout.clj"
          _ (spit test-file "(ns test-timeout)")
          result (mcp-nrepl/handle-tools-call
                  {"name" "load-file"
                   "arguments" {"file-path" test-file}})]
      (is (not (str/includes? (get-in result ["content" 0 "text"]) "Error"))))))

;; Main test runner
(defn run-all-tests []
  (println "Running unit tests for pure functions...")
  (let [results (run-tests 'unit-test)]
    (println (format "\nUnit Test Results: %d passed, %d failed, %d errors"
                     (:pass results)
                     (:fail results)
                     (:error results)))
    results))

;; Entry point
(when (= *file* (System/getProperty "babashka.file"))
  (let [results (run-all-tests)]
    (when (or (> (:fail results) 0) (> (:error results) 0))
      (System/exit 1))))