(ns millhouse.harnesses.executors.agent.spool
  "Activate the optional Harnesses-backed Workflow Agent descriptor."
  (:require [millhouse.harnesses.executors.agent :as agent]
            [millstrand.api.lifecycle.alpha :as lifecycle]))

(lifecycle/use-resource! agent/agent-engine)
