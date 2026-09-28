(ns millhouse.workflow.internal.execution.operations
  "Private inspection, explicit retry and exact root-freeze operations."
  (:require [millstrand.api.graph.alpha :as graph]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.weaver.alpha :as weaver]
            [millhouse.workflow.internal.execution.data :as data]
            [millhouse.workflow.internal.execution.driver :as driver]
            [millhouse.workflow.internal.execution.state :as state]
            [millhouse.workflow.internal.execution.store :as store]
            [millhouse.workflow.internal.guard :as guard]
            [millhouse.workflow.internal.query :as query]))

(defn refuse! [message]
  (throw (ex-info message {:reason :workflow/execution-refused})))

(defn select-gate [rt {:keys [run-id step]}]
  (let [gate (weaver/show rt step)
        root (store/nearest-root rt gate)]
    (when-not (= run-id (attr-get root :workflow/run-id))
      (refuse! "Gate does not belong to the requested nearest workflow root"))
    gate))

(defn retry! [rt request]
  (let [{:keys [run-id step expected-attempt request-id reason by-identity dry-run]} request]
    (when-not (and (every? #{:run-id :step :expected-attempt :request-id :reason :by-identity
                             :expected-revision :dry-run} (keys request))
                   (every? data/nonblank? [run-id step expected-attempt request-id reason by-identity])
                   (or (not (contains? request :dry-run)) (boolean? dry-run)))
      (refuse! "Retry requires run, step, expected-attempt, request-id, reason and by-identity"))
    (state/with-run!
      rt run-id
      (fn []
        (guard/with-run!
          rt run-id
          (fn []
            (let [payload (dissoc request :dry-run)
                  previous (some-> (first (weaver/list rt
                                                       [:and [:= [:attr "execution/action-run"] run-id]
                                                        [:= [:attr "execution/action-key"] request-id]] {}))
                                   (attr-get :execution/action) data/decode)]
              (if previous
                (if (= payload (:request previous))
                  {:status :replayed :action previous}
                  (refuse! "Retry request-id payload conflict"))
                (let [gate (select-gate rt request)
                      root (store/nearest-root rt gate)
                      prior (store/current-attempt rt gate)
                      view (when prior (store/model prior))
                      descriptor (get (state/selected rt) (attr-get gate :execution/owner))]
                  (when (attr-get gate :validation/recipe)
                    (refuse! "Validation-marked gates require their frozen validation policy"))
                  (when-not (and descriptor (= expected-attempt (:attempt-id prior))
                                 (= "active" (:state root)) (= "active" (:state gate))
                                 (nil? (attr-get root :execution/freeze))
                                 (= :done (:phase view)) (= :settled (get-in prior [:result :settlement]))
                                 (not= :succeeded (get-in prior [:result :outcome])))
                    (refuse! "Retry requires the current settled failed attempt on an active unfrozen gate"))
                  (query/resolve-ready-step rt run-id {:step step})
                  (let [attempt (store/prepare-attempt rt descriptor root gate)
                        action {:request payload :attempt-id (:attempt-id attempt)
                                :previous-attempt expected-attempt :frozen-request (:request attempt)}]
                    (if dry-run
                      {:status :eligible :action action}
                      (do (store/claim! rt root gate attempt action)
                          {:status :accepted :action action}))))))))))))

(defn managed-gates [rt root]
  (filterv #(and (attr-get % :execution/owner)
                 (= (:id root) (:id (store/nearest-root rt %))))
           (:strands (graph/subgraph rt [(:id root)]))))

(defn freeze [root] (some-> (attr-get root :execution/freeze) data/decode))
(defn retirement [root] (some-> (attr-get root :execution/retirement) data/decode))

(defn require-freeze! [rt receipt]
  (let [root (weaver/show rt (:root-id receipt))]
    (when-not (and (= "active" (:state root)) (= receipt (freeze root)))
      (refuse! "Quiescence receipt is stale"))
    root))

(defn quiesce! [rt run-id reason]
  (when-not (data/nonblank? reason) (refuse! "Quiescence requires a reason"))
  (state/with-run!
    rt run-id
    (fn []
      (let [receipt
            (guard/with-run!
              rt run-id
              (fn []
                (let [root (or (query/current-root-with-rt rt run-id) (refuse! "No active workflow root"))]
                  (or (freeze root)
                      (let [gates (managed-gates rt root)
                            receipt {:id (store/uuid) :run-id run-id :root-id (:id root) :reason reason
                                     :created-at (store/now rt)
                                     :attempts (into {} (map (fn [gate] [(:id gate) (attr-get gate :execution/current)])) gates)}]
                        (store/apply-plan!
                         rt (into {(:id root) root} (map (juxt :id identity)) gates)
                         {:refs {:root (:id root)}
                          :strands [{:ref :root :attributes {"execution/freeze" (data/encode receipt)}}]} {})
                        receipt)))))]
        (doseq [[_ token] (:attempts receipt) :when token]
          (guard/with-run! rt run-id
            (fn [] (driver/stop-attempt! rt (store/attempt-row rt token)
                                         (driver/stop-reason :cancelled reason)))))
        receipt))))

(defn retire! [rt receipt]
  (state/with-run!
    rt (:run-id receipt)
    (fn []
      (require-freeze! rt receipt)
      (doseq [[_ token] (:attempts receipt) :when token]
        (driver/drive-attempt! rt (store/attempt-row rt token)))
      (guard/with-run!
        rt (:run-id receipt)
        (fn []
          (let [root (require-freeze! rt receipt)
                gates (managed-gates rt root)
                _ (when-not (= (:attempts receipt)
                               (into {} (map (fn [gate] [(:id gate) (attr-get gate :execution/current)])) gates))
                    (refuse! "Frozen root's gate membership or current attempts changed"))
                attempts (mapv (fn [[gate-id token]]
                                 (let [attempt (when token (store/record (store/attempt-row rt token)))
                                       view (when attempt (store/model attempt))]
                                   {:gate-id gate-id :attempt-id token
                                    :settlement (if (or (nil? token)
                                                        (and (= :done (:phase view))
                                                             (= :settled (get-in attempt [:result :settlement]))))
                                                  :settled :unknown)
                                    :may-have-started? (boolean (or (:accepted? view) (:uncertain? view)))}))
                               (:attempts receipt))
                result {:freeze receipt :attempts attempts
                        :status (if (every? #(= :settled (:settlement %)) attempts) :settled :unknown)}]
            (when (and (= :settled (:status result)) (not= result (retirement root)))
              (store/apply-plan!
               rt (into {(:id root) root} (map (juxt :id identity))
                        (concat gates (keep (fn [{:keys [attempt-id]}]
                                              (when attempt-id (store/attempt-row rt attempt-id))) attempts)))
               {:refs {:root (:id root)}
                :strands [{:ref :root :attributes {"execution/retirement" (data/encode result)}}]} {}))
            result))))))

(defn resume! [rt run-id receipt]
  (state/with-run!
    rt run-id
    (fn []
      (guard/with-run!
        rt run-id
        (fn []
          (let [root (require-freeze! rt (:freeze receipt))]
            (when-not (and (= run-id (attr-get root :workflow/run-id))
                           (= :settled (:status receipt)) (= receipt (retirement root)))
              (refuse! "Resume requires exact positive retirement"))
            (store/apply-plan! rt {(:id root) root}
                               {:refs {:root (:id root)}
                                :strands [{:ref :root :attributes {"execution/freeze" nil "execution/retirement" nil}}]} {})
            {:status :resumed :root-id (:id root)}))))))
