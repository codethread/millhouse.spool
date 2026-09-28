(ns millhouse.workflow.internal.execution.store
  "Private conditional storage of immutable attempt inputs and lifecycle snapshots."
  (:require [clojure.spec.alpha :as s]
            [millstrand.api.batch.alpha :as batch]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.weaver.alpha :as weaver]
            [millhouse.workflow.internal.completion :as completion]
            [millhouse.workflow.internal.execution.authority :as authority]
            [millhouse.workflow.internal.execution.chart :as chart]
            [millhouse.workflow.internal.execution.data :as data]
            [millhouse.workflow.internal.execution.state :as state]
            [millhouse.workflow.internal.routing :as routing])
  (:import [java.time Instant]
           [java.util UUID]))

(defn now [rt] (str (runtime/now rt)))
(defn uuid [] (str (UUID/randomUUID)))

(defn nearest-root [rt gate]
  (loop [frontier [(:id gate)] seen #{}]
    (let [ids (->> (graph/incoming-edges rt frontier "parent-of")
                   (map :from_strand_id) (remove seen) distinct vec)
          roots (filterv #(= "root" (attr-get % :workflow/role)) (graph/strands-by-ids rt ids))]
      (cond
        (= 1 (count roots)) (first roots)
        (> (count roots) 1) (throw (ex-info "Ambiguous nearest workflow root" {:gate (:id gate)}))
        (empty? ids) nil
        :else (recur ids (into seen frontier))))))

(defn attempt-row [rt token]
  (when token
    (first (weaver/list rt [:and [:= [:attr "kind"] "workflow-execution"]
                            [:= [:attr "execution/token"] token]] {}))))

(defn record [row] (some-> (attr-get row :execution/data) data/decode))
(defn model [attempt] (chart/view (:snapshot attempt)))

(defn current-attempt [rt gate]
  (some-> (attempt-row rt (attr-get gate :execution/current)) record))

(defn apply-plan!
  "Apply exact internal patches and fence every expected row within the batch."
  [rt before payload scope]
  (let [patches (into {} (keep (fn [patch]
                                 (when-let [id (get (:refs payload) (:ref patch))]
                                   [id patch]))) (:strands payload))
        missing (remove (set (keys patches)) (keys before))
        payload (reduce (fn [p id]
                          (-> p (assoc-in [:refs (keyword id)] id)
                              (update :strands conj {:ref (keyword id) :attributes {}}))) payload missing)]
    (binding [authority/*transaction* (merge {:before before
                                              :writes (merge (zipmap missing (repeat {:attributes {}})) patches)} scope)]
      (batch/apply! rt payload))))

(defn- row-patch [attempt]
  {:attributes {"kind" "workflow-execution" "execution/token" (:attempt-id attempt)
                "execution/run" (:run-id attempt) "execution/gate" (:gate-id attempt)
                "execution/data" (data/encode attempt)}})

(defn- gate-patch [attempt]
  (let [view (model attempt)
        result (:result attempt)]
    {:attributes {"execution/current" (:attempt-id attempt)
                  "execution/owner" (name (:executor attempt))
                  "execution/revision" (:executor-revision attempt)
                  "execution/phase" (name (:phase view))
                  "gate/error" (when (and result (not= :succeeded (:outcome result)))
                                 (or (get-in result [:error :message]) (name (:outcome result))))}}))

(declare result-envelope)

(defn prepare-attempt
  "Capture the complete input before pure request projection, including failures."
  [rt descriptor root gate]
  (let [id (uuid)
        instant (now rt)
        input {:run-id (attr-get root :workflow/run-id) :root-id (:id root) :gate-id (:id gate)
               :attempt-id id :gate gate :context (attr-get root :workflow/context)}
        base {:attempt-id id :run-id (:run-id input) :root-id (:id root) :gate-id (:id gate)
              :executor (:waiter descriptor) :executor-revision (:revision descriptor)
              :input input :snapshot (chart/initial (:environment (state/state rt)) id instant)}]
    (try
      (let [request ((state/callable rt (:request descriptor)) input)]
        (when-not (and (data/edn-data? request) (s/valid? (:request-spec descriptor) request))
          (throw (ex-info "Invalid executor request" {:spec (:request-spec descriptor)})))
        (assoc base :request request
               :deadline (when-let [seconds (get request (keyword (name (:waiter descriptor)) "timeout-secs"))]
                           (str (.plusSeconds (Instant/parse instant) (long seconds))))))
      (catch Throwable error
        (let [failure {:status :terminal :outcome :failed :settlement :settled :value nil
                       :error (data/error "execution/invalid-request" error)
                       :evidence {"never-started" true}}
              decision (chart/decide (:environment (state/state rt)) (:snapshot base)
                                     {:attempt-id id :name :invalid :now instant :observation failure})
              committed (chart/decide (:environment (state/state rt)) (:snapshot decision)
                                      {:attempt-id id :name :commit :now instant})
              attempt (assoc base :request-error (:error failure) :snapshot (:snapshot committed))]
          (assoc attempt :result (result-envelope attempt (:view committed))))))))

(defn claim!
  "Atomically publish the UUID-correlated attempt, current token and retry action."
  [rt root gate attempt action]
  (let [payload {:refs {:gate (:id gate) :root (:id root)}
                 :strands [(assoc (gate-patch attempt) :ref :gate)
                           {:ref :root :attributes {}}
                           (assoc (row-patch attempt) :ref :attempt :title "Workflow execution attempt")]}
        payload (cond-> payload action
                        (update :strands conj {:ref :action :title "Workflow execution retry"
                                               :state "closed"
                                               :attributes {"execution/action-run" (:run-id attempt)
                                                            "execution/action-key" (get-in action [:request :request-id])
                                                            "execution/action" (data/encode action)}}))]
    (let [prior (attempt-row rt (attr-get gate :execution/current))
          before (cond-> {(:id root) root (:id gate) gate}
                   prior (assoc (:id prior) prior))]
      (apply-plan! rt before payload {}))
    attempt))

(defn save!
  "Fence the attempt, current token and root for an internal lifecycle decision."
  [rt row attempt]
  (let [gate (weaver/show rt (:gate-id attempt))
        root (weaver/show rt (:root-id attempt))
        current? (= (:attempt-id attempt) (attr-get gate :execution/current))
        payload {:refs {:attempt (:id row) :root (:id root)}
                 :strands [(assoc (row-patch attempt) :ref :attempt)
                           {:ref :root :attributes {}}]}
        payload (cond-> payload current?
                        (assoc-in [:refs :gate] (:id gate))
                        current? (update :strands conj (assoc (gate-patch attempt) :ref :gate)))]
    (apply-plan! rt (cond-> {(:id row) row (:id root) root} current? (assoc (:id gate) gate)) payload {})
    attempt))

(defn advance
  [rt attempt name event]
  (let [decision (chart/decide (:environment (state/state rt)) (:snapshot attempt)
                               (merge event {:attempt-id (:attempt-id attempt) :name name :now (now rt)}))]
    (cond-> (assoc attempt :snapshot (:snapshot decision))
      (:invalid? decision) (assoc :attention (get-in decision [:view :attention])))))

(defn result-envelope [attempt view]
  (merge (select-keys attempt [:executor :executor-revision :run-id :root-id :gate-id :attempt-id])
         (select-keys (:terminal view) [:outcome :value :error :evidence :settlement])
         (select-keys view [:created-at :committed-at :acknowledgement])))

(defn deliver!
  "Commit attempt result and ordinary Workflow completion in one conditional batch."
  [rt row attempt]
  (let [gate (weaver/show rt (:gate-id attempt))
        root (weaver/show rt (:root-id attempt))
        current? (= (:attempt-id attempt) (attr-get gate :execution/current))
        active? (and current? (= "active" (:state root)) (= "active" (:state gate)))
        frozen (attr-get root :execution/freeze)
        attempt (if (and frozen (= :succeeded (get-in (model attempt) [:terminal :outcome])))
                  (advance rt attempt :stop {:reason {:outcome :cancelled
                                                      :error {:code "execution/frozen"
                                                              :message "Run frozen" :data {}}}})
                  attempt)
        committed (advance rt attempt :commit {})
        view (model committed)
        committed (assoc committed :result (result-envelope committed view))
        success? (= :succeeded (get-in committed [:result :outcome]))
        _ (when (and success? (not active?))
            (throw (ex-info "Inactive attempt cannot publish success" {:attempt-id (:attempt-id attempt)})))
        payload (if success?
                  (:batch (completion/plan rt (:run-id attempt)
                                           {:step (:id gate) :executor (name (:executor attempt))
                                            :attributes (:attributes (gate-patch committed))} nil))
                  (if (and current? (= "active" (:state gate)))
                    {:refs {:gate (:id gate)}
                     :strands [(assoc (gate-patch committed) :ref :gate)]}
                    {:refs {} :strands []}))
        payload (-> payload (assoc-in [:refs :attempt] (:id row))
                    (update :strands conj (assoc (row-patch committed) :ref :attempt)))
            ;; The planner reads join/dependency state. Fence all supplied row
            ;; images, including cascaded joins, before any close can commit.
        rows (reduce (fn [images id]
                       (if (contains? images id) images
                           (assoc images id (weaver/show rt id))))
                     {(:id root) root (:id gate) gate (:id row) row}
                     (vals (:refs payload)))]
    (apply-plan! rt rows payload
                 (when success? {:completion [rt (:run-id attempt) (:id root) (:id gate) (:attempt-id attempt)]}))
    (routing/close-run-if-done! rt (:run-id attempt))
    committed))
