(ns millhouse.workflow.internal.execution.authority
  "Private transaction scopes. No public operation runs caller code under authority."
  (:require [clojure.string :as str]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.hooks.alpha :as hooks]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millhouse.workflow.internal.execution.state :as state]))

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

(defn before-commit
  "Refuse stale preimages and every unscoped managed authority mutation."
  [ctx]
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
        (doseq [key (protected-keys before after)]
          (when-not (= (attr-get after key)
                       (if (contains? attributes (if (keyword? key) (subs (str key) 1) key))
                         (attr-get {:attributes attributes} key)
                         (attr-get before key)))
            (refuse! "Execution authority fields cannot be changed directly" id)))
        (when (and owner (not= (:state before) (:state after)))
          (when-not (or (and (= "closed" (:state after))
                             (= id (nth (:completion *transaction*) 3 nil))
                             (= (attr-get before :execution/current)
                                (nth (:completion *transaction*) 4 nil)))
                        (contains? (get-in *transaction* [:abandonment :closures]) id))
            (refuse! "Managed completion requires execution or exact retirement authority" id)))
        (when (and (= "workflow-execution" (attr-get before :kind)) (nil? patch)
                   (not= before after))
          (refuse! "Execution attempts belong to the common driver" id))))
    (doseq [{:keys [id before]} (:batch/burned ctx)]
      (when (or (attr-get before :execution/owner)
                (= "workflow-execution" (attr-get before :kind)))
        (refuse! "Managed execution evidence cannot be burned" id))))
  nil)

(defn open! [{:keys [runtime]}]
  (hooks/register-hook! runtime :workflow/execution-ownership #{:attributes/normalize}
                        'millhouse.workflow.internal.execution.authority/normalize-ownership
                        {:order -100})
  (hooks/register-hook! runtime :workflow/execution-authority
                        #{:batch/apply-before-commit :strand/update-before-commit}
                        'millhouse.workflow.internal.execution.authority/before-commit
                        {:order -100})
  {:runtime runtime})

(defn close! [_]
  ;; Protection is runtime lifetime, not descriptor lifetime. Removing a selector
  ;; must not grant manual completion of already-published managed work.
  {:closed :execution-authority})
