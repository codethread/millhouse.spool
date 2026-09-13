(require '[ct.spools.codethread.bootstrap :as codethread]
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

;; Register shared identity, Workflow, Harnesses, aliases, and reviewers before
;; repository-specific policy. Executor activation remains deliberately last.
(codethread/register! runtime)

;; --- Workflow and shell provider surfaces ----------------------------------
(runtime/module! runtime :millhouse/spools-workflow-all
                 {:ns 'millhouse.spools.workflow.spool
                  :after [:millhouse/spools-workflow]
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

;; --- Codethread consumer roots, in dependency order ------------------------
(runtime/module! runtime :codethread/config-help
                 {:ns 'ct.spools.codethread.help
                  :after [:millstrand/spools-batteries]
                  :required? true})
(runtime/module! runtime :codethread/config-devflow
                 {:ns 'ct.spools.codethread.devflow
                  :required? true})
(runtime/module! runtime :codethread/config
                 {:ns 'ct.spools.codethread.config
                  :after [:codethread/config-help
                          :codethread/config-devflow
                          :millstrand/spools-batteries
                          :devflow/kanban-adapter]
                  :required? true})
(runtime/module! runtime :codethread/ralph
                 {:ns 'ct.spools.codethread.ralph
                  :after [:millhouse/spools-workflow]
                  :required? true})

;; Activate the sole shared :agent executor only after every consumer workflow,
;; alias election, and reviewer declaration is reconciled.
(codethread/register-executor!
 runtime [:millhouse/spools-workflow-all
          :devflow
          :devflow/kanban-adapter
          :codethread/config
          :codethread/ralph])
