(ns millhouse.spools.auto-run-land
  "Recorded autonomous landing phases with one persistent finisher target."
  (:require [clojure.spec.alpha :as s]
            [millhouse.spools.auto-run :as auto-run]
            [millhouse.spools.land :as land]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.format.alpha :as format]))

(s/def ::params
  (s/keys :req-un [::land/card ::land/feature ::land/branch ::land/worktree]))

(defn failure-policy
  "Render the full-land workflow's stop and custody rules."
  [card]
  (format/prose
   "
     Delivery-gate, handoff or landing failures require explicit recovery.
     Leave card {card} open unless landing has already completed. Retain and
     record owned resources and any held merge reservation. Do not clear gate
     errors, retry failed gates, spawn replacements or withdraw the merge turn.
     These rules override shared Land's repair/retry guidance.
     Await executor-owned gates; never manually assert a passing result.
     Normal queue waits and await timeouts are not failures; reissue bounded waits.

     Follow the assigned blocker contract when reporting; preserve its evidence.
   " {:card card}))

(defn- instruction [text]
  (fn [params]
    (format/prose
     "
       {action}

       {failure-policy}
     " {:action (format/prose text (assoc params :auto-run-policy
                                          (:text auto-run/auto-run-workflow)))
        :failure-policy (failure-policy (:card params))})))

(defn- role [name]
  {"auto-run/role" name "auto-run/card" (fn [{:keys [card]}] card)})

(workflow/defworkflow autonomous-land
  "Review, freeze and release one independent finisher through recorded phases."
  {:entrypoints #{:call} :param-spec ::params}
  (workflow/workflow
   "Autonomous landing handoff"
   (workflow/step
    :review "Record the reviewed landing candidate" :self
    :attributes (role "worker-review")
    (instruction
     "
       This route requires existing explicit user authorization for autonomous
       landing. You own review, not merge or worktree removal. Inspect `strand
       workflow show land` and `strand prime merge-queue`. Start or reuse the
       exact shared Land run `land-auto-{card}` with card {card}, feature {card},
       branch {branch} and worktree {worktree}; never replace an existing run.

       Drive resolve-pr and mandatory basic review. Adjudicate findings and record
       accepted immutable-range review evidence. STOP at signoff BEFORE approved:
       approval starts executor-owned merge and worktree deletion. Do not approve,
       merge, remove the worktree or finish the card yourself.

       Record the Land run ID, exact PR/base/head and review disposition in a note
       on card {card}. Complete this review step only with Land still at signoff.
       The next step freezes the independent handoff; it does not repeat review.

       {auto-run-policy}
     "))
   (workflow/step
    :prepare-handoff "Freeze the worker and finisher handoff" :self
    :depends-on [:review]
    :attributes (role "worker-prepare")
    (instruction
     "
       Read card {card}'s reviewed candidate note and auto-run/workflow-run-id.
       Resolve the canonical root with `wktree root` from {worktree}; use its
       .millstrand workspace explicitly for every subsequent strand command.
       Inspect the delivery root with `strand subgraph ROOT_ID`. Locate its unique
       auto-run/role=finisher strand for card {card}: this is the persistent
       FINISHER TARGET, not one of the finisher's phase steps.

       Require auto-run/run-id on the card names YOUR current Harnesses run.
       Inspect your agent target: it must be the card or a worker target, never the
       finisher. A stale current-worker receipt is an actionable stop BEFORE
       freezing or launching. Ask the authorized coordinator to use `strand
       auto-run register-worker --help` for an accepted continuation; do not edit
       the receipt yourself. The operation checks settled predecessor, accepted
       lineage, unchanged task/root ownership and unfrozen handoff. It does not
       supply recovery authorization. Old poured workflows are not migrated.

       Stop owned servers/browser sessions where practical. Record on card {card}
       the exact reviewed PR/head, Land and delivery run IDs, worker/release step
       ID, finisher target ID, current worker run ID, canonical root, branch,
       worktree and exact owned PIDs/session names/scratch paths (or none).
       Never guess resource ownership.

       Freeze auto-run/worker-run-id and auto-run/canonical-root on the finisher
       target. Store auto-run/finisher-request there: the exact grunt alias,
       canonical cwd, target, request ID auto-land-finisher/FINISHER_TARGET_ID and
       complete prompt. The prompt includes this handoff and the target's COMPLETE
       stored workflow/instruction, not this worker instruction. Read the target
       with strand show; custom attributes are not projected by workflow ready.
       Complete only after this durable request and handoff can be read back.
       If any frozen receipt already exists, reconcile it explicitly; never vary
       a payload after an uncertain acceptance.
     "))
   (workflow/step
    :accept-finisher "Accept and record the independent finisher" :self
    :depends-on [:prepare-handoff]
    :attributes (role "worker-accept")
    (instruction
     "
       Read card {card}'s handoff and the finisher target's frozen request. Verify
       card auto-run/run-id still equals its frozen worker ID and YOUR current run.
       Launch exactly that request through `strand agent run grunt`, with
       --by-identity YOUR_IDENTITY, --cwd CANONICAL_ROOT, --target FINISHER_TARGET_ID,
       --request-id auto-land-finisher/FINISHER_TARGET_ID and the stored --prompt
       as one argument. Never use a synchronous subagent or agent assign.

       This different target is intentionally blocked until worker release.
       After uncertain acceptance, inspect `strand agent show --request` with the
       SAME key and verify exact target/cwd/prompt. Reuse only the same request;
       never replace it, vary its payload or launch a second finisher.

       Store the accepted run ID as auto-run/finisher-run-id on the finisher
       target and in the card handoff note. Read back both run receipts and the
       accepted immutable request before completing this acceptance step. A crash
       between acceptance and receipt storage requires reconciliation of this
       exact request, not another worker or another finisher.
     "))
   (workflow/step
    :handoff-worker "Release the accepted handoff and return" :self
    :depends-on [:accept-finisher]
    :attributes (role "handoff-worker")
    (instruction
     "
       Read card {card}'s handoff, the finisher target's worker/finisher receipts
       and its exact accepted request. Require the frozen worker is YOUR current
       run and still matches card auto-run/run-id; the finisher is a DIFFERENT
       accepted run at the canonical root, serving the separate finisher target.
       Both receipts must be durable before release.

       Complete only THIS release step with `strand workflow complete
       DELIVERY_RUN_ID --step RELEASE_STEP_ID --by-identity YOUR_IDENTITY`.
       Return immediately: do not await the finisher, approve signoff, complete
       any finisher phase, finish the card or perform further worktree operations.
       A shell cd does not change your session's persistent cwd. The finisher
       waits for your actual successful settlement before allowing signoff.

       Interrupted release is coordinator recovery: keep the accepted request and
       frozen worker ID. Only successful settlement of that exact worker permits
       reconciliation of missing receipts/release. Failed or uncertain settlement
       requires a new explicit decision, never invented success or another launch.
     "))
   ;; The anchor is intentionally ready beside the linear finisher phases. It
   ;; stays open until their verified result, so one assignment owns the entire
   ;; sequence rather than reserving a succession of ephemeral phase targets.
   (workflow/step
    :finisher "Hold finisher custody until delivery is verified" :self
    :depends-on [:handoff-worker]
    :attributes (role "finisher")
    (instruction
     "
       You are the independent canonical-root landing finisher for card {card}.
       Keep your session at the canonical root; use git -C or explicit shell cwd
       for {worktree}. Do not claim the card, implement new scope, launch another
       finisher or replace auto-run/run-id with your own run.

       THIS target is a custody anchor, NOT the next phase to complete. Read its
       worker/finisher run receipts and canonical-root attribute. Require the
       finisher receipt names YOUR run, assigned to THIS target, and the worker
       names a DIFFERENT run. Read card {card}'s durable handoff and follow the
       delivery run's ready finisher phases in order, always using explicit --step:
       settlement wait, executor verification, signoff, then landing observation.
       Keep this anchor open throughout; no additional assignment is needed.

       Close this anchor only after the observation step is closed, Land is done
       and the card is closed with outcome done. Attach the verified landing
       receipt and return a concise handover. If final bookkeeping fails after
       landing, record that failure without reopening the card or repeating merge.
       Recovery against this target is finisher-only: finish, do not delegate.

       {auto-run-policy}
     "))
   (workflow/step
    :await-worker "Await the frozen worker's settlement" :self
    :depends-on [:handoff-worker]
    :attributes (role "finisher-wait")
    (instruction
     "
       Finisher only: read card {card}'s handoff and its unique finisher custody
       target. Require auto-run/finisher-run-id there names YOUR run, and read
       auto-run/worker-run-id. Await that EXACT worker, never yourself:
       `strand --workspace WORKSPACE await --query agent-run-settled
       --param run-id=WORKER_RUN_ID --min-count 1 --timeout-secs 1800`.
       Reissue bounded waits on timeout. Terminal alone does not mean settled.

       Inspect the actual worker: require settled=true, substatus=completed and
       exit-code=0, and the card's current worker receipt still matches. Record
       the observation on the card and complete only this wait step. The following
       executor gate independently checks those facts; never assert its success.
     "))
   (workflow/gate
    :verify-worker "Verify current worker settlement and finisher custody" :code
    :depends-on [:await-worker]
    :attributes {"code/fn" "millhouse.spools.auto-run-recovery/verify-worker"
                 "code/params" (fn [{:keys [card]}] {:card card})}
    (instruction "Await executor verification of the recorded runs for card {card}."))
   (workflow/step
    :authorize-land "Authorize the exact reviewed Land run" :self
    :depends-on [:verify-worker]
    :attributes (role "finisher-signoff")
    (instruction
     "
       Finisher only: read card {card}'s handoff and the closed verification gate's
       evidence with strand subgraph. Require the current worker receipt still
       matches the frozen worker, and your run still owns the finisher anchor.
       Keep signoff pending while auto-run/agent-blocked is set.

       Verify land-auto-{card} is at signoff for the recorded PR/head, card {card},
       branch {branch} and worktree {worktree}, with accepted immutable-range basic
       review evidence. Use the EXISTING user authorization, not a human label or
       actor name as a substitute. Inspect choices and approve that Land signoff
       with the exact PR and squash message; record the authorization receipt.
       If signoff already advanced after an interrupted response, inspect its
       recorded choice and exact revision rather than approving or merging again.
       A mismatch requires explicit recovery. Complete only this authorization
       step once the exact approved Land continuation is established.
     "))
   (workflow/step
    :observe-land "Verify landing, cleanup and the final card outcome" :self
    :depends-on [:authorize-land]
    :attributes (role "finisher-observe")
    (instruction
     "
       Finisher only: read card {card}'s handoff and continue ONLY land-auto-{card}.
       Its existing gates own FIFO, final-head validation, merge, main update and
       cleanup. Await them; never manually assert success. Keep reservations and
       uncertain resources on failure. At tidy-resources clean only the recorded
       owned inventory and record anything retained. Verify cleanup before closing
       tidy-resources; Land's finish-card gate owns card completion.

       Verify Land is done and card {card} is closed with outcome done. Record the
       merged revision, cleanup result and retained resources on the card and
       complete THIS observation step with its evidence. Then close the same
       finisher custody anchor, not a new agent target. Bookkeeping failure after
       successful landing must not reopen landed work or cause a duplicate merge.
       Never delete resources outside the shared cleanup contract.
     "))))
