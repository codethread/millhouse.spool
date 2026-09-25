# Millhouse Land spool

`millhouse/land` provides a reusable, conservative landing workflow for repositories that use GitHub, `origin/main`, Millhouse Workflow, and Millhouse Kanban. It publishes the workflow names `review`, `land`, `land-merge`, and `land-abort`, the `merge-queue` operation, and the `merge-lock` and `merge-queue` named queries.

- [cookbook](./land.cookbook.md)
- [API](./land.api.md)

## Contracts

Landing is serialized by durable FIFO reservations. A failed or timed-out head keeps its queue position and lock. Queue scanning performs only short mutations and never blocks an event thread. The merge turn is released after canonical `main` fast-forwards and before housekeeping, so cleanup cannot hold up the next merge.

`merge-turn` and `merge-release` are executor-only queue gates. Land installs a transaction precommit guard before its persisted-gate scanner. The guard identifies these gates from the stored pre-image, so `workflow complete`, `workflow next`, direct completion APIs, worker delegation, spoofed actor names, and replacement attributes cannot bypass queue ownership. Land grants exact runtime/run/gate-scoped authority only around its own grant, release, and safe withdrawal writes. Other workflow gates retain their ordinary completion contract.

The standalone `review` workflow and the normal `land` path share the same small review procedure. It validates the pushed HEAD through the quality contract, starts exactly one `:agent` gate using `harness/alias` from the `reviewer` parameter (default `"reviewer"`) and `harness/cwd` from the worktree, then stops at a coordinator `resolve-review` checkpoint. Reviewer process success supplies findings; it never implies approval. Agent review, coordinator resolution, sign-off under existing user authorization, and landing keep the card in `claimed` (in progress). `in_review` is reserved for actual human attention, including approval, blockers, or pending human decisions; it is not a later progress stage.

Choosing `accepted` requires durable coordinator evidence containing:

- `reviewer`: the seat that reviewed;
- `base` and `head`: the immutable full Git SHAs reviewed;
- `p1-p2`: `"none"` or `"resolved"`;
- `summary`: a non-blank adjudication summary.

This evidence names the prequeue range. Signoff also authorizes a conflict-free queue-time rebase followed by exact final-HEAD quality validation; material repairs require focused follow-up review.

A repository may retain a richer local review as a separate workflow. The shared root does not depend on a Harnesses implementation or prescribe a roster, but the consumer must activate one provider for `:agent` gates that understands `harness/alias`, `harness/cwd`, and `harness/prompt`.

Landing scripts are loaded from classpath resources when the namespace loads, then embedded into shell-gate requests. A changed branch cannot swap the script after the workflow is poured. Preparation rebases onto `origin/main`, validates the final pushed HEAD through the repository contract, and records that exact SHA in Git metadata. Merge requires the local, remote, pull-request, and validated-marker SHAs to match and uses `gh pr merge --match-head-commit`.

Automatic delivery workflows should build their CI shell gate with
`millhouse.land.support/pr-checks-argv`, passing an explicit policy and
the expected feature branch. `"required"` waits up to 120 seconds for GitHub's
initial check registration, then fails specifically if the rollup is still
empty. `"allow-empty"` accepts an empty rollup immediately, but only after
validating that the PR is open, ready, based on `main`, and at the exact
checked-out and pushed branch HEAD. If the rollup contains any checks, both
policies delegate pending/pass/fail handling to `gh pr checks --watch
--fail-fast`. Every registration poll revalidates the structured `gh pr view`
identity. After a successful check wait, the gate re-reads structured PR
metadata and local and pushed branch heads, requiring all three to remain at the
original frozen commit. It never interprets stderr text.

```clojure
(support/pr-checks-argv "required" branch)
(support/pr-checks-argv "allow-empty" branch)
```

Each target repository must own an executable `.millstrand/land-quality.sh`. It runs with `LAND_EXPECTED_BRANCH` and `LAND_EXPECTED_HEAD`. The generic spool does not guess a build command or silently fall back.

Cleanup validates the canonical `main` checkout, feature worktree, local branch, and remote branch against the merged PR's exact head before deleting anything. A repository that must stop owned processes may additionally commit an executable `.millstrand/land-cleanup.sh`; the cleanup script invokes that explicit hook before removing the worktree and verifies that it leaves the exact HEAD clean. No Millstrand warm-REPL behavior is hardcoded.

## Activation

Add the Land coordinate. Its root depends on the sibling Workflow and Kanban roots:

```clojure
;; deps.edn
{:deps
 {millhouse/land
  {:git/url "https://github.com/codethread/millhouse.spool.git"
   :git/sha "<immutable-sha>"
   :deps/root "spools/land"}}}
```

Activate the Workflow engine, its code/shell providers, and one consumer-chosen `:agent` gate provider before Land. In this example `:app/agent-provider` is the module key owned by the consuming workspace:

```clojure
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(let [rt (current/runtime)]
  (runtime/module! rt :workflow/engine
    {:ns 'millhouse.workflow :required? true})
  (runtime/module! rt :workflow/providers
    {:ns 'millhouse.workflow.spool
     :after [:workflow/engine]
     :required? true})
  ;; Activate the workspace's compatible :agent provider as :app/agent-provider.
  (runtime/module! rt :land
    {:ns 'millhouse.land.spool
     :after [:workflow/engine :workflow/providers :app/agent-provider]
     :required? true}))
```

The dependency coordinate makes source available but does not activate modules. Dependency changes require a Weaver generation replacement.

## Durable queue attributes

Queue entries use `kind=merge-queue-entry` and retain `land/run-id`, `queue/root`, `queue/gate`, `queue/sequence`, and `queue/queued-at`. Terminal entries add `queue/outcome`, `queue/released-at`, and, for withdrawal, `queue/withdraw-reason`. The one active `kind=merge-lock` row records `land/run-id` and `queue/entry`.

Any trusted agent may withdraw a reservation with an explicit reason. Withdrawal first freezes and quiesces shell work, then atomically closes only that run and replaces it with `land-abort`. Once an irreversible merge gate has started or has evidence of an attempt, withdrawal fails loudly and requires reconciliation; a completed merge can never be relabeled as aborted.

Pre-guard skipped gates are repaired only through `merge-queue repair`, with an actor, reason, exact graph IDs, and bounded evidence. A skipped turn can be restored only while its recorded Land merge root is active and shell quiescence proves the irreversible gate was not attempted. Its reservation and sequence are preserved; an unreserved run receives a normal new tail position. If out-of-turn preparation already completed or was in flight, repair first reconciles and acknowledges settled cancellation custody, then rewinds that reversible gate and its stale shell outcome so delayed terminal reconciliation cannot refence it and the irreversible merge remains blocked until normal grant and fresh preparation. A skipped release can settle only its own reservation and lock after the recorded prepare, merge, and pull-main gates prove success and supplied exact PR/merge/canonical-main evidence agrees. An active or retained closed root is supported. Missing roots, ownership ambiguity, mismatched IDs/evidence, and uncertain merge submission refuse loudly and leave the turn retained or fenced. Exact repeated repairs are idempotent and never release a successor's lock. See the cookbook repair runbook for evidence shapes.
