(ns millhouse.test-modules.code-executor
  "Test-only selective activation module."
  (:require [millhouse.spools.executors.code :as code]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.millstrand.alpha :as millstrand]))

(workflow/use-executor! code/code-stalled?)
(millstrand/use-query! code/stalled-code-gates)
(lifecycle/use-resource! code/code-engine)
