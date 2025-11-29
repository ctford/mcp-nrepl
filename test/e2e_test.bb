#!/usr/bin/env bb

(ns e2e-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.java.shell :as shell]
            [babashka.fs :as fs]
            [babashka.process :as proc]))

;; ANSI color codes
(def colors
  {:red "\033[0;31m"
   :green "\033[0;32m"
   :yellow "\033[1;33m"
   :nc "\033[0m"})

(defn color-print [color & args]
  (print (get colors color ""))
  (apply print args)
  (print (:nc colors))
  (println))

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

(defn start-nrepl-server []
  "Start a new Babashka nREPL server and return the port"
  (color-print :yellow "Starting new Babashka nREPL server...")
  (let [log-file "/tmp/nrepl-output.log"
        pid-file ".nrepl-pid"
        port-file ".nrepl-port"]

    ;; Clean up old files
    (fs/delete-if-exists log-file)
    (fs/delete-if-exists pid-file)
    (fs/delete-if-exists port-file)

    ;; Start nREPL server in background with random port (0 = auto-assign)
    (let [proc (proc/process ["bb" "nrepl-server" "localhost:0"]
                             {:out log-file
                              :err log-file})]
      ;; Save PID for reference
      (spit pid-file (str (:pid proc)))
      (color-print :green "nREPL server started with PID: " (:pid proc))

      ;; Wait for server to start and write to log
      (Thread/sleep 2000)

      ;; Extract port from log file
      (if (fs/exists? log-file)
        (let [log-content (slurp log-file)
              port-match (re-find #"127\.0\.0\.1:(\d+)" log-content)]
          (if port-match
            (let [port (second port-match)]
              (spit port-file port)
              (color-print :green "nREPL server listening on port: " port)
              port)
            (throw (ex-info "Failed to extract port from nREPL output"
                           {:log log-content}))))
        (throw (ex-info "nREPL log file not created" {}))))))

(defn setup-nrepl []
  "Ensure nREPL is running and return port"
  (cond
    ;; 1. Check for NREPL_PORT environment variable
    (System/getenv "NREPL_PORT")
    (let [port (System/getenv "NREPL_PORT")]
      (color-print :green "Using NREPL_PORT from environment: " port)
      port)

    ;; 2. Check for .nrepl-port file
    (fs/exists? ".nrepl-port")
    (let [port (str/trim (slurp ".nrepl-port"))]
      (color-print :green "Found existing .nrepl-port file with port: " port)
      port)

    ;; 3. Start new server
    :else
    (start-nrepl-server)))

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

(deftest test-session-introspection
  (testing "Can introspect session state"
    (let [port nrepl-port
          [init define vars-resp ns-resp] (run-mcp port
                                                    (mcp-initialize)
                                                    (mcp-eval 6 "(defn test-fn [] 42)")
                                                    (mcp-resource-read 7 "clojure://session/vars")
                                                    (mcp-resource-read 10 "clojure://session/namespaces"))
          vars (get-resource-text vars-resp)
          namespaces (get-resource-text ns-resp)]
      (color-print :green "✓ Session introspection successful")
      (is (str/includes? vars "test-fn"))
      (is (str/includes? namespaces "user")))))

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
                                    (:pass results)))
        (System/exit 0))
      (do
        (color-print :red "❌ Some tests failed")
        (color-print :red (format "   %d passed, %d failed, %d errors"
                                  (:pass results)
                                  (:fail results)
                                  (:error results)))
        (System/exit 1)))))

;; Entry point
(when (= *file* (System/getProperty "babashka.file"))
  (run-all-tests))
