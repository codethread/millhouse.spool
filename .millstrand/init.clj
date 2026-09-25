(require '[millhouse.config.bootstrap :as config]
         '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

;; Batteries is an ordinary workspace dependency. The declaration carries a
;; source target and world policy only: the module's contribution is
;; the declaration data the authoring forms in `millstrand.spools.batteries` collect
;; as its source loads — the strand ops and the glossary seed their documented
;; failure modes reference.
(runtime/module! runtime :millstrand/spools-batteries
                 {:ns 'millstrand.spools.batteries})

;; Register shared identity, Workflow, Harnesses, Kanban, Land, aliases, and
;; reviewers before repository-specific policy. Executor activation remains
;; deliberately last.
(config/register! runtime)

;; --- Workflow and shell provider surfaces ----------------------------------
(runtime/module! runtime :millhouse/workflow-all
                 {:ns 'millhouse.workflow.spool
                  :after [:millhouse/workflow]
                  :required? true})

;; --- Local Kanban + Devflow adapter ----------------------------------------
(runtime/module! runtime :devflow
                 {:ns 'millhouse.devflow
                  :after [:millhouse/workflow]
                  :required? true})
(runtime/module! runtime :devflow/kanban-adapter
                 {:ns 'millhouse.devflow-kanban-adapter
                  :after [:devflow
                          :millhouse/kanban
                          :millhouse/workflow]
                  :required? true})

;; --- Millhouse consumer roots, in dependency order ------------------------
(runtime/module! runtime :millhouse/config-help
                 {:ns 'millhouse.config.help
                  :after [:millstrand/spools-batteries]
                  :required? true})
(runtime/module! runtime :millhouse/config-devflow
                 {:ns 'millhouse.config.devflow
                  :required? true})
(runtime/module! runtime :millhouse/config
                 {:ns 'millhouse.config
                  :after [:millhouse/config-help
                          :millhouse/config-devflow
                          :millstrand/spools-batteries
                          :devflow/kanban-adapter]
                  :required? true})

;; --- Repository automatic delivery policy ---------------------------------
(runtime/module! runtime :millhouse/workspace-auto-run-workflows
                 {:file "me/auto_run_workflows.clj"
                  :after [:millhouse/workflow-all]
                  :required? true})
(runtime/module! runtime :millhouse/workspace-auto-run
                 {:file "me/auto_run.clj"
                  :after [:millhouse/workspace-auto-run-workflows
                          :millhouse/harnesses]
                  :required? true})

;; Activate the sole shared :agent executor only after every consumer workflow,
;; alias election, and reviewer declaration is reconciled.
(config/register-executor!
 runtime [:millhouse/workflow-all
          :devflow
          :devflow/kanban-adapter
          :millhouse/config
          :millhouse/workspace-auto-run])
