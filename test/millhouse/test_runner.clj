(ns millhouse.test-runner
  "Run the copied Millhouse spool suites."
  (:require [clojure.test :as test]))

(def ^:private test-namespaces
  '[millhouse.authoring-forms-test
    millhouse.executor-discovery-test
    millstrand.chime-test
    millstrand.spools.cron.runtime-test
    millstrand.e2e.cron.lifecycle-test
    millstrand.spools.executors.code-test
    millstrand.spools.executors.shell-test])

(defn -main
  "Run every copied suite and exit non-zero on a failed assertion or error."
  [& _]
  (run! require test-namespaces)
  (let [{:keys [fail error]} (apply test/run-tests test-namespaces)]
    (when (pos? (+ fail error))
      (System/exit 1))))
