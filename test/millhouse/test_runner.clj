(ns millhouse.test-runner
  "Run the copied Millhouse spool suites."
  (:require [clojure.test :as test]))

(def ^:private test-namespaces
  '[millhouse.authoring-forms-test
    millhouse.consumer-test
    millhouse.executor-discovery-test
    millhouse.spools.workflow-test
    millhouse.spools.workflow-cli-test
    millhouse.spools.workflow-run-cli-test
    millhouse.chime-test
    millhouse.spools.cron.runtime-test
    millhouse.e2e.cron.lifecycle-test
    millhouse.spools.executors.code-test
    millhouse.spools.executors.shell-test])

(defn -main
  "Run every copied suite and exit non-zero on a failed assertion or error."
  [& _]
  (run! require test-namespaces)
  (let [{:keys [fail error]} (apply test/run-tests test-namespaces)]
    (when (pos? (+ fail error))
      (System/exit 1))))
