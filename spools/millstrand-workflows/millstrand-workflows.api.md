
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

  The caller names the owning root, public namespace, spool key, and every
  macro-to-hook mapping. The workflow then walks the obligations in order:
  verify root ownership, publish resources on the root classpath, publish the
  explicit clj-kondo export and hooks, test the exported contract, and document
  the public surface. It does not discover macros automatically or perform
  filesystem edits itself; each step is an agent-facing instruction.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/millstrand-workflows/src/millhouse/spools/millstrand_workflows.clj#L112-L201">Source</a></sub></p>

-----
# <a name="millhouse.spools.millstrand-workflows.bootstrap-kondo">millhouse.spools.millstrand-workflows.bootstrap-kondo</a>


Bootstrap Millstrand clj-kondo support in an explicitly selected consumer.

  The workflow asks whether the consumer is greenfield or brownfield before it
  gives configuration instructions. Both routes import the complete resolved
  classpath once, validate provenance and cache hygiene, and hand back the
  local quality command for the consumer rather than guessing one.




## <a name="millhouse.spools.millstrand-workflows.bootstrap-kondo/bootstrap-kondo">`bootstrap-kondo`</a>




Choose a greenfield or brownfield Kondo bootstrap for a consumer checkout.

  Both routes import all resolved dependency exports once, validate provenance,
  duplicate mappings, and cache hygiene, discover local quality checks, and
  leave a precise handover. No repository hosting or release operation is part
  of this workflow.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/millstrand-workflows/src/millhouse/spools/millstrand_workflows/bootstrap_kondo.clj#L155-L191">Source</a></sub></p>

## <a name="millhouse.spools.millstrand-workflows.bootstrap-kondo/bootstrap-kondo-brownfield">`bootstrap-kondo-brownfield`</a>




Inventory and merge an existing Kondo boundary before importing exports.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/millstrand-workflows/src/millhouse/spools/millstrand_workflows/bootstrap_kondo.clj#L207-L219">Source</a></sub></p>

## <a name="millhouse.spools.millstrand-workflows.bootstrap-kondo/bootstrap-kondo-greenfield">`bootstrap-kondo-greenfield`</a>




Establish a greenfield Kondo boundary and import Millstrand exports.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/millstrand-workflows/src/millhouse/spools/millstrand_workflows/bootstrap_kondo.clj#L193-L205">Source</a></sub></p>

-----
# <a name="millhouse.spools.millstrand-workflows.bump-millstrand">millhouse.spools.millstrand-workflows.bump-millstrand</a>


The local-aware consumer workflow for updating Millstrand.

  The workflow asks the caller to inspect the selected coordinate. A local
  checkout stays local and uses the shared Kondo bootstrap; a pinned checkout
  delegates to bump-spool, whose family-only contract requests the remote
  default-branch HEAD SHA automatically.




## <a name="millhouse.spools.millstrand-workflows.bump-millstrand/bump-millstrand">`bump-millstrand`</a>




Inspect a consumer Millstrand coordinate and choose its honest update path.

  A local sibling coordinate is never converted into a guessed SHA. A pinned
  coordinate delegates to bump-spool, which requests the remote default-branch HEAD SHA and
  then calls the shared Kondo bootstrap.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/millstrand-workflows/src/millhouse/spools/millstrand_workflows/bump_millstrand.clj#L86-L129">Source</a></sub></p>

## <a name="millhouse.spools.millstrand-workflows.bump-millstrand/bump-millstrand-local">`bump-millstrand-local`</a>




Require an explicit decision before validating a local Millstrand checkout.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/millstrand-workflows/src/millhouse/spools/millstrand_workflows/bump_millstrand.clj#L131-L146">Source</a></sub></p>

## <a name="millhouse.spools.millstrand-workflows.bump-millstrand/bump-millstrand-local-validate">`bump-millstrand-local-validate`</a>




Bootstrap Kondo for an explicitly approved local Millstrand checkout.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/millstrand-workflows/src/millhouse/spools/millstrand_workflows/bump_millstrand.clj#L148-L187">Source</a></sub></p>

## <a name="millhouse.spools.millstrand-workflows.bump-millstrand/bump-millstrand-pinned">`bump-millstrand-pinned`</a>




Delegate a Git/SHA-pinned Millstrand update to registered bump-spool.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/millstrand-workflows/src/millhouse/spools/millstrand_workflows/bump_millstrand.clj#L189-L201">Source</a></sub></p>

-----
# <a name="millhouse.spools.millstrand-workflows.bump-spool">millhouse.spools.millstrand-workflows.bump-spool</a>


The portable consumer workflow for bumping pinned Millstrand spool families.

  This workflow assumes that its caller has selected the worktree and workspace
  in which the change is allowed. It requests the remote default-branch HEAD SHA for each
  family, tolerates an already-current coordinate, then reuses the shared Kondo
  bootstrap before handing over the refreshed runtime.




## <a name="millhouse.spools.millstrand-workflows.bump-spool/bump-spool">`bump-spool`</a>




Bump selected spool families to their remote default-branch HEAD SHA and bootstrap Kondo.

  The caller supplies exact consumer paths and family names. Each bump uses
  `spool bump FAMILY --latest sha`; an already-current coordinate is recorded
  and accepted. The shared bootstrap workflow then handles greenfield or
  brownfield Kondo adoption, local quality discovery, and handover. Runtime
  cutover is offered only for a direct user request.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/millstrand-workflows/src/millhouse/spools/millstrand_workflows/bump_spool.clj#L37-L155">Source</a></sub></p>
