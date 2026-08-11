(ns millhouse.spools.millstrand-workflows.bump-spool-test
  "Contract tests for the family-only consumer bump workflow."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [millhouse.spools.millstrand-workflows.bootstrap-kondo]
            [millhouse.spools.millstrand-workflows.bump-spool :as bump]
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

(deftest bump-instruction-requests-latest-peeled-sha
  (let [instruction (get-in (step :bump-spool) [:attributes "workflow/instruction"])
        text (instruction {:item "io.millstrand/millstrand"
                           :worktree "/tmp/consumer"
                           :workspace "/tmp/consumer/.millstrand"})]
    (is (str/includes? text
                       "strand --workspace /tmp/consumer/.millstrand spool bump io.millstrand/millstrand --latest sha"))
    (is (str/includes? text "already current"))
    (is (str/includes? text "peeled SHA"))
    (is (not (str/includes? text "--to")))))

(deftest bump-loop-is-family-only-and-reuses-bootstrap
  (let [bump-step (step :bump-spool)
        bootstrap-call (step :bootstrap-kondo)]
    (is (= {:each :families :chain true} (:loop bump-step)))
    (is (= #'millhouse.spools.millstrand-workflows.bootstrap-kondo/bootstrap-kondo
           (:procedure bootstrap-call)))
    (is (= [:bootstrap-kondo]
           (:depends-on (step :refresh-runtime))))))

(deftest runtime-cutover-has-an-explicit-direct-user-boundary
  (is (= [:= :direct-user-request true]
         (:condition (step :cutover))))
  (is (= [:= :direct-user-request false]
         (:condition (step :handover-pending-generation))))
  (is (str/includes? (get-in (step :cutover) [:attributes "workflow/instruction"])
                     "direct user"))
  (is (str/includes? (get-in (step :handover-pending-generation)
                             [:attributes "workflow/instruction"])
                     "Do not stop or restart")))

(deftest family-only-call-compiles-bootstrap-path
  (let [params {:families ["io.millstrand/millstrand"]
                :worktree "/tmp/consumer"
                :workspace "/tmp/consumer/.millstrand"
                :direct-user-request false}
        refs (set (map :ref (:strands (workflow/compile (definition) params))))]
    (is (contains? refs :bootstrap-kondo--select-world))
    (is (contains? refs :bootstrap-kondo--adoption-mode))
    (is (contains? refs :refresh-runtime))
    (is (not-any? #(str/includes? (name %) "quality") refs))))
