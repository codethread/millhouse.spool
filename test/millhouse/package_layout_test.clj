(ns millhouse.package-layout-test
  "Guard the explicitly preserved package graph and checkout-local distribution."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [millhouse.consumer-deps :as consumer]
            [millstrand.test.alpha :as t]))

(def ^:private repository
  (-> (t/spool-checkout-root "millhouse/workflow.clj")
      .getParentFile .getParentFile))

(def ^:private package-roots
  (:roots (edn/read-string (slurp (io/file repository "spool.edn")))))

;; This is the existing production dependency graph, not permission to expand
;; it. New cross-spool relationships require explicit user authorization.
(def ^:private production-edges
  '{millhouse/auto-review #{millhouse/workflow}
    millhouse/auto-run #{millhouse/harnesses millhouse/kanban
                         millhouse/workflow millhouse/land}
    millhouse/chime #{}
    millhouse/cron #{}
    millhouse/identity #{}
    millhouse/kanban #{millhouse/identity}
    millhouse/land #{millhouse/kanban millhouse/workflow}
    millhouse/workflow #{}
    millhouse/harnesses #{millhouse/kanban millhouse/workflow}
    millhouse/devflow #{millhouse/workflow}
    millhouse/devflow-kanban-adapter #{millhouse/kanban}
    millhouse/config #{millhouse/auto-run millhouse/workflow
                       millhouse/identity millhouse/kanban
                       millhouse/land millhouse/harnesses
                       millhouse/devflow millhouse/devflow-kanban-adapter}})

(deftest published-selection-keeps-only-the-required-closure
  (let [manifests '{a {:deps {b {} external/core {}}}
                    b {:deps {c {}}}
                    c {:deps {}}
                    independent {:deps {}}}
        roots '{a {:root "spools/a"} b {:root "spools/b"}
                c {:root "spools/c"} independent {:root "spools/independent"}}
        sha "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        result (consumer/published-deps roots manifests sha '[a b])]
    (is (= '#{a b c} (set (keys (:deps result)))))
    (is (= '#{independent}
           (consumer/dependency-closure manifests '[independent])))
    (is (= #{sha} (set (map :git/sha (vals (:deps result))))))
    (is (= "spools/c" (get-in result [:deps 'c :deps/root])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown Millhouse package"
                          (consumer/dependency-closure manifests '[missing])))))

(deftest exported-linter-configs-follow-package-coordinates
  (doseq [[library {:keys [root]}] package-roots
          :let [exports (io/file repository root "resources/clj-kondo.exports")]
          :when (.isDirectory exports)]
    (testing (str library)
      (is (.isFile (io/file exports (namespace library) (name library)
                            "config.edn"))))))

(deftest package-graph-stays-explicit-and-local
  (is (= (set (keys production-edges)) (set (keys package-roots))))
  (doseq [[library {:keys [root]}] package-roots]
    (testing (str library)
      (let [directory (io/file repository root)
            manifest (edn/read-string (slurp (io/file directory "deps.edn")))
            internal-libraries (set (keys package-roots))]
        (is (= (get production-edges library)
               (set (filter internal-libraries (keys (:deps manifest))))))
        (doseq [coordinates (filter map? (tree-seq coll? seq manifest))
                [dependency coordinate] coordinates
                :when (contains? package-roots dependency)]
          (is (= #{:local/root} (set (keys coordinate))))
          (when-let [local (:local/root coordinate)]
            (is (= (.getCanonicalPath
                    (io/file repository (get-in package-roots [dependency :root])))
                   (.getCanonicalPath (io/file directory local))))))))))
