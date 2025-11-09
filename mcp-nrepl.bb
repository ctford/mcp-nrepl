#!/usr/bin/env bb

(ns mcp-nrepl
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [bencode.core :as bencode]))

;; MCP Protocol constants
(def MCP-VERSION "2024-11-05")
(def SERVER-INFO {:name "mcp-nrepl" :version "0.1.0"})

;; Global state
(def state (atom {:nrepl-socket nil
                  :session-id nil
                  :initialized false
                  :nrepl-port nil}))

;; Utility functions
(defn log-error [msg & args]
  (binding [*out* *err*]
    (println (str "[ERROR] " (apply format msg args)))))

(defn parse-port [port-str]
  (when port-str
    (or (parse-long (str/trim port-str))
        (do (log-error "Invalid port number: %s" port-str)
            nil))))

(defn read-nrepl-port [& [provided-port]]
  (cond
    provided-port (parse-port provided-port)
    
    (fs/exists? ".nrepl-port")
    (try
      (-> ".nrepl-port"
          slurp
          parse-port)
      (catch Exception e
        (log-error "Failed to read .nrepl-port: %s" (.getMessage e))
        nil))
    
    :else
    (do
      (log-error "No nREPL port specified. Use --nrepl-port <port> or create .nrepl-port file")
      nil)))

(defn connect-to-nrepl [port]
  (try
    (let [socket (java.net.Socket. "localhost" port)]
      (.setSoTimeout socket 5000)
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

(defn read-nrepl-response [socket]
  (try
    (let [in (java.io.PushbackInputStream. (.getInputStream socket))
          response (bencode/read-bencode in)]
      response)
    (catch Exception e
      (log-error "Failed to read nREPL response: %s" (.getMessage e))
      nil)))

(defn create-session [socket]
  (let [clone-msg {"op" "clone" "id" (str (java.util.UUID/randomUUID))}]
    (when (send-nrepl-message socket clone-msg)
      (some-> (read-nrepl-response socket)
              (get "new-session")))))

(defn ensure-nrepl-connection []
  (when-not (:nrepl-socket @state)
    (some-> (or (:nrepl-port @state) (read-nrepl-port))
            (connect-to-nrepl)
            (doto (#(swap! state assoc :nrepl-socket %)))
            (create-session)
            (#(swap! state assoc :session-id %)))))

(defn eval-clojure-code [code]
  (ensure-nrepl-connection)
  (let [{:keys [nrepl-socket session-id]} @state]
    (if nrepl-socket
      (let [socket nrepl-socket
            eval-msg {"op" "eval"
                      "code" code
                      "session" session-id
                      "id" (str (java.util.UUID/randomUUID))}]
        (if (send-nrepl-message socket eval-msg)
          (loop [responses []]
            (if-let [response (read-nrepl-response socket)]
              (let [updated-responses (conj responses response)]
                (if (contains? response "status")
                  updated-responses
                  (recur updated-responses)))
              (throw (Exception. "Failed to read nREPL response"))))
          (throw (Exception. "Failed to send eval message to nREPL"))))
      (throw (Exception. "No nREPL connection available")))))

;; nREPL Resource Operations
(defn get-doc [symbol]
  (ensure-nrepl-connection)
  (let [{:keys [nrepl-socket session-id]} @state]
    (if nrepl-socket
      (let [socket nrepl-socket
            doc-msg {"op" "eval"
                     "code" (str "(clojure.repl/doc " symbol ")")
                     "session" session-id
                     "id" (str (java.util.UUID/randomUUID))}]
        (when (send-nrepl-message socket doc-msg)
          (let [responses (loop [responses []]
                           (if-let [response (read-nrepl-response socket)]
                             (let [updated-responses (conj responses response)]
                               (if (contains? response "status")
                                 updated-responses
                                 (recur updated-responses)))
                             responses))
                decode-if-bytes (fn [v] (if (bytes? v) (String. v) (str v)))]
            (->> responses
                 (keep #(get % "out"))
                 (map decode-if-bytes)
                 (str/join "")
                 str/trim))))
      nil)))

(defn get-source [symbol]
  (ensure-nrepl-connection)
  (let [{:keys [nrepl-socket session-id]} @state]
    (if nrepl-socket
      (let [socket nrepl-socket
            source-msg {"op" "eval"
                        "code" (str "(clojure.repl/source " symbol ")")
                        "session" session-id
                        "id" (str (java.util.UUID/randomUUID))}]
        (when (send-nrepl-message socket source-msg)
          (let [responses (loop [responses []]
                           (if-let [response (read-nrepl-response socket)]
                             (let [updated-responses (conj responses response)]
                               (if (contains? response "status")
                                 updated-responses
                                 (recur updated-responses)))
                             responses))
                decode-if-bytes (fn [v] (if (bytes? v) (String. v) (str v)))]
            (->> responses
                 (keep #(get % "out"))
                 (map decode-if-bytes)
                 (str/join "")
                 str/trim))))
      nil)))

(defn get-session-vars []
  (ensure-nrepl-connection)
  (let [{:keys [nrepl-socket session-id]} @state]
    (if nrepl-socket
      (let [socket nrepl-socket
            vars-msg {"op" "eval"
                      "code" "(keys (ns-publics *ns*))"
                      "session" session-id
                      "id" (str (java.util.UUID/randomUUID))}]
        (when (send-nrepl-message socket vars-msg)
          (let [responses (loop [responses []]
                           (if-let [response (read-nrepl-response socket)]
                             (let [updated-responses (conj responses response)]
                               (if (contains? response "status")
                                 updated-responses
                                 (recur updated-responses)))
                             responses))
                decode-if-bytes (fn [v] (if (bytes? v) (String. v) (str v)))]
            (->> responses
                 (keep #(get % "value"))
                 (map decode-if-bytes)
                 first))))
      nil)))

(defn get-session-namespaces []
  (ensure-nrepl-connection)
  (let [{:keys [nrepl-socket session-id]} @state]
    (if nrepl-socket
      (let [socket nrepl-socket
            ns-msg {"op" "eval"
                    "code" "(map str (all-ns))"
                    "session" session-id
                    "id" (str (java.util.UUID/randomUUID))}]
        (when (send-nrepl-message socket ns-msg)
          (let [responses (loop [responses []]
                           (if-let [response (read-nrepl-response socket)]
                             (let [updated-responses (conj responses response)]
                               (if (contains? response "status")
                                 updated-responses
                                 (recur updated-responses)))
                             responses))
                decode-if-bytes (fn [v] (if (bytes? v) (String. v) (str v)))]
            (->> responses
                 (keep #(get % "value"))
                 (map decode-if-bytes)
                 first))))
      nil)))

(defn get-current-namespace []
  (ensure-nrepl-connection)
  (let [{:keys [nrepl-socket session-id]} @state]
    (if nrepl-socket
      (let [socket nrepl-socket
            ns-msg {"op" "eval"
                    "code" "(str *ns*)"
                    "session" session-id
                    "id" (str (java.util.UUID/randomUUID))}]
        (when (send-nrepl-message socket ns-msg)
          (let [responses (loop [responses []]
                           (if-let [response (read-nrepl-response socket)]
                             (let [updated-responses (conj responses response)]
                               (if (contains? response "status")
                                 updated-responses
                                 (recur updated-responses)))
                             responses))
                decode-if-bytes (fn [v] (if (bytes? v) (String. v) (str v)))]
            (->> responses
                 (keep #(get % "value"))
                 (map decode-if-bytes)
                 first))))
      nil)))

;; MCP Protocol handlers
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

(defn handle-tools-call [params]
  (let [tool-name (get params "name")
        arguments (get params "arguments" {})]
    (case tool-name
      "eval-clojure"
      (let [code (get arguments "code")]
        (if (str/blank? code)
          {"isError" true
           "content" [{"type" "text"
                      "text" "Error: Code parameter is required and cannot be empty"}]}
          (try
            (let [responses (eval-clojure-code code)
                  decode-if-bytes (fn [v] (if (bytes? v) (String. v) (str v)))
                  extract-field (fn [field] 
                                  (->> responses
                                       (keep #(get % field))
                                       (map decode-if-bytes)))
                  values (extract-field "value")
                  output (str/join "\n" (extract-field "out"))
                  errors (str/join "\n" (extract-field "err"))
                  result-text (str/join "\n" 
                                        (concat
                                         (when-not (str/blank? output) [output])
                                         (when-not (str/blank? errors) [errors])
                                         values))]
              {"content" [{"type" "text"
                          "text" (if (str/blank? result-text)
                                   "nil"
                                   result-text)}]})
            (catch Exception e
              {"isError" true
               "content" [{"type" "text"
                          "text" (str "Error evaluating Clojure code: " (.getMessage e))}]}))))
      
      "load-file"
      (let [file-path (get arguments "file-path")]
        (if (str/blank? file-path)
          {"isError" true
           "content" [{"type" "text"
                      "text" "Error: file-path parameter is required and cannot be empty"}]}
          (try
            (if (fs/exists? file-path)
              (let [code (str "(load-file \"" file-path "\")")
                    responses (eval-clojure-code code)
                    decode-if-bytes (fn [v] (if (bytes? v) (String. v) (str v)))
                    extract-field (fn [field] 
                                    (->> responses
                                         (keep #(get % field))
                                         (map decode-if-bytes)))
                    values (extract-field "value")
                    output (str/join "\n" (extract-field "out"))
                    errors (str/join "\n" (extract-field "err"))
                    result-text (str/join "\n" 
                                          (concat
                                           (when-not (str/blank? output) [output])
                                           (when-not (str/blank? errors) [errors])
                                           values))]
                {"content" [{"type" "text"
                            "text" (if (str/blank? result-text)
                                     (str "Successfully loaded file: " file-path)
                                     result-text)}]})
              {"isError" true
               "content" [{"type" "text"
                          "text" (str "Error: File not found: " file-path)}]})
            (catch Exception e
              {"isError" true
               "content" [{"type" "text"
                          "text" (str "Error loading file: " (.getMessage e))}]}))))
      
      "set-ns"
      (let [namespace (get arguments "namespace")]
        (if (str/blank? namespace)
          {"isError" true
           "content" [{"type" "text"
                      "text" "Error: namespace parameter is required and cannot be empty"}]}
          (try
            (let [code (str "(in-ns '" namespace ")")
                  responses (eval-clojure-code code)
                  decode-if-bytes (fn [v] (if (bytes? v) (String. v) (str v)))
                  extract-field (fn [field]
                                  (->> responses
                                       (keep #(get % field))
                                       (map decode-if-bytes)))
                  values (extract-field "value")
                  output (str/join "\n" (extract-field "out"))
                  errors (str/join "\n" (extract-field "err"))
                  result-text (str/join "\n"
                                        (concat
                                         (when-not (str/blank? output) [output])
                                         (when-not (str/blank? errors) [errors])
                                         values))]
              {"content" [{"type" "text"
                          "text" (if (str/blank? result-text)
                                   (str "Successfully switched to namespace: " namespace)
                                   result-text)}]})
            (catch Exception e
              {"isError" true
               "content" [{"type" "text"
                          "text" (str "Error switching namespace: " (.getMessage e))}]}))))

      "apropos"
      (let [query (get arguments "query")]
        (if (str/blank? query)
          {"isError" true
           "content" [{"type" "text"
                      "text" "Error: query parameter is required and cannot be empty"}]}
          (try
            (let [code (str "(require 'clojure.repl) (clojure.repl/apropos \"" query "\")")
                  responses (eval-clojure-code code)
                  decode-if-bytes (fn [v] (if (bytes? v) (String. v) (str v)))
                  extract-field (fn [field]
                                  (->> responses
                                       (keep #(get % field))
                                       (map decode-if-bytes)))
                  values (extract-field "value")
                  output (str/join "\n" (extract-field "out"))
                  errors (str/join "\n" (extract-field "err"))
                  result-text (str/join "\n"
                                        (concat
                                         (when-not (str/blank? output) [output])
                                         (when-not (str/blank? errors) [errors])
                                         values))]
              {"content" [{"type" "text"
                          "text" (if (str/blank? result-text)
                                   "No matches found"
                                   result-text)}]})
            (catch Exception e
              {"isError" true
               "content" [{"type" "text"
                          "text" (str "Error searching symbols: " (.getMessage e))}]}))))

      {"isError" true
       "content" [{"type" "text"
                  "text" (str "Unknown tool: " tool-name)}]})))

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
     "mimeType" "text/plain"}]})

(defn handle-resources-read [params]
  (let [uri (get params "uri")]
    (cond
      (str/starts-with? uri "clojure://doc/")
      (let [symbol (subs uri 14)] ; Remove "clojure://doc/"
        (if-let [doc-content (get-doc symbol)]
          {"contents" [{"uri" uri
                       "mimeType" "text/plain"
                       "text" doc-content}]}
          {"contents" [{"uri" uri
                       "mimeType" "text/plain"
                       "text" (str "No documentation found for: " symbol)}]}))
      
      (str/starts-with? uri "clojure://source/")
      (let [symbol (subs uri 17)] ; Remove "clojure://source/"
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
   ["-h" "--help" "Show this help message"]])

(defn usage [options-summary]
  (->> ["mcp-nrepl - MCP server bridge to nREPL"
        ""
        "Usage: mcp-nrepl.bb [OPTIONS]"
        ""
        "Options:"
        options-summary
        ""
        "If no port is specified, reads from .nrepl-port file in current directory."
        ""
        "The script accepts MCP JSON-RPC messages on stdin and provides:"
        "  - eval-clojure tool for evaluating Clojure code via nREPL"]
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
        
        (loop []
          (when-let [line (read-line)]
            (let [response (process-message line)]
              (println (json/generate-string response))
              (flush))
            (recur)))
        (catch Exception e
          (log-error "Fatal error: %s" (.getMessage e))
          (System/exit 1))
        (finally
          (when-let [socket (:nrepl-socket @state)]
            (try
              (.close socket)
              (catch Exception _))))))))

;; Entry point
(when (= *file* (System/getProperty "babashka.file"))
  (apply main *command-line-args*))