(ns millhouse.authoring-forms-test
  "Test copied domain authoring forms through contribution collection."
  (:require [clojure.test :refer [deftest is]]
            [millhouse.spools.chime :as chime]
            [millhouse.spools.cron :as cron]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.authoring.alpha :as authoring]
            [millstrand.test.alpha :as test-alpha]))

(def ^:private test-ns (the-ns 'millhouse.authoring-forms-test))

(defn- contains-symbol? [form symbol]
  (some #(= symbol %) (tree-seq coll? #(when (coll? %) %) form)))

(deftest domain-authoring-macroexpansion-keeps-inert-and-bang-semantics-distinct
  (let [inert (macroexpand-1
               '(millhouse.spools.cron/defjob macro-job "Doc."
                  {:interval-ms 1 :handler 'millhouse.authoring-forms-test/sample-handler}))
        bang (macroexpand-1
              '(millhouse.spools.cron/defjob! macro-job! "Doc." {:override? true}
                 {:interval-ms 1 :handler 'millhouse.authoring-forms-test/sample-handler}))]
    (is (not (contains-symbol? inert
                               'millstrand.api.authoring.alpha/select-registry!)))
    (is (contains-symbol? bang
                          'millstrand.api.authoring.alpha/select-registry!))
    (is (thrown? Exception
                 (macroexpand-1
                  '(millhouse.spools.cron/defjob malformed "Doc."))))))

(deftest copied-domain-forms-define-callables-and-collect-override-intent
  (let [forms '((workflow/defworkflow! sample-workflow "Sample workflow."
                  {:entrypoints #{:start}}
                  (workflow/workflow "Sample" (workflow/step :done "Done" :self)))
                (workflow/defexecutor! sample-executor "Sample."
                  {:override? true} [_] nil)
                (cron/defjob! sample-job "Sample job." {:override? true}
                  {:interval-ms 1000
                   :handler 'millhouse.authoring-forms-test/sample-handler})
                (chime/defrule! sample-rule "Sample."
                  {:override? true} [_] nil))]
    ;; Exercise the copied authoring forms through Millstrand's blessed
    ;; collection boundary; module lifecycle behavior belongs to its own suites.
    (let [returns (atom [])
          contribution
          (:contribution
           (test-alpha/collect-module-forms
            :test/millhouse-authoring (ns-name test-ns)
            #(doseq [form forms]
               (swap! returns conj (eval form)))))]
      (is (every? var? @returns)
          "inert and bang definitions return their installed Vars")
      (let [declaration (fn [name]
                          (::authoring/declaration
                           (meta (ns-resolve test-ns name))))]
        (is (= {:channel :registry :key :sample-workflow}
               (select-keys (declaration 'sample-workflow)
                            [:channel :key])))
        (is (= {:channel :registry :key "sample-executor"}
               (select-keys (declaration 'sample-executor-stalled?)
                            [:channel :key])))
        (is (= {:channel :registry :key :sample-job}
               (select-keys (declaration 'sample-job)
                            [:channel :key])))
        (is (= {:channel :registry :key :sample-rule}
               (select-keys (declaration 'sample-rule-rule)
                            [:channel :key]))))
      (is (= #{"sample-executor"}
             (get-in contribution [workflow/executor-kind :overrides])))
      (is (= #{:sample-job} (get-in contribution [cron/job-kind :overrides])))
      (is (= #{:sample-rule} (get-in contribution [chime/rule-kind :overrides]))))
    (is (every? var?
                (map #(ns-resolve test-ns %)
                     '[sample-executor-stalled? sample-rule-rule])))))

(deftest bang-definition-replaces-an-inert-declaration
  (let [contribution
        (:contribution
         (test-alpha/collect-module-forms
          :test/millhouse-authoring-replacement (ns-name test-ns)
          #(do
             (eval '(cron/defjob replacement "Old."
                      {:interval-ms 1
                       :handler 'millhouse.authoring-forms-test/sample-handler}))
             (eval '(cron/defjob! replacement "New."
                      {:interval-ms 2
                       :handler 'millhouse.authoring-forms-test/sample-handler})))))]
    (is (= 2 (get-in contribution [cron/job-kind :entries :replacement :interval-ms])))))

(deftest typed-use-rejects-duplicate-selection-before-collection
  (is (thrown? clojure.lang.ExceptionInfo
               (test-alpha/collect-module-forms
                :test/millhouse-authoring-duplicate (ns-name test-ns)
                #(do
                   (eval '(cron/defjob duplicate "Duplicate."
                            {:interval-ms 1
                             :handler 'millhouse.authoring-forms-test/sample-handler}))
                   (eval '(cron/use-job! duplicate duplicate)))))))
