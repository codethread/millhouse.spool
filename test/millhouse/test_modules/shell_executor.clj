(ns millhouse.test-modules.shell-executor
  "Test-only selective activation module."
  (:require [millhouse.executors.shell :as shell]
            [millstrand.api.lifecycle.alpha :as lifecycle]))

(lifecycle/use-resource! shell/shell-engine)
