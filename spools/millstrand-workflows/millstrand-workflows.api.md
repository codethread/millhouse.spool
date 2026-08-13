
-----
# <a name="millhouse.spools.millstrand-workflows">millhouse.spools.millstrand-workflows</a>


Publisher-side workflows for reusable Millstrand and clj-kondo spool support.

  This namespace deliberately describes the publisher's obligations instead of
  trying to inspect a source tree. A publisher supplies the macro forms and
  their exported hook namespaces; the workflow turns those facts into an
  explicit, reviewable sequence of classpath, export, test, and documentation
  work.




## <a name="millhouse.spools.millstrand-workflows/publish-spool-kondo">`publish-spool-kondo`</a>




Publish clj-kondo support for a macro-owning spool root.

  Start the registered workflow with a complete publisher contract:

  ```clojure
  (require '[millhouse.spools.workflow :as workflow])

  (workflow/start! "publish-example" :publish-spool-kondo
    {:spool-root "spools/example-macros"
     :namespace "example.macros"
     :spool-key "example/macros"
     :macro-forms [{:macro "example.macros/defwidget"
                    :hook "hooks.example/defwidget"}]})
  ```

  The caller names the owning root, public namespace, spool key, and every
  macro-to-hook mapping. The workflow then walks the obligations in order:
  verify root ownership, publish resources on the root classpath, publish the
  explicit clj-kondo export and hooks, test the exported contract, and document
  the public surface. It does not discover macros automatically or perform
  filesystem edits itself; each step is an agent-facing instruction.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/millstrand-workflows/src/millhouse/spools/millstrand_workflows.clj#L112-L214">Source</a></sub></p>

-----
# <a name="millhouse.spools.millstrand-workflows.bootstrap-kondo">millhouse.spools.millstrand-workflows.bootstrap-kondo</a>


Bootstrap Millstrand clj-kondo support in an explicitly selected consumer.

  The workflow asks whether the consumer is greenfield or brownfield before it
  gives configuration instructions. Both routes import the complete eligible
  resolved classpath once, excluding consumer-owned producer roots, make
  explicit bootstrap the sole Kondo import owner, manually validate provenance
  and cache hygiene, and hand back the local quality command for the consumer
  rather than guessing one.




## <a name="millhouse.spools.millstrand-workflows.bootstrap-kondo/bootstrap-kondo">`bootstrap-kondo`</a>




Choose a greenfield or brownfield Kondo bootstrap for a consumer checkout.

  Start it with the exact consumer worktree and Millstrand workspace:

  ```clojure
  (require '[millhouse.spools.workflow :as workflow])

  (workflow/start! "consumer-kondo" :bootstrap-kondo
    {:worktree "/abs/path/to/consumer-worktree"
     :workspace "/abs/path/to/consumer-worktree/.millstrand"})
  ```

  Both routes import all eligible resolved dependency exports once, excluding
  consumer-owned producer roots, validate provenance, duplicate mappings, and
  cache hygiene manually, discover local quality checks, and leave a precise
  handover. After import, each retained dependency export is manually activated
  exactly once in the consumer `.clj-kondo/config.edn` `:config-paths` using a
  repository-relative entry resolved from that config, normally
  `imports/<group>/<artifact>`. Existing local entries remain byte-for-byte
  preserved. An existing portable entry that resolves to the retained import is
  validated as the one activation rather than duplicated. The workflow
  canonicalizes resolved targets only for provenance, ownership, ambiguity, and
  exact-once identity comparison; absolute or outside-repository activations,
  missing, duplicate, ambiguous, or owned paths fail loudly. Both route
  preparations merge
  `:copy-kondo-configs? false` into `.lsp/config.edn` without overwriting other
  LSP settings, so explicit bootstrap remains the sole import owner. Before any
  coordinate edit or refresh, a standalone parent captures exact selected
  family/root/sync evidence. A calling bump workflow instead supplies the exact
  evidence it verified before its first coordinate mutation. The routes use
  that recorded evidence and never re-enter spool status after mutation or
  refresh. A fully applied refresh uses active `sync.root` values.

  The only pending shape this workflow accepts is top-level `:status :partial`
  with a nonempty module outcome map. Every top-level `:modules` map key equals its outcome
  `:module/key`; reject any missing or mismatched map-key identity. Every
  unchanged module must have `:status :unchanged` and no `:error`, `:reason`,
  refusal, `:root/outcome`, `:dependency`, or `:dependency/outcome`. Every other
  module outcome is exactly one of a direct refused hard-conflict with the exact
  `:root/outcome` and shared changed-root/residual classification, or a
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
  Every previous root equals its unique pre-refresh current-root evidence, and
  the changed-root set equals the prepared-root set. Residuals map one to one
  to those entries, have nonempty providers, and use only `:root-repointed` or
  `:unledgered-loaded-namespace`; root-repointed has exactly one old binding.
  Binding and provider entries use `:root-lib`, equal the matched changed-root
  `:lib`, and reconcile every namespace, old/new root, and nonempty distinct
  provider `:file` path. Unledgered residuals have no binding. Empty, vacuous,
  duplicate, missing, extra, unrelated, or mixed mappings fail loudly, as do
  wrong root-lib, root, namespace, or provider paths. The runtime status also
  contains the matching pending-generation record, whose `:diff` exactly
  equals the conflict classification including both `:changed-roots` and
  `:namespace-residuals`. It records current and
  prepared generations, validates each prepared root's coordinate, cache
  provenance, `deps.edn`, and exports, and uses prepared roots for tooling
  without claiming Weaver adoption. Other partial or error results fail
  loudly. With no coordinate change, accept only the recorded full
  `(runtime/refresh! (current/runtime))` result with top-level refresh `:status
  :unchanged`, every module in `:modules` with the exact unchanged shape above,
  and runtime `:pending-generation nil`. The selected activation and relevant
  producer metadata define one exact intended family/root set before refresh;
  the recorded status `:families` and every `:roots` map cover exactly that
  set, with no missing, extra, or mismatched family/root. Every root outcome must be
  `:status :synced` with a `:sync` map whose nonempty `:root` (`:sync.root`)
  equals the recorded active-root evidence for that family/root. Failed,
  conflicted, source-reload, partial, missing, extra, or mismatched roots, and
  absent, blank, or otherwise invalid `:sync.root`, fail loudly. Applied,
  partial, refused, error, other module statuses, non-nil pending generation, or
  malformed and contradictory evidence fail loudly. Before
  resolving paths, the agent parses the selected spool metadata once into an
  `owned-roots` table. It canonicalizes every installed contribution entry,
  every consumer classpath entry, and every owned classpath/export comparison
  path with one path operation before union and ownership filtering. A failed
  canonicalization is a loud pre-copy failure. It filters the final combined
  classpath for exact owned producer classpath/export paths and their
  descendants, retains true remote dependency exports, and asserts immediately
  before `KONDO_CMD` using only canonical paths that no owned export remains. It
  fails loudly on ambiguous or unreconcilable canonical ownership. The explicit
  `millstrand/source-root` contribution and its `BASE`-derived paths remain
  retained even under the worktree. The agent-owned import derives every
  installed root's classpath from the recorded pre-refresh `sync.root` evidence
  and `deps.edn` `:paths`, defaulting absent `:paths` to the `src`
  path while preserving explicit `[]`. A declared `millstrand/source-root`
  coordinate is special: derive `BASE` by removing exactly its relative path
  segments from the end of `sync.root`, validate that `BASE` joined with the
  declaration reconstructs `sync.root`, then read `BASE/deps.edn` and add its
  declared paths, including `resources`, to the classpath. Require the
  Millstrand core export at
  `BASE/resources/clj-kondo.exports/io.millstrand/millstrand/` and fail loudly
  for an absent or unreconcilable base, a `BASE/deps.edn` that is missing, not a
  readable regular file, or invalid EDN, or a missing export; do not search
  upward or guess. Failures report the exact coordinate, source-root values,
  failing path, and permitted corrective invariant. The installed source-root
  spool is still resolved normally. It canonicalizes every installed directory,
  consumer classpath entry, and owned classpath/export comparison path with the
  same operation before union and filtering, then records the exact roots and
  final canonical-filtered classpath, including each base derivation, before one
  `KONDO_CMD --lint RESOLVED_CLASSPATH
  --dependencies --parallel --copy-configs --skip-lint` invocation. Immediately
  before it, the agent asserts using only canonical paths that no owned producer
  classpath or export path, or descendant, remains in `RESOLVED_CLASSPATH`, and
  that every owned export path is absent. Plain consumer `clojure -Spath` alone
  is insufficient; unresolved or ambiguously owned roots, failed
  canonicalization, unreconcilable canonical ownership, and an empty filtered
  installed-spool contribution fail loudly. No cleanup-after-copy workaround is
  allowed. No repository hosting or release operation is part of this workflow.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/millstrand-workflows/src/millhouse/spools/millstrand_workflows/bootstrap_kondo.clj#L422-L588">Source</a></sub></p>

## <a name="millhouse.spools.millstrand-workflows.bootstrap-kondo/bootstrap-kondo-brownfield">`bootstrap-kondo-brownfield`</a>




Inventory and merge an existing Kondo boundary before importing exports.

  This is the `brownfield` continuation selected by `bootstrap-kondo`; callers
  normally start the parent workflow so adoption mode is recorded first.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/millstrand-workflows/src/millhouse/spools/millstrand_workflows/bootstrap_kondo.clj#L607-L622">Source</a></sub></p>

## <a name="millhouse.spools.millstrand-workflows.bootstrap-kondo/bootstrap-kondo-greenfield">`bootstrap-kondo-greenfield`</a>




Establish a greenfield Kondo boundary and import Millstrand exports.

  This is the `greenfield` continuation selected by `bootstrap-kondo`; callers
  normally start the parent workflow so adoption mode is recorded first.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/millstrand-workflows/src/millhouse/spools/millstrand_workflows/bootstrap_kondo.clj#L590-L605">Source</a></sub></p>

-----
# <a name="millhouse.spools.millstrand-workflows.bump-millstrand">millhouse.spools.millstrand-workflows.bump-millstrand</a>


The local-aware consumer workflow for updating Millstrand.

  The workflow asks the caller to inspect the selected coordinate. A local
  checkout stays local and uses the shared Kondo bootstrap and repository-style
  tooling setup; a pinned checkout delegates to bump-spool, whose family-only
  contract requests the remote default-branch HEAD SHA automatically.




## <a name="millhouse.spools.millstrand-workflows.bump-millstrand/bump-millstrand">`bump-millstrand`</a>




Inspect a consumer Millstrand coordinate and choose its honest update path.

  Start it with exactly one Millstrand family and the dependency file to
  inspect:

  ```clojure
  (require '[millhouse.spools.workflow :as workflow])

  (workflow/start! "millstrand-bump" :bump-millstrand
    {:families ["io.millstrand/millstrand"]
     :worktree "/abs/path/to/consumer-worktree"
     :workspace "/abs/path/to/consumer-worktree/.millstrand"
     :invocation-producer {:kind "pinned-remote-family"
                           :family "millhouse/spools"
                           :coordinate {:git/url "https://github.com/codethread/millhouse.spool.git"
                                        :git/sha "0123456789012345678901234567890123456789"}}
     :direct-user-request false
     :deps-file "deps.edn"})
  ```

  A local sibling coordinate is never converted into a guessed SHA. A pinned
  coordinate delegates to bump-spool, which requests the remote default-branch
  HEAD SHA. Both routes choose the consumer repository style and manually align
  LSP, lint, tests, and Weaver proof without new executor gates.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/millstrand-workflows/src/millhouse/spools/millstrand_workflows/bump_millstrand.clj#L97-L174">Source</a></sub></p>

## <a name="millhouse.spools.millstrand-workflows.bump-millstrand/bump-millstrand-local">`bump-millstrand-local`</a>




Require an explicit decision before validating a local Millstrand checkout.

  This is the local continuation selected after `bump-millstrand` classifies
  the exact dependency coordinate.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/millstrand-workflows/src/millhouse/spools/millstrand_workflows/bump_millstrand.clj#L176-L198">Source</a></sub></p>

## <a name="millhouse.spools.millstrand-workflows.bump-millstrand/bump-millstrand-local-validate">`bump-millstrand-local-validate`</a>




Configure an explicitly approved local Millstrand checkout.

  This continuation preserves the local coordinate, runs shared bootstrap,
  configures repository-style tooling, and then refreshes the selected runtime
  before handing over or cutting over.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/millstrand-workflows/src/millhouse/spools/millstrand_workflows/bump_millstrand.clj#L200-L251">Source</a></sub></p>

## <a name="millhouse.spools.millstrand-workflows.bump-millstrand/bump-millstrand-pinned">`bump-millstrand-pinned`</a>




Delegate a Git/SHA-pinned Millstrand update to registered bump-spool.

  This continuation is selected after coordinate classification and keeps the
  family-only remote default-branch SHA contract.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/millstrand-workflows/src/millhouse/spools/millstrand_workflows/bump_millstrand.clj#L253-L270">Source</a></sub></p>

-----
# <a name="millhouse.spools.millstrand-workflows.bump-spool">millhouse.spools.millstrand-workflows.bump-spool</a>


The portable consumer workflow for bumping pinned Millstrand spool families.

  This workflow assumes that its caller has selected the worktree and workspace
  in which the change is allowed. It requests the remote default-branch HEAD SHA
  for each family, tolerates an already-current coordinate, then reuses the shared
  Kondo bootstrap and repository-style tooling setup before handing over the
  refreshed runtime.




## <a name="millhouse.spools.millstrand-workflows.bump-spool/bump-spool">`bump-spool`</a>




Bump selected spool families and configure consumer tooling.

  Start it with family names and the exact consumer paths:

  ```clojure
  (require '[millhouse.spools.workflow :as workflow])

  (workflow/start! "consumer-bump" :bump-spool
    {:families ["io.millstrand/millstrand" "millhouse/spools"]
     :worktree "/abs/path/to/consumer-worktree"
     :workspace "/abs/path/to/consumer-worktree/.millstrand"
     :invocation-producer {:kind "pinned-remote-family"
                           :family "millhouse/spools"
                           :coordinate {:git/url "https://github.com/codethread/millhouse.spool.git"
                                        :git/sha "0123456789012345678901234567890123456789"}}
     :direct-user-request false})
  ```

  The caller supplies exact consumer paths and family names. Before the first
  coordinate mutation, the workflow verifies and records one complete status
  result, exact intended family/root set, and `[family root] -> sync.root` map.
  Each bump then uses `spool bump FAMILY --latest sha`; an already-current
  coordinate is recorded and accepted. The shared bootstrap and consumer
  tooling workflows inherit the exact pre-bump evidence without another status
  command. Before the final refresh, an agent chooses the repository style and
  manually aligns LSP, lint, tests, and Weaver proof without new executor gates.
  Runtime cutover is offered only for a direct user request.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/millstrand-workflows/src/millhouse/spools/millstrand_workflows/bump_spool.clj#L63-L238">Source</a></sub></p>

-----
# <a name="millhouse.spools.millstrand-workflows.consumer-tooling">millhouse.spools.millstrand-workflows.consumer-tooling</a>


Agent-owned tooling setup for Millstrand consumer repository styles.

  The parent workflow records whether a consumer is an application-only
  repository, a spool library, or a Clojure application. Each continuation
  adapts tools.deps, LSP, clj-kondo, tests, and Weaver verification through
  ordinary manual steps. No executor gate assumes a universal repository
  layout or quality command.




## <a name="millhouse.spools.millstrand-workflows.consumer-tooling/configure-consumer-tooling">`configure-consumer-tooling`</a>




Choose and configure tooling for a Millstrand consumer repository style.

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
  Weaver.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/millstrand-workflows/src/millhouse/spools/millstrand_workflows/consumer_tooling.clj#L706-L840">Source</a></sub></p>

## <a name="millhouse.spools.millstrand-workflows.consumer-tooling/configure-consumer-tooling-app">`configure-consumer-tooling-app`</a>




Configure tooling for a non-Clojure product with Millstrand config.

  This continuation is selected by `configure-consumer-tooling`; callers
  normally start the parent so repository inspection and style choice are
  recorded before the continuation's producer-coordinate proof.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/millstrand-workflows/src/millhouse/spools/millstrand_workflows/consumer_tooling.clj#L842-L852">Source</a></sub></p>

## <a name="millhouse.spools.millstrand-workflows.consumer-tooling/configure-consumer-tooling-clojure-app">`configure-consumer-tooling-clojure-app`</a>




Configure tooling for a Clojure application with Millstrand config.

  This continuation is selected by `configure-consumer-tooling`; callers
  normally start the parent so repository inspection and style choice are
  recorded before the continuation's producer-coordinate proof.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/millstrand-workflows/src/millhouse/spools/millstrand_workflows/consumer_tooling.clj#L866-L876">Source</a></sub></p>

## <a name="millhouse.spools.millstrand-workflows.consumer-tooling/configure-consumer-tooling-spool">`configure-consumer-tooling-spool`</a>




Configure tooling for a repository that owns Millstrand spool roots.

  This continuation is selected by `configure-consumer-tooling`; callers
  normally start the parent so repository inspection and style choice are
  recorded before the continuation's producer-coordinate proof.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/millstrand-workflows/src/millhouse/spools/millstrand_workflows/consumer_tooling.clj#L854-L864">Source</a></sub></p>
