(ns millhouse.spools.millstrand-workflows.bootstrap-kondo-test
  "Contract tests for first-time Millstrand Kondo adoption."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [millhouse.spools.millstrand-workflows.bootstrap-kondo :as bootstrap]
            [millhouse.spools.workflow :as workflow]))

(def ^:private params
  {:worktree "/tmp/consumer"
   :workspace "/tmp/consumer/.millstrand"})

(defn- definition [var]
  @var)

(defn- step [definition id]
  (some #(when (= id (:id %)) %) (:steps definition)))

(deftest params-require-explicit-local-world
  (is (s/valid? ::bootstrap/bootstrap-kondo-params params))
  (is (not (s/valid? ::bootstrap/bootstrap-kondo-params
                     (dissoc params :workspace))))
  (is (not (s/valid? ::bootstrap/bootstrap-kondo-params
                     (assoc params :worktree " ")))))

(deftest adoption-mode-is-asked-before-route-specific-work
  (let [main (definition #'bootstrap/bootstrap-kondo)
        checkpoint (step main :adoption-mode)
        choices (get-in checkpoint [:attributes "workflow/choice-details"])]
    (is (= [:select-world] (:depends-on checkpoint)))
    (is (= ":bootstrap-kondo-greenfield"
           (get-in choices ["greenfield" "next"])))
    (is (= ":bootstrap-kondo-brownfield"
           (get-in choices ["brownfield" "next"])))
    (is (str/includes? (get-in choices ["greenfield" "description"])
                       "minimal"))
    (is (str/includes? (get-in choices ["brownfield" "description"])
                       "Inventory"))))

(deftest both-routes-use-one-agent-resolved-classpath-import
  (doseq [[definition-var mode]
          [[#'bootstrap/bootstrap-kondo-greenfield :greenfield]
           [#'bootstrap/bootstrap-kondo-brownfield :brownfield]]]
    (let [definition (definition definition-var)
          prepare (step definition :prepare)
          copy (step definition :copy-configs)
          validate (step definition :validate)
          quality (step definition :discover-quality)
          handover (step definition :handover)
          validate-command (nth (get-in validate [:attributes "shell/argv"]) 2)]
      (let [prepare-instruction ((get-in prepare [:attributes "workflow/instruction"])
                                 params)]
        (is (str/includes? prepare-instruction (name mode)))
        (is (str/includes? prepare-instruction ".clj-kondo/.cache/"))
        (is (str/includes? prepare-instruction "repository configuration"))
        (is (str/includes? prepare-instruction ".lsp/config.edn"))
        (is (str/includes? prepare-instruction ":copy-kondo-configs? false"))
        (is (str/includes? prepare-instruction "other existing LSP setting"))
        (is (str/includes? prepare-instruction "resulting LSP setting")))
      (is (= [":prepare"] (mapv str (:depends-on copy))))
      (is (nil? (get-in copy [:attributes "workflow/gate"])))
      (is (nil? (get-in copy [:attributes "shell/argv"])))
      (is (nil? (get-in copy [:attributes "shell/cwd"])))
      (let [instruction ((get-in copy [:attributes "workflow/instruction"]) params)]
        (is (str/includes? instruction
                           "strand --workspace /tmp/consumer/.millstrand spool status"))
        (is (str/includes? instruction "sync.root"))
        (is (str/includes? instruction "deps.edn"))
        (is (str/includes? instruction ":paths"))
        (is (str/includes? instruction
                           "absent `:paths` defaults to the `src` path"))
        (is (str/includes? instruction "explicit `:paths []` remains empty"))
        (is (str/includes? instruction "consumer Clojure classpath"))
        (is (str/includes? instruction "clojure -Spath` alone is insufficient"))
        (is (str/includes? instruction "consumer `deps.edn` has `:paths []`"))
        (is (str/includes? instruction "installed-spool contribution is empty"))
        (is (str/includes? instruction
                           "clj-kondo --lint RESOLVED_CLASSPATH --dependencies --parallel"))
        (is (str/includes? instruction "--copy-configs --skip-lint"))
        (is (= 1 (count (re-seq #"clj-kondo --lint" instruction))))
        (is (not (str/includes? instruction
                                "clj-kondo --lint \"$(clojure -Spath)\"")))
        (is (str/includes? instruction "exact roots and final classpath"))
        (is (str/includes? instruction "Do not require GitHub, GitLab, or `jq`")))
      (is (str/includes? validate-command
                         "git check-ignore -q --no-index .clj-kondo/.cache/"))
      (is (str/includes? validate-command
                         "git ls-files '.clj-kondo/.cache/**'"))
      (is (str/includes? validate-command "test -f .lsp/config.edn"))
      (is (str/includes? validate-command ":copy-kondo-configs? false"))
      (is (str/includes? validate-command "false; observed"))
      (is (str/includes? validate-command "(pr-str observed)"))
      (is (not (str/includes? validate-command
                              "millhouse.spools")))
      (is (str/includes? ((get-in validate [:attributes "workflow/instruction"])
                          params)
                         "provenance"))
      (is (str/includes? ((get-in validate [:attributes "workflow/instruction"])
                          params)
                         "duplicate"))
      (is (str/includes? ((get-in validate [:attributes "workflow/instruction"])
                          params)
                         "tracked `.clj-kondo/.cache`"))
      (is (str/includes? ((get-in validate [:attributes "workflow/instruction"])
                          params)
                         ".lsp/config.edn"))
      (is (str/includes? ((get-in validate [:attributes "workflow/instruction"])
                          params)
                         "sole import owner"))
      (is (str/includes? ((get-in validate [:attributes "workflow/instruction"])
                          params)
                         "consumer repository's"))
      (is (str/includes? ((get-in validate [:attributes "workflow/instruction"])
                          params)
                         "repository-relative self-import"))
      (is (str/includes? ((get-in validate [:attributes "workflow/instruction"])
                          params)
                         "Legitimate Millhouse"))
      (is (str/includes? ((get-in quality [:attributes "workflow/instruction"])
                          params)
                         "discover"))
      (is (str/includes? ((get-in handover [:attributes "workflow/instruction"])
                          params)
                         "precise local handover")))))

(deftest both-routes-require-no-self-import-before-during-and-after-quality
  (doseq [definition-var [#'bootstrap/bootstrap-kondo-greenfield
                          #'bootstrap/bootstrap-kondo-brownfield]]
    (let [definition (definition definition-var)
          validate (step definition :validate)
          handover (step definition :handover)
          validate-command (nth (get-in validate [:attributes "shell/argv"]) 2)
          handover-instruction ((get-in handover [:attributes "workflow/instruction"])
                                params)]
      (is (not (str/includes? validate-command
                              ".clj-kondo/imports/millhouse.spools")))
      (is (str/includes? ((get-in validate [:attributes "workflow/instruction"])
                          params)
                         "before import"))
      (is (str/includes? ((get-in validate [:attributes "workflow/instruction"])
                          params)
                         "immediately after import"))
      (is (str/includes? handover-instruction
                         "consumer self-import result before/during/after quality")))))

(deftest bootstrap-does-not-fix-a-repository-quality-command
  (let [compiled (workflow/compile (definition #'bootstrap/bootstrap-kondo-greenfield)
                                   params)
        refs (set (map :ref (:strands compiled)))]
    (is (not-any? #(str/includes? (name %) "make") refs))
    (is (not-any? #(str/includes? (name %) "github") refs))
    (is (not-any? #(str/includes? (name %) "push") refs))))
