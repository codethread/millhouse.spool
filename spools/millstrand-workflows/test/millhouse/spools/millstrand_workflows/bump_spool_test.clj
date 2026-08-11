(ns millhouse.spools.millstrand-workflows.bump-spool-test
  "Contract tests for the portable consumer bump-spool workflow."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [millhouse.spools.millstrand-workflows.bump-spool :as bump]
            [millhouse.spools.workflow :as workflow]))

(defn- definition []
  @#'bump/bump-spool)

(defn- step [id]
  (some #(when (= id (:id %)) %) (:steps (definition))))

(deftest params-require-an-explicit-world-and-request
  (let [valid {:bumps [{:family "io.millstrand/millstrand" :version "v12"}]
               :worktree "/tmp/consumer"
               :workspace "/tmp/consumer/.millstrand"
               :direct-user-request false}]
    (is (s/valid? ::bump/spool-bump-params valid))
    (is (not (s/valid? ::bump/spool-bump-params
                       (assoc-in valid [:bumps 0] {:family "io.millstrand/millstrand"}))))
    (is (not (s/valid? ::bump/spool-bump-params (dissoc valid :workspace))))
    (is (not (s/valid? ::bump/spool-bump-params
                       (update valid :bumps conj (first (:bumps valid))))))))

(deftest copy-configs-is-one-full-classpath-invocation
  (let [argv (get-in (step :copy-configs) [:attributes "shell/argv"])
        command (nth argv 2)]
    (is (= ["sh" "-c"] (subvec argv 0 2)))
    (is (= (str "set -eu\n"
                "clj-kondo --lint \"$(clojure -Spath)\" --dependencies --parallel"
                " --copy-configs --skip-lint\n")
           command))
    (is (= "/tmp/consumer"
           ((get-in (step :copy-configs) [:attributes "shell/cwd"])
            {:worktree "/tmp/consumer"})))))

(deftest import-review-checks-drift-and-cache-hygiene
  (let [review (step :inspect-import-drift)
        argv (get-in review [:attributes "shell/argv"])
        instruction ((get-in review [:attributes "workflow/instruction"])
                     {:worktree "/tmp/consumer"})]
    (is (= ["sh" "-c"] (subvec argv 0 2)))
    (is (str/includes? (nth argv 2) "git diff --check"))
    (is (str/includes? (nth argv 2) "git ls-files '.clj-kondo/.cache/**'"))
    (is (str/includes? instruction "one producer source"))
    (is (str/includes? instruction "external"))
    (is (str/includes? instruction "overlaps"))
    (is (str/includes? instruction "import drift"))
    (is (str/includes? instruction "self-imports"))))

(deftest final-status-gate-requires-clean-worktree
  (let [gate (step :verify-clean-status)
        argv (get-in gate [:attributes "shell/argv"])
        instruction (get-in gate [:attributes "workflow/instruction"])]
    (is (= ["sh" "-c"] (subvec argv 0 2)))
    (is (str/includes? (nth argv 2) "git status --short"))
    (is (= [:quality] (:depends-on gate)))
    (is (str/includes? instruction "tracked `.clj-kondo/.cache`"))))

(deftest quality-boundary-uses-resolved-argv
  (let [params {:bumps [{:family "io.millstrand/millstrand" :version "v12"}]
                :worktree "/tmp/consumer"
                :workspace "/tmp/consumer/.millstrand"
                :direct-user-request false}
        compile-call (fn [params]
                       (workflow/compile
                        (workflow/workflow
                         "Caller"
                         (workflow/call :millstrand-bump #'bump/bump-spool params))
                        {}))
        quality-strand (fn [compiled]
                         (some #(when (= :millstrand-bump--quality (:ref %)) %)
                               (:strands compiled)))
        default-quality (quality-strand (compile-call params))
        custom-quality (quality-strand
                        (compile-call (assoc params :quality-argv ["just" "quality"])))
        instruction (get-in custom-quality [:attributes "workflow/instruction"])]
    (is (= ["make" "quality"]
           (get-in default-quality [:attributes "shell/argv"])))
    (is (= ["just" "quality"]
           (get-in custom-quality [:attributes "shell/argv"])))
    (is (str/includes? instruction "[\"just\" \"quality\"]"))))

(deftest bump-instructions-use-the-requested-workspace
  (let [instruction (get-in (step :bump-spool) [:attributes "workflow/instruction"])
        text (instruction {:item {:family "io.millstrand/millstrand"
                                  :version "v12"}
                           :worktree "/tmp/consumer"
                           :workspace "/tmp/consumer/.millstrand"})]
    (is (str/includes? text "strand --workspace /tmp/consumer/.millstrand spool bump"))
    (is (str/includes? text "--to v12"))
    (is (str/includes? text "/tmp/consumer"))))

(deftest workflow-call-can-inline-the-consumer-bump
  (let [params {:bumps [{:family "io.millstrand/millstrand" :version "v12"}]
                :worktree "/tmp/consumer"
                :workspace "/tmp/consumer/.millstrand"
                :direct-user-request false}
        caller (workflow/workflow
                "Caller"
                (workflow/call :millstrand-bump #'bump/bump-spool params))
        refs (set (map :ref (:strands (workflow/compile caller {}))))]
    (is (contains? refs :millstrand-bump--copy-configs))
    (is (contains? refs :millstrand-bump--handover-pending-generation))))

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
