(ns millhouse.consumer-test
  "Exercise the six-root family through a disposable consumer workspace."
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

(def ^:private roots
  {'millhouse.spools/workflow "spools/workflow"
   'millhouse.spools/chime "spools/chime"
   'millhouse.spools/cron "spools/cron"
   'millhouse.spools.executors/code "spools/code-executor"
   'millhouse.spools.executors/shell "spools/shell-executor"
   'millhouse.spools/kanban "spools/kanban"
   'millhouse.spools/millstrand-workflows "spools/millstrand-workflows"})

(def ^:private init
  "(require '[millstrand.api.current.alpha :as current]
            '[millstrand.api.runtime.alpha :as runtime]
            '[millhouse.test-support :as test-support])
   (test-support/with-module-activation
     #(do
        (def rt (current/runtime))
        (runtime/module! rt :millhouse/workflow
          {:ns 'millhouse.spools.workflow
           :spools ['millhouse.spools/workflow]
           :required? true})
        (runtime/module! rt :millhouse/workflow-cli
          {:ns 'millhouse.spools.workflow.cli
           :spools ['millhouse.spools/workflow]
           :after [:millhouse/workflow]
           :required? true})
        (runtime/module! rt :millhouse/chime
          {:ns 'millhouse.spools.chime
           :spools ['millhouse.spools/chime]
           :required? true})
        (runtime/module! rt :millhouse/cron
          {:ns 'millhouse.spools.cron
           :spools ['millhouse.spools/cron]
           :required? true})
        (runtime/module! rt :millhouse/code-executor
          {:ns 'millhouse.spools.executors.code
           :spools ['millhouse.spools.executors/code
                    'millhouse.spools/workflow]
           :after [:millhouse/workflow]
           :required? true})
        (runtime/module! rt :millhouse/shell-executor
          {:ns 'millhouse.spools.executors.shell
           :spools ['millhouse.spools.executors/shell
                    'millhouse.spools/workflow]
           :after [:millhouse/workflow]
           :required? true})
        (runtime/module! rt :millhouse/kanban
          {:ns 'millhouse.spools.kanban
           :spools ['millhouse.spools/kanban]
           :required? true})
        (runtime/module! rt :millhouse/millstrand-workflows
          {:ns 'millhouse.spools.millstrand-workflows
           :spools ['millhouse.spools/millstrand-workflows
                    'millhouse.spools/workflow]
           :after [:millhouse/workflow]
           :required? true})))")

(deftest family-syncs-activates-and-publishes-all-roots
  (test-alpha/with-weaver-world
    [ctx {:spools-edn {:spools {'millhouse/spools
                                {:local/root (repository-root)
                                 :roots roots}}}
          :init init}]
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
          outcomes (:module/outcomes status)]
      (is (= #{:applied}
             (set (map :status (vals outcomes)))))
      (is (= #{:millhouse/workflow
               :millhouse/workflow-cli
               :millhouse/chime
               :millhouse/cron
               :millhouse/code-executor
               :millhouse/shell-executor
               :millhouse/kanban
               :millhouse/millstrand-workflows}
             (set (keys outcomes))))
      (is (contains? op-names "workflow"))
      (is (contains? glossary-outcomes "workflow/ready-next-absent"))
      (is (= :applied
             (get-in status [:lifecycle/outcomes :millhouse/workflow-cli
                             :workflow-glossary-seed :status])))
      (is (contains? workflow-names :publish-spool-kondo)))))

(deftest repository-kondo-config-keeps-producer-ownership
  (let [root (io/file (repository-root))
        config-text (slurp (io/file root ".clj-kondo/config.edn"))
        lsp-config (edn/read-string (slurp (io/file root ".lsp/config.edn")))
        project-hooks (slurp (io/file root ".clj-kondo/hooks/project_rules.clj"))
        config (edn/read-string config-text)
        config-paths (:config-paths config)
        self-imports (io/file root ".clj-kondo/imports/millhouse.spools")]
    (is (= ["imports/io.millstrand/millstrand"
            "../spools/workflow/resources/clj-kondo.exports/millhouse.spools/workflow"
            "../spools/chime/resources/clj-kondo.exports/millhouse.spools/chime"
            "../spools/cron/resources/clj-kondo.exports/millhouse.spools/cron"]
           config-paths))
    (doseq [form '[defop defquery defpattern defhook defhandler defbin]]
      (is (not (re-find (re-pattern (str "millstrand.api.millstrand.alpha/" form))
                        config-text)))
      (is (not (str/includes? project-hooks (str "(defn " form)))))
    (is (not (re-find #"millstrand\.macros\.(queries|ops|rules)" config-text)))
    (is (false? (:copy-kondo-configs? lsp-config)))
    (is (.isFile (io/file root ".clj-kondo/imports/io.millstrand/millstrand/config.edn")))
    (is (not (.exists self-imports)))))

(def ^:private resolved-spool-roots
  "Installed spool roots that status must contribute to a consumer classpath."
  ["spools/workflow" "spools/chime" "spools/cron"])

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

   (workflow/defworkflow! sample-workflow
     \"A sample workflow.\"
     {:entrypoints #{:start} :defaults {}}
     (workflow/workflow
       (fn [_] \"done\")
       (workflow/step :done \"Done\" :self)))

   (workflow/defexecutor! sample-executor
     \"A sample executor.\"
     {}
     [_]
     nil)

   (chime/defrule! sample-rule
     \"A sample Chime rule.\"
     [_]
     nil)

   (cron/defjob! sample-job \" Sample job. \"
     {:interval-ms 1000
      :handler 'consumer.forms/sample-job-handler})")

(defn- write-file! [^java.io.File file content]
  (.mkdirs (.getParentFile file))
  (spit file content)
  file)

(defn- portable-consumer-deps-edn [millstrand-dep]
  {:paths []
   :deps {'io.millstrand/millstrand millstrand-dep}})

(defn- resolved-spool-classpath [root]
  (->> resolved-spool-roots
       (mapcat (fn [root-name]
                 (let [root-dir (io/file root root-name)
                       deps (edn/read-string (slurp (io/file root-dir "deps.edn")))]
                   (map #(.getCanonicalPath (io/file root-dir %))
                        (if (contains? deps :paths)
                          (:paths deps)
                          ["src"])))))
       (str/join java.io.File/pathSeparator)))

(defn- portable-consumer-bin! [dir]
  (doto (write-file!
         (io/file dir "clj-kondo")
         (str "#!/bin/sh\n"
              "exec clojure -Sdeps '{:deps {clj-kondo/clj-kondo "
              "{:mvn/version \"2025.06.05\"}}}' -M -m clj-kondo.main \"$@\"\n"))
    (.setExecutable true)))

(defn- shell-env-with-bin [bin-dir]
  (let [path (or (System/getenv "PATH") "")]
    (assoc (into {} (System/getenv)) "PATH" (str (.getPath bin-dir) ":" path))))

(defn- run-consumer-command [dir bin-dir command]
  (sh/sh "sh" "-c" command
         :dir (.getPath dir)
         :env (shell-env-with-bin bin-dir)))

(defn- kondo-files [dir]
  (mapv #(.getPath ^java.io.File %)
        (file-seq (io/file dir ".clj-kondo"))))

(deftest portable-consumer-imports-and-lints-published-forms
  (testing "a temp Tools.deps consumer imports and lints owner exports"
    (let [root (io/file (repository-root))
          root-deps (edn/read-string (slurp (io/file root "deps.edn")))
          millstrand-dep (get-in root-deps [:deps 'io.millstrand/millstrand])
          consumer (test-support/temp-dir "millhouse-portable-consumer")
          bin-dir (io/file consumer "bin")
          kondo-config (io/file consumer ".clj-kondo/config.edn")
          deps-file (io/file consumer "deps.edn")
          source-file (io/file consumer "src/consumer/forms.clj")
          spool-classpath (resolved-spool-classpath root)]
      (try
        (is (= "e0ca975cd0d2d546249f63c9d699ae33e1c9b688"
               (:git/sha millstrand-dep)))
        (portable-consumer-bin! bin-dir)
        (write-file! kondo-config "{}")
        (write-file! deps-file
                     (pr-str (portable-consumer-deps-edn millstrand-dep)))
        (write-file! source-file portable-consumer-source)
        (let [import-result
              (run-consumer-command
               consumer bin-dir
               (str "clj-kondo --lint \"" spool-classpath
                    java.io.File/pathSeparator
                    "$(clojure -Spath)\" --dependencies --parallel"
                    " --copy-configs --skip-lint"))
              expected-imports
              ["io.millstrand/millstrand/config.edn"
               "io.millstrand/millstrand/hooks/millstrand.clj"
               "millhouse.spools/workflow/config.edn"
               "millhouse.spools/workflow/hooks/millhouse/spools/workflow.clj_kondo"
               "millhouse.spools/chime/config.edn"
               "millhouse.spools/chime/hooks/millhouse/spools/chime.clj_kondo"
               "millhouse.spools/cron/config.edn"]
              lint-result (run-consumer-command consumer bin-dir
                                                "clj-kondo --lint src --parallel")]
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
