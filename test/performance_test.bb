#!/usr/bin/env bb

(ns performance-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
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

;; Set up nREPL once before all tests
(def nrepl-port
  "Port for nREPL server, set up once before all tests run"
  (setup-nrepl))

;; Performance Tests

(deftest test-eval-performance
  (testing "Connectionless eval completes in reasonable time"
    (let [num-runs 10
          ;; Run evaluations and collect timing data
          run-results (reduce (fn [acc run-num]
                                (let [start (System/currentTimeMillis)
                                      result (shell/sh "bb" "mcp-nrepl.bb"
                                                      "--nrepl-port" nrepl-port
                                                      "--eval" "(+ 1 2 3)")
                                      elapsed (- (System/currentTimeMillis) start)]
                                  (conj acc {:run run-num :elapsed elapsed :result result})))
                              []
                              (range 1 (inc num-runs)))

          ;; Extract outputs and timings
          outputs (mapv #(str/trim (:out (:result %))) run-results)
          timings (mapv :elapsed run-results)

          ;; Calculate aggregate statistics
          total-time (reduce + timings)
          avg-time (double (/ total-time (count timings)))
          min-time (apply min timings)
          max-time (apply max timings)
          variance (/ (reduce + (map #(* (- % avg-time) (- % avg-time)) timings))
                      (count timings))
          stddev (Math/sqrt variance)]

      ;; Print per-run timings
      (color-print :yellow (format "Performance test results (%d runs):" num-runs))
      (doseq [{:keys [run elapsed]} run-results]
        (color-print :green (format "  Run %2d: %3dms" run elapsed)))

      ;; Print aggregate statistics
      (color-print :green (format "  Total:   %dms" total-time))
      (color-print :green (format "  Average: %.1fms" avg-time))
      (color-print :green (format "  Range:   %d-%dms" min-time max-time))
      (color-print :green (format "  StdDev:  %.1fms" stddev))
      (println)

      ;; Assertions
      (is (every? #(= "6" %) outputs)
          "All runs should produce correct result '6'")

      (is (< total-time 1000)
          (str "10 runs took " total-time "ms, expected < 1000ms (avg " (format "%.1f" avg-time) "ms per run)")))))

;; Main test runner
(defn run-all-tests []
  (color-print :yellow "Starting performance tests for mcp-nrepl...")
  (println)
  (color-print :yellow "Testing connectionless eval mode performance...")
  (println)
  (let [results (run-tests 'performance-test)]
    (println)
    (if (and (zero? (:fail results)) (zero? (:error results)))
      (do
        (color-print :green "✅ All performance tests passed!")
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
