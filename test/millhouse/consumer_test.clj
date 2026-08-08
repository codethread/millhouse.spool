(ns millhouse.consumer-test
  "Exercise the six-root family through a disposable consumer workspace."
  (:require [clojure.test :refer [deftest is]]
            [millstrand.test.alpha :as test-alpha]))

(defn- repository-root []
  (-> (test-alpha/spool-checkout-root "millhouse/spools/workflow.clj")
      .getParentFile
      .getParentFile
      .getCanonicalPath))

(def ^:private roots
  {'millhouse.spools/workflow "spools/workflow"
   'millhouse.spools/chime "spools/chime"
   'millhouse.spools/cron "spools/cron"
   'millhouse.spools.executors/code "spools/code-executor"
   'millhouse.spools.executors/shell "spools/shell-executor"
   'millhouse.spools/kanban "spools/kanban"})

(def ^:private init
  "(require '[millstrand.api.current.alpha :as current]
            '[millstrand.api.runtime.alpha :as runtime])
   (def rt (current/runtime))
   (runtime/module! rt :millhouse/workflow
     {:ns 'millhouse.spools.workflow
      :spools ['millhouse.spools/workflow]
      :required? true})
   (runtime/module! rt :millhouse/workflow-cli
     {:ns 'millhouse.spools.workflow.cli
      :spools ['millhouse.spools/workflow]
      :after [:millhouse/workflow]
      :required? true})
   (runtime/module! rt :millhouse/chime
     {:ns 'millhouse.spools.chime
      :spools ['millhouse.spools/chime]
      :required? true})
   (runtime/module! rt :millhouse/cron
     {:ns 'millhouse.spools.cron
      :spools ['millhouse.spools/cron]
      :required? true})
   (runtime/module! rt :millhouse/code-executor
     {:ns 'millhouse.spools.executors.code
      :spools ['millhouse.spools.executors/code
               'millhouse.spools/workflow]
      :after [:millhouse/workflow]
      :required? true})
   (runtime/module! rt :millhouse/shell-executor
     {:ns 'millhouse.spools.executors.shell
      :spools ['millhouse.spools.executors/shell
               'millhouse.spools/workflow]
      :after [:millhouse/workflow]
      :required? true})
   (runtime/module! rt :millhouse/kanban
     {:ns 'millhouse.spools.kanban
      :spools ['millhouse.spools/kanban]
      :required? true})")

(deftest family-syncs-and-activates-all-roots
  (test-alpha/with-weaver-world
    [ctx {:spools-edn {:spools {'millhouse/spools
                                {:local/root (repository-root)
                                 :roots roots}}}
          :init init}]
    (let [status (test-alpha/repl!
                  ctx
                  '(do
                     (require '[millstrand.api.current.alpha :as current]
                              '[millstrand.api.runtime.alpha :as runtime])
                     (runtime/status (current/runtime))))
          outcomes (:module/outcomes status)]
      (is (= #{:applied}
             (set (map :status (vals outcomes)))))
      (is (= #{:millhouse/workflow
               :millhouse/workflow-cli
               :millhouse/chime
               :millhouse/cron
               :millhouse/code-executor
               :millhouse/shell-executor
               :millhouse/kanban}
             (set (keys outcomes)))))))
