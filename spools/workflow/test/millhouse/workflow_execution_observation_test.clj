(ns millhouse.workflow-execution-observation-test
  "Persist changed execution evidence without turning reconciliation into events."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [millhouse.test-support :as support]
            [millhouse.workflow :as workflow]
            [millhouse.workflow.execution :as execution]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.hooks.alpha :as hooks]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.spool.alpha :refer [attr-get]]))

(def ^:private pending
  {:status :pending :phase :running :reference "retained-work"})

(defn- backend [rt]
  (:state (runtime/spool-state
           rt ::backend {:version 1}
           #(hash-map :state (atom {:observation pending :ack-error "Acknowledgement unavailable"
                                    :starts 0 :observes 0 :acks 0 :writes 0})))))

(defn request "Project an empty test request." [_] {})

(defn start! "Accept one operation without an external process." [rt _]
  (swap! (backend rt) update :starts inc)
  (:observation @(backend rt)))

(defn observe! "Read the test-controlled backend evidence." [rt _]
  (swap! (backend rt) update :observes inc)
  (:observation @(backend rt)))

(defn acknowledge! "Report test-controlled acknowledgement availability." [rt _]
  (let [{:keys [ack-error]} (swap! (backend rt) update :acks inc)]
    (when ack-error (throw (ex-info ack-error {})))
    {:status :acknowledged}))

(defn count-attempt-writes!
  "Count real public-store attempt mutations at the transaction boundary."
  [ctx]
  (when (some #(= "workflow-execution" (attr-get (:before %) :kind)) (:batch/updated ctx))
    (swap! (backend (current/runtime)) update :writes inc))
  nil)

(s/def ::request map?)
(s/def ::result any?)

(def descriptor
  "Test-controlled observations; the real kernel owns all state and persistence."
  {:waiter :observation-proof :revision "test/v1"
   :request 'millhouse.workflow-execution-observation-test/request
   :request-spec ::request :result-spec ::result
   :start 'millhouse.workflow-execution-observation-test/start!
   :observe 'millhouse.workflow-execution-observation-test/observe!
   :stop 'millhouse.workflow-execution-observation-test/observe!
   :acknowledge 'millhouse.workflow-execution-observation-test/acknowledge!})

(deftest unchanged-unknown-evidence-does-not-amplify-graph-events
  (support/with-embedded-runtime
    (fn [rt _]
      (support/activate-spool! rt :workflow 'millhouse.workflow)
      (hooks/register-hook! rt :test/count-attempt-writes #{:batch/apply-before-commit}
                            'millhouse.workflow-execution-observation-test/count-attempt-writes!)
      (let [resource (execution/open! rt descriptor)]
        (try
          (let [started (workflow/start! "observations"
                                         (workflow/workflow
                                          "Observations"
                                          (workflow/gate :work "Work" :observation-proof)) {})
                selector {:run-id "observations" :step (:id (first (:ready started)))}
                inspect #(execution/inspect rt selector)
                reconcile #(execution/reconcile! rt selector)
                state (backend rt)
                unknown {:status :unknown :reference "retained-work"
                         :reason {:code "test/custody-missing" :message "Custody is missing" :data {}}}]
            (support/poll-until #(when (= :running (:phase (inspect))) true))
            (swap! state assoc :observation unknown)
            (is (= (:reason unknown) (:attention (reconcile))))
            (testing "unchanged unknown evidence is read again without a database write"
              (let [{:keys [writes observes]} @state]
                (dotimes [_ 20] (reconcile))
                (is (<= (+ observes 20) (:observes @state)))
                (is (= writes (:writes @state)))))
            (testing "returning to the same pending observation clears attention"
              (swap! state assoc :observation pending)
              (let [view (reconcile)]
                (is (= :running (:phase view)))
                (is (nil? (:attention view))))
              (let [writes (:writes @state)]
                (dotimes [_ 5] (reconcile))
                (is (= writes (:writes @state)))))
            (testing "changed uncertainty is retained, then remains quiet while stopping"
              (let [changed (assoc-in unknown [:reason :message] "Custody is still unavailable")]
                (swap! state assoc :observation changed)
                (is (= (:reason changed) (:attention (reconcile))))
                (execution/quiesce-run! rt "observations" "Retire test operation")
                (is (= :stopping (:phase (reconcile))))
                (let [writes (:writes @state)]
                  (dotimes [_ 20] (reconcile))
                  (is (= writes (:writes @state))))))
            (testing "unchanged acknowledgement failure stays observable without writes"
              (swap! state assoc :observation
                     {:status :terminal :outcome :failed :settlement :settled
                      :reference "retained-work" :value nil
                      :error {:code "test/failed" :message "Stopped" :data {}} :evidence {}})
              (let [done (support/poll-until
                          #(let [view (reconcile)]
                             (when (= :unknown (get-in view [:cleanup :acknowledgement])) view)))
                    {:keys [writes acks]} @state]
                (is (= :cancelled (get-in done [:result :outcome])))
                (dotimes [_ 20] (reconcile))
                (is (<= (+ acks 20) (:acks @state)))
                (is (= writes (:writes @state)))))
            (testing "acknowledgement recovery is still persisted"
              (swap! state assoc :ack-error nil)
              (let [view (reconcile)]
                (is (= :confirmed (get-in view [:cleanup :acknowledgement])))
                (is (nil? (:attention view))))
              (is (= 1 (:starts @state)))))
          (finally (execution/close! rt resource)))))))
