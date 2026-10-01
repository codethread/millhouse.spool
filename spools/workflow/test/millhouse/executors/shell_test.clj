(ns millhouse.executors.shell-test
  "Tests for the workflow-gate to shell-command executor."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [millhouse.executors.shell :as shell]
            [millhouse.workflow :as workflow]
            [millhouse.workflow.cli :as cli]
            [millstrand.api.cli.alpha :as cli-alpha]
            [millhouse.workflow.execution :as execution]
            [millhouse.workflow.validation :as validation]
            [millhouse.test-support :as test-support :refer [with-embedded-runtime]]
            [millstrand.api.process.alpha :as process]
            [millstrand.api.batch.alpha :as batch]
            [millstrand.api.weaver.alpha :as weaver])
  (:import [java.io File]))

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

(defn- single-gate
  "A run whose first ready step is a `:shell` gate, followed by a dependent step."
  [run-id gate-attrs]
  (workflow/workflow
   "Shell single"
   (workflow/gate :check "Run shell check" :shell :attributes (assoc gate-attrs "test/run-id" run-id))
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

(defn- shell-gate-strand [rt run-id]
  (first (sort-by :title
                  (weaver/list rt [:and [:= [:attr "workflow/gate"] "shell"]
                                   [:= [:attr "test/run-id"] run-id]] {}))))

(defn- view [rt run-id]
  (execution/inspect rt {:run-id run-id :step (:id (shell-gate-strand rt run-id))}))

(deftest command-contract-through-common-inspection
  (with-shell-world
    (fn [rt]
      (doseq [[run-id code] [["success" 0] ["failure" 124]]]
        (workflow/start! run-id (single-gate run-id
                                             {"shell/argv" ["sh" "-c" (str "printf output; exit " code)]}) {})
        (await-eventually #(= :confirmed (get-in (view rt run-id) [:cleanup :acknowledgement])))
        (let [result (:result (view rt run-id))]
          (is (= code (get-in result [:value :exit-code])))
          (is (= "output" (get-in result [:value :output])))
          (is (= (if (zero? code) :succeeded :failed) (:outcome result)))
          (is (= :settled (:settlement result)))))
      (is (= ["After"] (mapv :title (workflow/ready "success"))))
      (let [prior (view rt "failure")
            request {:run-id "failure" :step (:gate-id prior)
                     :expected-attempt (:attempt-id prior) :request-id "ordinary"
                     :reason "Corrected command" :by-identity "fixture-owner"}]
        (is (thrown? clojure.lang.ExceptionInfo
                     (execution/retry! rt (assoc request :expected-revision "candidate"))))
        (weaver/update! rt (:gate-id prior) {:attributes {"shell/argv" ["true"]}})
        (is (= :accepted (:status (execution/retry! rt request))))
        (await-eventually #(= :succeeded (get-in (view rt "failure") [:result :outcome])))))))

(deftest adapter-request-correlation-output-and-exact-stop
  (let [stdout (temp-file ".stdout")
        stderr (temp-file ".stderr")
        record {:key "attempt" :handle "owned-handle" :phase :terminal :exit {:code 124}
                :output {:stdout-ref (str stdout) :stderr-ref (str stderr)}}
        calls (atom [])
        context {:attempt-id "attempt" :request {:shell/argv ["printf" "literal | argv"] :shell/cwd "/tmp"}}]
    (spit stdout (str/join (repeat 20000 "o")))
    (spit stderr "stderr-tail")
    (with-redefs [process/launch! (fn [& args] (swap! calls conj args) record)
                  process/list-owned (fn [& _] [record])]
      (let [result (shell/start! nil context)]
        (is (= [nil :millhouse/shell-executor "attempt"
                {:argv ["printf" "literal | argv"] :cwd "/tmp" :env {}}] (vec (first @calls))))
        (is (= :failed (:outcome result)))
        (is (= 16384 (alength (.getBytes ^String (get-in result [:value :output]) "UTF-8"))))
        (is (str/ends-with? (get-in result [:value :output]) "stderr-tail"))
        (is (= result (shell/observe! nil context)))))
    (with-redefs [process/list-owned (fn [& _] [(assoc record :phase :running)])
                  process/cancel! (fn [& args] (swap! calls conj args))]
      (shell/stop! nil context)
      (is (= [nil :millhouse/shell-executor "owned-handle"] (vec (last @calls)))))
    (with-redefs [process/list-owned (fn [& _] [])]
      (is (= :unknown (:status (shell/observe! nil context)))))))

(deftest lost-launch-response-and-commit-before-acknowledgement
  (with-shell-world
    (fn [rt]
      (let [launch process/launch!
            ack process/acknowledge!
            launches (atom 0)
            observed (atom [])]
        (with-redefs [process/launch! (fn [& args]
                                        (swap! launches inc)
                                        (apply launch args)
                                        (throw (ex-info "Launch response lost" {})))
                      process/acknowledge! (fn [& args]
                                             (swap! observed conj (:result (view rt "lost-response")))
                                             (apply ack args))]
          (workflow/start! "lost-response" (single-gate "lost-response" {"shell/argv" ["true"]}) {})
          (await-eventually #(= :confirmed (get-in (view rt "lost-response") [:cleanup :acknowledgement])))
          (is (= 1 @launches))
          (is (= [:succeeded] (mapv :outcome @observed)))
          (is (= ["After"] (mapv :title (workflow/ready "lost-response")))))))))

(deftest unknown-cancellation-keeps-the-whole-run-frozen
  ;; Simulated custody: this proves stop/settlement mapping, NOT OS tree killing.
  (with-shell-world
    (fn [rt]
      (workflow/start! "stop" (two-shell-gates "stop" {"shell/argv" ["sh" "-c" "sleep 30"]}) {})
      (await-eventually #(:accepted? (view rt "stop")))
      (let [freeze (execution/quiesce-run! rt "stop" "Stop for repair")
            records (process/list-owned rt :millhouse/shell-executor)
            uncertain (assoc (first records) :phase :terminal
                             :cancellation {:reason "unknown" :stop :uncertain})]
        (with-redefs [process/list-owned (fn [& _] [uncertain])]
          (is (= :unknown (:status (execution/retire! rt freeze))))
          (is (thrown? clojure.lang.ExceptionInfo
                       (execution/resume-run! rt "stop" {:freeze freeze :status :settled}))))
        (let [receipt (await-eventually #(let [receipt (execution/retire! rt freeze)]
                                           (when (= :settled (:status receipt)) receipt)))]
          (is (= 2 (count (:attempts receipt))))
          (is (= 1 (count (filter :may-have-started? (:attempts receipt)))))
          (is (= :resumed (:status (execution/resume-run! rt "stop" receipt)))))))))

(defn- retry-command [rt request]
  (let [argv (into ["retry" (:run-id request)]
                   (mapcat (fn [[k v]]
                             (if (= k :dry-run) (when v ["--dry-run"])
                                 [(str "--" (name k)) v])))
                   (dissoc request :run-id))
        spec (:arg-spec (weaver/resolve-op rt 'workflow))]
    (cli/workflow {:op/args (cli-alpha/parse spec argv {}) :op/argv argv})))

(defn inspect-disposable-validation
  "Inspect an independent disposable candidate, with no Land or Git policy."
  [_rt {:keys [params]}]
  {:decision :allow :revision (str/trim (slurp (get params "candidate")))
   :reason "Disposable candidate inspected" :evidence []})

(deftest revision-bound-retry-and-acknowledgement-loss
  (with-shell-world
    (fn [rt]
      (test-support/activate-spool! rt :millhouse/workflow-cli 'millhouse.test-modules.workflow-cli
                                    :after [:millhouse/workflow])
      (let [candidate (temp-file ".candidate")
            launches (temp-file ".launches")
            config (validation/open! rt {:recipes {:disposable/check-v1
                                                   {:inspect 'millhouse.executors.shell-test/inspect-disposable-validation}}})
            ack process/acknowledge!]
        (try
          (spit candidate "broken")
          (with-redefs [process/acknowledge! (fn [& args]
                                               (apply ack args)
                                               (throw (ex-info "Acknowledgement confirmation lost" {})))]
            (workflow/start! "validation"
                             (single-gate "validation"
                                          {"validation/recipe" "disposable/check-v1"
                                           "validation/params" {"candidate" (str candidate)}
                                           "shell/argv" ["sh" "-c" (str "echo launch >> '" launches
                                                                        "'; test \"$(cat '" candidate "')\" = repaired")]}) {})
            (await-eventually #(= :unknown (get-in (view rt "validation") [:cleanup :acknowledgement]))))
          (let [prior (view rt "validation")
                gate-id (:gate-id prior)
                request {:run-id "validation" :step gate-id :request-id "repair"
                         :expected-attempt (:attempt-id prior)
                         :expected-revision "repaired" :reason "Repair candidate" :by-identity "fixture-owner"}]
            (is (= :settled (get-in prior [:result :settlement])))
            (is (= "broken" (get-in prior [:result :validation-revision])))
            (is (thrown? clojure.lang.ExceptionInfo
                         (execution/retry! rt (-> request (dissoc :expected-revision)
                                                  (assoc :expected-attempt (:attempt-id prior))))))
            (is (thrown? clojure.lang.ExceptionInfo
                         (retry-command rt (assoc request :dry-run true))))
            (spit candidate "repaired")
            (validation/close! rt config)
            ;; Removal cannot turn retained validation into an ordinary retry.
            (weaver/update! rt gate-id {:attributes {"validation/recipe" nil}})
            (is (thrown? clojure.lang.ExceptionInfo
                         (retry-command rt (dissoc request :expected-revision))))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"differ from the prior attempt"
                                  (retry-command rt request)))
            (weaver/update! rt gate-id {:attributes {"validation/recipe" "disposable/check-v1"}})
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not registered"
                                  (retry-command rt request)))
            (let [changed (validation/open! rt {:recipes {:disposable/check-v1
                                                          {:inspect 'clojure.core/identity}}})]
              (try
                (is (thrown-with-msg? clojure.lang.ExceptionInfo #"registration or shell request changed"
                                      (retry-command rt request)))
                (finally (validation/close! rt changed))))
            (validation/open! rt (:config config))
            (doseq [[key value] {"validation/params" {"candidate" "replacement"}
                                 "validation/recipe" "disposable/replacement-v1"
                                 "validation/config" {"inspect" "clojure.core/identity"}
                                 "validation/request" {"shell/argv" ["true"]}}]
              (is (thrown? clojure.lang.ExceptionInfo
                           (weaver/update! rt gate-id {:attributes {key value}}))))
            (weaver/update! rt gate-id {:attributes {"shell/argv" ["true"]}})
            (is (thrown? clojure.lang.ExceptionInfo (retry-command rt request)))
            (weaver/update! rt gate-id {:attributes {"shell/argv" (get-in prior [:request :shell/argv])}})
            (let [apply-batch batch/apply!]
              (with-redefs [batch/apply! (fn [runtime payload & args]
                                           (when (some #(= "repair" (get-in % [:attributes "execution/action-key"]))
                                                       (:strands payload))
                                             (weaver/update! runtime gate-id {:attributes {"test/concurrent-edit" true}}))
                                           (apply apply-batch runtime payload args))]
                (is (thrown? clojure.lang.ExceptionInfo (retry-command rt request))))
              (is (= (:attempt-id prior) (:attempt-id (view rt "validation"))))
              (is (empty? (weaver/list rt [:= [:attr "execution/action-key"] "repair"] {}))))
            (let [caller (Thread/currentThread)
                  apply-batch batch/apply!]
              ;; Background acknowledgement may progress; the dry-run may not write.
              (with-redefs [batch/apply! (fn [& args]
                                          (when (= caller (Thread/currentThread))
                                            (throw (ex-info "Dry-run attempted a write" {})))
                                          (apply apply-batch args))]
                (is (= "eligible" (:status (retry-command rt (assoc request :dry-run true)))))))
            ;; Actual authorization must recheck after a successful read-only plan.
            (spit candidate "drifted-after-plan")
            (is (thrown? clojure.lang.ExceptionInfo (retry-command rt request)))
            (spit candidate "repaired")
            (is (thrown? clojure.lang.ExceptionInfo
                         (retry-command rt (assoc request :expected-attempt "stale"))))
            (let [accepted (retry-command rt request)]
              (is (= "accepted" (:status accepted)))
              (is (= :unknown (get-in accepted [:action :previous-result :acknowledgement]))))
            (await-eventually #(= :confirmed (get-in (view rt "validation") [:cleanup :acknowledgement])))
            (let [replayed (retry-command rt request)]
              (is (= "replayed" (:status replayed)))
              (is (= (:action replayed) (:retry-action (view rt "validation"))))
              (is (= :failed (get-in replayed [:action :previous-result :outcome]))))
            (is (thrown? clojure.lang.ExceptionInfo
                         (retry-command rt (assoc request :reason "conflict"))))
            (is (= 2 (count (str/split-lines (slurp launches)))))
            (is (= :succeeded (get-in (view rt "validation") [:result :outcome])))
            (is (= "closed" (:state (weaver/show rt gate-id))))
            (is (= 1 (count (weaver/list rt [:= [:attr "execution/token"] (:attempt-id prior)] {})))))
          (finally (validation/close! rt config)))))))

(deftest validation-completion-drift-and-never-accepted-failure
  (with-shell-world
    (fn [rt]
      (let [candidate (temp-file ".candidate")
            config (validation/open! rt {:recipes {:disposable/check-v1
                                                   {:inspect 'millhouse.executors.shell-test/inspect-disposable-validation}}})
            launch process/launch!]
        (try
          (spit candidate "one")
          (with-redefs [process/launch! (fn [& args]
                                          (let [record (apply launch args)]
                                            (spit candidate "two") record))]
            (workflow/start! "drift"
                             (single-gate "drift" {"validation/recipe" "disposable/check-v1"
                                                   "validation/params" {"candidate" (str candidate)}
                                                   "shell/argv" ["true"]}) {})
            (await-eventually #(:result (view rt "drift"))))
          (is (= "Validation revision changed" (get-in (view rt "drift") [:result :error :message])))
          (is (= :failed (get-in (view rt "drift") [:result :outcome])))
          (is (= {:outcome :succeeded :value {:exit-code 0 :output ""} :error nil}
                 (get-in (view rt "drift") [:result :evidence "backend-result"])))
          (is (= "active" (:state (shell-gate-strand rt "drift"))))
          (workflow/start! "never"
                           (workflow/workflow "Never accepted"
                                              (workflow/step :first "First" :self)
                                              (workflow/gate :check "Check" :shell :depends-on [:first]
                                                             :attributes {"test/run-id" "never"
                                                                          "validation/recipe" "disposable/check-v1"
                                                                          "validation/params" {"candidate" (str candidate)}
                                                                          "shell/argv" ["true"]})) {})
          (validation/close! rt config)
          (workflow/complete! "never")
          (await-eventually #(:result (view rt "never")))
          (is (false? (:accepted? (view rt "never"))))
          (is (= :settled (get-in (view rt "never") [:result :settlement])))
          (is (= :not-needed (get-in (view rt "never") [:cleanup :acknowledgement])))
          (finally (validation/close! rt config)))))))

(workflow/defworkflow abort-replacement
  "A non-success replacement for retained cleanup verification."
  {:entrypoints #{:start}}
  (workflow/workflow "Replacement" (workflow/step :abort "Abort" :self)))

(deftest retired-shell-cleanup-cannot-refence-the-replacement
  (with-shell-world
    (fn [rt]
      (let [ack process/acknowledge!
            allow-ack (atom false)
            acked (promise)]
        (with-redefs [process/acknowledge!
                      (fn [& args]
                        (if @allow-ack
                          (let [result (apply ack args)] (deliver acked true) result)
                          (throw (ex-info "Retain terminal custody" {}))))]
          (workflow/start! "retired" (single-gate "retired" {"shell/argv" ["false"]}) {})
          (await-eventually #(= :unknown (get-in (view rt "retired") [:cleanup :acknowledgement])))
          (let [freeze (execution/quiesce-run! rt "retired" "Abandon")
                receipt (execution/retire! rt freeze)
                gate-id (:gate-id (view rt "retired"))]
            (is (= :settled (:status receipt)))
            (execution/abandon-run! rt {:run-id "retired" :root-id (:root-id freeze)
                                        :reason "Abandon" :by-identity "fixture-owner"
                                        :retirement receipt :workflow #'abort-replacement :params {}})
            (let [old-gate (weaver/show rt gate-id)
                  replacement (workflow/current-root "retired")]
              (reset! allow-ack true)
              (execution/reconcile! rt {:run-id "retired" :step gate-id})
              (is (true? (deref acked 10000 false)))
              (is (= old-gate (weaver/show rt gate-id)))
              (is (= replacement (workflow/current-root "retired")))
              (is (= ["Abort"] (mapv :title (workflow/ready "retired")))))))))))

(deftest legacy-shell-cutover-requires-drain
  ;; The refusal precedes engine startup, so all DB access is serialized.
  (with-embedded-runtime {:storage :sqlite-memory}
    (fn [rt _]
      (weaver/add! rt {:title "Legacy Shell" :attributes {"workflow/gate" "shell" "shell/attempt-id" "legacy"}})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Drain legacy Shell execution"
                            (shell/open-shell-engine! {:runtime rt}))))))
