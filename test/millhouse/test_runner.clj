(ns millhouse.test-runner
  "Repository test entrypoint with parallel, serial, focused, and stress modes."
  (:require [clojure.string :as str]
            [clojure.test :as test])
  (:import [java.io StringWriter]
           [java.util.concurrent Callable Executors ExecutorService TimeUnit]))

(def test-namespaces
  "All test namespaces, in stable reporting order."
  '[millhouse.authoring-forms-test
    millhouse.consumer-test
    millhouse.executor-discovery-test
    millhouse.spools.workflow-test
    millhouse.spools.workflow-cli-test
    millhouse.spools.workflow-run-cli-test
    millhouse.chime-test
    millhouse.spools.cron.runtime-test
    millhouse.e2e.cron.lifecycle-test
    millhouse.spools.executors.code-test
    millhouse.spools.executors.shell-test
    millhouse.spools.kanban-test
    millhouse.spools.millstrand-workflows-test
    millhouse.spools.millstrand-workflows.bootstrap-kondo-test
    millhouse.spools.millstrand-workflows.bump-spool-test
    millhouse.spools.millstrand-workflows.bump-millstrand-test])

(def serial-namespaces
  "Namespaces proven to require a JVM-global serial island."
  #{'millhouse.spools.executors.code-test
    'millhouse.spools.kanban-test})

(defn- initial-summary [] test/*initial-report-counters*)

(defn- merge-summaries [summaries]
  (apply merge-with + (initial-summary) (map #(dissoc % :type) summaries)))

(defn- run-namespace [group ns-sym]
  (let [output (StringWriter.)
        started (System/nanoTime)]
    (binding [test/*report-counters* (ref (initial-summary))
              test/*test-out* output]
      (let [summary (try
                      (test/run-tests ns-sym)
                      (catch Throwable throwable
                        (test/do-report
                         {:type :error
                          :message (str "Uncaught exception while running " ns-sym)
                          :expected nil
                          :actual throwable})
                        @test/*report-counters*))]
        {:group group
         :ns ns-sym
         :summary summary
         :elapsed-ms (long (/ (- (System/nanoTime) started) 1000000))
         :output (str output)}))))

(defn- run-serial [group namespaces]
  (mapv #(run-namespace group %) namespaces))

(defn- pool-size [namespace-count]
  (max 1 (min namespace-count (.availableProcessors (Runtime/getRuntime)))))

(defn- run-parallel [namespaces]
  (if (empty? namespaces)
    []
    (let [^ExecutorService pool (Executors/newFixedThreadPool (pool-size (count namespaces)))]
      (try
        (->> namespaces
             (mapv #(.submit pool ^Callable (fn [] (run-namespace :parallel %))))
             (mapv #(.get %)))
        (finally
          (.shutdown pool)
          (.awaitTermination pool 1 TimeUnit/MINUTES))))))

(defn- run-once [mode namespaces]
  ;; Loading namespaces concurrently can race Clojure's first-load machinery.
  ;; Require serially so parallel mode tests runtime isolation rather than the
  ;; namespace loader.
  (run! require namespaces)
  (if (= :serial mode)
    (run-serial :serial namespaces)
    (let [serial-set (set serial-namespaces)]
      (concat (run-serial :serial-island (filterv serial-set namespaces))
              (run-parallel (filterv (complement serial-set) namespaces))))))

(defn- print-result! [{:keys [group summary elapsed-ms output] ns-sym :ns}]
  (print output)
  (when-not (or (str/blank? output) (str/ends-with? output "\n"))
    (println))
  (println "Namespace summary:" ns-sym
           (assoc (dissoc summary :type) :group group :elapsed-ms elapsed-ms)))

(defn- parse-positive-int [value]
  (let [parsed (try
                 (parse-long value)
                 (catch Throwable _ nil))]
    (when-not (and parsed (pos? parsed))
      (throw (ex-info "--stress requires a positive integer"
                      {:value value})))
    parsed))

(defn- parse-args [args]
  (cond
    (empty? args)
    {:mode :parallel :iterations 1 :namespaces test-namespaces}

    (= "--serial" (first args))
    (if (= 1 (count args))
      {:mode :serial :iterations 1 :namespaces test-namespaces}
      (throw (ex-info "--serial accepts no other arguments" {:args args})))

    (= "--stress" (first args))
    (if (= 2 (count args))
      {:mode :stress
       :iterations (parse-positive-int (second args))}
      (throw (ex-info "Usage: clojure -M:test --stress <positive-iterations>"
                      {:args args})))

    (= ["--parallel-once"] args)
    {:mode :parallel :iterations 1 :namespaces test-namespaces}

    (some #(str/starts-with? % "--") args)
    (throw (ex-info "Unknown test-runner arguments" {:args args}))

    :else
    (let [namespaces (mapv symbol args)
          known (set test-namespaces)
          unknown (filterv (complement known) namespaces)
          duplicates (filterv (fn [[_ count]] (< 1 count)) (frequencies namespaces))]
      (when (seq unknown)
        (throw (ex-info "Unknown test namespace"
                        {:unknown unknown :known test-namespaces})))
      (when (seq duplicates)
        (throw (ex-info "Duplicate test namespaces"
                        {:duplicates (mapv first duplicates)})))
      {:mode :serial :iterations 1 :namespaces namespaces})))

(defn- failed? [summary]
  (pos? (+ (:fail summary) (:error summary))))

(defn- java-command []
  [(str (System/getProperty "java.home") java.io.File/separator "bin"
        java.io.File/separator "java")
   "--enable-native-access=ALL-UNNAMED"
   "-cp" (System/getProperty "java.class.path")
   "clojure.main" "-m" "millhouse.test-runner" "--parallel-once"])

(defn- run-stress [iterations]
  ;; A fresh JVM per iteration prevents one run's loaded fixture namespaces and
  ;; global Clojure state from contaminating the next. Parallelism is stressed
  ;; within each child; every iteration still runs even after an earlier failure.
  (let [exit-codes
        (mapv (fn [iteration]
                (println "\n=== Stress iteration" iteration "of" iterations "===")
                (flush)
                (-> (ProcessBuilder. ^java.util.List (java-command))
                    (.inheritIO)
                    (.start)
                    (.waitFor)))
              (range 1 (inc iterations)))]
    (when (some pos? exit-codes)
      (System/exit 1))))

(defn- run-tests! [mode namespaces]
  (let [results (vec (run-once mode namespaces))
        summary (merge-summaries (map :summary results))]
    (doseq [result results]
      (print-result! result))
    (println "Aggregate summary:" (dissoc summary :type))
    ;; Tests use futures; release their shared executor threads after every
    ;; namespace has reported so a successful process exits promptly.
    (shutdown-agents)
    (when (failed? summary)
      (System/exit 1))))

(defn -main [& args]
  (let [{:keys [mode iterations namespaces]} (parse-args args)]
    (if (= :stress mode)
      (run-stress iterations)
      (run-tests! mode namespaces))))
