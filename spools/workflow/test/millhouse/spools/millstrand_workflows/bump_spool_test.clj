(ns millhouse.spools.millstrand-workflows.bump-spool-test
  "Regression coverage for retiring the manifest-era spool bump workflow."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(deftest legacy-spool-bump-workflow-is-not-shipped
  (is (nil? (io/resource
             "millhouse/spools/millstrand_workflows/bump_spool.clj"))))
