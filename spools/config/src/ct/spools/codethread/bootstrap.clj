(ns ct.spools.codethread.bootstrap
  "Register the shared Codethread agent and landing module stack.

  Consumers call `register!` before their workspace-specific modules, then call
  `register-executor!` after every alias, flag, and workflow module is present.
  Repository-specific modules remain consumer-owned."
  (:require [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.spool.alpha :refer [fail!]]))

(def module-definitions
  "Ordered modules that implement shared agents, reviewers, and landing."
  [[:millhouse/spools-identity
    {:ns 'millhouse.spools.identity
     :required? true}]
   [:millhouse/spools-workflow
    {:ns 'millhouse.spools.workflow
     :required? true}]
   [:millhouse/spools-kanban
    {:ns 'millhouse.spools.kanban
     :after [:millhouse/spools-identity
             :millhouse/spools-workflow]
     :required? true}]
   [:millstrand/spools-harnesses
    {:ns 'ct.spools.harnesses.spool
     :after [:millhouse/spools-identity
             :millhouse/spools-kanban
             :millhouse/spools-workflow]
     :required? true}]
   [:codethread/config-agents
    {:ns 'ct.spools.codethread.agents
     :after [:millstrand/spools-harnesses]
     :required? true}]
   [:codethread/config-reviewers
    {:ns 'ct.spools.codethread.reviewers
     :after [:codethread/config-agents]
     :required? true}]
   [:millhouse/spools-land
    {:ns 'millhouse.spools.land.spool
     :after [:millhouse/spools-kanban
             :codethread/config-reviewers]
     :required? true}]])

(def executor-module-id
  "Stable module id owned by `register-executor!`."
  :millstrand/spools-agent-executor)

(def ^:private executor-after
  [:millhouse/spools-workflow
   :millstrand/spools-harnesses
   :codethread/config-agents
   :codethread/config-reviewers
   :millhouse/spools-land])

(defn- register-modules! [runtime module-definitions]
  (doseq [[module-id options] module-definitions]
    (let [result (runtime/module! runtime module-id options)
          status (get-in result [:modules module-id :status])]
      (when-not (or (:staged? result)
                    (contains? #{:applied :unchanged} status))
        (fail! "Shared Codethread module registration failed"
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
        {:ns 'ct.spools.harnesses.executors.agent.spool
         :after after
         :required? true}]])
     {:registered [executor-module-id]
      :after after})))
