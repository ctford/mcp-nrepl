(ns mcp-nrepl
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [bencode.core :as bencode]))

;; MCP Protocol constants
(def MCP-VERSION "2024-11-05")
(def SERVER-INFO {:name "mcp-nrepl" :version "0.1.0"})

;; Resource URI prefixes
(def DOC-URI-PREFIX "clojure://doc/")
(def SOURCE-URI-PREFIX "clojure://source/")

;; Global state
(def state (atom {:nrepl-socket nil
                  :nrepl-input-stream nil
                  :session-id nil
                  :initialized false
                  :nrepl-port nil}))

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
          input-stream (java.io.PushbackInputStream. (.getInputStream socket))]
      (.setSoTimeout socket 5000)
      (swap! state assoc :nrepl-input-stream input-stream)
      socket)
    (catch Exception e
      (log-error "Failed to connect to nREPL on port %d: %s" port (.getMessage e))
      nil)))

(defn send-nrepl-message [socket msg]
  (try
    (let [out (.getOutputStream socket)]
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
        (when-not (map? response)
          (log-error "Invalid nREPL response (not a map): %s" response))
        response))
    (catch Exception e
      (log-error "Failed to read nREPL response: %s" (.getMessage e))
      nil)))

(defn create-session [socket]
  (let [clone-msg {"op" "clone" "id" (str (java.util.UUID/randomUUID))}]
    (when (send-nrepl-message socket clone-msg)
      (some-> (read-nrepl-response)
              (get "new-session")))))

(defn ensure-nrepl-connection []
  (when-not (:nrepl-socket @state)
    (when-let [port (or (:nrepl-port @state) (read-nrepl-port))]
      (when-let [socket (connect-to-nrepl port)]
        (swap! state assoc :nrepl-socket socket)
        (when-let [session-id (create-session socket)]
          (swap! state assoc :session-id session-id))))))

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
  (let [{:keys [nrepl-socket session-id]} @state]
    (when nrepl-socket
      (let [msg {"op" "eval"
                 "code" code
                 "session" session-id
                 "id" (str (java.util.UUID/randomUUID))}]
        (when (send-nrepl-message nrepl-socket msg)
          (collect-nrepl-responses))))))

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

(defn get-doc [symbol]
  "Get documentation for a symbol by evaluating (clojure.repl/doc symbol)"
  (some-> (eval-nrepl-code (str "(clojure.repl/doc " symbol ")"))
          extract-nrepl-output))

(defn get-source [symbol]
  "Get source code for a symbol by evaluating (clojure.repl/source symbol)"
  (some-> (eval-nrepl-code (str "(clojure.repl/source " symbol ")"))
          extract-nrepl-output))

(defn get-session-vars []
  "Get list of public variables in current namespace"
  (some-> (eval-nrepl-code "(keys (ns-publics *ns*))")
          extract-nrepl-value))

(defn get-session-namespaces []
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
                     "resources" {}}
     "serverInfo" SERVER-INFO}))

(defn handle-tools-list []
  {"tools"
   [{"name" "eval-clojure"
     "description" "Evaluate Clojure code using nREPL"
     "inputSchema"
     {"type" "object"
      "properties"
      {"code" {"type" "string"
               "description" "The Clojure code to evaluate"}}
      "required" ["code"]}}
    {"name" "load-file"
     "description" "Load and evaluate a Clojure file using nREPL"
     "inputSchema"
     {"type" "object"
      "properties"
      {"file-path" {"type" "string"
                    "description" "The path to the Clojure file to load"}}
      "required" ["file-path"]}}
    {"name" "set-ns"
     "description" "Switch to a different namespace in the nREPL session"
     "inputSchema"
     {"type" "object"
      "properties"
      {"namespace" {"type" "string"
                    "description" "The namespace to switch to"}}
      "required" ["namespace"]}}
    {"name" "apropos"
     "description" "Search for symbols matching a pattern in their name or documentation"
     "inputSchema"
     {"type" "object"
      "properties"
      {"query" {"type" "string"
                "description" "Search pattern (string or regex) to match against symbol names"}}
      "required" ["query"]}}]})

;; Pure code generation functions for testing
(defn build-load-file-code
  "Pure function: Build load-file code string with proper escaping"
  [file-path]
  (str "(load-file " (pr-str file-path) ")"))

(defn build-apropos-code
  "Pure function: Build apropos code string with proper escaping"
  [query]
  (str "(require 'clojure.repl) (clojure.repl/apropos " (pr-str query) ")"))

(defn handle-tools-call [params]
  (let [tool-name (get params "name")
        arguments (get params "arguments" {})]
    (case tool-name
      "eval-clojure"
      (with-required-param arguments "code" "evaluating Clojure code"
        (fn [code]
          (format-tool-result (eval-clojure-code code))))

      "load-file"
      (with-required-param arguments "file-path" "loading file"
        (fn [file-path]
          (if (fs/exists? file-path)
            (format-tool-result
              (eval-clojure-code (build-load-file-code file-path))
              :default-message (str "Successfully loaded file: " file-path))
            (format-tool-error (str "Error: File not found: " file-path)))))

      "set-ns"
      (with-required-param arguments "namespace" "switching namespace"
        (fn [namespace]
          (format-tool-result
            (eval-clojure-code (str "(in-ns '" namespace ")"))
            :default-message (str "Successfully switched to namespace: " namespace))))

      "apropos"
      (with-required-param arguments "query" "searching symbols"
        (fn [query]
          (format-tool-result
            (eval-clojure-code (build-apropos-code query))
            :default-message "No matches found")))

      (format-tool-error (str "Unknown tool: " tool-name)))))

(defn handle-resources-list []
  {"resources"
   [{"uri" "clojure://session/vars"
     "name" "Session Variables"
     "description" "Currently defined variables in the REPL session"
     "mimeType" "application/json"}
    {"uri" "clojure://session/namespaces"
     "name" "Session Namespaces"
     "description" "Currently loaded namespaces in the REPL session"
     "mimeType" "application/json"}
    {"uri" "clojure://session/current-ns"
     "name" "Current Namespace"
     "description" "The current default namespace in the REPL session"
     "mimeType" "text/plain"}
    {"uri" "clojure://doc/{symbol}"
     "name" "Symbol Documentation"
     "description" "Get documentation for any Clojure symbol (URI template - replace {symbol} with the symbol name)"
     "mimeType" "text/plain"}
    {"uri" "clojure://source/{symbol}"
     "name" "Symbol Source Code"
     "description" "Get source code for any Clojure symbol (URI template - replace {symbol} with the symbol name)"
     "mimeType" "text/clojure"}]})

(defn handle-resources-read [params]
  (let [uri (get params "uri")]
    (cond
      (str/starts-with? uri DOC-URI-PREFIX)
      (let [symbol (subs uri (count DOC-URI-PREFIX))]
        (if-let [doc-content (get-doc symbol)]
          {"contents" [{"uri" uri
                       "mimeType" "text/plain"
                       "text" doc-content}]}
          {"contents" [{"uri" uri
                       "mimeType" "text/plain"
                       "text" (str "No documentation found for: " symbol)}]}))

      (str/starts-with? uri SOURCE-URI-PREFIX)
      (let [symbol (subs uri (count SOURCE-URI-PREFIX))]
        (if-let [source-content (get-source symbol)]
          {"contents" [{"uri" uri
                       "mimeType" "text/clojure"
                       "text" source-content}]}
          {"contents" [{"uri" uri
                       "mimeType" "text/plain"
                       "text" (str "No source found for: " symbol)}]}))
      
      (= uri "clojure://session/vars")
      (if-let [vars (get-session-vars)]
        {"contents" [{"uri" uri
                     "mimeType" "application/json"
                     "text" vars}]}
        {"contents" [{"uri" uri
                     "mimeType" "application/json"
                     "text" "[]"}]})
      
      (= uri "clojure://session/namespaces")
      (if-let [namespaces (get-session-namespaces)]
        {"contents" [{"uri" uri
                     "mimeType" "application/json"
                     "text" namespaces}]}
        {"contents" [{"uri" uri
                     "mimeType" "application/json"
                     "text" "[]"}]})
      
      (= uri "clojure://session/current-ns")
      (if-let [current-ns (get-current-namespace)]
        {"contents" [{"uri" uri
                     "mimeType" "text/plain"
                     "text" current-ns}]}
        {"contents" [{"uri" uri
                     "mimeType" "text/plain"
                     "text" "user"}]})
      
      :else
      (throw (Exception. (str "Unknown resource URI: " uri))))))

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
  (try
    (let [request (json/parse-string line)]
      (if (get request "method")
        (handle-request request)
        (throw (Exception. "Invalid request: missing method"))))
    (catch Exception e
      (handle-error nil (.getMessage e)))))

(def cli-options
  [["-p" "--nrepl-port PORT" "Connect to nREPL server on specified port"
    :parse-fn parse-port
    :validate [integer? "Must be a valid port number"]]
   ["-e" "--eval CODE" "Evaluate Clojure code and print result (direct mode)"]
   ["-h" "--help" "Show this help message"]])

(defn usage [options-summary]
  (->> ["mcp-nrepl - MCP server bridge to nREPL"
        ""
        "Usage: mcp-nrepl.bb [OPTIONS]"
        "       mcp-nrepl.bb --eval CODE"
        ""
        "Options:"
        options-summary
        ""
        "Modes:"
        "  MCP Server Mode (default): Reads MCP JSON-RPC messages from stdin"
        "  Direct Eval Mode (--eval): Evaluates code and prints result"
        ""
        "If no port is specified, reads from .nrepl-port file in current directory."]
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

      :else
      {:options options})))

(defn run-eval-mode [code]
  "Direct evaluation mode - evaluate code and print result to stdout"
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
        ;; Set nREPL port from options if provided
        (when-let [port (:nrepl-port options)]
          (swap! state assoc :nrepl-port port))

        ;; Check if we're in eval mode or MCP server mode
        (if-let [code (:eval options)]
          ;; Direct eval mode - evaluate code and exit
          (run-eval-mode code)

          ;; MCP server mode - read JSON-RPC messages from stdin
          (loop []
            (when-let [line (read-line)]
              (let [response (process-message line)]
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
          (when-let [socket (:nrepl-socket @state)]
            (try
              (.close socket)
              (catch Exception _))))))))

;; Entry point
(when (= *file* (System/getProperty "babashka.file"))
  (apply main *command-line-args*))