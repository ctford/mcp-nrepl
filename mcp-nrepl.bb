(ns mcp-nrepl
  (:require [babashka.fs :as fs]
            [babashka.nrepl.server :as nrepl-server]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [bencode.core :as bencode]))

;; MCP Protocol constants
(def MCP-VERSION "2024-11-05")
(def SERVER-INFO {:name "mcp-nrepl" :version "0.1.0"})
(def MAX-REQUEST-SIZE 65536) ;; 64 KB - maximum JSON-RPC request size

;; Resource URI prefixes
(def DOC-URI-PREFIX "clojure://doc/")
(def SOURCE-URI-PREFIX "clojure://source/")
(def APROPOS-URI-PREFIX "clojure://symbols/apropos/")

;; Global state
(def state (atom {:nrepl-input-stream nil
                  :nrepl-output-stream nil
                  :nrepl-socket nil
                  :session-id nil
                  :initialized false
                  :nrepl-port nil
                  :embedded-server nil}))

;; Utility functions
(defn log-error [msg & args]
  (binding [*out* *err*]
    (println (str "[ERROR] " (apply format msg args)))))

(defn decode-bytes
  "Convert bytes to UTF-8 string"
  [v]
  (String. v "UTF-8"))

(defn parse-port [port-str]
  "Pure function: parse port string to integer, returns nil if invalid"
  (when port-str
    (parse-long (str/trim port-str))))

(defn read-nrepl-port [& [provided-port]]
  (cond
    provided-port
    (or (parse-port provided-port)
        (do (log-error "Invalid port number: %s" provided-port)
            nil))

    (fs/exists? ".nrepl-port")
    (try
      (let [port-str (slurp ".nrepl-port")
            port (parse-port port-str)]
        (or port
            (do (log-error "Invalid port number in .nrepl-port: %s" port-str)
                nil)))
      (catch Exception e
        (log-error "Failed to read .nrepl-port: %s" (.getMessage e))
        nil))

    :else
    (do
      (log-error "No nREPL port specified. Use --nrepl-port <port> or create .nrepl-port file")
      nil)))

(defn connect-to-nrepl [port]
  (try
    (let [socket (java.net.Socket. "localhost" port)
          input-stream (java.io.PushbackInputStream. (.getInputStream socket))
          output-stream (.getOutputStream socket)]
      (.setSoTimeout socket 2000)
      {:input-stream input-stream
       :output-stream output-stream
       :socket socket})
    (catch Exception e
      (log-error "Failed to connect to nREPL on port %d: %s" port (.getMessage e))
      nil)))

(defn send-nrepl-message [msg]
  (try
    (when-let [out (:nrepl-output-stream @state)]
      (bencode/write-bencode out msg)
      (.flush out)
      true)
    (catch Exception e
      (log-error "Failed to send nREPL message: %s" (.getMessage e))
      false)))

(defn read-nrepl-response []
  (try
    (when-let [in (:nrepl-input-stream @state)]
      (let [response (bencode/read-bencode in)]
        (if (map? response)
          response
          (do
            (log-error "Invalid nREPL response (not a map): %s" response)
            nil))))
    (catch Exception e
      (log-error "Failed to read nREPL response: %s" (.getMessage e))
      nil)))

(defn create-session []
  (let [clone-msg {"op" "clone" "id" (str (java.util.UUID/randomUUID))}]
    (when (send-nrepl-message clone-msg)
      (some-> (read-nrepl-response)
              (get "new-session")))))

(defn ensure-nrepl-connection []
  (when-not (:nrepl-input-stream @state)
    (when-let [port (or (:nrepl-port @state) (read-nrepl-port))]
      (when-let [{:keys [input-stream output-stream socket]} (connect-to-nrepl port)]
        (swap! state assoc
               :nrepl-input-stream input-stream
               :nrepl-output-stream output-stream
               :nrepl-socket socket)
        (when-let [session-id (create-session)]
          (swap! state assoc :session-id session-id))))))

(defn with-socket-timeout
  "Temporarily set socket timeout, execute function, then restore original timeout"
  [timeout-ms f]
  (ensure-nrepl-connection)
  (let [{:keys [nrepl-socket]} @state]
    (if-not nrepl-socket
      (throw (Exception. "No nREPL connection available"))
      (let [original-timeout (.getSoTimeout nrepl-socket)]
        (try
          (.setSoTimeout nrepl-socket timeout-ms)
          (f)
          (finally
            (.setSoTimeout nrepl-socket original-timeout)))))))

(defn collect-nrepl-responses
  "Collect all nREPL responses until a 'done' status is received"
  []
  (loop [responses []]
    (if-let [response (read-nrepl-response)]
      (let [updated-responses (conj responses response)
            status (get response "status")]
        ;; Keep reading until we get a status containing "done"
        (if (and status
                 (some #(= (decode-bytes %) "done") status))
          updated-responses
          (recur updated-responses)))
      responses)))

(defn eval-nrepl-code
  "Evaluate code via nREPL and return all responses.
   This is the common pattern used by all resource and tool functions."
  [code]
  (ensure-nrepl-connection)
  (let [{:keys [session-id]} @state
        msg {"op" "eval"
             "code" code
             "session" session-id
             "id" (str (java.util.UUID/randomUUID))}]
    (when (send-nrepl-message msg)
      (collect-nrepl-responses))))

(defn eval-clojure-code [code]
  "Evaluate Clojure code and return responses. Throws exception on failure."
  (if-let [responses (eval-nrepl-code code)]
    responses
    (throw (Exception. "No nREPL connection available"))))

;; nREPL Resource Operations

;; Helper functions for extracting data from nREPL responses
(defn extract-nrepl-output
  "Extract and join 'out' field from responses, trimming whitespace"
  [responses]
  (->> responses
       (keep #(get % "out"))
       (map decode-bytes)
       (str/join "")
       str/trim))

(defn extract-nrepl-value
  "Extract and decode 'value' field from first response"
  [responses]
  (->> responses
       (keep #(get % "value"))
       (map decode-bytes)
       first))

(defn valid-symbol-name? [s]
  "Check if string is a valid, safe symbol name (alphanumeric, -, _, *, +, !, ?, <, >, =, /)"
  (and (string? s)
       (not (str/blank? s))
       (re-matches #"^[a-zA-Z0-9\-_\*\+\!\?\<\>\=/\.]+$" s)))

(defn get-doc [symbol-str]
  "Get documentation for a symbol by evaluating (clojure.repl/doc symbol)"
  (when (valid-symbol-name? symbol-str)
    (some-> (eval-nrepl-code (str "(clojure.repl/doc " symbol-str ")"))
            extract-nrepl-output)))

(defn get-source [symbol-str]
  "Get source code for a symbol by evaluating (clojure.repl/source symbol)"
  (when (valid-symbol-name? symbol-str)
    (some-> (eval-nrepl-code (str "(clojure.repl/source " symbol-str ")"))
            extract-nrepl-output)))

(defn get-vars
  "Get list of public variables in a namespace (defaults to current namespace)"
  ([]
   (get-vars nil))
  ([namespace]
   (let [ns-code (if namespace
                   (str "(find-ns '" namespace ")")
                   "*ns*")]
     (some-> (eval-nrepl-code (str "(when-let [ns " ns-code "] (keys (ns-publics ns)))"))
             extract-nrepl-value))))

(defn get-loaded-namespaces []
  "Get list of all loaded namespaces"
  (some-> (eval-nrepl-code "(map str (all-ns))")
          extract-nrepl-value))

(defn get-current-namespace []
  "Get the current default namespace"
  (some-> (eval-nrepl-code "(str *ns*)")
          extract-nrepl-value))

;; MCP Protocol handlers

;; Tool response formatting helpers
(defn extract-field-from-responses
  "Extract and decode a field from nREPL responses"
  [responses field]
  (->> responses
       (keep #(get % field))
       (map decode-bytes)))

(defn format-tool-result
  "Format nREPL responses into a tool result"
  [responses & {:keys [default-message]}]
  (let [values (extract-field-from-responses responses "value")
        output (str/join "\n" (extract-field-from-responses responses "out"))
        errors (str/join "\n" (extract-field-from-responses responses "err"))
        result-text (str/join "\n"
                              (concat
                               (when-not (str/blank? output) [output])
                               (when-not (str/blank? errors) [errors])
                               values))]
    {"content" [{"type" "text"
                "text" (if (str/blank? result-text)
                         (or default-message "nil")
                         result-text)}]}))

(defn format-tool-error
  "Format an error message as a tool response"
  [error-msg]
  {"isError" true
   "content" [{"type" "text"
              "text" error-msg}]})

(defn with-required-param
  "Helper to validate a required parameter and handle errors.
   Calls f with the parameter value if valid, otherwise returns error."
  [arguments param-name error-context f]
  (let [param (get arguments param-name)]
    (if (str/blank? param)
      (format-tool-error (str "Error: " param-name " parameter is required and cannot be empty"))
      (try
        (f param)
        (catch Exception e
          (format-tool-error (str "Error " error-context ": " (.getMessage e))))))))

(defn handle-initialize [params]
  (let [client-version (get params "protocolVersion")
        capabilities (get params "capabilities" {})]
    (swap! state assoc :initialized true)
    {"protocolVersion" MCP-VERSION
     "capabilities" {"tools" {}
                     "resources" {}
                     "prompts" {}}
     "serverInfo" SERVER-INFO}))

(defn handle-tools-list []
  {"tools"
   [{"name" "eval-clojure"
     "description" "Evaluate Clojure code using nREPL"
     "inputSchema"
     {"type" "object"
      "properties"
      {"code" {"type" "string"
               "description" "The Clojure code to evaluate (e.g., \"(+ 1 2 3)\", \"(defn square [x] (* x x))\")"}
       "timeout-ms" {"type" "number"
                     "description" "Optional timeout in milliseconds (default: 2000ms). Increase for long-running operations like complex computations."}}
      "required" ["code"]}}
    {"name" "load-file"
     "description" "Load and evaluate a Clojure file using nREPL"
     "inputSchema"
     {"type" "object"
      "properties"
      {"file-path" {"type" "string"
                    "description" "The path to the Clojure file to load (e.g., \"src/myapp/core.clj\", \"test/myapp/core_test.clj\")"}
       "timeout-ms" {"type" "number"
                     "description" "Optional timeout in milliseconds (default: 2000ms). Increase for large files."}}
      "required" ["file-path"]}}
    {"name" "set-namespace"
     "description" "Switch to a different namespace in the nREPL session"
     "inputSchema"
     {"type" "object"
      "properties"
      {"namespace" {"type" "string"
                    "description" "The namespace to switch to (e.g., \"clojure.set\", \"user\", \"myapp.core\")"}}
      "required" ["namespace"]}}
    {"name" "doc"
     "description" "Get documentation for a Clojure symbol"
     "inputSchema"
     {"type" "object"
      "properties"
      {"symbol" {"type" "string"
                 "description" "The symbol name to get documentation for (e.g., \"map\", \"clojure.set/union\", \"filter\")"}}
      "required" ["symbol"]}}
    {"name" "source"
     "description" "Get source code for a Clojure symbol"
     "inputSchema"
     {"type" "object"
      "properties"
      {"symbol" {"type" "string"
                 "description" "The symbol name to get source code for (e.g., \"map\", \"clojure.set/union\", \"reduce\")"}}
      "required" ["symbol"]}}
    {"name" "apropos"
     "description" "Search for symbols matching a pattern in their name or documentation"
     "inputSchema"
     {"type" "object"
      "properties"
      {"query" {"type" "string"
                "description" "The search pattern to match against symbol names (e.g., \"map\" finds map, map?, mapcat, keep-indexed)"}}
      "required" ["query"]}}
    {"name" "vars"
     "description" "Get list of currently defined variables in a namespace (defaults to current namespace)"
     "inputSchema"
     {"type" "object"
      "properties"
      {"namespace" {"type" "string"
                    "description" "The namespace to list vars from (optional, defaults to current namespace) (e.g., \"clojure.set\", \"user\")"}}}}
    {"name" "loaded-namespaces"
     "description" "Get list of currently loaded namespaces in the REPL session"
     "inputSchema"
     {"type" "object"
      "properties" {}}}
    {"name" "current-namespace"
     "description" "Get the current default namespace in the REPL session"
     "inputSchema"
     {"type" "object"
      "properties" {}}}
    {"name" "macroexpand-all"
     "description" "Fully expand all macros in Clojure code using clojure.walk/macroexpand-all. Returns the completely expanded form."
     "inputSchema"
     {"type" "object"
      "properties"
      {"code" {"type" "string"
               "description" "The Clojure expression to fully macroexpand (e.g., \"(when x y)\")"}}
      "required" ["code"]}}
    {"name" "macroexpand-1"
     "description" "Expand a Clojure macro one step using macroexpand-1. Shows the result of a single macro expansion, useful for understanding macro transformations incrementally."
     "inputSchema"
     {"type" "object"
      "properties"
      {"code" {"type" "string"
               "description" "The Clojure expression to expand one step (e.g., \"(when x y)\")"}}
      "required" ["code"]}}]})

;; Code generation and resource helpers
(defn build-load-file-code
  "Pure function: Build load-file code string with proper escaping"
  [file-path]
  (str "(load-file " (pr-str file-path) ")"))

(defn build-apropos-code
  "Pure function: Build apropos code string with proper escaping"
  [query]
  (str "(require 'clojure.repl) (clojure.repl/apropos " (pr-str query) ")"))

(defn build-macroexpand-all-code
  "Pure function: Build macroexpand-all code string with proper quoting"
  [code]
  (str "(require 'clojure.walk) (clojure.walk/macroexpand-all (quote " code "))"))

(defn build-macroexpand-1-code
  "Pure function: Build macroexpand-1 code string with proper quoting"
  [code]
  (str "(macroexpand-1 (quote " code "))"))

(defn get-apropos-results [query]
  "Search for symbols matching a pattern"
  (some-> (eval-nrepl-code (build-apropos-code query))
          format-tool-result
          (get-in ["content" 0 "text"])))

(defn validate-timeout-ms
  "Validate timeout-ms parameter. Returns error string if invalid, nil if valid."
  [timeout-ms]
  (cond
    (not (number? timeout-ms))
    "timeout-ms must be a number"

    (< timeout-ms 100)
    "timeout-ms must be at least 100ms"

    (> timeout-ms 300000)
    "timeout-ms cannot exceed 300000ms (5 minutes)"

    :else
    nil))

(defn timeout-error-message
  "Generate timeout error message with helpful suggestion that respects maximum timeout"
  [operation timeout-ms]
  (let [max-timeout 300000
        suggested-timeout (min max-timeout (* 2 timeout-ms))]
    (str operation " timed out after " timeout-ms "ms. "
         (if (>= timeout-ms 150000)
           "Timeout is already at or near maximum (300000ms)."
           (str "Try increasing timeout-ms to " suggested-timeout "ms or higher.")))))

(defn handle-tools-call [params]
  (let [tool-name (get params "name")
        arguments (get params "arguments" {})]
    (case tool-name
      "eval-clojure"
      (with-required-param arguments "code" "evaluating Clojure code"
        (fn [code]
          (let [timeout-ms (get arguments "timeout-ms" 2000)]
            (if-let [error (validate-timeout-ms timeout-ms)]
              (format-tool-error error)
              (try
                (format-tool-result
                  (with-socket-timeout timeout-ms
                    #(eval-clojure-code code)))
                (catch java.net.SocketTimeoutException e
                  (format-tool-error (timeout-error-message "Evaluation" timeout-ms))))))))

      "load-file"
      (with-required-param arguments "file-path" "loading file"
        (fn [file-path]
          (if (fs/exists? file-path)
            (let [timeout-ms (get arguments "timeout-ms" 2000)]
              (if-let [error (validate-timeout-ms timeout-ms)]
                (format-tool-error error)
                (try
                  (format-tool-result
                    (with-socket-timeout timeout-ms
                      #(eval-clojure-code (build-load-file-code file-path)))
                    :default-message (str "Successfully loaded file: " file-path))
                  (catch java.net.SocketTimeoutException e
                    (format-tool-error (timeout-error-message "File loading" timeout-ms))))))
            (format-tool-error (str "Error: File not found: " file-path)))))

      "set-namespace"
      (with-required-param arguments "namespace" "switching namespace"
        (fn [namespace]
          (format-tool-result
            (eval-clojure-code (str "(in-ns '" namespace ")"))
            :default-message (str "Successfully switched to namespace: " namespace))))

      "doc"
      (with-required-param arguments "symbol" "getting documentation"
        (fn [symbol]
          (if-let [doc-content (get-doc symbol)]
            (format-tool-result [] :default-message doc-content)
            (format-tool-result [] :default-message (str "No documentation found for: " symbol)))))

      "source"
      (with-required-param arguments "symbol" "getting source code"
        (fn [symbol]
          (if-let [source-content (get-source symbol)]
            (format-tool-result [] :default-message source-content)
            (format-tool-result [] :default-message (str "No source found for: " symbol)))))

      "apropos"
      (with-required-param arguments "query" "searching for symbols"
        (fn [query]
          (if-let [results (get-apropos-results query)]
            (format-tool-result [] :default-message results)
            (format-tool-result [] :default-message "No matches found"))))

      "vars"
      (let [namespace (get arguments "namespace")]
        (if-let [vars (get-vars namespace)]
          (format-tool-result [] :default-message vars)
          (format-tool-result [] :default-message "[]")))

      "loaded-namespaces"
      (if-let [namespaces (get-loaded-namespaces)]
        (format-tool-result [] :default-message namespaces)
        (format-tool-result [] :default-message "[]"))

      "current-namespace"
      (if-let [ns (get-current-namespace)]
        (format-tool-result [] :default-message ns)
        (format-tool-result [] :default-message "user"))

      "macroexpand-all"
      (with-required-param arguments "code" "macroexpanding code"
        (fn [code]
          (format-tool-result
            (eval-nrepl-code (build-macroexpand-all-code code)))))

      "macroexpand-1"
      (with-required-param arguments "code" "macroexpanding code one step"
        (fn [code]
          (format-tool-result
            (eval-nrepl-code (build-macroexpand-1-code code)))))

      (format-tool-error (str "Unknown tool: " tool-name)))))

(defn handle-resources-list []
  "Returns empty resources list - all functionality migrated to tools"
  {"resources" []})

(defn handle-resources-read [params]
  "Resources have been migrated to tools - returns error"
  (throw (Exception. "Resources are no longer supported. Please use tools instead.")))

(defn handle-prompts-list []
  {"prompts"
   [{"name" "explore-namespace"
     "description" "Guide users through exploring their current REPL session state"
     "arguments" []}

    {"name" "define-and-test"
     "description" "Guide proper function definition and testing workflow"
     "arguments" [{"name" "function-name"
                   "description" "The name of the function to define"
                   "required" true}
                  {"name" "function-code"
                   "description" "The complete function definition code"
                   "required" true}]}

    {"name" "load-and-explore"
     "description" "Guide proper file loading and namespace exploration"
     "arguments" [{"name" "file-path"
                   "description" "Path to the Clojure file to load"
                   "required" true}]}

    {"name" "debug-error"
     "description" "Guide systematic troubleshooting of errors"
     "arguments" [{"name" "error-code"
                   "description" "The code that is producing an error"
                   "required" true}]}

    {"name" "search-and-learn"
     "description" "Guide discovering and learning about Clojure functions"
     "arguments" [{"name" "search-term"
                   "description" "The term to search for in symbol names"
                   "required" true}]}]})

(defn handle-prompts-get [params]
  (let [prompt-name (get params "name")
        arguments (get params "arguments" {})]
    (case prompt-name
      "explore-namespace"
      {"description" "Explore your current REPL session state"
       "messages" [{"role" "user"
                    "content" {"type" "text"
                               "text" "I want to explore my current REPL session. Let's start by:\n\n1. Check what namespace I'm in (use the current-namespace tool)\n2. List all variables defined in this namespace (use the vars tool)\n3. Show me documentation for any interesting functions you find\n\nThis will help me understand what's available in my current context."}}]}

      "define-and-test"
      (let [function-name (get arguments "function-name")
            function-code (get arguments "function-code")]
        (when-not function-name
          (throw (Exception. "Missing required argument: function-name")))
        (when-not function-code
          (throw (Exception. "Missing required argument: function-code")))
        {"description" (str "Define and test the function: " function-name)
         "messages" [{"role" "user"
                      "content" {"type" "text"
                                 "text" (str "I want to define and test a new function: " function-name "\n\nFirst, let's define it using the eval-clojure tool:\n" function-code "\n\nAfter defining it, please:\n1. Call it with a simple test case to verify it works\n2. Check the result is what we expect\n3. If there are any errors, help me understand what went wrong\n\nThis ensures the function is properly defined before I use it in my code.")}}]})

      "load-and-explore"
      (let [file-path (get arguments "file-path")]
        (when-not file-path
          (throw (Exception. "Missing required argument: file-path")))
        {"description" (str "Load and explore the file: " file-path)
         "messages" [{"role" "user"
                      "content" {"type" "text"
                                 "text" (str "I want to load and explore the file: " file-path "\n\nPlease help me:\n1. Load the file using the load-file tool\n2. Determine what namespace it defines (look at the ns declaration)\n3. Switch to that namespace using set-namespace\n4. List the public functions defined in that namespace (use the vars tool)\n5. Show me documentation for the main functions\n\nThis workflow helps me understand what's in the file before using it.")}}]})

      "debug-error"
      (let [error-code (get arguments "error-code")]
        (when-not error-code
          (throw (Exception. "Missing required argument: error-code")))
        {"description" "Debug an error systematically"
         "messages" [{"role" "user"
                      "content" {"type" "text"
                                 "text" (str "I'm getting an error with this code: " error-code "\n\nPlease help me debug this by:\n1. Running the code with eval-clojure to capture the full error message\n2. Analyzing the error type and message\n3. If the error mentions specific functions, search for them using the apropos tool\n4. Show me documentation for relevant functions using the doc tool\n5. Suggest what might be wrong and how to fix it\n\nThis systematic approach will help me understand and resolve the error.")}}]})

      "search-and-learn"
      (let [search-term (get arguments "search-term")]
        (when-not search-term
          (throw (Exception. "Missing required argument: search-term")))
        {"description" (str "Learn about functions related to: " search-term)
         "messages" [{"role" "user"
                      "content" {"type" "text"
                                 "text" (str "I want to learn about functions related to: " search-term "\n\nPlease help me:\n1. Search for symbols matching '" search-term "' using the apropos tool\n2. Show me which namespaces these symbols come from\n3. Pick 2-3 of the most commonly used functions\n4. Get their documentation using the doc tool\n5. If available, show me their source code using the source tool\n\nThis will help me discover and understand relevant functions in the Clojure standard library.")}}]})

      (throw (Exception. (str "Unknown prompt: " prompt-name))))))

(defn handle-request [request]
  (let [method (get request "method")
        params (get request "params")
        id (get request "id")]
    
    (when-not (:initialized @state)
      (when-not (= method "initialize")
        (throw (Exception. "Server not initialized"))))
    
    (let [result
          (case method
            "initialize" (handle-initialize params)
            "tools/list" (handle-tools-list)
            "tools/call" (handle-tools-call params)
            "resources/list" (handle-resources-list)
            "resources/read" (handle-resources-read params)
            "prompts/list" (handle-prompts-list)
            "prompts/get" (handle-prompts-get params)
            (throw (Exception. (str "Unknown method: " method))))]
      
      {"jsonrpc" "2.0"
       "id" id
       "result" result})))

(defn handle-error [id error-msg]
  {"jsonrpc" "2.0"
   "id" id
   "error" {"code" -1
            "message" error-msg}})

(defn process-message [line]
  (let [id (try (-> line json/parse-string (get "id" 0))
               (catch Exception _ 0))]
    (try
      (let [request (json/parse-string line)]
        (if (get request "method")
          (handle-request request)
          (throw (Exception. "Invalid request: missing method"))))
      (catch Exception e
        (handle-error id (.getMessage e))))))

(def cli-options
  [["-p" "--nrepl-port PORT" "Connect to nREPL server on specified port"
    :parse-fn parse-port
    :validate [integer? "Must be a valid port number"]]
   ["-b" "--bridge" "Connect to external nREPL server (bridge mode)"]
   ["-s" "--server" "Start embedded nREPL server (no external server needed)"]
   ["-e" "--eval CODE" "Evaluate Clojure code and print result (connectionless eval mode)"]
   ["-h" "--help" "Show this help message"]])

(defn usage [options-summary]
  (->> ["mcp-nrepl - MCP server bridge to nREPL"
        ""
        "Usage: mcp-nrepl.bb [OPTIONS]"
        "       mcp-nrepl.bb --bridge          # Connect to external nREPL (best practice)"
        "       mcp-nrepl.bb --server          # Start with embedded nREPL server"
        "       mcp-nrepl.bb --eval CODE       # Evaluate code"
        ""
        "Options:"
        options-summary
        ""
        "Modes:"
        "  MCP Server Mode (default): Reads MCP JSON-RPC messages from stdin"
        "  Connectionless Eval Mode (--eval): Evaluates code and prints result"
        ""
        "Connection Options:"
        "  --bridge: Connect to external nREPL server (explicit bridge mode, recommended)"
        "  --server: Start an embedded nREPL server (no external server needed)"
        "  --nrepl-port PORT: Specify port for external nREPL server"
        "  --bridge and --server are mutually exclusive"
        "  If no connection option is specified, reads from .nrepl-port file (implicit bridge mode)."]
       (str/join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (str/join \newline errors)))

(defn validate-args [args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-options)]
    (cond
      (:help options)
      {:exit-message (usage summary) :ok? true}

      errors
      {:exit-message (error-msg errors)}

      (and (:bridge options) (:server options))
      {:exit-message "Error: Cannot specify both --bridge and --server. Choose one mode."}

      :else
      {:options options})))

(defn run-eval-mode [code]
  "Connectionless evaluation mode - evaluate code and print result to stdout"
  (try
    (let [responses (eval-clojure-code code)
          result (format-tool-result responses)
          text (get-in result ["content" 0 "text"])]
      (println text)
      (System/exit 0))
    (catch Exception e
      (binding [*out* *err*]
        (println (str "Error: " (.getMessage e))))
      (System/exit 1))))

(defn main [& args]
  (let [{:keys [options exit-message ok?]} (validate-args args)]
    (if exit-message
      (do
        (println exit-message)
        (System/exit (if ok? 0 1)))
      (try
        ;; Start embedded nREPL server if --server option is provided
        (when (:server options)
          (let [server (nrepl-server/start-server! {:host "localhost" :port 0 :quiet true})
                port (.getLocalPort (:socket server))]
            (swap! state assoc :embedded-server server :nrepl-port port)
            ;; Add shutdown hook to stop server on exit
            (-> (Runtime/getRuntime)
                (.addShutdownHook
                 (Thread. (fn []
                           (when-let [srv (:embedded-server @state)]
                             (nrepl-server/stop-server! srv))))))))

        ;; Set nREPL port from options if provided (overrides embedded server port)
        (when-let [port (:nrepl-port options)]
          (when-not (:server options)  ; Don't override if using embedded server
            (swap! state assoc :nrepl-port port)))

        ;; Check if we're in eval mode or MCP server mode
        (if-let [code (:eval options)]
          ;; Connectionless eval mode - evaluate code and exit
          (run-eval-mode code)

          ;; MCP server mode - read JSON-RPC messages from stdin
          (loop []
            (when-let [line (read-line)]
              (let [response (if (> (count (.getBytes line "UTF-8")) MAX-REQUEST-SIZE)
                               (let [id (try (-> line json/parse-string (get "id" 0))
                                            (catch Exception _ 0))]
                                 (handle-error id (str "Request too large. Maximum size: 64 KB. "
                                                      "For large code, use the load-file tool instead.")))
                               (process-message line))]
                (println (json/generate-string response))
                (flush))
              (recur))))

        (catch Exception e
          (log-error "Fatal error: %s" (.getMessage e))
          (System/exit 1))
        (finally
          (when-let [in (:nrepl-input-stream @state)]
            (try
              (.close in)
              (catch Exception _)))
          (when-let [out (:nrepl-output-stream @state)]
            (try
              (.close out)
              (catch Exception _))))))))

;; Entry point
(when (= *file* (System/getProperty "babashka.file"))
  (apply main *command-line-args*))