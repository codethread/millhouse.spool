# Millhouse Land infrastructure

`millhouse/land` supplies conservative landing primitives for repositories using
GitHub, `origin/main`, Millhouse Workflow, and Millhouse Kanban. It does **not**
register a `land`, `review`, `land-merge`, or `land-abort` workflow. Each
repository owns that policy in its `.millstrand` workspace so its review,
merge-history, release, and cleanup choices remain visible in one small graph.

The package publishes:

- the durable FIFO `merge-queue` operation;
- the `merge-turn` and `merge-release` executors and their completion guard;
- the `merge-lock` and `merge-queue` named queries;
- card bookkeeping functions;
- frozen shell helpers for quality, PR checks, preparation, merge, main update,
  and cleanup.

- [cookbook](./land.cookbook.md)

## Repository workflow contract

A repository landing workflow may use the shared primitives, but owns their
composition and policy. A queue-compatible merge continuation must:

1. mark its root with `workflow/family=land`, `land/stage=merge`, and the fully
   qualified local `land/abort-definition`;
2. enter through a `merge-turn` gate;
3. validate the exact pushed branch through the repository's executable
   `.millstrand/land-quality.sh`;
4. perform one explicitly selected merge method;
5. update canonical `main` and close a `merge-release` gate before housekeeping;
6. run repository cleanup and card completion according to local policy.

`land/abort-definition` lets safe queue withdrawal compile the repository's own
abort continuation. The infrastructure never chooses review requirements,
checkpoint wording, merge history, or card completion policy.

Preparation accepts one explicit branch-update policy:

- `rebase` rebases an outdated branch onto `origin/main`, pushes with an exact
  lease, and reruns quality at the changed HEAD;
- `preserve` refuses an outdated branch so immutable candidate commits are never
  rewritten.

Merge accepts `squash` or `merge`. Rebase merging is intentionally absent because
it rewrites commit identities. Repositories select one method in their workflow;
there is no ambient default.

## Queue guarantees

Landing is serialized by durable FIFO reservations. A failed or timed-out head
keeps its queue position and lock. Queue scanning performs only short mutations
and never blocks an event thread. The merge turn is released after canonical
`main` advances and before housekeeping, so cleanup cannot hold the next merge.

`merge-turn` and `merge-release` are executor-only gates. A transaction precommit
guard prevents generic completion, spoofed actor names, or replacement
attributes from bypassing queue ownership. Any trusted agent may safely withdraw
a reservation before irreversible merge evidence exists. Once submission may
have occurred, withdrawal fails loudly and requires reconciliation.

Automatic delivery workflows should build CI gates with
`millhouse.land.support/pr-checks-argv`, passing `"required"` or
`"allow-empty"` and the expected branch. Both policies validate the exact open,
ready PR identity before and after check waiting.

Code bookkeeping gates use [managed Workflow execution](../workflow/execution.md).
Inspect their current attempt with `workflow execution`, then explicitly authorize
one settled failed attempt with `workflow retry`; deleting `gate/error` is not
Code retry authority. The existing Shell and queue repair paths are unchanged.

Roots containing managed Code work now require exact freeze/positive-retirement
receipts before routed abandonment. The legacy Land withdrawal operation does
not yet compose that receipt and refuses rather than silently orphaning Code.
Cross-backend operational freeze and Land withdrawal integration belong to the
Shell conversion; do not bypass either the Workflow or queue guards in the interim.

## Activation

Add the Land coordinate. Its root depends on the sibling Workflow and Kanban
roots:

```clojure
{:deps
 {millhouse/land
  {:git/url "https://github.com/codethread/millhouse.spool.git"
   :git/sha "<immutable-sha>"
   :deps/root "spools/land"}}}
```

Activate Workflow and Kanban before the infrastructure module, then activate the
repository's file-backed landing workflow separately:

```clojure
(runtime/module! runtime :app/land-infrastructure
  {:ns 'millhouse.land.spool
   :after [:app/workflow :app/kanban]
   :required? true})

(runtime/module! runtime :app/land
  {:file "me/land.clj"
   :after [:app/land-infrastructure]
   :required? true})
```

The dependency coordinate makes source available but does not activate modules.
Dependency changes require a Weaver generation replacement.

## Repository hooks

Each repository owns executable `.millstrand/land-quality.sh`. It runs with
`LAND_EXPECTED_BRANCH` and `LAND_EXPECTED_HEAD`; shared code does not guess a
build command.

A repository may also provide executable `.millstrand/land-cleanup.sh`. The
cleanup helper invokes it in the feature worktree before removal and requires it
to leave the exact HEAD clean. Cleanup is idempotent and refuses mismatched,
dirty, or ambiguously owned resources.
