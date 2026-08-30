(ns millhouse.spools.millstrand-workflows.consumer-tooling-test
  "Regression coverage for retiring manifest-era consumer tooling."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(deftest legacy-consumer-tooling-workflow-is-not-shipped
  (is (nil? (io/resource
             "millhouse/spools/millstrand_workflows/consumer_tooling.clj"))))
