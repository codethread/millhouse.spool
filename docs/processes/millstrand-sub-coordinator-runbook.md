# Sol preparation → Luna coordination

The parent owns the conversation, shared decisions and mentoring. Sol prepares bounded work; one headed Luna coordinator executes the prepared Kanban DAG. The [launch briefs](coordinator-handoff.md) apply this provider-neutral contract.

## Prepare before handing off

Sol turns each direct request into a feature with acceptance outcomes and tasks. Use an epic only to group genuinely related features, including cross-repository work; do not create an epic merely because a launch uses another harness. Keep source features in their owning repositories and record workspace-qualified supplier/receiver milestones. Do not invent cross-world dependency edges or make independent supplier publication depend on the entire receiving epic.

Reconcile stale cards against actual source, reviews, Land and ownership before execution. Reuse existing tasks and sole writers. Put dependencies before the specific source or acceptance slice they protect, not every independent task. Record ambiguous diagnosis/design or substantial cleanup as a bounded Sol/Oracle preparation task. A blocked preparation slice does not block independent prepared work. Luna selects ready work, delegates, awaits, inspects evidence, routes understood repair and executes acceptance/landing; it is not the default source writer or backlog investigator. New ambiguity returns to tracked preparation or Oracle direction without silently expanding the coordinator role.

## Establish custody and acknowledge the handoff

Read repository instructions, live `strand help`, `strand prime kanban`, the feature/task DAG, latest notes, current runs, workflows and actual source custody. Launch the coordinator in the assigned repository's **canonical root**, never a feature worktree or an alternative disposable coordination checkout. Keep that process CWD throughout Land. Record separately:

- canonical coordinator CWD and coordination `.millstrand` workspace;
- each source feature, branch/worktree, sole writer/current run and cleanup owner;
- any retained worker driver CWD, session and Git common-directory dependency.

One coordinator and at most one source writer own each slice/worktree. Current owner is the latest explicit claim/handoff, including an unresolved friendly identity. Reporter, mutation actor, worker, running session and historical participants are separate roles; none silently steals ownership, and reporter plus ordered participation history survive handoff. Adopt healthy serving runs rather than duplicating them. Never change another writer's files, index, flags, source custody or workflow/queue state.

The successor acknowledges exclusive scope on its coordinator task with actual identity, tracked request/run, native session/thread, real goal ID/state (or provider-visible goal evidence where no ID is exposed), canonical CWD and a verified discoverable terminal. Distinguish managed session from native thread when they differ. The planner verifies those facts and the ordinary owner/current run pointers before releasing custody or closing its preparation/handoff task. Publication, a visible TUI, a failed start, silence or a proposed handoff is not acknowledgement. Preserve predecessors; never manufacture goal or settlement rows.

Kanban is the **sole completion record**, not a separate checklist or ledger. Required source, review, repair, acceptance, cleanup and external handoff outcomes belong in existing cards/tasks. Keep the coordinator's own next actions under its coordination feature. Each meaningful transition gets a latest task note naming current decision/evidence, owner/run, blocker and concrete next action or wake condition. Parent-card notes stay lean; keep workspace and predecessor coordinates so a cold-start successor can continue from Kanban alone.

Never stop or restart Mill. Never restart or replace a running Weaver without explicit user sign-off. Never use deprecated `agent-harness.spool`; maintained harness source work belongs in Millhouse `spools/harnesses`. Stop only an identified run or PID, never a broad process-name kill. Never edit or push `main`. Use disposable, explicit workspaces for workspace-backed tests, never the shared world.

## Select owned work, then dispatch

Discover live queries with `strand query list` and `strand query explain kanban-identity-work`. Use the exact owner recorded on the assigned card, not a guessed worker identity:

```text
strand list --query kanban-identity-work --param identity=<recorded-owner> --state active
strand ready --query kanban-identity-work --param identity=<recorded-owner>
```

The query includes directly owned cards/tasks, tasks under owned cards and parent-epic context. Ready means dependency-eligible, not permission to dispatch. Stay inside the explicit assignment and inspect existing owners/current runs first. Empty ready is not completion: inspect active owned work, blockers and serving runs. Start narrow with a compact projection; `--limit` is a safety cap, not pagination. Narrow a cap error or make one intentional bounded larger read.

Delegate only through tracked Strand, never native/built-in helpers. A task under an already claimed feature uses `agent run --target TASK` and its supported direct/inherited ownership path, never a feature-claim template. Use `agent assign` only for an assignable open feature. Assignment never claims or hands off a target. For mutations, use canonical `--by-identity` actor attribution; reserve `--owner` for the explicit owner in a claim/handoff. Give each child an active, dependency-ready target, bounded scope, explicit source worktree, sole writer and stable request ID. Writers implement directly without recursively delegating; another coordination layer requires explicit parent authorization. Repeat the Mill prohibition and any direct-only review constraint in the actual invocation.

Pass rich prose as one structured argument, payload or raw-file value. JSON is not shell escaping. Verify stored content if delivery/quoting is uncertain. A publication timeout requires `agent show --request REQUEST` and actual run inspection before an equivalent retry with the same key. Preserve full returned IDs. A `ready` run alone proves neither target eligibility, invocation attempt, process custody nor the current run pointer. Never create a second writer to work around uncertain delivery.

## Await evidence, not elapsed time

Use bounded event-aware `strand await` queries, or `workflow await` for a workflow frontier. Apply the harness-specific launch timing policy and adequate client and outer-tool deadlines; act immediately on a positive event. After a result or timeout, query owned work and inspect the relevant changed task/run plus the coordinator task's latest note. Reissue as needed, not full-history reads or tight polling. A timeout alone is not failure; healthy long validation need not produce frequent notes. Notes are durable evidence, not a guaranteed live steering channel.

| Query/observation | What it proves; next action |
| --- | --- |
| `agent-run-terminal --min-count 1` | Terminal run, not successful work; inspect semantic result and target. |
| `agent-run-settled --min-count 1` | Positive settlement; still verify result, resources and eligibility before continuation. |
| `agent-run-active --max-count 0` | Absence, not successful completion. |
| `agent-work-complete --min-count 1` | Accepted assignment completion; missing IDs cannot satisfy positive evidence. |
| `agent-work-complete-or-intervention` | Completion or work needing intervention; inspect which. |
| Healthy executor-owned workflow gate | Let it run; read its real error/custody, not only static instructions. |

With stop-on-complete, await the run, inspect the result and accept the target; waiting only for an open target can wait for your own next action. Terminal, settlement and acceptance are distinct. Never fabricate a callback, process exit, past review or gate pause. Withholding an await does not pause an executor.

## Ask Oracle early; repair in the same lineage

A coordinator may request a tracked bounded Oracle direction task **within its assignment without parent preapproval**. Ask when material contract/design, review interpretation, ownership or repair direction needs judgment, or an attempt produces no explanatory progress; do not wait for a fixed retry count. An ordinary timeout or healthy validation is not an escalation trigger.

Give Oracle a precise decision question, current card contract, immutable candidate, relevant error/evidence, attempted remedies and options/constraints. Use a distinct eligible target, not the coordinator's reserved target. Preserve the declared Oracle role and any direct-only requirement in the actual prompt and appended instructions; do not silently substitute another role. Record the answer and resulting task/dependency or continuation in Kanban.

For an understood in-contract defect, record the finding and candidate, keep open or reopen the same unfinished source milestone, and delegate a bounded repair to its sole writer. Verify **positive prior settlement**, eligible target, native session and actual retained CWD/resources before resume or custody transfer. Advance the ordinary current run pointer, retaining predecessor/request lineage. A prompt cannot retarget frozen session settings; new scope or a different target needs an appropriate separately tracked assignment. Repaired candidates need fresh required quality/review through the existing workflow, not just exit zero.

Oracle direction does not expand permissions or waive gates. Escalate to the parent for product/scope or cross-repository priority decisions, missing permission, unavailable required Oracle, unresolved conflicting ownership or a blocker requiring an external decision. State the specific decision and keep unrelated eligible work moving.

## Accept, land, clean, then finish

Record distinct facts: implementation SHA, immutable reviewed SHA, quality command/result and required marker, pushed PR head, merged commit, source cleanup, consumer dependency pickup and loaded runtime activation. Review/quality must cover the actual candidate; reuse applicable exact-candidate acceptance rather than duplicate broad reviews. Verify material findings at their concrete contract boundary. A source-only candidate is not reviewed, landed or activated work.

Inspect the installed shared Land graph and drive every required gate and strict FIFO turn. Repair failed gates through supported paths, never bypass them. **Omit Land's optional `card` parameter while required cleanup or external handoffs remain.** Its finish-card cascade can close children as unactioned; that is not evidence their outcomes were fulfilled. Close each task only with its own evidence, then finish the feature yourself once its contract is satisfied.

Before checkout deletion verify cleanup ownership, active runs, clean pushed state, canonical ancestry, retained artifacts and dependent worktrees/common Git directory. Never remove the canonical coordinator root. Superseded PR closure, if required, is its own Kanban outcome, not implied by replacement landing.

Source landing does not authorize runtime activation. Pins, resolved dependencies, loaded code/commands and actual user entry-path acceptance are separate outcomes. The authorized runtime owner receives exact source and next pickup/verification requirements. If a tool loads directly from a checkout, advancing it can itself be activation. Finish the goal only after every required owned outcome is accepted or an explicit handoff is acknowledged. A list of active children is not a handoff.

## Cold-start recovery

Read the assigned card and latest coordinator/child notes, then the owner-scoped active/ready view. Verify current ownership and run pointers, predecessor settlement, open targets, actual coordinator/driver/source CWDs, sole writers, exact candidate/review/quality evidence and workflow frontier in its recorded workspace. Adopt healthy runs and preserve failed/settled history. Do not replay dispatch or cleanup from stale notes, infer success from closed children, or resume into a deleted CWD. Where evidence conflicts, record a bounded direction task rather than inventing chronology. The latest handoff note must name the pending gate/blocker, acknowledged owner, retained resources and exact next action or wake condition; otherwise report the concrete external action still required.
