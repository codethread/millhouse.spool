(ns millhouse.workflow.internal.execution.abandon
  "Private exact non-success cutover, separate from completion authority."
  (:require [clojure.string :as str]
            [millstrand.api.batch.alpha :as batch]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.weaver.alpha :as weaver]
            [millhouse.workflow.internal.execution.operations :as operations]
            [millhouse.workflow.internal.execution.store :as store]
            [millhouse.workflow.internal.routing :as routing]))

(defn- domain-patch! [{:keys [before update] :as patch}]
  (when-not (and (= #{:before :update} (set (keys patch)))
                 (string? (:id before)) (map? update)
                 (every? #{:attributes :state :title} (keys update))
                 (not (attr-get before :workflow/role))
                 (not= "workflow-execution" (attr-get before :kind))
                 (not-any? #(or (str/starts-with? (str %) ":execution/")
                                (str/starts-with? (str %) "execution/")
                                (str/starts-with? (str %) ":workflow/")
                                (str/starts-with? (str %) "workflow/"))
                           (concat (keys (:attributes before)) (keys (:attributes update)))))
    (operations/refuse! "Abandonment domain patches may update only existing non-Workflow domain rows"))
  patch)

(defn apply-route!
  "Apply the internally derived closure/pour with exact retirement and row fences.

  The caller holds only the Workflow guard. No adapters or execution monitors
  are acquired here; durable freeze excludes dispatch."
  [rt route step outcome receipt domain-patches]
  (let [root (:old-root route)
        run-id (attr-get root :workflow/run-id)
        gates (operations/managed-gates rt root)
        managed? (seq gates)
        payload (routing/routed-batch rt route step outcome)]
    (when (or managed? receipt)
      (when-not (and (= :settled (:status receipt))
                     (= receipt (operations/retirement root))
                     (= (:freeze receipt) (operations/freeze root))
                     (= (:id root) (get-in receipt [:freeze :root-id]))
                     (= (get-in receipt [:freeze :attempts])
                        (into {} (map (fn [gate] [(:id gate) (attr-get gate :execution/current)])) gates)))
        (operations/refuse! "Managed root cutover requires quiesce -> retire -> repeat with the exact receipt")))
    (if-not (or managed? receipt (seq domain-patches))
      (batch/apply! rt payload)
      (let [domain-patches (mapv domain-patch! domain-patches)
            closures (set (vals (:refs payload)))
            payload (update payload :strands
                            (fn [patches]
                              (mapv (fn [patch]
                                      (if (and (contains? (:refs payload) (:ref patch))
                                               (not= (:id step) (get (:refs payload) (:ref patch))))
                                        (update patch :attributes merge
                                                {"workflow/outcome" "abandoned"}
                                                (when (and (nil? step) (= (:id root) (get (:refs payload) (:ref patch))))
                                                  outcome)) patch)) patches)))
            rows (into {} (map (fn [id] [id (weaver/show rt id)])) closures)
            attempts (keep (fn [gate]
                             (when-let [token (attr-get gate :execution/current)]
                               (store/attempt-row rt token))) gates)
            _ (doseq [row attempts]
                (when-not (= :settled (get-in (store/record row) [:result :settlement]))
                  (operations/refuse! "Retired attempt no longer has positive settlement")))
            before (into (assoc rows (:id root) root) (map (juxt :id identity)) (concat gates attempts))
            before (reduce #(assoc %1 (get-in %2 [:before :id]) (:before %2)) before domain-patches)
            payload (reduce (fn [payload {:keys [before update]}]
                              (let [ref (keyword (:id before))]
                                (-> payload (assoc-in [:refs ref] (:id before))
                                    (clojure.core/update :strands conj (assoc update :ref ref))))) payload domain-patches)]
        (store/apply-plan! rt before payload
                           {:abandonment {:runtime rt :run run-id :root (:id root) :closures closures}})))))
