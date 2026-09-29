(ns millhouse.executors.shell-replacement-test
  "Real built-Mill/Weaver replacement proof; never touches a shared runtime."
  (:require [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [millhouse.test-support :as test-support]
            [millstrand.test.alpha :as test-alpha]))

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
              '[millhouse.workflow.execution :as execution])
     (let [result
           (workflow/start! \"shell-replacement\"
             (workflow/workflow
               \"Shell replacement\"
               (workflow/gate :check \"Run shell check\" :shell
                 :attributes {\"test/run-id\" \"shell-replacement\"
                              \"shell/argv\" [\"sh\" \"-c\" \"printf launch >> "
       release-fifo
       ".launches; IFS= read -r release < "
       release-fifo
       "; printf shell-ok\"]})
               (workflow/step :after \"After\" :self :depends-on [:check]))
             {})]
       result))"))

(defn- shell-gate-probe-form []
  "(do
     (require '[millstrand.api.current.alpha :as current]
              '[millstrand.api.weaver.alpha :as weaver]
              '[millhouse.workflow :as workflow]
              '[millhouse.workflow.execution :as execution])
     (let [rt (current/runtime)
           gate (first (weaver/list rt
                                   [:and
                                    [:= [:attr \"workflow/gate\"] \"shell\"]
                                    [:= [:attr \"test/run-id\"] \"shell-replacement\"]]
                                   {}))]
       {:generation (:generation-id rt)
        :gate (select-keys gate [:id :state :attributes])
        :execution (when gate (execution/inspect rt {:run-id \"shell-replacement\" :step (:id gate)}))
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
                    (when (get-in probe [:execution :reference])
                      probe))
                 {:timeout-ms (test-support/await-budget-ms)
                  :interval-ms 100
                  :on-timeout
                  (fn []
                    (throw (ex-info "Shell gate was not claimed"
                                    {:started @started-result
                                     :probe @last-probe})))})]
            (is (some? (get-in running [:execution :reference]))
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
              (is (= (select-keys (:execution running)
                                  [:attempt-id :reference])
                     (select-keys (:execution adopted)
                                  [:attempt-id :reference])))
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
                (is (= "shell-ok" (get-in after [:execution :result :value :output])))
                (is (= "launch" (slurp (str release-fifo ".launches")))
                    "the command started only once across Weaver replacement"))))))
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

