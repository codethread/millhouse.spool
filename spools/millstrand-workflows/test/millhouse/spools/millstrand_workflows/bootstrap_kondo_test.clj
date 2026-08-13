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

(defn- instruction [definition id]
  ((get-in (step definition id) [:attributes "workflow/instruction"]) params))

(def ^:private bootstrap-routes
  [#'bootstrap/bootstrap-kondo
   #'bootstrap/bootstrap-kondo-greenfield
   #'bootstrap/bootstrap-kondo-brownfield])

(def ^:private executor-attributes
  ["workflow/gate" "shell/argv" "shell/cwd" "shell/timeout"
   "shell/timeout-secs"])

(defn- has-executor-attribute? [step]
  (some #(contains? (:attributes step) %) executor-attributes))

(defn- precedes? [text first-needle second-needle]
  (let [first-index (str/index-of text first-needle)
        second-index (str/index-of text second-needle)]
    (and (some? first-index)
         (some? second-index)
         (< first-index second-index))))

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

(deftest every-bootstrap-route-is-agent-led
  (doseq [definition-var bootstrap-routes]
    (let [route (definition definition-var)
          compiled (workflow/compile route params)]
      (is (every? (complement has-executor-attribute?) (:steps route)))
      (is (every? (complement has-executor-attribute?) (:strands compiled)))
      (is (not-any? #(contains? (:attributes %) "workflow/gate")
                    (:steps route)))
      (is (not-any? #(contains? (:attributes %) "workflow/gate")
                    (:strands compiled))))))

(deftest both-routes-use-one-agent-resolved-classpath-import
  (doseq [[definition-var mode]
          [[#'bootstrap/bootstrap-kondo-greenfield :greenfield]
           [#'bootstrap/bootstrap-kondo-brownfield :brownfield]]]
    (let [definition (definition definition-var)
          prepare (step definition :prepare)
          ensure-kondo (step definition :ensure-kondo)
          copy (step definition :copy-configs)
          validate (step definition :validate)
          quality (step definition :discover-quality)
          handover (step definition :handover)]
      (let [prepare-instruction ((get-in prepare [:attributes "workflow/instruction"])
                                 params)]
        (is (str/includes? prepare-instruction (name mode)))
        (is (str/includes? prepare-instruction ".clj-kondo/.cache/"))
        (is (str/includes? prepare-instruction "repository configuration"))
        (is (str/includes? prepare-instruction ".lsp/config.edn"))
        (is (str/includes? prepare-instruction ":copy-kondo-configs? false"))
        (is (str/includes? prepare-instruction "other existing LSP setting"))
        (is (str/includes? prepare-instruction "resulting LSP setting")))
      (is (= [":prepare"] (mapv str (:depends-on ensure-kondo))))
      (let [instruction ((get-in ensure-kondo [:attributes "workflow/instruction"])
                         params)]
        (is (str/includes? instruction "repository's Makefile"))
        (is (str/includes? instruction "clojure -M:lint"))
        (is (str/includes? instruction "command -v clj-kondo"))
        (is (str/includes? instruction "KONDO_CMD --version"))
        (is (str/includes? instruction
                           "https://github.com/clj-kondo/clj-kondo/blob/master/doc/install.md"))
        (is (str/includes? instruction "user choose the installation method"))
        (is (str/includes? instruction "explicit approval"))
        (is (str/includes? instruction "stop before importing configs"))
        (is (str/includes? instruction "do not reinstall")))
      (is (= [":ensure-kondo"] (mapv str (:depends-on copy))))
      (is (nil? (get-in copy [:attributes "workflow/gate"])))
      (is (nil? (get-in copy [:attributes "shell/argv"])))
      (is (nil? (get-in copy [:attributes "shell/cwd"])))
      (let [instruction ((get-in copy [:attributes "workflow/instruction"]) params)]
        (is (str/includes? instruction
                           "strand --workspace /tmp/consumer/.millstrand spool status"))
        (is (str/includes? instruction "sync.root"))
        (is (str/includes? instruction "deps.edn"))
        (is (str/includes? instruction ":paths"))
        (is (str/includes? instruction "millstrand/source-root"))
        (is (str/includes? instruction "derive `BASE`"))
        (is (str/includes? instruction "removing exactly"))
        (is (str/includes? instruction "normalized `BASE` joined"))
        (is (str/includes? instruction "BASE/deps.edn"))
        (is (str/includes? instruction "including `resources`"))
        (is (str/includes? instruction
                           "BASE/resources/clj-kondo.exports/io.millstrand/millstrand/"))
        (is (str/includes? instruction "installed spool root normally"))
        (is (str/includes? instruction "Never search upward, guess `BASE`"))
        (is (str/includes? instruction "exact family/root coordinate"))
        (is (str/includes? instruction "failing path"))
        (is (str/includes? instruction "permitted corrective invariant"))
        (is (str/includes? instruction "readable regular `BASE/deps.edn`"))
        (is (str/includes? instruction "Never silently fall back"))
        (is (str/includes? instruction
                           "absent `:paths` defaults to the `src` path"))
        (is (str/includes? instruction "explicit `:paths []` remains empty"))
        (is (str/includes? instruction "consumer Clojure classpath"))
        (is (str/includes? instruction "clojure -Spath` alone is insufficient"))
        (is (str/includes? instruction "consumer `deps.edn` has `:paths []`"))
        (is (str/includes? instruction "installed-spool contribution is empty"))
        (is (str/includes? instruction
                           "KONDO_CMD --lint RESOLVED_CLASSPATH --dependencies --parallel"))
        (is (str/includes? instruction "--copy-configs --skip-lint"))
        (is (= 1 (count (re-seq #"KONDO_CMD --lint" instruction))))
        (is (not (str/includes? instruction
                                "clj-kondo --lint \"$(clojure -Spath)\"")))
        (is (str/includes? instruction "exact roots and final canonical classpath"))
        (is (str/includes? instruction "Do not require GitHub, GitLab, or `jq`")))
      (let [validate-instruction ((get-in validate [:attributes
                                                    "workflow/instruction"])
                                  params)]
        (is (str/includes? validate-instruction "test -f .lsp/config.edn"))
        (is (str/includes? validate-instruction ":copy-kondo-configs? false"))
        (is (str/includes? validate-instruction "false; observed"))
        (is (str/includes? validate-instruction "(pr-str observed)"))
        (is (str/includes? validate-instruction "git diff --check"))
        (is (str/includes? validate-instruction
                           "git check-ignore -q --no-index .clj-kondo/.cache/"))
        (is (str/includes? validate-instruction
                           "git ls-files '.clj-kondo/.cache/**'"))
        (is (str/includes? validate-instruction "provenance"))
        (is (str/includes? validate-instruction "duplicate"))
        (is (str/includes? validate-instruction
                           "tracked `.clj-kondo/.cache`"))
        (is (str/includes? validate-instruction "sole import owner"))
        (is (str/includes? validate-instruction "consumer repository's"))
        (is (str/includes? validate-instruction
                           "repository-relative self-import"))
        (is (str/includes? validate-instruction "Legitimate Millhouse")))
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
          handover-instruction ((get-in handover [:attributes "workflow/instruction"])
                                params)]
      (is (not (str/includes? ((get-in validate [:attributes
                                                 "workflow/instruction"])
                               params)
                              ".clj-kondo/imports/millhouse.spools")))
      (is (str/includes? ((get-in validate [:attributes "workflow/instruction"])
                          params)
                         "before import"))
      (is (str/includes? ((get-in validate [:attributes "workflow/instruction"])
                          params)
                         "immediately after import"))
      (is (str/includes? handover-instruction
                         "consumer self-import result before/during/after quality")))))

(deftest local-self-filters-owned-producer-roots-before-copy
  (let [definition (definition #'bootstrap/bootstrap-kondo-greenfield)
        copy-text (instruction definition :copy-configs)
        validate-text (instruction definition :validate)]
    (is (str/includes? copy-text "owned-roots"))
    (is (str/includes? copy-text "Millhouse local-self"))
    (is (str/includes? copy-text "owned producer classpath or export path"))
    (is (str/includes? copy-text "fail loudly before import"))
    (is (precedes? copy-text "owned producer classpath or export path"
                   "KONDO_CMD --lint RESOLVED_CLASSPATH"))
    (is (str/includes? copy-text "do not copy first and remove self-imports afterward"))
    (is (str/includes? validate-text "Reuse the exact `owned-roots` table"))
    (is (str/includes? validate-text "every owned producer export is absent"))
    (is (str/includes? validate-text "For a Millhouse local-self root"))))

(deftest final-classpath-filters-consumer-reintroduction-before-command
  (let [copy-text (instruction (definition #'bootstrap/bootstrap-kondo-greenfield)
                               :copy-configs)]
    (is (str/includes? copy-text
                       "Canonicalize every installed contribution entry"))
    (is (str/includes? copy-text
                       "every entry in the consumer Clojure classpath"))
    (is (str/includes? copy-text
                       "every owned producer classpath or export comparison path"))
    (is (precedes? copy-text "Canonicalize every installed contribution entry"
                   "Combine the canonical contributions"))
    (is (precedes? copy-text
                   "every owned producer classpath or export comparison path"
                   "Combine the canonical contributions"))
    (is (str/includes? copy-text "Any failed canonicalization is a loud pre-copy failure"))
    (is (str/includes? copy-text
                       "apply ownership classification to that final combined classpath"))
    (is (str/includes? copy-text "consumer `clojure -Spath` reintroduction"))
    (is (str/includes? copy-text "descendant of one"))
    (is (str/includes? copy-text "cannot be canonicalized or reconciled"))
    (is (precedes? copy-text "final combined classpath"
                   "KONDO_CMD --lint RESOLVED_CLASSPATH"))
    (is (str/includes? copy-text
                       "Immediately before `KONDO_CMD`, assert"))
    (is (str/includes? copy-text
                       "assert using only canonical paths"))
    (is (str/includes? copy-text
                       "Every `RESOLVED_CLASSPATH` entry must be canonical"))
    (is (str/includes? copy-text
                       "canonical owned export paths themselves are absent"))))

(deftest ordinary-remote-retains-dependency-producer-exports
  (let [definition (definition #'bootstrap/bootstrap-kondo-greenfield)
        copy-text (instruction definition :copy-configs)
        validate-text (instruction definition :validate)]
    (is (str/includes? copy-text "Keep every non-owned dependency export"))
    (is (str/includes? copy-text "including a pinned remote `millhouse/spools` family"))
    (is (str/includes? copy-text "Keep the explicit `millstrand/source-root` contribution"))
    (is (str/includes? validate-text
                       "for an ordinary pinned remote Millhouse family, retain"))
    (is (str/includes? validate-text "present exactly once"))
    (is (not (str/includes? validate-text "reject every Millhouse producer import")))))

(deftest bootstrap-does-not-fix-a-repository-quality-command
  (let [compiled (workflow/compile (definition #'bootstrap/bootstrap-kondo-greenfield)
                                   params)
        refs (set (map :ref (:strands compiled)))]
    (is (not-any? #(str/includes? (name %) "make") refs))
    (is (not-any? #(str/includes? (name %) "github") refs))
    (is (not-any? #(str/includes? (name %) "push") refs))))

(deftest codethread-paths-empty-greenfield-requires-millstrand-base-resources
  (let [definition (definition #'bootstrap/bootstrap-kondo-greenfield)
        copy (step definition :copy-configs)
        instruction ((get-in copy [:attributes "workflow/instruction"]) params)]
    ;; CodeThread's consumer has `:paths []`; existing brownfield imports must
    ;; not be able to make this contract appear satisfied. The source-root
    ;; contribution is independently required and names both the installed
    ;; sibling root and the Millstrand project base export.
    (is (str/includes? instruction "consumer `deps.edn` has `:paths []`"))
    (is (str/includes? instruction "millstrand/source-root"))
    (is (str/includes? instruction "installed spool root normally"))
    (is (str/includes? instruction
                       "BASE/resources/clj-kondo.exports/io.millstrand/millstrand/"))
    (is (str/includes? instruction "consumer Clojure classpath"))
    (is (str/includes? instruction "KONDO_CMD --lint RESOLVED_CLASSPATH"))
    (is (not (str/includes? instruction "existing imports are sufficient")))
    (is (not (str/includes? instruction "brownfield imports satisfy")))))
