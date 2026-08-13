(ns millhouse.spools.millstrand-workflows.consumer-tooling
  "Agent-owned tooling setup for Millstrand consumer repository styles.

  The parent workflow records whether a consumer is an application-only
  repository, a spool library, or a Clojure application. Each continuation
  adapts tools.deps, LSP, clj-kondo, tests, and Weaver verification through
  ordinary manual steps. No executor gate assumes a universal repository
  layout or quality command."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millstrand.api.format.alpha :as fmt]
            [millhouse.spools.workflow :as workflow]))

(defn- non-blank-string?
  "Return true when `value` is a non-blank string."
  [value]
  (and (string? value)
       (not (str/blank? value))))

(s/def ::non-blank-string non-blank-string?)
(s/def ::worktree ::non-blank-string)
(s/def ::workspace ::non-blank-string)
(s/def ::consumer-tooling-params
  (s/keys :req-un [::worktree ::workspace]))

(defn- inspect-repository-instruction
  [{:keys [worktree workspace]}]
  (fmt/reflow
   (format
    "|In worktree `%s` and selected workspace `%s`, inspect the repository before
     |choosing a style. Read shared and local spool approvals, then run
     |`strand --workspace %s spool status` and inspect the structured result.
     |Record every current approved root, its coordinate and `sync.root`, the
     |latest refresh result, and any pending generation. Inspect `deps.edn`,
     |`.lsp/config.edn`, `.clj-kondo/config.edn`, test paths, Makefile, scripts,
     |and CI or contributor guidance without editing them yet. Choose
     |`app` when the product has no Clojure application source beyond Millstrand
     |config, `spool` when this repository publishes one or more spool roots, or
     |`clojure-app` when ordinary application Clojure source and Millstrand
     |config coexist. Stop rather than guessing when more than one style owns
     |the repository."
    worktree workspace workspace)))

(defn- basis-instruction
  [style {:keys [worktree]}]
  (case style
    :app
    (fmt/reflow
     (format
      "|In `%s`, preserve the non-Clojure product's empty or existing product
       |classpath. If a tools.deps view is absent, prefer top-level `:paths []`.
       |Use a tooling project such as `.millstrand/deps.edn`, selected from the
       |repository's root LSP config, or an equivalent explicit project that
       |keeps Millstrand config off the product basis. Give that project pinned
       |Millstrand and approved-root coordinates with each required `:deps/root`.
       |Derive them from recorded spool status and root metadata; do not guess or
       |treat the tools.deps view as runtime approval. Record the product and
       |tooling bases. Prove the product excludes Millstrand config and spool
       |source while the tooling basis can require every external namespace used
       |by the workspace."
      worktree))

    :spool
    (fmt/reflow
     (format
      "|In `%s`, identify every spool root this repository owns and every external
       |namespace required by `.millstrand/init.clj`: inventory each module `:ns`
       |and every namespace reached through its `:spools` declarations. Build an
       |explicit namespace -> approved `spools.edn` root -> `sync.root` table;
       |for example, map `ct.spools.delegation` to `ct.spools/delegation` and
       |the exact root `delegation`, not merely to the agent-harness repository.
       |Include every configured root, such as `ct.spools/agent-run`,
       |`ct.spools/delegation`, `codethread/devflow`, `codethread/kanban`,
       |`codethread/devflow-setup`, `codethread/agents`, and `codethread/ralph`,
       |when the config requires them. Record the namespace, root coordinate,
       |Git URL, Git SHA or tag, `sync.root`, `deps.edn` `:paths`, and the
       |representative vars that will be required. A source-path search or a
       |runtime classloader is not an inventory.
       |Make the repository's selected tooling and test aliases a portable
       |authoring project. For every approved external root, add a fetched Git
       |coordinate with the exact `:deps/root` from the table, for example the
       |approved delegation root:
       |`{:git/url \"https://github.com/codethread/agent-harness.spool.git\"
       |:git/sha \"RECORDED_SHA\"
       |:deps/root \"delegation\"}`. Use the actual URL and SHA recorded by current spool status/root metadata;
       |`RECORDED_SHA` is an example only; any unresolved placeholder in a committed consumer coordinate must fail.
       |A `:local/root`
       |may be an explicit local development override, but it cannot be the only
       |coordinate needed by a fresh Git checkout, and the root project must not
       |silently substitute `.` for a named approved root. Keep consumer approval
       |in `spools.edn` and each root's `deps.edn` as the runtime source-path
       |contract. Record root, source-path, dependency identities, and the
       |portable coordinate from both views; stop on any missing namespace,
       |unapproved root, omitted `:deps/root`, or mismatch."
      worktree))

    :clojure-app
    (fmt/reflow
     (format
      "|In `%s`, preserve the ordinary application's base `:paths`, `:deps`, and
       |test entry point. Add or adapt a separate tooling alias so Millstrand
       |config, config tests, and pinned approved-root coordinates appear only
       |in the composed Millstrand view. Include each required `:deps/root`, and
       |derive identities from spool status rather than moving spool source onto
       |the base application classpath. Record and compare the base,
       |ordinary-test, and Millstrand-plus-test bases. Prove the base application
       |remains usable without the tooling alias, then directly require every
       |external namespace used by the workspace through the composed basis."
      worktree))))

(defn- lsp-instruction
  [style {:keys [worktree]}]
  (case style
    :app
    (fmt/reflow
     (format
      "|In `%s`, adapt the committed clojure-lsp configuration to select the
       |explicit Millstrand tooling project and aliases. A root LSP config may
       |select nested `.millstrand/deps.edn`; it remains the repository project
       |until navigation enters an external checkout. A `:project-path` only
       |matches that file; it does not change the working directory or basis used
       |by `:classpath-cmd`. Commit an explicit command such as `clojure -Srepro
       |-Sdeps .millstrand/deps.edn -Spath -M:lint` from the repository root
       |when that is the selected project. Include config and test source paths,
       |and do not assume clojure-lsp reads `spools.edn`. Start from a fresh LSP
       |cache, run repository-root diagnostics and a dump without a temporary
       |settings override, and inspect the command's actual classpath roots.
       |Directly require the external namespaces and verify external var
       |definitions in the fresh LSP index, recording each var and definition
       |path. Broad editor search or source-path visibility is not proof. When
       |the user is available and their editor supports it, walk through
       |go-to-definition from representative workspace requires as best-effort
       |human acceptance. Record the loaded config, selected project, command,
       |classpath roots, indexed vars and definitions, diagnostics, and whether
       |editor navigation was observed, skipped, or failed. Do not fail solely
       |because no compatible editor is available; fail when machine resolution,
       |classpath roots, direct require, or index proof is absent."
      worktree))

    :spool
    (fmt/reflow
     (format
      "|In `%s`, adapt the committed clojure-lsp configuration so its selected
       |portable authoring view includes every owned spool source root,
       |Millstrand config, tests, Millstrand, and required approved sibling
       |source. Do not rely on the Weaver's classloader, a sibling checkout, a
       |client's default aliases, or an uncommitted `--settings` override. Use a
       |cache-isolated directory that has never indexed this worktree; remove
       |or relocate any prior project cache and record the fresh cache path. Run
       |diagnostics and a dump at the repository root with the committed config,
       |then directly require every namespace in the inventory, including
       |representatives from `ct.spools/agent-run`, `ct.spools/delegation`, and
       |each other approved root actually required by `.millstrand/init.clj`.
       |Record the loaded config, exact require command and successful namespace
       |loads, indexed definition paths, and actual classpath roots; source-path
       |visibility is not proof. Then materialize or locate a clean fetched Git
       |checkout of each producer, run its committed LSP classpath command from
       |that checkout root (explicitly selecting any nested deps file, for example
       |with `-Sdeps <relative-deps-file>`), and repeat the direct require from a
       |new cache. Prove a fresh LSP index contains that dependency's external var
       |definitions and definition paths for every required producer. Broad editor
       |search or a cached/partial index is not proof. When the user has a compatible
       |editor, walk through
       |both navigation hops as best-effort human acceptance. Record both LSP
       |roots, cache paths, commands, actual classpath roots, required vars and
       |definitions, and whether editor navigation was observed, skipped, or
       |failed. Editor absence alone does not fail the step; missing classpath,
       |direct require, fresh index, or definition proof does."
      worktree))

    :clojure-app
    (fmt/reflow
     (format
      "|In `%s`, preserve ordinary application analysis and configure
       |clojure-lsp to compose the Millstrand and test aliases for config and
       |config-test analysis. Start from a fresh LSP cache. Confirm a dump contains
       |application source, Millstrand config, tests, required approved-root
       |source, and Millstrand APIs while the base application basis remains
       |unchanged. Ensure the committed `:classpath-cmd` selects the same project
       |explicitly when it is nested; `:project-path` alone is not classpath proof.
       |Directly require external workspace namespaces, inspect actual classpath
       |roots, and prove external var definitions and definition paths in a fresh
       |LSP index. Broad editor search or source-path visibility is not proof. When
       |the user has a compatible editor, walk through representative
       |go-to-definition calls as best-effort human acceptance. Run diagnostics
       |without a temporary settings override. Record the committed config,
       |selected project and aliases, command, classpath roots, indexed vars and
       |definitions, result, and whether editor navigation was observed, skipped,
       |or failed. Do not fail solely for absent editor support."
      worktree))))

(defn- lint-instruction
  [style {:keys [worktree]}]
  (case style
    :app
    (fmt/reflow
     (format
      "|In `%s`, require the preceding registered `bootstrap-kondo` call to have
       |completed successfully before starting lint. Verify that the producer
       |import directories exist and contain the expected files; missing import
       |directories, missing producer exports, or a nonzero bootstrap result fail
       |loudly and cannot be handed over as successful. Inspect the explicit Kondo
       |imports and confirm the Millstrand authoring mappings came from producer
       |exports in the effective spool world. Adapt the repository's lint alias or
       |native command to lint Millstrand config and tests through the composed
       |tools.deps view. Run the real command and require exit status zero; a
       |nonzero lint result fails loudly and cannot be handed over. Prove
       |macro-using files consumed the imported hooks. Explicitly lint and diagnose
       |every newly exposed `.millstrand` config and test path through this same
       |composed basis, even when the ordinary lint command excludes that directory;
       |record each path and namespace result. For a namespace/path mismatch, accept
       |only a coherent migration in which every affected namespace and path agrees
       |and the mapping changes as one set. A one-file opportunistic rename is not
       |proof; any new-basis error, including `namespace-name-mismatch`, fails
       |loudly and cannot be handed over. Do not prescribe repo-specific renames;
       |record the acceptance obligation for separately delegated consumer cleanup.
       |Record imports, command, errors, and warnings. Do not count syntax-only lint
       |as hook proof."
      worktree))

    :spool
    (fmt/reflow
     (format
      "|In `%s`, require the preceding registered `bootstrap-kondo` call to have
       |completed successfully before starting lint. Verify that the producer
       |import directories exist and contain the expected files; missing import
       |directories, missing producer exports, or a nonzero bootstrap result fail
       |loudly and cannot be handed over as successful. Confirm explicit Kondo
       |bootstrap imported Millstrand and approved sibling producer exports
       |without importing this repository's own producer coordinate or duplicating
       |a producer mapping with a local remap. Adapt the repository's lint command
       |to cover every owned spool source root, config, and tests through the author
       |tools.deps view. Run the real command and require exit status zero; a
       |nonzero lint result fails loudly and cannot be handed over. Prove authoring
       |macros use producer hooks. Explicitly lint and diagnose every newly exposed
       |`.millstrand` config and test path through this same composed basis, even
       |when ordinary lint excludes it; record each path and namespace result. For
       |a namespace/path mismatch, accept only a coherent migration where every
       |affected namespace and path agrees and the mapping changes as one set. A
       |one-file opportunistic rename is not proof; any new-basis error, including
       |`namespace-name-mismatch`, fails loudly and cannot be handed over. Do not
       |prescribe repo-specific renames; record the acceptance obligation for
       |separately delegated consumer cleanup. Record provenance, self-import
       |result, command, errors, and warnings."
      worktree))

    :clojure-app
    (fmt/reflow
     (format
      "|In `%s`, require the preceding registered `bootstrap-kondo` call to have
       |completed successfully before starting lint. Verify that the producer
       |import directories exist and contain the expected files; missing import
       |directories, missing producer exports, or a nonzero bootstrap result fail
       |loudly and cannot be handed over as successful. Confirm the explicit Kondo
       |imports came from producer exports in the effective spool world. Adapt the
       |repository's lint alias or native command so application source, Millstrand
       |config, and tests share the composed development basis while the base app
       |remains unchanged. Run the real command and require exit status zero; a
       |nonzero lint result fails loudly and cannot be handed over. Prove Millstrand
       |macro-using config consumed its imported hooks. Explicitly lint and diagnose
       |every newly exposed `.millstrand` config and test path through this same
       |composed basis, even when ordinary lint excludes it; record each path and
       |namespace result. For a namespace/path mismatch, accept only a coherent
       |migration where every affected namespace and path agrees and the mapping
       |changes as one set. A one-file opportunistic rename is not proof; any
       |new-basis error, including `namespace-name-mismatch`, fails loudly and
       |cannot be handed over. Do not prescribe repo-specific renames; record the
       |acceptance obligation for separately delegated consumer cleanup. Record
       |imports, command, errors, and warnings."
      worktree))))

(defn- test-instruction
  [style {:keys [worktree]}]
  (case style
    :app
    (fmt/reflow
     (format
      "|In `%s`, discover and run the repository's existing test command. Treat
       |repository-owned test configuration as ordinary Clojure: do not prescribe
       |a test runner or add test scaffolding. Record the exact command and test
       |result evidence, including counts, failures, and errors. Only when this
       |change alters runtime acquisition or adds Weaver-only behavior, follow the
       |documented disposable-Weaver pattern and record its result; a coordinate or
       |LSP projection alone does not require a new fixture."
      worktree))

    :spool
    (fmt/reflow
     (format
      "|In `%s`, discover and run the repository's existing test command. Treat
       |repository-owned test configuration as ordinary Clojure: do not prescribe
       |a test runner or add test scaffolding. Record the exact command and test
       |result evidence, including counts, failures, and errors. Only when this
       |change alters runtime acquisition or adds Weaver-only behavior, follow the
       |documented disposable-Weaver pattern and record its result; a coordinate or
       |LSP projection alone does not require a new fixture."
      worktree))

    :clojure-app
    (fmt/reflow
     (format
      "|In `%s`, discover and run the repository's existing test command. Treat
       |repository-owned test configuration as ordinary Clojure: do not prescribe
       |a test runner or add test scaffolding. Record the exact command and test
       |result evidence, including counts, failures, and errors. Only when this
       |change alters runtime acquisition or adds Weaver-only behavior, follow the
       |documented disposable-Weaver pattern and record its result; a coordinate or
       |LSP projection alone does not require a new fixture."
      worktree))))

(defn- weaver-instruction
  [style {:keys [workspace]}]
  (let [style-proof
        (case style
          :app
          "load the workspace config module and invoke one of its real operations"

          :spool
          (str "confirm the approved owned root was acquired, activate its "
               "`:spools`-guarded namespace module, and invoke contributed behavior")

          :clojure-app
          (str "load the workspace config module, invoke its real operation, and "
               "prove the result came from ordinary application code"))]
    (fmt/reflow
     (format
      "|Against selected workspace `%s`, read runtime and module status after the
       |preceding refresh. When no pending generation exists, %s. Record the
       |current approved root identities, module outcome, operation input and
       |result, and any failure. When a pending generation exists, run that same
       |style-specific probe against the current generation and record its result
       |as current-generation-only evidence, not adoption proof. Do not restart
       |the Weaver and do not call the new coordinate proved. Record which tooling
       |configuration is prepared and mark the exact probe against the adopted
       |generation as unfinished until authorized cutover."
      workspace style-proof))))

(defn- handover-instruction
  [style {:keys [worktree workspace]}]
  (fmt/reflow
   (format
    "|Leave a `%s` tooling handover for worktree `%s` and workspace `%s`.
     |Record the effective spool roots and refresh state, tools.deps views,
     |changed LSP and Kondo files, imported producer provenance, exact LSP,
     |lint, verify-tests, and Weaver commands and results, preserving the test
     |result evidence and every unresolved mismatch.
     |Separate prepared configuration, current-generation proof, and adopted
     |generation proof. If a pending generation remains, mark the style-specific
     |post-cutover Weaver check as unfinished. Do not stop, restart, push, or
     |land from this step."
    (name style) worktree workspace)))

(defn- bootstrap-composition-instruction
  "Return the manual handoff instructions for the separate bootstrap run."
  [{:keys [worktree workspace]}]
  (fmt/reflow
   (format
    "|In worktree `%s`, start a separate registered `bootstrap-kondo` run for
     |workspace `%s` (for example, use a distinct run id such as
     |`consumer-kondo-<timestamp>`). Drive that child run through its ready
     |frontier: choose the consumer's `greenfield` or `brownfield` adoption mode,
     |then complete every route step until the child result reports `:done true`.
     |Do not treat the runtime-routed child checkpoint as a return from this
     |workflow; complete this composition only after the child is done. Before
     |completing this step, record the child run id, selected mode, and explicit
     |done evidence, for example `{:bootstrap-kondo-run-id \"child-run\"
     |:bootstrap-kondo-mode \"brownfield\" :bootstrap-kondo-done true}`. Alignment
     |starts only after that evidence and the successful handover. Leave the
     |separate child run available for audit and preserve its exact command and
     |result evidence."
    worktree workspace)))

(defn- route-steps
  "Return the ordinary agent-owned setup steps for repository `style`."
  [style]
  [(workflow/step :bootstrap-kondo
                  "Bootstrap Kondo in a separate registered run"
                  :self
                  :attributes
                  {"workflow/action-ref" (str "millstrand-workflows.consumer-tooling."
                                              (name style) ".bootstrap-kondo")
                   "workflow/instruction" bootstrap-composition-instruction})
   (workflow/step :align-tools-deps
                  "Align the tools.deps view with the effective spool world"
                  :self
                  :depends-on [:bootstrap-kondo]
                  :attributes
                  {"workflow/action-ref" (str "millstrand-workflows.consumer-tooling."
                                              (name style) ".tools-deps")
                   "workflow/instruction" (fn [params] (basis-instruction style params))})
   (workflow/step :configure-lsp
                  "Configure and prove clojure-lsp analysis"
                  :self
                  :depends-on [:align-tools-deps]
                  :attributes
                  {"workflow/action-ref" (str "millstrand-workflows.consumer-tooling."
                                              (name style) ".lsp")
                   "workflow/instruction" (fn [params] (lsp-instruction style params))})
   (workflow/step :configure-lint
                  "Configure and prove lint and clj-kondo analysis"
                  :self
                  :depends-on [:configure-lsp]
                  :attributes
                  {"workflow/action-ref" (str "millstrand-workflows.consumer-tooling."
                                              (name style) ".lint")
                   "workflow/instruction" (fn [params] (lint-instruction style params))})
   (workflow/step :verify-tests
                  "Verify repository tests"
                  :self
                  :depends-on [:configure-lint]
                  :attributes
                  {"workflow/action-ref" (str "millstrand-workflows.consumer-tooling."
                                              (name style) ".verify-tests")
                   "workflow/instruction" (fn [params] (test-instruction style params))})
   (workflow/step :verify-weaver
                  "Prove the selected Weaver behavior or record pending proof"
                  :self
                  :depends-on [:verify-tests]
                  :attributes
                  {"workflow/action-ref" (str "millstrand-workflows.consumer-tooling."
                                              (name style) ".weaver")
                   "workflow/instruction" (fn [params] (weaver-instruction style params))})
   (workflow/step :handover
                  "Hand over repository tooling and generation evidence"
                  :self
                  :depends-on [:verify-weaver]
                  :attributes
                  {"workflow/action-ref" (str "millstrand-workflows.consumer-tooling."
                                              (name style) ".handover")
                   "workflow/instruction" (fn [params] (handover-instruction style params))})])
(workflow/defworkflow configure-consumer-tooling
  "Choose and configure tooling for a Millstrand consumer repository style.

  Start or call it with the exact consumer worktree and selected workspace. The
  agent first inspects the effective spool world and repository conventions,
  then chooses `app`, `spool`, or `clojure-app`. Each continuation aligns and
  composes the registered `bootstrap-kondo` workflow before aligning and
  proving tools.deps, clojure-lsp, clj-kondo/lint, tests, and Weaver behavior
  through ordinary manual steps. It contains no executor gates and never
  restarts a Weaver."
  {:entrypoints #{:start :call}
   :param-spec ::consumer-tooling-params
   :defaults {}
   :example {:worktree "/abs/path/to/consumer-worktree"
             :workspace "/abs/path/to/consumer-worktree/.millstrand"}
   :param-docs {:worktree "Exact consumer worktree to inspect and update."
                :workspace "Exact selected Millstrand workspace for the consumer."}}
  (workflow/workflow
   "Configure consumer tooling by repository style"
   (workflow/step :inspect-repository
                  "Inspect the effective spool world and classify the repository"
                  :self
                  :attributes
                  {"workflow/action-ref"
                   "millstrand-workflows.consumer-tooling.repository.inspect"
                   "workflow/instruction" inspect-repository-instruction})
   (workflow/checkpoint
    :repository-style
    "Choose the consumer repository style"
    :kind :agent
    :depends-on [:inspect-repository]
    :choices
    [{:key :app
      :label "Application"
      :description (fmt/reflow
                    "|The product is not a Clojure application; Clojure exists
                     |only for Millstrand config, tooling, and tests.")
      :next :configure-consumer-tooling-app}
     {:key :spool
      :label "Spool library"
      :description (fmt/reflow
                    "|The repository owns and publishes one or more Millstrand
                     |spool roots.")
      :next :configure-consumer-tooling-spool}
     {:key :clojure-app
      :label "Clojure application"
      :description (fmt/reflow
                    "|Ordinary Clojure application source and Millstrand config
                     |coexist in the repository.")
      :next :configure-consumer-tooling-clojure-app}
     {:key :unsupported
      :label "Stop"
      :description (fmt/reflow
                    "|Stop when one repository style cannot be established
                     |without guessing or collapsing distinct projects.")}])))

(workflow/defworkflow configure-consumer-tooling-app
  "Configure tooling for a non-Clojure product with Millstrand config.

  This continuation is selected by `configure-consumer-tooling`; callers
  normally start the parent so repository inspection and style choice are
  recorded first."
  {:entrypoints #{:continue}
   :param-spec ::consumer-tooling-params}
  (apply workflow/workflow
         "Configure application Millstrand tooling"
         (route-steps :app)))

(workflow/defworkflow configure-consumer-tooling-spool
  "Configure tooling for a repository that owns Millstrand spool roots.

  This continuation is selected by `configure-consumer-tooling`; callers
  normally start the parent so repository inspection and style choice are
  recorded first."
  {:entrypoints #{:continue}
   :param-spec ::consumer-tooling-params}
  (apply workflow/workflow
         "Configure spool repository tooling"
         (route-steps :spool)))

(workflow/defworkflow configure-consumer-tooling-clojure-app
  "Configure tooling for a Clojure application with Millstrand config.

  This continuation is selected by `configure-consumer-tooling`; callers
  normally start the parent so repository inspection and style choice are
  recorded first."
  {:entrypoints #{:continue}
   :param-spec ::consumer-tooling-params}
  (apply workflow/workflow
         "Configure Clojure application Millstrand tooling"
         (route-steps :clojure-app)))
