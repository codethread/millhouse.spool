(ns millhouse.spools.millstrand-workflows-test
  "Focused contract tests for the Millstrand-workflows publisher spool."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
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

(deftest activated-module-resolves-both-public-workflows
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
              bump-resolved (workflow/resolve-workflow :bump-spool)
              definition (:value resolved)
              bump-definition (:value bump-resolved)
              ids (mapv :id (:steps definition))]
          (is (= #{:start} (:entrypoints resolved)))
          (is (= #{:start :call} (:entrypoints bump-resolved)))
          (is (= 'millhouse.spools.millstrand-workflows.bump-spool/bump-spool
                 (:definition bump-resolved)))
          (is (= "bump-spool"
                 (get-in bump-definition [:attributes "workflow/family"])))
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
