# Working planner and coordinator briefs

Use the [common protocol](millstrand-sub-coordinator-runbook.md). These are copy-and-fill briefs, not a second completion record: store the filled contract and evidence on the existing Kanban feature/tasks. Prompt files are delivery artifacts, not an outcome ledger.

## Sol planner brief

> Prepare REQUEST within REPOSITORY/SCOPE for one headed Luna coordinator. Parent owns conversation, product/scope, permissions and cross-repo priorities. Read current authorization before historical hold text. Reuse existing features, tasks, accepted evidence and source lineages. Reconcile stale ownership/board state and decompose nontrivial work before handoff. Use a feature per direct request; group related features under an epic only where useful. Record exact workspace-qualified supplier/receiver milestones for cross-repo boundaries. Protect existing writers and dependent slices without blocking independent prepared work. Put ambiguous diagnosis/design in bounded Sol/Oracle tasks.
>
> Record the prepared DAG, acceptance outcomes (including required PR cleanup and external handoffs), permission exclusions, canonical coordinator CWD, source branches/worktrees and sole writers/cleanup owners separately. Inspect existing runs before any dispatch. Launch one authorized headed coordinator through tracked Strand from the repository's canonical root, never a feature worktree or disposable coordination checkout. Apply the selected harness launch policy. Verify resolved harness/model/effort, actual CWD, tracked identity/request/run, native session/thread, discoverable terminal and real goal. Do not alter flags or model settings to work around a stale live alias.
>
> Keep preparation/handoff open until the successor acknowledges exclusive scope and a real goal on its coordinator task. Verify ordinary owner/current-run pointers; preserve predecessors and source custody. Close only evidenced preparation/handoff outcomes and report the acknowledgement to the parent. Do not claim the downstream source work complete. Never stop/restart Mill, mutate unauthorized runtimes or use deprecated agent-harness.spool. All helpers must be supported tracked Strand assignments.

`sub-coordinator-sol` remains the accepted sustained **coordinator** alias; it is not silently repurposed as this planner. Select the already declared Sol planner or worker seat authorized for the assignment without incidental model changes.

## Coordinator assignment brief

> Own FEATURE / COORDINATOR_TASK in WORKSPACE. Execute only prepared TASK_DAG and acceptance outcomes in its Kanban contract. Parent is PARENT_TARGET/WORKSPACE; escalate product/scope/permissions/cross-repo priority decisions there. Permission: ALLOWED_ACTIONS. Excluded: EXCLUSIONS. Activation owner: OWNER; required activation outcome or acknowledged handoff: TASK.
>
> Stay in CANONICAL_REPO_ROOT. Source custody is SOURCE_FEATURE → BRANCH/WORKTREE → SOLE_WRITER/CURRENT_RUN/CLEANUP_OWNER; retained worker driver is DRIVER_CWD. Predecessor request/run/session: PREDECESSOR. Adopt healthy existing workers; verify settlement, open target and retained resources before any continuation.
>
> Set a real goal to deliver REQUIRED_OUTCOMES. Before planner release, acknowledge scope, actual friendly identity, tracked request/run, native session/thread, public goal ID/state, canonical CWD and TERMINAL on COORDINATOR_TASK. Verify the live owner/current-run pointers and record predecessor lineage. Query owned active/ready Kanban work using the exact recorded owner. First ready action is ACTION; blocked slice waits for PREREQUISITE/OWNER/EVENT, not the entire epic.
>
> Coordinate, do not implement by default. Use tracked Strand workers/reviewers, bounded event-aware waits with HARNESS_POLICY and adequate client deadlines, compact coordinator/child notes, early direct Oracle direction when judgment is needed, and settled same-lineage repair for understood defects. Require exact-candidate review/quality, shared Land/FIFO, cleanup and required handoffs. Do not finish while owned children/gates still need your next action. Kanban is the only completion record. Never stop/restart Mill, edit main, use deprecated agent-harness.spool, or use native/built-in delegates. Do not broaden runtime permissions from technical advice. Report accepted source separately from dependency pickup and actual activation; do not automatically enable guidance.

## Headed Codex launch example

Run from a named, retained terminal using the supported interactive `agent` bin. Discover `strand help agent`, `mill bin --help` and the installed agent bin help first. The bin owns tracked interactive publication; do not emulate callbacks. This Nushell example assumes a reviewed Codethread checkout, prepared open task under an already claimed feature, and verified live Codex alias. Values are placeholders to fill, not new authorization to launch a cohort.

```nu
let repo = "/absolute/canonical/repository"
let ws = ($repo | path join ".millstrand")
let docs = "/absolute/reviewed/codethread/docs/processes"
let actor = "actual-planner-identity"
let task = "prepared-coordinator-task"
let request = "unique-logical-handoff-request"
let prompt = (open --raw "/absolute/prepared-assignment.txt")
let policy = (open --raw ($docs | path join "coordinator-launch-codex.md"))
cd $repo
^strand --workspace $ws agent list --by-identity $actor
^strand --workspace $ws agent runs --task $task --by-identity $actor
^mill bin --workspace $ws run agent sub-coordinator --target $task --cwd $repo --by-identity $actor --request-id $request --title "Bounded repository coordination" --append-system-prompt $policy --prompt $prompt
```

Do not dispatch if another live run serves that target. `agent assign` is for an assignable feature, not this child task. Before launch follow the selected alias chain: shared Luna is Codex `gpt-5.6-luna/max`, Terra fallback remains `gpt-5.6-terra/high`, sustained Sol is `gpt-5.6-sol/high`. Selection flags and unrelated worker/reviewer aliases stay unchanged. If live aliases are stale, hand pickup to the authorized runtime owner; do not silently register, reload or use a different seat. A specifically authorized direct `codex` one-off must explicitly supply the generic runbook as well as this launch policy, model/effort and contract; it is not a proof that the shared alias was exercised.

For an explicitly authorized Pi launch, select the verified Pi seat, append [Pi launch policy](coordinator-launch-pi.md) instead, and pass an initial prompt beginning `/goal `. Do not change the shared Codex aliases or goal guards.

The alias supplies only common coordination guidance. The launcher explicitly appends the selected Codex/Pi policy file: its 180-second wait is a documented launch default, **not** an alias-enforced timer. Record any override in the actual launch and task. Verify frozen `harness/cwd`, harness/model/effort and `harness/appended-system-prompts` with raw `strand show RUN`; `agent show RUN` provides lifecycle, not that attribute map. Verify actual process/TUI CWD and public goal/session state too. A 180-second inner await uses a 210-second Strand client deadline and an outer-tool deadline of at least 240 seconds; asynchronous yield must await the same handle, not become remote polling every few seconds.

## Acknowledgement and first observation

The successor writes this on its existing coordinator task using its own actual identity. The planner reads it back and verifies the live run/goal/terminal before closing preparation or superseding its old coordinator task:

> ACK: IDENTITY accepts exclusive SCOPE on FEATURE/TASK in WORKSPACE from canonical CWD. Tracked REQUEST/RUN; managed SESSION; native THREAD/SESSION; real GOAL/STATE at PUBLIC_EVIDENCE; terminal NAME verified at CWD. Effective alias/harness/model/ effort and appended POLICY (override if any) verified from frozen launch. Source owners/worktrees and predecessor lineages remain as recorded. Next: READY_ACTION; blocked slice: OWNER/PREREQUISITE/WAKE. Parent retains EXCLUSIONS.

Compact Nushell observation after the launch (use full returned identifiers):

```nu
let owner = "exact-owner-recorded-on-feature"
let run = "full-returned-run-id"
^strand --workspace $ws list --query kanban-identity-work --param $"identity=($owner)" --state active
^strand --workspace $ws ready --query kanban-identity-work --param $"identity=($owner)"
^strand --workspace $ws --timeout 210s await --query agent-run-terminal --param $"run-id=($run)" --min-count 1 --timeout-secs 180
^strand --workspace $ws agent show $run --by-identity $actor
^strand --workspace $ws notes $task | from json | last 1
```

Handle positive events immediately. After timeout read the relevant changed worker task and coordinator note, then act or await again. Do not await your own running coordinator as its next action: `run` above is the observed child.

## Direct Oracle direction and settled repair examples

Create/reuse a **distinct**, eligible direction task with a precise decision question, contract, immutable candidate, evidence, attempted remedies and options. Inspect its serving runs before publication. In Nushell, pass rich files as single arguments; retain the returned full ID and request key:

```nu
let direction_task = "eligible-oracle-direction-task"
let question = (open --raw "/absolute/oracle-question.txt")
^strand --workspace $ws agent runs --task $direction_task --by-identity $actor
^strand --workspace $ws agent run oracle --target $direction_task --cwd $repo --by-identity $actor --request-id "unique-direction-request" --append-system-prompt "Direct-only Oracle direction. No native or built-in delegates. Never stop or restart Mill." --prompt $question
```

Record its answer and next action on the task. This direction does not replace required basic review. If the declared Oracle is unavailable, preserve the blocker and ask the parent rather than substitute another role.

For an understood defect, first record the finding and reopen only the same unfinished milestone where appropriate (`strand update TASK --state active`). Verify its sole writer, dependency eligibility, current pointer, native session and actual retained CWD. Then continue the settled lineage:

```nu
let predecessor_run = "full-settled-writer-run"
let continuation_request = "new-stable-repair-request"
let repair = (open --raw "/absolute/bounded-repair.txt")
^strand --workspace $ws --timeout 210s await --query agent-run-settled --param $"run-id=($predecessor_run)" --min-count 1 --timeout-secs 180
^strand --workspace $ws agent show $predecessor_run --by-identity $actor
```

**Only after positive settlement and the resource/target preflight**, resume:

```nu
^strand --workspace $ws agent resume --run-id $predecessor_run --by-identity $actor --request-id $continuation_request --prompt $repair
```

Equivalent retries reuse `continuation_request`; it must not be the predecessor's request key. Record the returned current run pointer while retaining predecessor history. A different frozen target requires a separately tracked assignment, not a prose retarget. Review/quality must accept the repaired candidate through Land.

## Cold-start and final source handoff

A successor starts from the assigned card, latest coordinator and relevant child notes, owner-scoped views, current serving runs and workspace-qualified workflow frontier. Verify actual source/driver paths and settlement before continuation; never replay a launch or cleanup from stale history. Preserve old failed sessions and unrelated worktrees. See the common protocol for acceptance boundaries.

A final task note names exact candidate/review/quality marker, PR/merged commit, workflow **and workspace**, FIFO result, source cleanup, retained artifacts, remaining consumer pickup/activation task and its acknowledged owner. Close source outcomes only when evidenced; external handoff acknowledgement is not activation. Parent launches later cohorts and performs only separately authorized runtime pickup. Cohort completion notifies the user that the next step is ready; it does not automatically enable native guidance.
