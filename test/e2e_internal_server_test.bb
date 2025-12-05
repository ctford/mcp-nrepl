#!/usr/bin/env bb

(ns e2e-internal-server-test
  (:require [clojure.test :refer [run-tests]]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.java.shell :as shell]))

(load-file "test/test_utils.bb")
(refer 'test-utils)

;; Internal server mode: Use embedded nREPL server (--server flag)
(defn run-mcp [& messages]
  "Send JSON-RPC messages to mcp-nrepl with embedded server"
  (let [input (str/join "\n" (map json/generate-string messages))
        result (shell/sh "bb" "mcp-nrepl.bb" "--server"
                         :in input)]
    (when (not= 0 (:exit result))
      (throw (ex-info "MCP command failed" result)))
    ;; Filter out non-JSON lines (nREPL server startup messages, etc.)
    (->> (:out result)
         str/split-lines
         (filter #(str/starts-with? % "{"))
         (mapv json/parse-string))))

;; Connectionless eval test for internal server mode
(defn run-eval-mode-test []
  "Test connectionless eval mode with embedded nREPL server"
  (let [result (shell/sh "bb" "mcp-nrepl.bb" "--server" "--eval" "(+ 1 2 3)")
        ;; Filter out server startup message - take the last line
        output (str/trim (last (str/split-lines (:out result))))]
    output))

;; Switch to e2e-internal namespace for test isolation
(let [[init set-ns-resp] (run-mcp {"jsonrpc" "2.0" "id" 1 "method" "initialize"
                                   "params" {"protocolVersion" "2024-11-05" "capabilities" {}}}
                                  {"jsonrpc" "2.0" "id" 999 "method" "tools/call"
                                   "params" {"name" "set-namespace" "arguments" {"namespace" "e2e-internal"}}})]
  (color-print :green "Switched to e2e-internal namespace for test isolation (internal server)"))

;; Load shared E2E test definitions
(load-file "test/e2e_test.bb")

;; Main test runner
(defn run-all-tests []
  (color-print :yellow "Starting end-to-end tests for mcp-nrepl...")
  (let [results (run-tests 'e2e-internal-server-test)]
    (println)
    (if (and (zero? (:fail results)) (zero? (:error results)))
      (do
        (color-print :green "✅ All end-to-end tests passed!")
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
