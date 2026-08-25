(ns millhouse.test-modules.shell-executor
  "Test-only selective activation module."
  (:require [millhouse.spools.executors.shell :as shell]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.millstrand.alpha :as millstrand]))

(workflow/use-executor! shell/shell-stalled?)
(millstrand/use-query! shell/stalled-shell-gates)
(lifecycle/use-resource! shell/shell-pool shell/shell-handler)
(lifecycle/use-reconcile! shell/shell-attempts)
