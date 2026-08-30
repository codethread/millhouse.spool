(ns millhouse.spools.millstrand-workflows.bump-millstrand-test
  "Regression coverage for retiring the manifest-era Millstrand bump workflow."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(deftest legacy-millstrand-bump-workflow-is-not-shipped
  (is (nil? (io/resource
             "millhouse/spools/millstrand_workflows/bump_millstrand.clj"))))
