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
       |Add or adapt development aliases so Millstrand config, tests,
       |Millstrand APIs, and source needed from the recorded approved roots are
       |visible only when those aliases are selected. Derive root paths from
       |the recorded spool status and each root's `deps.edn`; do not guess or
       |treat the tools.deps view as runtime approval. Record the base and
       |composed bases and prove the base excludes Millstrand config and spool
       |source."
      worktree))

    :spool
    (fmt/reflow
     (format
      "|In `%s`, identify every spool root this repository owns. Validate each
       |root's `deps.edn` `:paths` and Maven dependencies, then adapt the author
       |project's tools.deps view so owned source roots, config, tests,
       |Millstrand APIs, and required approved sibling roots are visible to
       |development tools. Keep consumer approval in `spools.edn` and keep each
       |root's `deps.edn` as the runtime source-path contract; the author basis
       |is a tooling view, not a replacement approval graph. Record root,
       |source-path, and dependency identities from both views and resolve any
       |mismatch before continuing."
      worktree))

    :clojure-app
    (fmt/reflow
     (format
      "|In `%s`, preserve the ordinary application's base `:paths`, `:deps`, and
       |test entry point. Add or adapt development aliases so Millstrand config,
       |config tests, Millstrand APIs, and source needed from recorded approved
       |roots appear only in the composed Millstrand view. Derive approved-root
       |paths from spool status and root `deps.edn`; do not guess or move them
       |onto the base application classpath. Record and compare the base,
       |ordinary-test, and Millstrand-plus-test bases, proving the base
       |application remains usable without Millstrand."
      worktree))))

(defn- lsp-instruction
  [style {:keys [worktree]}]
  (case style
    :app
    (fmt/reflow
     (format
      "|In `%s`, adapt the committed clojure-lsp configuration to select the
       |Millstrand and test aliases explicitly. Include the config and test
       |source paths and use a project classpath command when the repository's
       |empty base would otherwise hide them. Do not assume clojure-lsp recurses
       |through arbitrary aliases or reads `spools.edn`. Run repository-root
       |diagnostics and a dump without a temporary CLI settings override; prove
       |the committed config was consumed, required config/test namespaces and
       |Millstrand APIs are present, and diagnostics are clean."
      worktree))

    :spool
    (fmt/reflow
     (format
      "|In `%s`, adapt the committed clojure-lsp configuration so its selected
       |tools.deps view includes every owned spool source root, Millstrand
       |config, tests, Millstrand APIs, and required approved sibling source.
       |Do not rely on the Weaver's dynamic classloader or on an uncommitted
       |`--settings` override. Run diagnostics and a dump from the repository
       |root, then record the loaded config, selected aliases, source paths,
       |classpath roots, owned namespaces, and any diagnostics."
      worktree))

    :clojure-app
    (fmt/reflow
     (format
      "|In `%s`, preserve ordinary application analysis and configure
       |clojure-lsp to compose the Millstrand and test aliases for config and
       |config-test analysis. Confirm a dump contains application source,
       |Millstrand config, tests, required approved-root source, and Millstrand
       |APIs while the base application basis remains unchanged. Run diagnostics
       |without a temporary settings override and record the committed config,
       |selected aliases, paths, and result."
      worktree))))

(defn- lint-instruction
  [style {:keys [worktree]}]
  (case style
    :app
    (fmt/reflow
     (format
      "|In `%s`, inspect the explicit Kondo imports produced by the preceding
       |bootstrap and confirm the Millstrand authoring mappings came from
       |producer exports in the effective spool world. Adapt the repository's
       |lint alias or native command to lint Millstrand config and tests through
       |the composed tools.deps view. Run the real command, prove macro-using
       |files consumed the imported hooks, and record imports, command, errors,
       |and warnings. Do not count syntax-only lint as hook proof."
      worktree))

    :spool
    (fmt/reflow
     (format
      "|In `%s`, confirm explicit Kondo bootstrap imported Millstrand and
       |approved sibling producer exports without importing this repository's
       |own producer coordinate or duplicating a producer mapping with a local
       |remap. Adapt the repository's lint command to cover every owned spool
       |source root, config, and tests through the author tools.deps view. Run
       |the real lint command, prove authoring macros use producer hooks, and
       |record provenance, self-import result, command, errors, and warnings."
      worktree))

    :clojure-app
    (fmt/reflow
     (format
      "|In `%s`, confirm the explicit Kondo imports came from producer exports
       |in the effective spool world. Adapt the repository's lint alias or native
       |command so application source, Millstrand config, and tests share the
       |composed development basis while the base app remains unchanged. Run the
       |real command, prove Millstrand macro-using config consumed its imported
       |hooks, and record imports, command, errors, and warnings."
      worktree))))

(defn- test-instruction
  [style {:keys [worktree]}]
  (case style
    :app
    (fmt/reflow
     (format
      "|In `%s`, discover the repository's test convention. Add or adapt focused
       |tests for pure config behavior and a disposable unpublished Weaver world
       |that loads the same workspace module shape and invokes a real operation.
       |Keep test paths off the empty product base. Run the focused commands and
       |record exact test and assertion counts, failures, errors, temporary-world
       |isolation, and the operation result."
      worktree))

    :spool
    (fmt/reflow
     (format
      "|In `%s`, discover the repository's test convention and preserve its
       |direct classpath or unit tier. Add or adapt a separate disposable-world
       |test that approves the real spool root in fixture `spools.edn`, declares
       |a module guarded by that root, and invokes a contributed operation or
       |other public behavior. A direct `require` alone does not prove runtime
       |acquisition. Run both tiers and record commands, root identity, module
       |outcome, test counts, failures, errors, and runtime result."
      worktree))

    :clojure-app
    (fmt/reflow
     (format
      "|In `%s`, discover and preserve the ordinary application test command,
       |then run it without selecting Millstrand and prove its basis excludes
       |Millstrand config and APIs. Add or adapt a composed
       |Millstrand-plus-test path for config tests and a disposable unpublished
       |Weaver world whose configured operation reaches ordinary application
       |code. Record both bases, commands, test counts, failures, errors, and the
       |runtime result."
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
     |lint, test, and Weaver commands and results, and every unresolved mismatch.
     |Separate prepared configuration, current-generation proof, and adopted
     |generation proof. If a pending generation remains, mark the style-specific
     |post-cutover Weaver check as unfinished. Do not stop, restart, push, or
     |land from this step."
    (name style) worktree workspace)))

(defn- route-steps
  "Return the ordinary agent-owned setup steps for repository `style`."
  [style]
  [(workflow/step :align-tools-deps
                  "Align the tools.deps view with the effective spool world"
                  :self
                  :attributes
                  {"workflow/action-ref"
                   (str "millstrand-workflows.consumer-tooling."
                        (name style) ".tools-deps")
                   "workflow/instruction" (fn [params]
                                            (basis-instruction style params))})
   (workflow/step :configure-lsp
                  "Configure and prove clojure-lsp analysis"
                  :self
                  :depends-on [:align-tools-deps]
                  :attributes
                  {"workflow/action-ref"
                   (str "millstrand-workflows.consumer-tooling."
                        (name style) ".lsp")
                   "workflow/instruction" (fn [params]
                                            (lsp-instruction style params))})
   (workflow/step :configure-lint
                  "Configure and prove lint and clj-kondo analysis"
                  :self
                  :depends-on [:configure-lsp]
                  :attributes
                  {"workflow/action-ref"
                   (str "millstrand-workflows.consumer-tooling."
                        (name style) ".lint")
                   "workflow/instruction" (fn [params]
                                            (lint-instruction style params))})
   (workflow/step :configure-tests
                  "Configure and run repository-appropriate tests"
                  :self
                  :depends-on [:configure-lint]
                  :attributes
                  {"workflow/action-ref"
                   (str "millstrand-workflows.consumer-tooling."
                        (name style) ".tests")
                   "workflow/instruction" (fn [params]
                                            (test-instruction style params))})
   (workflow/step :verify-weaver
                  "Prove the selected Weaver behavior or record pending proof"
                  :self
                  :depends-on [:configure-tests]
                  :attributes
                  {"workflow/action-ref"
                   (str "millstrand-workflows.consumer-tooling."
                        (name style) ".weaver")
                   "workflow/instruction" (fn [params]
                                            (weaver-instruction style params))})
   (workflow/step :handover
                  "Hand over repository tooling and generation evidence"
                  :self
                  :depends-on [:verify-weaver]
                  :attributes
                  {"workflow/action-ref"
                   (str "millstrand-workflows.consumer-tooling."
                        (name style) ".handover")
                   "workflow/instruction" (fn [params]
                                            (handover-instruction style params))})])

(workflow/defworkflow configure-consumer-tooling
  "Choose and configure tooling for a Millstrand consumer repository style.

  Start or call it with the exact consumer worktree and selected workspace. The
  agent first inspects the effective spool world and repository conventions,
  then chooses `app`, `spool`, or `clojure-app`. Each continuation aligns and
  proves tools.deps, clojure-lsp, clj-kondo/lint, tests, and Weaver behavior
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
