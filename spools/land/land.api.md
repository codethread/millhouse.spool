
-----
# <a name="millhouse.spools.land">millhouse.spools.land</a>


Reusable one-seat review and serialized landing workflow definitions.




## <a name="millhouse.spools.land/land">`land`</a>




Review and merge work through sign-off and a durable FIFO turn.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land.clj#L236-L277">Source</a></sub></p>

## <a name="millhouse.spools.land/land-abort">`land-abort`</a>




Record an aborted landing and leave the work available for follow-up.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land.clj#L155-L172">Source</a></sub></p>

## <a name="millhouse.spools.land/land-merge">`land-merge`</a>




Land approved work in FIFO order.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land.clj#L174-L234">Source</a></sub></p>

## <a name="millhouse.spools.land/review">`review`</a>




Run one configured review agent, then require coordinator P1/P2 resolution.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land.clj#L100-L153">Source</a></sub></p>

-----
# <a name="millhouse.spools.land.autonomous">millhouse.spools.land.autonomous</a>


Two-role handoff for consumer-owned automatic delivery workflows.




## <a name="millhouse.spools.land.autonomous/autonomous-land">`autonomous-land`</a>




Review and hand off to a distinct, initially blocked canonical-root finisher.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land/autonomous.clj#L150-L163">Source</a></sub></p>

## <a name="millhouse.spools.land.autonomous/failure-policy">`failure-policy`</a>
``` clojure
(failure-policy card)
```
Function.

Render the stop-on-failure contract shared by autonomous workers and finishers.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land/autonomous.clj#L11-L26">Source</a></sub></p>

-----
# <a name="millhouse.spools.land.card-actions">millhouse.spools.land.card-actions</a>


Short, repeatable kanban card updates used by landing workflows.




## <a name="millhouse.spools.land.card-actions/finish!">`finish!`</a>
``` clojure
(finish! runtime {:keys [card]})
```
Function.

Finish an optional card after housekeeping, accepting an existing done result.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land/card_actions.clj#L42-L51">Source</a></sub></p>

## <a name="millhouse.spools.land.card-actions/finish-card!">`finish-card!`</a>
``` clojure
(finish-card! params)
```
Function.

Workflow callback for `finish!` in the code executor's bound runtime.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land/card_actions.clj#L67-L70">Source</a></sub></p>

## <a name="millhouse.spools.land.card-actions/review!">`review!`</a>
``` clojure
(review! runtime {:keys [card]})
```
Function.

Move an optional card into review; an already-reviewed card is unchanged.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land/card_actions.clj#L14-L26">Source</a></sub></p>

## <a name="millhouse.spools.land.card-actions/review-card!">`review-card!`</a>
``` clojure
(review-card! params)
```
Function.

Workflow callback for `review!` in the code executor's bound runtime.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land/card_actions.clj#L57-L60">Source</a></sub></p>

## <a name="millhouse.spools.land.card-actions/rework!">`rework!`</a>
``` clojure
(rework! runtime {:keys [card]})
```
Function.

Return an optional card to claimed after abort; repeat calls are harmless.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land/card_actions.clj#L28-L40">Source</a></sub></p>

## <a name="millhouse.spools.land.card-actions/rework-card!">`rework-card!`</a>
``` clojure
(rework-card! params)
```
Function.

Workflow callback for `rework!` in the code executor's bound runtime.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land/card_actions.clj#L62-L65">Source</a></sub></p>

-----
# <a name="millhouse.spools.land.merge-queue">millhouse.spools.land.merge-queue</a>


Strict FIFO landing turns, driven by short workflow queue gates.




## <a name="millhouse.spools.land.merge-queue/await-turn">`await-turn`</a>
``` clojure
(await-turn runtime id timeout-secs)
```
Function.

Wait for a reservation to hold the turn or close; timeout preserves its place.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land/merge_queue.clj#L328-L338">Source</a></sub></p>

## <a name="millhouse.spools.land.merge-queue/close-completion-guard!">`close-completion-guard!`</a>
``` clojure
(close-completion-guard! {:keys [runtime]})
```
Function.

Remove the queue-gate completion guard after the scanner is stopped.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land/merge_queue.clj#L951-L955">Source</a></sub></p>

## <a name="millhouse.spools.land.merge-queue/close-handler!">`close-handler!`</a>
``` clojure
(close-handler! {:keys [runtime]})
```
Function.

Remove the module's queue scanner; durable reservations remain.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land/merge_queue.clj#L967-L971">Source</a></sub></p>

## <a name="millhouse.spools.land.merge-queue/grant!">`grant!`</a>
``` clojure
(grant! runtime run-id)
```
Function.

Grant the head run's turn and close its queue gate without blocking a worker.

  Failure after lock creation retains the lock and reservation for retry in
  place. A non-head run simply remains waiting.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land/merge_queue.clj#L207-L240">Source</a></sub></p>

## <a name="millhouse.spools.land.merge-queue/join!">`join!`</a>
``` clojure
(join! runtime run-id)
```
Function.

Reserve a run's FIFO position at its merge-turn gate; repeat calls retain it.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land/merge_queue.clj#L178-L197">Source</a></sub></p>

## <a name="millhouse.spools.land.merge-queue/merge-queue">`merge-queue`</a>
``` clojure
(merge-queue ctx)
```
Function.

Own strict FIFO reservations; ordinary landing progression uses workflow verbs.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land/merge_queue.clj#L870-L885">Source</a></sub></p>

## <a name="millhouse.spools.land.merge-queue/merge-release-stalled?">`merge-release-stalled?`</a>
``` clojure
(merge-release-stalled? view)
```
Function.

Release the completed merge turn automatically before housekeeping.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land/merge_queue.clj#L911-L915">Source</a></sub></p>

## <a name="millhouse.spools.land.merge-queue/merge-turn-stalled?">`merge-turn-stalled?`</a>
``` clojure
(merge-turn-stalled? view)
```
Function.

Wait for automatic FIFO admission and acquisition; failed gates expose their error.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land/merge_queue.clj#L905-L909">Source</a></sub></p>

## <a name="millhouse.spools.land.merge-queue/on-event">`on-event`</a>
``` clojure
(on-event _event)
```
Function.

Reconsider queue gates after graph mutations.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land/merge_queue.clj#L936-L939">Source</a></sub></p>

## <a name="millhouse.spools.land.merge-queue/open-completion-guard!">`open-completion-guard!`</a>
``` clojure
(open-completion-guard! {:keys [runtime]})
```
Function.

Install the queue-gate completion guard before any queue scan can run.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land/merge_queue.clj#L941-L949">Source</a></sub></p>

## <a name="millhouse.spools.land.merge-queue/open-handler!">`open-handler!`</a>
``` clojure
(open-handler! {:keys [runtime]})
```
Function.

Register queue scanning and recover pending queue gates on activation.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land/merge_queue.clj#L957-L965">Source</a></sub></p>

## <a name="millhouse.spools.land.merge-queue/queue-completion-guard">`queue-completion-guard`</a>




Protect Land queue gates before persisted work is scanned.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land/merge_queue.clj#L973-L976">Source</a></sub></p>

## <a name="millhouse.spools.land.merge-queue/queue-gate-completion-guard">`queue-gate-completion-guard`</a>
``` clojure
(queue-gate-completion-guard ctx)
```
Function.

Reject closure of Land queue gates unless the current Land operation authorized it.

  Gate kind is read only from each update's pre-image. Outcome attributes and
  actor attribution therefore cannot hide or authorize a protected close.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land/merge_queue.clj#L56-L72">Source</a></sub></p>

## <a name="millhouse.spools.land.merge-queue/queue-handler">`queue-handler`</a>




Drive durable FIFO queue gates on graph changes.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land/merge_queue.clj#L978-L982">Source</a></sub></p>

## <a name="millhouse.spools.land.merge-queue/release!">`release!`</a>
``` clojure
(release! runtime run-id)
```
Function.

Close a completed turn's reservation and lock before closing its release gate.

  The queue writes share one batch. If workflow completion fails afterwards,
  retry recognizes the closed reservation and never releases another run's lock.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land/merge_queue.clj#L242-L289">Source</a></sub></p>

## <a name="millhouse.spools.land.merge-queue/repair!">`repair!`</a>
``` clojure
(repair! runtime run-id request)
```
Function.

Repair one explicitly evidenced pre-guard skipped Land queue gate.

  Supported kinds are `:skipped-turn` before possible irreversible work and
  `:skipped-release` after exact successful merge/main evidence. Turn repair
  retires quiesced preparation custody and rewinds reversible preparation to
  restore an ownership-blocked frontier. Every request
  records actor, reason, graph ids, and evidence; mismatches fail without queue
  settlement. Repeating the exact request is idempotent.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land/merge_queue.clj#L812-L833">Source</a></sub></p>

## <a name="millhouse.spools.land.merge-queue/scan!">`scan!`</a>
``` clojure
(scan! runtime)
```
Function.

Advance ready queue gates using short serialized mutations, never a worker wait.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land/merge_queue.clj#L917-L934">Source</a></sub></p>

## <a name="millhouse.spools.land.merge-queue/status">`status`</a>
``` clojure
(status runtime)
(status runtime id)
```
Function.

Report active FIFO order or one reservation, with current workflow evidence.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land/merge_queue.clj#L311-L326">Source</a></sub></p>

## <a name="millhouse.spools.land.merge-queue/withdraw!">`withdraw!`</a>
``` clojure
(withdraw! runtime id reason)
```
Function.

Stop a named landing and atomically replace it with abort bookkeeping.

  Any trusted agent may withdraw; no owner restriction or timeout eviction.
  Shell quiescence precedes release. A started irreversible gate requires
  reconciliation instead: cancelling a local client cannot undo a remote merge.
  A failed withdrawal keeps the reservation and lock, with shell gates frozen
  for inspection. Repair and retry those gates to resume the original landing.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land/merge_queue.clj#L379-L427">Source</a></sub></p>

-----
# <a name="millhouse.spools.land.support">millhouse.spools.land.support</a>


Shared script helpers for the repo's independently loaded workflow definitions.




## <a name="millhouse.spools.land.support/canonical-worktree">`canonical-worktree`</a>
``` clojure
(canonical-worktree worktree)
```
Function.

Resolve the canonical checkout while the feature worktree still exists.

  The resulting path is frozen into cleanup gates, so their working directory
  survives removal of the feature worktree.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land/support.clj#L8-L20">Source</a></sub></p>

## <a name="millhouse.spools.land.support/card-gate">`card-gate`</a>
``` clojure
(card-gate id title dependencies callable)
```
Function.

Build a short, retryable card bookkeeping gate.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land/support.clj#L80-L87">Source</a></sub></p>

## <a name="millhouse.spools.land.support/land-cleanup-argv">`land-cleanup-argv`</a>
``` clojure
(land-cleanup-argv branch worktree pr-number)
```
Function.

Freeze cleanup and obtain its expected branch HEAD from the merged PR.

  A rebase may have changed HEAD after the continuation was poured. The merged
  PR retains that identity even when a previous cleanup removed the worktree.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land/support.clj#L68-L78">Source</a></sub></p>

## <a name="millhouse.spools.land.support/land-cleanup-script">`land-cleanup-script`</a>




Clean up the landed feature branch and worktree.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land/support.clj#L64-L66">Source</a></sub></p>

## <a name="millhouse.spools.land.support/land-merge-script">`land-merge-script`</a>




Idempotently ready and squash-merge the feature PR.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land/support.clj#L46-L48">Source</a></sub></p>

## <a name="millhouse.spools.land.support/land-pull-main-script">`land-pull-main-script`</a>




Fast-forward the canonical main checkout to origin/main.

  This stays inline as the small-script exemplar: eight lines of shell and no
  data-shaping logic do not earn a separate file.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land/support.clj#L50-L62">Source</a></sub></p>

## <a name="millhouse.spools.land.support/land-quality-gate-script">`land-quality-gate-script`</a>




POSIX script that validates and runs the target repository's quality contract.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land/support.clj#L42-L44">Source</a></sub></p>

## <a name="millhouse.spools.land.support/non-blank-string?">`non-blank-string?`</a>
``` clojure
(non-blank-string? v)
```
Function.

Return true when v is a non-blank string.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land/support.clj#L22-L25">Source</a></sub></p>

## <a name="millhouse.spools.land.support/script">`script`</a>
``` clojure
(script name)
```
Function.

Return the frozen source of a named workspace script.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land/support.clj#L27-L35">Source</a></sub></p>

## <a name="millhouse.spools.land.support/sh-gate">`sh-gate`</a>
``` clojure
(sh-gate script name & args)
```
Function.

Return shell argv that runs script with name as `$0` and args as positionals.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land/support.clj#L37-L40">Source</a></sub></p>

## <a name="millhouse.spools.land.support/shell-gate">`shell-gate`</a>
``` clojure
(shell-gate id title dependencies argv timeout instruction)
```
Function.

Build a shell gate whose request is frozen with the worktree context.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/spools/land/support.clj#L89-L97">Source</a></sub></p>
