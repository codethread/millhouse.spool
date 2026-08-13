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

(deftest activated-module-resolves-published-workflows
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
              bootstrap-resolved (workflow/resolve-workflow :bootstrap-kondo)
              bump-resolved (workflow/resolve-workflow :bump-spool)
              tooling-resolved (workflow/resolve-workflow :configure-consumer-tooling)
              tooling-app-resolved
              (workflow/resolve-workflow :configure-consumer-tooling-app)
              tooling-spool-resolved
              (workflow/resolve-workflow :configure-consumer-tooling-spool)
              tooling-clojure-app-resolved
              (workflow/resolve-workflow :configure-consumer-tooling-clojure-app)
              definition (:value resolved)
              bootstrap-definition (:value bootstrap-resolved)
              bump-definition (:value bump-resolved)
              ids (mapv :id (:steps definition))]
          (is (= #{:start} (:entrypoints resolved)))
          (is (= #{:start :call} (:entrypoints bootstrap-resolved)))
          (is (= #{:start :call} (:entrypoints bump-resolved)))
          (is (= #{:start :call} (:entrypoints tooling-resolved)))
          (is (= #{:continue} (:entrypoints tooling-app-resolved)))
          (is (= #{:continue} (:entrypoints tooling-spool-resolved)))
          (is (= #{:continue} (:entrypoints tooling-clojure-app-resolved)))
          (is (= 'millhouse.spools.millstrand-workflows.bootstrap-kondo/bootstrap-kondo
                 (:definition bootstrap-resolved)))
          (is (= [:select-world :capture-spool-status :adoption-mode]
                 (mapv :id (:steps bootstrap-definition))))
          (is (= 'millhouse.spools.millstrand-workflows.bump-spool/bump-spool
                 (:definition bump-resolved)))
          (is (= "bump-spool"
                 (get-in bump-definition [:attributes "workflow/family"])))
          (is (= 'millhouse.spools.millstrand-workflows.consumer-tooling/configure-consumer-tooling
                 (:definition tooling-resolved)))
          (is (= 'millhouse.spools.millstrand-workflows.consumer-tooling/configure-consumer-tooling-app
                 (:definition tooling-app-resolved)))
          (is (= 'millhouse.spools.millstrand-workflows.consumer-tooling/configure-consumer-tooling-spool
                 (:definition tooling-spool-resolved)))
          (is (= 'millhouse.spools.millstrand-workflows.consumer-tooling/configure-consumer-tooling-clojure-app
                 (:definition tooling-clojure-app-resolved)))
          (is (= [:inspect-spool-root
                  :publish-root-classpath
                  :publish-kondo-export
                  :publish-kondo-hooks
                  :review-import-boundary
                  :test-kondo-export
                  :document-kondo-export
                  :verify-clean-status]
                 ids))
          (is (= "millstrand-workflows.publish.kondo-export"
                 (get-in (step definition :publish-kondo-export)
                         [:attributes "workflow/action-ref"])))
          (is (= :review-import-boundary
                 (first (:depends-on (step definition :test-kondo-export)))))
          (let [review (get-in (step definition :review-import-boundary)
                               [:attributes "workflow/instruction"])]
            (is (re-find #"one source" review))
            (is (re-find #"external dependency imports" review))
            (is (re-find #"overlaps" review))
            (is (re-find #"import drift" review))
            (is (re-find #"tracked `\.clj-kondo/\.cache`" review)))
          (is (= :document-kondo-export
                 (first (:depends-on (step definition :verify-clean-status)))))
          (let [clean-status (get-in (step definition :verify-clean-status)
                                     [:attributes "workflow/instruction"])]
            (is (re-find #"git diff --check" (clean-status params)))
            (is (re-find #"git status --short" (clean-status params)))
            (is (re-find #"tracked `\.clj-kondo/\.cache`" (clean-status params))))
          (is (re-find #"no automatic macro discovery"
                       ((get-in (step definition :publish-kondo-export)
                                [:attributes "workflow/instruction"])
                        params))))))))
