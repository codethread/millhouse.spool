# Devflow guide: afk

Execute the approved task queue unattended, one slice at a time, until the
queue is exhausted, blocked, or a run fails.

## Artifacts

- Task strands: the feature's open queue (see the tasks guide's vocabulary)
- Plan: `devflow/feat/<feature>/<feature>.plan.md`

## Prerequisites

- proposal.md and `<feature>.plan.md` exist in the feature folder, and the signed-off task strands exist in the graph.
- The plan status is Reviewed or Active; a Draft plan routes back to plan review first.
- `strand ready --query devflow-tasks` serves runnable work for this feature, and the dependency edges contain no cycles.
- The worktree is clean, or the only dirt belongs to the single task currently being worked.

## Modes

- **Delegated** — Approve the task queue (`choose! :approved`) with choice input `:tasks` (vector of `{:id :title :body :harness}` maps — copy each map's `:body` from the task strand's body) plus `:delegate-harness` / `:delegate-cwd` / `:delegate-preamble`; devflow pours one sequential `:agent` gate per task and finishes with a human acceptance checkpoint.
- **External** — Approve without `:tasks` to keep the single run-afk-loop step, run or hand off an external loop runner, then `complete!` the step.

## Queue states

- **Exhausted** — No open task strands remain for the feature: report it and move to finish/archive instead of running the loop.
- **Blocked** — No runnable AFK task: everything ready is HITL, or every open AFK task waits on an unclosed prerequisite. Report the blocked state instead of running the loop.

## Loop contract

- Select the next runnable task from `strand ready --query devflow-tasks`, filtered to this feature's `devflow/feature`.
- A ready strand carrying `hitl=true` is never picked up: stop and surface it for human input.
- Run one slice per cycle; never run concurrent tasks in one worktree — use separate worktrees for parallelism.
- When multiple workers share the graph, record who is working a task with an `owner` attribute or a note before starting it.
- Close the task strand when the slice is finished and committed.
- Append discoveries, blockers, and follow-up notes to the plan's Developer Notes; new work becomes new task strands, wired with `depends-on` edges.
- Stop when the queue is exhausted, a task is blocked on human input, or a run fails.

## Procedures

### Prepare

1. Identify the feature; ask if ambiguous.
2. Verify proposal and plan exist and the plan is Reviewed or Active.
3. Inspect the queue: `strand list --query devflow-tasks` for shape and edges, `strand ready --query devflow-tasks` for runnable work.
4. Report exhausted or blocked queues instead of starting a loop.
5. Choose the delegated or external mode and start the loop.

## Constraints

- AFK work follows the signed-off plan and task contracts; queue-shape changes go through the tasks guide's update procedure.
- HITL tasks are never picked up by the loop; they wait for human input.
- Never edit proposal.md during the loop; discoveries go to the plan's Developer Notes and contract changes to the spec deltas.

## Validation

- Each completed slice is committed and its task strand is closed
- Blockers and discoveries are recorded in the plan's Developer Notes
- The queue state (exhausted/blocked/failed) is reported when the loop stops

## See also

tasks, finish-archive — fetch another guide with `strand devflow guidance <key>`.
