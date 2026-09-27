(ns millhouse.quality-lock-test
  "Exercise acquisition retries and suite exclusion without the shared lock."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [millhouse.test-support :as support]
            [millstrand.api.format.alpha :as format]
            [millstrand.test.alpha :as test-alpha]))

(def ^:private lock-script
  (-> (test-alpha/spool-checkout-root "millhouse/workflow.clj")
      .getParentFile .getParentFile
      (io/file "scripts/with-test-lock.sh") .getPath))

(defn- run-command [directory argv env]
  (let [builder (doto (ProcessBuilder. ^java.util.List argv)
                  (.directory directory)
                  (.redirectErrorStream true))]
    (.putAll (.environment builder) env)
    (let [process (.start builder)
          output (slurp (.getInputStream process))]
      {:exit (.waitFor process) :output output})))

(defn- with-lock-fixture [f]
  (let [root (support/temp-dir "millhouse-quality-lock")
        bin (io/file root "bin")
        lock (io/file root "suite.lock")
        attempts (io/file root "attempts")
        calls (io/file root "calls")
        real-flock (str/trim (:output (run-command root ["sh" "-c" "command -v flock"] {})))]
    (try
      (.mkdirs bin)
      (spit attempts "0")
      ;; Script-boundary fault injection avoids waiting three minutes per conflict.
      ;; Successful acquisition and competing-lock exclusion use the real flock.
      (let [wrapper (io/file bin "flock")]
        (spit wrapper
              (format/prose
               "
                 #!/bin/sh
                 set -eu
                 n=$(cat \"$TEST_ATTEMPTS\")
                 n=$((n + 1))
                 echo \"$n\" >\"$TEST_ATTEMPTS\"
                 if [ \"$n\" -le \"$TEST_CONFLICTS\" ]; then exit 75; fi
                 if [ \"$TEST_LOCK_ERROR\" -ne 0 ]; then exit \"$TEST_LOCK_ERROR\"; fi
                 exec \"$TEST_REAL_FLOCK\" \"$@\"
               " {}))
        (.setExecutable wrapper true false))
      (f {:root root :lock lock :attempts attempts :calls calls
          :env {"PATH" (str (.getPath bin) java.io.File/pathSeparator (System/getenv "PATH"))
                "TEST_REAL_FLOCK" real-flock
                "TEST_ATTEMPTS" (.getPath attempts)
                "TEST_LOCK" (.getPath lock)
                "TEST_CALLS" (.getPath calls)
                "TEST_CONFLICTS" "0"
                "TEST_LOCK_ERROR" "0"}})
      (finally
        (support/delete-tree! root)))))

(deftest acquisition-retries-retain-exclusion-and-never-rerun-the-suite
  (doseq [suite-exit [0 75]]
    (testing (str "suite exit " suite-exit)
      (with-lock-fixture
        (fn [{:keys [root lock attempts calls env]}]
          (let [command (format/prose
                         "
                           echo invoked >>\"$TEST_CALLS\"
                           \"$TEST_REAL_FLOCK\" -n -E 75 \"$TEST_LOCK\" true
                           test \"$?\" -eq 75 || exit 99
                           exit \"$TEST_SUITE_EXIT\"
                         " {})
                result (run-command root ["sh" lock-script (.getPath lock) "sh" "-c" command]
                                    (assoc env "TEST_CONFLICTS" "2"
                                           "TEST_SUITE_EXIT" (str suite-exit)))]
            (is (= suite-exit (:exit result)) (:output result))
            (is (= "3" (str/trim (slurp attempts))))
            (is (= "invoked\n" (slurp calls)))
            (is (zero? (:exit (run-command root [(get env "TEST_REAL_FLOCK") "-n"
                                                 (.getPath lock) "true"] {})))
                "the lock is released when the suite exits")))))))

(deftest acquisition-exhaustion-and-errors-do-not-start-the-suite
  (doseq [[conflicts lock-error expected-exit expected-attempts]
          [[10 0 75 10] [0 64 64 1]]]
    (with-lock-fixture
      (fn [{:keys [root lock attempts calls env]}]
        (let [result (run-command root ["sh" lock-script (.getPath lock)
                                        "sh" "-c" "echo invoked >\"$TEST_CALLS\""]
                                  (assoc env "TEST_CONFLICTS" (str conflicts)
                                         "TEST_LOCK_ERROR" (str lock-error)))]
          (is (= expected-exit (:exit result)) (:output result))
          (is (= (str expected-attempts) (str/trim (slurp attempts))))
          (is (not (.exists calls))))))))
