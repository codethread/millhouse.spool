(ns millhouse.spools.millstrand-workflows.bootstrap-kondo-test
  "Regression coverage for retiring the manifest-era bootstrap workflow."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(deftest legacy-bootstrap-workflow-is-not-shipped
  (is (nil? (io/resource
             "millhouse/spools/millstrand_workflows/bootstrap_kondo.clj"))))
