(ns millhouse.spools.executors.shell-test
  "Tests for the workflow-gate to shell-command executor."
  (:require [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [millhouse.spools.executors.shell :as shell]
            [millhouse.spools.workflow :as workflow]
            [millhouse.test-support :as test-support :refer [with-runtime]]
            [millstrand.api.events.alpha :as events]
            [millstrand.api.process.alpha :as process]
            [millstrand.api.scheduler.alpha :as scheduler]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.test.alpha :as test-alpha])
  (:import [java.io File]))

(defn- with-shell [f]
  ;; Unit worlds do not have a Mill control channel. Keep these focused tests
  ;; deterministic by replacing only the custody seam with a terminal-fact fake;
  ;; disposable-world acceptance exercises the real Weaver-to-Mill channel.
  (let [records (atom {})
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
                       output-file (doto (File/createTempFile "shell-custody-output" ".txt")
                                     (.deleteOnExit))
                       error-file (doto (File/createTempFile "shell-custody-error" ".txt")
                                    (.deleteOnExit))
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
        (with-runtime
          (fn [rt _]
            (test-support/activate-spool! rt :millhouse/spools-workflow 'millhouse.spools.workflow)
            (test-support/activate-spool! rt :millhouse/spools-shell 'millhouse.test-modules.shell-executor
                                          :after [:millhouse/spools-workflow])
            (f rt)))))))

(defn- await-eventually
  "Poll for a real `:shell` subprocess outcome (RFC-Dtt-001.REC7): callers
  settle dispatch with `test-alpha/await-quiescent!` first, then use this only for
  the off-lane process-completion signal that quiescence cannot observe."
  ([pred] (await-eventually pred (test-support/await-budget-ms)))
  ([pred timeout-ms]
   (test-support/poll-until pred
                            {:timeout-ms timeout-ms
                             :on-timeout #(throw (ex-info "Timed out" {}))})))

(defn- attr [strand k]
  (get-in strand [:attributes k]))

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

(defn- ready-shell-gate [run-id]
  (first (filter #(= "shell" (:gate %)) (workflow/ready run-id))))

(defn- shell-gate-strand [rt run-id]
  (first (weaver/list rt [:and [:= [:attr "workflow/gate"] "shell"]
                          [:= [:attr "test/run-id"] run-id]]
                      {})))

(defn- temp-file [suffix]
  (doto (File/createTempFile "shell-test" suffix)
    (.deleteOnExit)))

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

(deftest custody-output-failure-is-visible-and-does-not-close-a-gate
  (with-runtime
    (fn [rt _]
      (let [gate (weaver/add! rt {:title "Unreadable custody"
                                  :attributes {"workflow/gate" "shell"
                                               "workflow/run-id" "unreadable"
                                               "shell/attempt-id" "attempt-unreadable"
                                               "shell/custody-handle" "handle-unreadable"}})
            output (try
                     (#'shell/terminal-commit!
                      "unreadable" (:id gate) "attempt-unreadable" "handle-unreadable"
                      {:phase :terminal
                       :output {:stdout-ref "/missing/stdout"
                                :stderr-ref "/missing/stderr"}
                       :exit {:code 0 :signal nil}}
                      false)
                     nil
                     (catch java.io.FileNotFoundException throwable throwable))]
        (is (instance? java.io.FileNotFoundException output))
        (is (= "attempt-unreadable"
               (attr (weaver/show rt (:id gate)) :shell/attempt-id)))
        (is (nil? (attr (weaver/show rt (:id gate)) :gate/error)))))))

(deftest terminal-exit-124-is-not-inferred-as-a-timeout
  (is (= "shell command exited 124"
         (#'shell/terminal-error {:exit {:code 124}} false))))

(deftest terminal-reconciliation-reserves-a-due-timeout
  (with-runtime
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
  (with-runtime
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
  (with-runtime
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
  (with-runtime
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
  (with-runtime
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
  (with-runtime
    (fn [rt _]
      (let [fact {:handle "orphan-handle" :owner :millhouse/shell-executor
                  :key "orphan-attempt" :phase :running
                  :output {:stdout-ref "/tmp/orphan.stdout"
                           :stderr-ref "/tmp/orphan.stderr"}}]
        (with-redefs [process/list-owned (fn [_ _] [fact])]
          (is (= [fact] (shell/read-shell-custody {:runtime rt}))))))))

(deftest custody-listing-defer-is-preserved-through-apply
  (with-runtime
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

(deftest custody-listing-rethrows-unrelated-failure-with-empty-desired
  (with-runtime
    (fn [rt _]
      (let [failure (ex-info "broken listing" {:code "process/broken"})]
        (with-redefs [process/list-owned (fn [_ _] (throw failure))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"broken listing"
                                (shell/read-shell-custody {:runtime rt}))))))))

(deftest unreadable-custody-output-is-contained-per-attempt
  (with-runtime
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
            (is (= ["handle-good-output"] @acknowledged))))))))

(deftest retained-mismatch-is-visible-without-touching-newer-gate
  (with-runtime
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
  (with-runtime
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
  (with-runtime
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
  (with-runtime
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

(def ^:private canonical-m0-producer
  "71c0ed3d80fcad090b74a704a8eb165a3fad996e")

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
                                ["weaver" "status"])
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
  (-> (test-alpha/spool-checkout-root "millhouse/spools/workflow.clj")
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
  (pr-str {:deps {'millhouse.spools/workflow
                  {:local/root (str root "/spools/workflow")}}}))

(def ^:private shell-acceptance-init
  "(require '[millstrand.api.current.alpha :as current]
            '[millstrand.api.runtime.alpha :as runtime])
   (def rt (current/runtime))
   (runtime/module! rt :millhouse/spools-workflow
     {:ns 'millhouse.spools.workflow
      :required? true})
   (runtime/module! rt :millhouse/spools-shell
     {:ns 'millhouse.spools.workflow.spool
      :after [:millhouse/spools-workflow]
      :required? true})")

(defn- workflow-shell-gate-form [release-fifo]
  (str "(do
     (require '[millhouse.spools.workflow :as workflow]
              '[millhouse.spools.executors.shell :as shell])
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
              '[millhouse.spools.workflow :as workflow])
     (let [rt (current/runtime)
           gate (first (weaver/list rt
                                   [:and
                                    [:= [:attr \"workflow/gate\"] \"shell\"]
                                    [:= [:attr \"test/run-id\"] \"shell-replacement\"]]
                                   {}))]
       {:generation (:generation-id rt)
        :gate (select-keys gate [:id :state :attributes])
        :ready (workflow/ready \"shell-replacement\")}))")

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
      (is (= canonical-m0-producer
             (str/trim (run-command! ["git" "-C" (.getCanonicalPath (io/file source))
                                      "rev-parse" "HEAD"]
                                     nil {} nil)))
          "the acceptance world is built from the canonical M0 producer")
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
            (let [_after-status (weaver-status! mill source state-home workspace-path)]
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
                (is (= "shell" (get-in after [:gate :attributes :workflow/outcome-by])))
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

(deftest pass-closes-gate-records-outcome-and-unblocks-next-step
  (with-shell
    (fn [rt]
      (workflow/start! "pass" (single-gate "pass" {"shell/argv" ["true"]}) {})
      (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
      (let [gate-id (:id (shell-gate-strand rt "pass"))
            closed (await-eventually #(let [g (weaver/show rt gate-id)]
                                        (when (= "closed" (:state g)) g)))]
        (is (= "shell" (attr closed :workflow/outcome-by)))
        (is (zero? (attr closed :shell/exit-code)))
        (is (string? (attr closed :shell/output)))
        (is (nil? (attr closed :gate/error)))
        (is (= "After" (:title (first (workflow/ready "pass")))))))))

(deftest non-zero-exit-stamps-error-stays-ready-and-is-discoverable
  (with-shell
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
        (is (nil? (attr (weaver/show rt gate-id) :workflow/outcome-by)))
        ;; discoverable through both the stall predicate and the coordinator query
        (is (= gate-id (:gate (shell/shell-stalled? (ready-shell-gate "fail")))))
        (is (some #(= gate-id (:id %)) (weaver/list-query rt 'stalled-shell-gates {})))))))

(deftest errored-gate-is-not-rerun-until-error-cleared
  (with-shell
    (fn [rt]
      (let [counter (temp-file ".count")
            run-count (fn [] (count (remove str/blank? (str/split-lines (slurp counter)))))
            argv (fn [exit] ["sh" "-c" (str "echo run >> '" (.getPath counter) "'; exit " exit)])]
        (workflow/start! "rec" (single-gate "rec" {"shell/argv" (argv 3)}) {})
        (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
        (let [gate-id (:id (ready-shell-gate "rec"))
              errored (await-eventually #(let [g (weaver/show rt gate-id)]
                                           (when (attr g :gate/error) g)))]
          (is (= 3 (attr errored :shell/exit-code)))
          (is (= 1 (run-count)))
          ;; unrelated graph mutations fire scans, but the errored gate is skipped:
          ;; the expensive check runs once, not per mutation. This is deterministic
          ;; without waiting: claim-and-dispatch! stamps shell/running on the scan
          ;; thread strictly before the only worker-pool submission path, so a
          ;; re-dispatch regression is visible as a claim marker the moment scan!
          ;; returns — no marker means nothing was submitted.
          (weaver/add! rt {:title "noise-1"})
          (weaver/add! rt {:title "noise-2"})
          (shell/scan!)
          (is (nil? (attr (weaver/show rt gate-id) :shell/running)))
          (is (some? (attr (weaver/show rt gate-id) :gate/error)))
          (is (= 1 (run-count)))
          ;; clearing gate/error (and fixing the command) re-runs the check once
          ;; and closes the gate on the next scan.
          (weaver/update! rt gate-id {:attributes {"gate/error" nil
                                                   "shell/running" nil
                                                   "shell/argv" (argv 0)}})
          (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
          (let [closed (await-eventually #(let [g (weaver/show rt gate-id)]
                                            (when (= "closed" (:state g)) g)))]
            (is (zero? (attr closed :shell/exit-code)))
            (is (nil? (attr closed :gate/error)))
            (is (= 2 (run-count)))))))))

(deftest blank-error-stamp-is-present-data-not-a-clear-and-nil-re-arms
  (with-shell
    (fn [rt]
      (let [counter (temp-file ".count")
            run-count (fn [] (count (remove str/blank? (str/split-lines (slurp counter)))))
            argv (fn [exit] ["sh" "-c" (str "echo run >> '" (.getPath counter) "'; exit " exit)])]
        (workflow/start! "blank" (single-gate "blank" {"shell/argv" (argv 5)}) {})
        (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
        (let [gate-id (:id (ready-shell-gate "blank"))]
          (await-eventually #(let [g (weaver/show rt gate-id)]
                               (when (attr g :gate/error) g)))
          (is (= 1 (run-count)))
          ;; blanking gate/error stores "" — present data, not absence — so the
          ;; gate stays errored and skipped. The deterministic no-marker check from
          ;; errored-gate-is-not-rerun proves nothing was dispatched.
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

(deftest invalid-input-fails-loudly-and-spawns-no-process
  (with-shell
    (fn [rt]
      (doseq [[i [bad expected]] (map-indexed vector [[{} "shell/argv"]
                                                      [{"shell/argv" ""} "shell/argv"]
                                                      [{"shell/argv" []} "shell/argv"]
                                                      [{"shell/argv" ["echo" 5]} "shell/argv"]
                                                      [{"shell/argv" ["true"] "shell/cwd" 7} "shell/cwd"]
                                                      [{"shell/argv" ["true"] "shell/cwd" ""} "shell/cwd"]])]
        (let [run-id (str "invalid-" i)]
          (workflow/start! run-id (single-gate run-id bad) {})
          (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
          (let [gate-id (:id (ready-shell-gate run-id))
                errored (await-eventually #(let [g (weaver/show rt gate-id)]
                                             (when (attr g :gate/error) g)))]
            (is (= "active" (:state errored)) (str "case " i))
            (is (str/includes? (attr errored :gate/error) expected) (str "case " i))
            (is (str/includes? (attr errored :gate/error)
                               "millhouse.spools.executors.shell/request")
                (str "case " i ": the stamped detail names the request spec"))
            ;; no process ran: no exit code and no captured output
            (is (nil? (attr errored :shell/exit-code)) (str "case " i))
            (is (nil? (attr errored :shell/output)) (str "case " i))))))))

(deftest timeout-kills-process-and-bad-timeout-fails-loudly
  (with-shell
    (fn [rt]
      ;; a command exceeding the wall-clock bound is force-killed and stamped
      (workflow/start! "timeout" (single-gate "timeout" {"shell/argv" ["sh" "-c" "sleep 30"]
                                                         "shell/timeout-secs" 1}) {})
      (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
      (let [gate-id (:id (ready-shell-gate "timeout"))
            errored (await-eventually #(let [g (weaver/show rt gate-id)]
                                         (when (attr g :gate/error) g)))]
        (is (= "active" (:state errored)))
        (is (str/includes? (attr errored :gate/error) "timed out")))
      ;; Time is the behavior under test: a backgrounded descendant inherits the
      ;; output pipe, so the timeout path must still reach a terminal stamp.
      (workflow/start! "timeout-descendant" (single-gate "timeout-descendant" {"shell/argv" ["sh" "-c" "sleep 30 & sleep 30"]
                                                                               "shell/timeout-secs" 1}) {})
      (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
      (let [gate-id (:id (ready-shell-gate "timeout-descendant"))
            errored (await-eventually #(let [g (weaver/show rt gate-id)]
                                         (when (attr g :gate/error) g)))]
        (is (= "active" (:state errored)))
        (is (str/includes? (attr errored :gate/error) "timed out")))
      ;; a non-positive timeout fails loudly with no process
      (workflow/start! "timeout-bad" (single-gate "timeout-bad" {"shell/argv" ["true"]
                                                                 "shell/timeout-secs" 0}) {})
      (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
      (let [gate-id (:id (ready-shell-gate "timeout-bad"))
            errored (await-eventually #(let [g (weaver/show rt gate-id)]
                                         (when (attr g :gate/error) g)))]
        (is (str/includes? (attr errored :gate/error) "shell/timeout-secs"))
        (is (nil? (attr errored :shell/exit-code)))))))

(deftest non-shell-gate-is-ignored-and-output-is-bounded
  (with-shell
    (fn [rt]
      ;; a non-:shell gate is never touched, even carrying shell/* attributes
      (workflow/start! "iso" (workflow/workflow
                              "Iso"
                              (workflow/gate :sub "Delegate" :subagent
                                             :attributes {"shell/argv" ["true"]})) {})
      (let [sub-gate-id (:id (first (workflow/ready "iso")))]
        (shell/scan!)
        (is (= "active" (:state (weaver/show rt sub-gate-id))))
        (is (nil? (attr (weaver/show rt sub-gate-id) :shell/running)))
        (is (nil? (attr (weaver/show rt sub-gate-id) :shell/exit-code))))
      ;; large output is retained only as a bounded tail
      (workflow/start! "big" (single-gate "big" {"shell/argv" ["sh" "-c" "yes 0123456789 | head -c 200000"]}) {})
      (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
      (let [gate-id (:id (shell-gate-strand rt "big"))
            closed (await-eventually #(let [g (weaver/show rt gate-id)]
                                        (when (= "closed" (:state g)) g)))
            output (attr closed :shell/output)]
        (is (zero? (attr closed :shell/exit-code)))
        (is (pos? (count output)))
        (is (<= (count output) @#'shell/output-tail-bytes))
        (is (< (count output) 200000))))))

(deftest dependent-shell-gate-runs-only-after-its-dependency-closes
  (with-shell
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

(deftest state-shape-matches-declared-version
  ;; Drift alarm for the shell executor's versioned spool-state: a key added to new-state
  ;; without a state-version bump would survive refresh as a stale map.
  (test-support/assert-state-shape
   #'shell/new-state
   #{:scan-monitor :worker-executor :close-fn}))

(deftest module-forms-publish-and-preserve-runtime-pool
  (with-redefs [process/list-owned (fn [_ _] [])]
    (with-runtime
      (fn [rt _]
        (test-support/activate-spool! rt :millhouse/spools-workflow 'millhouse.spools.workflow)
        (test-support/activate-spool! rt :millhouse/spools-shell 'millhouse.test-modules.shell-executor
                                      :after [:millhouse/spools-workflow])
        (let [pool (binding [shell/*runtime* rt] (:worker-executor (#'shell/state)))]
          (is (some #(= :shell/engine (:key %)) (events/handlers rt))
              "the graph-change event handler is registered")
          (is (= "shell" (:waiter (first (workflow/executor-catalog)))))
          (is (= shell/stalled-shell-gates
                 [:and [:= :state "active"]
                  [:= [:attr "workflow/gate"] "shell"]
                  [:exists [:attr "gate/error"]]]))
          (test-support/activate-spool! rt :millhouse/spools-shell 'millhouse.test-modules.shell-executor
                                        :after [:millhouse/spools-workflow])
          (is (identical? pool (binding [shell/*runtime* rt] (:worker-executor (#'shell/state))))
              "unchanged refresh preserves the runtime-owned worker pool"))))))
