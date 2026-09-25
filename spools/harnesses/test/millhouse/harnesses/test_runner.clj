(ns millhouse.harnesses.test-runner
  "Cold test runner for the consolidated Harnesses spool."
  (:require [clojure.test :as test]
            [millhouse.harnesses.native-session-test]
            [millhouse.harnesses.agent-bin-test]
            [millhouse.harnesses.assignment-test]
            [millhouse.harnesses.publication-test]
            [millhouse.harnesses.assignment-concurrency-test]
            [millhouse.harnesses.execution-assignment-test]
            [millhouse.harnesses.executors.agent-test]
            [millhouse.harnesses.guidance-capability-test]
            [millhouse.harnesses.guidance-capability-deadline-test]
            [millhouse.harnesses.guidance-closure-test]
            [millhouse.harnesses.guidance-fixture-hygiene-test]
            [millhouse.harnesses.guidance-process-cleanup-test]
            [millhouse.harnesses.guidance-process-deadline-test]
            [millhouse.harnesses.guidance-process-gate-test]
            [millhouse.harnesses.guidance-process-identity-test]
            [millhouse.harnesses.guidance-process-scan-test]
            [millhouse.harnesses.guidance-provider-transport-test]
            [millhouse.harnesses.guidance-protocol-repair-test]
            [millhouse.harnesses.guidance-representation-deadline-test]
            [millhouse.harnesses.guidance-representation-test]
            [millhouse.harnesses.guidance-test]
            [millhouse.harnesses.lifecycle-test]
            [millhouse.harnesses.lifecycle-custody-test]
            [millhouse.harnesses.managed-startup-test]
            [millhouse.harnesses.native-registration-test]
            [millhouse.harnesses.native-resume-cli-replay-test]
            [millhouse.harnesses.providers.claude-test]
            [millhouse.harnesses.providers.codex-test]
            [millhouse.harnesses.providers.cursor-test]
            [millhouse.harnesses.providers.internal.outcome-test]
            [millhouse.harnesses.providers.pi-test]
            [millhouse.harnesses.reconciliation-callback-test]
            [millhouse.harnesses.reconciliation-sweep-test]
            [millhouse.harnesses.reconciliation-test]
            [millhouse.harnesses.review-git-test]
            [millhouse.harnesses.reviewers-test]
            [millhouse.harnesses.spool-test]
            [millhouse.harnesses.strict-json-test]))

(def ^:private test-namespaces
  '[millhouse.harnesses.native-session-test
    millhouse.harnesses.agent-bin-test
    millhouse.harnesses.assignment-test
    millhouse.harnesses.publication-test
    millhouse.harnesses.assignment-concurrency-test
    millhouse.harnesses.executors.agent-test
    millhouse.harnesses.guidance-capability-test
    millhouse.harnesses.guidance-capability-deadline-test
    millhouse.harnesses.guidance-closure-test
    millhouse.harnesses.guidance-fixture-hygiene-test
    millhouse.harnesses.guidance-process-cleanup-test
    millhouse.harnesses.guidance-process-deadline-test
    millhouse.harnesses.guidance-process-gate-test
    millhouse.harnesses.guidance-process-identity-test
    millhouse.harnesses.guidance-process-scan-test
    millhouse.harnesses.guidance-provider-transport-test
    millhouse.harnesses.guidance-protocol-repair-test
    millhouse.harnesses.guidance-representation-deadline-test
    millhouse.harnesses.guidance-representation-test
    millhouse.harnesses.guidance-test
    millhouse.harnesses.lifecycle-test
    millhouse.harnesses.lifecycle-custody-test
    millhouse.harnesses.managed-startup-test
    millhouse.harnesses.native-registration-test
    millhouse.harnesses.native-resume-cli-replay-test
    millhouse.harnesses.providers.claude-test
    millhouse.harnesses.providers.codex-test
    millhouse.harnesses.providers.cursor-test
    millhouse.harnesses.providers.internal.outcome-test
    millhouse.harnesses.providers.pi-test
    millhouse.harnesses.reconciliation-callback-test
    millhouse.harnesses.reconciliation-sweep-test
    millhouse.harnesses.reconciliation-test
    millhouse.harnesses.review-git-test
    millhouse.harnesses.reviewers-test
    millhouse.harnesses.spool-test
    millhouse.harnesses.strict-json-test])

(defn -main
  "Run Harnesses tests, adding external process acceptance with `--e2e`."
  [& args]
  (let [namespaces (cond-> test-namespaces
                     (some #{"--e2e"} args)
                     (conj 'millhouse.harnesses.execution-assignment-test))
        {:keys [fail error]} (apply test/run-tests namespaces)]
    (shutdown-agents)
    (when (pos? (+ fail error))
      (System/exit 1))))
