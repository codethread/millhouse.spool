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

;; --- Workflow and shell provider surfaces ----------------------------------
(runtime/module! runtime :millhouse/spools-workflow
                 {:ns 'millhouse.spools.workflow
                  :spools ['millhouse.spools/workflow]
                  :required? true})
(runtime/module! runtime :millhouse/spools-workflow-all
                 {:ns 'millhouse.spools.workflow.spool
                  :spools ['millhouse.spools/workflow]
                  :after [:millhouse/spools-workflow]
                  :required? true})

;; --- identity, agent-run, delegation, and provider surfaces -----------------
(runtime/module! runtime :millhouse/spools-identity
                 {:ns 'millhouse.spools.identity
                  :spools ['millhouse.spools/identity]
                  :required? true})
(runtime/module! runtime :millstrand/spools-agent-run
                 {:ns 'ct.spools.agent-run
                  :spools ['ct.spools/agent-run]
                  :required? true})
(runtime/module! runtime :millstrand/spools-delegation
                 {:ns 'ct.spools.delegation
                  :spools ['ct.spools/delegation 'ct.spools/agent-run]
                  :after [:millstrand/spools-agent-run]
                  :required? true})
(runtime/module! runtime :millstrand/spools-harness-core
                 {:ns 'ct.spools.harness-core
                  :spools ['ct.spools/harness-core 'millhouse.spools/identity]
                  :after [:millhouse/spools-identity]
                  :required? true})
(runtime/module! runtime :millstrand/spools-claude-harness
                 {:ns 'ct.spools.claude-harness
                  :spools ['ct.spools/claude-harness 'ct.spools/harness-core]
                  :after [:millstrand/spools-harness-core]
                  :required? true})
(runtime/module! runtime :millstrand/spools-codex-harness
                 {:ns 'ct.spools.codex-harness
                  :spools ['ct.spools/codex-harness 'ct.spools/harness-core]
                  :after [:millstrand/spools-harness-core]
                  :required? true})
(runtime/module! runtime :millstrand/spools-pi-harness
                 {:ns 'ct.spools.pi-harness
                  :spools ['ct.spools/pi-harness 'ct.spools/harness-core]
                  :after [:millstrand/spools-harness-core]
                  :required? true})
(runtime/module! runtime :millstrand/spools-agent-cli
                 {:ns 'ct.spools.agent-cli
                  :spools ['ct.spools/agent-cli 'ct.spools/harness-core]
                  :after [:millstrand/spools-harness-core
                          :millstrand/spools-claude-harness
                          :millstrand/spools-codex-harness
                          :millstrand/spools-pi-harness]
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
(runtime/module! runtime :codethread/config-agents
  {:ns 'ct.spools.codethread.agents
   :spools ['codethread/config 'ct.spools/agent-run]
   :after [:millstrand/spools-agent-run]
   :required? true})
(runtime/module! runtime :codethread/config-help
  {:ns 'ct.spools.codethread.help
   :spools ['codethread/config 'millstrand.spools/batteries]
   :after [:millstrand/spools-batteries]
   :required? true})
(runtime/module! runtime :codethread/config-devflow
  {:ns 'ct.spools.codethread.devflow
   :spools ['codethread/config]
   :required? true})
(runtime/module! runtime :codethread/config
  {:ns 'ct.spools.codethread.config
   :spools ['codethread/config 'millstrand.spools/batteries
            'ct.spools/agent-run 'ct.spools/delegation]
   :after [:codethread/config-agents
           :codethread/config-help
           :codethread/config-devflow
           :millstrand/spools-batteries
           :millstrand/spools-agent-run
           :millstrand/spools-delegation
           :devflow/kanban-adapter]
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
                          :codethread/config
                          :devflow/kanban-adapter]
                  :required? true})
