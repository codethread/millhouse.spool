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

Await timeout is data and never removes or moves a reservation. Inspect managed
work through common execution and authorize retries explicitly. Clear
`gate/error` only for scanner-owned queue gates after repairing their cause.

## Withdraw safely

```text
strand merge-queue withdraw ENTRY_ID --reason "Scope changed" --by-identity ACTOR
```

Withdrawal is allowed for any trusted agent; there is no timeout eviction or
owner-only restriction. It retires managed work before releasing the turn and
continues into the repository's declared `:continue` abort workflow. The landing
context plus `:reason`, with abort defaults underneath, must satisfy that
workflow's parameter spec before queue state changes. If the irreversible gate
may already have submitted the merge, withdrawal refuses to guess. Reconcile
the pull request and resume the retained turn instead.

## Repair reversible preparation

Historical skipped-gate rewind belongs to old-code preflight before cutover.
Never raw-clear execution fences or reopen managed gates. For a failed reversible
preparation, inspect its settled attempt and repair the request or candidate:

```nu
strand workflow execution RUN --step GATE
strand merge-queue repair RUN --kind preparation --by-identity ACTOR --reason 'Repaired preparation' --evidence '{"root-id":"ROOT","gate-id":"GATE","expected-attempt":"TOKEN","request-id":"repair-1"}'
```

Repair quiesces and retires outside the queue lock, checks the exact reservation
and irreversible-work evidence, consumes the positive retirement receipt, and
requests one explicit retry. It preserves FIFO position. Unknown settlement
keeps the run fenced; a possibly started irreversible merge requires operator
reconciliation, not withdrawal or remote-success inference.

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
