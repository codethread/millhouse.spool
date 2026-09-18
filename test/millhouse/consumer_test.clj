(ns millhouse.consumer-test
  "Exercise the consolidated family through a disposable consumer workspace."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [millhouse.test-support :as test-support]
            [millstrand.test.alpha :as test-alpha]))

(defn- repository-root []
  (-> (test-alpha/spool-checkout-root "millhouse/spools/workflow.clj")
      .getParentFile
      .getParentFile
      .getCanonicalPath))

(defn- consumer-deps-edn []
  (pr-str
   {:deps
    {'millhouse.spools/workflow {:local/root (str (repository-root) "/spools/workflow")}
     'millhouse.spools/chime {:local/root (str (repository-root) "/spools/chime")}
     'millhouse.spools/cron {:local/root (str (repository-root) "/spools/cron")}
     'millhouse.spools/land {:local/root (str (repository-root) "/spools/land")}
     'millhouse.spools/kanban {:local/root (str (repository-root) "/spools/kanban")}}}))

(defn- land-only-consumer-deps-edn []
  (pr-str
   {:deps
    {'millhouse.spools/land {:local/root (str (repository-root) "/spools/land")}}}))

(def ^:private land-only-init
  "(require '[millstrand.api.current.alpha :as current]
            '[millstrand.api.runtime.alpha :as runtime])
   (let [rt (current/runtime)]
     (runtime/module! rt :consumer/workflow
       {:ns 'millhouse.spools.workflow
        :required? true})
     (runtime/module! rt :consumer/workflow-providers
       {:ns 'millhouse.spools.workflow.spool
        :after [:consumer/workflow]
        :required? true})
     (runtime/module! rt :consumer/kanban
       {:ns 'millhouse.spools.kanban
        :required? true})
     (runtime/module! rt :consumer/land
       {:ns 'millhouse.spools.land.spool
        :after [:consumer/workflow-providers :consumer/kanban]
        :required? true}))")

(def ^:private init
  "(require '[millstrand.api.current.alpha :as current]
            '[millstrand.api.runtime.alpha :as runtime]
            '[millhouse.test-support :as test-support])
   (test-support/with-module-activation
     #(do
        (def rt (current/runtime))
        (runtime/module! rt :millhouse/workflow
          {:ns 'millhouse.spools.workflow
           :required? true})
        (runtime/module! rt :millhouse/workflow-all
          {:ns 'millhouse.spools.workflow.spool
           :after [:millhouse/workflow]
           :required? true})
        (runtime/module! rt :millhouse/chime
          {:ns 'millhouse.spools.chime
           :required? true})
        (runtime/module! rt :millhouse/cron
          {:ns 'millhouse.spools.cron
           :required? true})
        (runtime/module! rt :millhouse/kanban
          {:ns 'millhouse.spools.kanban
           :required? true})
        (runtime/module! rt :millhouse/land
          {:ns 'millhouse.spools.land.spool
           :after [:millhouse/workflow-all :millhouse/kanban]
           :required? true})))")

(deftest family-syncs-activates-and-publishes-all-roots
  (test-alpha/with-weaver-world
    [ctx {:deps-edn (consumer-deps-edn)
          :init-clj init}]
    (let [{:keys [status op-names glossary-outcomes workflow-names]}
          (test-alpha/repl!
           ctx
           '(do
              (require '[millhouse.spools.workflow :as workflow]
                       '[millstrand.api.current.alpha :as current]
                       '[millstrand.api.runtime.alpha :as runtime]
                       '[millstrand.api.runtime.glossary.alpha :as glossary]
                       '[millstrand.api.weaver.alpha :as weaver])
              (let [rt (current/runtime)]
                {:status (runtime/status rt)
                 :op-names (set (map :name (weaver/ops rt)))
                 :glossary-outcomes (set (map :name (glossary/glossary-outcomes rt)))
                 :workflow-names (set (keys (workflow/workflows)))})))
          outcomes (:modules status)]
      (is (= #{:millhouse/workflow
               :millhouse/workflow-all
               :millhouse/chime
               :millhouse/cron
               :millhouse/kanban
               :millhouse/land}
             (set (keys outcomes))))
      (is (= #{'millhouse.spools.workflow
               'millhouse.spools.workflow.spool
               'millhouse.spools.chime
               'millhouse.spools.cron
               'millhouse.spools.kanban
               'millhouse.spools.land.spool}
             (set (map :ns (vals outcomes)))))
      (is (contains? op-names "workflow"))
      (is (contains? op-names "merge-queue"))
      (is (contains? glossary-outcomes "workflow/ready-next-absent"))
      (is (= [:millhouse/workflow]
             (get-in status [:modules :millhouse/workflow-all :after])))
      (is (contains? workflow-names :publish-spool-kondo))
      (is (every? workflow-names [:land :land-merge :land-abort])))))

(deftest land-only-consumer-resolves-and-activates-transitive-siblings
  (test-alpha/with-weaver-world
    [ctx {:deps-edn (land-only-consumer-deps-edn)
          :init-clj land-only-init}]
    (let [{:keys [modules workflows op-names]}
          (test-alpha/repl!
           ctx
           '(do
              (require '[millhouse.spools.workflow :as workflow]
                       '[millstrand.api.current.alpha :as current]
                       '[millstrand.api.runtime.alpha :as runtime]
                       '[millstrand.api.weaver.alpha :as weaver])
              (let [rt (current/runtime)]
                {:modules (set (keys (:modules (runtime/status rt))))
                 :workflows (set (keys (workflow/workflows)))
                 :op-names (set (map :name (weaver/ops rt)))})))]
      (is (= #{:consumer/workflow
               :consumer/workflow-providers
               :consumer/kanban
               :consumer/land}
             modules))
      (is (every? workflows [:review :land :land-merge :land-abort]))
      (is (contains? op-names "merge-queue")))))

(deftest repository-kondo-config-keeps-producer-ownership
  (let [root (io/file (repository-root))
        config-text (slurp (io/file root ".clj-kondo/config.edn"))
        project-hooks (slurp (io/file root ".clj-kondo/repo-policy/hooks/project_rules.clj"))
        config (edn/read-string config-text)]
    (is (= ["repo-policy"] (:config-paths config)))
    (is (not (.exists (io/file root ".lsp/config.edn"))))
    (doseq [form '[defop defquery defpattern defhook defhandler defbin]]
      (is (not (re-find (re-pattern (str "millstrand.api.millstrand.alpha/" form))
                        config-text)))
      (is (not (str/includes? project-hooks (str "(defn " form)))))
    (is (not (re-find #"millstrand\.macros\.(queries|ops|rules)" config-text)))
    (doseq [artifact ["io.millstrand/millstrand"
                      "millhouse.spools/chime"
                      "millhouse.spools/cron"
                      "millhouse.spools/land"
                      "millhouse.spools/workflow"]]
      (is (.isFile (io/file root ".clj-kondo/imports" artifact "config.edn"))))))

(def ^:private portable-consumer-source
  "A consumer source exercising every imported authoring-form family."
  "(ns consumer.forms
     \"A portable consumer's authoring forms.\"
     (:require [millstrand.api.lifecycle.alpha :as lifecycle]
               [millstrand.api.millstrand.alpha :as millstrand]
               [millstrand.test.alpha :as test-alpha]
               [millhouse.spools.workflow :as workflow]
               [millhouse.spools.chime :as chime]
               [millhouse.spools.cron :as cron]))

   (defn sample-job-handler [_] nil)

   (defn sample-lifecycle-call [_] nil)

   (lifecycle/defseed sample-seed
     \"A sample seed.\"
     {:apply 'consumer.forms/sample-lifecycle-call})

   (lifecycle/defresource sample-resource
     \"A sample resource.\"
     {:open 'consumer.forms/sample-lifecycle-call
      :close 'consumer.forms/sample-lifecycle-call})

   (lifecycle/defreconcile sample-reconcile
     \"A sample reconciliation.\"
     {:read-desired 'consumer.forms/sample-lifecycle-call
      :read-actual 'consumer.forms/sample-lifecycle-call
      :apply 'consumer.forms/sample-lifecycle-call
      :on-removed 'consumer.forms/sample-lifecycle-call})

   (test-alpha/with-weaver-world [ctx {}]
     (str ctx))

   (millstrand/defop sample-op
     \"A sample operation.\"
     {:arg-spec {:op \"sample-op\"
                 :doc \"Run the sample operation.\"
                 :hook-class :read
                 :deadline-class :standard}}
     [_]
     nil)

   (millstrand/defquery sample-query
     \"A sample query.\"
     {}
     [:= [:attr :sample] true])

   (millstrand/defpattern sample-pattern
     \"A sample pattern.\"
     {:spec ::sample-pattern}
     [_]
     nil)

   (millstrand/defhook sample-hook
     \"A sample hook.\"
     {:types #{:strand/add-before-commit}}
     [_]
     nil)

   (millstrand/defhandler sample-handler
     \"A sample event handler.\"
     {:types #{:strand/added}}
     [_]
     nil)

   (millstrand/defbin sample-bin
     \"A sample executable.\"
     {:executable \"sample-bin\"})

   (workflow/defworkflow sample-workflow
     (str \"A sample \" \"workflow.\")
     {:entrypoints #{:start} :defaults {}}
     (workflow/workflow
       (fn [_] \"done\")
       (workflow/step :done \"Done\" :self)))

   (workflow/defexecutor sample-executor
     \"A sample executor.\"
     {}
     [_]
     nil)

   (chime/defrule sample-rule
     \"A sample Chime rule.\"
     [_]
     nil)

   (cron/defjob sample-job \"Sample job.\"
     {:interval-ms 1000
      :handler 'consumer.forms/sample-job-handler})

   (workflow/use-workflow! sample-workflow)
   (workflow/use-executor! sample-executor-stalled?)
   (chime/use-rule! sample-rule-rule)
   (cron/use-job! sample-job)

   (workflow/defworkflow! sample-workflow-bang
     \"A selected sample workflow.\"
     {:entrypoints #{:start} :defaults {}}
     (workflow/workflow
       (fn [_] \"done\")
       (workflow/step :done \"Done\" :self)))

   (workflow/defexecutor! sample-executor-bang
     \"A selected sample executor.\"
     {}
     [_]
     nil)

   (chime/defrule! sample-rule-bang
     \"A selected sample Chime rule.\"
     [_]
     nil)

   (cron/defjob! sample-job-bang \"A selected sample job.\"
     {:interval-ms 1000
      :handler 'consumer.forms/sample-job-handler})")

(defn- write-file! [^java.io.File file content]
  (.mkdirs (.getParentFile file))
  (spit file content)
  file)

(defn- portable-consumer-deps-edn [root]
  {:paths ["src"]
   :deps
   {'millhouse.spools/workflow
    {:local/root (.getCanonicalPath (io/file root "spools/workflow"))}
    'millhouse.spools/chime
    {:local/root (.getCanonicalPath (io/file root "spools/chime"))}
    'millhouse.spools/cron
    {:local/root (.getCanonicalPath (io/file root "spools/cron"))}
    'millhouse.spools/land
    {:local/root (.getCanonicalPath (io/file root "spools/land"))}}})

(defn- run-consumer-command [dir command]
  (sh/sh "sh" "-c" command :dir (.getPath dir)))

(defn- kondo-files [dir]
  (mapv #(.getPath ^java.io.File %)
        (file-seq (io/file dir ".clj-kondo"))))

(deftest portable-consumer-imports-and-lints-published-forms
  (testing "a temp Tools.deps consumer imports and lints owner exports"
    (let [root (io/file (repository-root))
          consumer (test-support/temp-dir "millhouse-portable-consumer")
          kondo-config (io/file consumer ".clj-kondo/config.edn")
          deps-file (io/file consumer "deps.edn")
          source-file (io/file consumer "src/consumer/forms.clj")]
      (try
        (write-file! kondo-config "{}")
        (write-file! deps-file (pr-str (portable-consumer-deps-edn root)))
        (write-file! source-file portable-consumer-source)
        (let [import-result
              (run-consumer-command
               consumer
               (str "classpath=\"$(clojure -Srepro -Spath)\" && "
                    "clj-kondo --repro --lint \"$classpath\" "
                    "--copy-configs --skip-lint"))
              expected-imports
              ["io.millstrand/millstrand/config.edn"
               "io.millstrand/millstrand/hooks/millstrand.clj"
               "millhouse.spools/workflow/config.edn"
               "millhouse.spools/workflow/hooks/millhouse/spools/workflow.clj_kondo"
               "millhouse.spools/chime/config.edn"
               "millhouse.spools/chime/hooks/millhouse/spools/chime.clj_kondo"
               "millhouse.spools/cron/config.edn"
               "millhouse.spools/land/config.edn"]
              lint-result (run-consumer-command
                           consumer "clj-kondo --repro --parallel --lint src")]
          (is (zero? (:exit import-result))
              (str "dependency import failed:\n" (:err import-result)))
          (doseq [relative-path expected-imports]
            (is (.isFile (io/file consumer ".clj-kondo/imports" relative-path))
                (str "missing copied import: " relative-path
                     "; import output: " (:out import-result)
                     "; actual: " (kondo-files consumer))))
          (is (zero? (:exit lint-result))
              (str "consumer lint failed:\n" (:out lint-result) (:err lint-result))))
        (finally
          (test-support/delete-tree! consumer))))))
