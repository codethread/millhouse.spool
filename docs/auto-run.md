# Automatic feature delivery

Millhouse uses Codethread's shared dispatcher to pick up graph-ready pending
features carrying the `auto-run` label. Epics and refinement cards are not
eligible, and existing dependencies must close before dispatch.

## Repository policy

`.millstrand/me/auto_run.clj` owns admission: one worker, a
15-second scan interval, `wktree` preparation, and Sol/low with
`auto-human-review` by default. The only allowed workflows are:

- `auto-human-review`: implement, run `make quality`, publish a ready PR, wait
  for CI, move the card to `in_review` for human attention, and stop at an explicit human checkpoint.
- `auto-full-land`: perform the same preparation, then call shared
  `millhouse.auto-run-land/autonomous-land`. Its worker phases record
  review, frozen handoff, accepted finisher and release separately. One independent
  canonical-root grunt serves a **separate finisher custody target** across its
  settlement wait, executor verification, signoff and landing-observation phases.
  The custody target stays open throughout; phases never create new assignments.
  The grunt owns
  FIFO merge, cleanup, and card completion. The card stays `claimed` (in progress)
  during agent review and active landing work; no human-review lane transition
  is needed. `in_review` means a recorded action requires the user. Queue waiting uses
  `pending`; advancing the workflow does not require human review.

Per-card overrides use `auto-run/seat`, `auto-run/effort`, and
`auto-run/workflow`. For example:

```clojure
{:auto-run/seat "astra"
 :auto-run/effort "low"
 :auto-run/workflow "auto-full-land"}
```

Inspect admission with `strand auto-run status` and request an immediate scan
with `strand auto-run scan`. A durable `assigned` receipt is not a live-agent
status; inspect its exact Harnesses run separately.

## Quality lock ownership

Both automatic delivery and shared Land invoke `.millstrand/land-quality.sh`.
That script owns `/tmp/millstrand-test.lock` through `scripts/with-test-lock.sh`.
The helper makes at most ten `flock -w 180` acquisition attempts, reporting each
wait, then runs `make quality` once while retaining the acquired descriptor.
Do not wrap the contract in another lock or probe/release the lock before calling
it: a successful probe cannot reserve capacity for the real executor. The gate uses
Git changes against the merge-base with `main` to select affected tests and
independent package gates; static/docs checks remain repository-wide. Inspect
with `make test-plan`, override with `TEST_BASE=feature/parent`, or request all
suites with `TEST_FULL=1` (`make quality-full` outside the contract). These Make
overrides can also be passed to the contract script. CI uses the same selector.
Direct full suite commands still need the shared lock. Focused tests remain exempt.
Acquisition conflicts retry inside the same shell attempt; they do not require
worker replacement or Oracle approval. A started check is never retried by this
helper, even if it returns the conflict exit code. After the ten attempts are
exhausted, the gate fails with exit 75 and an explicit command-never-started
message. Inspect the holder and preserve that evidence before further recovery;
do not kill a healthy holder or clear an executor gate implicitly.

The test lock is separate from Land's FIFO merge reservation. A failed FIFO head
retains its turn while its owner repairs and revalidates the candidate. Improving
test-lock acquisition does not clear that failure or authorize queue withdrawal.

## Ownership and failure policy

The assigned worker claims the supplied card and drives the dispatcher-created
workflow. Human-review workers never approve their own checkpoint. Full-land
workers never approve shared-land sign-off themselves: they record the exact
PR/head, run IDs, branch/worktree, and owned resources, then accept the tracked
finisher against the step marked `auto-run/role=finisher`, never the worker's own
step. Record `auto-run/worker-run-id` and `auto-run/finisher-run-id` on that target
before completing the worker step. The accepted finisher is blocked until that
release; it then waits for the frozen current worker to settle successfully.
The code gate independently checks actual settled/completed/exit-zero evidence,
current-worker agreement and the accepted canonical-root finisher target. The
worker returns immediately and never completes any finisher phase. The finisher
uses explicit --step selectors because its custody anchor remains ready beside
its current phase; it closes that anchor last, after verified landing.

Before review or finisher acceptance, workers diagnose failed quality and CI
checks and repair defects caused by their changes or covered by the assigned
scope without human approval. This includes flaky tests introduced by the change
or explicitly assigned for repair. Keep the card `claimed` during active repair.
Record the failing commit, gate, exit/output and cause evidence before retrying.
Commit and push the repair, update the PR, and rerun `.millstrand/land-quality.sh`
on the repaired revision before retrying CI; previous quality evidence does not
cover a changed HEAD. The executor must verify the new check result.

Unrelated existing flakes, infrastructure outages, unavailable credentials and
uncertain causes remain blockers. Follow the shared
[agent blocker contract](processes/auto-run.md#agent-blocker-contract), preserving
the diagnosis and exact requested intervention. Do not rerun unchanged failures
hoping for green, weaken assertions or raise production limits to hide failure.

Only retry the existing pre-review validation gate after its failed shell attempt
is terminal and its running/attempt/custody attributes are absent. An opted-in
`validation/recipe` gate uses `workflow retry-validation`; never bypass a refusal.
For an ordinary shell gate, a JSON null patch removes only `gate/error` after
recording the failure. Never complete executor gates manually or repour the run.

After handoff, the independent finisher owns scoped rebase conflicts and defects
caused by the candidate. Record the exact gate, failed commit and output, repair
the cause, obtain focused review for material changes, push, then retry the same
gate after subprocess settlement and removal of live shell custody. Use
`workflow retry-validation` for an opted-in recipe; otherwise remove only
`gate/error`. The executor validates the final HEAD. Keep the FIFO reservation.
These repairs require neither another approval nor a replacement worker.

Escalate to the recovery coordinator only for uncertain subprocess or merge
settlement, mismatched custody/receipts, unknown resource ownership, failures
outside the assigned scope, or an exhausted retry budget. Retain the run,
reservation and resources and record the exact required action. Ask the user
only for a scope or authorization decision the coordinator cannot make.
Never withdraw a possibly submitted merge or replace an existing workflow.

A failed FIFO predecessor remains that predecessor's recovery responsibility.
Downstream finishers record the dependency, notify its owner once, and keep
awaiting their existing Land runs; they do not publish their own blocker or end
custody solely because the queue head failed. Use `pending` while solely waiting
on another card and restore `claimed` when this run progresses. Re-read the
frontier after each wait: executors can merge and remove the worktree while the
finisher waits. Queue release is not delivery completion. The finisher must
await Land's automatic cleanup and `finish-card` gates, verify the closed/done
card, and close the delivery observation and custody anchor before returning.

Before stopping for a failure owned by this delivery, record its remaining
action, owner, and evidence. Publish on the feature card: `auto-run-needs-decision`
sets `in_review` for a specific user question; `auto-run-unknown-failure` sets
`pending` for operator recovery. Each pattern updates lane, evidence, and labels
atomically.

When recovering, clear resolved blockers while retaining their evidence and
verify an accepted owner will finish both Land and the enclosing delivery run.

Supplemental reviewer work needs a separate active review task. Resuming an
agent whose original review gate is closed retains that target and cannot
launch; do not reopen the completed gate or replace the workflow to recover it.

## Recovery coordinator procedure

1. Inspect with `agent show`, `show`, `workflow ready`, and `merge-queue status`.
   Do not use `workflow next`, `complete`, or `choose` to inspect state.
2. Re-read the current PR head/checks and workflow frontier. Clear only blockers
   whose recorded cause is resolved. Preserve their evidence. A receipt pointing
   to a settled run is historical evidence, not proof of active work.
3. Keep one recovery intervention active per epic. Record the card, owner, active
   intervention task, next action, and existing workflow/run IDs before deferring
   recovery. The coordinator follows that task after the current intervention
   settles; removing an intake label does not assign recovery.
4. Before resuming, verify the exact predecessor is settled, its native session
   is usable, and its retained target is open. Reuse that native lineage and the
   original workflow. For a closed review target, create a separate active
   review task and pass the exact revision and required review lens to it.
5. Create an intervention task separately from its evidence note. Read only the
   creation result's `task.id`, verify its active state, then launch against it.
   Reconcile an accepted but unstarted request before creating a replacement.
6. For a pre-PR79 lock-only failure, use the updated quality contract on the
   rebased candidate. Record the old gate and terminal subprocess evidence, then
   retry that gate once. The helper owns all ten acquisition attempts. Do not
   probe/release the lock, run duplicate full suites, or seek repeated approvals.
   If the helper exhausts its budget, record command-never-started and escalate
   once to the recovery owner. A started suite failure requires diagnosis.
7. Register an accepted worker continuation before freezing handoff. Read back
   the current worker receipt and require acknowledgment from the running child.
   `ready/pending` publication alone does not prove execution. Preserve original
   request IDs and historical receipts; do not overwrite a frozen finisher.
8. Include the current recovery rules in every resumed agent's prompt. Frozen
   guidance and already-poured topology remain unchanged. For an older Land run,
   complete its existing manual resource-tidy step after verifying owned cleanup;
   then verify Land/card completion and close delivery observation and custody.

## Authorized recovery

Inspect the target's role **before** launching a continuation. A recovery worker
serves the card or `handoff-worker` step, not the finisher step. A run assigned to
the finisher step must be the independent canonical-root finisher and must never
launch another finisher. Do not rewrite the delivery-worker receipt to name it.

Before handoff freezes, an authorized coordinator may accept a worker
continuation after prior workers settle, then register it with the supported
operation (inspect its live help first):

```nu
strand auto-run register-worker --card CARD_ID --worker ACCEPTED_RUN_ID --expected-current-worker PREDECESSOR_ID --reason 'Authorized recovery reason' --by-identity COORDINATOR_ID
```

The operation verifies a unique published continuation path from the recorded
worker to the accepted head, settlement of every predecessor (including cancelled
intermediate workers), unchanged task/logical root/worktree ownership, the expected current
receipt and absence of a frozen or accepted finisher. It records the reason and
actor with `auto-run/run-id` in one card update. Exact request replay is a no-op;
a changed request is not a replay. It does not launch or claim anything, clear
blockers, or grant recovery authorization. It requires Harnesses' public
`call-with-run-publication-lock` boundary to serialize validation and registration
with run acceptance. This is not protection against arbitrary raw graph edits.
The workspace and Auto-run library select the local Harnesses package providing
that API at the same Millhouse revision. Production activation remains a separate coordinator-owned action.
The worker must verify the reconciled receipt before accepting a finisher; a
stale receipt is an actionable stop, not permission to await the earlier worker. Harnesses requests and their lineage remain immutable.

After an interrupted handoff, inspect the exact immutable
`auto-land-finisher/FINISHER_STEP_ID` request before doing anything else. A run may
already be accepted but blocked because the worker did not record its receipt or
close its step. Retain it for explicit reconciliation; do not replace it or vary
the key/payload. Do not start a new worker or rewrite the accepted finisher's
frozen worker ID. After successful settlement of the recorded worker, a coordinator
can reconcile the exact request, missing receipts and worker-step completion.
Failed or uncertain settlement needs a new explicit recovery decision and must
not be relabeled as success.

Existing single-step and two-step workflow runs keep their poured instructions
and topology after refresh.
Do not replace or repour them. For an explicitly authorized recovery already at
reviewed sign-off, an independent canonical-root finisher may serve that old
handoff step directly after successful worker settlement and exact review/PR
verification. Its complete prompt must supersede the old worker instructions:
**finish, do not delegate again**. Record the exact request, receipts and retained
resources. Otherwise stop with an actionable request for coordinator intervention.

## Activation and verification

Run the disposable workspace test from `.millstrand` with `clojure -M:test` and
run repository `make quality`. The test activates the real init and policy in an
in-memory Weaver without labeling a card or launching a paid agent. It reproduces
the combined-step recovery collision using real Harnesses publication, then
verifies separate target acceptance and the shared quality entry point. Library
integration tests drive the new ready phases with one persistent target, verify
actual successful worker settlement, reject stale or foreign recovery lineage,
and exercise accepted-finisher recovery before receipts exist, exact request
idempotency and immutable-payload conflicts. It does not simulate successful external review or GitHub merging.

Changing the loaded dependency basis requires separate operator-authorized
activation. Never restart a running Weaver without explicit user sign-off. Source-only policy edits can use normal module refresh. Restart only
this repository's Weaver, never unrelated Weavers, then verify `strand auto-run
status`, `strand workflow show auto-human-review`, and `strand workflow show
auto-full-land` before opting in production work.
