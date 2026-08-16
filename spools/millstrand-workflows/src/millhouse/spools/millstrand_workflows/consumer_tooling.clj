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

(defn- git-sha?
  "Return true when `value` is a complete lowercase Git SHA."
  [value]
  (and (string? value)
       (boolean (re-matches #"[0-9a-f]{40}" value))))

(defn- consumer-workspace
  "Return the Millstrand workspace belonging to consumer `worktree`."
  [^String worktree]
  (str (java.io.File. worktree ".millstrand")))

(defn- consumer-world?
  "Return true when the declared workspace is the consumer worktree's world."
  [{:keys [worktree workspace]}]
  (and (non-blank-string? worktree)
       (non-blank-string? workspace)
       (= workspace (consumer-workspace worktree))))

(defn- pinned-remote-family?
  "Return true when `value` names a pinned remote Millhouse family."
  [{:keys [kind family coordinate root]}]
  (and (= "pinned-remote-family" kind)
       (= "millhouse/spools" family)
       (nil? root)
       (map? coordinate)
       (non-blank-string? (:git/url coordinate))
       (git-sha? (:git/sha coordinate))))

(defn- local-self-root?
  "Return true when `value` names a local Millhouse self root."
  [{:keys [kind family coordinate root]}]
  (and (= "local-self-root" kind)
       (= "millhouse/spools" family)
       (non-blank-string? root)
       (map? coordinate)
       (non-blank-string? (:local/root coordinate))))

(s/def ::non-blank-string non-blank-string?)
(s/def ::worktree ::non-blank-string)
(s/def ::workspace ::non-blank-string)
(s/def ::inherited-pre-refresh-evidence boolean?)
(s/def ::kind ::non-blank-string)
(s/def ::family ::non-blank-string)
(s/def ::coordinate map?)
(s/def ::root ::non-blank-string)
(s/def ::pinned-remote-family
  (s/and
   (s/keys :req-un [::kind ::family ::coordinate])
   pinned-remote-family?))
(s/def ::local-self-root
  (s/and
   (s/keys :req-un [::kind ::family ::coordinate ::root])
   local-self-root?))
(s/def ::invocation-producer
  (s/or :pinned-remote-family ::pinned-remote-family
        :local-self-root ::local-self-root))
(s/def ::consumer-tooling-params
  (s/and
   (s/keys :req-un [::worktree ::workspace ::invocation-producer]
           :opt-un [::inherited-pre-refresh-evidence])
   consumer-world?))

(defn- invocation-producer-instruction
  [{:keys [worktree invocation-producer inherited-pre-refresh-evidence]}]
  (fmt/reflow
   (format
    "|In worktree `%s` and selected workspace `%s`, require the preceding
     |repository inspection and bootstrap preparation to %s. Derive one exact intended family/root
     |set from the selected activation and relevant producer metadata. Require
     |the recorded `:families` map and every nested `:roots` map to cover exactly
     |that set, with no missing, extra, or mismatched family/root. Every intended
     |root outcome must have `:status :synced`, a `:sync` map, and a nonempty
     |`:sync.root` (the `:root` inside `:sync`). Record the exact
     |`[family root] -> sync.root` map as pre-refresh current-root evidence.
     |Reject failed, conflicted, source-reload, partial, missing, extra, or
     |mismatched roots and absent, blank, or otherwise invalid `:sync.root`.
     |Fail loudly when %s. %s Then prove the
     |consumer uses this exact invocation-producer contract: `%s`. For
     |`pinned-remote-family`, the contract is the complete `millhouse/spools`
     |family coordinate with its exact `git/url` and full lowercase `git/sha`.
     |For `local-self-root`, it is the exact Millhouse self root and `local/root`.
     |Read the selected `spools.edn` activation and every relevant Millhouse root
     |in every applicable `deps.edn`. Record the exact family/root, coordinate,
     |and `:deps/root` evidence for each. When an existing coordinate differs,
     |manually edit only the applicable `spools.edn` activation and Millhouse
     |entries in `deps.edn` to the supplied contract, preserving each declared
     |`:deps/root`; record the before and after values. Show that shared roots use
     |one version-coherent family coordinate. Stop only when metadata is missing
     |or incomparable, or the manual alignment cannot be verified. When
     |alignment changes an active coordinate, run exactly
     |`(runtime/refresh! (current/runtime))` in the
     |selected workspace before continuing and record its full result. A fully
     |applied `:status :applied` result continues normally. The only supported
     |pending next-generation result has top-level `:status :partial` and a
     |nonempty `:modules` outcome map. Every top-level `:modules` map key must
     |equal its outcome `:module/key`; reject any missing or mismatched map-key
     |identity. Every module outcome must be exactly one of three forms: (a) an
     |unchanged module with `:status :unchanged` and no `:error`, `:reason`,
     |refusal, `:root/outcome`, `:dependency`, or `:dependency/outcome`; (b) direct
     |`:status :refused` and `:reason :hard-conflict`, carrying the exact
     |`:root/outcome`; or (c) `:status :failed` or `:status :skipped` with
     |`:reason :missing-dependency`, whose `:dependency` equals the nested
     |`:dependency/outcome` `:module/key` at every hop. Missing-dependency
     |wrappers must form a finite acyclic chain of the same allowed wrapper
     |shape and terminate in an exact direct refused hard-conflict outcome.
     |The direct outcome has `:status :hard-conflict` and must carry `:root-lib`
     |equal to exactly one `:lib` in the declared nonempty changed-root set;
     |reject any direct or terminal refusal whose `:root-lib` is outside or
     |mismatched against the declared nonempty changed-root set. No `:applied`
     |outcome,
     |other status or reason, unrelated terminal, missing or mismatched
     |dependency outcome, cycle, or other refusal/error may be present.
     |Declare one nonempty prepared conflict classification with exactly
     |`:changed-roots` and `:namespace-residuals`. Every direct refusal and
     |every terminal refusal reached through a wrapper chain must carry that
     |exact same shared classification. Every such root outcome's `:conflict`
     |must contain exactly `:changed-roots` and `:namespace-residuals`, and its
     |`:changed-roots` must equal that exact declared set. Each changed-root
     |entry must have exactly `:lib`, `:previous-root`, and `:new-root`.
     |Resolve every changed `:lib` to exactly one family/root in the pre-refresh
     |current-root evidence. Its `:previous-root` must equal that recorded
     |`sync.root`; its `:new-root` is the prepared-root evidence. The changed-root
     |set must equal the prepared-root set exactly. Unchanged intended roots stay
     |on their recorded pre-refresh current roots. Reject any missing, extra,
     |ambiguous, or mismatched current, changed, or prepared root.
     |Accept residuals only when every one maps to exactly one changed-root
     |entry. The only allowed reasons are `:root-repointed` and
     |`:unledgered-loaded-namespace`; every allowed residual must have a
     |nonempty `:namespace` and nonempty `:providers`. A `:root-repointed`
     |residual must have exactly one old `:binding`; its binding and every
     |provider must use `:root-lib` equal to the matched changed-root `:lib`.
     |The binding `:root` must equal `:previous-root`, while every provider
     |`:root` must equal `:new-root`. An
     |`:unledgered-loaded-namespace` residual must have no binding, and every
     |provider must use that same `:root-lib` and `:new-root`. Every binding and
     |provider `:namespace` must equal the residual namespace. Every binding
     |and provider `:file` path must be nonempty, canonical, and belong to its
     |stated root; provider paths must be distinct. Reject an empty `:providers`
     |collection and every other empty or vacuous,
     |duplicate, missing, extra, unrelated, or mixed mappings, including a
     |wrong `:root-lib`, wrong root, wrong namespace, or wrong provider path.
     |The runtime status must also contain a matching `:pending-generation` with
     |exactly `:status`, `:generation`, `:diff`, `:approved-spools`, and
     |`:remedy`; its `:diff` must have that same classification, including both
     |`:changed-roots` and `:namespace-residuals`.
     |Record the current generation, prepared generation, and every
     |current/prepared root pair, then continue tooling against the prepared
     |roots without restarting or claiming adoption. Any other partial,
     |refused, per-root failure, missing pending record, refresh error, or
     |ambiguous ownership fails loudly. If no coordinate changed, run exactly
     |the same `(runtime/refresh! (current/runtime))` and record its full result
     |and the read-only `(runtime/status (current/runtime))` result. Continue
     |only when the refresh result has top-level `:status :unchanged`, every
     |entry in its `:modules` map has the exact unchanged shape above, and
     |runtime status has `:pending-generation nil`. Combine those two results
     |with the exact pre-refresh current-root evidence; the unchanged refresh
     |proves that every recorded active root remains current without another
     |spool-status command. Reject `:status :applied`,
     |`:status :partial`, `:status :refused`, any refresh error, any other module
     |status, any non-nil pending generation, or any absent, contradictory, or
     |malformed result loudly. Do not
     |infer the running workflow SHA, guess metadata, or automate these edits."
    worktree
    (consumer-workspace worktree)
    (if inherited-pre-refresh-evidence
      (str "reuse the unchanged exact `:bump-pre-refresh-evidence` already "
           "verified before the first coordinate mutation")
      "have recorded the same pre-refresh spool-status evidence")
    (if inherited-pre-refresh-evidence
      (str "the inherited status result, selected workspace, Weaver identity, "
           "intended set, root outcome, or sync evidence contradicts the exact "
           "calling bump evidence")
      (str "the two recorded status results, selected workspace, Weaver identity, "
           "intended set, root outcome, or sync evidence differ"))
    (if inherited-pre-refresh-evidence
      (str "Do not run or re-enter `spool status`; carry that same evidence "
           "through the following runtime refresh proof.")
      "Do all of this before any coordinate edit or runtime refresh.")
    (pr-str invocation-producer))))

(defn- inspect-repository-instruction
  [{:keys [worktree inherited-pre-refresh-evidence]}]
  (if inherited-pre-refresh-evidence
    (fmt/reflow
     (format
      "|In worktree `%s` and selected workspace `%s`, reuse only the complete
       |`:bump-pre-refresh-evidence` recorded by the calling bump workflow before
       |its first coordinate mutation. Do not run, retry, or otherwise re-enter
       |`spool status`, and do not start or restart a Weaver. Require the evidence
       |to contain the exact status command, complete structured result, worktree,
       |selected workspace, Weaver identity, exact intended family/root set, and
       |`[family root] -> sync.root` map. Verify that its worktree and workspace
       |equal this exact consumer world and carry the same evidence through
       |repository classification, producer alignment, bootstrap, and refresh
       |proof without replacing, weakening, or recapturing it. Missing or
       |contradictory inherited evidence fails loudly. Derive the exact intended
       |family/root set again from the recorded selected activation and relevant
       |producer metadata. Require `:families` and every nested `:roots` map to
       |cover exactly that set. Every root must be `:status :synced` with a
       |`:sync` map and nonempty `:sync.root`; require the inherited current-root
       |map to equal that exact projection. Missing, extra, mismatched, failed,
       |conflicted, source-reload, partial, or malformed roots fail loudly. Record
       |each coordinate and the latest refresh and runtime generation state
       |already present in that result. Do not edit a coordinate or call runtime
       |refresh during this step. Inspect `deps.edn`, `.clj-kondo/config.edn`, test
       |paths, Makefile, scripts, and CI or contributor guidance without editing
       |them yet. Choose `app` when the product has no Clojure application source
       |beyond Millstrand config, `spool` when this repository publishes one or
       |more spool roots, or `clojure-app` when ordinary application Clojure source
       |and Millstrand config coexist. Stop rather than guessing when more than one
       |style owns the repository."
      worktree (consumer-workspace worktree)))
    (fmt/reflow
     (format
      "|In worktree `%s` and selected workspace `%s`, acquire and verify the exact
     |consumer Weaver before inspecting or classifying the repository. First run
     |the exact command `strand --workspace %s spool status` and record the
     |before evidence: command, structured result, selected workspace, and
     |current Weaver/root identities. If the result is `mill/no-selected-weaver`,
     |explicitly start only that worktree workspace as a disposable Weaver with
     |`mill weaver start --workspace %s`; record the start command, PID, and
     |Weaver/root identities. Rerun the same exact status command and record the
     |after-start evidence. If status already selects a Weaver, use that exact
     |target as-is and record its identity. Any other status failure stops this
     |step. Never start or restart a canonical or already-running Weaver.
     |Only after successful before or after-start status evidence, read shared and
     |local spool approvals and inspect the structured result. Derive one exact
     |intended family/root set from the selected activation and relevant producer
     |metadata. Require `:families` and every nested `:roots` map to cover exactly
     |that set. Every root must be `:status :synced` with a `:sync` map and a
     |nonempty `:sync.root`. Record the complete structured result and the exact
     |`[family root] -> sync.root` map as pre-refresh current-root evidence.
     |Missing, extra, mismatched, failed, conflicted, source-reload, partial, or
     |malformed roots fail loudly. Record each coordinate and the latest refresh
     |and runtime generation state already present in that result. Do not edit a
     |coordinate or call runtime refresh during this step. Inspect `deps.edn`,
     |`.clj-kondo/config.edn`, test paths, Makefile, scripts, and CI or contributor
     |guidance without editing them yet. Choose `app` when the product has no
     |Clojure application source beyond Millstrand config, `spool` when this
     |repository publishes one or more spool roots, or `clojure-app` when ordinary
     |application Clojure source and Millstrand config coexist. Stop rather than
     |guessing when more than one style owns the repository."
      worktree (consumer-workspace worktree) (consumer-workspace worktree)
      (consumer-workspace worktree)))))

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
       |`ct.spools/delegation`, `codethread/devflow`, `millhouse.spools/kanban`,
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
       |and do not assume clojure-lsp reads `spools.edn`. Stop the repository's
       |clojure-lsp client, remove only its project-local `.lsp/.cache`, then
       |restart the client and let it rebuild. Do not remove anything under
       |`.gitlibs`. Run repository-root diagnostics and a dump without a
       |temporary settings override, and inspect the command's actual classpath
       |roots.
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
       |client's default aliases, or an uncommitted `--settings` override. Stop
       |the repository's clojure-lsp client, remove only its project-local
       |`.lsp/.cache`, then restart the client and let it rebuild. Do not remove
       |anything under `.gitlibs`. Run diagnostics and a dump at the repository
       |root with the committed config, then directly require every namespace in
       |the inventory, including
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
       |config-test analysis. Stop the repository's clojure-lsp client, remove
       |only its project-local `.lsp/.cache`, then restart the client and let it
       |rebuild. Do not remove anything under `.gitlibs`. Confirm a dump contains
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
  [style {:keys [worktree]}]
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
       |generation as unfinished until authorized cutover. The Weaver probe uses
       |only current-generation roots; it never proves or adopts a prepared root."
      (consumer-workspace worktree) style-proof))))

(defn- handover-instruction
  [style {:keys [worktree]}]
  (fmt/reflow
   (format
    "|Leave a `%s` tooling handover for worktree `%s` and workspace `%s`.
     |Record the effective spool roots and refresh state, tools.deps views,
     |changed LSP and Kondo files, imported producer provenance, exact LSP,
     |lint, verify-tests, and Weaver commands and results, preserving the test
     |result evidence and every unresolved mismatch.
     |Separate prepared configuration, current-generation proof, and adopted
     |generation proof. If a pending generation remains, record the current and
     |prepared generations and mark the style-specific
     |post-cutover Weaver check as unfinished. Do not stop, restart, push, or
     |land from this step."
    (name style) worktree (consumer-workspace worktree))))

(defn- bootstrap-preparation-instruction
  "Return instructions that pause bootstrap after pre-refresh status capture."
  [{:keys [worktree inherited-pre-refresh-evidence]}]
  (fmt/reflow
   (format
    "|In worktree `%s`, derive the target consumer world as `%s` (`worktree/.millstrand`).
     |The separate child `bootstrap-kondo` run must receive and verify exactly
     |`{:worktree \"%s\" :workspace \"%s\"%s}` before it runs any command; never
     |substitute the disposable workflow-host workspace. Start a separate
     |registered `bootstrap-kondo` run (for example, use a distinct run id such as
     |`consumer-kondo-<timestamp>`). Drive that child run through its ready
     |frontier through `select-world`, `capture-spool-status`, and the adoption
     |checkpoint. The capture must %s and must match the preceding repository
     |inspection exactly. Stop
     |the child before completing any route `prepare` step. Record the child run
     |id, selected mode, complete structured status evidence, exact intended
     |family/root set, `[family root] -> sync.root` map, selected workspace, and
     |Weaver identity. Any mismatch fails loudly. Coordinate alignment and refresh
     |may start only after this evidence is recorded. Never stop or restart a
     |canonical or already-running Weaver. Leave the child run available for the
     |post-refresh continuation."
    worktree (consumer-workspace worktree) worktree (consumer-workspace worktree)
    (if inherited-pre-refresh-evidence
      " :inherited-pre-refresh-evidence true"
      "")
    (if inherited-pre-refresh-evidence
      (str "reuse the calling bump workflow's exact `:bump-pre-refresh-evidence` "
           "without running or re-entering `spool status`")
      "run the exact status command while it is still available"))))

(defn- bootstrap-completion-instruction
  "Return instructions that resume bootstrap from recorded pre-refresh evidence."
  [{:keys [worktree]}]
  (fmt/reflow
   (format
    "|In worktree `%s`, resume the exact child `bootstrap-kondo` run and adoption
     |mode recorded by `prepare-bootstrap-kondo`. Require its pre-refresh status,
     |intended family/root set, current-root map, selected workspace, and Weaver
     |identity to equal the repository-inspection evidence exactly. Combine that
     |recorded evidence with the preceding producer refresh result and runtime
     |status. Do not run, retry, or otherwise re-enter `spool status` after
     |refresh. Drive every route step until the child result reports `:done true`.
     |Do not treat the routed checkpoint as a return from this workflow. Before
     |completing this step, record the child run id, selected mode, and explicit
     |done evidence, for example `{:bootstrap-kondo-run-id \"child-run\"
     |:bootstrap-kondo-mode \"brownfield\" :bootstrap-kondo-done true}`. Leave
     |the child run available for audit and preserve its exact command and result
     |evidence."
    worktree)))

(defn- route-steps
  "Return the ordinary agent-owned setup steps for repository `style`."
  [style]
  [(workflow/step :prepare-bootstrap-kondo
                  "Capture bootstrap spool evidence before producer alignment"
                  :self
                  bootstrap-preparation-instruction)
   (workflow/step :prove-invocation-producer
                  "Prove the consumer uses the invocation producer coordinate"
                  :self
                  :depends-on [:prepare-bootstrap-kondo]
                  invocation-producer-instruction)
   (workflow/step :bootstrap-kondo
                  "Bootstrap Kondo in a separate registered run"
                  :self
                  :depends-on [:prove-invocation-producer]
                  bootstrap-completion-instruction)
   (workflow/step :align-tools-deps
                  "Align the tools.deps view with the effective spool world"
                  :self
                  :depends-on [:bootstrap-kondo]
                  (fn [params] (basis-instruction style params)))
   (workflow/step :configure-lsp
                  "Configure and prove clojure-lsp analysis"
                  :self
                  :depends-on [:align-tools-deps]
                  (fn [params] (lsp-instruction style params)))
   (workflow/step :configure-lint
                  "Configure and prove lint and clj-kondo analysis"
                  :self
                  :depends-on [:configure-lsp]
                  (fn [params] (lint-instruction style params)))
   (workflow/step :verify-tests
                  "Verify repository tests"
                  :self
                  :depends-on [:configure-lint]
                  (fn [params] (test-instruction style params)))
   (workflow/step :verify-weaver
                  "Prove the selected Weaver behavior or record pending proof"
                  :self
                  :depends-on [:verify-tests]
                  (fn [params] (weaver-instruction style params)))
   (workflow/step :handover
                  "Hand over repository tooling and generation evidence"
                  :self
                  :depends-on [:verify-weaver]
                  (fn [params] (handover-instruction style params)))])
(workflow/defworkflow configure-consumer-tooling
  "Choose and configure tooling for a Millstrand consumer repository style.

  Start or call it with the exact consumer worktree, its derived
  `worktree/.millstrand` workspace, and invocation-producer coordinate. The
  workspace is verified against the worktree and never taken from a disposable
  workflow host. The coordinate must be either a pinned remote
  `millhouse/spools` family or a local Millhouse self root. The agent first
  acquires and verifies the exact consumer Weaver, then inspects the effective
  spool world and repository conventions before choosing `app`, `spool`, or
  `clojure-app`. A calling bump workflow supplies the exact status evidence it
  verified before its first coordinate mutation; tooling reuses that evidence
  without another status command. The selected continuation starts the
  registered `bootstrap-kondo` workflow and records or inherits its exact
  family/root/sync evidence.
  It pauses that child before route preparation, manually aligns and proves
  activation and dependencies against the exact coordinate, refreshes, then
  resumes the same child without spool-status CLI re-entry. It then proves
  tools.deps, clojure-lsp, clj-kondo/lint, tests, and Weaver behavior through
  ordinary manual steps. When producer alignment changes an active coordinate, its proof
  explicitly refreshes the runtime. A fully applied refresh continues normally;
  the only accepted pending result is top-level `:status :partial` with a
  nonempty module outcome map. Every top-level `:modules` map key must equal its
  outcome `:module/key`; reject any missing or mismatched map-key identity. Every
  unchanged module must have `:status :unchanged` and no `:error`, `:reason`,
  refusal, `:root/outcome`, `:dependency`, or `:dependency/outcome`. Every other
  module outcome is exactly one of a direct refused hard-conflict with the
  exact `:root/outcome` and shared changed-root/residual classification, or a
  failed/skipped missing-dependency wrapper whose
  `:dependency` equals the nested `:dependency/outcome` `:module/key` at every
  hop. Wrapper chains are finite and acyclic and terminate in a direct refused
  hard-conflict whose `:conflict` exactly matches that shared classification.
  Every direct refused hard-conflict terminal, including one reached through a
  missing-dependency chain, carries `:root-lib` equal to exactly one `:lib` in
  the declared nonempty changed-root set; reject any terminal whose `:root-lib`
  is outside or mismatched against that set. No applied outcome, other status
  or reason, missing or mismatched dependency, cycle, unrelated terminal, or
  other refusal/error is allowed. Each
  changed-root entry has exactly `:lib`, `:previous-root`, and `:new-root`.
  Every previous root must equal its unique pre-refresh current-root evidence;
  the changed-root set and prepared-root set must be exact. Residuals map one to
  one to those entries, have nonempty providers, and use only `:root-repointed` or
  `:unledgered-loaded-namespace`; root-repointed has exactly one old binding.
  Binding and provider entries use `:root-lib`, equal the matched changed-root
  `:lib`, and reconcile every namespace, old/new root, and nonempty distinct
  provider `:file` path. Unledgered residuals have no binding. Empty,
  vacuous, duplicate, missing, extra, unrelated, or mixed mappings fail
  loudly, as do wrong root-lib, root, namespace, or provider paths. The runtime
  status also contains the matching pending-generation record, whose `:diff`
  exactly equals the conflict classification including both `:changed-roots`
  and `:namespace-residuals`. It records
  current and prepared generations and uses prepared roots for tooling without
  claiming adoption. Other partial or error results fail loudly. With no
  coordinate change, the workflow runs the same full
  `(runtime/refresh! (current/runtime))` and records that result together with
  `(runtime/status (current/runtime))`. It accepts only top-level refresh `:status
  :unchanged`, a `:modules` map whose every module has the exact unchanged shape
  above, and runtime `:pending-generation nil`. Before refresh, the selected
  activation and relevant producer metadata define one exact intended
  family/root set. The recorded status `:families` and every `:roots` map cover
  exactly that set, with no missing, extra, or mismatched family/root. Every root
  outcome is `:status :synced` with a `:sync` map whose nonempty `:root` (`:sync.root`)
  equals the recorded active-root evidence for that family/root. Failed,
  conflicted, source-reload, partial, missing, extra, or mismatched roots, and
  absent, blank, or otherwise invalid `:sync.root`, fail loudly. Applied,
  partial, refused, error, other module statuses, non-nil pending generation, or
  malformed and contradictory results fail loudly. No step re-enters spool
  status after refresh. Weaver proof remains
  current-generation-only. It contains no executor gates and never restarts a
  Weaver."
  {:entrypoints #{:start :call}
   :param-spec ::consumer-tooling-params
   :defaults {:inherited-pre-refresh-evidence false}
   :example {:worktree "/abs/path/to/consumer-worktree"
             :workspace "/abs/path/to/consumer-worktree/.millstrand"
             :inherited-pre-refresh-evidence false
             :invocation-producer
             {:kind "pinned-remote-family"
              :family "millhouse/spools"
              :coordinate {:git/url "https://github.com/codethread/millhouse.spool.git"
                           :git/sha "0123456789012345678901234567890123456789"}}}
   :param-docs {:worktree "Exact consumer worktree to inspect and update."
                :workspace "Derived consumer workspace; must equal worktree/.millstrand."
                :inherited-pre-refresh-evidence
                (fmt/reflow
                 "|True only for a calling bump workflow that already recorded
                  |complete exact pre-mutation status evidence in its run context.
                  |Tooling and its bootstrap child must reuse that evidence without
                  |another status command.")
                :invocation-producer
                (fmt/reflow
                 "|Required exact coordinate of the Millhouse workflow producer:
                  |use `pinned-remote-family` with `millhouse/spools`, `git/url`,
                  |and a full lowercase `git/sha` for ordinary consumers, or
                  |`local-self-root` with the exact self `root` and `local/root`
                  |when Millhouse is the consumer. The workflow never infers or
                  |edits this coordinate.")}}
  (workflow/workflow
   "Configure consumer tooling by repository style"
   (workflow/step :inspect-repository
                  "Inspect the effective spool world and classify the repository"
                  :self
                  inspect-repository-instruction)
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
  recorded before the continuation's producer-coordinate proof."
  {:entrypoints #{:continue}
   :param-spec ::consumer-tooling-params}
  (apply workflow/workflow
         "Configure application Millstrand tooling"
         (route-steps :app)))

(workflow/defworkflow configure-consumer-tooling-spool
  "Configure tooling for a repository that owns Millstrand spool roots.

  This continuation is selected by `configure-consumer-tooling`; callers
  normally start the parent so repository inspection and style choice are
  recorded before the continuation's producer-coordinate proof."
  {:entrypoints #{:continue}
   :param-spec ::consumer-tooling-params}
  (apply workflow/workflow
         "Configure spool repository tooling"
         (route-steps :spool)))

(workflow/defworkflow configure-consumer-tooling-clojure-app
  "Configure tooling for a Clojure application with Millstrand config.

  This continuation is selected by `configure-consumer-tooling`; callers
  normally start the parent so repository inspection and style choice are
  recorded before the continuation's producer-coordinate proof."
  {:entrypoints #{:continue}
   :param-spec ::consumer-tooling-params}
  (apply workflow/workflow
         "Configure Clojure application Millstrand tooling"
         (route-steps :clojure-app)))
