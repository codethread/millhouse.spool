(ns millhouse.workflow.internal.execution.state
  "Private runtime ownership for selected descriptors and execution monitors."
  (:require [clojure.spec.alpha :as s]
            [millstrand.api.runtime.alpha :as runtime]
            [millhouse.workflow.internal.execution.chart :as chart]
            [millhouse.workflow.internal.execution.data :as data])
  (:import [java.util.concurrent ExecutorService]
           [java.util.concurrent.locks ReentrantLock]))

(defn state [rt]
  (runtime/spool-state rt ::state {:version 1}
                       (fn []
                         (let [scheduler (atom nil)]
                           {:descriptors (atom {}) :draining (atom #{}) :locks (atom {})
                            :environment (chart/environment) :scheduler scheduler
                            :dirty (atom true) :errors (atom {})
                            :close-fn (fn []
                                        (when-let [^ExecutorService executor @scheduler]
                                          (.shutdownNow executor)))}))))

(defn selected [rt] @(:descriptors (state rt)))

(defn with-run! [rt run-id f]
  (let [locks (:locks (state rt))
        ^ReentrantLock lock (get (swap! locks #(if (contains? % run-id) %
                                                   (assoc % run-id (ReentrantLock.)))) run-id)]
    (.lock lock)
    (try (f) (finally (.unlock lock)))))

(defn callable [rt sym]
  (let [value (some-> (runtime/resolve-var rt sym) deref)]
    (when-not (fn? value)
      (throw (ex-info "Execution callback must resolve to a function" {:symbol sym})))
    value))

(defn validate-descriptor! [rt descriptor]
  (when-not (and (map? descriptor)
                 (= #{:waiter :revision :request :request-spec :result-spec
                      :start :observe :stop :acknowledge} (set (keys descriptor)))
                 (simple-keyword? (:waiter descriptor))
                 (data/nonblank? (:revision descriptor))
                 (every? #(and (qualified-keyword? %) (s/get-spec %))
                         ((juxt :request-spec :result-spec) descriptor))
                 (every? qualified-symbol? ((juxt :request :start :observe :stop :acknowledge) descriptor)))
    (throw (ex-info "Invalid execution descriptor" {:descriptor descriptor})))
  (doseq [key [:request :start :observe :stop :acknowledge]]
    (callable rt (get descriptor key)))
  descriptor)

(defn validate-legacy-candidates!
  "Reject publication of a legacy driver beside a selected descriptor."
  [{:keys [runtime entries]}]
  (doseq [waiter (keys entries)]
    (when (get (selected runtime) waiter)
      (throw (ex-info "Waiter already has a managed executor" {:waiter waiter}))))
  nil)
