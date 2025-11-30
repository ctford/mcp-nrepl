#!/usr/bin/env bb

(ns test-utils
  (:require [cheshire.core :as json]
            [clojure.string :as str]
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

;; nREPL server management
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
  (if-let [port (System/getenv "NREPL_PORT")]
    (do
      (color-print :green "Using NREPL_PORT from environment: " port)
      port)
    (if (fs/exists? ".nrepl-port")
      (let [port (str/trim (slurp ".nrepl-port"))]
        (color-print :green "Found existing .nrepl-port file with port: " port)
        port)
      (start-nrepl-server))))

;; JSON-RPC message builders
(defn make-init-msg
  "Create an MCP initialize message"
  [id]
  (json/generate-string
   {"jsonrpc" "2.0"
    "id" id
    "method" "initialize"
    "params" {"protocolVersion" "2024-11-05"
              "capabilities" {}}}))

(defn make-tool-call-msg
  "Create a tools/call message"
  [id tool-name arguments]
  (json/generate-string
   {"jsonrpc" "2.0"
    "id" id
    "method" "tools/call"
    "params" {"name" tool-name
              "arguments" arguments}}))

(defn make-resource-read-msg
  "Create a resources/read message"
  [id uri]
  (json/generate-string
   {"jsonrpc" "2.0"
    "id" id
    "method" "resources/read"
    "params" {"uri" uri}}))

;; Response parsing helpers
(defn parse-response
  "Parse a JSON-RPC response from output, returning the nth line (0-indexed)"
  [output n]
  (let [lines (str/split-lines output)]
    (when (> (count lines) n)
      (json/parse-string (nth lines n)))))

(defn parse-first-response [output]
  (parse-response output 0))

(defn parse-second-response [output]
  (parse-response output 1))
