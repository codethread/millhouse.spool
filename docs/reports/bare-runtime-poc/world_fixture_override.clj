(require '[millstrand.test.alpha :as test-alpha])

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
      (test-alpha/run-with-weaver-world
       (cond-> (merge {:deps-edn fixture-deps-edn}
                      (select-keys opts [:storage]))
         (:name opts) (assoc :name (:name opts)))
       f)))))
