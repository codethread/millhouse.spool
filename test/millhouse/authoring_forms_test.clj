(ns millhouse.authoring-forms-test
  "Test copied domain authoring forms through contribution collection."
  (:require [clojure.test :refer [deftest is]]
            [millhouse.spools.chime :as chime]
            [millhouse.spools.cron :as cron]
            [millhouse.spools.workflow :as workflow]
            [millstrand.test.alpha :as test-alpha]))

(def ^:private test-ns (the-ns 'millhouse.authoring-forms-test))

(deftest copied-domain-forms-define-callables-and-collect-override-intent
  (let [forms '((workflow/defexecutor sample-executor "Sample."
                  {:override? true} [_] nil)
                (cron/defjob :sample-job {:override? true}
                  {:interval-ms 1000
                   :handler 'millhouse.authoring-forms-test/sample-handler})
                (chime/defrule sample-rule "Sample."
                  {:override? true} [_] nil))]
    ;; Exercise the copied authoring forms through Millstrand's blessed
    ;; collection boundary; module lifecycle behavior belongs to its own suites.
    (let [contribution
          (:contribution
           (test-alpha/collect-module-forms
            :test/millhouse-authoring (ns-name test-ns)
            #(doseq [form forms]
               (eval form))))]
      (is (= #{"sample-executor"}
             (get-in contribution [workflow/executor-kind :overrides])))
      (is (= #{:sample-job} (get-in contribution [cron/job-kind :overrides])))
      (is (= #{:sample-rule} (get-in contribution [chime/rule-kind :overrides]))))
    (is (every? var?
                (map #(ns-resolve test-ns %)
                     '[sample-executor-stalled? sample-rule-rule])))))
