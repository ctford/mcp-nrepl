#!/usr/bin/env bb

(ns e2e-internal-server-test
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
             "clientInfo" {"name" "e2e-internal-server-test" "version" "1.0.0"}}})

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

(defn mcp-set-namespace [id namespace]
  {"jsonrpc" "2.0"
   "id" id
   "method" "tools/call"
   "params" {"name" "set-namespace"
             "arguments" {"namespace" namespace}}})

(defn mcp-apropos [id query]
  {"jsonrpc" "2.0"
   "id" id
   "method" "tools/call"
   "params" {"name" "apropos"
             "arguments" {"query" query}}})

(defn mcp-get-doc [id symbol]
  {"jsonrpc" "2.0"
   "id" id
   "method" "tools/call"
   "params" {"name" "doc"
             "arguments" {"symbol" symbol}}})

(defn mcp-get-source [id symbol]
  {"jsonrpc" "2.0"
   "id" id
   "method" "tools/call"
   "params" {"name" "source"
             "arguments" {"symbol" symbol}}})

(defn mcp-get-session-vars [id]
  {"jsonrpc" "2.0"
   "id" id
   "method" "tools/call"
   "params" {"name" "session-vars"
             "arguments" {}}})

(defn mcp-get-loaded-namespaces [id]
  {"jsonrpc" "2.0"
   "id" id
   "method" "tools/call"
   "params" {"name" "loaded-namespaces"
             "arguments" {}}})

(defn mcp-get-current-namespace [id]
  {"jsonrpc" "2.0"
   "id" id
   "method" "tools/call"
   "params" {"name" "current-namespace"
             "arguments" {}}})

(defn mcp-prompts-list [id]
  {"jsonrpc" "2.0"
   "id" id
   "method" "prompts/list"
   "params" {}})

(defn mcp-prompts-get [id prompt-name arguments]
  {"jsonrpc" "2.0"
   "id" id
   "method" "prompts/get"
   "params" {"name" prompt-name
             "arguments" arguments}})

(defn mcp-macroexpand-all [id code]
  {"jsonrpc" "2.0"
   "id" id
   "method" "tools/call"
   "params" {"name" "macroexpand-all"
             "arguments" {"code" code}}})

(defn mcp-macroexpand-1 [id code]
  {"jsonrpc" "2.0"
   "id" id
   "method" "tools/call"
   "params" {"name" "macroexpand-1"
             "arguments" {"code" code}}})

;; Test utilities - Internal Server Mode (uses --server)
(defn run-mcp [& messages]
  "Send JSON-RPC messages to mcp-nrepl with embedded server and get parsed responses"
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

(defn get-result-text [response]
  (get-in response ["result" "content" 0 "text"]))

;; Switch to e2e-internal namespace for isolation from other test suites
(let [[init set-ns-resp] (run-mcp (mcp-initialize)
                                  (mcp-set-namespace 999 "e2e-internal"))]
  (color-print :green "Switched to e2e-internal namespace for test isolation (internal server)"))

;; E2E Tests
(deftest test-mcp-initialization
  (testing "MCP protocol initialization works"
    (let [
          [response] (run-mcp (mcp-initialize))]
      (color-print :green "✓ MCP initialization successful")
      (is (= "2024-11-05" (get-in response ["result" "protocolVersion"]))))))

(deftest test-function-definition-and-invocation
  (testing "Can define and invoke functions"
    (let [
          [init define invoke] (run-mcp
                                         (mcp-initialize)
                                         (mcp-eval 2 "(defn square [x] (* x x))")
                                         (mcp-eval 3 "(square 7)"))
          result (get-result-text invoke)]
      (color-print :green "✓ Function invocation successful: square(7) = " result)
      (is (= "49" result)))))

(deftest test-error-handling
  (testing "Error handling captures exceptions"
    (let [
          [init error] (run-mcp
                                (mcp-initialize)
                                (mcp-eval 5 "(/ 1 0)"))
          error-text (get-result-text error)]
      (color-print :green "✓ Error handling verified")
      (is (str/includes? error-text "Divide by zero")))))

(deftest test-file-loading
  (testing "Can load Clojure files"
    (let [
          test-file "/tmp/test-e2e-file.clj"
          _ (spit test-file "(ns test-ns)\n(defn double-it [x] (* x 2))")
          [init load-resp test-resp] (run-mcp
                                              (mcp-initialize)
                                              (mcp-load-file 11 test-file)
                                              (mcp-eval 12 "(test-ns/double-it 5)"))
          result (get-result-text test-resp)]
      (fs/delete test-file)
      (color-print :green "✓ File loading successful: test-ns/double-it(5) = " result)
      (is (= "10" result)))))

(deftest test-namespace-switching
  (testing "Can switch namespaces"
    (let [
          [init set-ns-resp get-ns-resp] (run-mcp
                                                   (mcp-initialize)
                                                   (mcp-set-namespace 14 "clojure.set")
                                                   (mcp-get-current-namespace 15))
          current-ns (get-result-text get-ns-resp)]
      (color-print :green "✓ Namespace switch successful: " current-ns)
      (is (str/includes? current-ns "clojure.set")))))

(deftest test-apropos
  (testing "Can search for symbols"
    (let [
          [init apropos-resp] (run-mcp
                                       (mcp-initialize)
                                       (mcp-apropos 16 "map"))
          result (get-result-text apropos-resp)]
      (color-print :green "✓ Apropos search successful")
      (is (str/includes? result "clojure.core/map")))))

(deftest test-session-vars
  (testing "Can list session variables"
    (let [
          [init define vars-resp] (run-mcp
                                           (mcp-initialize)
                                           (mcp-eval 6 "(defn test-fn [] 42)")
                                           (mcp-get-session-vars 7))
          vars (get-result-text vars-resp)]
      (color-print :green "✓ Session vars listing successful")
      (is (str/includes? vars "test-fn")))))

(deftest test-loaded-namespaces
  (testing "Can list loaded namespaces"
    (let [
          [init ns-resp] (run-mcp
                                  (mcp-initialize)
                                  (mcp-get-loaded-namespaces 10))
          namespaces (get-result-text ns-resp)]
      (color-print :green "✓ Loaded namespaces listing successful")
      (is (str/includes? namespaces "user")))))

(deftest test-doc-tool
  (testing "Doc tool retrieval"
    (let [
          [init doc-resp] (run-mcp
                                   (mcp-initialize)
                                   (mcp-get-doc 11 "map"))
          doc-text (get-result-text doc-resp)]
      (color-print :green "✓ Doc tool retrieval successful")
      (is (some? doc-text) "Doc tool should return content"))))

(deftest test-source-tool
  (testing "Source tool retrieval"
    (let [
          [init source-resp] (run-mcp
                                      (mcp-initialize)
                                      (mcp-get-source 12 "map"))
          source-text (get-result-text source-resp)]
      (color-print :green "✓ Source tool retrieval successful")
      (is (some? source-text) "Source tool should return content"))))

(deftest test-connectionless-eval-mode
  (testing "Connectionless eval mode works"
    (let [result (shell/sh "bb" "mcp-nrepl.bb" "--server" "--eval" "(+ 1 2 3)")
          ;; Filter out server startup message - take the last line
          output (str/trim (last (str/split-lines (:out result))))]
      (color-print :green "✓ Connectionless eval works: (+ 1 2 3) = " output)
      (is (= "6" output)))))

(deftest test-persistent-connection
  (testing "Persistent connection has no off-by-one bug"
    (let [
          [init r1 r2 r3 r4] (run-mcp
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

(deftest test-prompts-capability
  (testing "Initialization declares prompts capability"
    (let [[response] (run-mcp (mcp-initialize))
          capabilities (get-in response ["result" "capabilities"])]
      (color-print :green "✓ Prompts capability declared in initialization")
      (is (contains? capabilities "prompts")))))

(deftest test-prompts-list
  (testing "Can list available prompts"
    (let [[init list-resp] (run-mcp
                             (mcp-initialize)
                             (mcp-prompts-list 20))
          prompts (get-in list-resp ["result" "prompts"])]
      (color-print :green "✓ Prompts list returned 5 prompts")
      (is (= 5 (count prompts)))
      (is (some #(= "explore-namespace" (get % "name")) prompts))
      (is (some #(= "define-and-test" (get % "name")) prompts))
      (is (some #(= "load-and-explore" (get % "name")) prompts))
      (is (some #(= "debug-error" (get % "name")) prompts))
      (is (some #(= "search-and-learn" (get % "name")) prompts)))))

(deftest test-prompts-get-explore-namespace
  (testing "Can get explore-namespace prompt (no arguments)"
    (let [[init get-resp] (run-mcp
                            (mcp-initialize)
                            (mcp-prompts-get 21 "explore-namespace" {}))
          messages (get-in get-resp ["result" "messages"])]
      (color-print :green "✓ explore-namespace prompt returned messages")
      (is (= 1 (count messages)))
      (is (= "user" (get-in messages [0 "role"])))
      (is (str/includes? (get-in messages [0 "content" "text"])
                         "clojure://session/current-ns")))))

(deftest test-prompts-get-with-arguments
  (testing "Can get prompts with arguments"
    (let [[init get-resp] (run-mcp
                            (mcp-initialize)
                            (mcp-prompts-get 22 "define-and-test"
                                             {"function-name" "square"
                                              "function-code" "(defn square [x] (* x x))"}))
          message-text (get-in get-resp ["result" "messages" 0 "content" "text"])]
      (color-print :green "✓ define-and-test prompt interpolated arguments")
      (is (str/includes? message-text "square"))
      (is (str/includes? message-text "(defn square [x] (* x x))")))))

(deftest test-macroexpand-all-tool
  (testing "Can fully expand macros"
    (let [[init expand] (run-mcp
                          (mcp-initialize)
                          (mcp-macroexpand-all 30 "(when x y)"))
          result (get-result-text expand)]
      (color-print :green "✓ Macroexpand-all tool works")
      (is (str/includes? result "(if x")))))

(deftest test-macroexpand-1-tool
  (testing "Can expand macro one step"
    (let [[init expand] (run-mcp
                          (mcp-initialize)
                          (mcp-macroexpand-1 31 "(when x y)"))
          result (get-result-text expand)]
      (color-print :green "✓ Macroexpand-1 tool works")
      (is (str/includes? result "(if x")))))

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
