#!/usr/bin/env bb

(ns unit-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; Load the main module for testing
(load-file "mcp-nrepl.bb")

;; Test pure functions only - no side effects, no I/O, no state mutations

;; Test parse-port function
(deftest test-parse-port
  (testing "Valid port numbers"
    (is (= 1667 (mcp-nrepl/parse-port "1667")))
    (is (= 8080 (mcp-nrepl/parse-port "8080")))
    (is (= 1234 (mcp-nrepl/parse-port " 1234 "))))
  
  (testing "Invalid port numbers"
    (is (nil? (mcp-nrepl/parse-port "abc")))
    (is (nil? (mcp-nrepl/parse-port "")))
    (is (nil? (mcp-nrepl/parse-port nil))))
  
  (testing "Valid but high port numbers"
    (is (= 65536 (mcp-nrepl/parse-port "65536")))
    (is (= 65535 (mcp-nrepl/parse-port "65535")))))

;; Test handle-initialize function (pure - no side effects)
(deftest test-handle-initialize
  (testing "Valid initialization parameters"
    (let [params {"protocolVersion" "2024-11-05"
                  "capabilities" {}
                  "clientInfo" {"name" "test" "version" "1.0"}}
          result (mcp-nrepl/handle-initialize params)]
      (is (= "2024-11-05" (get result "protocolVersion")))
      (is (map? (get result "capabilities")))
      (is (map? (get result "serverInfo")))
      (is (= "mcp-nrepl" (get-in result ["serverInfo" :name])))))
  
  (testing "Missing capabilities"
    (let [params {"protocolVersion" "2024-11-05"}
          result (mcp-nrepl/handle-initialize params)]
      (is (map? (get result "capabilities")))))
  
  (testing "Missing protocol version"
    (let [params {"capabilities" {}}
          result (mcp-nrepl/handle-initialize params)]
      (is (= "2024-11-05" (get result "protocolVersion"))))))

;; Test handle-tools-list function (pure)
(deftest test-handle-tools-list
  (testing "Tools list structure"
    (let [result (mcp-nrepl/handle-tools-list)]
      (is (vector? (get result "tools")))
      (is (= 1 (count (get result "tools"))))
      (let [tool (first (get result "tools"))]
        (is (= "eval-clojure" (get tool "name")))
        (is (string? (get tool "description")))
        (is (map? (get tool "inputSchema")))))))

;; Test handle-error function (pure)
(deftest test-handle-error
  (testing "Error response structure"
    (let [result (mcp-nrepl/handle-error 123 "Test error")]
      (is (= "2.0" (get result "jsonrpc")))
      (is (= 123 (get result "id")))
      (is (map? (get result "error")))
      (is (= -1 (get-in result ["error" "code"])))
      (is (= "Test error" (get-in result ["error" "message"])))))
  
  (testing "Nil id handling"
    (let [result (mcp-nrepl/handle-error nil "Error")]
      (is (nil? (get result "id")))
      (is (= "Error" (get-in result ["error" "message"]))))))


;; Test constants and data structures
(deftest test-constants
  (testing "MCP version is defined"
    (is (string? mcp-nrepl/MCP-VERSION))
    (is (= "2024-11-05" mcp-nrepl/MCP-VERSION)))
  
  (testing "Server info structure"
    (is (map? mcp-nrepl/SERVER-INFO))
    (is (= "mcp-nrepl" (:name mcp-nrepl/SERVER-INFO)))
    (is (string? (:version mcp-nrepl/SERVER-INFO)))))

;; Test JSON serialization (pure function behavior)
(deftest test-json-handling
  (testing "JSON round-trip"
    (let [data {"test" "value" "number" 42}
          json-str (json/generate-string data)
          parsed (json/parse-string json-str)]
      (is (= data parsed))))
  
  (testing "MCP message structure"
    (let [msg {"jsonrpc" "2.0" "id" 1 "method" "test"}
          json-str (json/generate-string msg)
          parsed (json/parse-string json-str)]
      (is (= "2.0" (get parsed "jsonrpc")))
      (is (= 1 (get parsed "id")))
      (is (= "test" (get parsed "method"))))))

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