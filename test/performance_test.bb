#!/usr/bin/env bb

(ns performance-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [clojure.string :as str]
            [clojure.java.shell :as shell]
            [babashka.fs :as fs]))

(load-file "test/test_utils.bb")
(refer 'test-utils)

;; Set up nREPL once before all tests
(def nrepl-port
  "Port for nREPL server, set up once before all tests run"
  (setup-nrepl))

;; Performance Tests

(deftest test-eval-performance
  (testing "Connectionless eval completes in reasonable time"
    (let [num-runs 5
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
          (str num-runs " runs took " total-time "ms, expected < 1000ms (avg " (format "%.1f" avg-time) "ms per run)")))))

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
