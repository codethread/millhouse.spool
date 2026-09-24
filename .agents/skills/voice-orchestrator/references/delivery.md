# Auto-run and delivery

Read target AGENTS.md, delivery documentation and live `prime auto-run`, `auto-run status`, `workflow list` and `workflow show NAME`. Defaults, available workflows, capacity and authorization are repository-specific, not seat defaults. This repository's full contract is [automatic delivery](../../../../docs/auto-run.md).

Opt in only a scoped, graph-ready feature with explicit delivery authorization. Prepare overrides while still in refinement; add the label, then promote last.

```nu
strand --workspace $ws update $card --attr $'auto-run/seat=($seat)' --attr $'auto-run/effort=($effort)' --attr $'auto-run/workflow=($delivery)'
strand --workspace $ws kanban label add $card auto-run
strand --workspace $ws update $card --attr kanban/lane=pending
strand --workspace $ws auto-run status
```

Only opted-in pending graph-ready features dispatch; epics and refinement do not. `auto-run scan --by-identity ACTOR` admits work and is not a read-only inspection. Cadence normally owns scanning. `preparing` means admission has begun; `assigned` is a receipt, not live agent status. Inspect its exact Harnesses run. Removing a label prevents future admission but does not cancel accepted work.

Drive the **existing assigned run**, not a new workflow with the same intent:

```nu
strand --workspace $ws workflow ready $delivery_run
strand --workspace $ws workflow await $delivery_run --timeout-secs 1800
# Only after evidence for an ordinary worker-owned step:
strand --workspace $ws workflow complete $delivery_run --step $step --by-identity $actor
```

Read every rendered instruction. `ready` returns the whole frontier. Await healthy executor-owned gates; never manually assert their success. Timeouts are normal: reissue bounded waits (under roughly 50 minutes). Inspect checkpoint contracts with `workflow choices` before any authorized `choose`; use `workflow defer` for deferred workflow selection, not complete. A failed gate is not an ordinary step.

- **auto-human-review:** passing non-draft PR, exact head/checks and review package; leave feature/PR/worktree open at human acceptance, return, and do not approve.
- **auto-full-land (this repository):** prepare PR, drive shared land review, adjudicate findings, then stop **before sign-off**. Follow the rendered bounded canonical-root grunt prompt exactly. A shell cd does not move the parent session cwd. Target the delivery handoff step, not the already-reserved card; use the prescribed idempotency key. Record the accepted finisher receipt and return without completing the handoff step or feature. Grunt awaits successful original worker settlement, then owns sign-off, FIFO merge, cleanup and late card closure.

Before shared land, inspect `workflow show land` and `prime merge-queue`. Do not copy a stale parameter schema or bypass review/FIFO. Record PR/head, review outcome, both run IDs, handoff step, original run, canonical root, branch/worktree and exact owned resource inventory (or none). An uncertain launch response calls for `agent show --request KEY`, never a new launch key.
