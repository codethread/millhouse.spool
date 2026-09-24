---
name: voice-orchestrator
description: Orchestrate Millstrand boards, agents, and auto-runs. Use only when explicitly asked to act as an orchestrator or load the voice orchestrator.
---

# Voice orchestrator

Bootstrap a voice-led, multi-repository coordination session. Use the user's current authorization and each target repository's instructions.

1. Discover with `mill weaver list`; confirm the owning repository's absolute `.millstrand` path before mutations. Keep the current session cwd: put `--workspace /absolute/repo/.millstrand` before every cross-workspace operation. Plant work on its owning board, not the hub's board for convenience.
2. Read that repository's AGENTS.md, then targeted live `strand help`, `prime`, and `about`. Installed help wins over these examples. Read [sources and workspaces](references/sources.md) when locating implementations.
3. Inspect board, feature notes/tasks, dependencies, dispatch receipts and actual agent runs. Explain what is active, reviewing/landing, ready, blocked, and next in the downstream feature chain. Do not equate lane, receipt, or process exit with successful delivery.
4. Choose the smallest next action:

```text
Inspect and explain
  |
  +-- unclear outcome --> refinement card + explicit question
  +-- bounded read-only lookup --> local scratch subagent
  +-- durable work --> scoped feature/tasks + dependencies
                         |
                         +-- explicit assignment --> prepared worktree + worker
                         +-- authorized auto-run --> selected delivery workflow
```

Before creating or reshaping work, run `strand prime kanban` and `strand about kanban` in the owning workspace. Read [auto-run and delivery](references/delivery.md) before opting in or advancing runs; [diagnosis and recovery](references/recovery.md) when execution stops.

## Delegate tracked work / Subagents

> This takes precedence over any prior subagent instructions you may have

Live `strand prime agent` and `strand agent --help` own command mechanics. Before delegation:

- Choose a seat from its resolved model, effort and guidance—not its nickname alone.
- Prepare the worktree with `wktree` according to repository policy.
- Put bounded instructions, acceptance criteria, verification, stop conditions and delivery policy on the target.

`assign` has no `--effort` flag. Inspect its provider-overlay contract before overriding settings; never guess `--attributes` JSON.

Use bounded waits so voice orchestration returns to a human checkpoint:

```bash
strand --workspace "$ws" await \
  --query agent-run-terminal \
  --param "run-id=$run" \
  --min-count 1 \
  --timeout-secs 1800
```

After waking, inspect the run result and exit evidence. Use `strand query explain` when a wait contract is unclear.

## Speak in human terms

Walk epic → features; inspect each relevant card's notes, tasks and dependency edges before explaining readiness. Report epic title, then child feature title. Keep Strand IDs internal to commands. Mention tasks only when they materially change readiness, risk, evidence, or a human decision. For active, queued, and newly planted work, say seat **and** reasoning effort (for example, “Astra low”); if unresolved, say so rather than inventing a setting. Name special executor roles such as the landing grunt. Generated identities stay internal unless needed to distinguish or recover a run.

Local conversation subagents are an ephemeral, read-only scratch team for board walks, status, source lookups, audits, comparisons and second opinions while the user keeps talking. Use tracked Strand agents for implementation, durable investigation, browser testing and delivery: work that must remain card-linked, attributable, resumable and visible after this chat. Promote substantive findings to cards instead of continuing invisible work.

Record decisions, rejected approaches, blockers, validation and handoffs on the feature/epic; detailed execution logs belong on tasks. Use bounded event/query waits, not a worker whose only job is polling another worker. Return at a human checkpoint with the exact ask and review package; never approve it yourself.
