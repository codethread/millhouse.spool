
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
# <a name="millhouse.spools.millstrand-workflows.bump-millstrand">millhouse.spools.millstrand-workflows.bump-millstrand</a>


The local-aware consumer workflow for updating Millstrand.

  The workflow does not guess whether a consumer dependency is local or
  pinned. It asks the caller to inspect the selected deps.edn and records that
  classification as a checkpoint choice. A local checkout needs a second
  explicit decision before validation; a pinned checkout delegates to the
  registered bump-spool workflow.




## <a name="millhouse.spools.millstrand-workflows.bump-millstrand/bump-millstrand">`bump-millstrand`</a>




Inspect a consumer Millstrand coordinate and choose its honest update path.

  A local sibling coordinate is never converted into a guessed SHA. The
  classification and local-checkout decision are recorded as checkpoints. A
  Git/SHA-pinned coordinate routes to the registered bump-spool workflow with
  the single Millstrand request supplied by the caller.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/millstrand-workflows/src/millhouse/spools/millstrand_workflows/bump_millstrand.clj#L103-L166">Source</a></sub></p>

## <a name="millhouse.spools.millstrand-workflows.bump-millstrand/bump-millstrand-local">`bump-millstrand-local`</a>




Require an explicit decision before validating a local Millstrand checkout.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/millstrand-workflows/src/millhouse/spools/millstrand_workflows/bump_millstrand.clj#L168-L183">Source</a></sub></p>

## <a name="millhouse.spools.millstrand-workflows.bump-millstrand/bump-millstrand-local-validate">`bump-millstrand-local-validate`</a>




Validate a consumer against its explicitly approved local Millstrand checkout.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/millstrand-workflows/src/millhouse/spools/millstrand_workflows/bump_millstrand.clj#L185-L269">Source</a></sub></p>

## <a name="millhouse.spools.millstrand-workflows.bump-millstrand/bump-millstrand-pinned">`bump-millstrand-pinned`</a>




Delegate a Git/SHA-pinned Millstrand update to registered bump-spool.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/millstrand-workflows/src/millhouse/spools/millstrand_workflows/bump_millstrand.clj#L271-L284">Source</a></sub></p>

-----
# <a name="millhouse.spools.millstrand-workflows.bump-spool">millhouse.spools.millstrand-workflows.bump-spool</a>


The portable consumer workflow for bumping a pinned Millstrand spool.

  This workflow assumes that its caller has selected the worktree and workspace
  in which the change is allowed. It describes the work; it does not choose a
  branch, land a change, or infer permission to restart a runtime.




## <a name="millhouse.spools.millstrand-workflows.bump-spool/bump-spool">`bump-spool`</a>




Bump a pinned spool in a selected consumer worktree and refresh its runtime.

  The caller supplies the exact worktree and Millstrand workspace. The workflow
  imports dependency clj-kondo exports once, reviews and commits those copied
  configs, then runs the consumer's ordinary quality boundary. Runtime
  cutover is offered only when the invocation records a direct user request;
  other invocations stop at a pending-generation handover.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/millstrand-workflows/src/millhouse/spools/millstrand_workflows/bump_spool.clj#L82-L288">Source</a></sub></p>
