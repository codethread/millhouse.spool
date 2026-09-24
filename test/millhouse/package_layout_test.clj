(ns millhouse.package-layout-test
  "Guard the explicitly preserved package graph and checkout-local distribution."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [millstrand.test.alpha :as t]))

(def ^:private repository
  (-> (t/spool-checkout-root "millhouse/spools/workflow.clj")
      .getParentFile .getParentFile))

(def ^:private package-roots
  (:roots (edn/read-string (slurp (io/file repository "spool.edn")))))

;; This is the existing production dependency graph, not permission to expand
;; it. New cross-spool relationships require explicit user authorization.
(def ^:private production-edges
  '{millhouse.spools/auto-review #{millhouse.spools/workflow}
    millhouse.spools/auto-run #{ct.spools/harnesses millhouse.spools/kanban
                                millhouse.spools/workflow millhouse.spools/land}
    millhouse.spools/chime #{}
    millhouse.spools/cron #{}
    millhouse.spools/identity #{}
    millhouse.spools/kanban #{millhouse.spools/identity}
    millhouse.spools/land #{millhouse.spools/kanban millhouse.spools/workflow}
    millhouse.spools/workflow #{}
    ct.spools/harnesses #{millhouse.spools/kanban millhouse.spools/workflow}
    codethread/devflow #{millhouse.spools/workflow}
    codethread/devflow-kanban-adapter #{millhouse.spools/kanban}
    codethread/config #{millhouse.spools/auto-run millhouse.spools/workflow
                        millhouse.spools/identity millhouse.spools/kanban
                        millhouse.spools/land ct.spools/harnesses
                        codethread/devflow codethread/devflow-kanban-adapter}})

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
