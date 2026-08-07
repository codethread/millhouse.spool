(ns millhouse.authoring-forms-test
  "Test copied domain authoring forms through contribution collection."
  (:require [clojure.test :refer [deftest is]]
            [millstrand.core.weaver.module-graph :as module-graph]
            [millstrand.spools.chime :as chime]
            [millstrand.spools.cron :as cron]
            [millstrand.spools.workflow :as workflow]))

(def ^:private test-ns (the-ns 'millhouse.authoring-forms-test))

(def ^:private collection-context
  {:module/key :test/millhouse-authoring
   :source/file (.getCanonicalPath (java.io.File. *file*))
   :source/namespace (ns-name test-ns)})

(defn- collect [f]
  (binding [*ns* test-ns
            *file* (:source/file collection-context)]
    (:contribution
     (module-graph/with-contribution-collection collection-context f))))

(deftest copied-domain-forms-define-callables-and-collect-override-intent
  (let [contribution
        (collect
         #(eval
           '(do
              (workflow/defexecutor sample-executor "Sample."
                {:override? true} [_] nil)
              (cron/defjob :sample-job {:override? true}
                {:interval-ms 1000
                 :handler 'millhouse.authoring-forms-test/sample-handler})
              (chime/defrule sample-rule "Sample."
                {:override? true} [_] nil))))]
    (is (= #{"sample-executor"}
           (get-in contribution [workflow/executor-kind :overrides])))
    (is (= #{:sample-job} (get-in contribution [cron/job-kind :overrides])))
    (is (= #{:sample-rule} (get-in contribution [chime/rule-kind :overrides])))
    (is (every? var?
                (map #(ns-resolve test-ns %)
                     '[sample-executor-stalled? sample-rule-rule])))))
