(ns millhouse.executor-discovery-test
  "Test request-contract projection for the copied workflow executors."
  (:require [clojure.test :refer [deftest is testing]]
            [millhouse.test-support :as test-support :refer [with-runtime]]
            [millhouse.workflow.cli :as cli]))

(defn- activate-executors! [runtime]
  (test-support/activate-spool!
   runtime :millhouse/workflow 'millhouse.workflow)
  (test-support/activate-spool!
   runtime :millhouse/workflow-cli 'millhouse.test-modules.workflow-cli
   :after [:millhouse/workflow])
  (test-support/activate-spool!
   runtime :millhouse/shell 'millhouse.test-modules.shell-executor
   :after [:millhouse/workflow])
  (test-support/activate-spool!
   runtime :millhouse/code 'millhouse.test-modules.code-executor
   :after [:millhouse/workflow]))

(defn- request-keys [request kind]
  (mapv #(get % "key") (get-in request [:contract kind])))

(deftest executors-project-their-declared-request-contracts
  (with-runtime
    (fn [runtime _]
      (activate-executors! runtime)
      (let [items (:executors (cli/workflow {:op/args {:subcommand ["executors"]}}))
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
