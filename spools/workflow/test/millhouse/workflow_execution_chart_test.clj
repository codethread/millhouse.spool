(ns millhouse.workflow-execution-chart-test
  "Pure real-library lifecycle and complete working-memory restoration proofs."
  (:require [clojure.test :refer [deftest is testing]]
            [com.fulcrologic.statecharts :as sc]
            [com.fulcrologic.statecharts.protocols :as protocols]
            [millhouse.workflow.internal.execution.chart :as chart]
            [millhouse.workflow.internal.execution.data :as data]
            [millhouse.workflow.internal.execution.store :as store]))

(defn- step [snapshot event & [attributes]]
  ;; A fresh environment on every step is stronger than reusing a process-local
  ;; interpreter. Only the complete persisted EDN crosses this boundary.
  (chart/decide (chart/environment) snapshot
                (merge {:attempt-id "attempt" :name event :now "2026-09-28T00:00:01Z"} attributes)))

(defn- initial []
  (chart/initial (chart/environment) "attempt" "2026-09-28T00:00:00Z"))

(def ^:private terminal
  {:status :terminal :outcome :succeeded :settlement :settled :value nil :error nil :evidence {}})

(def ^:private stop
  {:outcome :timed-out :error {:code "deadline" :message "Deadline expired" :data {}}})

(deftest full-memory-traces-and-stop-linearization
  (let [initial (initial)
        dispatched (:snapshot (step initial :dispatch))
        busy (:snapshot (step dispatched :busy))
        accepted (:snapshot (step (:snapshot (step busy :dispatch)) :observed
                                  {:observation {:status :pending :phase :running :reference "local"}}))
        terminal (:snapshot (step accepted :observed {:observation terminal}))
        committed (:snapshot (step terminal :commit))
        finished (:snapshot (step committed :acknowledged))]
    (doseq [snapshot [initial dispatched busy accepted terminal committed finished]]
      (is (= (data/decode snapshot) (data/decode (data/encode (data/decode snapshot)))))
      (is (data/edn-data? (data/decode snapshot))))
    (is (false? (:accepted? (chart/view busy))))
    (is (false? (:uncertain? (chart/view busy))))
    (is (= :done (:phase (chart/view finished))))
    (is (= :succeeded (get-in (chart/view finished) [:terminal :outcome])))
    (is (contains? (:terminal (chart/view finished)) :value))
    (is (nil? (get-in (chart/view finished) [:terminal :value])))
    (is (= finished (:snapshot (step finished :observed {:observation terminal}))))
    (let [unknown (:snapshot (step committed :ack-unknown {:error {:code "cleanup" :message "Unavailable" :data {}}}))
          recovered (step unknown :acknowledged)]
      (is (= :unknown (:acknowledgement (chart/view unknown))))
      (is (= :confirmed (get-in recovered [:view :acknowledgement])))
      (is (nil? (get-in recovered [:view :attention]))))
    (is (= initial (:snapshot (step initial :dispatch {:attempt-id "stale"}))))
    (testing "stop wins until the result batch commits, including retained terminal evidence"
      (doseq [snapshot [accepted terminal]]
        (let [stopped (:snapshot (step snapshot :stop {:reason stop}))
              settled (:snapshot (step stopped :observed {:observation
                                                          {:status :terminal :outcome :succeeded
                                                           :settlement :settled :value "late" :evidence {}}}))]
          (is (= :timed-out (get-in (chart/view settled) [:terminal :outcome])))
          (is (nil? (get-in (chart/view settled) [:terminal :value]))))))
    (testing "never-accepted stop settles without a backend stop effect"
      (let [decision (step busy :stop {:reason stop})]
        (is (empty? (:effects decision)))
        (is (= :settled (get-in decision [:view :terminal :settlement])))))
    (testing "a committed success is not overwritten by a later stop"
      (is (= :succeeded (get-in (step committed :stop {:reason stop}) [:view :terminal :outcome]))))))

(deftest errors-preserve-the-last-valid-snapshot-and-emit-no-effects
  (let [snapshot (initial)
        failure {:status :terminal :outcome :failed :settlement :settled :value nil
                 :error {:code "bad-input" :message "Invalid input" :data {}}
                 :evidence {"never-started" true}}
        invalid (:snapshot (step snapshot :invalid {:observation failure}))]
    (is (= failure (:terminal (chart/view invalid))))
    (is (= :failed (get-in (step invalid :commit) [:view :terminal :outcome])))
    (testing "strict-env error.execution normalizes Throwable/expression and discards partial decisions"
      (let [decision (step snapshot :invalid {:observation (assoc failure :value (Object.))})]
        (is (:invalid? decision))
        (is (= snapshot (:snapshot decision)))
        (is (empty? (:effects decision)))
        (is (= "execution/interpreter" (get-in decision [:view :attention :code])))
        (is (data/json? (get-in decision [:view :attention])))
        (is (= :starting (:phase (chart/view (:snapshot decision)))))))
    (testing "malformed interpreter return cannot authorize an effect"
      (let [malformed (reify protocols/Processor
                        (start! [_ _ _ _] {})
                        (exit! [_ _ _ _] {})
                        (process-event! [_ _ _ _] {}))
            decision (chart/decide (assoc (chart/environment) ::sc/processor malformed)
                                   snapshot {:name :dispatch :attempt-id "attempt" :now "now"})]
        (is (:invalid? decision))
        (is (= snapshot (:snapshot decision)))
        (is (empty? (:effects decision)))))
    (is (thrown? clojure.lang.ExceptionInfo (data/decode "{:version 99 :memory {}}")))
    (is (thrown? clojure.lang.ExceptionInfo (data/encode {:handle (Object.)})))))

(defrecord JsonRecord [value])

(deftest normalized-errors-and-json-values-fit-the-durable-boundary
  (is (data/observation? {:status :unknown :reason (data/error "interpreter/error" nil)}))
  (doseq [message [nil "" (.repeat "x" 20000)]]
    (let [error (data/error "callback/threw" (ex-info message {:not-retained (Object.)}))]
      (is (data/observation? {:status :unknown :reason error}))
      (is (= error (data/decode (data/encode error))))))
  (is (false? (data/json? (->JsonRecord "plain field"))))
  (is (data/json? nil)))

(deftest opaque-provenance-is-terminal-only-and-survives-result-recomputation
  (is (data/observation? terminal))
  (is (not (contains? (store/result-envelope {} {:terminal terminal}) :executor-run-id)))
  (doseq [id [nil "" "  " 1 :run]]
    (is (not (data/observation? (assoc terminal :executor-run-id id)))))
  (doseq [observation [{:status :busy}
                       {:status :pending :phase :running}
                       {:status :unknown :reason (data/error "test/unknown" nil)}]]
    (is (not (data/observation? (assoc observation :executor-run-id "opaque")))))
  (let [observation (assoc terminal :executor-run-id "opaque")
        accepted (:snapshot (step (:snapshot (step (initial) :dispatch))
                                  :observed {:observation observation}))]
    (is (data/observation? observation))
    (doseq [outcome [:succeeded :cancelled :failed]]
      (let [ready (if (= :succeeded outcome) accepted
                      (:snapshot (step accepted :stop {:reason (assoc stop :outcome outcome)})))
            committed (:snapshot (step ready :commit))
            uncertain (:snapshot (step committed :ack-unknown
                                       {:error (data/error "test/ack" nil)}))
            acknowledged (:snapshot (step uncertain :acknowledged))]
        (doseq [snapshot [ready committed uncertain acknowledged]]
          (let [result (store/result-envelope {} (chart/view snapshot))]
            (is (= "opaque" (:executor-run-id result)))
            (is (= outcome (:outcome result)))))))))
