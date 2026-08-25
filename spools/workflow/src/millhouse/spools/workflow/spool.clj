(ns millhouse.spools.workflow.spool
  "Convenience entry point that activates the complete Workflow spool.

  All provider namespaces expose inert `def*` declarations. Consumers that want
  the full batteries-included surface can activate this namespace; consumers
  that want a smaller surface should require the provider namespaces they need
  and select their declaration Vars in their own module."
  (:require [millhouse.spools.executors.code :as code]
            [millhouse.spools.executors.shell :as shell]
            [millhouse.spools.millstrand-workflows :as workflows]
            [millhouse.spools.millstrand-workflows.bootstrap-kondo :as bootstrap]
            [millhouse.spools.millstrand-workflows.bump-millstrand :as bump-millstrand]
            [millhouse.spools.millstrand-workflows.bump-spool :as bump-spool]
            [millhouse.spools.millstrand-workflows.consumer-tooling :as tooling]
            [millhouse.spools.workflow :as workflow]
            [millhouse.spools.workflow.cli :as cli]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.millstrand.alpha :as millstrand]))

(workflow/use-executor! code/code-stalled? shell/shell-stalled?)

(millstrand/use-query! code/stalled-code-gates shell/stalled-shell-gates)
(millstrand/use-op! cli/workflow)

(lifecycle/use-resource! code/code-engine shell/shell-pool shell/shell-handler)
(lifecycle/use-reconcile! shell/shell-attempts)
(lifecycle/use-seed! cli/workflow-glossary-seed)

(workflow/use-workflow!
 workflows/publish-spool-kondo
 bootstrap/bootstrap-kondo
 bootstrap/bootstrap-kondo-greenfield
 bootstrap/bootstrap-kondo-brownfield
 bump-spool/bump-spool
 bump-millstrand/bump-millstrand
 bump-millstrand/bump-millstrand-local
 bump-millstrand/bump-millstrand-local-validate
 bump-millstrand/bump-millstrand-pinned
 tooling/configure-consumer-tooling
 tooling/configure-consumer-tooling-app
 tooling/configure-consumer-tooling-spool
 tooling/configure-consumer-tooling-clojure-app)
