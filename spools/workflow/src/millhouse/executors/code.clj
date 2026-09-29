(ns millhouse.executors.code
  "Code adapter for the shared Workflow execution lifecycle.

  Trusted callbacks occupy one of eight zero-queue workers until they actually
  return. Interruption requests stop, not settlement. Nil succeeds; non-JSON
  results fail. Lost local handles are unknown and never authorize a new launch."
  (:require [clojure.spec.alpha :as s]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.weaver.alpha :as weaver]
            [millhouse.workflow.execution :as execution]
            [millhouse.workflow.internal.execution.data :as data])
  (:import [java.util.concurrent ExecutorService RejectedExecutionException
            SynchronousQueue ThreadFactory ThreadPoolExecutor
            ThreadPoolExecutor$AbortPolicy TimeUnit]))

(s/def :code/fn #(and (string? %) (qualified-symbol? (symbol %))))
(s/def :code/params (s/and map? data/json?))
(s/def :code/timeout-secs pos-int?)
(s/def ::request (s/keys :req [:code/fn :code/params] :opt [:code/timeout-secs]))
(s/def ::result data/json?)

(defn request
  "Project the captured gate image without rereading live graph inputs."
  [{:keys [gate]}]
  (cond-> {:code/fn (attr-get gate :code/fn) :code/params (attr-get gate :code/params)}
    (some? (attr-get gate :code/timeout-secs))
    (assoc :code/timeout-secs (attr-get gate :code/timeout-secs))))

(defn- backend [rt]
  (runtime/spool-state rt ::backend {:version 1}
                       (fn []
                         (let [pool (atom nil)]
                           {:pool pool :handles (atom {})
                            :close-fn (fn []
                                        (when-let [^ExecutorService executor @pool]
                                          (.shutdownNow executor)))}))))

(defn- unknown []
  {:status :unknown :reference nil
   :reason {:code "code/handle-missing"
            :message "Code invocation handle missing; settlement is unknown. Drain before replacement."
            :data {}}})

(defn observe!
  "Observe the exact local invocation; absence is never positive settlement."
  [rt {:keys [attempt-id]}]
  (if-let [handle (get @(:handles (backend rt)) attempt-id)]
    (or (:terminal @handle)
        {:status :pending :phase (if (:stop? @handle) :stopping :running) :reference attempt-id})
    (unknown)))

(defn- terminal [outcome value error id]
  {:status :terminal :outcome outcome :settlement :settled :value value
   :error error :reference id :evidence {"callback-returned" true}})

(defn- invoke! [rt context handle]
  (current/with-runtime rt
    (let [id (:attempt-id context)
          result (try
                   (if (locking handle
                         (:stop? (swap! handle assoc :thread (Thread/currentThread))))
                     (terminal :cancelled nil nil id)
                     (let [{fn-name :code/fn params :code/params} (:request context)
                           callable (some-> (runtime/resolve-var rt (symbol fn-name)) deref)]
                       (when-not (fn? callable)
                         (throw (ex-info "code/fn did not resolve to a function" {})))
                       (let [value (callable params)]
                         (when-not (data/json? value)
                           (throw (ex-info "Code result is not JSON-safe" {})))
                         (terminal :succeeded value nil id))))
                   (catch Throwable error
                     (terminal :failed nil (data/error "code/callback" error) id)))]
      ;; Publication here follows callable return/throw. Future.cancel and an
      ;; interrupt flag cannot publish this receipt or make the worker free.
      (locking handle
        (swap! handle assoc :terminal result :thread nil)))))

(defn start!
  "Offer one invocation to the eight-worker pool; explicit busy means no acceptance."
  [rt {:keys [attempt-id] :as context}]
  (let [{:keys [handles pool]} (backend rt)]
    (locking handles
      (if (contains? @handles attempt-id)
        (observe! rt context)
        (let [handle (atom {:stop? false})
              ^ThreadPoolExecutor executor @pool]
          (when (or (nil? executor) (.isShutdown executor))
            (throw (ex-info "Code worker pool is not active" {})))
          (swap! handles assoc attempt-id handle)
          (try
            (.execute executor ^Runnable #(invoke! rt context handle))
            {:status :pending :phase :running :reference attempt-id}
            (catch RejectedExecutionException _
              (swap! handles dissoc attempt-id)
              {:status :busy})))))))

(defn stop!
  "Interrupt this exact worker without claiming that the callable has settled."
  [rt {:keys [attempt-id] :as context}]
  (when-let [handle (get @(:handles (backend rt)) attempt-id)]
    (locking handle
      (when-let [^Thread thread (:thread (swap! handle assoc :stop? true))]
        (.interrupt thread))))
  (observe! rt context))

(defn acknowledge!
  "Forget positively settled local evidence after the common result is durable."
  [rt {:keys [attempt-id]}]
  (let [handles (:handles (backend rt))]
    (locking handles
      (when-let [handle (get @handles attempt-id)]
        (when-not (:terminal @handle)
          (throw (ex-info "Code invocation has not settled" {:attempt-id attempt-id})))
        (swap! handles dissoc attempt-id))))
  {:status :acknowledged})

(def executor
  "Inert Code descriptor. Select code-engine to activate this driver."
  {:waiter :code :revision "code-v1"
   :request 'millhouse.executors.code/request :request-spec ::request :result-spec ::result
   :start 'millhouse.executors.code/start! :observe 'millhouse.executors.code/observe!
   :stop 'millhouse.executors.code/stop! :acknowledge 'millhouse.executors.code/acknowledge!})

(defn- require-clean-cutover! [rt]
  (when-let [gate (first (weaver/list rt [:and [:= :state "active"]
                                          [:= [:attr "workflow/gate"] "code"]
                                          [:or [:exists [:attr "code/running"]]
                                           [:and [:exists [:attr "gate/error"]]
                                            [:not [:exists [:attr "execution/current"]]]]]] {}))]
    (throw (ex-info "Drain legacy Code gates before selecting managed execution; no snapshot translation is supported"
                    {:gate-id (:id gate)}))))

(defn open-code-engine!
  "Open the bounded backend and select its common lifecycle descriptor.

  Refuse active legacy invocation/error markers rather than translating them or
  treating a missing local handle as settlement. Drain before this cutover."
  [{:keys [runtime]}]
  (require-clean-cutover! runtime)
  (let [pool (:pool (backend runtime))]
    (locking pool
      (when-let [^ExecutorService old @pool]
        (when-not (.isTerminated old)
          (throw (ex-info "Prior Code workers are not settled; drain before reopening" {}))))
      (let [threads (reify ThreadFactory
                      (newThread [_ task] (doto (Thread. task "workflow-code") (.setDaemon true))))
            worker-pool (ThreadPoolExecutor. 8 8 0 TimeUnit/MILLISECONDS (SynchronousQueue.)
                                             threads (ThreadPoolExecutor$AbortPolicy.))]
        (reset! pool worker-pool)
        (try
          {:pool worker-pool :execution (execution/open! runtime executor)}
          (catch Throwable error
            (.shutdownNow worker-pool)
            (throw error)))))))

(defn close-code-engine!
  "Persist stop intent before interrupting workers; retain unconfirmed handles."
  [{:keys [runtime resource]}]
  (execution/close! runtime (:execution resource))
  (.shutdownNow ^ExecutorService (:pool resource))
  {:closed :code-engine})

(lifecycle/defresource code-engine
  "Select the common Code lifecycle and own its eight invocation workers."
  {:open 'millhouse.executors.code/open-code-engine!
   :close 'millhouse.executors.code/close-code-engine!})
