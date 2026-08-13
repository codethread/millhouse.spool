
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
  gives configuration instructions. Both routes import the complete resolved
  classpath once, make explicit bootstrap the sole Kondo import owner, manually
  validate provenance and cache hygiene, and hand back the local quality command
  for the consumer rather than guessing one.




## <a name="millhouse.spools.millstrand-workflows.bootstrap-kondo/bootstrap-kondo">`bootstrap-kondo`</a>




Choose a greenfield or brownfield Kondo bootstrap for a consumer checkout.

  Start it with the exact consumer worktree and Millstrand workspace:

  ```clojure
  (require '[millhouse.spools.workflow :as workflow])

  (workflow/start! "consumer-kondo" :bootstrap-kondo
    {:worktree "/abs/path/to/consumer-worktree"
     :workspace "/abs/path/to/consumer-worktree/.millstrand"})
  ```

  Both routes import all resolved dependency exports once, validate provenance,
  duplicate mappings, and cache hygiene manually, discover local quality checks,
  and leave a precise handover. Both route preparations merge
  `:copy-kondo-configs? false` into `.lsp/config.edn` without overwriting other
  LSP settings, so explicit bootstrap remains the sole import owner. The
  agent-owned import runs `strand --workspace
  <workspace> spool status`, derives every installed root's classpath from its
  `sync.root` and `deps.edn` `:paths`, defaulting absent `:paths` to the `src`
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
  spool is still resolved normally. It combines those directories with the
  consumer classpath and records the exact roots and classpath, including each
  base derivation, before one
  `KONDO_CMD --lint RESOLVED_CLASSPATH
  --dependencies --parallel --copy-configs --skip-lint` invocation. Plain
  consumer `clojure -Spath` alone is insufficient; unresolved roots and an empty
  installed-spool contribution fail loudly. No repository hosting or release
  operation is part of this workflow.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/millstrand-workflows/src/millhouse/spools/millstrand_workflows/bootstrap_kondo.clj#L226-L301">Source</a></sub></p>

## <a name="millhouse.spools.millstrand-workflows.bootstrap-kondo/bootstrap-kondo-brownfield">`bootstrap-kondo-brownfield`</a>




Inventory and merge an existing Kondo boundary before importing exports.

  This is the `brownfield` continuation selected by `bootstrap-kondo`; callers
  normally start the parent workflow so adoption mode is recorded first.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/millstrand-workflows/src/millhouse/spools/millstrand_workflows/bootstrap_kondo.clj#L320-L335">Source</a></sub></p>

## <a name="millhouse.spools.millstrand-workflows.bootstrap-kondo/bootstrap-kondo-greenfield">`bootstrap-kondo-greenfield`</a>




Establish a greenfield Kondo boundary and import Millstrand exports.

  This is the `greenfield` continuation selected by `bootstrap-kondo`; callers
  normally start the parent workflow so adoption mode is recorded first.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/millstrand-workflows/src/millhouse/spools/millstrand_workflows/bootstrap_kondo.clj#L303-L318">Source</a></sub></p>

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
  refreshes the selected runtime, and configures repository-style tooling before
  handing over or cutting over.
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

  The caller supplies exact consumer paths and family names. Each bump uses
  `spool bump FAMILY --latest sha`; an already-current coordinate is recorded
  and accepted. The shared bootstrap workflow handles greenfield or brownfield
  Kondo adoption. After refresh, an agent chooses the repository style and
  manually aligns LSP, lint, tests, and Weaver proof without new executor gates.
  Runtime cutover is offered only for a direct user request.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/millstrand-workflows/src/millhouse/spools/millstrand_workflows/bump_spool.clj#L41-L198">Source</a></sub></p>

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
  `clojure-app`. The selected continuation manually aligns
  and proves activation and dependencies against that exact coordinate before
  composing the registered `bootstrap-kondo` workflow and proving tools.deps,
  clojure-lsp, clj-kondo/lint, tests, and Weaver behavior through ordinary manual
  steps. It contains no executor gates and never restarts a Weaver.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/millstrand-workflows/src/millhouse/spools/millstrand_workflows/consumer_tooling.clj#L535-L607">Source</a></sub></p>

## <a name="millhouse.spools.millstrand-workflows.consumer-tooling/configure-consumer-tooling-app">`configure-consumer-tooling-app`</a>




Configure tooling for a non-Clojure product with Millstrand config.

  This continuation is selected by `configure-consumer-tooling`; callers
  normally start the parent so repository inspection and style choice are
  recorded before the continuation's producer-coordinate proof.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/millstrand-workflows/src/millhouse/spools/millstrand_workflows/consumer_tooling.clj#L609-L619">Source</a></sub></p>

## <a name="millhouse.spools.millstrand-workflows.consumer-tooling/configure-consumer-tooling-clojure-app">`configure-consumer-tooling-clojure-app`</a>




Configure tooling for a Clojure application with Millstrand config.

  This continuation is selected by `configure-consumer-tooling`; callers
  normally start the parent so repository inspection and style choice are
  recorded before the continuation's producer-coordinate proof.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/millstrand-workflows/src/millhouse/spools/millstrand_workflows/consumer_tooling.clj#L633-L643">Source</a></sub></p>

## <a name="millhouse.spools.millstrand-workflows.consumer-tooling/configure-consumer-tooling-spool">`configure-consumer-tooling-spool`</a>




Configure tooling for a repository that owns Millstrand spool roots.

  This continuation is selected by `configure-consumer-tooling`; callers
  normally start the parent so repository inspection and style choice are
  recorded before the continuation's producer-coordinate proof.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/millstrand-workflows/src/millhouse/spools/millstrand_workflows/consumer_tooling.clj#L621-L631">Source</a></sub></p>
