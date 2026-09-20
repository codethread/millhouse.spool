(ns millhouse.spools.land.scripts-test
  "Exercise the protected landing scripts against disposable Git repositories."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [millhouse.spools.land.support :as support]
            [millhouse.test-support :as test-support]))

(def ^:private branch "feature/land-script-test")
(def ^:private script-root
  (.getCanonicalFile
   (io/file (or (io/resource "millhouse/spools/land/scripts")
                (throw (ex-info "Landing script resources are missing" {}))))))

(defn- script-file
  [name]
  (io/file script-root name))

(defn- write-file!
  [root path contents executable?]
  (let [file (io/file root path)]
    (io/make-parents file)
    (spit file contents)
    (when executable?
      (.setExecutable file true false))
    file))

(defn- run-command!
  "Run argv in dir, returning combined output and the exit status."
  [dir argv env]
  (let [process-builder (doto (ProcessBuilder. ^java.util.List argv)
                          (.directory (io/file dir))
                          (.redirectErrorStream true))
        environment (.environment process-builder)]
    (doseq [[key value] env]
      (.put environment key value))
    (let [process (.start process-builder)
          output (slurp (.getInputStream process))]
      {:exit (.waitFor process)
       :output output})))

(defn- run-script
  [dir name args env]
  (run-command! dir
                (into ["sh" (.getPath (script-file name))] args)
                env))

(defn- commit!
  [dir message]
  (test-support/run-git! dir "add" ".")
  (test-support/run-git! dir "commit" "-m" message)
  (str/trim (test-support/run-git! dir "rev-parse" "HEAD")))

(defn- fixture
  []
  (let [root (test-support/temp-dir "millstrand-land-scripts")
        remote (io/file root "remote.git")
        seed (io/file root "seed")
        canonical (io/file root "canonical")
        worktree (io/file root "feature")
        log (io/file root "quality.log")]
    (test-support/run-git! root "init" "--bare" (.getPath remote))
    (test-support/run-git! root "init" "-b" "main" (.getPath seed))
    (test-support/run-git! seed "config" "user.name" "Millstrand Test")
    (test-support/run-git! seed "config" "user.email" "test@millstrand.invalid")
    (write-file! seed "README" "baseline\n" false)
    (write-file! seed ".millstrand/land-quality.sh"
                 "#!/bin/sh\nset -eu\nprintf '%s\\n' pass >>\"$LAND_TEST_LOG\"\n"
                 true)
    (commit! seed "baseline")
    (test-support/run-git! seed "remote" "add" "origin" (.getPath remote))
    (test-support/run-git! seed "push" "-u" "origin" "main")
    (test-support/run-git! root "clone" (.getPath remote) (.getPath canonical))
    (test-support/run-git! canonical "config" "user.name" "Millstrand Test")
    (test-support/run-git! canonical "config" "user.email" "test@millstrand.invalid")
    (test-support/run-git! canonical "worktree" "add" "-b" branch
                           (.getPath worktree) "origin/main")
    (write-file! worktree "feature.txt" "feature\n" false)
    (let [feature-head (commit! worktree "feature")]
      (test-support/run-git! worktree "push" "-u" "origin" branch)
      {:root root
       :canonical canonical
       :worktree worktree
       :log log
       :feature-head feature-head})))

(defn- quality-env
  [fixture]
  {"LAND_TEST_LOG" (.getPath (:log fixture))})

(defn- quality-source
  []
  (slurp (script-file "land-quality-gate.sh")))

(defn- prepare!
  [fixture env]
  (run-script (:worktree fixture)
              "land-prepare.sh"
              [branch (quality-source)]
              env))

(defn- marker
  [fixture]
  (let [path (str/trim (test-support/run-git! (:worktree fixture)
                                              "rev-parse"
                                              "--git-path"
                                              "millstrand-land-quality-head"))
        file (if (.isAbsolute (io/file path))
               (io/file path)
               (io/file (:worktree fixture) path))]
    file))

(defn- quality-runs
  [fixture]
  (if (.exists (:log fixture))
    (count (str/split-lines (slurp (:log fixture))))
    0))

(defn- assert-success
  [{:keys [exit output]}]
  (is (zero? exit) output))

(deftest pr-checks-argv-freezes-policy-and-registration-window
  (let [argv (support/pr-checks-argv "required" branch)]
    (is (= ["sh" "-c"] (subvec argv 0 2)))
    (is (= ["pr-checks" "required" branch "120" "5"]
           (subvec argv (- (count argv) 5))))))

(defn- pr-check-env
  [fixture]
  (let [fake-bin (io/file (:root fixture) "pr-check-bin")
        gh-log (io/file (:root fixture) "pr-check-gh.log")
        view-count (io/file (:root fixture) "pr-check-view-count")
        head-file (io/file (:root fixture) "pr-check-head")]
    (spit view-count "0\n")
    (spit head-file (str (:feature-head fixture) "\n"))
    (write-file!
     fake-bin "gh"
     (str "#!/bin/sh\n"
          "set -eu\n"
          "printf '%s\\n' \"$*\" >>\"$GH_TEST_LOG\"\n"
          "if [ \"${1-} ${2-}\" = 'pr view' ]; then\n"
          "  view_count=$(cat \"$GH_TEST_VIEW_COUNT\")\n"
          "  view_count=$((view_count + 1))\n"
          "  printf '%s\\n' \"$view_count\" >\"$GH_TEST_VIEW_COUNT\"\n"
          "  printf '%s\\n' \"${GH_TEST_IS_DRAFT:-false}\"\n"
          "  printf '%s\\n' \"${GH_TEST_STATE:-OPEN}\"\n"
          "  printf '%s\\n' \"${GH_TEST_BASE:-main}\"\n"
          "  printf '%s\\n' \"${GH_TEST_BRANCH}\"\n"
          "  cat \"$GH_TEST_HEAD_FILE\"\n"
          "  if [ \"$view_count\" -le \"${GH_TEST_EMPTY_VIEWS:-0}\" ]; then\n"
          "    printf '0\\n'\n"
          "  else\n"
          "    printf '%s\\n' \"${GH_TEST_CHECK_COUNT:-0}\"\n"
          "  fi\n"
          "  exit 0\n"
          "fi\n"
          "if [ \"${1-} ${2-}\" = 'pr checks' ]; then\n"
          "  printf '%s\\n' \"${GH_TEST_CHECK_OUTPUT:-checks passed}\"\n"
          "  if [ -n \"${GH_TEST_HEAD_AFTER_CHECKS:-}\" ]; then\n"
          "    printf '%s\\n' \"$GH_TEST_HEAD_AFTER_CHECKS\" >\"$GH_TEST_HEAD_FILE\"\n"
          "  fi\n"
          "  if [ -n \"${GH_TEST_REMOTE_AFTER_CHECKS:-}\" ]; then\n"
          "    git push --quiet origin \"$GH_TEST_REMOTE_AFTER_CHECKS:refs/heads/$GH_TEST_BRANCH\"\n"
          "  fi\n"
          "  exit \"${GH_TEST_CHECK_EXIT:-0}\"\n"
          "fi\n"
          "exit 64\n")
     true)
    {:env {"GH_TEST_LOG" (.getPath gh-log)
           "GH_TEST_VIEW_COUNT" (.getPath view-count)
           "GH_TEST_HEAD_FILE" (.getPath head-file)
           "GH_TEST_BRANCH" branch
           "PATH" (str (.getPath fake-bin) java.io.File/pathSeparator
                       (System/getenv "PATH"))}
     :log gh-log
     :view-count view-count
     :head-file head-file}))

(deftest pr-checks-allows-only-explicit-empty-policy
  (let [fixture (fixture)
        {:keys [env log]} (pr-check-env fixture)]
    (try
      (let [result (run-script (:worktree fixture) "pr-checks.sh"
                               ["allow-empty" branch] env)]
        (assert-success result)
        (is (str/includes? (:output result) "allow-empty accepted exact")))
      (let [calls (str/split-lines (slurp log))]
        (is (= 1 (count calls)))
        (is (str/includes? (first calls)
                           "--json isDraft,state,baseRefName,headRefName,headRefOid,statusCheckRollup")))
      (spit log "")
      (let [result (run-script (:worktree fixture) "pr-checks.sh"
                               ["required" branch "0" "0"] env)]
        (is (not (zero? (:exit result))))
        (is (str/includes? (:output result) "no checks registered"))
        (is (str/includes? (:output result) "policy required")))
      (is (= 1 (count (str/split-lines (slurp log))))
          "an empty required rollup must not call gh pr checks")
      (finally
        (test-support/delete-tree! (:root fixture))))))

(deftest pr-checks-delegates-registered-pending-pass-and-fail-rollups
  (let [fixture (fixture)
        {:keys [env log view-count]} (pr-check-env fixture)]
    (try
      (doseq [[case-name empty-views check-output check-exit expected-success?]
              [["empty-then-pending-then-pass" "1" "pending then passed" "0" true]
               ["pass" "0" "checks passed" "0" true]
               ["fail" "0" "checks failed" "7" false]]]
        (spit log "")
        (spit view-count "0\n")
        (let [result (run-script (:worktree fixture) "pr-checks.sh"
                                 ["required" branch "1" "0"]
                                 (assoc env
                                        "GH_TEST_EMPTY_VIEWS" empty-views
                                        "GH_TEST_CHECK_COUNT" "2"
                                        "GH_TEST_CHECK_OUTPUT" check-output
                                        "GH_TEST_CHECK_EXIT" check-exit))]
          (is (= expected-success? (zero? (:exit result))) case-name)
          (is (str/includes? (:output result) check-output) case-name)
          (let [calls (str/split-lines (slurp log))
                expected-view-calls (if (= "1" empty-views) 2 1)
                expected-call-count (if expected-success?
                                      (+ 2 expected-view-calls)
                                      (inc expected-view-calls))]
            (is (= expected-call-count (count calls)) case-name)
            (is (str/includes? (first calls)
                               "--json isDraft,state,baseRefName,headRefName,headRefOid,statusCheckRollup")
                case-name)
            (is (= (str "pr checks " branch " --watch --fail-fast")
                   (if expected-success?
                     (nth calls (- (count calls) 2))
                     (last calls)))
                case-name))))
      (finally
        (test-support/delete-tree! (:root fixture))))))

(deftest pr-checks-rejects-pr-identity-mismatches-before-check-wait
  (let [fixture (fixture)
        {:keys [env log head-file]} (pr-check-env fixture)
        other-head (str/join (repeat 40 "a"))]
    (try
      (doseq [[case-name overrides expected]
              [["draft" {"GH_TEST_IS_DRAFT" "true"} "is draft"]
               ["closed" {"GH_TEST_STATE" "CLOSED"} "expected OPEN"]
               ["wrong base" {"GH_TEST_BASE" "release"} "expected main"]
               ["wrong head branch" {"GH_TEST_BRANCH" "feature/other"} "PR head is"]
               ["invalid check count" {"GH_TEST_CHECK_COUNT" "unknown"}
                "invalid check count"]]]
        (spit log "")
        (let [result (run-script (:worktree fixture) "pr-checks.sh"
                                 ["allow-empty" branch]
                                 (merge env overrides))]
          (is (not (zero? (:exit result))) case-name)
          (is (str/includes? (:output result) expected) case-name)
          (is (= 1 (count (str/split-lines (slurp log))))
              (str case-name " must stop after the metadata query"))))
      (spit log "")
      (spit head-file (str other-head "\n"))
      (let [result (run-script (:worktree fixture) "pr-checks.sh"
                               ["allow-empty" branch] env)]
        (is (not (zero? (:exit result))))
        (is (str/includes? (:output result) "does not match local HEAD")))
      (spit head-file (str (:feature-head fixture) "\n"))
      (spit log "")
      (let [result (run-script (:worktree fixture) "pr-checks.sh"
                               ["allow-empty" "feature/other"]
                               (assoc env "GH_TEST_BRANCH" "feature/other"))]
        (is (not (zero? (:exit result))))
        (is (str/includes? (:output result) "checked-out branch")))
      (is (= 1 (count (str/split-lines (slurp log)))))
      (finally
        (test-support/delete-tree! (:root fixture))))))

(deftest pr-checks-rejects-a-pr-head-that-changes-during-the-watch
  (let [fixture (fixture)
        {:keys [env log]} (pr-check-env fixture)
        moved-head (str/join (repeat 40 "b"))]
    (try
      (let [result (run-script (:worktree fixture) "pr-checks.sh"
                               ["required" branch "1" "0"]
                               (assoc env
                                      "GH_TEST_CHECK_COUNT" "1"
                                      "GH_TEST_HEAD_AFTER_CHECKS" moved-head))]
        (is (not (zero? (:exit result))))
        (is (str/includes? (:output result) "PR head changed during checks"))
        (let [calls (str/split-lines (slurp log))]
          (is (= 3 (count calls)))
          (is (= (str "pr checks " branch " --watch --fail-fast")
                 (second calls)))))
      (finally
        (test-support/delete-tree! (:root fixture))))))

(deftest pr-checks-rejects-a-pushed-head-that-changes-during-the-watch
  (let [fixture (fixture)
        {:keys [env log]} (pr-check-env fixture)]
    (try
      (let [remote-head
            (str/trim
             (test-support/run-git!
              (:worktree fixture) "commit-tree"
              (str (:feature-head fixture) "^{tree}")
              "-p" (:feature-head fixture) "-m" "remote successor during checks"))
            result (run-script (:worktree fixture) "pr-checks.sh"
                               ["required" branch "1" "0"]
                               (assoc env
                                      "GH_TEST_CHECK_COUNT" "1"
                                      "GH_TEST_REMOTE_AFTER_CHECKS" remote-head))]
        (is (not (zero? (:exit result))))
        (is (str/includes? (:output result)
                           "pushed origin/feature/land-script-test HEAD changed during checks"))
        (is (= remote-head
               (first (str/split
                       (str/trim (test-support/run-git!
                                  (:worktree fixture) "ls-remote" "origin"
                                  (str "refs/heads/" branch)))
                       #"\s+"))))
        (let [calls (str/split-lines (slurp log))]
          (is (= 3 (count calls)))
          (is (= (str "pr checks " branch " --watch --fail-fast")
                 (second calls)))))
      (finally
        (test-support/delete-tree! (:root fixture))))))

(deftest pr-checks-rejects-a-pushed-head-that-moved
  (let [fixture (fixture)
        {:keys [env log]} (pr-check-env fixture)]
    (try
      (let [remote-head
            (str/trim
             (test-support/run-git!
              (:worktree fixture) "commit-tree"
              (str (:feature-head fixture) "^{tree}")
              "-p" (:feature-head fixture) "-m" "remote-only successor"))]
        (test-support/run-git! (:worktree fixture) "push" "origin"
                               (str remote-head ":refs/heads/" branch)))
      (let [result (run-script (:worktree fixture) "pr-checks.sh"
                               ["allow-empty" branch] env)]
        (is (not (zero? (:exit result))))
        (is (str/includes? (:output result) "does not match local HEAD"))
        (is (= 1 (count (str/split-lines (slurp log))))))
      (finally
        (test-support/delete-tree! (:root fixture))))))

(deftest quality-marker-reuses-an-unchanged-head
  (let [fixture (fixture)
        env (quality-env fixture)]
    (try
      (assert-success (run-script (:worktree fixture) "land-quality-gate.sh"
                                  [branch] env))
      (is (= 1 (quality-runs fixture)))
      (assert-success (prepare! fixture env))
      (is (= 1 (quality-runs fixture))
          "prepare must reuse the successful unchanged-head marker")
      (is (= (str (:feature-head fixture) "\n") (slurp (marker fixture))))
      (finally
        (test-support/delete-tree! (:root fixture))))))

(deftest rebase-invalidates-marker-and-reruns-quality
  (let [fixture (fixture)
        env (quality-env fixture)]
    (try
      (assert-success (run-script (:worktree fixture) "land-quality-gate.sh"
                                  [branch] env))
      (write-file! (:canonical fixture) "main.txt" "main moved\n" false)
      (commit! (:canonical fixture) "main change")
      (test-support/run-git! (:canonical fixture) "push" "origin" "main")
      (assert-success (prepare! fixture env))
      (is (= 2 (quality-runs fixture))
          "a rebase changes HEAD and must run quality again")
      (is (= (str/trim (test-support/run-git! (:worktree fixture)
                                              "rev-parse" "HEAD"))
             (str/trim (slurp (marker fixture)))))
      (finally
        (test-support/delete-tree! (:root fixture))))))

(deftest failed-quality-removes-any-cached-pass
  (let [fixture (fixture)
        env (quality-env fixture)]
    (try
      (assert-success (run-script (:worktree fixture) "land-quality-gate.sh"
                                  [branch] env))
      (write-file! (:worktree fixture) ".millstrand/land-quality.sh"
                   "#!/bin/sh\nexit 17\n"
                   true)
      (let [head (commit! (:worktree fixture) "make quality fail")]
        (test-support/run-git! (:worktree fixture) "push" "origin" branch)
        (let [{:keys [exit output]}
              (run-script (:worktree fixture) "land-quality-gate.sh" [branch] env)]
          (is (not (zero? exit)) output)
          (is (re-find  #"quality" output))
          (is (not (.exists (marker fixture))))
          (is (= head (str/trim (test-support/run-git! (:worktree fixture)
                                                       "rev-parse" "HEAD")))))
        (is (not (zero? (:exit (prepare! fixture env))))
            "prepare must not reuse the removed marker"))
      (finally
        (test-support/delete-tree! (:root fixture))))))

(deftest merge-requires-the-marker-and-matches-the-pr-head
  (let [fixture (fixture)
        fake-bin (io/file (:root fixture) "bin")
        gh-log (io/file (:root fixture) "gh.log")
        gh-state (io/file (:root fixture) "gh.state")
        head (:feature-head fixture)]
    (try
      (spit gh-state "OPEN\n")
      (write-file! fake-bin "gh"
                   (str "#!/bin/sh\n"
                        "printf '%s\\n' \"$*\" >>\"$GH_TEST_LOG\"\n"
                        "case \"$*\" in\n"
                        "  *'--json state'*) cat \"$GH_TEST_STATE\" ;;\n"
                        "  *'--json headRefName'*) printf '" branch "\\n' ;;\n"
                        "  *'--json baseRefName'*) printf '%s\\n' \"${GH_TEST_BASE:-main}\" ;;\n"
                        "  *'--json headRefOid'*) printf '%s\\n' \"$GH_TEST_HEAD\" ;;\n"
                        "  *'--json isDraft'*) printf 'false\\n' ;;\n"
                        "  *'pr ready '*) exit 1 ;;\n"
                        "  *'pr merge '*)\n"
                        "    match=\n"
                        "    while [ \"$#\" -gt 0 ]; do\n"
                        "      if [ \"$1\" = --match-head-commit ]; then\n"
                        "        shift\n"
                        "        match=${1-}\n"
                        "      fi\n"
                        "      shift\n"
                        "    done\n"
                        "    [ \"$match\" = \"$GH_TEST_HEAD\" ] || exit 23\n"
                        "    printf '%s\\n' \"${GH_TEST_MERGE_STATE:-MERGED}\" >\"$GH_TEST_STATE\"\n"
                        "    printf 'merged\\n'\n"
                        "    ;;\n"
                        "esac\n")
                   true)
      (let [env (merge (quality-env fixture)
                       {"GH_TEST_LOG" (.getPath gh-log)
                        "GH_TEST_STATE" (.getPath gh-state)
                        "GH_TEST_HEAD" head
                        "PATH" (str (.getPath fake-bin) java.io.File/pathSeparator
                                    (System/getenv "PATH"))})
            result (run-script (:worktree fixture) "land-merge.sh"
                               ["42" "subject" "body" branch] env)]
        (is (not (zero? (:exit result)))
            "merge must fail before quality has produced a marker")
        (is (not (str/includes? (slurp gh-log) "pr merge")))
        (assert-success (run-script (:worktree fixture) "land-quality-gate.sh"
                                    [branch] env))
        (let [wrong-base (run-script (:worktree fixture) "land-merge.sh"
                                     ["42" "subject" "body" branch]
                                     (assoc env "GH_TEST_BASE" "release"))]
          (is (not (zero? (:exit wrong-base))))
          (is (str/includes? (:output wrong-base) "base is release; expected main"))
          (is (not (str/includes? (slurp gh-log) "pr merge"))))
        (assert-success (run-script (:worktree fixture) "land-merge.sh"
                                    ["42" "subject" "body" branch] env))
        (is (str/includes? (slurp gh-log) "--json isDraft")
            "an already-ready PR is accepted when gh pr ready declines the conversion")
        (is (str/includes? (slurp gh-log) "pr merge"))
        (is (= "MERGED\n" (slurp gh-state)))
        ;; A real squash merge advances main to a commit that is not an
        ;; ancestor of the old feature HEAD. MERGED retry must validate PR and
        ;; reviewed-head identity without reapplying the pre-merge ancestry guard.
        (write-file! (:canonical fixture) "squash.txt" "squashed feature\n" false)
        (commit! (:canonical fixture) "squash merge result")
        (test-support/run-git! (:canonical fixture) "push" "origin" "main")
        (assert-success (run-script (:worktree fixture) "land-merge.sh"
                                    ["42" "subject" "body" branch] env))
        (is (= 1 (count (filter #(str/includes? % "pr merge")
                                (str/split-lines (slurp gh-log)))))
            "a merged PR retry must not invoke gh pr merge again")
        (let [wrong-head (run-script (:worktree fixture) "land-merge.sh"
                                     ["42" "subject" "body" "feature/other"] env)]
          (is (not (zero? (:exit wrong-head))))
          (is (str/includes? (:output wrong-head)
                             "expected feature/other"))))
      (finally
        (test-support/delete-tree! (:root fixture))))))

(deftest merge-fails-loudly-on-state-errors-and-unmerged-command
  (let [fixture (fixture)
        fake-bin (io/file (:root fixture) "bin")
        gh-log (io/file (:root fixture) "gh.log")
        gh-state (io/file (:root fixture) "gh.state")
        gh-merged (io/file (:root fixture) "gh.merged")
        head (:feature-head fixture)]
    (try
      (spit gh-state "OPEN\n")
      (write-file! fake-bin "gh"
                   (str "#!/bin/sh\n"
                        "printf '%s\\n' \"$*\" >>\"$GH_TEST_LOG\"\n"
                        "case \"$*\" in\n"
                        "  *'--json state'*)\n"
                        "    if [ \"${GH_TEST_FAIL_STATE_READ:-}\" = 1 ] && [ -f \"$GH_TEST_MERGED\" ]; then exit 19; fi\n"
                        "    cat \"$GH_TEST_STATE\"\n"
                        "    ;;\n"
                        "  *'--json headRefName'*) printf '" branch "\\n' ;;\n"
                        "  *'--json baseRefName'*) printf '%s\\n' \"${GH_TEST_BASE:-main}\" ;;\n"
                        "  *'--json headRefOid'*) printf '%s\\n' \"$GH_TEST_HEAD\" ;;\n"
                        "  *'--json isDraft'*) printf 'false\\n' ;;\n"
                        "  *'pr ready '*) exit 0 ;;\n"
                        "  *'pr merge '*)\n"
                        "    match=\n"
                        "    while [ \"$#\" -gt 0 ]; do\n"
                        "      if [ \"$1\" = --match-head-commit ]; then\n"
                        "        shift\n"
                        "        match=${1-}\n"
                        "      fi\n"
                        "      shift\n"
                        "    done\n"
                        "    [ \"$match\" = \"$GH_TEST_HEAD\" ] || exit 23\n"
                        "    printf '%s\\n' \"${GH_TEST_MERGE_STATE:-MERGED}\" >\"$GH_TEST_STATE\"\n"
                        "    : >\"$GH_TEST_MERGED\"\n"
                        "    printf 'merged\\n'\n"
                        "    ;;\n"
                        "esac\n")
                   true)
      (let [base-env (merge (quality-env fixture)
                            {"GH_TEST_LOG" (.getPath gh-log)
                             "GH_TEST_STATE" (.getPath gh-state)
                             "GH_TEST_MERGED" (.getPath gh-merged)
                             "GH_TEST_HEAD" head
                             "PATH" (str (.getPath fake-bin) java.io.File/pathSeparator
                                         (System/getenv "PATH"))})]
        (assert-success (run-script (:worktree fixture) "land-quality-gate.sh"
                                    [branch] base-env))
        (let [{:keys [exit output]}
              (run-script (:worktree fixture) "land-merge.sh"
                          ["42" "subject" "body" branch]
                          (assoc base-env "GH_TEST_FAIL_STATE_READ" "1"))]
          (is (not (zero? exit)) output)
          (is (str/includes? output "cannot verify PR 42 state after merge"))
          (is (= "MERGED\n" (slurp gh-state)))
          (is (str/includes? (slurp gh-log) "pr merge")))
        (spit gh-state "OPEN\n")
        (let [{:keys [exit output]}
              (run-script (:worktree fixture) "land-merge.sh"
                          ["42" "subject" "body" branch]
                          (assoc base-env "GH_TEST_MERGE_STATE" "OPEN"))]
          (is (not (zero? exit)) output)
          (is (str/includes? output "without reaching MERGED"))
          (is (= "OPEN\n" (slurp gh-state)))
          (is (str/includes? (slurp gh-log) "--match-head-commit"))))
      (finally
        (test-support/delete-tree! (:root fixture))))))

(deftest cleanup-refuses-main-as-a-feature-branch
  (let [fixture (fixture)]
    (try
      (let [result (run-script (:canonical fixture) "land-cleanup.sh"
                               ["main" (.getPath (:worktree fixture))
                                (:feature-head fixture)] {})]
        (is (not (zero? (:exit result))))
        (is (str/includes? (:output result) "feature branch must not be main"))
        (is (.exists (:worktree fixture))))
      (finally
        (test-support/delete-tree! (:root fixture))))))

(deftest cleanup-runs-the-explicit-repository-hook
  (let [fixture (fixture)
        hook-log (io/file (:root fixture) "cleanup.log")]
    (try
      (write-file! (:worktree fixture) ".millstrand/land-cleanup.sh"
                   (str "#!/bin/sh\nset -eu\n"
                        "printf '%s %s %s\\n' \"$LAND_EXPECTED_BRANCH\" "
                        "\"$LAND_EXPECTED_HEAD\" \"$(pwd -P)\" "
                        ">\"$LAND_CLEANUP_LOG\"\n")
                   true)
      (let [expected-worktree (.getCanonicalPath (:worktree fixture))
            expected (commit! (:worktree fixture) "add repository cleanup hook")]
        (test-support/run-git! (:worktree fixture) "push" "origin" branch)
        (assert-success
         (run-script (:canonical fixture) "land-cleanup.sh"
                     [branch (.getPath (:worktree fixture)) expected]
                     {"LAND_CLEANUP_LOG" (.getPath hook-log)}))
        (is (= (str branch " " expected " " expected-worktree "\n")
               (slurp hook-log))))
      (finally
        (test-support/delete-tree! (:root fixture))))))

(deftest cleanup-is-retryable-after-worktree-removal
  (let [fixture (fixture)
        expected (:feature-head fixture)
        args [branch (.getPath (:worktree fixture)) expected]]
    (try
      (assert-success (run-command! (:canonical fixture)
                                    (into ["sh" (.getPath (script-file "land-cleanup.sh"))]
                                          args)
                                    {}))
      (is (not (.exists (:worktree fixture))))
      (assert-success (run-command! (:canonical fixture)
                                    (into ["sh" (.getPath (script-file "land-cleanup.sh"))]
                                          args)
                                    {}))
      (finally
        (test-support/delete-tree! (:root fixture))))))

(deftest cleanup-refuses-a-subsequent-unlanded-commit
  (let [fixture (fixture)
        expected (:feature-head fixture)]
    (try
      (write-file! (:worktree fixture) "later.txt" "not landed\n" false)
      (let [later-head (commit! (:worktree fixture) "subsequent change")
            result (run-command! (:canonical fixture)
                                 ["sh" (.getPath (script-file "land-cleanup.sh"))
                                  branch (.getPath (:worktree fixture)) expected]
                                 {})]
        (is (not (zero? (:exit result))) (:output result))
        (is (re-find #"changed feature worktree" (:output result)))
        (is (.exists (:worktree fixture)))
        (is (= later-head
               (str/trim (test-support/run-git! (:worktree fixture)
                                                "rev-parse" "HEAD"))))
        (is (= later-head
               (str/trim (test-support/run-git! (:canonical fixture)
                                                "rev-parse" branch)))))
      (finally
        (test-support/delete-tree! (:root fixture))))))

(deftest cleanup-preserves-uncommitted-work
  (let [fixture (fixture)
        added (write-file! (:worktree fixture) "uncommitted.txt" "keep me\n" false)]
    (try
      (let [result (run-script (:canonical fixture) "land-cleanup.sh"
                               [branch (.getPath (:worktree fixture)) (:feature-head fixture)] {})]
        (is (not (zero? (:exit result))) (:output result))
        (is (re-find #"dirty feature worktree" (:output result)))
        (is (= "keep me\n" (slurp added))))
      (finally
        (test-support/delete-tree! (:root fixture))))))
