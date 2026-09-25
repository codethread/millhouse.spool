(ns millhouse.config.bootstrap
  "Register the shared Millhouse agent and landing module stack.

  Consumers call `register!` before their workspace-specific modules, then call
  `register-executor!` after every alias, flag, and workflow module is present.
  Repository-specific modules remain consumer-owned."
  (:require [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.spool.alpha :refer [fail!]]))

(def module-definitions
  "Ordered modules that implement shared agents, reviewers, and landing."
  [[:millhouse/identity
    {:ns 'millhouse.identity
     :required? true}]
   [:millhouse/workflow
    {:ns 'millhouse.workflow
     :required? true}]
   [:millhouse/kanban
    {:ns 'millhouse.kanban
     :after [:millhouse/identity
             :millhouse/workflow]
     :required? true}]
   [:millhouse/harnesses
    {:ns 'millhouse.harnesses.spool
     :after [:millhouse/identity
             :millhouse/kanban
             :millhouse/workflow]
     :required? true}]
   [:millhouse/config-agents
    {:ns 'millhouse.config.agents
     :after [:millhouse/harnesses]
     :required? true}]
   [:millhouse/config-reviewers
    {:ns 'millhouse.config.reviewers
     :after [:millhouse/config-agents]
     :required? true}]
   [:millhouse/land
    {:ns 'millhouse.land.spool
     :after [:millhouse/kanban
             :millhouse/config-reviewers]
     :required? true}]])

(def executor-module-id
  "Stable module id owned by `register-executor!`."
  :millhouse/agent-executor)

(def ^:private executor-after
  [:millhouse/workflow
   :millhouse/harnesses
   :millhouse/config-agents
   :millhouse/config-reviewers
   :millhouse/land])

(defn- register-modules! [runtime module-definitions]
  (doseq [[module-id options] module-definitions]
    (let [result (runtime/module! runtime module-id options)
          status (get-in result [:modules module-id :status])]
      (when-not (or (:staged? result)
                    (contains? #{:applied :unchanged} status))
        (fail! "Shared Millhouse module registration failed"
               {:module module-id :status status :result result}))))
  {:registered (mapv first module-definitions)})

(defn register!
  "Register shared agents, reviewers, and landing without the executor.

  Return the ordered module ids after every registration succeeds. Repeated
  calls are safe because Millstrand module registration is idempotent for an
  unchanged descriptor.

  Call `register-executor!` only after consumer aliases, flags, and workflows
  have registered."
  [runtime]
  (register-modules! runtime module-definitions))

(defn register-executor!
  "Register the sole shared Workflow `:agent` executor in `runtime`.

  Call this after all consumer configuration. `additional-after` names any
  consumer modules that must precede the executor's initial ready-gate scan.
  Return the executor module id and its complete ordered dependency list."
  ([runtime]
   (register-executor! runtime []))
  ([runtime additional-after]
   (when-not (and (coll? additional-after)
                  (every? keyword? additional-after))
     (fail! "Executor :after dependencies must be a collection of keywords"
            {:after additional-after}))
   (let [after (vec (distinct (concat executor-after additional-after)))]
     (register-modules!
      runtime
      [[executor-module-id
        {:ns 'millhouse.harnesses.executors.agent.spool
         :after after
         :required? true}]])
     {:registered [executor-module-id]
      :after after})))
