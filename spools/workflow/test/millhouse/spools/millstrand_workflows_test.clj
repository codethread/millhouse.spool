(ns millhouse.spools.millstrand-workflows-test
  "Focused contract tests for the Millstrand-workflows publisher spool."
  (:require [clojure.test :refer [deftest is]]
            [millhouse.spools.workflow :as workflow]
            [millhouse.test-support :as test-support]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.test.alpha :as t]))

(def ^:private workflow-root
  (.getCanonicalPath
   (t/spool-checkout-root "millhouse/spools/workflow.clj")))

(deftest bundled-selector-publishes-only-the-shipped-workflow
  (t/with-weaver-world [ctx {:storage :sqlite-memory
                             :deps-edn
                             (pr-str
                              {:deps {'millhouse.spools/workflow
                                      {:local/root workflow-root}}})}]
    (let [rt (:runtime ctx)]
      (test-support/with-module-activation
        #(do
           (runtime/module! rt :millhouse/workflow
                            {:ns 'millhouse.spools.workflow})
           (runtime/module! rt :millhouse/workflow-all
                            {:ns 'millhouse.spools.workflow.spool
                             :after [:millhouse/workflow]})))
      (current/with-runtime rt
        (is (= #{:publish-spool-kondo}
               (set (keys (workflow/workflows)))))
        (is (= #{:start}
               (:entrypoints
                (workflow/resolve-workflow :publish-spool-kondo))))))))
