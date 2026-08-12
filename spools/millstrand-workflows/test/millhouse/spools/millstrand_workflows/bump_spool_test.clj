(ns millhouse.spools.millstrand-workflows.bump-spool-test
  "Contract tests for the family-only consumer bump workflow."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [millhouse.spools.millstrand-workflows.bootstrap-kondo]
            [millhouse.spools.millstrand-workflows.bump-spool :as bump]
            [millhouse.spools.millstrand-workflows.consumer-tooling :as tooling]
            [millhouse.spools.workflow :as workflow]))

(defn- definition []
  @#'bump/bump-spool)

(defn- step [id]
  (some #(when (= id (:id %)) %) (:steps (definition))))

(deftest params-require-families-and-an-explicit-world
  (let [valid {:families ["io.millstrand/millstrand"]
               :worktree "/tmp/consumer"
               :workspace "/tmp/consumer/.millstrand"
               :direct-user-request false}]
    (is (s/valid? ::bump/spool-bump-params valid))
    (is (not (s/valid? ::bump/spool-bump-params
                       (assoc valid :families
                              ["io.millstrand/millstrand"
                               "io.millstrand/millstrand"]))))
    (is (not (s/valid? ::bump/spool-bump-params (dissoc valid :workspace))))))

(deftest bump-instruction-requests-remote-default-branch-head-sha
  (let [instruction (get-in (step :bump-spool) [:attributes "workflow/instruction"])
        text (instruction {:item "io.millstrand/millstrand"
                           :worktree "/tmp/consumer"
                           :workspace "/tmp/consumer/.millstrand"})]
    (is (str/includes? text
                       "strand --workspace /tmp/consumer/.millstrand spool bump io.millstrand/millstrand --latest sha"))
    (is (str/includes? text "already current"))
    (is (str/includes? text "remote default-branch HEAD SHA"))
    (is (not (str/includes? text "--to")))))

(deftest bump-loop-reuses-bootstrap-before-consumer-tooling
  (let [bump-step (step :bump-spool)
        bootstrap-call (step :bootstrap-kondo)
        tooling-call (step :configure-consumer-tooling)]
    (is (= {:each :families :chain true} (:loop bump-step)))
    (is (= #'millhouse.spools.millstrand-workflows.bootstrap-kondo/bootstrap-kondo
           (:procedure bootstrap-call)))
    (is (= [:bump-spool] (:depends-on bootstrap-call)))
    (is (= [:bootstrap-kondo]
           (:depends-on (step :refresh-runtime))))
    (is (= #'tooling/configure-consumer-tooling
           (:procedure tooling-call)))
    (is (= [:refresh-runtime] (:depends-on tooling-call)))
    (is (= [:configure-consumer-tooling]
           (:depends-on (step :assess-authorized-cutover))))
    (is (= [:configure-consumer-tooling]
           (:depends-on (step :handover-runtime-generation-evidence))))))

(deftest compiled-bootstrap-cannot-begin-before-bump-loop-terminal
  (let [params {:families ["io.millstrand/millstrand"]
                :worktree "/tmp/consumer"
                :workspace "/tmp/consumer/.millstrand"
                :direct-user-request false}
        payload (workflow/compile (definition) params)
        edges (set (map (juxt :from :to :type) (:edges payload)))]
    (is (contains? edges
                   [:bootstrap-kondo--select-world
                    :bump-spool-1
                    "depends-on"]))))

(deftest runtime-cutover-has-an-explicit-direct-user-boundary
  (is (= [:= :direct-user-request true]
         (:condition (step :assess-authorized-cutover))))
  (is (= [:= :direct-user-request false]
         (:condition (step :handover-runtime-generation-evidence))))
  (is (str/includes? (get-in (step :assess-authorized-cutover)
                             [:attributes "workflow/instruction"])
                     "If no pending generation exists"))
  (is (str/includes? (get-in (step :assess-authorized-cutover)
                             [:attributes "workflow/instruction"])
                     "repeat the selected"))
  (is (str/includes? (get-in (step :handover-runtime-generation-evidence)
                             [:attributes "workflow/instruction"])
                     "no cutover is required"))
  (is (str/includes? (get-in (step :handover-runtime-generation-evidence)
                             [:attributes "workflow/instruction"])
                     "unfinished")))

(deftest family-only-call-compiles-bootstrap-path
  (let [params {:families ["io.millstrand/millstrand"]
                :worktree "/tmp/consumer"
                :workspace "/tmp/consumer/.millstrand"
                :direct-user-request false}
        strands (into {} (map (juxt :ref identity)
                              (:strands (workflow/compile (definition) params))))
        refs (set (keys strands))
        inspect-instruction
        (get-in strands [:configure-consumer-tooling--inspect-repository
                         :attributes "workflow/instruction"])]
    (is (contains? refs :bootstrap-kondo--select-world))
    (is (contains? refs :bootstrap-kondo--adoption-mode))
    (is (contains? refs :refresh-runtime))
    (is (contains? refs :configure-consumer-tooling--inspect-repository))
    (is (contains? refs :configure-consumer-tooling--repository-style))
    (is (str/includes? inspect-instruction "/tmp/consumer"))
    (is (str/includes? inspect-instruction "/tmp/consumer/.millstrand"))
    (is (not-any? #(str/includes? (name %) "quality") refs))))
