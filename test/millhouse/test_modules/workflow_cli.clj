(ns millhouse.test-modules.workflow-cli
  "Test-only selective activation module."
  (:require [millhouse.spools.workflow.cli :as cli]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.millstrand.alpha :as millstrand]))

(millstrand/use-op! cli/workflow)
(lifecycle/use-seed! cli/workflow-glossary-seed)
