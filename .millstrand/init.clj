(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

;; Batteries is an ordinary workspace dependency. The declaration carries a
;; source target and world policy only: the module's contribution is
;; the declaration data the authoring forms in `millstrand.spools.batteries` collect
;; as its source loads — the strand ops and the glossary seed their documented
;; failure modes reference.
(runtime/module! runtime :millstrand/spools-batteries
                 {:ns 'millstrand.spools.batteries})

;; --- Workflow and shell provider surfaces ----------------------------------
(runtime/module! runtime :millhouse/spools-workflow
                 {:ns 'millhouse.spools.workflow
                  :required? true})
(runtime/module! runtime :millhouse/spools-workflow-all
                 {:ns 'millhouse.spools.workflow.spool
                  :after [:millhouse/spools-workflow]
                  :required? true})

;; --- identity and consolidated Harnesses surface ----------------------------
(runtime/module! runtime :millhouse/spools-identity
                 {:ns 'millhouse.spools.identity
                  :required? true})
(runtime/module! runtime :harnesses
                 {:ns 'ct.spools.harnesses.spool
                  :after [:millhouse/spools-identity]
                  :required? true})

;; --- Local Kanban + Devflow adapter ----------------------------------------
(runtime/module! runtime :devflow
                 {:ns 'ct.spools.devflow
                  :after [:millhouse/spools-workflow]
                  :required? true})
(runtime/module! runtime :millhouse/spools-kanban
                 {:ns 'millhouse.spools.kanban
                  :required? true})
(runtime/module! runtime :devflow/kanban-adapter
                 {:ns 'ct.spools.devflow-kanban-adapter
                  :after [:devflow
                          :millhouse/spools-kanban
                          :millhouse/spools-workflow]
                  :required? true})

;; --- Codethread shared roots, in dependency order --------------------------
(runtime/module! runtime :codethread/config-agents
  {:ns 'ct.spools.codethread.agents
   :after [:harnesses]
   :required? true})
(runtime/module! runtime :codethread/config-help
  {:ns 'ct.spools.codethread.help
   :after [:millstrand/spools-batteries]
   :required? true})
(runtime/module! runtime :codethread/config-devflow
  {:ns 'ct.spools.codethread.devflow
   :required? true})
(runtime/module! runtime :codethread/config
  {:ns 'ct.spools.codethread.config
   :after [:codethread/config-agents
           :codethread/config-help
           :codethread/config-devflow
           :millstrand/spools-batteries
           :harnesses
           :devflow/kanban-adapter]
   :required? true})
(runtime/module! runtime :codethread/ralph
                 {:ns 'ct.spools.codethread.ralph
                  :after [:millhouse/spools-workflow]
                  :required? true})

(runtime/module! runtime :harnesses/agent-executor
                 {:ns 'ct.spools.harnesses.executors.agent.spool
                  :after [:harnesses
                          :millhouse/spools-workflow
                          :codethread/config
                          :devflow/kanban-adapter]
                  :required? true})
