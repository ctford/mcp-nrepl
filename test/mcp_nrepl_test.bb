#!/usr/bin/env bb

(ns mcp-nrepl-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [clojure.data.json :as json]
            [clojure.string :as str]))

;; Load the main module for testing
(load-file "mcp-nrepl.bb")

;; Test data
(def valid-initialize-request
  {"jsonrpc" "2.0"
   "id" 1
   "method" "initialize"
   "params" {"protocolVersion" "2024-11-05"
             "capabilities" {}
             "clientInfo" {"name" "test-client" "version" "1.0.0"}}})

(def valid-tools-list-request
  {"jsonrpc" "2.0"
   "id" 2
   "method" "tools/list"
   "params" {}})

(def valid-tools-call-request
  {"jsonrpc" "2.0"
   "id" 3
   "method" "tools/call"
   "params" {"name" "eval-clojure"
             "arguments" {"code" "(+ 1 2 3)"}}})

(def invalid-json-request
  "{\"jsonrpc\": \"2.0\", \"id\": 1, \"method\": \"invalid}")

(def missing-method-request
  {"jsonrpc" "2.0"
   "id" 1
   "params" {}})

;; Mock nREPL responses for testing
(def mock-eval-responses
  [{"id" "test-id" "session" "test-session" "value" "6"}
   {"id" "test-id" "session" "test-session" "status" ["done"]}])

;; Test utilities
(defn reset-server-state! []
  (reset! mcp-nrepl/state {:nrepl-socket nil
                           :session-id nil
                           :initialized false}))

(defn parse-response [response-str]
  (json/read-str response-str))

;; MCP Message Parsing Tests
(deftest test-valid-initialize-request
  (testing "Valid initialize request should succeed"
    (reset-server-state!)
    (let [response (mcp-nrepl/handle-request valid-initialize-request)]
      (is (= "2.0" (get response "jsonrpc")))
      (is (= 1 (get response "id")))
      (is (contains? (get response "result") "protocolVersion"))
      (is (contains? (get response "result") "capabilities"))
      (is (contains? (get response "result") "serverInfo")))))

(deftest test-tools-list-request
  (testing "Tools list request should return available tools"
    (reset-server-state!)
    ;; Initialize first
    (mcp-nrepl/handle-request valid-initialize-request)
    (let [response (mcp-nrepl/handle-request valid-tools-list-request)]
      (is (= "2.0" (get response "jsonrpc")))
      (is (= 2 (get response "id")))
      (let [tools (get-in response ["result" "tools"])]
        (is (vector? tools))
        (is (= 1 (count tools)))
        (is (= "eval-clojure" (get-in tools [0 "name"])))))))

(deftest test-tools-call-request-structure
  (testing "Tools call request should have proper structure"
    (reset-server-state!)
    ;; Initialize first
    (mcp-nrepl/handle-request valid-initialize-request)
    ;; Mock the eval function to avoid nREPL dependency
    (with-redefs [mcp-nrepl/eval-clojure-code (fn [code] 
                                                 [{"value" "6" "status" ["done"]}])]
      (let [response (mcp-nrepl/handle-request valid-tools-call-request)]
        (is (= "2.0" (get response "jsonrpc")))
        (is (= 3 (get response "id")))
        (is (contains? (get response "result") "content"))
        (let [content (get-in response ["result" "content"])]
          (is (vector? content))
          (is (= "text" (get-in content [0 "type"]))))))))

(deftest test-missing-method-request
  (testing "Request without method should return error"
    (reset-server-state!)
    (let [response (mcp-nrepl/process-message (json/write-str missing-method-request))]
      (is (= "2.0" (get response "jsonrpc")))
      (is (contains? response "error"))
      (is (string? (get-in response ["error" "message"]))))))

(deftest test-uninitialized-server
  (testing "Requests to uninitialized server should fail"
    (reset-server-state!)
    (let [response (mcp-nrepl/process-message (json/write-str valid-tools-list-request))]
      (is (= "2.0" (get response "jsonrpc")))
      (is (contains? response "error"))
      (is (str/includes? (get-in response ["error" "message"]) "not initialized")))))

;; nREPL Message Translation Tests
(deftest test-eval-clojure-code-translation
  (testing "MCP eval request should translate to proper format"
    (let [code "(+ 1 2 3)"
          tool-call-params {"name" "eval-clojure"
                           "arguments" {"code" code}}]
      ;; Test that the tool call parameters are structured correctly
      (is (= "eval-clojure" (get tool-call-params "name")))
      (is (= code (get-in tool-call-params ["arguments" "code"]))))))

(deftest test-empty-code-handling
  (testing "Empty code should return error"
    (reset-server-state!)
    (mcp-nrepl/handle-request valid-initialize-request)
    (let [empty-code-request {"name" "eval-clojure"
                             "arguments" {"code" ""}}
          response (mcp-nrepl/handle-tools-call empty-code-request)]
      (is (true? (get response "isError")))
      (is (str/includes? (get-in response ["content" 0 "text"]) "required")))))

(deftest test-nil-code-handling
  (testing "Missing code should return error"
    (reset-server-state!)
    (mcp-nrepl/handle-request valid-initialize-request)
    (let [nil-code-request {"name" "eval-clojure"
                           "arguments" {}}
          response (mcp-nrepl/handle-tools-call nil-code-request)]
      (is (true? (get response "isError")))
      (is (str/includes? (get-in response ["content" 0 "text"]) "required")))))

(deftest test-unknown-tool-handling
  (testing "Unknown tool should return error"
    (reset-server-state!)
    (mcp-nrepl/handle-request valid-initialize-request)
    (let [unknown-tool-request {"name" "unknown-tool"
                               "arguments" {"code" "(+ 1 2)"}}
          response (mcp-nrepl/handle-tools-call unknown-tool-request)]
      (is (true? (get response "isError")))
      (is (str/includes? (get-in response ["content" 0 "text"]) "Unknown tool")))))

;; Error Response Translation Tests
(deftest test-error-response-structure
  (testing "Error responses should have proper structure"
    (let [error-response (mcp-nrepl/handle-error 123 "Test error message")]
      (is (= "2.0" (get error-response "jsonrpc")))
      (is (= 123 (get error-response "id")))
      (is (contains? error-response "error"))
      (is (= -1 (get-in error-response ["error" "code"])))
      (is (= "Test error message" (get-in error-response ["error" "message"]))))))

;; Integration Tests
(deftest test-message-processing-pipeline
  (testing "Complete message processing pipeline"
    (reset-server-state!)
    (let [init-json (json/write-str valid-initialize-request)
          init-response (mcp-nrepl/process-message init-json)]
      (is (= "2.0" (get init-response "jsonrpc")))
      (is (= 1 (get init-response "id")))
      (is (contains? init-response "result"))
      
      ;; Now test tools/list
      (let [list-json (json/write-str valid-tools-list-request)
            list-response (mcp-nrepl/process-message list-json)]
        (is (= "2.0" (get list-response "jsonrpc")))
        (is (= 2 (get list-response "id")))
        (is (contains? list-response "result"))))))

(deftest test-json-serialization-roundtrip
  (testing "JSON serialization should be consistent"
    (let [test-data {"jsonrpc" "2.0"
                    "id" 42
                    "result" {"tools" [{"name" "test-tool"}]}}
          json-str (json/write-str test-data)
          parsed (json/read-str json-str)]
      (is (= test-data parsed)))))

;; Utility function tests
(deftest test-server-info-constants
  (testing "Server info should be properly defined"
    (is (= "mcp-nrepl" (get mcp-nrepl/SERVER-INFO :name)))
    (is (string? (get mcp-nrepl/SERVER-INFO :version)))
    (is (string? mcp-nrepl/MCP-VERSION))))

;; Main test runner
(defn run-all-tests []
  (println "Running mcp-nrepl test suite...")
  (let [results (run-tests 'mcp-nrepl-test)]
    (println (format "\nTest Results: %d passed, %d failed, %d errors"
                     (:pass results)
                     (:fail results)
                     (:error results)))
    (when (or (> (:fail results) 0) (> (:error results) 0))
      (System/exit 1))))

;; Entry point
(when (= *file* (System/getProperty "babashka.file"))
  (run-all-tests))