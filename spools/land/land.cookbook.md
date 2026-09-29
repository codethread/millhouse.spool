# Land infrastructure cookbook

## Inspect repository policy

The active `land` definition belongs to the current repository, not this spool.
Inspect it before starting a run:

```text
strand workflow show land
strand prime merge-queue
```

Repository documentation owns its parameters, review checkpoints, merge method,
and cleanup outcome. Do not copy assumptions from another workspace.

## Inspect or await the queue

```text
strand merge-queue status
strand merge-queue status ENTRY_ID
strand --timeout 60s merge-queue await ENTRY_ID --timeout-secs 40
```

Await timeout is data and never removes or moves a reservation. Repair a failed
head in place and remove its `gate/error` only after the failed executor attempt
has settled.

## Withdraw safely

```text
strand merge-queue withdraw ENTRY_ID --reason "Scope changed"
```

Withdrawal is allowed for any trusted agent; there is no timeout eviction or
owner-only restriction. It stops shell work before releasing the turn and
continues into the repository's declared `:continue` abort workflow. The landing
context plus `:reason`, with abort defaults underneath, must satisfy that
workflow's parameter spec before queue state changes. If the irreversible gate
may already have submitted the merge, withdrawal refuses to guess. Reconcile
the pull request and resume the retained turn instead.

## Repair a pre-guard skipped queue gate

Use repair only for corruption created before the completion guard was active.
Do not use it as a generic retry or queue override. Collect the exact persisted
root, gate, reservation, and lock IDs. Supply a non-blank actor and reason; the
complete evidence object is retained.

For a skipped `merge-turn`, prove no irreversible merge attempt is possible:

```text
strand merge-queue repair RUN_ID --kind skipped-turn --by-identity OPERATOR --reason "Pre-guard turn was skipped before merge work" --evidence '{"root-id":"ROOT_ID","gate-id":"TURN_GATE_ID","irreversible-work":"not-started"}'
```

Repair quiesces active shell gates, preserves an existing reservation and
sequence, and restores the frontier before repository preparation. Any closed or
attempted irreversible gate causes refusal.

For a skipped `merge-release`, independently verify the exact PR is merged at the
recorded feature HEAD and canonical `main` is the recorded merge commit:

```text
strand merge-queue repair RUN_ID --kind skipped-release --by-identity OPERATOR --reason "Pre-guard release was skipped after verified merge" --evidence '{"root-id":"ROOT_ID","gate-id":"RELEASE_GATE_ID","entry-id":"ENTRY_ID","lock-id":"LOCK_ID","pr-number":42,"pr-state":"MERGED","base-branch":"main","pr-head":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","merge-commit":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","canonical-main":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}'
```

Mismatched IDs, evidence, queue ownership, PR state, or canonical `main` refuse
without partial settlement. Exact repeated repairs are idempotent.

## Repository quality and cleanup hooks

Commit an executable quality contract:

```text
.millstrand/land-quality.sh
```

It must return zero only when `LAND_EXPECTED_HEAD` on `LAND_EXPECTED_BRANCH` is
safe to merge. The wrapper checks cleanliness and unchanged identity before and
after invocation.

Repositories with owned processes may also commit:

```text
.millstrand/land-cleanup.sh
```

Keep it idempotent and narrowly scoped to resources owned by that worktree. If
absent, no repository-specific cleanup runs.
