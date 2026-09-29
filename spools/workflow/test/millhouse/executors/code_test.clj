(ns millhouse.executors.code-test
  "Code-specific mapping/capacity proofs plus the common public tracer bullet."
  (:require [clojure.test :refer [deftest is]]
            [millhouse.executors.code :as code]
            [millhouse.test-support :as support]
            [millhouse.workflow :as workflow]
            [millhouse.workflow.execution :as execution]
            [millhouse.workflow.cli :as cli]
            [millstrand.api.cli.alpha :as cli-alpha]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.test.alpha :as t])
  (:import [java.time Duration Instant]
           [java.util.concurrent CountDownLatch TimeUnit]))

(defn return-value "Return the supplied JSON value, including nil." [params] (:value params))
(defn throw-value "Fail this callback." [_] (throw (ex-info "Callback failed" {})))
(defn interrupt-value "Report callback interruption without automatic retry." [_]
  (throw (InterruptedException. "Interrupted callback")))
(defn non-json-value "Return an invalid backend value." [_] (Object.))

(defn- with-code [f]
  (support/with-runtime
    (fn [rt _]
      (support/activate-spool! rt :workflow 'millhouse.workflow)
      (support/activate-spool! rt :code 'millhouse.test-modules.code-executor :after [:workflow])
      (f rt))))

(defn- definition [function params]
  (workflow/workflow "Code"
                     (workflow/gate :check "Check" :code
                                    :attributes {"code/fn" function "code/params" params})
                     (workflow/step :after "After" :self :depends-on [:check])))

(defn- from-argv [rt argv]
  (cli/workflow {:op/args (cli-alpha/parse (:arg-spec (weaver/resolve-op rt 'workflow)) argv {})
                 :op/argv argv}))

(defn- view [rt run-id gate-id]
  (execution/inspect rt {:run-id run-id :step gate-id}))

(defn- await-done [rt run-id gate-id]
  (support/poll-until #(let [v (view rt run-id gate-id)]
                         (when (= :done (:phase v)) v))
                      {:on-timeout #(throw (ex-info "Attempt did not finish" (view rt run-id gate-id)))}))

(deftest public-success-failure-and-exact-repaired-retry
  (with-code
    (fn [rt]
      (support/activate-spool! rt :workflow-cli 'millhouse.test-modules.workflow-cli :after [:workflow])
      (is (= :unknown (:status (code/observe! rt {:attempt-id "lost-local-handle"}))))
      (doseq [[run-id value] [["value" {:nested [1 true "ok"]}] ["nil" nil]]]
        (let [started (workflow/start! run-id
                                       (definition "millhouse.executors.code-test/return-value" {:value value}) {})
              gate-id (:id (first (:ready started)))
              done (await-done rt run-id gate-id)]
          (is (= :succeeded (get-in done [:result :outcome])))
          (is (= value (get-in done [:result :value])))
          (is (= "After" (:title (first (workflow/ready run-id)))))))
      (doseq [[run-id function] [["throw" "millhouse.executors.code-test/throw-value"]
                                 ["invalid" "unqualified"]
                                 ["interrupt" "millhouse.executors.code-test/interrupt-value"]
                                 ["json" "millhouse.executors.code-test/non-json-value"]]]
        (let [started (workflow/start! run-id (definition function {}) {})
              gate-id (:id (first (:ready started)))
              failed (await-done rt run-id gate-id)
              retry {:run-id run-id :step gate-id :expected-attempt (:attempt-id failed)
                     :request-id "repair" :reason "Fix callback" :by-identity "test-worker"}]
          (is (= :failed (get-in failed [:result :outcome])))
          (is (= :settled (get-in failed [:result :settlement])))
          (when (= "invalid" run-id)
            (is (false? (:accepted? failed)))
            (is (thrown? clojure.lang.ExceptionInfo
                         (execution/retry! rt (assoc retry :expected-revision "unsupported")))))
          (is (thrown? clojure.lang.ExceptionInfo
                       (weaver/update! rt gate-id {:attributes {"gate/error" nil}})))
          (is (thrown? clojure.lang.ExceptionInfo
                       (workflow/complete! run-id {:step gate-id :executor "code"})))
          (weaver/update! rt gate-id {:attributes {"code/fn" "millhouse.executors.code-test/return-value"
                                                   "code/params" {:value "repaired"}}})
          (is (= (:attempt-id failed)
                 (:attempt-id (from-argv rt ["execution" run-id "--step" gate-id]))))
          (is (= "eligible" (:status (from-argv rt ["retry" run-id "--step" gate-id
                                                    "--expected-attempt" (:attempt-id failed)
                                                    "--request-id" "repair" "--reason" "Fix callback"
                                                    "--by-identity" "test-worker" "--dry-run"]))))
          (let [accepted (execution/retry! rt retry)
                done (await-done rt run-id gate-id)]
            (is (= :accepted (:status accepted)))
            (is (not= (:attempt-id failed) (:attempt-id done)))
            (is (= "repaired" (get-in done [:result :value])))
            (weaver/update! rt gate-id {:attributes {"code/params" {:value "later"}}})
            (is (= (:action accepted) (:action (execution/retry! rt retry))))
            (is (= "replayed" (:status (from-argv rt ["retry" run-id "--step" gate-id
                                                      "--expected-attempt" (:attempt-id failed)
                                                      "--request-id" "repair" "--reason" "Fix callback"
                                                      "--by-identity" "test-worker"]))))
            (is (thrown? clojure.lang.ExceptionInfo
                         (execution/retry! rt (assoc retry :reason "conflict"))))))))))

(def ^:private controls (atom {}))
(def ^:private calls (atom {}))

(defn stubborn
  "Ignore interruption until the test-owned release establishes real settlement."
  [{:keys [id]}]
  (let [{:keys [entered release interrupted]} (get @controls id)]
    (swap! calls update id (fnil inc 0))
    (.countDown ^CountDownLatch entered)
    (loop []
      (if (try (.await ^CountDownLatch release) true
               (catch InterruptedException _
                 (.countDown ^CountDownLatch interrupted)
                 false))
        "late"
        (recur)))))

(defn count-value "Count accepted callback invocations." [{:keys [id]}]
  (swap! calls update id (fnil inc 0))
  id)

(defn- latch! [^CountDownLatch latch]
  (is (.await latch (support/await-budget-ms) TimeUnit/MILLISECONDS)))

(defn- start-code! [run-id function params timeout]
  (let [definition (definition function params)
        definition (cond-> definition timeout
                           (assoc-in [:steps 0 :attributes "code/timeout-secs"] timeout))]
    (:id (first (:ready (workflow/start! run-id definition {}))))))

(defn- await-busy [rt run-id gate-id]
  ;; All eight callable latches remain held. Join a bounded public driver turn
  ;; for this exact attempt, rather than infer its admission from another gate.
  (support/poll-until
   #(when (:attempt-id (view rt run-id gate-id))
      (let [v (execution/reconcile! rt {:run-id run-id :step gate-id})]
        (when (and (= :starting (:phase v)) (false? (:accepted? v))
                   (false? (:dispatch-uncertain? v))) v)))
   {:on-timeout #(throw (ex-info "Attempt did not reach busy admission" (view rt run-id gate-id)))}))

(deftest occupied-workers-busy-stop-and-fixed-deadline
  (with-code
    (fn [rt]
      (t/set-clock! rt (t/manual-clock (Instant/parse "2026-09-28T00:00:00Z")))
      (reset! calls {})
      (reset! controls (into {} (for [i (range 8)]
                                  [i {:entered (CountDownLatch. 1) :release (CountDownLatch. 1)
                                      :interrupted (CountDownLatch. 1)}])))
      (try
        (let [gates (mapv (fn [i]
                            (start-code! (str "occupied-" i) "millhouse.executors.code-test/stubborn"
                                         {:id i} (when (zero? i) 1))) (range 8))]
          (doseq [i (range 8)] (latch! (get-in @controls [i :entered])))
          (let [stopped (start-code! "busy-stop" "millhouse.executors.code-test/count-value" {:id "stop"} 30)
                eventual (start-code! "eventual" "millhouse.executors.code-test/count-value" {:id "eventual"} 30)
                stopped-busy (await-busy rt "busy-stop" stopped)
                busy (await-busy rt "eventual" eventual)
                freeze (execution/quiesce-run! rt "busy-stop" "Stop before capacity")]
            (is (= :settled (:status (support/poll-until
                                      #(let [receipt (execution/retire! rt freeze)]
                                         (when (= :settled (:status receipt)) receipt))))))
            (let [done (await-done rt "busy-stop" stopped)]
              (is (= (:attempt-id stopped-busy) (:attempt-id done)))
              (is (= :cancelled (get-in done [:result :outcome]))))
            (is (nil? (get @calls "stop")))
            (t/advance! rt (Duration/ofSeconds 2))
            (latch! (get-in @controls [0 :interrupted]))
            (let [timed-out (view rt "occupied-0" (first gates))]
              (is (= :timed-out (get-in timed-out [:stop-reason :outcome])))
              (is (= :stopping (:phase timed-out)))
              (is (nil? (:result timed-out)))
              (is (false? (:accepted? (view rt "eventual" eventual))))
              (is (thrown? clojure.lang.ExceptionInfo
                           (execution/retry! rt {:run-id "occupied-0" :step (first gates)
                                                 :expected-attempt (:attempt-id timed-out)
                                                 :request-id "unsafe" :reason "Still occupied" :by-identity "test-worker"}))))
            (weaver/update! rt eventual {:attributes {"code/params" {:id "edited-after-claim"}}})
            ;; Capacity returns only after an unrelated occupied callable returns.
            (.countDown ^CountDownLatch (get-in @controls [1 :release]))
            (let [done (await-done rt "eventual" eventual)]
              (is (= (:deadline busy) (:deadline done)))
              (is (= (:attempt-id busy) (:attempt-id done)))
              (is (= 1 (get @calls "eventual")))
              (is (nil? (get @calls "edited-after-claim"))))
            (.countDown ^CountDownLatch (get-in @controls [0 :release]))
            (let [done (await-done rt "occupied-0" (first gates))]
              (is (= :timed-out (get-in done [:result :outcome])))
              (is (nil? (get-in done [:result :value])))
              (is (= "active" (:state (weaver/show rt (first gates))))))))
        (finally
          (doseq [control (vals @controls)] (.countDown ^CountDownLatch (:release control))))))))
