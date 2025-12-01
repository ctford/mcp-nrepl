#!/usr/bin/env bb

(ns e2e-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.java.shell :as shell]
            [babashka.fs :as fs]
            [babashka.process :as proc]))

(load-file "test/test_utils.bb")
(refer 'test-utils)

;; MCP message builders
(defn mcp-initialize []
  {"jsonrpc" "2.0"
   "id" 1
   "method" "initialize"
   "params" {"protocolVersion" "2024-11-05"
             "capabilities" {}
             "clientInfo" {"name" "e2e-test" "version" "1.0.0"}}})

(defn mcp-eval [id code]
  {"jsonrpc" "2.0"
   "id" id
   "method" "tools/call"
   "params" {"name" "eval-clojure"
             "arguments" {"code" code}}})

(defn mcp-load-file [id file-path]
  {"jsonrpc" "2.0"
   "id" id
   "method" "tools/call"
   "params" {"name" "load-file"
             "arguments" {"file-path" file-path}}})

(defn mcp-set-ns [id namespace]
  {"jsonrpc" "2.0"
   "id" id
   "method" "tools/call"
   "params" {"name" "set-ns"
             "arguments" {"namespace" namespace}}})

(defn mcp-apropos [id query]
  {"jsonrpc" "2.0"
   "id" id
   "method" "resources/read"
   "params" {"uri" (str "clojure://symbols/apropos/" query)}})

(defn mcp-resource-read [id uri]
  {"jsonrpc" "2.0"
   "id" id
   "method" "resources/read"
   "params" {"uri" uri}})

;; Test utilities
(defn run-mcp [port & messages]
  "Send JSON-RPC messages to mcp-nrepl and get parsed responses"
  (let [input (str/join "\n" (map json/generate-string messages))
        result (shell/sh "bb" "mcp-nrepl.bb" "--nrepl-port" (str port)
                         :in input)]
    (when (not= 0 (:exit result))
      (throw (ex-info "MCP command failed" result)))
    (mapv json/parse-string (str/split-lines (:out result)))))

(defn get-result-text [response]
  (get-in response ["result" "content" 0 "text"]))

(defn get-resource-text [response]
  (get-in response ["result" "contents" 0 "text"]))

;; Set up nREPL once before all tests
(def nrepl-port
  "Port for nREPL server, set up once before all tests run"
  (setup-nrepl))

;; E2E Tests
(deftest test-mcp-initialization
  (testing "MCP protocol initialization works"
    (let [port nrepl-port
          [response] (run-mcp port (mcp-initialize))]
      (color-print :green "✓ MCP initialization successful")
      (is (= "2024-11-05" (get-in response ["result" "protocolVersion"]))))))

(deftest test-function-definition-and-invocation
  (testing "Can define and invoke functions"
    (let [port nrepl-port
          [init define invoke] (run-mcp port
                                         (mcp-initialize)
                                         (mcp-eval 2 "(defn square [x] (* x x))")
                                         (mcp-eval 3 "(square 7)"))
          result (get-result-text invoke)]
      (color-print :green "✓ Function invocation successful: square(7) = " result)
      (is (= "49" result)))))

(deftest test-error-handling
  (testing "Error handling captures exceptions"
    (let [port nrepl-port
          [init error] (run-mcp port
                                (mcp-initialize)
                                (mcp-eval 5 "(/ 1 0)"))
          error-text (get-result-text error)]
      (color-print :green "✓ Error handling verified")
      (is (str/includes? error-text "ArithmeticException")))))

(deftest test-file-loading
  (testing "Can load Clojure files"
    (let [port nrepl-port
          test-file "/tmp/test-e2e-file.clj"
          _ (spit test-file "(ns test-ns)\n(defn double-it [x] (* x 2))")
          [init load-resp test-resp] (run-mcp port
                                              (mcp-initialize)
                                              (mcp-load-file 11 test-file)
                                              (mcp-eval 12 "(test-ns/double-it 5)"))
          result (get-result-text test-resp)]
      (fs/delete test-file)
      (color-print :green "✓ File loading successful: test-ns/double-it(5) = " result)
      (is (= "10" result)))))

(deftest test-namespace-switching
  (testing "Can switch namespaces"
    (let [port nrepl-port
          [init set-ns-resp get-ns-resp] (run-mcp port
                                                   (mcp-initialize)
                                                   (mcp-set-ns 14 "clojure.set")
                                                   (mcp-resource-read 15 "clojure://session/current-ns"))
          current-ns (get-resource-text get-ns-resp)]
      (color-print :green "✓ Namespace switch successful: " current-ns)
      (is (str/includes? current-ns "clojure.set")))))

(deftest test-apropos
  (testing "Can search for symbols"
    (let [port nrepl-port
          [init apropos-resp] (run-mcp port
                                       (mcp-initialize)
                                       (mcp-apropos 16 "map"))
          result (get-resource-text apropos-resp)]
      (color-print :green "✓ Apropos search successful")
      (is (str/includes? result "clojure.core/map")))))

(deftest test-session-vars
  (testing "Can list session variables"
    (let [port nrepl-port
          [init define vars-resp] (run-mcp port
                                           (mcp-initialize)
                                           (mcp-eval 6 "(defn test-fn [] 42)")
                                           (mcp-resource-read 7 "clojure://session/vars"))
          vars (get-resource-text vars-resp)]
      (color-print :green "✓ Session vars listing successful")
      (is (str/includes? vars "test-fn")))))

(deftest test-session-namespaces
  (testing "Can list session namespaces"
    (let [port nrepl-port
          [init ns-resp] (run-mcp port
                                  (mcp-initialize)
                                  (mcp-resource-read 10 "clojure://session/namespaces"))
          namespaces (get-resource-text ns-resp)]
      (color-print :green "✓ Session namespaces listing successful")
      (is (str/includes? namespaces "user")))))

(deftest test-doc-resource
  (testing "Doc resource retrieval"
    (let [port nrepl-port
          [init doc-resp] (run-mcp port
                                   (mcp-initialize)
                                   (mcp-resource-read 11 "clojure://doc/map"))
          doc-text (get-resource-text doc-resp)]
      (color-print :green "✓ Doc resource retrieval successful")
      (is (some? doc-text) "Doc resource should return content"))))

(deftest test-source-resource
  (testing "Source resource retrieval"
    (let [port nrepl-port
          [init source-resp] (run-mcp port
                                      (mcp-initialize)
                                      (mcp-resource-read 12 "clojure://source/map"))
          source-text (get-resource-text source-resp)]
      (color-print :green "✓ Source resource retrieval successful")
      (is (some? source-text) "Source resource should return content"))))

(deftest test-connectionless-eval-mode
  (testing "Connectionless eval mode works"
    (let [result (shell/sh "bb" "mcp-nrepl.bb" "--nrepl-port" nrepl-port "--eval" "(+ 1 2 3)")
          output (str/trim (:out result))]
      (color-print :green "✓ Connectionless eval works: (+ 1 2 3) = " output)
      (is (= "6" output)))))

(deftest test-persistent-connection
  (testing "Persistent connection has no off-by-one bug"
    (let [port nrepl-port
          [init r1 r2 r3 r4] (run-mcp port
                                      (mcp-initialize)
                                      (mcp-eval 100 "(+ 1 1)")
                                      (mcp-eval 101 "(+ 5 5)")
                                      (mcp-eval 102 "(* 7 8)")
                                      (mcp-eval 103 "(- 100 42)"))
          results [(get-result-text r1)
                   (get-result-text r2)
                   (get-result-text r3)
                   (get-result-text r4)]]
      (color-print :green "✓ Persistent connection test passed")
      (is (= ["2" "10" "56" "58"] results)))))

;; Main test runner
(defn run-all-tests []
  (color-print :yellow "Starting end-to-end tests for mcp-nrepl...")
  (let [results (run-tests 'e2e-test)]
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
