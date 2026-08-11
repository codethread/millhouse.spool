(ns millhouse.spools.millstrand-workflows-test
  "Focused contract tests for the Millstrand-workflows publisher spool."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [millhouse.spools.millstrand-workflows]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.test.alpha :as t]))

(def ^:private params
  {:spool-root "spools/example-macros"
   :namespace "example.macros"
   :spool-key "example/macros"
   :macro-forms [{:macro "example.macros/defwidget"
                  :hook "hooks.example/defwidget"}
                 {:macro "example.macros/defpanel"
                  :hook "hooks.example/defpanel"}]})

(def ^:private project-root
  (.getCanonicalPath (io/file ".")))

(def ^:private spools-edn
  {:spools {'millhouse/spools
            {:local/root project-root
             :roots {'millhouse.spools/workflow "spools/workflow"
                     'millhouse.spools/millstrand-workflows
                     "spools/millstrand-workflows"}}}})

(defn- step [definition id]
  (some #(when (= id (:id %)) %) (:steps definition)))

(deftest publisher-workflow-exposes-ordered-obligations
  (t/with-weaver-world [ctx {:storage :sqlite-memory
                             :spools-edn spools-edn}]
    (let [rt (:runtime ctx)]
      (runtime/module! rt :millhouse/workflow
                       {:ns 'millhouse.spools.workflow})
      (runtime/module! rt :millhouse/millstrand-workflows
                       {:ns 'millhouse.spools.millstrand-workflows
                        :spools ['millhouse.spools/millstrand-workflows
                                 'millhouse.spools/workflow]
                        :after [:millhouse/workflow]})
      (current/with-runtime rt
        (let [resolved (workflow/resolve-workflow :publish-spool-kondo)
              definition (:value resolved)
              ids (mapv :id (:steps definition))]
          (is (= #{:start} (:entrypoints resolved)))
          (is (= [:inspect-spool-root
                  :publish-root-classpath
                  :publish-kondo-export
                  :publish-kondo-hooks
                  :test-kondo-export
                  :document-kondo-export]
                 ids))
          (is (= "millstrand-workflows.publish.kondo-export"
                 (get-in (step definition :publish-kondo-export)
                         [:attributes "workflow/action-ref"])))
          (is (= :publish-kondo-hooks
                 (first (:depends-on (step definition :test-kondo-export)))))
          (is (re-find #"no automatic macro discovery"
                       ((get-in (step definition :publish-kondo-export)
                                [:attributes "workflow/instruction"])
                        params))))))))

(deftest exported-kondo-contract-is-on-root-classpath
  (testing "the root manifest keeps resources visible"
    (is (some #(= "resources" %) (:paths (edn/read-string
                                          (slurp "spools/millstrand-workflows/deps.edn"))))))
  (testing "the export config and hook resource resolve"
    (let [config (io/resource
                  "clj-kondo.exports/millhouse.spools/millstrand-workflows/config.edn")
          hook (io/resource
                "clj-kondo.exports/millhouse.spools/millstrand-workflows/hooks/millhouse/spools/millstrand_workflows.clj_kondo")
          config-data (edn/read-string (slurp config))]
      (is config)
      (is hook)
      (is (= 'clojure.core/def
             (get-in config-data [:lint-as 'millhouse.spools.workflow/defworkflow])))
      (is (= 'hooks.millhouse.spools.millstrand-workflows/defworkflow
             (get-in config-data
                     [:hooks :analyze-call
                      'millhouse.spools.workflow/defworkflow]))))))
