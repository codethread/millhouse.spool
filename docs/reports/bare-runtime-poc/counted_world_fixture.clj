(require '[clojure.data.json :as json]
         '[clojure.java.io :as io]
         '[millstrand.test.alpha :as test-alpha])

(def ^:private bare-fixture-calls (atom 0))
(def ^:private world-fixture-calls (atom 0))
(def ^:private source-url
  (str (or (io/resource "millstrand/test/alpha.clj")
           (throw (ex-info "Millstrand test fixture source is unavailable" {})))))
(def ^:private count-output
  (or (System/getenv "BARE_RUNTIME_COUNT_OUTPUT")
      (throw (ex-info "BARE_RUNTIME_COUNT_OUTPUT is required" {}))))
(def ^:private original-run-with-weaver-world
  (var-get #'test-alpha/run-with-weaver-world))

(alter-var-root
 #'test-alpha/run-with-weaver-world
 (constantly
  (fn [opts f]
    (swap! world-fixture-calls inc)
    (original-run-with-weaver-world opts f))))

(let [root (-> (test-alpha/spool-checkout-root "millhouse/workflow.clj")
               .getParentFile
               .getParentFile
               .getCanonicalPath)
      fixture-deps-edn
      (pr-str {:paths [(str root "/test")
                       (str root "/spools/workflow/test")]
               :deps {'millhouse/workflow
                      {:local/root (str root "/spools/workflow")}
                      'millhouse/land
                      {:local/root (str root "/spools/land")}}})]
  (alter-var-root
   #'test-alpha/run-with-bare-runtime
   (constantly
    (fn [opts f]
      (swap! bare-fixture-calls inc)
      (test-alpha/run-with-weaver-world
       (cond-> (merge {:deps-edn fixture-deps-edn}
                      (select-keys opts [:storage]))
         (:name opts) (assoc :name (:name opts)))
       f)))))

(.addShutdownHook
 (Runtime/getRuntime)
 (Thread.
  ^Runnable
  #(spit count-output
         (json/write-str {"fixture" "world-control"
                          "source-url" source-url
                          "bare-fixture-invocations" @bare-fixture-calls
                          "world-fixture-invocations" @world-fixture-calls}))))
