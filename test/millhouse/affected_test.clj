(ns millhouse.affected-test
  "Protect affected-test boundaries, Git baselines and independent CI jobs."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [millhouse.test-runner :as runner]
            [millhouse.test-support :as support]
            [millstrand.test.alpha :as test-alpha]
            [quality.affected :as affected]))

(def ^:private repository
  (-> (test-alpha/spool-checkout-root "millhouse/workflow.clj")
      .getParentFile .getParentFile))

(deftest declared-dependencies-select-only-downstream-suites
  (let [model (affected/repository repository)
        select #(affected/plan model [%] false)]
    (testing "an isolated spool does not pull in unrelated siblings"
      (let [plan (select "spools/chime/src/millhouse/chime.clj")]
        (is (= #{"millhouse/chime" "workspace" "millhouse/config"}
               (set (:affected plan))))
        (is (= #{"millhouse.chime-test" "millhouse.authoring-forms-test"
                 "millhouse.consumer-test" "millhouse.package-layout-test"}
               (set (:namespaces plan))))
        (is (= ["config-check" "workspace-test"] (:targets plan)))))
    (testing "shipped Markdown can be runtime input, not just documentation"
      (is (contains? (set (:affected (select "spools/harnesses/plugins/skill/SKILL.md")))
                     "millhouse/harnesses")))
    (testing "a changed integration test does not fan out to every spool"
      (let [plan (select "test/millhouse/e2e/cron/lifecycle_test.clj")]
        (is (= ["millhouse.e2e.cron.lifecycle-test"] (:namespaces plan)))
        (is (empty? (:targets plan)))
        (is (false? (:full plan)))))
    (testing "test-only dependencies select their owning suites"
      (let [plan (select "spools/cron/resources/jobs.edn")]
        (is (contains? (set (:affected plan)) "millhouse/auto-review"))
        (is (contains? (set (:namespaces plan)) "millhouse.e2e.cron.lifecycle-test"))
        (is (not (contains? (set (:affected plan)) "millhouse/land")))))
    (testing "nested packages and test extra-paths retain their own boundary"
      (let [plan (select "spools/devflow/kanban-adapter/src/adapter.clj")]
        (is (= #{"millhouse/devflow-kanban-adapter" "millhouse/devflow"
                 "millhouse/config" "workspace"}
               (set (:affected plan))))
        (is (= ["config-check" "devflow-check" "workspace-test"] (:targets plan)))))
    (testing "foundational changes follow the complete reverse closure"
      (let [plan (select "spools/workflow/src/millhouse/workflow.clj")]
        (is (= (set (map str (keys (:roots model)))) (set (:affected plan))))))))

(deftest shared-inputs-full-mode-and-empty-selections
  (let [model (affected/repository repository)]
    (doseq [path ["deps.edn" "spool.edn" "Makefile" ".millstrand/land-quality.sh"
                  "scripts/quality/affected.clj"
                  "test/millhouse/test_support.clj" ".github/workflows/quality.yml"
                  "new-build-tool.edn"]]
      (testing path
        (let [plan (affected/plan model [path] false)]
          (is (:full plan))
          (is (= (mapv str runner/test-namespaces) (:namespaces plan))))))
    (doseq [paths [[] ["README.md" "docs/guide.md"]]]
      (let [plan (affected/plan model paths false)]
        (is (empty? (:namespaces plan)))
        (is (empty? (:targets plan)))
        (is (false? (:distribution plan)))
        (is (empty? (affected/jobs plan)))))
    (is (= (mapv str runner/test-namespaces)
           (:namespaces (affected/plan model [] true))))))

(deftest reverse-closure-terminates-on-cycles
  (is (= '#{a b c}
         (affected/dependents '{a #{b} b #{a} c #{b} unrelated #{}} '#{a}))))

(deftest ci-jobs-preserve-platforms-and-exact-selections
  (let [jobs (affected/jobs {:namespaces ["a" "b"]
                             :targets ["harnesses-check" "devflow-check"]})]
    (is (= ["test-root" "harnesses-check" "devflow-check"] (mapv :target jobs)))
    (is (= "a b" (:namespaces (first jobs))))
    (is (= ["ubuntu-latest" "macos-latest" "ubuntu-latest"] (mapv :os jobs)))
    (is (= [false true false] (mapv :plugins jobs)))))

(defn- write-file! [directory path text]
  (let [file (io/file directory path)]
    (.mkdirs (.getParentFile file))
    (spit file text)))

(deftest git-selection-includes-branch-and-working-tree-changes
  (let [directory (support/temp-dir "millhouse-affected-git")
        git #(apply support/run-git! directory %&)]
    (try
      (git "init" "-b" "main")
      (git "config" "user.name" "Affected tests")
      (git "config" "user.email" "affected@example.invalid")
      (doseq [path ["original.clj" "dirty.clj" "staged.clj" "deleted.clj"]]
        (write-file! directory path "base"))
      (git "add" ".")
      (git "commit" "-m" "base")
      (git "checkout" "-b" "feature")
      (git "mv" "original.clj" "renamed.clj")
      (git "commit" "-m" "rename")
      (git "branch" "stack-base")
      (git "checkout" "main")
      (write-file! directory "main-only.clj" "unrelated")
      (git "add" ".")
      (git "commit" "-m" "main advanced")
      (git "checkout" "feature")
      (write-file! directory "dirty.clj" "unstaged")
      (write-file! directory "staged.clj" "staged")
      (git "add" "staged.clj")
      (git "rm" "deleted.clj")
      (write-file! directory "space and\nnewline.clj" "untracked")
      (let [paths #{"original.clj" "renamed.clj" "dirty.clj" "staged.clj"
                    "deleted.clj" "space and\nnewline.clj"}]
        (is (= paths (set (:paths (affected/changes directory "main")))))
        (is (= (disj paths "original.clj" "renamed.clj")
               (set (:paths (affected/changes directory "stack-base"))))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot determine affected tests"
                            (affected/changes directory "missing-base")))
      (finally
        (support/delete-tree! directory)))))
