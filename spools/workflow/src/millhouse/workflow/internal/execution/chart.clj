(ns millhouse.workflow.internal.execution.chart
  "Private synchronous Statecharts decision core. No effects run in this namespace."
  (:require [com.fulcrologic.statecharts :as sc]
            [com.fulcrologic.statecharts.chart :refer [statechart]]
            [com.fulcrologic.statecharts.data-model.operations :as ops]
            [com.fulcrologic.statecharts.data-model.working-memory-data-model :as dm]
            [com.fulcrologic.statecharts.elements :refer [state transition script final on-entry]]
            [com.fulcrologic.statecharts.events :as events]
            [com.fulcrologic.statecharts.protocols :as sp]
            [com.fulcrologic.statecharts.simple :as simple]
            [millhouse.workflow.internal.execution.data :as data]
            [taoensso.timbre :as log]))

(defn- action [id f]
  (script {:id id
           :expr (fn [_ model]
                   (let [patch (f model (get-in model [:_event :data]))]
                     (when-not (and (map? patch) (data/edn-data? patch))
                       (throw (ex-info "Invalid execution action result" {})))
                     (mapv (fn [[k v]] (ops/assign k v)) patch)))}))

(defn- matches? [status]
  (fn [_ model] (= status (get-in model [:_event :data :observation :status]))))

(defn- observed [model event]
  (let [observation (:observation event)]
    {:accepted? true :uncertain? false :desired nil :attention nil
     :reference (:reference observation) :observation observation
     :updated-at (:now event)
     :terminal (when (= :terminal (:status observation))
                 (if-let [reason (:stop-reason model)]
                   (assoc observation :outcome (:outcome reason) :value nil :error (:error reason))
                   observation))}))

(defn- stop-patch [model event]
  (let [reason (or (:stop-reason model) (:reason event))]
    {:stop-reason reason :updated-at (:now event)
     :desired (when-not (:terminal model) :stop)
     :terminal (when-let [terminal (:terminal model)]
                 (assoc terminal :outcome (:outcome reason) :value nil :error (:error reason)))}))

(defn- observation-transitions [prefix]
  [(transition {:id (keyword (str prefix "-terminal")) :event :observed
                :cond (matches? :terminal) :target :committing}
               (action (keyword (str prefix "-terminal-data")) observed))
   (transition {:id (keyword (str prefix "-pending")) :event :observed
                :cond (matches? :pending) :target (if (= prefix "stopping") :stopping :running)}
               (action (keyword (str prefix "-pending-data")) observed))
   (transition {:id (keyword (str prefix "-unknown")) :event :observed :cond (matches? :unknown)}
               (action (keyword (str prefix "-unknown-data"))
                       (fn [_ event] {:desired nil :attention (get-in event [:observation :reason])
                                      :updated-at (:now event)})))])

(def ^:private chart
  (statechart {}
              (state {:id :initial :initial? true}
                     (transition {:id :initial-transition :target :active}))
              (state {:id :active}
                     (state {:id :active-initial :initial? true}
                            (transition {:id :active-initial-transition :target :starting}))
                     (transition {:id :interpreter-error :event :error.execution}
                                 (action :record-interpreter-error
                                         (fn [_ event] {:interpreter-error
                                                        (data/error "execution/interpreter" (:error event))})))
                     (apply state {:id :starting}
                            (concat
                             [(transition {:id :dispatch :event :dispatch
                                           :cond (fn [_ m] (not (or (:accepted? m) (:uncertain? m))))}
                                          (action :dispatch-data (fn [_ e] {:uncertain? true :desired :start :updated-at (:now e)})))
                              (transition {:id :busy :event :busy}
                                          (action :busy-data (fn [_ e] {:uncertain? false :desired nil :updated-at (:now e)})))
                              (transition {:id :invalid :event :invalid :target :committing}
                                          (action :invalid-data (fn [_ e] {:terminal (:observation e) :desired nil})))
                              (transition {:id :never-started-stop :event :stop :target :committing
                                           :cond (fn [_ m] (not (or (:accepted? m) (:uncertain? m))))}
                                          (action :never-started-stop-data
                                                  (fn [_ e] {:stop-reason (:reason e) :desired nil
                                                             :terminal {:status :terminal :settlement :settled
                                                                        :outcome (get-in e [:reason :outcome]) :value nil
                                                                        :error (get-in e [:reason :error])
                                                                        :evidence {"never-started" true}}})))
                              (transition {:id :uncertain-stop :event :stop :target :stopping}
                                          (action :uncertain-stop-data stop-patch))]
                             (observation-transitions "starting")))
                     (apply state {:id :running}
                            (concat [(transition {:id :running-stop :event :stop :target :stopping}
                                                 (action :running-stop-data stop-patch))]
                                    (observation-transitions "running")))
                     (apply state {:id :stopping}
                            (concat [(transition {:id :repeat-stop :event :stop}
                                                 (action :repeat-stop-data stop-patch))]
                                    (observation-transitions "stopping")))
                     (state {:id :committing}
                            (transition {:id :commit-stop :event :stop}
                                        (action :commit-stop-data stop-patch))
                            (transition {:id :commit :event :commit :target :done}
                                        (action :commit-data (fn [m e] {:committed-at (:now e)
                                                                        :desired (when (:accepted? m) :acknowledge)
                                                                        :acknowledgement (if (:accepted? m) :pending :not-needed)}))))
                     (state {:id :done}
                            (transition {:id :acknowledged :event :acknowledged :target :finished}
                                        (action :acknowledged-data (fn [_ _] {:desired nil :acknowledgement :confirmed})))
                            (transition {:id :ack-unknown :event :ack-unknown}
                                        (action :ack-unknown-data (fn [_ e] {:desired :acknowledge :acknowledgement :unknown
                                                                             :attention (:error e)})))))
              (final {:id :finished}
                     (on-entry {:id :finished-entry}
                               (action :finished-data (fn [_ _] {:finished? true}))))))

(defn environment
  "Build the private strict environment; register the fixed chart once."
  []
  (let [env (simple/strict-env {::sc/invocation-processors []})]
    (simple/register! env :workflow.execution/v1 chart)
    env))

(defn view
  "Return our data model, retaining terminal evidence after SCXML final exit."
  [snapshot]
  (let [memory (data/decode snapshot)
        model (::dm/data-model memory)]
    (assoc model :phase (or (some (::sc/configuration memory)
                                  [:starting :running :stopping :committing :done])
                            (when (:finished? model) :done)))))

(defn- snapshot [memory]
  (data/encode (update memory ::dm/data-model dissoc :_event)))

(defn initial
  "Start one attempt with an explicit identity and clock value."
  [env attempt-id now]
  (log/with-min-level :warn
    (snapshot (sp/start! (::sc/processor env) env :workflow.execution/v1
                         {::sc/session-id attempt-id
                          ::sc/invocation-data {:attempt-id attempt-id :created-at now
                                                :accepted? false :uncertain? false}}))))

(defn decide
  "Return a complete snapshot, view and effect intents, never executing them.

  Duplicate/stale attempt events are no-ops. Interpreter failures preserve the
  last valid snapshot and discard every intent produced by the invalid decision."
  [env previous {:keys [attempt-id name] :as event}]
  (let [prior (view previous)]
    (if (not= attempt-id (:attempt-id prior))
      {:snapshot previous :view prior :effects []}
      (try
        (let [memory (log/with-min-level :warn
                       (sp/process-event! (::sc/processor env) env (data/decode previous)
                                          (events/new-event name (dissoc event :name))))
              _ (when-let [error (get-in memory [::dm/data-model :interpreter-error])]
                  (throw (ex-info (:message error) {:normalized error})))
              next (snapshot memory)
              result (view next)]
          (when-not (:phase result)
            (throw (ex-info "Invalid interpreter working memory" {})))
          {:snapshot next :view result
           :effects (if-let [desired (:desired result)] [desired] [])})
        (catch Throwable error
          {:snapshot previous
           :view (assoc prior :attention (or (:normalized (ex-data error))
                                             (data/error "execution/interpreter" error)))
           :effects [] :invalid? true})))))
