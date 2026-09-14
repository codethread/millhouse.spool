# Land cookbook

## Start a landing

Inspect the registered parameter and checkpoint contracts first:

```text
strand workflow show land
strand workflow start land-my-change --workflow land --params '{"feature":"card-or-task","branch":"feat/change","worktree":"/absolute/worktree","card":"optional-card","reviewer":"reviewer"}'
strand workflow ready land-my-change
```

Resolve the pull request and let the optional card and quality gates complete. The configured provider then runs one `:agent` review at the pushed, quality-marked HEAD. When it succeeds, inspect the review gate's `harness/result` at `resolve-review`; success alone does not approve findings. Record the coordinator's adjudication through the checkpoint:

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

Await timeout is data and never removes or moves a reservation. Repair a failed head in place and remove its `gate/error` to retry.

## Withdraw safely

```text
strand merge-queue withdraw ENTRY_ID --reason "Scope changed"
```

Withdrawal is allowed for any trusted agent; there is no timeout eviction or owner-only restriction. It stops shell work before releasing the turn. If the merge gate may already have submitted the remote merge, withdrawal refuses to guess. Reconcile the pull request and resume the retained turn instead.

## Repair a pre-guard skipped queue gate

Use repair only for corruption created before the Land completion guard was active. Do not use it as a generic retry or queue override. First collect the exact persisted root, gate, reservation, and lock IDs. Supply a non-blank actor and reason; the complete evidence object is retained on the repaired gate or entry.

For a skipped `merge-turn`, first establish that no irreversible merge attempt is possible. Repair quiesces every active shell gate before checking that invariant. It restores the exact turn and prior shell error state, preserves an existing reservation/sequence, or creates one ordinary new tail reservation when the run was never admitted. If `prepare-merge` already completed out of turn, repair reopens it and clears its stale shell outcome. If preparation was in flight, repair reconciles and acknowledges its settled cancellation while the freeze remains, then clears the retired attempt metadata during rewind; delayed terminal reconciliation is harmless. The frontier is therefore blocked at `merge-turn`; after normal grant, preparation runs again before the irreversible merge can become ready:

```text
strand merge-queue repair RUN_ID --kind skipped-turn --by OPERATOR --reason "Pre-guard turn was skipped before merge work" --evidence '{"root-id":"ROOT_ID","gate-id":"TURN_GATE_ID","irreversible-work":"not-started"}'
```

If an irreversible gate is closed, has shell outcome evidence, or had a confirmed custody attempt, repair refuses and leaves shell work fenced. Use safe withdrawal instead when the existing reservation can still be withdrawn and merge submission is impossible.

For a skipped `merge-release`, independently verify the exact pull request is merged at the recorded validated feature HEAD and that canonical `main` fast-forwarded to the exact merge commit. The persisted prepare, irreversible merge, and pull-main gates must all be closed with successful shell evidence. The entry/root and active lock/entry associations must match exactly:

```text
strand merge-queue repair RUN_ID --kind skipped-release --by OPERATOR --reason "Pre-guard release was skipped after verified merge" --evidence '{"root-id":"ROOT_ID","gate-id":"RELEASE_GATE_ID","entry-id":"ENTRY_ID","lock-id":"LOCK_ID","pr-number":42,"pr-state":"MERGED","base-branch":"main","pr-head":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","merge-commit":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","canonical-main":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}'
```

This settles only that reservation and its own lock as merged. Cleanup may already be underway, and a retained closed root is accepted without reopening or replaying housekeeping. Repeating the identical request is harmless, including after a successor acquires the lock. Any changed evidence, missing/deleted/mismatched root, duplicate reservation or lock, unsuccessful recorded gate, or merge/canonical-main mismatch refuses without partial queue settlement. Retain the fenced turn for explicit reconciliation; never infer abort or merge success from root absence.

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
