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
  (testing "Tools list includes all 11 tools (eval, load, set-namespace, get-doc, get-source, apropos, session tools, macroexpand)"
    (let [result (mcp-nrepl/handle-tools-list)
          tools (get result "tools")]
      (is (= 11 (count tools)) "Should have 11 tools")
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
      (is (some #(= "macroexpand-1" (get % "name")) tools)))))

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
(deftest handle-prompts-list-returns-all-prompts
  (testing "Returns all 5 prompts with correct structure"
    (let [result (mcp-nrepl/handle-prompts-list)
          prompts (get result "prompts")]
      (is (= 5 (count prompts)))
      (is (every? #(contains? % "name") prompts))
      (is (every? #(contains? % "description") prompts))
      (is (every? #(contains? % "arguments") prompts))))

  (testing "Prompt names are correct"
    (let [result (mcp-nrepl/handle-prompts-list)
          prompt-names (set (map #(get % "name") (get result "prompts")))]
      (is (contains? prompt-names "explore-namespace"))
      (is (contains? prompt-names "define-and-test"))
      (is (contains? prompt-names "load-and-explore"))
      (is (contains? prompt-names "debug-error"))
      (is (contains? prompt-names "search-and-learn"))))

  (testing "explore-namespace has no required arguments"
    (let [result (mcp-nrepl/handle-prompts-list)
          explore-prompt (first (filter #(= "explore-namespace" (get % "name"))
                                        (get result "prompts")))]
      (is (empty? (get explore-prompt "arguments")))))

  (testing "define-and-test has two required arguments"
    (let [result (mcp-nrepl/handle-prompts-list)
          define-prompt (first (filter #(= "define-and-test" (get % "name"))
                                       (get result "prompts")))
          args (get define-prompt "arguments")]
      (is (= 2 (count args)))
      (is (every? #(get % "required") args)))))

(deftest handle-prompts-get-returns-proper-messages
  (testing "explore-namespace returns message with proper structure"
    (let [result (mcp-nrepl/handle-prompts-get {"name" "explore-namespace"})
          messages (get result "messages")]
      (is (= 1 (count messages)))
      (is (= "user" (get-in messages [0 "role"])))
      (is (= "text" (get-in messages [0 "content" "type"])))
      (is (string? (get-in messages [0 "content" "text"])))
      (is (str/includes? (get-in messages [0 "content" "text"])
                         "current-namespace tool"))))

  (testing "define-and-test interpolates arguments correctly"
    (let [result (mcp-nrepl/handle-prompts-get
                   {"name" "define-and-test"
                    "arguments" {"function-name" "square"
                                 "function-code" "(defn square [x] (* x x))"}})
          message-text (get-in result ["messages" 0 "content" "text"])]
      (is (str/includes? message-text "square"))
      (is (str/includes? message-text "(defn square [x] (* x x))"))))

  (testing "load-and-explore interpolates file-path"
    (let [result (mcp-nrepl/handle-prompts-get
                   {"name" "load-and-explore"
                    "arguments" {"file-path" "src/my_file.clj"}})
          message-text (get-in result ["messages" 0 "content" "text"])]
      (is (str/includes? message-text "src/my_file.clj"))))

  (testing "search-and-learn interpolates search-term"
    (let [result (mcp-nrepl/handle-prompts-get
                   {"name" "search-and-learn"
                    "arguments" {"search-term" "map"}})
          message-text (get-in result ["messages" 0 "content" "text"])]
      (is (str/includes? message-text "map"))
      (is (str/includes? message-text "clojure://symbols/apropos/map")))))

(deftest handle-prompts-get-validates-required-arguments
  (testing "define-and-test throws when function-name missing"
    (is (thrown-with-msg? Exception #"Missing required argument: function-name"
          (mcp-nrepl/handle-prompts-get
            {"name" "define-and-test"
             "arguments" {"function-code" "(defn foo [] 42)"}}))))

  (testing "define-and-test throws when function-code missing"
    (is (thrown-with-msg? Exception #"Missing required argument: function-code"
          (mcp-nrepl/handle-prompts-get
            {"name" "define-and-test"
             "arguments" {"function-name" "foo"}}))))

  (testing "load-and-explore throws when file-path missing"
    (is (thrown-with-msg? Exception #"Missing required argument: file-path"
          (mcp-nrepl/handle-prompts-get
            {"name" "load-and-explore"
             "arguments" {}}))))

  (testing "debug-error throws when error-code missing"
    (is (thrown-with-msg? Exception #"Missing required argument: error-code"
          (mcp-nrepl/handle-prompts-get
            {"name" "debug-error"
             "arguments" {}}))))

  (testing "search-and-learn throws when search-term missing"
    (is (thrown-with-msg? Exception #"Missing required argument: search-term"
          (mcp-nrepl/handle-prompts-get
            {"name" "search-and-learn"
             "arguments" {}})))))

(deftest handle-prompts-get-rejects-unknown-prompts
  (testing "Throws exception for unknown prompt name"
    (is (thrown-with-msg? Exception #"Unknown prompt: nonexistent"
          (mcp-nrepl/handle-prompts-get {"name" "nonexistent"})))))

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