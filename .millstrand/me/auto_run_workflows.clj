(ns millhouse.auto-run-workflows
  "Repository-owned delivery contracts for automatically assigned Millhouse features."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.format.alpha :as format]))

(s/def ::text (s/and string? (complement str/blank?)))
(s/def ::card ::text)
(s/def ::feature ::text)
(s/def ::branch ::text)
(s/def ::worktree ::text)
(s/def ::params (s/keys :req-un [::card ::feature ::branch ::worktree]))

(defn- failure-policy [card]
  (format/prose
   "
     On an observed delivery-gate, handoff or landing failure, add the label
     `auto-run-failure` to card {card} and record the failing run/step, command,
     evidence, retained resources and any held merge reservation in a card note.
     Stop and leave the card open for manual intervention. Do not clear gate/error,
     retry a failed gate, spawn a replacement, withdraw the merge turn or claim
     success. These instructions override shared land's repair/retry guidance.
     Await executor-owned gates; never manually assert a passing result.
     Normal queue waits and await timeouts are not failures; reissue bounded waits.
     Never stop a Weaver or unrelated processes. Labeling is best-effort if the
     CLI itself fails; report that failure in your final response.
   " {:card card}))

(defn- landing-handoff [{:keys [card branch worktree]}]
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
        the latter is YOUR current Harnesses run; if not, stop for intervention.
        Read that delivery run's ready frontier to obtain this ordinary step ID.
     2. Record a handoff on the card BEFORE launch: card, exact PR/head, review
        disposition, land run ID, delivery run ID, this handoff step ID, original
        worker run ID, canonical root, branch/worktree and owned resource inventory
        (exact PIDs/session names/scratch paths, or explicitly none). Stop your
        owned servers/browser sessions first where practical. Never guess ownership.
     3. Build the grunt prompt from that handoff plus ALL the finisher instructions
        below, with actual IDs and paths substituted. Launch via `strand agent run
        grunt`, not a synchronous subagent, agent assign, or interactive launch.
        Pass --by-identity with YOUR supplied identity, --cwd with the canonical
        root, --target with THIS HANDOFF STEP ID (not card {card}, which your active
        run reserves), --request-id `auto-land-finisher/HANDOFF_STEP_ID`, and
        --prompt with the complete prompt as one argument. Do not change the request
        ID or payload to get past an uncertain response: inspect `agent show
        --request` with the same key before declaring handoff failure.
     4. Confirm the accepted run and record its ID on this step as
        auto-run/finisher-run-id using strand update, and in a card handoff note.
        Return immediately. Do not wait for the grunt, complete this delivery step,
        or perform further worktree operations. The grunt owns the remaining work.

     FINISHER INSTRUCTIONS (include verbatim in the grunt prompt):

     You are the independent landing finisher, running from the canonical root.
     Keep your session there; use git -C or explicit shell cwd for the feature
     worktree. Do not claim the feature, implement new scope or launch another
     finisher. You serve the supplied delivery handoff step, not a new workflow.

     First await the ORIGINAL WORKER RUN, never yourself:
     `strand --workspace WORKSPACE await --query agent-run-settled
     --param run-id=ORIGINAL_WORKER_RUN_ID --min-count 1 --timeout-secs 1800`.
     Reissue on timeout; a stopped/terminal status alone is not proof of settlement.
     Before signoff, inspect that exact run and require settled=true, completed
     substatus and exit-code=0. Verify the handoff step's auto-run/finisher-run-id
     names your run, the card has no auto-run-failure label, and the supplied land
     run is still at signoff for the supplied card/PR/branch/worktree. Any mismatch
     requires the failure policy below, not an invented retry or alternate run.

     Use the existing authorization: read workflow choices and approve signoff with
     the exact PR and squash message. Drive THAT land run through its FIFO turn,
     validation, merge, main update and cleanup. Await executor-owned gates; never
     assert their success manually. At tidy-resources, clean only the recorded
     owned resources, recording anything retained. Complete tidy-resources only
     after cleanup is verified; land's finish-card gate then closes the card.
     Do not finish the card early or advance it by a generic lane edit.

     Verify land is done and the card is closed with outcome done. Only then
     complete the supplied outer delivery handoff step with your identity and
     landing evidence, and return a concise final handover. If that last bookkeeping
     action fails AFTER the card is closed, label/note the failure but do not reopen
     already-landed work or repeat the merge. Failure before land finishes leaves
     the card open. Never delete resources outside the shared cleanup contract.

     FAILURE POLICY FOR BOTH WORKERS:
     {failure-policy}
   " {:card card :branch branch :worktree worktree
      :failure-policy (failure-policy card)}))

(defn- shell-gate [id title dependencies argv timeout failure-instruction]
  (workflow/gate id title :shell
    :depends-on dependencies
    :attributes {"shell/argv" argv
                 "shell/cwd" (fn [{:keys [worktree]}] worktree)
                 "shell/timeout-secs" timeout}
    failure-instruction))

(defn- delivery [autonomous?]
  (let [failure-instruction
        (if autonomous?
          (fn [{:keys [card]}] (failure-policy card))
          "Await this executor-owned gate. Inspect failures, repair the cause, then explicitly clear gate/error to retry. Never manually assert a passing result.")]
    (apply
     workflow/workflow
     (if autonomous? "Deliver automatically" "Prepare for human review")
     (concat
      [(workflow/step
        :implement "Implement and verify the assigned feature" :self
        (fn [{:keys [card]}]
          (format/prose
           "
             Read card {card}, its epic and tasks, and AGENTS.md. Claim the card
             with your provided identity, branch, worktree and Harnesses run ID.
             Work in the provided worktree; do not create a second one. Implement
             the scoped outcome yourself and record evidence on the card's tasks.
             Follow the architecture contract and preserve unrelated work.

             Use disposable fixtures for mutations; never launch paid agents or publish
             external reviews as a smoke test. Add focused regression tests when
             behavior or ownership boundaries warrant them.

             Run make quality while iterating. When implementation and focused
             verification are complete, commit your work and complete this step.
             Do not start land yet; the following steps own the review handoff.

             {failure-policy}
           " {:card card :failure-policy (if autonomous? (failure-policy card) "")})))
       (shell-gate :quality "Pass repository quality checks" [:implement]
                   ["make" "quality"] 5400 failure-instruction)
       (workflow/step
        :prepare-pr "Publish the exact change with its review package" :self
        :depends-on [:quality]
        (fn [{:keys [card branch]}]
          (format/prose
           "
             Push {branch} and create or update its PR against main. It must be
             ready for review, not a draft. The PR body must contain these exact
             nonempty Markdown sections:

             - ## Summary: outcome, scope and important decisions.
             - ## Walkthrough: explain the change with a Mermaid diagram at a
               useful C4 context/container/component level. Show the affected
               boundaries and data flow, not an exhaustive class diagram.
             - ## Verification: automated checks, reproduction or manual testing
               instructions, and limitations.

             Put the PR URL and concise handoff on card {card}; retain detailed
             evidence on its verification task. Complete this step only after
             publishing the committed revision and review package. The next gates
             independently wait for CI and verify the exact PR head and package.
           " {:card card :branch branch})))
       (shell-gate :ci "Wait for the PR checks" [:prepare-pr]
                   (fn [{:keys [branch]}]
                     ["gh" "pr" "checks" branch "--watch" "--fail-fast"])
                   2100 failure-instruction)
       (workflow/gate
        :review-card "Move the verified feature into review" :code
        :depends-on [:ci]
        :attributes {"code/fn" "millhouse.spools.land.card-actions/review-card!"
                     "code/params" (fn [{:keys [card]}] {:card card})}
        (if autonomous?
          failure-instruction
          "This is an automatic card transition after the review-package checks."))]
      (if autonomous?
        [(workflow/step
          :land "Review then hand landing to a canonical-root grunt" :self
          :depends-on [:review-card]
          landing-handoff)]
        [(workflow/checkpoint
          :human-acceptance "Human review: return the passing PR and stop"
          :depends-on [:review-card]
          :kind :human
          :choices [{:key :reviewed :label "Human review recorded"}]
          :attributes
          {"workflow/instruction"
           (format/prose
            "
              Stop here and return the PR URL, walkthrough, screenshots or their
              applicability explanation, verification evidence and open questions.
              Do not choose this checkpoint, merge, start land, finish the card,
              remove the worktree, or remain running to poll for the user.

              The user will review and decide what happens next. Generic landing
              instructions on the epic do not override this explicit stop boundary.
            " {})})])))))

(workflow/defworkflow! auto-human-review
  "Prepare a passing, documented PR and stop for the user's full review."
  {:entrypoints #{:start} :param-spec ::params}
  (delivery false))

(workflow/defworkflow! auto-full-land
  "Prepare and review the change, then hand shared landing to a canonical-root grunt."
  {:entrypoints #{:start} :param-spec ::params}
  (delivery true))
