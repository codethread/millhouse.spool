(ns millhouse.executor-discovery-test
  "Test request-contract projection for the copied workflow executors."
  (:require [clojure.test :refer [deftest is testing]]
            [millhouse.test-support :as test-support :refer [with-runtime]]
            [millhouse.spools.workflow.cli :as cli]))

(defn- activate-executors! [runtime]
  (test-support/activate-spool!
   runtime :millhouse/spools-workflow 'millhouse.spools.workflow)
  (test-support/activate-spool!
   runtime :millhouse/spools-workflow-cli 'millhouse.spools.workflow.cli
   :after [:millhouse/spools-workflow])
  (test-support/activate-spool!
   runtime :millhouse/spools-shell 'millhouse.spools.executors.shell
   :after [:millhouse/spools-workflow])
  (test-support/activate-spool!
   runtime :millhouse/spools-code 'millhouse.spools.executors.code
   :after [:millhouse/spools-workflow]))

(defn- request-keys [request kind]
  (mapv #(get % "key") (get-in request [:contract kind])))

(deftest executors-project-their-declared-request-contracts
  (with-runtime
    (fn [runtime _]
      (activate-executors! runtime)
      (let [items (:executors (cli/workflow-op {:op/args {:subcommand ["executors"]}}))
            by-waiter (into {} (map (juxt :waiter identity)) items)]
        (testing "shell"
          (let [request (:request (by-waiter "shell"))]
            (is (= ["shell/argv"] (request-keys request "required")))
            (is (= ["shell/cwd" "shell/timeout-secs"]
                   (request-keys request "optional")))
            (is (= #{"shell/argv" "shell/cwd" "shell/timeout-secs"}
                   (set (keys (:template request)))))))
        (testing "code"
          (let [request (:request (by-waiter "code"))]
            (is (= ["code/fn" "code/params"]
                   (request-keys request "required")))
            (is (= ["code/timeout-secs"] (request-keys request "optional")))
            (is (= #{"code/fn" "code/params" "code/timeout-secs"}
                   (set (keys (:template request)))))))))))
