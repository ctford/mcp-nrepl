#!/usr/bin/env bb

(ns e2e-external-server-test
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
             "clientInfo" {"name" "e2e-external-server-test" "version" "1.0.0"}}})

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

(defn mcp-get-session-namespaces [id]
  {"jsonrpc" "2.0"
   "id" id
   "method" "tools/call"
   "params" {"name" "session-namespaces"
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
  "Extract text from three content blocks, joining non-empty ones with newlines"
  (let [content (get-in response ["result" "content"])
        parts (map #(get % "text") content)]
    (->> parts
         (filter #(not (str/blank? %)))
         (str/join "\n"))))

(defn get-output [response]
  "Get stdout (content block 0)"
  (get-in response ["result" "content" 0 "text"]))

(defn get-error [response]
  "Get stderr (content block 1)"
  (get-in response ["result" "content" 1 "text"]))

(defn get-value [response]
  "Get return value (content block 2)"
  (get-in response ["result" "content" 2 "text"]))

;; Set up nREPL once before all tests
(def nrepl-port
  "Port for nREPL server, set up once before all tests run"
  (setup-nrepl))

;; Switch to e2e namespace for isolation from other test suites
(let [[init set-ns-resp] (run-mcp nrepl-port
                                  (mcp-initialize)
                                  (mcp-set-namespace 999 "e2e-external"))]
  (color-print :green "Switched to e2e namespace for test isolation"))

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
                                                   (mcp-set-namespace 14 "clojure.set")
                                                   (mcp-get-current-namespace 15))
          current-ns (get-result-text get-ns-resp)]
      (color-print :green "✓ Namespace switch successful: " current-ns)
      (is (str/includes? current-ns "clojure.set")))))

(deftest test-apropos
  (testing "Can search for symbols"
    (let [port nrepl-port
          [init apropos-resp] (run-mcp port
                                       (mcp-initialize)
                                       (mcp-apropos 16 "map"))
          result (get-result-text apropos-resp)]
      (color-print :green "✓ Apropos search successful")
      (is (str/includes? result "clojure.core/map")))))

(deftest test-session-vars
  (testing "Can list session variables"
    (let [port nrepl-port
          [init define vars-resp] (run-mcp port
                                           (mcp-initialize)
                                           (mcp-eval 6 "(defn test-fn [] 42)")
                                           (mcp-get-session-vars 7))
          vars (get-result-text vars-resp)]
      (color-print :green "✓ Session vars listing successful")
      (is (str/includes? vars "test-fn")))))

(deftest test-session-namespaces
  (testing "Can list session namespaces"
    (let [port nrepl-port
          [init ns-resp] (run-mcp port
                                  (mcp-initialize)
                                  (mcp-get-session-namespaces 10))
          namespaces (get-result-text ns-resp)]
      (color-print :green "✓ Session namespaces listing successful")
      (is (str/includes? namespaces "user")))))

(deftest test-doc-tool
  (testing "Doc tool retrieval"
    (let [port nrepl-port
          [init doc-resp] (run-mcp port
                                   (mcp-initialize)
                                   (mcp-get-doc 11 "map"))
          doc-text (get-result-text doc-resp)]
      (color-print :green "✓ Doc tool retrieval successful")
      (is (some? doc-text) "Doc tool should return content"))))

(deftest test-source-tool
  (testing "Source tool retrieval"
    (let [port nrepl-port
          [init source-resp] (run-mcp port
                                      (mcp-initialize)
                                      (mcp-get-source 12 "map"))
          source-text (get-result-text source-resp)]
      (color-print :green "✓ Source tool retrieval successful")
      (is (some? source-text) "Source tool should return content"))))

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

(deftest test-prompts-capability
  (testing "Initialization declares prompts capability"
    (let [port nrepl-port
          [response] (run-mcp port (mcp-initialize))
          capabilities (get-in response ["result" "capabilities"])]
      (color-print :green "✓ Prompts capability declared in initialization")
      (is (contains? capabilities "prompts")))))

(deftest test-prompts-list
  (testing "Can list available prompts"
    (let [port nrepl-port
          [init list-resp] (run-mcp port
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
    (let [port nrepl-port
          [init get-resp] (run-mcp port
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
    (let [port nrepl-port
          [init get-resp] (run-mcp port
                                   (mcp-initialize)
                                   (mcp-prompts-get 22 "define-and-test"
                                                    {"function-name" "square"
                                                     "function-code" "(defn square [x] (* x x))"}))
          message-text (get-in get-resp ["result" "messages" 0 "content" "text"])]
      (color-print :green "✓ define-and-test prompt interpolated arguments")
      (is (str/includes? message-text "square"))
      (is (str/includes? message-text "(defn square [x] (* x x))")))))

(deftest test-content-block-extraction
  (testing "Can extract individual content blocks from mixed output"
    (let [port nrepl-port
          [init result] (run-mcp port
                                 (mcp-initialize)
                                 (mcp-eval 30 "(do (println \"debug\") (binding [*out* *err*] (println \"warning\")) 42)"))]
      (color-print :green "✓ Content block extraction test passed")
      (is (= "debug\n" (get-output result)))
      (is (= "warning\n" (get-error result)))
      (is (= "42" (get-value result)))))

  (testing "All three content blocks are present in response"
    (let [port nrepl-port
          [init result] (run-mcp port
                                 (mcp-initialize)
                                 (mcp-eval 31 "(+ 1 2 3)"))
          content (get-in result ["result" "content"])]
      (is (= 3 (count content)))
      (is (= "text" (get-in content [0 "type"])))
      (is (= "text" (get-in content [1 "type"])))
      (is (= "text" (get-in content [2 "type"]))))))

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
