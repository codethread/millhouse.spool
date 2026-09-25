(ns millhouse.millstrand-workflows-test
  "Focused contract tests for the Millstrand-workflows publisher spool."
  (:require [clojure.test :refer [deftest is]]
            [millhouse.workflow :as workflow]
            [millhouse.test-support :as test-support]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.test.alpha :as t]))

(def ^:private workflow-root
  (.getCanonicalPath
   (t/spool-checkout-root "millhouse/workflow.clj")))

(deftest bundled-selector-publishes-only-the-shipped-workflow
  (t/with-weaver-world [ctx {:storage :sqlite-memory
                             :deps-edn
                             (pr-str
                              {:deps {'millhouse/workflow
                                      {:local/root workflow-root}}})}]
    (let [rt (:runtime ctx)]
      (test-support/with-module-activation
        #(do
           (runtime/module! rt :millhouse/workflow
                            {:ns 'millhouse.workflow})
           (runtime/module! rt :millhouse/workflow-all
                            {:ns 'millhouse.workflow.spool
                             :after [:millhouse/workflow]})))
      (current/with-runtime rt
        (is (= #{:publish-spool-kondo}
               (set (keys (workflow/workflows)))))
        (is (= #{:start}
               (:entrypoints
                (workflow/resolve-workflow :publish-spool-kondo))))))))
