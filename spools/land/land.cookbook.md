# Land cookbook

## Start a landing

Inspect the registered parameter and checkpoint contracts first:

```text
strand workflow show land
strand workflow start land-my-change --workflow land --params '{"feature":"card-or-task","branch":"feat/change","worktree":"/absolute/worktree","card":"optional-card","reviewer":"reviewer"}'
strand workflow ready land-my-change
```

Resolve the pull request and let the optional card and quality gates complete. The card gate resumes `claimed` (in progress), including when agent work resumes from human review; agent review and authorized landing do not use the human-attention `in_review` lane. The configured provider then runs one `:agent` review at the pushed, quality-marked HEAD. When it succeeds, inspect the review gate's `harness/result` at `resolve-review`; success alone does not approve findings. Record the coordinator's adjudication through the checkpoint:

```text
strand workflow next land-my-change --choice accepted --input '{"reviewer":"reviewer","base":"0123456789abcdef0123456789abcdef01234567","head":"89abcdef0123456789abcdef0123456789abcdef","p1-p2":"none","summary":"Reviewed immutable range; no P1/P2 findings."}'
```

The sign-off checkpoint then accepts either `approved`, with exact pull-request and squash-message input, or `abort`, with a reason. Existing user authorization to land need not be repeated. Use the standalone `review` workflow when review should finish without proceeding to sign-off.

## Inspect or await the queue

```text
strand merge-queue status
strand merge-queue status ENTRY_ID
strand --timeout 60s merge-queue await ENTRY_ID --timeout-secs 40
```

Await timeout is data and never removes or moves a reservation. Repair a failed head in place using common execution inspection and explicit retry.

## Withdraw safely

```text
strand merge-queue withdraw ENTRY_ID --reason "Scope changed" --by-identity ACTOR
```

Withdrawal is allowed for any trusted agent; there is no timeout eviction or owner-only restriction. It stops shell work before releasing the turn. If the merge gate may already have submitted the remote merge, withdrawal refuses to guess. Reconcile the pull request and resume the retained turn instead.

## Repair reversible preparation

Historical skipped-gate rewind belongs to old-code preflight before cutover.
Never raw-clear execution fences or reopen managed gates. For a failed reversible
preparation, inspect its settled attempt and repair the request/candidate first:

```nu
strand workflow execution RUN --step GATE
strand merge-queue repair RUN --kind preparation --by-identity ACTOR --reason 'Repaired preparation' --evidence '{"root-id":"ROOT","gate-id":"GATE","expected-attempt":"TOKEN","request-id":"repair-1"}'
```

Repair quiesces and retires outside the queue lock, checks the exact reservation
and irreversible-work evidence, consumes the exact positive retirement and
requests one explicit retry. It preserves FIFO position. Unknown settlement
keeps the run fenced; a possibly started irreversible merge requires operator
reconciliation, not withdrawal or remote-success inference.

## Repository quality and cleanup hooks

Commit an executable quality contract:

```text
.millstrand/land-quality.sh
```

It must return zero only when the checked-out `LAND_EXPECTED_HEAD` on `LAND_EXPECTED_BRANCH` is safe to merge. The landing wrapper checks cleanliness and unchanged identity before and after invoking it.

Repositories with owned processes may also commit this executable hook:

```text
.millstrand/land-cleanup.sh
```

The generic cleanup enters the feature worktree and invokes the hook there with the same two environment variables before worktree removal. Keep it idempotent and narrowly scoped to resources owned by that worktree. If absent, no repository-specific cleanup runs.
