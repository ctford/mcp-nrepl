;; Shared E2E test definitions for mcp-nrepl
;; This file is loaded by e2e_external_server_test.bb and e2e_internal_server_test.bb
;; The loading namespace must define a `run-mcp` function before loading this file

(require '[clojure.test :refer [deftest is testing]]
         '[clojure.string :as str]
         '[babashka.fs :as fs])

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

(defn mcp-get-vars
  ([id]
   (mcp-get-vars id nil))
  ([id namespace]
   {"jsonrpc" "2.0"
    "id" id
    "method" "tools/call"
    "params" {"name" "vars"
              "arguments" (if namespace
                            {"namespace" namespace}
                            {})}}))

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

;; Helper function
(defn get-result-text [response]
  (get-in response ["result" "content" 0 "text"]))

;; E2E Tests - these use run-mcp from the loading namespace
(deftest test-mcp-initialization
  (testing "MCP protocol initialization works"
    (let [[response] (run-mcp (mcp-initialize))]
      (color-print :green "✓ MCP initialization successful")
      (is (= "2024-11-05" (get-in response ["result" "protocolVersion"]))))))

(deftest test-function-definition-and-invocation
  (testing "Can define and invoke functions"
    (let [[init define invoke] (run-mcp (mcp-initialize)
                                        (mcp-eval 2 "(defn square [x] (* x x))")
                                        (mcp-eval 3 "(square 7)"))
          result (get-result-text invoke)]
      (color-print :green "✓ Function invocation successful: square(7) = " result)
      (is (= "49" result)))))

(deftest test-error-handling
  (testing "Error handling captures exceptions"
    (let [[init error] (run-mcp (mcp-initialize)
                                (mcp-eval 5 "(/ 1 0)"))
          error-text (get-result-text error)]
      (color-print :green "✓ Error handling verified")
      (is (str/includes? error-text "Divide by zero")))))

(deftest test-file-loading
  (testing "Can load Clojure files"
    (let [test-file "/tmp/test-e2e-file.clj"
          _ (spit test-file "(ns test-ns)\n(defn double-it [x] (* x 2))")
          [init load-resp test-resp] (run-mcp (mcp-initialize)
                                              (mcp-load-file 11 test-file)
                                              (mcp-eval 12 "(test-ns/double-it 5)"))
          result (get-result-text test-resp)]
      (fs/delete test-file)
      (color-print :green "✓ File loading successful: test-ns/double-it(5) = " result)
      (is (= "10" result)))))

(deftest test-namespace-switching
  (testing "Can switch namespaces"
    (let [[init set-ns-resp get-ns-resp] (run-mcp (mcp-initialize)
                                                   (mcp-set-namespace 14 "clojure.set")
                                                   (mcp-get-current-namespace 15))
          current-ns (get-result-text get-ns-resp)]
      (color-print :green "✓ Namespace switch successful: " current-ns)
      (is (str/includes? current-ns "clojure.set")))))

(deftest test-apropos
  (testing "Can search for symbols"
    (let [[init apropos-resp] (run-mcp (mcp-initialize)
                                       (mcp-apropos 16 "map"))
          result (get-result-text apropos-resp)]
      (color-print :green "✓ Apropos search successful")
      (is (str/includes? result "clojure.core/map")))))

(deftest test-vars
  (testing "Can list variables"
    (let [[init define vars-resp] (run-mcp (mcp-initialize)
                                           (mcp-eval 6 "(defn test-fn [] 42)")
                                           (mcp-get-vars 7))
          vars (get-result-text vars-resp)]
      (color-print :green "✓ Vars listing successful")
      (is (str/includes? vars "test-fn")))))

(deftest test-vars-with-namespace
  (testing "Can list variables from specific namespace"
    (let [[init vars-resp] (run-mcp (mcp-initialize)
                                    (mcp-get-vars 8 "clojure.set"))
          vars (get-result-text vars-resp)]
      (color-print :green "✓ Vars with namespace argument successful")
      (is (str/includes? vars "union"))
      (is (str/includes? vars "difference")))))

(deftest test-loaded-namespaces
  (testing "Can list loaded namespaces"
    (let [[init ns-resp] (run-mcp (mcp-initialize)
                                  (mcp-get-loaded-namespaces 10))
          namespaces (get-result-text ns-resp)]
      (color-print :green "✓ Loaded namespaces listing successful")
      (is (str/includes? namespaces "user")))))

(deftest test-doc-tool
  (testing "Doc tool retrieval"
    (let [[init doc-resp] (run-mcp (mcp-initialize)
                                   (mcp-get-doc 11 "map"))
          doc-text (get-result-text doc-resp)]
      (color-print :green "✓ Doc tool retrieval successful")
      (is (some? doc-text) "Doc tool should return content"))))

(deftest test-source-tool
  (testing "Source tool retrieval"
    (let [[init source-resp] (run-mcp (mcp-initialize)
                                      (mcp-get-source 12 "map"))
          source-text (get-result-text source-resp)]
      (color-print :green "✓ Source tool retrieval successful")
      (is (some? source-text) "Source tool should return content"))))

(deftest test-one-shot-eval-mode
  (testing "One-shot eval mode works"
    (let [result (run-eval-mode-test)
          output (str/trim result)]
      (color-print :green "✓ One-shot eval works: (+ 1 2 3) = " output)
      (is (= "6" output)))))

(deftest test-persistent-connection
  (testing "Persistent connection has no off-by-one bug"
    (let [[init r1 r2 r3 r4] (run-mcp (mcp-initialize)
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

(deftest test-macroexpand-all-tool
  (testing "Can fully expand macros"
    (let [[init expand] (run-mcp (mcp-initialize)
                                 (mcp-macroexpand-all 30 "(when x y)"))
          result (get-result-text expand)]
      (color-print :green "✓ Macroexpand-all tool works")
      (is (str/includes? result "(if x")))))

(deftest test-macroexpand-1-tool
  (testing "Can expand macro one step"
    (let [[init expand] (run-mcp (mcp-initialize)
                                 (mcp-macroexpand-1 31 "(when x y)"))
          result (get-result-text expand)]
      (color-print :green "✓ Macroexpand-1 tool works")
      (is (str/includes? result "(if x")))))

(defn mcp-eval-with-timeout [id code timeout-ms]
  {"jsonrpc" "2.0"
   "id" id
   "method" "tools/call"
   "params" {"name" "eval-clojure"
             "arguments" {"code" code
                         "timeout-ms" timeout-ms}}})

(defn mcp-load-file-with-timeout [id file-path timeout-ms]
  {"jsonrpc" "2.0"
   "id" id
   "method" "tools/call"
   "params" {"name" "load-file"
             "arguments" {"file-path" file-path
                         "timeout-ms" timeout-ms}}})

(deftest test-timeout-parameter-eval
  (testing "Can specify custom timeout for eval-clojure"
    (let [[init eval-resp] (run-mcp (mcp-initialize)
                                    (mcp-eval-with-timeout 40 "(+ 1 1)" 10000))
          result (get-result-text eval-resp)]
      (color-print :green "✓ Custom timeout parameter works for eval-clojure")
      (is (= "2" result)))))

(deftest test-timeout-parameter-load-file
  (testing "Can specify custom timeout for load-file"
    (let [test-file "/tmp/test-e2e-timeout.clj"
          _ (spit test-file "(ns test-timeout) (defn test-fn [] 42)")
          [init load-resp] (run-mcp (mcp-initialize)
                                    (mcp-load-file-with-timeout 41 test-file 10000))
          result (get-result-text load-resp)]
      (fs/delete test-file)
      (color-print :green "✓ Custom timeout parameter works for load-file")
      (is (some? result)))))
