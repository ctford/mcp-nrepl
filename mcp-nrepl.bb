#!/usr/bin/env bb

(ns mcp-nrepl
  (:require [clojure.java.io :as io]
            [cheshire.core :as json]
            [clojure.string :as str]
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
  (try
    (Integer/parseInt (str/trim port-str))
    (catch Exception e
      (log-error "Invalid port number: %s" port-str)
      nil)))

(defn read-nrepl-port [& [provided-port]]
  (cond
    provided-port (parse-port provided-port)
    
    (.exists (io/file ".nrepl-port"))
    (try
      (-> ".nrepl-port"
          slurp
          str/trim
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

(defn ensure-nrepl-connection []
  (when-not (:nrepl-socket @state)
    (when-let [port (or (:nrepl-port @state) (read-nrepl-port))]
      (when-let [socket (connect-to-nrepl port)]
        (swap! state assoc :nrepl-socket socket)
        
        ;; Create a new session
        (let [clone-msg {"op" "clone" "id" (str (java.util.UUID/randomUUID))}]
          (when (send-nrepl-message socket clone-msg)
            (when-let [response (read-nrepl-response socket)]
              (when-let [session-id (get response "new-session")]
                (swap! state assoc :session-id session-id)
                true))))))))

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
      "required" ["code"]}}]})

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
      
      {"isError" true
       "content" [{"type" "text"
                  "text" (str "Unknown tool: " tool-name)}]})))

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

(defn show-help []
  (println "mcp-nrepl - MCP server bridge to nREPL")
  (println "")
  (println "Usage: mcp-nrepl.bb [OPTIONS]")
  (println "")
  (println "Options:")
  (println "  --nrepl-port <port>   Connect to nREPL server on specified port")
  (println "  --help                Show this help message")
  (println "")
  (println "If no port is specified, reads from .nrepl-port file in current directory.")
  (println "")
  (println "The script accepts MCP JSON-RPC messages on stdin and provides:")
  (println "  - eval-clojure tool for evaluating Clojure code via nREPL"))

(defn parse-args [args]
  (loop [args args
         port nil]
    (cond
      (empty? args) port
      
      (or (= (first args) "--help") (= (first args) "-h"))
      (do
        (show-help)
        (System/exit 0))
      
      (= (first args) "--nrepl-port")
      (if (second args)
        (recur (drop 2 args) (second args))
        (do
          (log-error "Missing port number after --nrepl-port")
          (System/exit 1)))
      
      :else
      (do
        (log-error "Unknown argument: %s" (first args))
        (show-help)
        (System/exit 1)))))

(defn main [& args]
  (try
    ;; Parse command line arguments
    (when-let [port (parse-args args)]
      (when-let [parsed-port (parse-port port)]
        (swap! state assoc :nrepl-port parsed-port)))
    
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
          (catch Exception _))))))

;; Entry point
(when (= *file* (System/getProperty "babashka.file"))
  (apply main *command-line-args*))