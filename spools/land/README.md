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

Cleanup validates the canonical `main` checkout, feature worktree, local branch, and remote branch against the merged PR's exact head before deleting anything. A repository that must stop owned processes may additionally commit an executable `.millstrand/land-cleanup.sh`; the cleanup script invokes that explicit hook before removing the worktree and verifies that it leaves the exact HEAD clean. Before sign-off, remove scratch files and stop owned processes by exact PID or session name. Record retained resources and their owners; cleanup that must wait for merge belongs in that hook. Successful cleanup automatically finishes the optional card, including a pending queue waiter. There is no post-merge agent bookkeeping gate. No Millstrand warm-REPL behavior is hardcoded.

Code and Shell gates use [managed Workflow execution](../workflow/execution.md).
Inspect `workflow execution`, then explicitly authorize a settled failed attempt
with `workflow retry`; deleting `gate/error` is not retry authority. Recipe-marked
Shell gates retain the delegated `retry-validation` entrypoint.

Queue gates (`merge-turn` and `merge-release`) remain scanner-owned. Repair the
cause, then clear only that queue gate's `gate/error` to re-arm it. Managed retry
and preparation repair do not apply to queue gates. Keep the reservation and
respect any run freeze.

Withdrawal retires managed work before acquiring the queue lock. It then fences
exact root, reservation, freeze and retirement evidence in the atomic abandonment
batch. It never claims successful execution for skipped old work. An irreversible
gate that may have started refuses withdrawal, even after local cancellation.

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

Any trusted agent may withdraw a reservation with an explicit reason. Withdrawal first freezes and retires managed work, then atomically closes only that run and replaces it with `land-abort`. Once an irreversible merge gate has started or has evidence of an attempt, withdrawal fails loudly and requires reconciliation; a completed merge can never be relabeled as aborted.

Historical skipped-turn/release rewind must be resolved under old loaded code
before cutover; it is not supported by the managed model. For new data,
`merge-queue repair --kind preparation` retains the reservation, positively
retires the exact freeze, resumes and explicitly retries the reversible gate.
It never removes raw fences, reopens gates or infers remote merge success.
See the cookbook for exact evidence and actor fields.
