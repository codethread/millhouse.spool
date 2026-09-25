(ns millhouse.harnesses.process-custody
  "Reconciliation between durable harness runs and Mill process custody."
  (:require [millhouse.harnesses.execution :as execution]
            [millhouse.harnesses.internal.process-custody :as custody]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.weaver.alpha :as weaver]))

(defn desired
  "Read active headless harness runs requiring process inspection."
  [{:keys [runtime]}]
  (->> (weaver/list runtime
                    [:and
                     [:= [:attr "harness/run"] "true"]
                     [:= [:attr "harness/mode"] "headless"]
                     [:= [:attr "harness/published"] "true"]
                     [:or
                      [:= [:attr "harness/status"] "running"]
                      [:and
                       [:in [:attr "harness/status"] ["stopped" "failed"]]
                       [:not [:= [:attr "harness/settled"] "true"]]]]]
                    {})
       (remove #(execution/launch-in-flight? runtime %))
       (mapv #(select-keys % [:id :state :attributes]))))

(defn actual
  "Read owner-scoped process facts for the desired harness runs."
  [{:keys [runtime desired]}]
  (when (seq desired)
    (custody/list-owned runtime)))

(defn apply!
  "Reconcile durable harness runs against Mill process facts."
  [{:keys [runtime desired actual]}]
  (when (seq desired)
    (execution/inspect-owned! runtime))
  {:reconciled :harness-process-custody
   :runs (count desired)
   :facts (count actual)
   :status :applied})

(defn remove!
  "Leave Mill-owned process facts intact when reconciliation is removed."
  [_context]
  {:reconciled :harness-process-custody :status :removed})

(lifecycle/defreconcile harness-process-custody
  "Reconcile active headless harness runs with Mill process custody."
  {:read-desired 'millhouse.harnesses.process-custody/desired
   :read-actual 'millhouse.harnesses.process-custody/actual
   :apply 'millhouse.harnesses.process-custody/apply!
   :on-removed 'millhouse.harnesses.process-custody/remove!
   :trigger-kinds #{}
   :after #{:harness-execution-runtime}})
