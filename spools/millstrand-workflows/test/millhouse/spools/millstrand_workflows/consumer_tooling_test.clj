(ns millhouse.spools.millstrand-workflows.consumer-tooling-test
  "Contract tests for manual consumer tooling setup by repository style."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [millhouse.spools.millstrand-workflows.consumer-tooling :as tooling]
            [millhouse.spools.workflow :as workflow]))

(def ^:private params
  {:worktree "/tmp/consumer"
   :workspace "/tmp/consumer/.millstrand"
   :invocation-producer {:kind "pinned-remote-family"
                         :family "millhouse/spools"
                         :coordinate {:git/url "https://github.com/codethread/millhouse.spool.git"
                                      :git/sha "0123456789012345678901234567890123456789"}}})

(def ^:private workflow-host-params
  (assoc params :workspace "/tmp/workflow-host/.millstrand"))

(def ^:private routes
  [[:app #'tooling/configure-consumer-tooling-app]
   [:spool #'tooling/configure-consumer-tooling-spool]
   [:clojure-app #'tooling/configure-consumer-tooling-clojure-app]])

(defn- definition [var]
  @var)

(defn- step [definition id]
  (some #(when (= id (:id %)) %) (:steps definition)))

(defn- instruction [definition id]
  ((get-in (step definition id) [:attributes "workflow/instruction"]) params))

(defn- instruction-with-params [definition id instruction-params]
  ((get-in (step definition id) [:attributes "workflow/instruction"])
   instruction-params))

(defn- precedes?
  [text first-needle second-needle]
  (let [first-index (str/index-of text first-needle)
        second-index (str/index-of text second-needle)]
    (and (some? first-index)
         (some? second-index)
         (< first-index second-index))))

(deftest params-require-an-explicit-consumer-world-and-producer-coordinate
  (is (s/valid? ::tooling/consumer-tooling-params params))
  (is (not (s/valid? ::tooling/consumer-tooling-params
                     (dissoc params :workspace))))
  (is (not (s/valid? ::tooling/consumer-tooling-params
                     (dissoc params :invocation-producer))))
  (is (s/valid? ::tooling/invocation-producer
                (:invocation-producer params)))
  (is (s/valid? ::tooling/invocation-producer
                {:kind "local-self-root"
                 :family "millhouse/spools"
                 :root "spools/millstrand-workflows"
                 :coordinate {:local/root "/tmp/millhouse"}}))
  (is (not (s/valid? ::tooling/invocation-producer
                     (assoc (:invocation-producer params)
                            :coordinate {:git/url "https://example.test/millhouse.git"
                                         :git/sha "18f2f43"}))))
  (is (not (s/valid? ::tooling/consumer-tooling-params
                     (assoc params :worktree " ")))))

(deftest consumer-world-cannot-be-confused-with-workflow-host
  (is (not (s/valid? ::tooling/consumer-tooling-params workflow-host-params)))
  (doseq [[style definition-var] routes]
    (testing (name style)
      (let [route (definition definition-var)
            bootstrap-text (instruction-with-params route :bootstrap-kondo
                                                    workflow-host-params)
            inspect-text (instruction-with-params (definition #'tooling/configure-consumer-tooling)
                                                  :inspect-repository
                                                  workflow-host-params)
            handover-text (instruction-with-params route :handover
                                                   workflow-host-params)]
        (is (str/includes? bootstrap-text
                           "derive the target consumer world as `/tmp/consumer/.millstrand`"))
        (is (str/includes? bootstrap-text
                           "{:worktree \"/tmp/consumer\" :workspace \"/tmp/consumer/.millstrand\"}"))
        (is (str/includes? bootstrap-text "never substitute the disposable workflow-host workspace"))
        (is (str/includes? inspect-text "/tmp/consumer/.millstrand"))
        (is (str/includes? handover-text "/tmp/consumer/.millstrand"))
        (is (not (str/includes? bootstrap-text "/tmp/workflow-host/.millstrand")))
        (is (not (str/includes? inspect-text "/tmp/workflow-host/.millstrand")))
        (is (not (str/includes? handover-text "/tmp/workflow-host/.millstrand")))))))

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

(deftest inspection-acquires-exact-consumer-weaver-before-classification
  (let [parent (definition #'tooling/configure-consumer-tooling)
        inspect-text (instruction parent :inspect-repository)]
    (is (str/includes? inspect-text
                       "First run the exact command `strand --workspace /tmp/consumer/.millstrand spool status`"))
    (is (str/includes? inspect-text
                       "If the result is `mill/no-selected-weaver`"))
    (is (str/includes? inspect-text
                       "`mill weaver start --workspace /tmp/consumer/.millstrand`"))
    (is (str/includes? inspect-text "record the start command, PID"))
    (is (str/includes? inspect-text "Weaver/root identities"))
    (is (str/includes? inspect-text "Rerun the same exact status command"))
    (is (str/includes? inspect-text "read shared and local spool approvals"))
    (is (str/includes? inspect-text "inspect the structured result"))
    (is (str/includes? inspect-text "Never start or restart a canonical or already-running Weaver"))
    (is (precedes? inspect-text
                   "First run the exact command"
                   "If the result is `mill/no-selected-weaver`"))
    (is (precedes? inspect-text
                   "If the result is `mill/no-selected-weaver`"
                   "`mill weaver start --workspace /tmp/consumer/.millstrand`"))
    (is (precedes? inspect-text
                   "`mill weaver start --workspace /tmp/consumer/.millstrand`"
                   "Rerun the same exact status command"))
    (is (precedes? inspect-text
                   "Rerun the same exact status command"
                   "read shared and local spool approvals"))
    (is (precedes? inspect-text
                   "read shared and local spool approvals"
                   "Choose `app`"))))

(deftest every-route-is-an-ordinary-manual-sequence
  (doseq [[style definition-var] routes]
    (testing (name style)
      (let [route (definition definition-var)
            steps (:steps route)
            payload (workflow/compile route params)]
        (is (= [:prove-invocation-producer
                :bootstrap-kondo
                :align-tools-deps
                :configure-lsp
                :configure-lint
                :verify-tests
                :verify-weaver
                :handover]
               (mapv :id steps)))
        (is (= [nil
                [:prove-invocation-producer]
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
        (is (= :prove-invocation-producer (:id (first steps))))
        (is (nil? (:procedure bootstrap-step)))
        (let [producer-text (instruction route :prove-invocation-producer)]
          (is (str/includes? producer-text "exact invocation-producer contract"))
          (is (str/includes? producer-text "pinned-remote-family"))
          (is (str/includes? producer-text "local-self-root"))
          (is (str/includes? producer-text "selected `spools.edn` activation"))
          (is (str/includes? producer-text "every relevant Millhouse root"))
          (is (str/includes? producer-text "one version-coherent family coordinate"))
          (is (str/includes? producer-text "manually edit only the applicable"))
          (is (str/includes? producer-text "record the before and after values"))
          (is (str/includes? producer-text "Stop only when metadata is missing"))
          (is (str/includes? producer-text "Do not infer the running workflow SHA"))
          (is (str/includes? producer-text "automate these edits")))
        (let [bootstrap-text (instruction route :bootstrap-kondo)]
          (is (str/includes? bootstrap-text
                             "separate registered `bootstrap-kondo` run"))
          (is (str/includes? bootstrap-text
                             "Confirm the exact target acquired by the preceding repository inspection"))
          (is (str/includes? bootstrap-text
                             "`strand --workspace /tmp/consumer/.millstrand spool status`"))
          (is (str/includes? bootstrap-text "idempotent confirmation"))
          (is (str/includes? bootstrap-text
                             "If status reports `mill/no-selected-weaver`, fail loudly"))
          (is (str/includes? bootstrap-text "do not start or restart a Weaver here"))
          (is (str/includes? bootstrap-text "Never stop or restart"))
          (is (precedes? bootstrap-text
                         "Confirm the exact target acquired"
                         "If status reports `mill/no-selected-weaver`"))
          (is (precedes? bootstrap-text
                         "If status reports `mill/no-selected-weaver`"
                         "start a separate registered"))
          (is (not (str/includes? bootstrap-text
                                  "mill weaver start --workspace /tmp/consumer/.millstrand"))))
        (is (= [:prove-invocation-producer]
               (:depends-on (step route :bootstrap-kondo))))
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
