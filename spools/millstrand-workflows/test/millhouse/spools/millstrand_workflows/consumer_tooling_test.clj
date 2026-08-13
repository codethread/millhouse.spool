(ns millhouse.spools.millstrand-workflows.consumer-tooling-test
  "Contract tests for manual consumer tooling setup by repository style."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [millhouse.spools.millstrand-workflows.consumer-tooling :as tooling]
            [millhouse.spools.workflow :as workflow]))

(def ^:private params
  {:worktree "/tmp/consumer"
   :workspace "/tmp/consumer/.millstrand"})

(def ^:private routes
  [[:app #'tooling/configure-consumer-tooling-app]
   [:spool #'tooling/configure-consumer-tooling-spool]
   [:clojure-app #'tooling/configure-consumer-tooling-clojure-app]])

(defn- definition [var]
  @var)

(defn- step [definition id]
  (some #(when (= id (:id %)) %) (:steps definition)))

(defn- instruction [definition id]
  ((get-in (step definition id) [:attributes "workflow/instruction"])
   params))

(defn- precedes?
  [text first-needle second-needle]
  (let [first-index (str/index-of text first-needle)
        second-index (str/index-of text second-needle)]
    (and (some? first-index)
         (some? second-index)
         (< first-index second-index))))

(deftest params-require-an-explicit-consumer-world
  (is (s/valid? ::tooling/consumer-tooling-params params))
  (is (not (s/valid? ::tooling/consumer-tooling-params
                     (dissoc params :workspace))))
  (is (not (s/valid? ::tooling/consumer-tooling-params
                     (assoc params :worktree " ")))))

(deftest repository-style-choice-is-explicit
  (let [parent (definition #'tooling/configure-consumer-tooling)
        checkpoint (step parent :repository-style)
        choices (get-in checkpoint [:attributes "workflow/choice-details"])
        inspect-text (instruction parent :inspect-repository)]
    (is (= [:inspect-repository] (:depends-on checkpoint)))
    (is (= ":configure-consumer-tooling-app"
           (get-in choices ["app" "next"])))
    (is (= ":configure-consumer-tooling-spool"
           (get-in choices ["spool" "next"])))
    (is (= ":configure-consumer-tooling-clojure-app"
           (get-in choices ["clojure-app" "next"])))
    (is (nil? (get-in choices ["unsupported" "next"])))
    (is (str/includes? inspect-text "spool status"))
    (is (str/includes? inspect-text "app"))
    (is (str/includes? inspect-text "spool"))
    (is (str/includes? inspect-text "clojure-app"))))

(deftest every-route-is-an-ordinary-manual-sequence
  (doseq [[style definition-var] routes]
    (testing (name style)
      (let [route (definition definition-var)
            steps (:steps route)
            payload (workflow/compile route params)]
        (is (= [:bootstrap-kondo
                :align-tools-deps
                :configure-lsp
                :configure-lint
                :verify-tests
                :verify-weaver
                :handover]
               (mapv :id steps)))
        (is (= [nil
                [:bootstrap-kondo]
                [:align-tools-deps]
                [:configure-lsp]
                [:configure-lint]
                [:verify-tests]
                [:verify-weaver]]
               (mapv :depends-on steps)))
        (is (every? #(nil? (get-in % [:attributes "workflow/gate"])) steps))
        (is (every? #(nil? (get-in % [:attributes "shell/argv"])) steps))
        (is (every? #(nil? (:procedure %)) steps))
        (is (not (str/includes? (pr-str payload) "workflow/gate")))))))

(deftest every-standalone-route-bootstraps-before-lint
  (doseq [[style definition-var] routes]
    (testing (name style)
      (let [route (definition definition-var)
            steps (:steps route)
            bootstrap-step (step route :bootstrap-kondo)
            lint (step route :configure-lint)]
        (is (= :bootstrap-kondo (:id (first steps))))
        (is (nil? (:procedure bootstrap-step)))
        (let [bootstrap-text (instruction route :bootstrap-kondo)]
          (is (str/includes? bootstrap-text
                             "separate registered `bootstrap-kondo` run"))
          (is (str/includes? bootstrap-text
                             "first attempt exact target status"))
          (is (str/includes? bootstrap-text
                             "`strand --workspace /tmp/consumer/.millstrand spool status`"))
          (is (str/includes? bootstrap-text
                             "before evidence"))
          (is (str/includes? bootstrap-text
                             "`mill/no-selected-weaver` as the normal clean-worktree"))
          (is (str/includes? bootstrap-text
                             "`mill weaver start --workspace /tmp/consumer/.millstrand`"))
          (is (str/includes? bootstrap-text
                             "after-start evidence"))
          (is (str/includes? bootstrap-text
                             "exact structured startup failure"))
          (is (str/includes? bootstrap-text
                             "Only when the startup failure proves an acquisition invariant"))
          (is (str/includes? bootstrap-text
                             "only that proven `spools.edn` acquisition coordinate"))
          (is (str/includes? bootstrap-text
                             "approved remote root metadata"))
          (is (str/includes? bootstrap-text
                             "Do not guess"))
          (is (str/includes? bootstrap-text
                             "error/repair evidence"))
          (is (str/includes? bootstrap-text
                             "disposable target"))
          (is (str/includes? bootstrap-text
                             "after-start evidence"))
          (is (str/includes? bootstrap-text
                             "after-repair evidence"))
          (is (str/includes? bootstrap-text
                             "fail loudly and do not start the child"))
          (is (str/includes? bootstrap-text
                             "Never stop or restart"))
          (is (str/includes? bootstrap-text
                             "Only after successful before, after-start, or after-repair evidence"))
          (is (str/includes? bootstrap-text
                             "choose the consumer's `greenfield` or `brownfield`"))
          (is (str/includes? bootstrap-text
                             "child run id"))
          (is (str/includes? bootstrap-text
                             "selected mode"))
          (is (str/includes? bootstrap-text
                             ":bootstrap-kondo-done true"))
          (is (str/includes? bootstrap-text
                             "Do not treat the runtime-routed child checkpoint as a return"))
          (is (precedes? bootstrap-text
                         "before evidence"
                         "`mill/no-selected-weaver` as the normal clean-worktree"))
          (is (precedes? bootstrap-text
                         "`mill/no-selected-weaver` as the normal clean-worktree"
                         "`mill weaver start --workspace /tmp/consumer/.millstrand`"))
          (is (precedes? bootstrap-text
                         "`mill weaver start --workspace /tmp/consumer/.millstrand`"
                         "after-start evidence"))
          (is (precedes? bootstrap-text
                         "after-start evidence"
                         "Only when the startup failure proves an acquisition invariant"))
          (is (precedes? bootstrap-text
                         "Only when the startup failure proves an acquisition invariant"
                         "after-repair evidence"))
          (is (precedes? bootstrap-text
                         "after-repair evidence"
                         "Only after successful before, after-start, or after-repair evidence"))
          (is (precedes? bootstrap-text
                         "Only after successful before, after-start, or after-repair evidence"
                         "child run id")))
        (is (= [:bootstrap-kondo]
               (:depends-on (step route :align-tools-deps))))
        (is (= [:configure-lsp] (:depends-on lint)))
        (let [lint-text (instruction route :configure-lint)]
          (is (str/includes? lint-text "preceding registered `bootstrap-kondo`"))
          (is (str/includes? lint-text "missing import directories"))
          (is (str/includes? lint-text "nonzero lint result"))
          (is (str/includes? lint-text "cannot be handed over"))
          (is (str/includes? lint-text "Explicitly lint and diagnose"))
          (is (str/includes? lint-text "newly exposed `.millstrand`"))
          (is (str/includes? lint-text "namespace/path mismatch"))
          (is (str/includes? lint-text "coherent migration"))
          (is (str/includes? lint-text "one-file opportunistic rename"))
          (is (str/includes? lint-text "new-basis error")))))))

(deftest application-route-preserves-an-empty-product-base
  (let [route (definition #'tooling/configure-consumer-tooling-app)]
    (is (str/includes? (instruction route :align-tools-deps) ":paths []"))
    (is (str/includes? (instruction route :align-tools-deps)
                       "`.millstrand/deps.edn`"))
    (is (str/includes? (instruction route :align-tools-deps)
                       "require every external namespace"))
    (is (str/includes? (instruction route :configure-lsp)
                       "select nested `.millstrand/deps.edn`"))
    (is (str/includes? (instruction route :configure-lsp)
                       "clojure -Srepro -Sdeps .millstrand/deps.edn -Spath -M:lint"))
    (is (str/includes? (instruction route :configure-lsp)
                       "`:project-path` only"))
    (is (str/includes? (instruction route :configure-lsp)
                       "fresh LSP cache"))
    (is (str/includes? (instruction route :configure-lsp)
                       "external var definitions in the fresh LSP index"))
    (is (str/includes? (instruction route :configure-lsp)
                       "actual classpath roots"))
    (is (str/includes? (instruction route :configure-lsp)
                       "external var"))
    (is (str/includes? (instruction route :configure-lsp)
                       "Broad editor search or source-path visibility is not proof"))
    (is (str/includes? (instruction route :configure-lsp)
                       "best-effort human acceptance"))
    (is (str/includes? (instruction route :configure-lsp)
                       "Do not fail solely because no compatible editor"))
    (is (str/includes? (instruction route :configure-lint)
                       "Do not count syntax-only lint as hook proof"))
    (is (str/includes? (instruction route :verify-tests)
                       "discover and run the repository's existing test command"))
    (is (str/includes? (instruction route :verify-tests)
                       "repository-owned test configuration as ordinary Clojure"))
    (is (str/includes? (instruction route :verify-tests)
                       "do not prescribe a test runner"))
    (is (str/includes? (instruction route :verify-tests)
                       "add test scaffolding"))
    (is (str/includes? (instruction route :verify-tests)
                       "documented disposable-Weaver pattern"))
    (is (str/includes? (instruction route :verify-tests)
                       "coordinate or LSP projection alone does not require a new fixture"))
    (is (str/includes? (instruction route :verify-weaver)
                       "invoke one of its real operations"))))

(deftest spool-route-proves-publication-through-an-approved-world
  (let [route (definition #'tooling/configure-consumer-tooling-spool)]
    (is (str/includes? (instruction route :align-tools-deps)
                       "consumer approval in `spools.edn`"))
    (is (str/includes? (instruction route :align-tools-deps)
                       "every external namespace required by `.millstrand/init.clj`"))
    (is (str/includes? (instruction route :align-tools-deps)
                       "ct.spools.delegation"))
    (is (str/includes? (instruction route :align-tools-deps)
                       "ct.spools/delegation"))
    (is (str/includes? (instruction route :align-tools-deps)
                       "exact `:deps/root`"))
    (is (str/includes? (instruction route :align-tools-deps)
                       ":deps/root \"delegation\""))
    (is (str/includes? (instruction route :align-tools-deps)
                       ":git/sha \"RECORDED_SHA\""))
    (is (str/includes? (instruction route :align-tools-deps)
                       "current spool status/root metadata"))
    (is (str/includes? (instruction route :align-tools-deps)
                       "any unresolved placeholder in a"))
    (is (str/includes? (instruction route :align-tools-deps)
                       "committed consumer coordinate must fail"))
    (is (str/includes? (instruction route :align-tools-deps)
                       "RECORDED_SHA` is an example only"))
    (is (str/includes? (instruction route :align-tools-deps)
                       "stop on any missing namespace"))
    (is (str/includes? (instruction route :align-tools-deps)
                       "portable authoring project"))
    (is (str/includes? (instruction route :align-tools-deps)
                       "cannot be the only coordinate"))
    (is (str/includes? (instruction route :configure-lsp)
                       "clean fetched Git checkout"))
    (is (str/includes? (instruction route :configure-lsp)
                       "cache-isolated directory"))
    (is (str/includes? (instruction route :configure-lsp)
                       "directly require every namespace in the inventory"))
    (is (str/includes? (instruction route :configure-lsp)
                       "ct.spools/delegation"))
    (is (str/includes? (instruction route :configure-lsp)
                       "cached/partial"))
    (is (str/includes? (instruction route :configure-lsp)
                       "fresh LSP index contains that dependency's external var definitions"))
    (is (str/includes? (instruction route :configure-lsp)
                       "actual classpath roots"))
    (is (str/includes? (instruction route :configure-lsp)
                       "external var definitions"))
    (is (str/includes? (instruction route :configure-lsp)
                       "source-path visibility is not proof"))
    (is (str/includes? (instruction route :configure-lsp)
                       "both navigation hops as best-effort"))
    (is (str/includes? (instruction route :configure-lsp)
                       "Editor absence alone does not fail"))
    (is (str/includes? (instruction route :configure-lint)
                       "own producer coordinate"))
    (is (str/includes? (instruction route :verify-tests)
                       "discover and run the repository's existing test command"))
    (is (str/includes? (instruction route :verify-weaver)
                       "`:spools`-guarded namespace module"))))

(deftest clojure-application-route-preserves-the-ordinary-application
  (let [route (definition #'tooling/configure-consumer-tooling-clojure-app)]
    (is (str/includes? (instruction route :align-tools-deps)
                       "preserve the ordinary application's base"))
    (is (str/includes? (instruction route :align-tools-deps)
                       "separate tooling alias"))
    (is (str/includes? (instruction route :align-tools-deps)
                       "directly require every external namespace"))
    (is (str/includes? (instruction route :configure-lsp)
                       "base application basis remains unchanged"))
    (is (str/includes? (instruction route :configure-lsp)
                       "fresh LSP cache"))
    (is (str/includes? (instruction route :configure-lsp)
                       "actual classpath roots"))
    (is (str/includes? (instruction route :configure-lsp)
                       "external var definitions"))
    (is (str/includes? (instruction route :configure-lsp)
                       "source-path visibility is not proof"))
    (is (str/includes? (instruction route :configure-lsp)
                       "best-effort human acceptance"))
    (is (str/includes? (instruction route :configure-lsp)
                       "Do not fail solely for absent editor support"))
    (is (str/includes? (instruction route :verify-tests)
                       "discover and run the repository's existing test command"))
    (is (str/includes? (instruction route :verify-tests)
                       "coordinate or LSP projection alone does not require a new fixture"))
    (is (str/includes? (instruction route :verify-weaver)
                       "result came from ordinary application code"))))

(deftest pending-generation-evidence-is-not-called-adopted-proof
  (doseq [[style definition-var] routes]
    (testing (name style)
      (let [route (definition definition-var)
            verify-text (instruction route :verify-weaver)
            handover-text (instruction route :handover)]
        (is (str/includes? verify-text "Do not restart"))
        (is (str/includes? verify-text "run that same style-specific probe"))
        (is (str/includes? verify-text "current-generation-only evidence"))
        (is (str/includes? verify-text "not adoption proof"))
        (is (str/includes? verify-text "adopted generation as unfinished"))
        (is (str/includes? handover-text "prepared configuration"))
        (is (str/includes? handover-text "preserving the test result evidence"))
        (is (str/includes? handover-text "adopted generation proof"))
        (is (str/includes? handover-text "unfinished"))))))
