(ns quality.affected
  "Plan affected tests from Git changes and declared package dependencies.

  This is repository tooling, not a runtime dependency or activation graph."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.set :as set]
            [clojure.string :as str]
            [millhouse.test-runner :as runner]))

(def ^:private package-targets
  '{millhouse/harnesses "harnesses-check"
    millhouse/devflow "devflow-check"
    millhouse/devflow-kanban-adapter "devflow-check"
    millhouse/config "config-check"
    workspace "workspace-test"})

(defn- git! [directory & args]
  (let [{:keys [exit out err]} (apply sh/sh "git" (concat args [:dir directory]))]
    (when-not (zero? exit)
      (throw (ex-info "Cannot determine affected tests from Git"
                      {:args args :exit exit :stderr err})))
    out))

(defn changes
  "Return the merge-base and changed paths, including dirty and untracked files.

  Disable rename detection so both sides of moves select their original owners.
  A missing base is an error, never an empty or silently full test selection."
  [directory base]
  (let [revision (str/trim (git! directory "rev-parse" "--verify"
                                 (str base "^{commit}")))
        merge-base (str/trim (git! directory "merge-base" revision "HEAD"))
        paths (str (git! directory "diff" "--name-only" "--no-renames" "-z"
                         merge-base "--")
                   (git! directory "ls-files" "--others" "--exclude-standard" "-z"))]
    {:base base :merge-base merge-base
     :paths (vec (sort (set (remove str/blank? (str/split paths #"\u0000")))))}))

(defn- read-edn [directory path]
  (edn/read-string (slurp (io/file directory path))))

(defn- owner [roots path]
  (->> roots
       (sort-by (comp - count val))
       (some (fn [[library root]]
               (when (str/starts-with? path (str root "/")) library)))))

(defn- declared-deps [roots root manifest]
  (let [test-alias (get-in manifest [:aliases :test])
        paths (for [path (:extra-paths test-alias)]
                (str (.normalize (.toPath (io/file root path)))))]
    (set (concat (filter (set (keys roots))
                         (keys (merge (:deps manifest) (:extra-deps test-alias)
                                      (:replace-deps test-alias))))
                 (keep #(owner roots (str % "/")) paths)))))

(defn repository
  "Read package ownership and test dependencies from this checkout's manifests.

  Include test-alias dependencies and nested test paths without changing any
  spool's published dependency graph. Workspace-reading Config tests are an
  explicit repository-only integration edge."
  [directory]
  (let [roots (assoc (update-vals (:roots (read-edn directory "spool.edn")) :root)
                     'workspace ".millstrand")
        graph (into {} (for [[library root] roots]
                         [library (disj (declared-deps
                                         roots root (read-edn directory (str root "/deps.edn")))
                                        library)]))
        integrations {'millhouse.authoring-forms-test
                      '#{millhouse/workflow millhouse/chime millhouse/cron}
                      'millhouse.consumer-test
                      '#{millhouse/workflow millhouse/land millhouse/chime millhouse/cron}
                      'millhouse.executor-discovery-test '#{millhouse/workflow}
                      'millhouse.e2e.cron.lifecycle-test '#{millhouse/cron}
                      'millhouse.package-layout-test (disj (set (keys roots)) 'workspace)
                      'millhouse.affected-test #{}}
        tests (into {} (for [ns-sym runner/test-namespaces]
                         (let [path (str "test/" (-> (str ns-sym)
                                                     (str/replace "." "/")
                                                     (str/replace "-" "_")) ".clj")
                               owners (or (get integrations ns-sym)
                                          (some (fn [[library root]]
                                                  (when (.isFile (io/file directory root path))
                                                    #{library}))
                                                roots))]
                           (when-not owners
                             (throw (ex-info "Test namespace has no affected-test owner"
                                             {:namespace ns-sym})))
                           [ns-sym owners])))]
    {:roots roots :graph (update graph 'millhouse/config conj 'workspace)
     :tests tests}))

(defn dependents
  "Return changed components and their transitive reverse dependency closure."
  [graph changed]
  (loop [affected (set changed)]
    (let [expanded (into affected (keep (fn [[library dependencies]]
                                          (when (seq (set/intersection affected dependencies))
                                            library))) graph)]
      (if (= expanded affected) affected (recur expanded)))))

(defn- documentation? [path]
  (or (and (not (str/includes? path "/")) (str/ends-with? path ".md"))
      (str/starts-with? path "docs/")
      (str/starts-with? path ".agents/")
      (= path "mkdocs.yml")
      (= path "LICENSE")))

(defn plan
  "Select root namespaces and independent package gates for changed paths.

  Unknown non-documentation paths select everything: shared fixtures, catalog,
  build scripts, dependency pins and CI policy can affect every suite. Spool
  files belong to their longest matching root, including nested packages."
  [{:keys [roots graph tests]} paths full?]
  (let [code-paths (remove documentation? paths)
        global-paths (filterv #(or (= % ".millstrand/land-quality.sh")
                                   (not (owner roots %))) code-paths)
        full? (or full? (seq global-paths))
        changed (set (keep #(owner roots %) code-paths))
        affected (if full? (set (keys roots)) (dependents graph changed))
        namespaces (filterv #(or full? (seq (set/intersection affected (get tests %))))
                            runner/test-namespaces)
        targets (cond-> (set (keep package-targets affected))
                  (contains? affected 'millhouse/kanban) (conj "kanban-dash-check"))]
    {:full (boolean full?) :global-paths global-paths
     :affected (vec (sort (map str affected)))
     :namespaces (mapv str namespaces) :targets (vec (sort targets))
     :distribution (boolean (seq affected))}))

(defn checkout-plan
  "Build an affected-test plan against base, or an explicit full plan without Git."
  [directory base full?]
  (let [changed (if full? {:base base :paths []} (changes directory base))]
    (merge changed (plan (repository directory) (:paths changed) full?))))

(defn jobs
  "Return CI matrix entries, retaining independent classpaths and native platforms."
  [{:keys [namespaces targets]}]
  (vec
   (concat
    (map-indexed (fn [index group]
                   {:name (str "root-" (inc index)) :target "test-root"
                    :namespaces (str/join " " group)
                    :os "ubuntu-latest" :plugins false})
                 (partition-all 4 namespaces))
    (map (fn [target]
           {:name target :target target :namespaces ""
            :os (if (= target "harnesses-check") "macos-latest" "ubuntu-latest")
            :plugins (= target "harnesses-check")})
         targets))))
