(ns millhouse.workflow.internal.execution.authority
  "Private transaction scopes. No public operation runs caller code under authority."
  (:require [clojure.string :as str]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.hooks.alpha :as hooks]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millhouse.workflow.internal.execution.state :as state]
            [millhouse.workflow.internal.execution.roots :as roots]))

(def ^:dynamic *transaction* nil)

(defn- refuse! [message id]
  (throw (ex-info message {:reason :workflow/execution-protected :id id})))

(defn completion-authorized? [rt run-id root-id gate-id attempt-id]
  (= [rt run-id root-id gate-id attempt-id] (:completion *transaction*)))

(defn abandonment-authorized? [rt run-id root-id gate-id]
  (let [{:keys [runtime run root closures]} (:abandonment *transaction*)]
    (and (= [rt run-id root-id] [runtime run root]) (contains? closures gate-id))))

(defn normalize-ownership
  "Persist selection at publication, even when the gate is not ready."
  [ctx]
  (let [attrs (:hook/value ctx)
        waiter (attr-get {:attributes attrs} :workflow/gate)]
    {:hook/value
     (if-let [descriptor (get (state/selected (current/runtime)) waiter)]
       (assoc attrs "execution/owner" waiter "execution/revision" (:revision descriptor))
       attrs)}))

(defn- protected-keys [before after]
  (let [keys (into (set (keys (:attributes before))) (keys (:attributes after)))]
    (filter #(or (str/starts-with? (if (keyword? %) (subs (str %) 1) %) "execution/")
                 (and (attr-get before :execution/owner)
                      (contains? #{"workflow/gate" "gate/error" "workflow/executor"
                                   "workflow/executor-run-id"}
                                 (if (keyword? %) (subs (str %) 1) %)))) keys)))

(defn- execution-key? [key]
  (str/starts-with? (if (keyword? key) (subs (str key) 1) key) "execution/"))

(defn- validate-created! [ctx row]
  (let [attrs (:attributes row)
        descriptor (get (state/selected (current/runtime)) (attr-get row :workflow/gate))
        expected (some (fn [[ref attributes]]
                         (when (= (:id row) (get (:batch/refs ctx) ref)) attributes))
                       (:creates *transaction*))
        allowed (when descriptor {"execution/owner" (name (:waiter descriptor))
                                  "execution/revision" (:revision descriptor)})]
    (when (and descriptor (= "closed" (:state row)))
      (refuse! "A new managed gate cannot assert completion" (:id row)))
    (if expected
      (when-not (and (= (count expected) (count attrs))
                     (every? (fn [[key value]] (= value (attr-get row key))) expected))
        (refuse! "Execution creation differs from its exact plan" (:id row)))
      (when (or (= "workflow-execution" (attr-get row :kind))
                (some (fn [key]
                        (and (execution-key? key)
                             (not= (attr-get row key) (attr-get {:attributes allowed} key))))
                      (keys attrs)))
        (refuse! "Execution authority cannot be supplied on new strands" (:id row))))))

(defn- managed-descendants? [rt row active-only? after-images]
  (and (= "root" (attr-get row :workflow/role))
       (some #(and (attr-get % :execution/owner)
                   (or (not active-only?) (= "active" (:state (get-in after-images [(:id %) :after] %)))))
             (:strands (graph/subgraph rt [(:id row)])))))

(defn before-burn
  "Retain managed gates, attempts and their roots on the public graph burn path."
  [ctx]
  (doseq [row (:strand/before ctx)]
    (when (or (attr-get row :execution/owner)
              (= "workflow-execution" (attr-get row :kind))
              (managed-descendants? (current/runtime) row false nil))
      (refuse! "Managed execution evidence cannot be burned" (:id row))))
  nil)

(defn before-commit
  "Refuse stale preimages and every unscoped managed authority mutation."
  [ctx]
  (doseq [row (or (:batch/created ctx)
                  (when (and (nil? (:strand/before ctx)) (:strand/after ctx)) [(:strand/after ctx)]))]
    (validate-created! ctx row))
  (let [updates (or (:batch/updated ctx)
                    (when (:strand/before ctx)
                      [{:id (:strand/id ctx) :before (:strand/before ctx) :after (:strand/after ctx)}]))
        by-id (into {} (map (juxt :id identity)) updates)]
    (doseq [[id expected] (:before *transaction*)]
      (when-not (= expected (:before (get by-id id)))
        (refuse! "Execution transaction before-image changed or was not fenced" id)))
    (doseq [{:keys [id before after]} updates]
      (let [patch (get-in *transaction* [:writes id])
            attributes (:attributes patch)
            owner (or (attr-get before :execution/owner)
                      (get (state/selected (current/runtime)) (attr-get before :workflow/gate)))]
        (when (and (= "workflow-execution" (attr-get after :kind))
                   (not= "workflow-execution" (attr-get before :kind)))
          (refuse! "Existing strands cannot be converted into execution attempts" id))
        (doseq [key (protected-keys before after)]
          (when-not (= (attr-get after key)
                       (if (contains? attributes (if (keyword? key) (subs (str key) 1) key))
                         (attr-get {:attributes attributes} key)
                         (attr-get before key)))
            (refuse! "Execution authority fields cannot be changed directly" id)))
        (when (and (not= (:state before) (:state after))
                   (managed-descendants? (current/runtime) before true by-id)
                   (not (contains? (get-in *transaction* [:abandonment :closures]) id)))
          (refuse! "A root with active managed gates requires exact retirement" id))
        (when (and owner (not= (:state before) (:state after)))
          ;; Resolve topology at final precommit, while the batch writer excludes
          ;; other writers. Our completion/cutover payloads never reparent old
          ;; gates: fresh committed parent edges are also this transaction's
          ;; parent edges. Planned row preimages alone cannot fence edge changes.
          (let [rt (current/runtime)
                root (roots/nearest-root rt before)
                run-id (attr-get root :workflow/run-id)]
            (when-not (and root (= "closed" (:state after))
                           (or (completion-authorized? rt run-id (:id root) id
                                                       (attr-get before :execution/current))
                               (abandonment-authorized? rt run-id (:id root) id)))
              (refuse! "Managed completion requires authority for its exact nearest root" id))))
        (when (and (= "workflow-execution" (attr-get before :kind)) (nil? patch)
                   (not= before after))
          (refuse! "Execution attempts belong to the common driver" id))))
    (doseq [{:keys [id before]} (:batch/burned ctx)]
      (when (or (attr-get before :execution/owner)
                (= "workflow-execution" (attr-get before :kind))
                (managed-descendants? (current/runtime) before false nil))
        (refuse! "Managed execution evidence cannot be burned" id))))
  nil)

(defn open! [{:keys [runtime]}]
  (hooks/register-hook! runtime :workflow/execution-ownership #{:attributes/normalize}
                        'millhouse.workflow.internal.execution.authority/normalize-ownership
                        {:order -100})
  (hooks/register-hook! runtime :workflow/execution-authority
                        #{:batch/apply-before-commit :strand/update-before-commit :strand/add-before-commit}
                        'millhouse.workflow.internal.execution.authority/before-commit
                        {:order -100})
  (hooks/register-hook! runtime :workflow/execution-burn #{:strand/burn-before-commit}
                        'millhouse.workflow.internal.execution.authority/before-burn {:order -100})
  {:runtime runtime})

(defn close! [_]
  ;; Protection is runtime lifetime, not descriptor lifetime. Removing a selector
  ;; must not grant manual completion of already-published managed work.
  {:closed :execution-authority})
