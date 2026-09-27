(ns millhouse.executors.shell-test
  "Tests for the workflow-gate to shell-command executor."
  (:require [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is use-fixtures]]
            [millhouse.executors.shell :as shell]
            [millhouse.workflow :as workflow]
            [millhouse.workflow.validation :as validation]
            [millhouse.test-support :as test-support :refer [with-embedded-runtime]]
            [millstrand.api.events.alpha :as events]
            [millstrand.api.batch.alpha :as batch]
            [millstrand.api.process.alpha :as process]
            [millstrand.api.scheduler.alpha :as scheduler]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.test.alpha :as test-alpha])
  (:import [java.io File]
           [java.util.concurrent Executors]))

;; Proof tiers stay in one namespace so the runner's existing serial isolation
;; still covers the process API redefinitions. Pure request/output checks need
;; no runtime. Synchronous DB contracts explicitly opt into memory storage;
;; observers, event dispatch and validation retain file-backed worlds. There is
;; no supported bare-runtime constructor in the pinned downstream API.

(def ^:dynamic ^:private *temp-root* nil)

(use-fixtures :each
  (fn [f]
    (let [root (test-support/temp-dir "shell-test-")]
      (try
        (binding [*temp-root* root] (f))
        (finally (test-support/delete-tree! root))))))

(defn- temp-file [suffix]
  (File/createTempFile "shell-test" suffix *temp-root*))

(defn- with-shell-world [f]
  ;; Embedded worlds have no Mill control channel. This fake runs short commands
  ;; but simulates long-running custody and cancellation; it does not prove
  ;; process-tree termination. The built-Mill test below owns replacement proof.
  ;; Keep file storage for the event/worker threads' connection topology.
  (let [records (atom {})
        ;; Executor threads do not convey the test's dynamic bindings.
        output-root *temp-root*
        run-command (fn [argv cwd]
                      (let [builder (doto (ProcessBuilder. ^java.util.List argv)
                                      (.redirectErrorStream true)
                                      (.directory (io/file cwd)))
                            child (.start builder)]
                        (.close (.getOutputStream child))
                        (let [output (slurp (.getInputStream child))
                              exit (.waitFor child)]
                          {:output output :exit exit})))
        launch (fn [_runtime _owner key {:keys [argv cwd]}]
                 (let [handle (str "test-custody-" key)
                       long-running? (some #(str/includes? % "sleep 30") argv)
                       {:keys [exit output]} (when-not long-running?
                                               (run-command argv cwd))
                       output-file (File/createTempFile "shell-custody" ".stdout" output-root)
                       error-file (File/createTempFile "shell-custody" ".stderr" output-root)
                       record (cond-> {:handle handle :owner :millhouse/shell-executor :key key
                                       :phase (if long-running? :running :terminal)
                                       :output {:stdout-ref (.getAbsolutePath output-file)
                                                :stderr-ref (.getAbsolutePath error-file)}}
                                (not long-running?) (assoc :exit {:code exit :signal nil}))]
                   (spit output-file (or output ""))
                   (swap! records assoc handle record)
                   record))
        get-record (fn [_runtime handle] (get @records handle))
        list-owned (fn [_runtime _owner] (vec (vals @records)))
        cancel (fn [_runtime _owner handle]
                 (let [record (assoc (get @records handle)
                                     :phase :terminal
                                     :cancellation {:reason "cancelled by owner"})]
                   (swap! records assoc handle record)
                   record))
        acknowledge (fn [_runtime _owner handle]
                      (swap! records dissoc handle)
                      {:acknowledged true :handle handle})]
    (with-redefs-fn {#'process/launch! launch
                     #'process/get get-record
                     #'process/list-owned list-owned
                     #'process/cancel! cancel
                     #'process/acknowledge! acknowledge}
      (fn []
        (with-embedded-runtime
          (fn [rt _]
            (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
            (test-support/activate-spool! rt :millhouse/shell 'millhouse.test-modules.shell-executor
                                          :after [:millhouse/workflow])
            (f rt)))))))

(defn- await-eventually
  "Await off-lane executor completion, which event quiescence cannot observe.

  Custody is simulated in embedded worlds, real in the built-Mill test."
  ([pred] (await-eventually pred (test-support/await-budget-ms)))
  ([pred timeout-ms]
   (test-support/poll-until pred
                            {:timeout-ms timeout-ms
                             :on-timeout #(throw (ex-info "Timed out" {}))})))

(defn- attr [strand k]
  (let [value (get-in strand [:attributes k])]
    (if (= "validation" (namespace k)) (validation/wire-data value) value)))

(defn- single-gate
  "A run whose first ready step is a `:shell` gate, followed by a dependent step."
  [run-id gate-attrs]
  (workflow/workflow
   "Shell single"
   (workflow/gate :check "Run shell check" :shell :attributes (assoc gate-attrs "test/run-id" run-id))
   (workflow/step :after "After" :self :depends-on [:check])))

(defn- gated-gate
  "A `:self` step feeding a dependent `:shell` gate, then a trailing step."
  [run-id gate-attrs]
  (workflow/workflow
   "Shell gated"
   (workflow/step :first "First" :self)
   (workflow/gate :check "Run shell check" :shell :depends-on [:first] :attributes (assoc gate-attrs "test/run-id" run-id))
   (workflow/step :after "After" :self :depends-on [:check])))

(defn- two-shell-gates
  "Two active shell gates, with only the first one on the ready frontier."
  [run-id gate-attrs]
  (workflow/workflow
   "Shell withdrawal"
   (workflow/gate :first "First shell" :shell
                  :attributes (assoc gate-attrs "test/run-id" run-id))
   (workflow/gate :second "Second shell" :shell :depends-on [:first]
                  :attributes (assoc gate-attrs "test/run-id" run-id))))

(defn- idle-workflow []
  (workflow/workflow
   "Idle workflow"
   (workflow/step :wait "Wait" :self)))

(defn- ready-shell-gate [run-id]
  (first (filter #(= "shell" (:gate %)) (workflow/ready run-id))))

(defn- shell-gate-strand [rt run-id]
  (first (weaver/list rt [:and [:= [:attr "workflow/gate"] "shell"]
                          [:= [:attr "test/run-id"] run-id]]
                      {})))

(deftest quiesce-freezes-all-active-shell-gates-in-the-run
  (with-shell-world
    (fn [rt]
      (workflow/start! "quiesce-all"
                       (two-shell-gates "quiesce-all" {"gate/error" "held"})
                       {})
      (let [result (shell/quiesce-run! "quiesce-all" "withdrawn")
            gates (weaver/list rt [:and [:= [:attr "workflow/gate"] "shell"]
                                   [:= [:attr "test/run-id"] "quiesce-all"]] {})]
        (is (= "quiesce-all" (:run-id result)))
        (is (= 2 (count (:gates result))))
        (is (= #{"withdrawn"} (set (map #(attr % :gate/error) gates))))
        (is (every? (comp false? :attempted?) (:gates result)))))))

(deftest quiesce-cancels-a-running-owned-attempt
  (with-shell-world
    (fn [rt]
      (workflow/start! "quiesce-running"
                       (single-gate "quiesce-running"
                                    {"gate/error" "held"
                                     "shell/argv" ["sh" "-c" "sleep 30"]})
                       {})
      (let [gate (shell-gate-strand rt "quiesce-running")
            attempt-id "quiesce-running-attempt"
            record (process/launch! rt :millhouse/shell-executor attempt-id
                                    {:argv ["sh" "-c" "sleep 30"]
                                     :cwd "." :env {}})]
        (weaver/update! rt (:id gate)
                        {:attributes {"gate/error" nil
                                      "shell/running" attempt-id
                                      "shell/attempt-id" attempt-id
                                      "shell/custody-handle" (:handle record)}})
        (let [cancel process/cancel!
              result (with-redefs [process/cancel! (fn [& args]
                                                     (apply cancel args)
                                                     record)]
                       (shell/quiesce-run! "quiesce-running" "withdrawn"))
              after (weaver/show rt (:id gate))]
          (is (true? (:attempted? (first (:gates result)))))
          (is (= "withdrawn" (attr after :gate/error)))
          (is (= :terminal (:phase (process/get rt (:handle record))))))))))

(deftest quiesce-rejects-uncertain-cancellation-and-retains-freeze
  (with-shell-world
    (fn [rt]
      (workflow/start! "quiesce-uncertain"
                       (single-gate "quiesce-uncertain"
                                    {"gate/error" "held"
                                     "shell/argv" ["sh" "-c" "sleep 30"]})
                       {})
      (let [gate (shell-gate-strand rt "quiesce-uncertain")
            attempt-id "quiesce-uncertain-attempt"
            record (process/launch! rt :millhouse/shell-executor attempt-id
                                    {:argv ["sh" "-c" "sleep 30"]
                                     :cwd "." :env {}})]
        (weaver/update! rt (:id gate)
                        {:attributes {"gate/error" nil
                                      "shell/running" attempt-id
                                      "shell/attempt-id" attempt-id
                                      "shell/custody-handle" (:handle record)}})
        (let [uncertain (assoc record :phase :terminal
                               :cancellation {:reason "signal failed" :stop :uncertain})
              acknowledgements (atom [])]
          (with-redefs [process/cancel! (fn [& _] uncertain)
                        process/get (fn [& _] uncertain)
                        process/acknowledge! (fn [& args] (swap! acknowledgements conj args))]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cancellation is uncertain"
                                  (shell/quiesce-run! "quiesce-uncertain" "withdrawn")))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cancellation is uncertain"
                                  (#'shell/terminal-reconcile!
                                   rt "quiesce-uncertain" (:id gate) attempt-id
                                   (:handle record) uncertain false)))
            (is (empty? @acknowledgements))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cancellation is uncertain"
                                  (shell/quiesce-run! "quiesce-uncertain" "withdrawn")))))
        (let [after (weaver/show rt (:id gate))]
          (is (= "withdrawn" (attr after :gate/error)))
          (is (= attempt-id (attr after :shell/attempt-id)))
          (is (= (:handle record) (attr after :shell/custody-handle))))))))

(deftest claimed-attempt-cannot-launch-after-quiesce
  (with-shell-world
    (fn [rt]
      (workflow/start! "quiesce-claimed"
                       (single-gate "quiesce-claimed"
                                    {"gate/error" "held"
                                     "shell/argv" ["true"]})
                       {})
      (let [gate (shell-gate-strand rt "quiesce-claimed")
            attempt-id "quiesce-claimed-attempt"]
        (weaver/update! rt (:id gate)
                        {:attributes {"gate/error" nil
                                      "shell/running" attempt-id
                                      "shell/attempt-id" attempt-id}})
        (let [result (shell/quiesce-run! "quiesce-claimed" "withdrawn")]
          (is (false? (:attempted? (first (:gates result))))))
        (#'shell/run-gate! rt "quiesce-claimed" (:id gate) attempt-id)
        (is (nil? (attr (weaver/show rt (:id gate)) :shell/custody-handle)))
        (is (nil? (attr (weaver/show rt (:id gate)) :shell/attempt-id)))
        (is (= [] (process/list-owned rt :millhouse/shell-executor)))
        (weaver/update! rt (:id gate) {:attributes {"gate/error" nil}})
        (shell/scan!)
        (await-eventually #(= "closed" (:state (weaver/show rt (:id gate)))))
        (is (= "After" (:title (first (workflow/ready "quiesce-claimed")))))))))

(deftest retained-custody-output-keeps-the-combined-tail-bound
  (let [stdout (temp-file ".stdout")
        stderr (temp-file ".stderr")]
    (spit stdout (str/join (repeat 12000 "o")))
    (spit stderr (str/join (repeat 12000 "e")))
    (let [output (#'shell/custody-output
                  {:stdout-ref (.getAbsolutePath stdout)
                   :stderr-ref (.getAbsolutePath stderr)})]
      (is (<= (alength (.getBytes output "UTF-8")) @#'shell/output-tail-bytes))
      (is (str/ends-with? output (str/join (repeat 100 "e")))))))

(deftest terminal-exit-124-is-not-inferred-as-a-timeout
  (is (= "shell command exited 124"
         (#'shell/terminal-error {:exit {:code 124}} false))))

(deftest terminal-reconciliation-reserves-a-due-timeout
  (with-embedded-runtime {:storage :sqlite-memory}
    (fn [rt _]
      (doseq [[deadline timed-out? expected]
              [["2999-01-01T00:00:00Z" false [:commit/ordinary :cancel :acknowledge :clear]]
               ["2000-01-01T00:00:00Z" false [:commit/timeout :acknowledge :clear]]
               [nil true [:commit/timeout :acknowledge :clear]]]]
        (let [steps (atom [])
              gate (weaver/add! rt
                                {:title "Terminal timeout ownership"
                                 :attributes (cond-> {"shell/attempt-id" "attempt"
                                                      "shell/custody-handle" "handle"}
                                               deadline (assoc "shell/timeout-deadline"
                                                               deadline))})]
          (with-redefs-fn {#'shell/terminal-commit! (fn [& args]
                                                      (swap! steps conj
                                                             (if (last args)
                                                               :commit/timeout
                                                               :commit/ordinary))
                                                      :committed)
                           #'shell/cancel-timeout! (fn [& _] (swap! steps conj :cancel))
                           #'process/acknowledge! (fn [& _] (swap! steps conj :acknowledge))
                           #'shell/clear-attempt! (fn [& _]
                                                    (swap! steps conj :clear)
                                                    true)}
            (fn []
              (is (= :acknowledged
                     (#'shell/terminal-reconcile!
                      rt "run" (:id gate) "attempt" "handle"
                      {:phase :terminal} timed-out?)))))
          (is (= expected @steps)))))))

(deftest clearing-an-attempt-clears-its-timeout-state
  (with-embedded-runtime {:storage :sqlite-memory}
    (fn [rt _]
      (let [stdout (temp-file ".stdout")
            stderr (temp-file ".stderr")
            gate (weaver/add! rt
                              {:title "Timed-out attempt"
                               :attributes {"shell/attempt-id" "attempt-old"
                                            "shell/custody-handle" "handle-old"
                                            "shell/running" "attempt-old"
                                            "shell/timeout-deadline" "2000-01-01T00:00:00Z"
                                            "shell/timeout-intent" "timed-out"}})]
        (spit stdout "")
        (spit stderr "")
        (is (#'shell/clear-attempt! (:id gate) "attempt-old" "handle-old"))
        (weaver/update! rt (:id gate)
                        {:attributes {"shell/attempt-id" "attempt-new"
                                      "shell/custody-handle" "handle-new"
                                      "shell/running" "attempt-new"}})
        (is (= :committed
               (#'shell/terminal-commit!
                "run" (:id gate) "attempt-new" "handle-new"
                {:phase :terminal
                 :output {:stdout-ref (.getAbsolutePath stdout)
                          :stderr-ref (.getAbsolutePath stderr)}
                 :exit {:code 7}}
                false)))
        (let [after (weaver/show rt (:id gate))]
          (is (= "shell command exited 7" (attr after :gate/error)))
          (is (nil? (attr after :shell/timeout-deadline)))
          (is (nil? (attr after :shell/timeout-intent))))))))

(deftest malformed-terminal-fact-identifies-observed-custody-shape
  (let [detail (#'shell/terminal-error
                {:key "attempt-malformed"
                 :handle "handle-malformed"
                 :phase :terminal}
                false)]
    (is (str/includes? detail "expected one of :cancellation, :launch-failure, or :exit"))
    (is (str/includes? detail "attempt-malformed"))
    (is (str/includes? detail "handle-malformed"))))

(deftest stale-terminal-fact-does-not-touch-a-newer-attempt
  (with-embedded-runtime {:storage :sqlite-memory}
    (fn [rt _]
      (let [stdout (temp-file ".stdout")
            stderr (temp-file ".stderr")
            gate (weaver/add! rt {:title "New attempt"
                                  :attributes {"workflow/gate" "shell"
                                               "shell/attempt-id" "attempt-new"
                                               "shell/custody-handle" "handle-new"}})]
        (spit stdout "old")
        (spit stderr "")
        (is (= :stale
               (#'shell/terminal-commit!
                "stale" (:id gate) "attempt-old" "handle-old"
                {:phase :terminal
                 :output {:stdout-ref (.getAbsolutePath stdout)
                          :stderr-ref (.getAbsolutePath stderr)}
                 :exit {:code 0 :signal nil}}
                false)))
        (let [after (weaver/show rt (:id gate))]
          (is (= "attempt-new" (attr after :shell/attempt-id)))
          (is (= "handle-new" (attr after :shell/custody-handle)))
          (is (nil? (attr after :gate/error))))))))

(deftest launch-interruption-retains-the-claimed-attempt
  (with-embedded-runtime {:storage :sqlite-memory}
    (fn [rt _]
      (let [gate (weaver/add! rt {:title "Interrupted launch"
                                  :attributes {"workflow/gate" "shell"
                                               "workflow/run-id" "interrupted"
                                               "shell/argv" ["true"]
                                               "shell/running" "attempt-interrupted"
                                               "shell/attempt-id" "attempt-interrupted"}})]
        (with-redefs [process/launch! (fn [& _] (throw (InterruptedException.)))]
          (binding [shell/*runtime* rt]
            (#'shell/run-gate! rt "interrupted" (:id gate) "attempt-interrupted")))
        (let [after (weaver/show rt (:id gate))]
          (is (= "attempt-interrupted" (attr after :shell/attempt-id)))
          (is (= "attempt-interrupted" (attr after :shell/running))))))))

(deftest closed-attempt-with-acknowledged-fact-recovery-clears-only-its-claim
  (with-embedded-runtime {:storage :sqlite-memory}
    (fn [rt _]
      (let [gate (weaver/add! rt {:title "Closed custody"
                                  :state "closed"
                                  :attributes {"workflow/gate" "shell"
                                               "shell/attempt-id" "attempt-closed"
                                               "shell/custody-handle" "handle-closed"}})
            result (shell/apply-shell-attempts!
                    {:runtime rt
                     :desired [{:gate-id (:id gate)
                                :state "closed"
                                :attempt-id "attempt-closed"
                                :custody-handle "handle-closed"}]
                     :actual []})
            after (weaver/show rt (:id gate))]
        (is (= :closed-without-custody-fact
               (:recovered (first (:attempts result)))))
        (is (nil? (attr after :shell/attempt-id)))
        (is (nil? (attr after :shell/custody-handle)))
        (is (nil? (attr after :gate/error)))))))

(deftest owner-facts-remain-visible-when-no-attempt-is-desired
  (let [fact {:handle "orphan-handle" :owner :millhouse/shell-executor
              :key "orphan-attempt" :phase :running
              :output {:stdout-ref "/tmp/orphan.stdout"
                       :stderr-ref "/tmp/orphan.stderr"}}]
    (with-redefs [process/list-owned (fn [_ _] [fact])]
      (is (= [fact] (shell/read-shell-custody {:runtime nil}))))))

(deftest custody-listing-defer-is-preserved-through-apply
  (with-embedded-runtime
    (fn [rt _]
      (let [gate (weaver/add! rt {:title "Deferred custody"
                                  :attributes {"workflow/gate" "shell"
                                               "shell/attempt-id" "attempt-deferred"
                                               "shell/custody-handle" "handle-deferred"
                                               "shell/running" "attempt-deferred"}})
            deferred (with-redefs [process/list-owned
                                   (fn [_ _]
                                     (throw (ex-info "stale Weaver"
                                                     {:code "process/stale-weaver"})))]
                       (shell/read-shell-custody {:runtime rt}))
            result (shell/apply-shell-attempts!
                    {:runtime rt
                     :desired (shell/read-shell-attempts {:runtime rt})
                     :actual deferred})]
        (is (= :deferred (:status deferred)))
        (is (= :deferred (:status result)))
        (is (= [{:attempt-id "attempt-deferred" :deferred true}]
               (:attempts result)))
        (is (empty? (:errors result)))
        (is (= "attempt-deferred"
               (attr (weaver/show rt (:id gate)) :shell/attempt-id)))))))

(deftest read-shell-attempts-resolves-run-id-from-workflow-root
  (with-embedded-runtime {:storage :sqlite-memory}
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow
                                    'millhouse.workflow)
      (workflow/start! "root-derived"
                       (single-gate "root-derived" {"shell/argv" ["true"]})
                       {})
      (let [gate (shell-gate-strand rt "root-derived")]
        (weaver/update! rt (:id gate)
                        {:attributes {"shell/attempt-id" "attempt-root"
                                      "shell/custody-handle" "handle-root"
                                      "shell/running" "attempt-root"}})
        (let [attempt (first (shell/read-shell-attempts {:runtime rt}))]
          (is (nil? (attr gate :workflow/run-id)))
          (is (= "root-derived" (:run-id attempt))))))))

(deftest repeated-reconciliation-submits-one-retained-observer
  (with-shell-world
    (fn [rt]
      (let [stdout (temp-file ".stdout")
            stderr (temp-file ".stderr")
            started (promise)
            release (promise)
            calls (atom 0)
            terminal {:handle "handle-observer"
                      :key "attempt-observer"
                      :phase :terminal
                      :output {:stdout-ref (.getAbsolutePath stdout)
                               :stderr-ref (.getAbsolutePath stderr)}
                      :exit {:code 1}}
            get-record (fn [_runtime _handle]
                         (swap! calls inc)
                         (deliver started true)
                         @release)]
        (spit stdout "")
        (spit stderr "")
        (let [gate (weaver/add! rt {:title "Retained observer"
                                    :attributes {"workflow/gate" "shell"
                                                 "shell/attempt-id" "attempt-observer"
                                                 "shell/custody-handle" "handle-observer"
                                                 "shell/running" "attempt-observer"}})
              context {:runtime rt
                       :desired [{:gate-id (:id gate)
                                  :run-id "observer"
                                  :state "active"
                                  :attempt-id "attempt-observer"
                                  :custody-handle "handle-observer"}]
                       :actual [{:handle "handle-observer"
                                 :key "attempt-observer"
                                 :phase :running}]}]
          (with-redefs [process/get get-record]
            (try
              (is (= :applied (:status (shell/apply-shell-attempts! context))))
              (is (true? (deref started (test-support/await-budget-ms) false)))
              (is (= :applied (:status (shell/apply-shell-attempts! context))))
              (deliver release terminal)
              (await-eventually #(nil? (attr (weaver/show rt (:id gate)) :shell/attempt-id)))
              (is (= 1 @calls))
              (finally
                (deliver release terminal)))))))))

(deftest shutdown-observer-rejection-releases-claim-for-later-reconciliation
  (with-shell-world
    (fn [rt]
      (let [stdout (temp-file ".stdout")
            stderr (temp-file ".stderr")
            cleared (promise)
            shutdown-executor (Executors/newSingleThreadExecutor)
            usable-executor (Executors/newSingleThreadExecutor)
            clear-attempt (deref #'shell/clear-attempt!)
            terminal {:handle "handle-shutdown-observer"
                      :key "attempt-shutdown-observer"
                      :phase :terminal
                      :output {:stdout-ref (.getAbsolutePath stdout)
                               :stderr-ref (.getAbsolutePath stderr)}
                      :exit {:code 0}}]
        (spit stdout "")
        (spit stderr "")
        (try
          (workflow/start! "observer-shutdown"
                           (single-gate "observer-shutdown"
                                        {"shell/argv" ["true"]
                                         "gate/error" "held"})
                           {})
          (let [gate (shell-gate-strand rt "observer-shutdown")
                context {:runtime rt
                         :desired [{:gate-id (:id gate)
                                    :run-id "observer-shutdown"
                                    :state "active"
                                    :attempt-id "attempt-shutdown-observer"
                                    :custody-handle "handle-shutdown-observer"}]
                         :actual [{:handle "handle-shutdown-observer"
                                   :key "attempt-shutdown-observer"
                                   :phase :running}]}]
            (weaver/update! rt (:id gate)
                            {:attributes {"gate/error" nil
                                          "shell/running" "attempt-shutdown-observer"
                                          "shell/attempt-id" "attempt-shutdown-observer"
                                          "shell/custody-handle" "handle-shutdown-observer"}})
            (.shutdownNow shutdown-executor)
            (with-redefs-fn {#'shell/worker-executor (constantly shutdown-executor)}
              (fn []
                (doseq [actual [(:actual context) {:status :deferred :facts []}]]
                  (shell/apply-shell-attempts! (assoc context :actual actual))
                  (is (empty? @(:terminal-observers (#'shell/state))))
                  (is (false? @(:reconciliation-pending? (#'shell/state)))))))
            (let [after-rejection (weaver/show rt (:id gate))]
              (is (= "attempt-shutdown-observer"
                     (attr after-rejection :shell/attempt-id)))
              (is (= "attempt-shutdown-observer"
                     (attr after-rejection :shell/running)))
              (is (nil? (attr after-rejection :gate/error)))
              (is (empty? @(:terminal-observers (#'shell/state)))))
            (with-redefs-fn {#'shell/worker-executor (constantly usable-executor)
                             #'process/get (fn [_runtime _handle] terminal)
                             #'process/acknowledge! (fn [& _] {:acknowledged true})
                             #'shell/clear-attempt! (fn [& args]
                                                      (let [result (apply clear-attempt args)]
                                                        (deliver cleared true)
                                                        result))}
              (fn []
                (shell/apply-shell-attempts! context)
                (is (true? (deref cleared (test-support/await-budget-ms) false)))
                (await-eventually #(empty? @(:terminal-observers (#'shell/state))))
                (is (empty? @(:terminal-observers (#'shell/state))))
                (let [after-reconciliation (weaver/show rt (:id gate))]
                  (is (= "closed" (:state after-reconciliation)))
                  (is (nil? (attr after-reconciliation :shell/attempt-id)))
                  (is (nil? (attr after-reconciliation :gate/error)))))))
          (finally
            (.shutdownNow shutdown-executor)
            (.shutdownNow usable-executor)))))))

(deftest interrupted-observer-can-resume-without-losing-attempt
  (with-shell-world
    (fn [rt]
      (let [stdout (temp-file ".stdout")
            stderr (temp-file ".stderr")
            interrupted (promise)
            resumed (promise)
            calls (atom 0)
            terminal {:handle "handle-interrupted"
                      :key "attempt-interrupted-observer"
                      :phase :terminal
                      :output {:stdout-ref (.getAbsolutePath stdout)
                               :stderr-ref (.getAbsolutePath stderr)}
                      :exit {:code 0}}
            get-record (fn [_runtime _handle]
                         (case (swap! calls inc)
                           1 (do
                               (deliver interrupted true)
                               (throw (InterruptedException.)))
                           2 (do
                               (deliver resumed true)
                               terminal)))]
        (spit stdout "")
        (spit stderr "")
        (workflow/start! "observer-interrupted"
                         (single-gate "observer-interrupted"
                                      {"shell/argv" ["true"]
                                       "gate/error" "held"})
                         {})
        (let [gate (shell-gate-strand rt "observer-interrupted")]
          (weaver/update! rt (:id gate)
                          {:attributes {"gate/error" nil
                                        "shell/running" "attempt-interrupted-observer"
                                        "shell/attempt-id" "attempt-interrupted-observer"
                                        "shell/custody-handle" "handle-interrupted"}})
          (let [context {:runtime rt
                         :desired [{:gate-id (:id gate)
                                    :run-id "observer-interrupted"
                                    :state "active"
                                    :attempt-id "attempt-interrupted-observer"
                                    :custody-handle "handle-interrupted"}]
                         :actual [{:handle "handle-interrupted"
                                   :key "attempt-interrupted-observer"
                                   :phase :running}]}]
            (with-redefs [process/get get-record]
              (shell/apply-shell-attempts! context)
              (is (true? (deref interrupted (test-support/await-budget-ms) false)))
              (await-eventually #(empty? @(:terminal-observers (#'shell/state))))
              (let [after-interruption (weaver/show rt (:id gate))]
                (is (= "attempt-interrupted-observer"
                       (attr after-interruption :shell/attempt-id)))
                (is (= "attempt-interrupted-observer"
                       (attr after-interruption :shell/running)))
                (is (nil? (attr after-interruption :gate/error))))
              (shell/apply-shell-attempts! context)
              (is (true? (deref resumed (test-support/await-budget-ms) false)))
              (await-eventually #(nil? (attr (weaver/show rt (:id gate)) :shell/attempt-id)))
              (let [after (weaver/show rt (:id gate))]
                (is (= "closed" (:state after)))
                (is (nil? (attr after :gate/error)))))))))))

(deftest deferred-startup-custody-read-retries-after-admission
  (with-embedded-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow
                                    'millhouse.workflow)
      (workflow/start! "deferred-startup"
                       (single-gate "deferred-startup"
                                    {"shell/argv" ["true"]})
                       {})
      (let [stdout (temp-file ".stdout")
            stderr (temp-file ".stderr")
            first-read (promise)
            admitted (promise)
            acknowledged (promise)
            reads (atom 0)
            launches (atom 0)
            terminal {:handle "handle-deferred-startup"
                      :key "attempt-deferred-startup"
                      :phase :terminal
                      :output {:stdout-ref (.getAbsolutePath stdout)
                               :stderr-ref (.getAbsolutePath stderr)}
                      :exit {:code 0}}
            list-owned (fn [_runtime _owner]
                         (if (= 1 (swap! reads inc))
                           (do
                             (deliver first-read true)
                             (throw (ex-info "Mill has not admitted Weaver"
                                             {:code "process/control-unavailable"})))
                           (do @admitted [terminal])))
            gate (shell-gate-strand rt "deferred-startup")]
        (spit stdout "")
        (spit stderr "")
        (weaver/update! rt (:id gate)
                        {:attributes {"shell/running" "attempt-deferred-startup"
                                      "shell/attempt-id" "attempt-deferred-startup"
                                      "shell/custody-handle" "handle-deferred-startup"}})
        (with-redefs [process/list-owned list-owned
                      process/launch! (fn [& _] (swap! launches inc))
                      process/acknowledge! (fn [_ _ _]
                                             (deliver acknowledged true)
                                             {:acknowledged true})]
          (try
            (test-support/activate-spool! rt :millhouse/shell
                                          'millhouse.test-modules.shell-executor
                                          :after [:millhouse/workflow])
            (is (true? (deref first-read (test-support/await-budget-ms) false)))
            (is (= "attempt-deferred-startup"
                   (attr (weaver/show rt (:id gate)) :shell/attempt-id)))
            (deliver admitted true)
            (is (true? (deref acknowledged (test-support/await-budget-ms) false)))
            (await-eventually #(nil? (attr (weaver/show rt (:id gate)) :shell/attempt-id)))
            (is (= "closed" (:state (weaver/show rt (:id gate)))))
            (is (zero? @launches))
            (is (nil? (attr (weaver/show rt (:id gate)) :gate/error)))
            (finally
              (deliver admitted true))))))))

(deftest custody-listing-rethrows-unrelated-failure-with-empty-desired
  (let [failure (ex-info "broken listing" {:code "process/broken"})]
    (with-redefs [process/list-owned (fn [_ _] (throw failure))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"broken listing"
                            (shell/read-shell-custody {:runtime nil}))))))

(deftest unreadable-custody-output-is-contained-per-attempt
  (with-embedded-runtime {:storage :sqlite-memory}
    (fn [rt _]
      (let [bad (weaver/add! rt {:title "Unreadable custody"
                                 :attributes {"workflow/gate" "shell"
                                              "shell/attempt-id" "attempt-bad-output"
                                              "shell/custody-handle" "handle-bad-output"}})
            good (weaver/add! rt {:title "Readable custody"
                                  :attributes {"workflow/gate" "shell"
                                               "shell/attempt-id" "attempt-good-output"
                                               "shell/custody-handle" "handle-good-output"}})
            stdout (temp-file ".stdout")
            stderr (temp-file ".stderr")
            acknowledged (atom [])]
        (spit stdout "ok")
        (spit stderr "")
        (with-redefs [process/acknowledge!
                      (fn [_ _ handle]
                        (swap! acknowledged conj handle)
                        {:acknowledged true :handle handle})]
          (let [result (shell/apply-shell-attempts!
                        {:runtime rt
                         :desired [{:gate-id (:id bad)
                                    :run-id "unreadable"
                                    :state "active"
                                    :attempt-id "attempt-bad-output"
                                    :custody-handle "handle-bad-output"}
                                   {:gate-id (:id good)
                                    :run-id "readable"
                                    :state "active"
                                    :attempt-id "attempt-good-output"
                                    :custody-handle "handle-good-output"}]
                         :actual [{:handle "handle-bad-output"
                                   :key "attempt-bad-output"
                                   :phase :terminal
                                   :output {:stdout-ref "/missing/stdout"
                                            :stderr-ref "/missing/stderr"}
                                   :exit {:code 0}}
                                  {:handle "handle-good-output"
                                   :key "attempt-good-output"
                                   :phase :terminal
                                   :output {:stdout-ref (.getAbsolutePath stdout)
                                            :stderr-ref (.getAbsolutePath stderr)}
                                   :exit {:code 1}}
                                  {:handle "orphan-output"
                                   :key "orphan-output"
                                   :phase :running}]})]
            (is (some #(= "attempt-bad-output" (:attempt-id %)) (:errors result)))
            (is (some #(= "orphan-output" (:attempt-id %)) (:errors result)))
            (is (some #(and (= "attempt-good-output" (:attempt-id %))
                            (:acknowledged %))
                      (:attempts result)))
            (is (= ["handle-good-output"] @acknowledged))
            (let [retained (weaver/show rt (:id bad))]
              (is (= "active" (:state retained)))
              (is (= "attempt-bad-output" (attr retained :shell/attempt-id)))
              (is (= "handle-bad-output" (attr retained :shell/custody-handle))))))))))

(deftest retained-mismatch-is-visible-without-touching-newer-gate
  (with-embedded-runtime {:storage :sqlite-memory}
    (fn [rt _]
      (let [gate (weaver/add! rt {:title "Newer attempt"
                                  :attributes {"workflow/gate" "shell"
                                               "shell/attempt-id" "attempt-new"
                                               "shell/custody-handle" "handle-new"}})
            result (shell/apply-shell-attempts!
                    {:runtime rt
                     :desired [{:gate-id (:id gate)
                                :state "active"
                                :attempt-id "attempt-old"
                                :custody-handle "handle-old"}]
                     :actual [{:handle "handle-other"
                               :key "attempt-old"
                               :phase :running}]})
            after (weaver/show rt (:id gate))]
        (is (some #(= "attempt-old" (:attempt-id %)) (:errors result)))
        (is (= "attempt-new" (attr after :shell/attempt-id)))
        (is (= "handle-new" (attr after :shell/custody-handle)))
        (is (nil? (attr after :gate/error)))))))

(deftest running-fact-rearms-original-absolute-timeout
  (with-embedded-runtime
    (fn [rt _]
      (let [deadline "2999-01-01T00:00:00Z"
            scheduled (atom nil)]
        (with-redefs [scheduler/schedule!
                      (fn [_ wake] (reset! scheduled wake) wake)]
          (shell/apply-shell-attempts!
           {:runtime rt
            :desired [{:gate-id "gate-timeout"
                       :state "active"
                       :attempt-id "attempt-timeout"
                       :custody-handle "handle-timeout"
                       :timeout-deadline deadline}]
            :actual [{:handle "handle-timeout"
                      :key "attempt-timeout"
                      :phase :running}]}))
        (is (= "shell-timeout/attempt-timeout" (:key @scheduled)))
        (is (= (java.time.Instant/parse deadline) (:wake-at @scheduled)))
        (is (= {:attempt-id "attempt-timeout" :handle "handle-timeout"}
               (:payload @scheduled)))))))

(deftest reconciliation-rejects-a-non-string-durable-timeout-deadline
  (with-embedded-runtime {:storage :sqlite-memory}
    (fn [rt _]
      (let [failure (try
                      (shell/apply-shell-attempts!
                       {:runtime rt
                        :desired [{:gate-id "gate-malformed"
                                   :state "active"
                                   :attempt-id "attempt-malformed"
                                   :custody-handle "handle-malformed"
                                   :timeout-deadline 42}]
                        :actual [{:handle "handle-malformed"
                                  :key "attempt-malformed"
                                  :phase :running}]})
                      nil
                      (catch clojure.lang.ExceptionInfo throwable throwable))]
        (is (= {:attempt-id "attempt-malformed"
                :gate-id "gate-malformed"
                :value 42
                :expected-format "ISO-8601 Instant string"}
               (ex-data failure)))))))

(deftest timeout-wake-rejects-an-invalid-durable-timeout-deadline
  (with-embedded-runtime {:storage :sqlite-memory}
    (fn [rt _]
      (let [gate (weaver/add! rt
                              {:title "Malformed timeout wake"
                               :state "active"
                               :attributes {"workflow/gate" "shell"
                                            "shell/attempt-id" "attempt-wake"
                                            "shell/custody-handle" "handle-wake"
                                            "shell/timeout-deadline" "not-an-instant"}})
            failure (try
                      (shell/timeout-wake
                       {:runtime rt
                        :payload {:attempt-id "attempt-wake"
                                  :handle "handle-wake"}})
                      nil
                      (catch clojure.lang.ExceptionInfo throwable throwable))]
        (is (= {:attempt-id "attempt-wake"
                :gate-id (:id gate)
                :value "not-an-instant"
                :expected-format "ISO-8601 Instant string"}
               (ex-data failure)))))))

(defn- millstrand-source-root []
  (test-alpha/spool-checkout-root "millstrand/api/process/alpha.clj"))

(declare run-command-result!)

(defn- run-command!
  "Run one isolated command and return stdout, failing on nonzero exit."
  [command cwd environment stdin]
  (let [{:keys [output error-output exit-code]}
        (run-command-result! command cwd environment stdin)
        diagnostics (str "stdout:\n" output "\nstderr:\n" error-output)]
    (is (zero? exit-code)
        (str "command failed: " (pr-str command) "\n" diagnostics))
    (when-not (zero? exit-code)
      (throw (ex-info "Isolated command failed" {:command command :exit-code exit-code
                                                 :output output
                                                 :error-output error-output})))
    output))

(defn- run-command-result!
  "Run one isolated command and return its stdout, stderr, and exit code."
  [command cwd environment stdin]
  (let [builder (ProcessBuilder. ^java.util.List command)
        _ (when cwd (.directory builder (io/file cwd)))
        _ (doseq [[key value] environment]
            (.put (.environment builder) key value))
        process (.start builder)]
    (when stdin
      (with-open [writer (io/writer (.getOutputStream process))]
        (.write writer stdin)))
    (let [output (future (slurp (.getInputStream process)))
          error-output (future (slurp (.getErrorStream process)))
          exit-code (.waitFor process)]
      {:output @output :error-output @error-output :exit-code exit-code})))

(deftest isolated-command-keeps-stderr-out-of-stdout
  (is (= {:output "edn-output\n"
          :error-output "download-progress\n"
          :exit-code 0}
         (run-command-result! ["sh" "-c" "echo edn-output; echo download-progress >&2"]
                              nil {} nil))))

(defn- mill-environment [source state-home]
  {"MILLSTRAND_SOURCE" (.getCanonicalPath (io/file source))
   "XDG_STATE_HOME" (.getCanonicalPath (io/file state-home))})

(defn- mill-command!
  ([mill source state-home workspace args]
   (mill-command! mill source state-home workspace args nil))
  ([mill source state-home workspace args stdin]
   (run-command! (into [mill]
                       (if (= ["status"] args)
                         args
                         (concat args ["--workspace" workspace])))
                 source
                 (mill-environment source state-home)
                 stdin)))

(defn- weaver-repl! [mill source state-home workspace form]
  (edn/read-string
   (mill-command! mill source state-home workspace
                  ["weaver" "repl" "--stdin"]
                  form)))

(defn- weaver-status! [mill source state-home workspace]
  (json/read-str (mill-command! mill source state-home workspace
                                ["weaver" "status" "--json"])
                 :key-fn keyword))

(defn- build-mill! [source target state-home]
  (run-command! ["go" "build" "-o" (.getCanonicalPath (io/file target))
                 "./cli/cmd/mill"]
                source
                (mill-environment source state-home)
                nil)
  (.getCanonicalPath (io/file target)))

(defn- start-mill! [mill source state-home log-file]
  (let [builder (doto (ProcessBuilder. [mill "start"])
                  (.redirectErrorStream true)
                  (.redirectOutput (io/file log-file)))
        _ (doseq [[key value] (mill-environment source state-home)]
            (.put (.environment builder) key value))]
    (.start builder)))

(defn- millhouse-source-root []
  (-> (test-alpha/spool-checkout-root "millhouse/workflow.clj")
      .getParentFile
      .getParentFile
      .getCanonicalPath))

(defn- short-disposable-root []
  (let [root (io/file "/tmp" (str "ms" (.pid (java.lang.ProcessHandle/current))))]
    (when (.exists root)
      (throw (ex-info "Short disposable root is already in use"
                      {:root (.getCanonicalPath root)})))
    (when-not (.mkdirs root)
      (throw (ex-info "Could not create short disposable root"
                      {:root (.getCanonicalPath root)})))
    root))

(defn- shell-acceptance-deps-edn [root]
  (pr-str {:deps {'millhouse/workflow
                  {:local/root (str root "/spools/workflow")}}}))

(def ^:private shell-acceptance-init
  "(require '[millstrand.api.current.alpha :as current]
            '[millstrand.api.runtime.alpha :as runtime])
   (def rt (current/runtime))
   (runtime/module! rt :millhouse/workflow
     {:ns 'millhouse.workflow
      :required? true})
   (runtime/module! rt :millhouse/shell
     {:ns 'millhouse.workflow.spool
      :after [:millhouse/workflow]
      :required? true})")

(defn- workflow-shell-gate-form [release-fifo]
  (str "(do
     (require '[millhouse.workflow :as workflow]
              '[millhouse.executors.shell :as shell])
     (let [result
           (workflow/start! \"shell-replacement\"
             (workflow/workflow
               \"Shell replacement\"
               (workflow/gate :check \"Run shell check\" :shell
                 :attributes {\"test/run-id\" \"shell-replacement\"
                              \"shell/argv\" [\"sh\" \"-c\" \"IFS= read -r release < "
       release-fifo
       "; printf shell-ok\"]})
               (workflow/step :after \"After\" :self :depends-on [:check]))
             {})]
       (shell/scan!)
       result))"))

(defn- shell-gate-probe-form []
  "(do
     (require '[millstrand.api.current.alpha :as current]
              '[millstrand.api.weaver.alpha :as weaver]
              '[millhouse.workflow :as workflow])
     (let [rt (current/runtime)
           gate (first (weaver/list rt
                                   [:and
                                    [:= [:attr \"workflow/gate\"] \"shell\"]
                                    [:= [:attr \"test/run-id\"] \"shell-replacement\"]]
                                   {}))]
       {:generation (:generation-id rt)
        :gate (select-keys gate [:id :state :attributes])
        :ready (workflow/ready \"shell-replacement\")}))")

;; This expensive proof builds Mill and replaces a real Weaver while a command
;; is blocked on a FIFO. Only this topology demonstrates custody surviving the
;; death of its observer generation. Embedded reopen or fake facts cannot do so.
;; Keep the owned Process cleanup and isolated state home; never use shared Mill.
(deftest shell-gate-reaches-next-frontier-across-planned-weaver-replacement
  (let [source (millstrand-source-root)
        consumer-root (millhouse-source-root)
        disposable-root (short-disposable-root)
        state-home (io/file disposable-root "state")
        workspace (io/file disposable-root ".millstrand")
        release-fifo (io/file disposable-root "release")
        mill-target (io/file disposable-root "mill")
        mill-log (io/file disposable-root "mill.log")
        mill-process (atom nil)
        started-result (atom nil)
        last-probe (atom nil)
        after-probe (atom nil)]
    (try
      (run-command! ["mkfifo" (.getCanonicalPath release-fifo)] nil {} nil)
      (let [mill (build-mill! source mill-target state-home)
            workspace-path (.getCanonicalPath workspace)]
        (reset! mill-process (start-mill! mill source state-home mill-log))
        (test-support/poll-until
         #(zero? (:exit-code
                  (run-command-result! [mill "status"]
                                       source
                                       (mill-environment source state-home)
                                       nil)))
         {:timeout-ms (test-support/await-budget-ms 30000)
          :interval-ms 100
          :on-timeout #(throw (ex-info "Timed out waiting for disposable Mill" {}))})
        (mill-command! mill source state-home workspace-path ["init"])
        (spit (io/file workspace "deps.edn")
              (str (shell-acceptance-deps-edn consumer-root) "\n"))
        (spit (io/file workspace "init.clj") shell-acceptance-init)
        (mill-command! mill source state-home workspace-path ["weaver" "start"])
        (let [_before-status (weaver-status! mill source state-home workspace-path)
              before (weaver-repl! mill source state-home workspace-path
                                   (shell-gate-probe-form))]
          (reset! started-result
                  (weaver-repl! mill source state-home workspace-path
                                (workflow-shell-gate-form
                                 (.getCanonicalPath release-fifo))))
          (let [running
                (test-support/poll-until
                 #(let [probe (weaver-repl! mill source state-home workspace-path
                                            (shell-gate-probe-form))]
                    (reset! last-probe probe)
                    (when (get-in probe [:gate :attributes :shell/running])
                      probe))
                 {:timeout-ms (test-support/await-budget-ms)
                  :interval-ms 100
                  :on-timeout
                  (fn []
                    (throw (ex-info "Shell gate was not claimed"
                                    {:started @started-result
                                     :probe @last-probe})))})]
            (is (some? (get-in running [:gate :attributes :shell/custody-handle]))
                "the running gate has a Mill custody handle before replacement")
            (is (.isAlive ^Process @mill-process)
                "Mill is alive before the planned Weaver replacement")
            ;; Ask Mill to perform its planned Weaver replacement while the
            ;; custody-backed shell attempt is still in flight.
            (mill-command! mill source state-home workspace-path ["weaver" "restart"])
            (is (.isAlive ^Process @mill-process)
                "Mill remains alive through the planned Weaver replacement")
            (let [_after-status (weaver-status! mill source state-home workspace-path)
                  adopted (weaver-repl! mill source state-home workspace-path
                                        (shell-gate-probe-form))]
              (is (not= (:generation before) (:generation adopted)))
              (is (= (select-keys (get-in running [:gate :attributes])
                                  [:shell/attempt-id :shell/custody-handle])
                     (select-keys (get-in adopted [:gate :attributes])
                                  [:shell/attempt-id :shell/custody-handle])))
              (spit release-fifo "release\n")
              (let [after
                    (test-support/poll-until
                     #(let [probe (weaver-repl! mill source state-home workspace-path
                                                (shell-gate-probe-form))]
                        (reset! after-probe probe)
                        (when (= ["After"] (mapv :title (:ready probe)))
                          probe))
                     {:timeout-ms (test-support/await-budget-ms 15000)
                      :interval-ms 100
                      :on-timeout #(throw (ex-info "Shell gate did not advance after Weaver replacement"
                                                   {:before before
                                                    :probe @after-probe}))})]
                (is (= ["After"] (mapv :title (:ready after))))
                (is (= "closed" (get-in after [:gate :state])))
                (is (= "shell" (get-in after [:gate :attributes :workflow/executor])))
                (is (nil? (get-in after [:gate :attributes :identity/by-identity])))
                (is (= "shell-ok" (get-in after [:gate :attributes :shell/output]))))))))
      (finally
        (when (and @mill-process (.isAlive ^Process @mill-process))
          (try
            (mill-command! (if (.isFile mill-target)
                             (.getCanonicalPath mill-target)
                             "mill")
                           source
                           state-home
                           (.getCanonicalPath workspace)
                           ["weaver" "stop"])
            (catch Throwable _ nil)))
        (when-let [^Process process @mill-process]
          (when (.isAlive process)
            (.destroy process)
            (when-not (.waitFor process 5 java.util.concurrent.TimeUnit/SECONDS)
              (.destroyForcibly process)
              (.waitFor process 5 java.util.concurrent.TimeUnit/SECONDS))))
        (test-support/delete-tree! disposable-root)))))

(deftest non-zero-exit-stamps-error-stays-ready-and-is-discoverable
  (with-shell-world
    (fn [rt]
      (workflow/start! "fail" (single-gate "fail" {"shell/argv" ["false"]}) {})
      (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
      (let [gate-id (:id (ready-shell-gate "fail"))
            errored (await-eventually #(let [g (weaver/show rt gate-id)]
                                         (when (attr g :gate/error) g)))]
        (is (= "active" (:state errored)))
        (is (= 1 (attr errored :shell/exit-code)))
        (is (string? (attr errored :shell/output)))
        (is (str/includes? (attr errored :gate/error) "exited 1"))
        ;; the gate stays ready and stamped, not masquerading as a closed step
        (is (= [gate-id] (mapv :id (filter #(= "shell" (:gate %)) (workflow/ready "fail")))))
        (is (nil? (attr (weaver/show rt gate-id) :identity/by-identity)))
        ;; discoverable through both the stall predicate and the coordinator query
        (is (= gate-id (:gate (shell/shell-stalled? (ready-shell-gate "fail")))))
        (is (some #(= gate-id (:id %)) (weaver/list-query rt 'stalled-shell-gates {})))))))

(deftest blank-error-stamp-is-present-data-not-a-clear-and-nil-re-arms
  (with-shell-world
    (fn [rt]
      (let [counter (temp-file ".count")
            run-count (fn [] (count (remove str/blank? (str/split-lines (slurp counter)))))
            argv (fn [exit] ["sh" "-c" (str "echo run >> '" (.getPath counter) "'; exit " exit)])]
        (workflow/start! "blank" (single-gate "blank" {"shell/argv" (argv 5)}) {})
        (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
        (let [gate-id (:id (ready-shell-gate "blank"))]
          (await-eventually #(let [g (weaver/show rt gate-id)]
                               (when (attr g :gate/error) g)))
          (shell/scan!)
          (is (nil? (attr (weaver/show rt gate-id) :shell/running)))
          (is (= 1 (run-count)))
          ;; blanking gate/error stores "" — present data, not absence — so the
          ;; gate stays errored and skipped. Scan reserves synchronously before
          ;; dispatch, so no claim marker means no worker was submitted.
          (weaver/update! rt gate-id {:attributes {"gate/error" ""
                                                   "shell/argv" (argv 0)}})
          (weaver/add! rt {:title "noise-1"})
          (shell/scan!)
          (is (nil? (attr (weaver/show rt gate-id) :shell/running)))
          (is (= "" (attr (weaver/show rt gate-id) :gate/error)))
          (is (= 1 (run-count)))
          ;; removing gate/error (nil patch / JSON null) is the only re-arm: the
          ;; next scan finds an un-errored gate and re-runs the check.
          (weaver/update! rt gate-id {:attributes {"gate/error" nil}})
          (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
          (let [closed (await-eventually #(let [g (weaver/show rt gate-id)]
                                            (when (= "closed" (:state g)) g)))]
            (is (zero? (attr closed :shell/exit-code)))
            (is (= 2 (run-count)))))
        ;; a blank-stamped active gate is present, so it is a stall: both the
        ;; predicate and the coordinator query report it.
        (let [decoy (weaver/add! rt {:title "Blank decoy"
                                     :attributes {"workflow/gate" "shell"
                                                  "gate/error" ""}})]
          (is (= (:id decoy) (:gate (shell/shell-stalled? {:id (:id decoy)}))))
          (is (some #(= (:id decoy) (:id %))
                    (weaver/list-query rt 'stalled-shell-gates {}))))))))

(deftest request-contract-rejects-malformed-input-without-a-world
  (is (s/valid? ::shell/request {:shell/argv ["true"]}))
  (is (s/valid? ::shell/request {:shell/argv ["echo" "ok"]
                                 :shell/cwd "/tmp" :shell/timeout-secs 1}))
  (doseq [request [{}
                   {:shell/argv ""}
                   {:shell/argv []}
                   {:shell/argv ["echo" 5]}
                   {:shell/argv ["true"] :shell/cwd 7}
                   {:shell/argv ["true"] :shell/cwd ""}
                   {:shell/argv ["true"] :shell/timeout-secs 0}]]
    (is (not (s/valid? ::shell/request request)) (pr-str request))))

(deftest invalid-input-fails-loudly-and-spawns-no-process
  (with-shell-world
    (fn [rt]
      (let [launches (atom [])]
        (with-redefs [process/launch! (fn [& args] (swap! launches conj args))]
          (workflow/start! "invalid" (single-gate "invalid" {"shell/argv" []}) {})
          (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
          (let [gate-id (:id (ready-shell-gate "invalid"))
                errored (await-eventually #(let [g (weaver/show rt gate-id)]
                                             (when (attr g :gate/error) g)))]
            (is (= "active" (:state errored)))
            (is (str/includes? (attr errored :gate/error) "shell/argv"))
            (is (str/includes? (attr errored :gate/error)
                               "millhouse.executors.shell/request"))
            (is (empty? @launches))
            (is (nil? (attr errored :shell/exit-code)))
            (is (nil? (attr errored :shell/output)))))))))

(deftest timeout-cancels-simulated-custody-and-stamps-failure
  ;; This proves the executor's bounded timeout/cancellation projection, not OS
  ;; process killing: the custody fake deliberately never launches sleep 30.
  (with-shell-world
    (fn [rt]
      (workflow/start! "timeout" (single-gate "timeout" {"shell/argv" ["sh" "-c" "sleep 30"]
                                                         "shell/timeout-secs" 1}) {})
      (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
      (let [gate-id (:id (ready-shell-gate "timeout"))
            errored (await-eventually #(let [g (weaver/show rt gate-id)]
                                         (when (attr g :gate/error) g)))]
        (is (= "active" (:state errored)))
        (is (str/includes? (attr errored :gate/error) "timed out"))))))

(deftest non-shell-gate-is-ignored
  (with-shell-world
    (fn [rt]
      ;; a non-:shell gate is never touched, even carrying shell/* attributes
      (workflow/start! "iso" (workflow/workflow
                              "Iso"
                              (workflow/gate :sub "Delegate" :agent
                                             :attributes {"shell/argv" ["true"]})) {})
      (let [sub-gate-id (:id (first (workflow/ready "iso")))]
        (shell/scan!)
        (is (= "active" (:state (weaver/show rt sub-gate-id))))
        (is (nil? (attr (weaver/show rt sub-gate-id) :shell/running)))
        (is (nil? (attr (weaver/show rt sub-gate-id) :shell/exit-code)))))))

(deftest dependent-shell-gate-runs-only-after-its-dependency-closes
  (with-shell-world
    (fn [rt]
      (workflow/start! "comp" (gated-gate "comp" {"shell/argv" ["true"]}) {})
      (let [first-step (first (workflow/ready "comp"))]
        (is (= "First" (:title first-step)))
        ;; the :shell gate is not ready yet, so the executor must not touch it
        (shell/scan!)
        (let [gate (shell-gate-strand rt "comp")]
          (is (= "active" (:state gate)))
          (is (nil? (attr gate :shell/running)))
          (is (nil? (attr gate :shell/exit-code))))
        ;; close the dependency; the gate becomes ready and the executor runs the check
        (workflow/complete! "comp" {:step (:id first-step)})
        (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)}))
      (let [gate-id (:id (shell-gate-strand rt "comp"))]
        (await-eventually #(= "closed" (:state (weaver/show rt gate-id))))
        (is (zero? (attr (weaver/show rt gate-id) :shell/exit-code)))
        (is (= "After" (:title (first (workflow/ready "comp")))))))))

(deftest closed-nested-workflow-root-does-not-release-shell-gate-to-outer-run
  (with-embedded-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow
                                    'millhouse.workflow)
      (workflow/start! "outer" (idle-workflow) {})
      (workflow/start! "inner" (gated-gate "inner" {"shell/argv" ["true"]}) {})
      (let [outer-root (workflow/current-root "outer")
            inner-root (workflow/current-root "inner")]
        (weaver/update! rt (:id outer-root)
                        {:edges [{:type "parent-of"
                                  :to (:id inner-root)}]})
        ;; Make the inner gate ready and close its root before opening the shell
        ;; resource. The first activation scan and the explicit scan below must
        ;; both honour the nearest closed root instead of releasing the gate to
        ;; the active outer run.
        (workflow/complete! "inner")
        (weaver/update! rt (:id inner-root) {:state "closed"})
        (let [gate-id (:id (shell-gate-strand rt "inner"))]
          (with-redefs [process/list-owned (fn [_ _] [])]
            (test-support/activate-spool! rt :millhouse/shell
                                          'millhouse.test-modules.shell-executor
                                          :after [:millhouse/workflow])
            (shell/scan!))
          (is (= "closed" (:state (weaver/show rt (:id inner-root)))))
          (is (= "active" (:state (weaver/show rt (:id outer-root)))))
          (is (= "active" (:state (weaver/show rt gate-id))))
          (is (nil? (attr (weaver/show rt gate-id) :shell/running)))
          (is (some #(= gate-id (:id %)) (weaver/ready rt))))))))

(deftest parent-blocked-shell-gate-is-not-dispatched
  (with-embedded-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow
                                    'millhouse.workflow)
      (workflow/start! "bond-left" (idle-workflow) {})
      (workflow/start! "bond-right"
                       (single-gate "bond-right" {"shell/argv" ["true"]})
                       {})
      (let [left-root (workflow/current-root "bond-left")
            right-root (workflow/current-root "bond-right")
            gate-id (:id (shell-gate-strand rt "bond-right"))]
        (workflow/bond! (:id left-root) (:id right-root))
        (is (= [] (workflow/ready "bond-right")))
        (with-redefs [process/list-owned (fn [_ _] [])]
          (test-support/activate-spool! rt :millhouse/shell
                                        'millhouse.test-modules.shell-executor
                                        :after [:millhouse/workflow])
          (shell/scan!))
        (is (= "active" (:state (weaver/show rt gate-id))))
        (is (nil? (attr (weaver/show rt gate-id) :shell/running)))))))

(deftest malformed-active-workflow-root-identity-fails-through-public-scan
  (with-embedded-runtime {:storage :sqlite-memory}
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow
                                    'millhouse.workflow)
      (workflow/start! "malformed-root"
                       (single-gate "malformed-root" {"shell/argv" ["true"]})
                       {})
      (let [root-id (:id (workflow/current-root "malformed-root"))
            gate-id (:id (shell-gate-strand rt "malformed-root"))]
        (doseq [run-id [nil "" 42]]
          (weaver/update! rt root-id {:attributes {"workflow/run-id" run-id}})
          (let [failure (try
                          (shell/scan!)
                          nil
                          (catch clojure.lang.ExceptionInfo throwable
                            throwable))]
            (is (some? failure) (str "malformed run-id: " (pr-str run-id)))
            (is (= gate-id (:gate-id (ex-data failure)))
                (str "malformed run-id: " (pr-str run-id)))
            (is (= root-id (:root-id (ex-data failure)))
                (str "malformed run-id: " (pr-str run-id)))
            (is (= "non-blank string" (:expected (ex-data failure)))
                (str "malformed run-id: " (pr-str run-id)))
            (is (str/includes? (ex-message failure) "workflow/run-id")
                (str "malformed run-id: " (pr-str run-id)))))))))

(deftest state-shape-matches-declared-version
  ;; Drift alarm for the shell executor's versioned spool-state: a key added to new-state
  ;; without a state-version bump would survive refresh as a stale map.
  (let [state (#'shell/new-state)]
    (try
      (is (= #{:scan-monitor :terminal-observers :reconciliation-pending? :worker-executor :close-fn}
             (set (keys state))))
      (finally ((:close-fn state))))))

(deftest module-forms-publish-and-preserve-runtime-pool
  (with-redefs [process/list-owned (fn [_ _] [])]
    (with-embedded-runtime
      (fn [rt _]
        (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
        (test-support/activate-spool! rt :millhouse/shell 'millhouse.test-modules.shell-executor
                                      :after [:millhouse/workflow])
        (let [pool (binding [shell/*runtime* rt] (:worker-executor (#'shell/state)))]
          (is (some #(= :shell/engine (:key %)) (events/handlers rt))
              "the graph-change event handler is registered")
          (is (= "shell" (:waiter (first (workflow/executor-catalog)))))
          (is (= shell/stalled-shell-gates
                 [:and [:= :state "active"]
                  [:= [:attr "workflow/gate"] "shell"]
                  [:exists [:attr "gate/error"]]]))
          (test-support/activate-spool! rt :millhouse/shell 'millhouse.test-modules.shell-executor
                                        :after [:millhouse/workflow])
          (is (identical? pool (binding [shell/*runtime* rt] (:worker-executor (#'shell/state))))
              "unchanged refresh preserves the runtime-owned worker pool"))))))

(defn inspect-disposable-validation
  "Non-Land fixture: revision is the contents of a disposable candidate file."
  [_runtime {:keys [params]}]
  {:decision :allow :revision (str/trim (slurp (get params "candidate")))
   :reason "Disposable candidate inspected" :evidence []})

(defn- validation-config []
  {:recipes {:disposable/check-v1
             {:inspect 'millhouse.executors.shell-test/inspect-disposable-validation}}})

(deftest validation-failure-repair-one-attempt-and-replay
  (with-shell-world
    (fn [rt]
      (let [candidate (temp-file ".candidate")
            launches (temp-file ".launches")
            handle (validation/open! rt (validation-config))]
        (try
          (spit candidate "broken")
          (workflow/start!
           "validation-repair"
           (single-gate "validation-repair"
                        {"validation/recipe" "disposable/check-v1"
                         "validation/params" {"candidate" (str candidate)}
                         "shell/argv" ["sh" "-c"
                                       (str "echo attempt >> '" launches "'; test \"$(cat '"
                                            candidate "')\" = repaired")]}) {})
          (await-eventually #(some? (attr (shell-gate-strand rt "validation-repair") :validation/receipt)))
          (await-eventually #(nil? (attr (shell-gate-strand rt "validation-repair") :shell/attempt-id)))
          (let [gate (shell-gate-strand rt "validation-repair")
                request {:run-id "validation-repair" :step (:id gate) :request-id "repair-1"
                         :expected-revision "repaired" :reason "Repair disposable candidate"
                         :by-identity "fixture-owner"}]
            (is (= "confirmed" (get (attr gate :validation/receipt) "acknowledgement")))
            (spit candidate "repaired")
            (is (= "eligible" (:state (workflow/retry-validation! (assoc request :dry-run true)))))
            (is (= gate (weaver/show rt (:id gate))))
            (is (= "accepted" (:state (workflow/retry-validation! request))))
            (await-eventually #(= "closed" (:state (weaver/show rt (:id gate)))))
            (is (= "replayed" (:state (workflow/retry-validation! request))))
            (is (= "refused" (:state (workflow/retry-validation! (assoc request :reason "different")))))
            (is (= "refused" (:state (workflow/retry-validation!
                                      (assoc request :step (:id (first (workflow/ready "validation-repair"))))))))
            (is (= 2 (count (str/split-lines (slurp launches)))))
            (let [final (weaver/show rt (:id gate))
                  action (first (attr final :validation/actions))]
              (is (= 1 (get-in action ["old-attempt" "exit"])))
              (is (= "broken" (get-in action ["old-attempt" "revision"])))
              (is (= "shell" (attr final :workflow/executor))))
            (is (= "refused" (:state (workflow/retry-validation! (assoc request :request-id "closed"))))))
          (finally (validation/close! rt handle)))))))

(deftest validation-interrupted-acknowledgement-permits-honest-progress
  (with-shell-world
    (fn [rt]
      (let [candidate (temp-file ".candidate")
            handle (validation/open! rt (validation-config))
            ack process/acknowledge!]
        (try
          (spit candidate "revision-one")
          (with-redefs [process/acknowledge!
                        (fn [& args]
                          (apply ack args)
                          (throw (ex-info "Acknowledgement confirmation lost" {})))]
            (workflow/start!
             "validation-lost-ack"
             (single-gate "validation-lost-ack"
                          {"validation/recipe" "disposable/check-v1"
                           "validation/params" {"candidate" (str candidate)}
                           "shell/argv" ["sh" "-c" "exit 1"]}) {})
            (await-eventually #(str/includes? (or (attr (shell-gate-strand rt "validation-lost-ack") :gate/error) "")
                                              "Acknowledgement confirmation lost")))
          (let [gate (shell-gate-strand rt "validation-lost-ack")
                result (workflow/retry-validation!
                        {:run-id "validation-lost-ack" :step (:id gate) :request-id "explicit-second"
                         :expected-revision "revision-one" :reason "One more explicit check"
                         :by-identity "fixture-owner"})]
            (is (= "accepted" (:state result)))
            (is (= "unknown" (get-in result [:action "old-attempt" "acknowledgement"])))
            (is (true? (get-in result [:action "old-attempt" "terminal-observed"])))
            (await-eventually #(= "confirmed" (get (attr (weaver/show rt (:id gate)) :validation/receipt)
                                                   "acknowledgement"))))
          (finally (validation/close! rt handle)))))))

(defn- with-failed-validation [f]
  (with-shell-world
    (fn [rt]
      (let [candidate (temp-file ".candidate")
            handle (validation/open! rt (validation-config))]
        (try
          (spit candidate "one")
          (workflow/start! "refusals"
                           (single-gate "refusals"
                                        {"validation/recipe" "disposable/check-v1"
                                         "validation/params" {"candidate" (str candidate)}
                                         "shell/argv" ["sh" "-c" "exit 1"]}) {})
          (await-eventually #(and (attr (shell-gate-strand rt "refusals") :validation/receipt)
                                  (nil? (attr (shell-gate-strand rt "refusals") :shell/attempt-id))))
          (let [gate (shell-gate-strand rt "refusals")]
            (f rt candidate gate {:run-id "refusals" :step (:id gate) :request-id "one"
                                  :expected-revision "one" :reason "Explicit validation"
                                  :by-identity "fixture-owner"}))
          (finally (validation/close! rt handle)))))))

(deftest validation-revision-current-attempt-and-live-custody-refuse
  (with-failed-validation
    (fn [rt candidate gate request]
      (spit candidate "two")
      (is (= "refused" (:state (workflow/retry-validation! request))))
      (spit candidate "one")
      (let [receipt (attr gate :validation/receipt)]
        (with-redefs [process/list-owned
                      (fn [_ _] [{:key (get receipt "attempt-id")
                                  :handle (get receipt "custody-handle") :phase :running}])]
          (is (= "refused" (:state (workflow/retry-validation! request)))))
        (with-redefs [process/list-owned
                      (fn [_ _] [{:key (get receipt "attempt-id")
                                  :handle (get receipt "custody-handle") :phase :unknown}])]
          (is (= "refused" (:state (workflow/retry-validation! request))))))
      (weaver/update! rt (:id gate) {:attributes {"shell/attempt-id" "newer"}})
      (is (= "refused" (:state (workflow/retry-validation! request))))
      (is (empty? (attr (weaver/show rt (:id gate)) :validation/actions))))))

(deftest validation-completion-revision-drift-is-not-success
  (with-shell-world
    (fn [rt]
      (let [candidate (temp-file ".candidate")
            handle (validation/open! rt (validation-config))
            launch process/launch!]
        (try
          (spit candidate "one")
          (with-redefs [process/launch! (fn [& args]
                                          (let [result (apply launch args)]
                                            (spit candidate "two")
                                            result))]
            (workflow/start! "drift"
                             (single-gate "drift"
                                          {"validation/recipe" "disposable/check-v1"
                                           "validation/params" {"candidate" (str candidate)}
                                           "shell/argv" ["sh" "-c" "exit 0"]}) {})
            (await-eventually #(attr (shell-gate-strand rt "drift") :gate/error)))
          (let [gate (shell-gate-strand rt "drift")]
            (is (= "active" (:state gate)))
            (is (= "Validation revision changed" (attr gate :gate/error)))
            (is (= "one" (get (attr gate :validation/receipt) "revision"))))
          (finally (validation/close! rt handle)))))))

(deftest validation-frozen-registration-and-executor-success-are-protected
  (with-failed-validation
    (fn [rt _candidate gate _request]
      (is (thrown? clojure.lang.ExceptionInfo
                   (weaver/update! rt (:id gate)
                                   {:attributes {"validation/recipe" "other/check-v1"}})))
      (is (thrown? clojure.lang.ExceptionInfo
                   (workflow/complete! "refusals" {:step (:id gate)
                                                   :by-identity "shell"})))
      (is (= "active" (:state (weaver/show rt (:id gate))))))))

(deftest validation-batch-preimage-fence-rolls-back-authorization
  (with-failed-validation
    (fn [rt _candidate gate request]
      (let [apply-batch batch/apply!
            inject? (atom true)]
        (with-redefs [batch/apply!
                      (fn [runtime payload & context]
                        (when (compare-and-set! inject? true false)
                          (weaver/update! runtime (:id gate)
                                          {:attributes {"gate/error" "Newer failure projection"}}))
                        (apply apply-batch runtime payload context))]
          (is (thrown? clojure.lang.ExceptionInfo (workflow/retry-validation! request))))
        (is (= "Newer failure projection" (attr (weaver/show rt (:id gate)) :gate/error)))
        (is (nil? (attr (weaver/show rt (:id gate)) :validation/actions)))
        (is (empty? (weaver/list rt [:= [:attr "validation/action-request-id"] "one"] {})))))))

(deftest validation-does-not-rearm-unmarked-or-other-executor-gates
  (with-shell-world
    (fn [_rt]
      (doseq [waiter [:shell :code :agent :merge-turn]]
        (let [run-id (str "unmarked-" (name waiter))]
          (workflow/start! run-id
                           (workflow/workflow "Unmarked"
                                              (workflow/gate :check "Check" waiter
                                                             :attributes {"gate/error" "failed"})) {})
          (let [gate (first (workflow/ready run-id))]
            (is (= "refused"
                   (:state (workflow/retry-validation!
                            {:run-id run-id :step (:id gate) :request-id "refuse"
                             :expected-revision "one" :reason "Cannot retry arbitrary gates"
                             :by-identity "fixture-owner"}))))))))))
