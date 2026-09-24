(ns ct.spools.codethread.shared-landing-consumer-smoke
  "Exercise local shared landing source through consumer workspace configuration.

  Every consumer runs in a disposable Weaver world. The fixture preserves its
  checked-in init and workspace files while replacing published coordinates
  with explicit local checkout roots supplied by the caller. This smoke covers
  consumer integration with the supplied source, not the checked-in pins."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.test.alpha :as t]))

(defn- canonical-directory! [label path]
  (let [directory (.getCanonicalFile (io/file path))]
    (when-not (.isDirectory directory)
      (throw (ex-info (str label " must be a directory")
                      {:label label :path path})))
    directory))

(defn- local-root [root relative-path]
  {:local/root (.getCanonicalPath (io/file root relative-path))})

(defn- normalize-consumer-deps [deps consumer]
  (update deps :deps
          (fn [coordinates]
            (into {}
                  (map (fn [[library coordinate]]
                         [library
                          (if-let [path (:local/root coordinate)]
                            (local-root (io/file consumer ".millstrand") path)
                            coordinate)]))
                  coordinates))))

(defn- local-overrides [millhouse]
  {'millhouse.spools/auto-run (local-root millhouse "spools/auto-run")
   'millhouse.spools/workflow (local-root millhouse "spools/workflow")
   'millhouse.spools/identity (local-root millhouse "spools/identity")
   'millhouse.spools/kanban (local-root millhouse "spools/kanban")
   'millhouse.spools/land (local-root millhouse "spools/land")
   'ct.spools/harnesses (local-root millhouse "spools/harnesses")
   'codethread/config (local-root millhouse "spools/config")
   'codethread/devflow (local-root millhouse "spools/devflow")
   'codethread/devflow-kanban-adapter
   (local-root millhouse "spools/devflow/kanban-adapter")})

(defn- fixture-deps [roots consumer]
  (let [deps-file (io/file consumer ".millstrand/deps.edn")]
    (when-not (.isFile deps-file)
      (throw (ex-info "Consumer has no .millstrand/deps.edn"
                      {:consumer (.getPath consumer)})))
    (-> (edn/read-string (slurp deps-file))
        (normalize-consumer-deps consumer)
        (update :deps merge (local-overrides roots))
        pr-str)))

(defn- fixture-files [consumer]
  (let [workspace (.getCanonicalFile (io/file consumer ".millstrand"))]
    (into {}
          (comp
           (mapcat (fn [directory]
                     (let [root (io/file workspace directory)]
                       (if (.isDirectory root) (file-seq root) []))))
           (filter #(.isFile ^java.io.File %))
           (map (fn [file]
                  [(str (.relativize (.toPath workspace) (.toPath file)))
                   (slurp file)])))
          ["config" "me" "workflows"])))

(defn- require-smoke! [valid? message data]
  (when-not valid?
    (throw (ex-info message data))))

(defn- smoke-consumer! [roots consumer]
  (let [init-file (io/file consumer ".millstrand/init.clj")]
    (when-not (.isFile init-file)
      (throw (ex-info "Consumer has no .millstrand/init.clj"
                      {:consumer (.getPath consumer)})))
    (t/run-with-weaver-world
     {:storage :sqlite-memory
      :deps-edn (fixture-deps roots consumer)
      :init-clj (slurp init-file)
      :files (fixture-files consumer)}
     (fn [{:keys [runtime]}]
       (let [consumer-path (.getPath consumer)
             op-names (set (map :name (weaver/ops runtime)))
             workflow-names (set (map :name (:definitions
                                             (weaver/op! runtime 'workflow ["list"]))))]
         (weaver/op! runtime 'merge-queue ["status"])
         (require-smoke! (contains? op-names "merge-queue")
                         "merge-queue operation is not visible"
                         {:consumer consumer-path :operations op-names})
         (require-smoke! (every? workflow-names ["review" "land"])
                         "shared review and land workflows are not listed"
                         {:consumer consumer-path :workflows workflow-names})
         (doseq [workflow-name ["review" "land"]]
           (require-smoke!
            (= workflow-name
               (:name (weaver/op! runtime 'workflow ["show" workflow-name])))
            "Shared workflow is not visible"
            {:consumer consumer-path :workflow workflow-name}))
         (println "shared landing local-source smoke: clean"
                  consumer-path))))))

(defn -main
  "Exercise each consumer using disposable local dependency overrides.

  Arguments are MILLHOUSE followed by one or more consumer checkout roots.
  This command does not verify published pins; scripts/verify-distribution.sh
  first resolves the remote revision in an isolated consumer."
  [& paths]
  (when (< (count paths) 2)
    (throw (ex-info "Usage: shared-landing-consumer-smoke MILLHOUSE CONSUMER..."
                    {:arguments paths})))
  (let [[millhouse & consumers] paths
        root (canonical-directory! "MILLHOUSE" millhouse)]
    (doseq [consumer consumers]
      (smoke-consumer! root (canonical-directory! "CONSUMER" consumer))))
  (shutdown-agents))
