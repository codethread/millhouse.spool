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

(workflow/defworkflow abort-fixture
  "Compile repository-owned abort bookkeeping for withdrawal tests."
  {:entrypoints #{:continue}}
  (workflow/workflow
   "Abort fixture"
   {:attributes {"workflow/family" "land"
                 "land/stage" "abort"
                 "land/abort-reason" (fn [{:keys [reason]}] reason)}}
   (workflow/step :record "Pause unfinished work" :self)))

(def ^:private landing-attributes
  "Root attributes required by repository-owned landing fixtures."
  {"workflow/family" "land"
   "land/abort-definition" "millhouse.land.withdrawal-test/abort-fixture"})

(defn- start! [rt]
  (support/activate-spool! rt :workflow 'millhouse.workflow)
  (queue/open-completion-guard! {:runtime rt})
  (workflow/start! "race"
                   (workflow/workflow "Withdrawal race" {:attributes landing-attributes}
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

(defn- with-simulated-custody [rt directory f]
  ;; Simulated custody proves domain translation, not process-tree cancellation.
  (let [output (io/file directory "output")
        records (atom {})]
    (spit output "")
    (with-redefs [process/launch! (fn [_ owner key {:keys [argv]}]
                                    (let [failed? (= ["fail"] argv)
                                          record (cond-> {:owner owner :key key :handle key
                                                          :phase (if failed? :terminal :running)
                                                          :output {:stdout-ref (str output) :stderr-ref (str output)}}
                                                   failed? (assoc :exit {:code 1}))]
                                      (swap! records assoc key record) record))
                  process/list-owned (fn [& _] (vec (vals @records)))
                  process/cancel! (fn [_ _ key]
                                    (swap! records update key assoc :phase :terminal
                                           :cancellation {:reason "Stopped locally"}))
                  process/acknowledge! (fn [_ _ key] (swap! records dissoc key))]
      (let [handle (execution/open! rt shell/executor)]
        (try (f) (finally (execution/close! rt handle)))))))

(deftest live-withdrawal-settles-but-irreversible-work-still-refuses
  (doseq [irreversible? [false true]]
    (support/with-runtime
      (fn [rt directory]
        (support/activate-spool! rt :workflow 'millhouse.workflow)
        (queue/open-completion-guard! {:runtime rt})
        (with-simulated-custody
          rt directory
          (fn []
            (workflow/start! "live"
                             (workflow/workflow "May have submitted" {:attributes landing-attributes}
                                                (workflow/gate :turn "Turn" :merge-turn)
                                                (workflow/gate :merge "Merge" :shell :depends-on [:turn]
                                                               :attributes {"shell/argv" ["submit"] "land/irreversible" irreversible?}))
                             {:branch "live"})
            (let [entry (queue/join! rt "live")]
              (queue/grant! rt "live")
              (let [selector {:run-id "live" :step (:id (first (workflow/ready "live")))}]
                (support/poll-until #(:accepted? (execution/inspect rt selector)) {:timeout-ms 10000})
                (if-not irreversible?
                  (do (is (= "withdrawn" (:outcome (queue/withdraw! rt (:id entry) "Abandon" "fixture-owner"))))
                      (is (= "Pause unfinished work" (:title (first (workflow/ready "live"))))))
                  (let [freeze (execution/quiesce-run! rt "live" "Stop")
                        receipt (support/poll-until #(let [r (execution/retire! rt freeze)]
                                                       (when (= :settled (:status r)) r)) {:timeout-ms 10000})]
                    (is (true? (:may-have-started? (first (:attempts receipt)))))
                    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"may already have been submitted"
                                          (queue/withdraw! rt (:id entry) "Abandon" "fixture-owner")))
                    (is (:holds-lock (queue/status rt (:id entry))))
                    (is (some? (:freeze (execution/run-view rt "live"))))))))))))))

(deftest preparation-repair-retains-reservation-and-uses-an-exact-new-attempt
  (support/with-runtime
    (fn [rt directory]
      (support/activate-spool! rt :workflow 'millhouse.workflow)
      (queue/open-completion-guard! {:runtime rt})
      (with-simulated-custody
        rt directory
        (fn []
          (workflow/start! "repair"
                           (workflow/workflow "Repair preparation" {:attributes landing-attributes}
                                              (workflow/gate :turn "Turn" :merge-turn)
                                              (workflow/gate :prepare "Prepare merge" :shell :depends-on [:turn]
                                                             :attributes {"shell/argv" ["fail"]}))
                           {:branch "repair"})
          (let [entry (queue/join! rt "repair")]
            (queue/grant! rt "repair")
            (let [gate-id (:id (first (workflow/ready "repair")))
                  selector {:run-id "repair" :step gate-id}
                  prior (support/poll-until #(let [v (execution/inspect rt selector)] (when (:result v) v))
                                            {:timeout-ms 10000})
                  reservation (select-keys (queue/status rt (:id entry)) [:id :sequence :holds-lock])
                  request {:kind :preparation :reason "Repair" :by-identity "fixture-owner"
                           :evidence {:root-id (:id (workflow/current-root "repair")) :gate-id gate-id
                                      :expected-attempt (:attempt-id prior) :request-id "repair"}}]
              (is (= :failed (get-in prior [:result :outcome])))
              (is (thrown? clojure.lang.ExceptionInfo
                           (queue/repair! rt "repair" (assoc request :kind :skipped-turn))))
              (is (thrown? clojure.lang.ExceptionInfo
                           (queue/repair! rt "repair" (assoc-in request [:evidence :expected-attempt] "stale"))))
              (is (= (:attempt-id prior) (:attempt-id (execution/inspect rt selector))))
              (is (some? (:freeze (execution/run-view rt "repair"))))
              (let [result (queue/repair! rt "repair" request)
                    fresh-attempt (get-in result [:action :attempt-id])]
                (is (= :accepted (:status result)))
                (is (not= (:attempt-id prior) fresh-attempt))
                (is (= fresh-attempt (:attempt-id (execution/inspect rt selector))))
                (is (= :failed
                       (get-in
                        (support/poll-until
                         #(let [view (execution/inspect rt selector)]
                            (when (and (= fresh-attempt (:attempt-id view))
                                       (:result view))
                              view))
                         {:timeout-ms 10000})
                        [:result :outcome]))))
              (is (= reservation (select-keys (queue/status rt (:id entry)) [:id :sequence :holds-lock])))
              (is (nil? (:freeze (execution/run-view rt "repair")))))))))))

(deftest queue-row-ownership-rejects-unscoped-writes
  (support/with-runtime
    (fn [rt _]
      (let [entry (start! rt)]
        (queue/grant! rt "race")
        (doseq [id [(:id entry) (get-in (queue/status rt) [:lock :id])]
                patch [{:state "closed"} {:attributes {"land/run-id" "forged"}}
                       {:attributes {"queue/entry" "forged"}} {:attributes {"kind" nil}}]]
          (is (thrown? clojure.lang.ExceptionInfo (weaver/update! rt id patch))))
        (doseq [kind ["merge-queue-entry" "merge-lock"]]
          (is (thrown? clojure.lang.ExceptionInfo (weaver/add! rt {:title "Forged" :attributes {"kind" kind}}))))
        (weaver/update! rt (:id entry) {:title "Allowed annotation"})
        (is (= "Allowed annotation" (:title (weaver/show rt (:id entry)))))))))
