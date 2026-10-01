(ns millhouse.workflow.internal.execution.driver
  "Private off-event-lane driver for selected execution descriptors."
  (:require [clojure.spec.alpha :as s]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.events.alpha :as events]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.weaver.alpha :as weaver]
            [millhouse.workflow.validation :as validation]
            [millhouse.workflow.internal.execution.data :as data]
            [millhouse.workflow.internal.execution.state :as state]
            [millhouse.workflow.internal.execution.store :as store]
            [millhouse.workflow.internal.execution.roots :as roots]
            [millhouse.workflow.internal.guard :as guard]
            [millhouse.workflow.internal.query :as query]
            [millhouse.workflow.internal.routing :as routing])
  (:import [java.time Instant]
           [java.util.concurrent Executors ScheduledExecutorService ThreadFactory TimeUnit]))

(defn context [attempt]
  (merge (select-keys attempt [:run-id :root-id :gate-id :attempt-id :executor
                               :executor-revision :request :deadline])
         (select-keys (store/model attempt) [:reference :stop-reason])
         {:request-id (str "execution/" (:attempt-id attempt) "/start")}))

(defn stop-reason [outcome message]
  {:outcome outcome :error {:code (str "execution/" (name outcome)) :message message :data {}}})

(defn- call-adapter [rt descriptor operation attempt]
  (try
    (let [result ((state/callable rt (get descriptor operation)) rt (context attempt))]
      (when-not (and (data/observation? result)
                     (or (= :start operation) (not= :busy (:status result))))
        (throw (ex-info "Invalid executor observation" {:operation operation})))
      (if (and (= :terminal (:status result)) (= :succeeded (:outcome result))
               (not (s/valid? (:result-spec descriptor) (:value result))))
        (assoc result :outcome :failed :value nil
               :error {:code "execution/invalid-result" :message "Executor result failed its contract" :data {}})
        result))
    (catch Throwable error
      {:status :unknown :reference (:reference (store/model attempt))
       :reason (data/error "execution/adapter" error)})))

(defn- update-observation! [rt row attempt observation]
  (when-not (= observation (:observation (store/model attempt)))
    (store/save-observation! rt row (store/advance rt attempt
                                                   (if (= :busy (:status observation)) :busy :observed)
                                                   {:observation observation}))))

(defn stop-attempt! [rt row reason]
  (let [attempt (store/record row)]
    (when-not (= :done (:phase (store/model attempt)))
      (store/save! rt row (store/advance rt attempt :stop {:reason reason})))))

(defn- next-effect! [rt row]
  (let [row (weaver/show rt (:id row))
        attempt (store/record row)
        descriptor (get (state/selected rt) (name (:executor attempt)))
        view (store/model attempt)
        root (weaver/show rt (:root-id attempt))
        gate (weaver/show rt (:gate-id attempt))
        current? (= (:attempt-id attempt) (attr-get gate :execution/current))
        active? (and current? (= "active" (:state root)) (= "active" (:state gate))
                     (= (:id root) (:id (roots/nearest-root rt gate))))
        deadline (:deadline attempt)
        expired? (and deadline (not (.isBefore (Instant/parse (store/now rt)) (Instant/parse deadline))))
        effect (fn [operation candidate image]
                 {:operation operation :attempt candidate :row image :descriptor descriptor})]
    (when (and descriptor (= (:revision descriptor) (:executor-revision attempt))
               (not (:attention attempt)))
      (cond
        (and (not= :done (:phase view)) (nil? (:stop-reason view))
             (or expired? (attr-get root :execution/freeze) (not active?)
                 (contains? @(:draining (state/state rt)) (name (:executor attempt)))))
        (do (stop-attempt! rt row (if expired?
                                    (stop-reason :timed-out "Execution deadline expired")
                                    (stop-reason :cancelled "Execution root is frozen, inactive or its executor is draining"))) nil)

        (= :committing (:phase view))
        (do (store/deliver! rt row attempt) nil)

        (= :done (:phase view))
        (if (:finalization-pending? attempt)
          (do (when (= (:root-id attempt) (:id (query/current-root-with-rt rt (:run-id attempt))))
                (routing/close-run-if-done! rt (:run-id attempt)))
              (store/save-cleanup! rt row (dissoc attempt :finalization-pending?))
              nil)
          (when (= :acknowledge (:desired view)) (effect :acknowledge attempt row)))

        (= :stopping (:phase view)) (effect :stop attempt row)

        (and (= :starting (:phase view)) (not (:uncertain? view)) (not (:accepted? view)) active?)
        (when (some #(= (:id gate) (:id %)) (query/ready-with-rt rt (:run-id attempt) {}))
          (let [inspection (validation/check-attempt rt :launch attempt)
                refused? (and inspection (not= :allow (:decision inspection)))
                candidate (cond-> attempt inspection (assoc :validation-revision (:revision inspection)))
                dispatched (if refused?
                             (store/advance rt candidate :invalid
                                            {:observation {:status :terminal :outcome :failed :settlement :settled
                                                           :value nil :error {:code "validation/launch-refused"
                                                                              :message (:reason inspection) :data {}}
                                                           :evidence {"never-started" true}}})
                             (store/advance rt candidate :dispatch {}))]
            (store/save! rt row dispatched)
            (when-not (or refused? (:attention dispatched))
              (effect :start dispatched (store/attempt-row rt (:attempt-id attempt))))))

        (#{:starting :running} (:phase view)) (effect :observe attempt row)))))

(defn- acknowledge [rt descriptor attempt]
  (try
    (when-not (= {:status :acknowledged}
                 ((state/callable rt (:acknowledge descriptor)) rt (context attempt)))
      (throw (ex-info "Unknown acknowledgement response" {})))
    {:name :acknowledged}
    (catch Throwable error
      {:name :ack-unknown :error (data/error "execution/acknowledgement" error)})))

(defn drive-attempt!
  "Persist intent under the Workflow guard, then dispatch outside it.

  The execution monitor spans bounded adapter dispatch. Lock order is execution
  monitor -> optional adapter/domain lock -> Workflow guard -> transaction; no
  adapter is called with the Workflow guard held."
  [rt row]
  (let [run-id (:run-id (store/record row))]
    (state/with-run!
      rt run-id
      (fn []
        (when-let [{:keys [operation attempt row descriptor]}
                   (guard/with-run! rt run-id (fn [] (next-effect! rt row)))]
          (let [response (if (= :acknowledge operation)
                           (acknowledge rt descriptor attempt)
                           (call-adapter rt descriptor operation attempt))]
            (guard/with-run!
              rt run-id
              (fn []
                (if (= :acknowledge operation)
                  (let [updated (store/advance rt attempt (:name response) (dissoc response :name))]
                    (store/save-cleanup! rt row
                                         (assoc updated :result (store/result-envelope updated (store/model updated)))))
                  (update-observation! rt row attempt response))))))))
    (swap! (:errors (state/state rt)) dissoc [:attempt (attr-get row :execution/token)])
    nil))

(defn- adopt-and-claim! [rt descriptor gate claim?]
  (when-let [root (roots/nearest-root rt gate)]
    (when-let [run-id (attr-get root :workflow/run-id)]
      (when (or (not claim?) (= "active" (:state root)))
        (state/with-run!
          rt run-id
          (fn [] (guard/with-run!
                   rt run-id
                   (fn []
                     (let [gate (weaver/show rt (:id gate))
                           root (roots/nearest-root rt gate)]
                       (when (and (or (not claim?) (= "active" (:state root))) (= "active" (:state gate))
                                  (= descriptor (get (state/selected rt) (name (:waiter descriptor))))
                                  (or (not claim?)
                                      (not (contains? @(:draining (state/state rt)) (name (:waiter descriptor))))))
                         (if (and claim? (not (attr-get root :execution/freeze))
                                  (not (attr-get gate :execution/current))
                                  (some #(= (:id gate) (:id %)) (query/ready-with-rt rt run-id {})))
                           (store/claim! rt root gate (store/prepare-attempt rt descriptor root gate) nil)
                           (when-not (attr-get gate :execution/owner)
                             (store/apply-plan!
                              rt {(:id root) root (:id gate) gate}
                              {:refs {:gate (:id gate)}
                               :strands [{:ref :gate :attributes {"execution/owner" (name (:waiter descriptor))
                                                                  "execution/revision" (:revision descriptor)}}]} {})))))))))))))

(defn retain-ownership!
  "Join any in-progress claims and persist unstarted ownership before removal."
  [rt descriptor]
  (doseq [gate (weaver/list rt [:and [:= :state "active"]
                                [:= [:attr "workflow/gate"] (name (:waiter descriptor))]] {})]
    (adopt-and-claim! rt descriptor gate false)))

(defn- reconcile-one! [rt gate-id f]
  (try
    (f)
    (swap! (:errors (state/state rt)) dissoc gate-id)
    (catch Throwable error
      (swap! (:errors (state/state rt)) assoc gate-id (data/error "execution/driver" error)))))

(defn scan!
  "Drive selected waiters only; legacy Agent/queue remain source-owned."
  [rt]
  (current/with-runtime rt
    (when (compare-and-set! (:dirty (state/state rt)) true false)
      (doseq [[waiter descriptor] (state/selected rt)]
        (doseq [gate (weaver/list rt [:and [:= :state "active"] [:= [:attr "workflow/gate"] waiter]] {})]
          (reconcile-one! rt [:gate (:id gate)] #(adopt-and-claim! rt descriptor gate true)))))
    (doseq [row (weaver/list rt [:and [:= [:attr "kind"] "workflow-execution"]
                                 [:= [:attr "execution/reconcile"] true]] {})]
      (reconcile-one! rt [:attempt (attr-get row :execution/token)] #(drive-attempt! rt row)))))

(defn on-event
  "Coalesce graph changes; all graph selection and adapters run on the scheduler."
  [_]
  (reset! (:dirty (state/state (current/runtime))) true)
  nil)

(defn open-scheduler! [rt]
  (reset! (:dirty (state/state rt)) true)
  (let [slot (:scheduler (state/state rt))]
    (locking slot
      (when-not @slot
        (events/register-handler! rt :workflow/execution
                                  #{:strand/added :strand/updated :batch/applied}
                                  'millhouse.workflow.internal.execution.driver/on-event {})
        (let [threads (reify ThreadFactory
                        (newThread [_ task] (doto (Thread. task "workflow-execution") (.setDaemon true))))
              scheduler (Executors/newSingleThreadScheduledExecutor threads)]
          (reset! slot scheduler)
          (.scheduleWithFixedDelay
           scheduler
           ^Runnable #(try (scan! rt)
                           (swap! (:errors (state/state rt)) dissoc :scan)
                           (catch Throwable error
                             (swap! (:errors (state/state rt)) assoc :scan (data/error "execution/driver" error))))
           0 100 TimeUnit/MILLISECONDS))))))

(defn close-scheduler! [rt]
  (let [slot (:scheduler (state/state rt))]
    (locking slot
      (when-let [^ScheduledExecutorService scheduler @slot]
        (.shutdownNow scheduler)
        (when-not (.awaitTermination scheduler 1000 TimeUnit/MILLISECONDS)
          (throw (ex-info "Execution scheduler did not settle" {})))
        (reset! slot nil)))
    (events/unregister-handler! rt :workflow/execution)))
