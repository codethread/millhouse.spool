(ns millhouse.workflow.internal.completion
  "Private shared completion planner for ordinary and managed Workflow gates."
  (:require [millstrand.api.spool.alpha :refer [fail!]]
            [millhouse.workflow.internal.query :as query]
            [millhouse.workflow.internal.routing :as routing]))

(defn plan
  "Build the ordinary ready-step close, context projection and cascading joins.

  The caller holds the Workflow run guard. Execution composes only its own
  attempt record with this internal plan; no public success patch hook exists."
  [rt run-id opts context]
  (let [step (or (query/resolve-ready-step rt run-id opts)
                 (fail! "No ready workflow step" {:run-id run-id}))
        role (query/attr step :workflow/role)
        actor (:by-identity opts)
        executor (:executor opts)]
    (when (contains? #{"checkpoint" "defer"} role)
      (fail! (if (= "checkpoint" role)
               "Cannot complete a checkpoint; use choose!"
               "Cannot complete a defer; use defer!")
             (cond-> {:run-id run-id :step (query/strand->view step)}
               (= "defer" role) (assoc :reason :workflow/step-is-defer))))
    (when (and (query/attr step :workflow/gate) (not (or actor executor)))
      (fail! "Gate steps require actor or executor provenance"
             {:run-id run-id :step (query/strand->view step)
              :gate (query/attr step :workflow/gate) :by-identity actor :executor executor}))
    (let [attrs (cond-> (or (routing/close-attributes! opts) {})
                  actor (assoc "identity/by-identity" actor)
                  executor (assoc "workflow/executor" executor)
                  (:executor-run-id opts) (assoc "workflow/executor-run-id" (:executor-run-id opts)))
          root (query/current-root-with-rt rt run-id)
          existing-context (query/attr root :workflow/context)]
      (when (and (contains? opts :context) (not (map? existing-context)))
        (fail! "Workflow root context is malformed"
               {:reason :workflow/context-invalid :run-id run-id :root (:id root)
                :context existing-context :expected "map"}))
      {:root root :gate step
       :batch (routing/close-batch (:id step) (not-empty attrs)
                                   (routing/cascade-join-ids rt (:id root) #{(:id step)})
                                   (:id root)
                                   (when (contains? opts :context)
                                     {"workflow/context" (merge existing-context context)}))})))
