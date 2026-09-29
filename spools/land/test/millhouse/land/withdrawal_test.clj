(ns millhouse.land.withdrawal-test
  "Deterministic lock ordering and conditional abandonment in file-backed worlds."
  (:require [clojure.test :refer [deftest is]]
            [millhouse.land.merge-queue :as queue]
            [millhouse.executors.shell :as shell]
            [clojure.java.io :as io]
            [millstrand.api.process.alpha :as process]
            [millhouse.workflow :as workflow]
            [millhouse.workflow.execution :as execution]
            [millhouse.test-support :as support]
            [millstrand.api.batch.alpha :as batch]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.weaver.alpha :as weaver]))

(defn- start! [rt]
  (support/activate-spool! rt :workflow 'millhouse.workflow)
  (queue/open-completion-guard! {:runtime rt})
  (workflow/start! "race"
                   (workflow/workflow "Withdrawal race" {:attributes {"workflow/family" "land"}}
                                      (workflow/gate :turn "Turn" :merge-turn)
                                      (workflow/step :work "Work" :self :depends-on [:turn]))
                   {:branch "race"})
  (queue/join! rt "race"))

(deftest winning-freeze-excludes-grant-and-both-calls-terminate
  (support/with-runtime
    (fn [rt _]
      (let [entry (start! rt)
            selected (promise)
            frozen (promise)
            apply-batch batch/apply!
            quiesce execution/quiesce-run!]
        (with-redefs [batch/apply!
                      (fn [runtime payload & args]
                        (when (some #(= "merge-lock" (attr-get % :kind)) (:strands payload))
                          (deliver selected true)
                          (when-not (deref frozen 10000 false)
                            (throw (ex-info "Freeze did not complete while grant held queue lock" {}))))
                        (apply apply-batch runtime payload args))
                      execution/quiesce-run!
                      (fn [& args]
                        (let [receipt (apply quiesce args)]
                          (deliver frozen true) receipt))]
          (let [grant (future (try (queue/grant! rt "race") :granted
                                   (catch clojure.lang.ExceptionInfo _ :refused)))]
            (try
              (is (true? (deref selected 10000 false)))
              (let [withdraw (future (queue/withdraw! rt (:id entry) "Withdraw" "fixture-owner"))]
                (is (= :refused (deref grant 10000 :timeout)))
                (is (= "withdrawn" (:outcome (deref withdraw 10000 {:outcome :timeout})))))
              (finally (deliver frozen true)))))
        (is (nil? (:lock (queue/status rt))))
        (is (= "Pause unfinished work" (:title (first (workflow/ready "race")))))))))

(deftest stale-reservation-cutover-writes-no-partial-release
  (support/with-runtime
    (fn [rt _]
      (let [entry (start! rt)
            _ (queue/grant! rt "race")
            root (workflow/current-root "race")
            lock (:lock (queue/status rt))
            apply-batch batch/apply!
            injected (atom false)]
        (with-redefs [batch/apply!
                      (fn [runtime payload & args]
                        (when (and (some #(= "withdrawn" (attr-get % :queue/outcome)) (:strands payload))
                                   (compare-and-set! injected false true))
                          (weaver/update! runtime (:id entry) {:title "Concurrent reservation metadata"}))
                        (apply apply-batch runtime payload args))]
          (is (thrown? clojure.lang.ExceptionInfo
                       (queue/withdraw! rt (:id entry) "Withdraw" "fixture-owner"))))
        (is @injected)
        (is (= "active" (:state (weaver/show rt (:id entry)))))
        (is (= (:id root) (:id (workflow/current-root "race"))))
        (is (= lock (:lock (queue/status rt))))
        (is (some? (:freeze (execution/run-view rt "race"))))
        (is (thrown? clojure.lang.ExceptionInfo (queue/grant! rt "race")))
        (is (= "withdrawn" (:outcome (queue/withdraw! rt (:id entry) "Withdraw" "fixture-owner"))))))))

(deftest locally-stopped-irreversible-command-still-refuses-withdrawal
  ;; Simulated custody proves the domain mapping, not process-tree cancellation.
  (support/with-runtime
    (fn [rt directory]
      (support/activate-spool! rt :workflow 'millhouse.workflow)
      (queue/open-completion-guard! {:runtime rt})
      (let [output (io/file directory "output")
            records (atom {})]
        (spit output "")
        (with-redefs [process/launch! (fn [_ owner key _]
                                        (let [record {:owner owner :key key :handle key :phase :running
                                                      :output {:stdout-ref (str output) :stderr-ref (str output)}}]
                                          (swap! records assoc key record) record))
                      process/list-owned (fn [& _] (vec (vals @records)))
                      process/cancel! (fn [_ _ key]
                                        (swap! records update key assoc :phase :terminal
                                               :cancellation {:reason "Stopped locally"}))
                      process/acknowledge! (fn [_ _ key] (swap! records dissoc key))]
          (let [handle (execution/open! rt shell/executor)]
            (try
              (workflow/start! "irreversible"
                               (workflow/workflow "May have submitted" {:attributes {"workflow/family" "land"}}
                                                  (workflow/gate :turn "Turn" :merge-turn)
                                                  (workflow/gate :merge "Merge" :shell :depends-on [:turn]
                                                                 :attributes {"shell/argv" ["submit"] "land/irreversible" true}))
                               {:branch "irreversible"})
              (let [entry (queue/join! rt "irreversible")]
                (queue/grant! rt "irreversible")
                (let [gate-id (:id (first (workflow/ready "irreversible")))
                      selector {:run-id "irreversible" :step gate-id}]
                  (support/poll-until #(:accepted? (execution/inspect rt selector)) {:timeout-ms 10000})
                  (let [freeze (execution/quiesce-run! rt "irreversible" "Stop")
                        receipt (support/poll-until #(let [r (execution/retire! rt freeze)]
                                                       (when (= :settled (:status r)) r)) {:timeout-ms 10000})]
                    (is (true? (:may-have-started? (first (:attempts receipt)))))
                    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"may already have been submitted"
                                          (queue/withdraw! rt (:id entry) "Abandon" "fixture-owner")))
                    (is (:holds-lock (queue/status rt (:id entry))))
                    (is (some? (:freeze (execution/run-view rt "irreversible")))))))
              (finally (execution/close! rt handle)))))))))
