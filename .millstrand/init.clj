(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

;; batteries is approved as a shipped source-root spool by default. The module
;; guard keeps source loading behind that visible approval. The declaration
;; carries a source target and world policy only: the module's contribution is
;; the declaration data the authoring forms in `millstrand.spools.batteries` collect
;; as its source loads — the strand ops and the glossary seed their documented
;; failure modes reference.
(runtime/module! runtime :millstrand/spools-batteries
                 {:ns 'millstrand.spools.batteries
                  :spools ['millstrand.spools/batteries]})

(runtime/module! runtime :codethread/config
                 {:ns 'ct.spools.codethread.config
                  :spools ['codethread/config 'millstrand.spools/batteries]
                  :after [:millstrand/spools-batteries]})

;; --- Workflow and shell provider surfaces ----------------------------------
(runtime/module! runtime :millhouse/spools-workflow
                 {:ns 'millhouse.spools.workflow
                  :spools ['millhouse.spools/workflow]
                  :required? true})
(runtime/module! runtime :millhouse/spools-workflow-cli
                 {:ns 'millhouse.spools.workflow.cli
                  :spools ['millhouse.spools/workflow]
                  :after [:millhouse/spools-workflow]
                  :required? true})
(runtime/module! runtime :millhouse/spools-millstrand-workflows
                 {:ns 'millhouse.spools.millstrand-workflows
                  :spools ['millhouse.spools/millstrand-workflows
                           'millhouse.spools/workflow]
                  :after [:millhouse/spools-workflow
                          :millhouse/spools-workflow-cli]
                  :required? true})
(runtime/module! runtime :millhouse/spools-shell
                 {:ns 'millhouse.spools.executors.shell
                  :spools ['millhouse.spools.executors/shell
                           'millhouse.spools/workflow]
                  :after [:millhouse/spools-workflow]
                  :required? true})

;; --- agent-run, delegation, and provider surfaces --------------------------
(runtime/module! runtime :millstrand/spools-agent-run
                 {:ns 'ct.spools.agent-run
                  :spools ['ct.spools/agent-run]
                  :required? true})
(runtime/module! runtime :millstrand/spools-delegation
                 {:ns 'ct.spools.delegation
                  :spools ['ct.spools/delegation 'ct.spools/agent-run]
                  :after [:millstrand/spools-agent-run]
                  :required? true})

;; --- Local Kanban + Devflow adapter ----------------------------------------
(runtime/module! runtime :devflow
                 {:ns 'ct.spools.devflow
                  :spools ['codethread/devflow 'millhouse.spools/workflow]
                  :after [:millhouse/spools-workflow]
                  :required? true})
(runtime/module! runtime :millhouse/spools-kanban
                 {:ns 'millhouse.spools.kanban
                  :spools ['millhouse.spools/kanban]
                  :required? true})
(runtime/module! runtime :devflow/kanban-adapter
                 {:ns 'ct.spools.devflow-kanban-adapter
                  :spools ['codethread/devflow-kanban-adapter
                           'codethread/devflow
                           'millhouse.spools/kanban
                           'millhouse.spools/workflow]
                  :after [:devflow
                          :millhouse/spools-kanban
                          :millhouse/spools-workflow]
                  :required? true})

;; --- Codethread shared roots, in dependency order --------------------------
(runtime/module! runtime :codethread/agents
                 {:ns 'ct.spools.codethread.agents
                  :spools ['codethread/agents
                           'ct.spools/agent-run
                           'ct.spools/delegation]
                  :after [:millstrand/spools-agent-run
                          :millstrand/spools-delegation]
                  :required? true})
(runtime/module! runtime :codethread/devflow-setup
                 {:ns 'ct.spools.codethread.devflow-setup
                  :spools ['codethread/devflow-setup]
                  :after [:devflow/kanban-adapter]
                  :required? true})
(runtime/module! runtime :codethread/ralph
                 {:ns 'ct.spools.codethread.ralph
                  :spools ['codethread/ralph 'millhouse.spools/workflow]
                  :after [:millhouse/spools-workflow]
                  :required? true})

(runtime/module! runtime :millstrand/spools-subagent
                 {:ns 'ct.spools.executors.subagent
                  :spools ['ct.spools/agent-run
                           'millhouse.spools/workflow]
                  :after [:millstrand/spools-agent-run
                          :millhouse/spools-workflow
                          :codethread/agents
                          :devflow/kanban-adapter]
                  :required? true})
