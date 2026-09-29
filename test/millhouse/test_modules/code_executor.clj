(ns millhouse.test-modules.code-executor
  "Test-only selective activation module."
  (:require [millhouse.executors.code :as code]
            [millstrand.api.lifecycle.alpha :as lifecycle]))

(lifecycle/use-resource! code/code-engine)
