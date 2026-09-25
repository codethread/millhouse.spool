(ns millhouse.executors.code-test
  "Tests for the workflow-gate to in-process Clojure executor."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [millstrand.api.weaver.alpha :as weaver]
            [millhouse.executors.code :as code]
            [millhouse.test-support :as test-support :refer [with-runtime]]
            [millhouse.workflow :as workflow]
            [millstrand.test.alpha :as test-alpha])
  (:import [java.io File]
           [java.util.concurrent CountDownLatch TimeUnit]))

(def ^:private blocker (atom (CountDownLatch. 0)))
(def ^:private worker-exited (atom (CountDownLatch. 0)))
(def ^:private interrupt-once? (atom true))
(def ^:private callback-count (atom 0))

(defn return-value
  "Return the test value supplied in `params`."
  [params]
  (:value params))

(defn count-return-value
  "Count callback invocations before returning the test value."
  [params]
  (swap! callback-count inc)
  (:value params))

(defn nil-value
  "Return nil for result-omission coverage."
  [_params]
  nil)

(defn throw-value
  "Throw a test exception carrying stable ex-data."
  [_params]
  (throw (ex-info "code test exploded" {:reason "broken"})))

(defn interrupt-once
  "Interrupt the first callback and succeed on the retry."
  [params]
  (if (compare-and-set! interrupt-once? true false)
    (throw (InterruptedException. "code callback interrupted"))
    (:value params)))

(defn non-json-value
  "Return a value that cannot be persisted as JSON."
  [_params]
  (Object.))

(defn late-value
  "Return the original value used before a test redefines this Var."
  [_params]
  "old")

(defn wait-for-release
  "Occupy a worker until the test-owned latch is released."
  [params]
  (.await ^CountDownLatch @blocker)
  (:value params))

(defn ignore-interrupt-until-release
  "Ignore interrupts and return only after the test-owned latch is released."
  [params]
  (try
    (loop []
      (if (try
            (.await ^CountDownLatch @blocker 100 TimeUnit/MILLISECONDS)
            (catch InterruptedException _
              false))
        (:value params)
        (recur)))
    (finally
      (.countDown ^CountDownLatch @worker-exited))))

(defn poll-short-subprocesses
  "Poll short-lived subprocesses until interrupted, cleaning up the active child."
  [params]
  (let [marker (File. ^String (:marker params))]
    (try
      (loop []
        (when (Thread/interrupted)
          (throw (InterruptedException. "poll interrupted")))
        (spit marker "tick\n" :append true)
        ;; `read` blocks on the pipe this function owns until the executor
        ;; interrupts waitFor; no wall-clock delay decides when the child exits.
        (let [process (.start (ProcessBuilder. ^java.util.List ["sh" "-c" "read _"]))]
          (try
            (.waitFor process)
            (catch InterruptedException interrupted
              (.destroyForcibly process)
              (.waitFor process)
              (throw interrupted))))
        (recur))
      (finally
        (.countDown ^CountDownLatch @worker-exited)))))

(defn- with-code [f]
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (test-support/activate-spool! rt :millhouse/code 'millhouse.test-modules.code-executor
                                    :after [:millhouse/workflow])
      (f rt))))

(defn- await-eventually
  ([pred] (await-eventually pred (test-support/await-budget-ms)))
  ([pred timeout-ms]
   (test-support/poll-until pred
                            {:timeout-ms timeout-ms
                             :on-timeout #(throw (ex-info "Timed out" {}))})))

(defn- attr [strand k]
  (get-in strand [:attributes k]))

(defn- single-gate [run-id gate-attrs]
  (workflow/workflow
   "Code single"
   (workflow/gate :check "Run code check" :code
                  :attributes (assoc gate-attrs "test/run-id" run-id))
   (workflow/step :after "After" :self :depends-on [:check])))

(defn- gated-gate [run-id gate-attrs]
  (workflow/workflow
   "Code gated"
   (workflow/step :first "First" :self)
   (workflow/gate :check "Run code check" :code
                  :depends-on [:first]
                  :attributes (assoc gate-attrs "test/run-id" run-id))
   (workflow/step :after "After" :self :depends-on [:check])))

(defn- idle-workflow []
  (workflow/workflow
   "Idle workflow"
   (workflow/step :wait "Wait" :self)))

(defn- request
  ([fn-name params]
   {"code/fn" fn-name "code/params" params})
  ([fn-name params timeout-secs]
   {"code/fn" fn-name
    "code/params" params
    "code/timeout-secs" timeout-secs}))

(defn- gate-strand [rt run-id]
  (first (weaver/list rt
                      [:and
                       [:= [:attr "workflow/gate"] "code"]
                       [:= [:attr "test/run-id"] run-id]]
                      {})))

(defn- ready-code-gate [run-id]
  (first (filter #(= "code" (:gate %)) (workflow/ready run-id))))

(defn- temp-file []
  (doto (File/createTempFile "code-executor-test" ".txt")
    (.deleteOnExit)))

(defn- line-count [file]
  (count (remove str/blank? (str/split-lines (slurp file)))))

(deftest pass-records-json-result-closes-gate-and-unblocks-next-step
  (with-code
    (fn [rt]
      (workflow/start! "pass"
                       (single-gate
                        "pass"
                        (request "millhouse.executors.code-test/return-value"
                                 {:value {"nested" [1 true "ok"]}}))
                       {})
      (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
      (let [gate-id (:id (gate-strand rt "pass"))
            closed (await-eventually #(let [gate (weaver/show rt gate-id)]
                                        (when (= "closed" (:state gate)) gate)))]
        (is (= "code" (attr closed :workflow/executor)))
        (is (nil? (attr closed :identity/by-identity)))
        (is (= {:nested [1 true "ok"]} (attr closed :code/result)))
        (is (nil? (attr closed :code/running)))
        (is (nil? (attr closed :gate/error)))
        (is (= "After" (:title (first (workflow/ready "pass")))))))))

(deftest nil-result-is-omitted
  (with-code
    (fn [rt]
      (workflow/start! "nil"
                       (single-gate
                        "nil"
                        (request "millhouse.executors.code-test/nil-value" {}))
                       {})
      (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
      (let [gate-id (:id (gate-strand rt "nil"))
            closed (await-eventually #(let [gate (weaver/show rt gate-id)]
                                        (when (= "closed" (:state gate)) gate)))]
        (is (nil? (attr closed :code/result)))
        (is (not (contains? (:attributes closed) :code/result)))))))

(deftest exception-and-non-json-result-stamp-errors-and-stay-ready
  (with-code
    (fn [rt]
      (doseq [[run-id fn-name expected]
              [["throw" "millhouse.executors.code-test/throw-value" "code test exploded"]
               ["json" "millhouse.executors.code-test/non-json-value" "not JSON-safe"]]]
        (workflow/start! run-id (single-gate run-id (request fn-name {})) {})
        (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
        (let [gate-id (:id (ready-code-gate run-id))
              errored (await-eventually #(let [gate (weaver/show rt gate-id)]
                                           (when (attr gate :gate/error) gate)))]
          (is (= "active" (:state errored)))
          (is (str/includes? (attr errored :gate/error) expected))
          (is (nil? (attr errored :code/result)))
          (is (nil? (attr errored :code/running)))
          (is (= gate-id (:gate (code/code-stalled? (ready-code-gate run-id)))))
          (is (some #(= gate-id (:id %))
                    (weaver/list-query rt 'stalled-code-gates {}))))))))

(deftest malformed-requests-and-unresolvable-symbols-fail-loudly
  (with-code
    (fn [rt]
      (doseq [[index [gate-attrs expected]]
              (map-indexed
               vector
               [[{"code/params" {}} "code/fn"]
                [(request "unqualified" {}) "code/fn"]
                [(request "millhouse.executors.code-test/missing" {}) "did not resolve"]
                [{"code/fn" "millhouse.executors.code-test/return-value"} "code/params"]
                [(request "millhouse.executors.code-test/return-value" []) "code/params"]
                [(request "millhouse.executors.code-test/return-value" {} 0)
                 "code/timeout-secs"]])]
        (let [run-id (str "invalid-" index)]
          (workflow/start! run-id (single-gate run-id gate-attrs) {})
          (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
          (let [gate-id (:id (ready-code-gate run-id))
                errored (await-eventually #(let [gate (weaver/show rt gate-id)]
                                             (when (attr gate :gate/error) gate)))]
            (is (str/includes? (attr errored :gate/error) expected)
                (str "case " index))
            (is (nil? (attr errored :code/result)))))))))

(deftest function-var-is-resolved-when-the-poured-gate-executes
  (with-code
    (fn [rt]
      (workflow/start! "late"
                       (gated-gate
                        "late"
                        (request "millhouse.executors.code-test/late-value" {}))
                       {})
      (let [first-step (first (workflow/ready "late"))]
        (with-redefs [late-value (fn [_params] "new")]
          (workflow/complete! "late" {:step (:id first-step)})
          (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
          (let [gate-id (:id (gate-strand rt "late"))
                closed (await-eventually #(let [gate (weaver/show rt gate-id)]
                                            (when (= "closed" (:state gate)) gate)))]
            (is (= "new" (attr closed :code/result)))))))))

(deftest ready-code-gate-dispatches-callback-once
  (with-code
    (fn [rt]
      (reset! callback-count 0)
      (workflow/start!
       "once"
       (single-gate
        "once"
        (request "millhouse.executors.code-test/count-return-value"
                 {:value "once"}))
       {})
      (let [closed (await-eventually
                    #(let [gate (gate-strand rt "once")]
                       (when (= "closed" (:state gate)) gate)))]
        (is (= "once" (attr closed :code/result)))
        (is (= 1 @callback-count))))))

(deftest nested-workflow-root-keeps-its-own-run-id
  (with-runtime
    (fn [rt _]
      (reset! callback-count 0)
      (test-support/activate-spool! rt :millhouse/workflow
                                    'millhouse.workflow)
      (workflow/start! "outer" (idle-workflow) {})
      (workflow/start!
       "inner"
       (gated-gate
        "inner"
        (request "millhouse.executors.code-test/return-value"
                 {:value "inner"}))
       {})
      (weaver/update! rt (:id (workflow/current-root "outer"))
                      {:edges [{:type "parent-of"
                                :to (:id (workflow/current-root "inner"))}]})
      (test-support/activate-spool! rt :millhouse/code
                                    'millhouse.test-modules.code-executor
                                    :after [:millhouse/workflow])
      (let [gate-id (:id (gate-strand rt "inner"))]
        (workflow/complete! "inner")
        (let [closed (await-eventually
                      #(let [gate (weaver/show rt gate-id)]
                         (when (= "closed" (:state gate)) gate)))]
          (is (= "inner" (attr closed :code/result)))
          (is (= "active" (:state (workflow/current-root "outer")))))))))

(defn- nested-ready-code-gate! [rt]
  (workflow/start! "outer" (idle-workflow) {})
  (workflow/start!
   "inner"
   (gated-gate
    "inner"
    (request "millhouse.executors.code-test/count-return-value"
             {:value "inner"}))
   {})
  (let [outer-root (workflow/current-root "outer")
        inner-root (workflow/current-root "inner")]
    (weaver/update! rt (:id outer-root)
                    {:edges [{:type "parent-of" :to (:id inner-root)}]})
    (workflow/complete! "inner")
    (let [gate (ready-code-gate "inner")]
      (is (= "active" (:state gate)))
      (is (= (:id gate) (:id (first (workflow/ready "inner")))))
      {:gate-id (:id gate)
       :inner-root-id (:id inner-root)
       :outer-root-id (:id outer-root)})))

(deftest closed-nested-workflow-root-does-not-release-code-gate-to-outer-run
  (with-runtime
    (fn [rt _]
      (reset! callback-count 0)
      (test-support/activate-spool! rt :millhouse/workflow
                                    'millhouse.workflow)
      (let [{:keys [gate-id inner-root-id outer-root-id]}
            (nested-ready-code-gate! rt)]
        (weaver/update! rt inner-root-id {:state "closed"})
        (test-support/activate-spool! rt :millhouse/code
                                      'millhouse.test-modules.code-executor
                                      :after [:millhouse/workflow])
        (code/on-event {})
        (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
        (is (= "closed" (:state (weaver/show rt inner-root-id))))
        (is (= "active" (:state (weaver/show rt outer-root-id))))
        (is (= "active" (:state (weaver/show rt gate-id))))
        (is (nil? (attr (weaver/show rt gate-id) :code/running)))
        (is (some #(= gate-id (:id %)) (weaver/ready rt)))
        (is (zero? @callback-count))))))

(deftest superseded-nested-workflow-root-does-not-release-code-gate-to-outer-run
  (with-runtime
    (fn [rt _]
      (reset! callback-count 0)
      (test-support/activate-spool! rt :millhouse/workflow
                                    'millhouse.workflow)
      (let [{:keys [gate-id inner-root-id outer-root-id]}
            (nested-ready-code-gate! rt)
            replacement (weaver/add! rt {:title "Inner replacement"
                                         :attributes {"workflow/role" "root"
                                                      "workflow/run-id" "inner"}})]
        (weaver/update! rt outer-root-id
                        {:edges [{:type "parent-of" :to (:id replacement)}]})
        (weaver/supersede! rt inner-root-id (:id replacement))
        (test-support/activate-spool! rt :millhouse/code
                                      'millhouse.test-modules.code-executor
                                      :after [:millhouse/workflow])
        (code/on-event {})
        (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
        (is (= "replaced" (:state (weaver/show rt inner-root-id))))
        (is (= "active" (:state (workflow/current-root "inner"))))
        (is (= "active" (:state (weaver/show rt outer-root-id))))
        (is (= "active" (:state (weaver/show rt gate-id))))
        (is (nil? (attr (weaver/show rt gate-id) :code/running)))
        (is (some #(= gate-id (:id %)) (weaver/ready rt)))
        (is (zero? @callback-count))))))

(deftest blocked-errored-and-orphaned-gates-are-not-dispatched
  (with-runtime
    (fn [rt _]
      (reset! callback-count 0)
      (test-support/activate-spool! rt :millhouse/workflow
                                    'millhouse.workflow)
      (workflow/start!
       "blocked"
       (gated-gate
        "blocked"
        (request "millhouse.executors.code-test/count-return-value"
                 {:value "blocked"}))
       {})
      (workflow/start!
       "errored"
       (gated-gate
        "errored"
        (request "millhouse.executors.code-test/count-return-value"
                 {:value "errored"}))
       {})
      (let [blocked-id (:id (gate-strand rt "blocked"))]
        (workflow/complete! "errored")
        (let [errored-id (:id (ready-code-gate "errored"))
              orphan (weaver/add! rt {:title "Orphan code gate"
                                      :state "active"
                                      :attributes
                                      {"workflow/gate" "code"
                                       "code/fn" "millhouse.executors.code-test/count-return-value"
                                       "code/params" {:value "orphan"}}})]
          (weaver/update! rt errored-id
                          {:attributes {"gate/error" "prior failure"}})
          (test-support/activate-spool! rt :millhouse/code
                                        'millhouse.test-modules.code-executor
                                        :after [:millhouse/workflow])
          (code/on-event {})
          (is (zero? @callback-count))
          (is (= "active" (:state (weaver/show rt (:id orphan)))))
          (is (nil? (attr (weaver/show rt errored-id) :code/running)))
          (is (= "active"
                 (:state (weaver/show rt blocked-id)))))))))

(deftest scan-uses-one-filtered-ready-query-without-per-root-scans
  (with-code
    (fn [rt]
      (doseq [run-id ["idle-1" "idle-2" "idle-3"]]
        (workflow/start! run-id (idle-workflow) {}))
      (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
      (let [ready-calls (atom [])
            real-ready weaver/ready
            fail-per-root (fn [& _]
                            (throw (ex-info "per-root scan should not run" {})))
            fail-mutation (fn [& _]
                            (throw (ex-info "irrelevant scan must not mutate" {})))]
        (with-redefs [weaver/ready (fn [runtime query params]
                                     (swap! ready-calls conj [query params])
                                     (real-ready runtime query params))
                      workflow/active-runs fail-per-root
                      workflow/ready fail-per-root
                      weaver/update! fail-mutation]
          (is (= {:scanned true} (code/on-event {}))))
        (is (= 1 (count @ready-calls)))
        (is (= [:= [:attr "workflow/gate"] "code"]
               (ffirst @ready-calls)))
        (is (= {} (second (first @ready-calls))))))))

(deftest saturated-pool-does-not-queue-or-claim-extra-gates
  (with-code
    (fn [rt]
      (reset! blocker (CountDownLatch. 1))
      (reset! worker-exited (CountDownLatch. 1))
      (let [run-id "saturation"
            gates (mapv (fn [index]
                          (workflow/gate
                           (keyword (str "gate-" index))
                           (str "Gate " index)
                           :code
                           :attributes
                           (assoc (request
                                   "millhouse.executors.code-test/wait-for-release"
                                   {:value index})
                                  "test/run-id" run-id)))
                        (range 9))]
        (try
          (workflow/start! run-id (apply workflow/workflow "Saturation" gates) {})
          (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
          (let [all-gates #(weaver/list rt
                                        [:and
                                         [:= [:attr "workflow/gate"] "code"]
                                         [:= [:attr "test/run-id"] run-id]]
                                        {})]
            (await-eventually
             #(when (= 8 (count (filter (fn [gate]
                                          (some? (attr gate :code/running)))
                                        (all-gates))))
                true))
            (is (= 1 (count (filter (fn [gate]
                                      (nil? (attr gate :code/running)))
                                    (all-gates)))))
            (is (zero? (.size (.getQueue ^java.util.concurrent.ThreadPoolExecutor
                               (:worker-executor
                                (with-bindings {#'code/*runtime* rt}
                                  (#'code/resources)))))))
            (.countDown ^CountDownLatch @blocker)
            (await-eventually #(when (every? (fn [gate] (= "closed" (:state gate)))
                                             (all-gates))
                                 true)))
          (finally
            (.countDown ^CountDownLatch @blocker)))))))

(deftest timeout-abandons-stubborn-thread-without-late-write-or-lost-capacity
  (with-code
    (fn [rt]
      (reset! blocker (CountDownLatch. 1))
      (try
        (workflow/start!
         "stubborn"
         (single-gate
          "stubborn"
          (request "millhouse.executors.code-test/ignore-interrupt-until-release"
                   {:value "late"}
                   1))
         {})
        (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
        (let [gate-id (:id (ready-code-gate "stubborn"))
              timed-out (await-eventually #(let [gate (weaver/show rt gate-id)]
                                             (when (attr gate :gate/error) gate)))]
          (is (str/includes? (attr timed-out :gate/error) "timed out"))
          (is (nil? (attr timed-out :code/running)))
          (workflow/start!
           "fresh"
           (single-gate
            "fresh"
            (request "millhouse.executors.code-test/return-value"
                     {:value "fresh"}))
           {})
          (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
          (let [fresh-id (:id (gate-strand rt "fresh"))
                fresh (await-eventually #(let [gate (weaver/show rt fresh-id)]
                                           (when (= "closed" (:state gate)) gate)))]
            (is (= "fresh" (attr fresh :code/result))))
          (.countDown ^CountDownLatch @blocker)
          (is (.await ^CountDownLatch @worker-exited
                      (test-support/await-budget-ms)
                      TimeUnit/MILLISECONDS))
          (let [after-late-return (weaver/show rt gate-id)]
            (is (= "active" (:state after-late-return)))
            (is (str/includes? (attr after-late-return :gate/error) "timed out"))
            (is (nil? (attr after-late-return :code/result)))))
        (finally
          (.countDown ^CountDownLatch @blocker))))))

(deftest timeout-stops-cooperative-subprocess-poll-with-no-late-completion
  (with-code
    (fn [rt]
      (reset! worker-exited (CountDownLatch. 1))
      (let [marker (temp-file)]
        (workflow/start!
         "poll"
         (single-gate
          "poll"
          (request "millhouse.executors.code-test/poll-short-subprocesses"
                   {:marker (.getPath marker)}
                   1))
         {})
        (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
        (let [gate-id (:id (ready-code-gate "poll"))
              timed-out (await-eventually #(let [gate (weaver/show rt gate-id)]
                                             (when (attr gate :gate/error) gate)))
              count-at-timeout (line-count marker)]
          (is (str/includes? (attr timed-out :gate/error) "timed out"))
          (is (pos? count-at-timeout))
          (is (.await ^CountDownLatch @worker-exited
                      (test-support/await-budget-ms)
                      TimeUnit/MILLISECONDS))
          (is (= count-at-timeout (line-count marker)))
          (let [after-wait (weaver/show rt gate-id)]
            (is (= "active" (:state after-wait)))
            (is (nil? (attr after-wait :code/result)))))))))

(deftest interrupted-callback-clears-claim-and-is-retryable
  (with-code
    (fn [rt]
      (reset! interrupt-once? true)
      (workflow/start!
       "interrupted"
       (single-gate
        "interrupted"
        (request "millhouse.executors.code-test/interrupt-once"
                 {:value "retried"}))
       {})
      (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
      (let [gate-id (-> (gate-strand rt "interrupted") :id)
            retried (await-eventually
                     #(let [gate (weaver/show rt gate-id)]
                        (when (= "closed" (:state gate)) gate)))]
        (is (= "retried" (attr retried :code/result)))
        (is (nil? (attr retried :gate/error)))
        (is (nil? (attr retried :code/running)))))))

(deftest state-shape-matches-declared-version
  (test-support/assert-state-shape
   #'code/new-state
   #{:scan-monitor :resources :close-fn}))
