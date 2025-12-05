#!/usr/bin/env bb

(ns e2e-external-server-test
  (:require [clojure.test :refer [run-tests]]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.java.shell :as shell]))

(load-file "test/test_utils.bb")
(refer 'test-utils)

;; Set up nREPL once before all tests
(def nrepl-port
  "Port for nREPL server, set up once before all tests run"
  (setup-nrepl))

;; External server mode: Connect to existing nREPL server
(defn run-mcp [& messages]
  "Send JSON-RPC messages to mcp-nrepl connected to external nREPL server"
  (let [port nrepl-port
        input (str/join "\n" (map json/generate-string messages))
        result (shell/sh "bb" "mcp-nrepl.bb" "--nrepl-port" (str port)
                         :in input)]
    (when (not= 0 (:exit result))
      (throw (ex-info "MCP command failed" result)))
    (mapv json/parse-string (str/split-lines (:out result)))))

;; Connectionless eval test for external server mode
(defn run-eval-mode-test []
  "Test connectionless eval mode with external nREPL"
  (let [result (shell/sh "bb" "mcp-nrepl.bb" "--nrepl-port" nrepl-port "--eval" "(+ 1 2 3)")]
    (:out result)))

;; Switch to e2e-external namespace for test isolation
(let [[init set-ns-resp] (run-mcp {"jsonrpc" "2.0" "id" 1 "method" "initialize"
                                   "params" {"protocolVersion" "2024-11-05" "capabilities" {}}}
                                  {"jsonrpc" "2.0" "id" 999 "method" "tools/call"
                                   "params" {"name" "set-namespace" "arguments" {"namespace" "e2e-external"}}})]
  (color-print :green "Switched to e2e namespace for test isolation"))

;; Load shared E2E test definitions
(load-file "test/e2e_test.bb")

;; Main test runner
(defn run-all-tests []
  (color-print :yellow "Starting end-to-end tests for mcp-nrepl...")
  (let [results (run-tests 'e2e-external-server-test)]
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
