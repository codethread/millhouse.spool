(ns millhouse.workflow.execution
  "Managed Workflow gate execution through one Statecharts-backed lifecycle.

  Select an inert descriptor with open! from a lifecycle resource. Requiring
  this namespace activates nothing. Effects run off the event lane; retries
  require explicit expected-attempt and request-key authority. There is no
  exactly-once side-effect or lost-Code-generation settlement guarantee."
  (:require [millstrand.api.current.alpha :as current]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.weaver.alpha :as weaver]
            [millhouse.workflow.internal.compile :as compile]
            [millhouse.workflow.internal.definitions :as definitions]
            [millhouse.workflow.internal.execution.abandon :as abandon]
            [millhouse.workflow.internal.execution.authority :as authority]
            [millhouse.workflow.internal.execution.driver :as driver]
            [millhouse.workflow.internal.execution.data :as data]
            [millhouse.workflow.internal.execution.operations :as operations]
            [millhouse.workflow.internal.execution.state :as state]
            [millhouse.workflow.internal.execution.store :as store]
            [millhouse.workflow.internal.execution.view :as view]
            [millhouse.workflow.internal.guard :as guard]
            [millhouse.workflow.internal.query :as query]
            [millhouse.workflow.internal.registry :as registry]
            [millhouse.workflow.internal.routing :as routing]))

(defn open!
  "Select a validated descriptor, protect managed gates, then start reconciliation.

  Reject duplicate and legacy waiter ownership before admission. Callback Vars
  resolve through the runtime classloader. An unchanged lifecycle refresh retains
  this resource; live revision changes require an explicit drain."
  [rt descriptor]
  (state/validate-descriptor! rt descriptor)
  (let [descriptors (:descriptors (state/state rt))
        waiter (name (:waiter descriptor))]
    (locking descriptors
      (when (or (get @descriptors waiter) (contains? (registry/executor-entries rt) waiter))
        (operations/refuse! "Waiter already has an executor"))
      (doseq [row (weaver/list rt [:= [:attr "kind"] "workflow-execution"] {})
              :let [attempt (store/record row)]
              :when (and (= (:waiter descriptor) (:executor attempt))
                         (not= :done (:phase (store/model attempt)))
                         (not= (:revision descriptor) (:executor-revision attempt)))]
        (operations/refuse! "Live attempts require their recorded descriptor revision"))
      (doseq [gate (weaver/list rt [:and [:= :state "active"]
                                    [:= [:attr "execution/owner"] waiter]] {})]
        (when-not (= (:revision descriptor) (attr-get gate :execution/revision))
          (operations/refuse! "Managed gates require their published descriptor revision; drain before changing it")))
      (current/with-runtime rt (authority/open! {:runtime rt}))
      (swap! descriptors assoc waiter descriptor)
      (driver/open-scheduler! rt)
      {:waiter waiter :descriptor descriptor})))

(defn close!
  "Stop admission and persist exact stop intent before removing a descriptor.

  Retain managed ownership and attempt evidence. Removal never makes a managed
  gate manually completable or establishes settlement of external work."
  [rt {:keys [waiter descriptor]}]
  (let [descriptors (:descriptors (state/state rt))]
    (locking descriptors
      (when (= descriptor (get @descriptors waiter))
        (swap! (:draining (state/state rt)) conj waiter)
        (driver/retain-ownership! rt descriptor)
        (doseq [row (weaver/list rt [:= [:attr "kind"] "workflow-execution"] {})
                :let [attempt (store/record row)]
                :when (= (:waiter descriptor) (:executor attempt))]
          (state/with-run!
            rt (:run-id attempt)
            #(guard/with-run! rt (:run-id attempt)
               (fn [] (driver/stop-attempt! rt row
                                            (driver/stop-reason :cancelled "Executor removed"))))))
        (swap! descriptors dissoc waiter)
        (swap! (:draining (state/state rt)) disj waiter)
        (when (empty? @descriptors) (driver/close-scheduler! rt))))
    {:closed waiter}))

(defn inspect
  "Return one gate's normalized execution view using {:run-id run :step gate}.

  Includes frozen request/deadline, current attempt, result, attention and actual
  settlement. An unstarted managed gate remains visible after descriptor removal."
  [rt selector]
  (view/gate-view rt (operations/select-gate rt selector)))

(defn reconcile!
  "Observe/deliver the exact current attempt; never authorize another attempt."
  [rt selector]
  (let [gate (operations/select-gate rt selector)]
    (when-let [row (store/attempt-row rt (attr-get gate :execution/current))]
      (current/with-runtime rt (driver/drive-attempt! rt row)))
    (inspect rt selector)))

(defn retry!
  "Authorize one new attempt on a settled failed ready gate.

  Require :run-id, :step, :expected-attempt, :request-id, :reason and
  :by-identity. :dry-run writes nothing. Corrected current input is captured
  once; exact request replay returns its original action even after later edits.
  Conflicting key reuse, unsettled work and validation-policy bypass refuse."
  [rt request]
  (current/with-runtime rt (operations/retry! rt request)))

(defn quiesce-run!
  "Freeze the current root and request stop; return an exact quiescence receipt.

  The freeze covers not-yet-ready managed gates belonging to this nearest root.
  Independent nested roots do not share its authority. This is not settlement."
  [rt run-id reason]
  (current/with-runtime rt (operations/quiesce! rt run-id reason)))

(defn retire!
  "Reconcile the exact frozen attempt set; report positive settlement or unknown."
  [rt quiescence-receipt]
  (current/with-runtime rt (operations/retire! rt quiescence-receipt)))

(defn resume-run!
  "Remove only the exact positively retired freeze; never retry failed gates."
  [rt run-id retirement-receipt]
  (current/with-runtime rt (operations/resume! rt run-id retirement-receipt)))

(defn abandon-run!
  "Atomically abandon a frozen, positively retired root and pour its replacement.

  Independent nested roots require their own cutover: this receipt cannot close
  their active managed gates, even if those roots are separately retired.

  Request keys: :run-id, :root-id, :reason, :by-identity, :retirement,
  :workflow, :params, and optional :domain-patches. Domain patches are exact
  {:before row :update patch} pairs on existing non-Workflow rows only. No
  callbacks, new domain rows, edges or success authority are accepted."
  [rt {:keys [run-id root-id reason by-identity retirement workflow params domain-patches] :as request}]
  (when-not (and (every? #{:run-id :root-id :reason :by-identity :retirement :workflow :params :domain-patches}
                         (keys request))
                 (every? data/nonblank? [run-id root-id reason by-identity])
                 (map? params) (or (nil? domain-patches) (vector? domain-patches)))
    (operations/refuse! "Invalid abandonment request"))
  (current/with-runtime rt
    (guard/with-run!
      rt run-id
      (fn []
        (let [root (query/current-root-with-rt rt run-id)]
          (when-not (and (= root-id (:id root)) (= :settled (:status retirement)))
            (operations/refuse! "Abandonment requires the exact retired current root"))
          (let [plan (definitions/plan rt workflow params {:entrypoint :start})
                payload (compile/compile (:workflow plan) (:params plan)
                                         (merge (select-keys plan [:definition :definition-name])
                                                {:run-id run-id :context (compile/default-context (:params plan))
                                                 :family (query/attr root :workflow/family)
                                                 :form :molecule}))]
            (abandon/apply-route! rt {:old-root root :payload payload} nil
                                  {"identity/by-identity" by-identity "workflow/abandon-reason" reason}
                                  retirement (or domain-patches []))
            (routing/close-run-if-done! rt run-id)
            (query/run-result rt run-id)))))))

(defn completion-authorized?
  "Recognize the kernel's exact success scope; provenance strings grant no authority."
  [rt run-id root-id gate-id attempt-id]
  (authority/completion-authorized? rt run-id root-id gate-id attempt-id))

(defn abandonment-authorized?
  "Recognize exact non-success closure authority, never success authority."
  [rt run-id root-id gate-id]
  (authority/abandonment-authorized? rt run-id root-id gate-id))
