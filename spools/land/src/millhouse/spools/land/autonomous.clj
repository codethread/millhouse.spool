(ns millhouse.spools.land.autonomous
  "Two-role handoff for consumer-owned automatic delivery workflows."
  (:require [clojure.spec.alpha :as s]
            [millhouse.spools.land :as land]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.format.alpha :as format]))

(s/def ::params
  (s/keys :req-un [::land/card ::land/feature ::land/branch ::land/worktree]))

(defn failure-policy
  "Render the cause-and-attention contract shared by autonomous workers and finishers."
  [card]
  (format/prose
   "
     Treat cause and current attention as independent signals. Add
     `auto-run-failure` to card {card} only after positive execution or validation
     evidence identifies the failed operation, concrete attempt and current
     delivery/workflow. Record the delivery/run/step, command or operation,
     attempt/custody reference, evidence, retained resources and any held merge
     reservation in an attributed card note. A label alone is not failure proof
     or recovery authority.

     For a design, scope or authority question, add `needs-decision` and set
     `auto-run/decision-question` to the exact nonblank question and
     `auto-run/decision-role` to `human` or `operator`. The role is responsibility,
     not actor identity. Record the question and context in an attributed note,
     preserving who raised it and who answers it. A question alone is not a
     failure. Both signals may coexist when a genuine failure also needs a
     decision; resolve each independently, preserving notes and history.

     On a positively evidenced failure:
     Stop and leave the card open for manual intervention. Do not clear
     gate/error, retry a failed gate, or spawn a replacement.

     Never withdraw the merge turn or claim success. These instructions override
     shared land's
     repair/retry guidance. Unknown evidence stays
     explicit and authorizes no retry, gate reset, replacement, merge action or
     recovery. Await executor-owned gates;
     never manually assert a passing result.

     Healthy queue waits, bounded await timeouts and ordinary human checkpoints
     remain normal waits. Reissue a bounded wait only when the delivery remains
     healthy; if the timeout leaves evidence unknown, stop without retrying.
     Never stop a Weaver or unrelated processes. Labeling is best-effort if the
     CLI itself fails; report that failure in your final response.
   " {:card card}))

(defn- worker-instruction [{:keys [card branch worktree]}]
  (format/prose
   "
     This card has explicit user authorisation for autonomous landing. You own
     implementation and review, not merge or worktree removal. Start or continue
     shared land run `land-auto-{card}` with card {card}, feature {card}, branch
     {branch}, and worktree {worktree}. Inspect `strand workflow show land` and
     `strand prime merge-queue`. Drive resolve-pr and the mandatory basic review,
     adjudicate its findings and record actual immutable-range review evidence.

     STOP at land's signoff checkpoint BEFORE choosing approved. Approval starts
     executor-owned merge AND worktree deletion without another worker checkpoint.
     Do not approve signoff, merge, remove the worktree or finish the card yourself.
     A shell cd does not change the persistent working directory of your session.

     Prepare an independent canonical-root grunt:

     1. Resolve the canonical root with `wktree root` from {worktree}; use its
        .millstrand workspace explicitly for every subsequent strand command.
        Read card {card} for auto-run/workflow-run-id and auto-run/run-id. Verify
        the latter is YOUR current Harnesses run; if not, stop BEFORE accepting
        a finisher and ask the coordinator to reconcile the authorized recovery
        worker receipt. Do not silently wait on the old run or rewrite it yourself.
        Inspect that delivery run's ready frontier and its root with `strand
        subgraph ROOT_ID`. Locate the separate dependent step with
        auto-run/role=finisher and auto-run/card={card}. It must be unique.
        THIS worker step and the FINISHER STEP are different targets. Inspect
        your agent run and require its target is NOT the finisher step. A recovery
        worker may serve the card or this worker step, never the finisher step.
        If a previous combined single-step run has no separate finisher step,
        stop for explicit recovery; do not invent a target or replace the workflow.
     2. Record a handoff on the card BEFORE launch: card, exact PR/head, review
        disposition, land run ID, delivery run ID, worker step ID, finisher step ID,
        original worker run ID, canonical root, branch/worktree and owned resource
        inventory (exact PIDs/session names/scratch paths, or explicitly none).
        Stop your owned servers/browser sessions first where practical.
        Never guess ownership. Record auto-run/worker-run-id on the finisher step.
     3. Build the grunt prompt from that handoff plus the COMPLETE instruction on
        the finisher step. Launch via `strand agent run grunt`, not a synchronous
        subagent, agent assign, or interactive launch. Pass --by-identity with YOUR
        supplied identity, --cwd with the canonical root, --target with the
        FINISHER STEP ID (never this worker step), --request-id
        `auto-land-finisher/FINISHER_STEP_ID`, and --prompt as one argument.
        Acceptance of this blocked target is intentional: it cannot launch until
        this worker step closes. Do not change the request ID or payload after an
        uncertain response. Inspect `agent show --request` with the same key and
        verify the exact accepted target/cwd/prompt before declaring failure.
     4. Confirm the accepted run and record its ID on the finisher step as
        auto-run/finisher-run-id using strand update, and in a card handoff note.
        Only after both worker and finisher receipts are recorded, complete THIS
        worker step with `strand workflow complete DELIVERY_RUN_ID --step
        WORKER_STEP_ID --by-identity YOUR_IDENTITY`. Return immediately without
        waiting for the grunt or performing further worktree operations. Do not complete the
        finisher step. The grunt waits for your successful settlement before signoff.

     Recovery requires explicit authorization after any failure. Before launching
     a recovery worker, inspect the target's auto-run/role: worker recovery serves
     the card or handoff-worker step; finisher recovery serves ONLY the finisher
     step from the canonical root and must never launch another finisher. Inspect
     the original immutable request before any new launch. When NO finisher was
     accepted, the coordinator may authorize a worker continuation after prior
     workers settle, record the predecessor and new run IDs with the recovery
     reason, and update the card's auto-run/run-id to the accepted CURRENT WORKER
     before this handoff proceeds. The dispatch receipt is not immutable lineage;
     the recorded Harnesses requests are. The new worker must pass the receipt
     check above before publishing a finisher. Missing reconciliation is an
     actionable stop before the finisher target is occupied.

     When a finisher WAS accepted, do not launch another worker or change its
     frozen worker ID. The coordinator must reconcile that exact request, both
     receipts and the interrupted worker-step completion. Only successful recorded
     worker settlement permits completing that handoff; failed or uncertain
     settlement requires an explicit recovery decision, never invented success.
     An accepted but blocked finisher is retained, not replaced or given another key.

     {failure-policy}
   " {:card card :branch branch :worktree worktree
      :failure-policy (failure-policy card)}))

(defn- finisher-instruction [{:keys [card branch worktree]}]
  (format/prose
   "
     You are the independent landing finisher, running from the canonical root.
     Keep your session there; use git -C or explicit shell cwd for {worktree}.
     Do not claim card {card}, implement new scope or launch another finisher.
     This step is finisher-only, including during authorized recovery. Do not
     follow worker handoff instructions or create a second run against this target.
     You serve this supplied delivery step, not a new workflow.

     Read the recorded card handoff and this step's auto-run/worker-run-id and
     auto-run/finisher-run-id. Require the latter names YOUR current run and the
     former names a DIFFERENT run. First await that ORIGINAL WORKER RUN, never
     yourself: `strand --workspace WORKSPACE await --query agent-run-settled
     --param run-id=ORIGINAL_WORKER_RUN_ID --min-count 1 --timeout-secs 1800`.
     Reissue on timeout; a stopped/terminal status alone is not proof of settlement.
     Before signoff, inspect that exact run and require settled=true, completed
     substatus and exit-code=0. Verify the card's auto-run/run-id still names that
     worker, this finisher step is ready, the card has no auto-run-failure label,
     and land-auto-{card} is still at signoff for card {card}, the recorded PR/head,
     branch {branch} and worktree {worktree}, with accepted immutable-range basic
     review evidence. Any mismatch requires the failure policy, not an invented
     retry, receipt rewrite or alternate run. A finisher is not the delivery worker:
     do not replace the card's auto-run/run-id with your own run.

     Use the existing authorization: read workflow choices and approve signoff
     with the exact PR and squash message. Drive THAT land run through its FIFO
     turn, validation, merge, main update and cleanup. Await executor-owned gates;
     never assert their success manually. At tidy-resources, clean only recorded
     owned resources, recording anything retained. Complete tidy-resources only
     after cleanup is verified; land's finish-card gate then closes the card.
     Do not finish the card early or advance it by a generic lane edit.

     Verify land is done and the card is closed with outcome done. Only then
     complete THIS finisher step with `strand workflow complete DELIVERY_RUN_ID
     --step FINISHER_STEP_ID --by-identity YOUR_IDENTITY` plus landing evidence,
     and return a concise final handover. If that last bookkeeping action fails AFTER
     the card is closed, label/note the failure but do not reopen already-landed
     work or repeat the merge. Failure before land finishes leaves the card open.
     Never delete resources outside the shared cleanup contract.

     {failure-policy}
   " {:card card :branch branch :worktree worktree
      :failure-policy (failure-policy card)}))

(workflow/defworkflow autonomous-land
  "Review and hand off to a distinct, initially blocked canonical-root finisher."
  {:entrypoints #{:call} :param-spec ::params}
  (workflow/workflow
   "Autonomous landing handoff"
   (workflow/step :handoff-worker "Review then prepare the independent landing finisher" :self
                  :attributes {"auto-run/role" "handoff-worker"
                               "auto-run/card" (fn [{:keys [card]}] card)}
                  worker-instruction)
   (workflow/step :finisher "Finish autonomous landing from the canonical root" :self
                  :depends-on [:handoff-worker]
                  :attributes {"auto-run/role" "finisher"
                               "auto-run/card" (fn [{:keys [card]}] card)}
                  finisher-instruction)))
