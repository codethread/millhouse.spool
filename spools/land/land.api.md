
-----
# <a name="millhouse.land">millhouse.land</a>


Reusable one-seat review and serialized landing workflow definitions.




## <a name="millhouse.land/land">`land`</a>




Review and merge work through sign-off and a durable FIFO turn.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land.clj#L244-L292">Source</a></sub></p>

## <a name="millhouse.land/land-abort">`land-abort`</a>




Record an aborted landing and leave the work available for follow-up.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land.clj#L162-L182">Source</a></sub></p>

## <a name="millhouse.land/land-merge">`land-merge`</a>




Land approved work in FIFO order.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land.clj#L184-L242">Source</a></sub></p>

## <a name="millhouse.land/review">`review`</a>




Run one configured review agent, then require coordinator P1/P2 resolution.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land.clj#L101-L160">Source</a></sub></p>

-----
# <a name="millhouse.land.card-actions">millhouse.land.card-actions</a>


Short, repeatable kanban card updates used by landing workflows.




## <a name="millhouse.land.card-actions/finish!">`finish!`</a>
``` clojure
(finish! runtime {:keys [card]})
```
Function.

Finish an optional card after verified cleanup, resuming a pending queue waiter.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land/card_actions.clj#L43-L55">Source</a></sub></p>

## <a name="millhouse.land.card-actions/finish-card!">`finish-card!`</a>
``` clojure
(finish-card! params)
```
Function.

Workflow callback for `finish!` in the code executor's bound runtime.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land/card_actions.clj#L84-L87">Source</a></sub></p>

## <a name="millhouse.land.card-actions/pause!">`pause!`</a>
``` clojure
(pause! runtime {:keys [card]})
```
Function.

Pause an aborted delivery without claiming idle work or hiding a human question.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land/card_actions.clj#L57-L68">Source</a></sub></p>

## <a name="millhouse.land.card-actions/pause-card!">`pause-card!`</a>
``` clojure
(pause-card! params)
```
Function.

Workflow callback for `pause!` in the code executor's bound runtime.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land/card_actions.clj#L89-L92">Source</a></sub></p>

## <a name="millhouse.land.card-actions/review!">`review!`</a>
``` clojure
(review! runtime {:keys [card]})
```
Function.

Mark an optional card as needing human attention; in_review is unchanged.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land/card_actions.clj#L14-L26">Source</a></sub></p>

## <a name="millhouse.land.card-actions/review-card!">`review-card!`</a>
``` clojure
(review-card! params)
```
Function.

Workflow callback for `review!` in the code executor's bound runtime.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land/card_actions.clj#L74-L77">Source</a></sub></p>

## <a name="millhouse.land.card-actions/rework!">`rework!`</a>
``` clojure
(rework! runtime {:keys [card]})
```
Function.

Resume pending or human-review work in claimed; repeat calls are harmless.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land/card_actions.clj#L28-L41">Source</a></sub></p>

## <a name="millhouse.land.card-actions/rework-card!">`rework-card!`</a>
``` clojure
(rework-card! params)
```
Function.

Workflow callback for `rework!` in the code executor's bound runtime.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land/card_actions.clj#L79-L82">Source</a></sub></p>

-----
# <a name="millhouse.land.merge-queue">millhouse.land.merge-queue</a>


Strict FIFO landing turns, driven by short workflow queue gates.




## <a name="millhouse.land.merge-queue/await-turn">`await-turn`</a>
``` clojure
(await-turn runtime id timeout-secs)
```
Function.

Wait for a reservation to hold the turn or close; timeout preserves its place.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land/merge_queue.clj#L254-L264">Source</a></sub></p>

## <a name="millhouse.land.merge-queue/close-completion-guard!">`close-completion-guard!`</a>
``` clojure
(close-completion-guard! {:keys [runtime]})
```
Function.

Remove the queue-gate completion guard after the scanner is stopped.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land/merge_queue.clj#L458-L462">Source</a></sub></p>

## <a name="millhouse.land.merge-queue/close-handler!">`close-handler!`</a>
``` clojure
(close-handler! {:keys [runtime]})
```
Function.

Remove the module's queue scanner; durable reservations remain.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land/merge_queue.clj#L474-L478">Source</a></sub></p>

## <a name="millhouse.land.merge-queue/grant!">`grant!`</a>
``` clojure
(grant! runtime run-id)
```
Function.

Grant the FIFO head, fencing the public root image in each transaction.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land/merge_queue.clj#L170-L187">Source</a></sub></p>

## <a name="millhouse.land.merge-queue/join!">`join!`</a>
``` clojure
(join! runtime run-id)
```
Function.

Reserve an unfrozen run's FIFO position; repeats retain its reservation.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land/merge_queue.clj#L134-L150">Source</a></sub></p>

## <a name="millhouse.land.merge-queue/merge-queue">`merge-queue`</a>
``` clojure
(merge-queue ctx)
```
Function.

Own strict FIFO reservations; ordinary landing progression uses workflow verbs.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land/merge_queue.clj#L372-L386">Source</a></sub></p>

## <a name="millhouse.land.merge-queue/merge-release-stalled?">`merge-release-stalled?`</a>
``` clojure
(merge-release-stalled? view)
```
Function.

Release the completed merge turn automatically before housekeeping.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land/merge_queue.clj#L412-L416">Source</a></sub></p>

## <a name="millhouse.land.merge-queue/merge-turn-stalled?">`merge-turn-stalled?`</a>
``` clojure
(merge-turn-stalled? view)
```
Function.

Wait for automatic FIFO admission and acquisition; failed gates expose their error.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land/merge_queue.clj#L406-L410">Source</a></sub></p>

## <a name="millhouse.land.merge-queue/on-event">`on-event`</a>
``` clojure
(on-event _event)
```
Function.

Reconsider queue gates after graph mutations.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land/merge_queue.clj#L443-L446">Source</a></sub></p>

## <a name="millhouse.land.merge-queue/open-completion-guard!">`open-completion-guard!`</a>
``` clojure
(open-completion-guard! {:keys [runtime]})
```
Function.

Install the queue-gate completion guard before any queue scan can run.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land/merge_queue.clj#L448-L456">Source</a></sub></p>

## <a name="millhouse.land.merge-queue/open-handler!">`open-handler!`</a>
``` clojure
(open-handler! {:keys [runtime]})
```
Function.

Register queue scanning and recover pending queue gates on activation.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land/merge_queue.clj#L464-L472">Source</a></sub></p>

## <a name="millhouse.land.merge-queue/queue-completion-guard">`queue-completion-guard`</a>




Protect Land queue gates before persisted work is scanned.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land/merge_queue.clj#L480-L483">Source</a></sub></p>

## <a name="millhouse.land.merge-queue/queue-handler">`queue-handler`</a>




Drive durable FIFO queue gates on graph changes.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land/merge_queue.clj#L485-L489">Source</a></sub></p>

## <a name="millhouse.land.merge-queue/release!">`release!`</a>
``` clojure
(release! runtime run-id)
```
Function.

Release only this exact reservation/lock; freeze prevents further mutation.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land/merge_queue.clj#L189-L215">Source</a></sub></p>

## <a name="millhouse.land.merge-queue/repair!">`repair!`</a>
``` clojure
(repair! runtime run-id {:keys [kind by-identity reason evidence], :as request})
```
Function.

Resume and explicitly retry failed reversible preparation, retaining its turn.

  Historical skipped-gate rewind is unsupported; resolve it under old loaded
  code before cutover. This operation neither rewinds graphs nor infers merges.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land/merge_queue.clj#L335-L365">Source</a></sub></p>

## <a name="millhouse.land.merge-queue/scan!">`scan!`</a>
``` clojure
(scan! runtime)
```
Function.

Advance ready queue gates using short serialized mutations, never a worker wait.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land/merge_queue.clj#L418-L441">Source</a></sub></p>

## <a name="millhouse.land.merge-queue/status">`status`</a>
``` clojure
(status runtime)
(status runtime id)
```
Function.

Report active FIFO order or one reservation, with current workflow evidence.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land/merge_queue.clj#L237-L252">Source</a></sub></p>

## <a name="millhouse.land.merge-queue/withdraw!">`withdraw!`</a>
``` clojure
(withdraw! runtime id reason by-identity)
```
Function.

Retire before taking the queue lock, then atomically abandon into abort.

  Irreversible may-have-started evidence refuses even after local settlement.
  The final conditional batch fences root, retirement, attempts and domain rows.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land/merge_queue.clj#L284-L333">Source</a></sub></p>

-----
# <a name="millhouse.land.support">millhouse.land.support</a>


Shared script helpers for the repo's independently loaded workflow definitions.




## <a name="millhouse.land.support/canonical-worktree">`canonical-worktree`</a>
``` clojure
(canonical-worktree worktree)
```
Function.

Resolve the canonical checkout while the feature worktree still exists.

  The resulting path is frozen into cleanup gates, so their working directory
  survives removal of the feature worktree.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land/support.clj#L9-L21">Source</a></sub></p>

## <a name="millhouse.land.support/card-gate">`card-gate`</a>
``` clojure
(card-gate id title dependencies callable)
```
Function.

Build a short, retryable card bookkeeping gate.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land/support.clj#L99-L118">Source</a></sub></p>

## <a name="millhouse.land.support/land-cleanup-argv">`land-cleanup-argv`</a>
``` clojure
(land-cleanup-argv branch worktree pr-number)
```
Function.

Freeze cleanup and obtain its expected branch HEAD from the merged PR.

  A rebase may have changed HEAD after the continuation was poured. The merged
  PR retains that identity even when a previous cleanup removed the worktree.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land/support.clj#L87-L97">Source</a></sub></p>

## <a name="millhouse.land.support/land-cleanup-script">`land-cleanup-script`</a>




Clean up the landed feature branch and worktree.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land/support.clj#L83-L85">Source</a></sub></p>

## <a name="millhouse.land.support/land-merge-script">`land-merge-script`</a>




Idempotently ready and squash-merge the feature PR.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land/support.clj#L65-L67">Source</a></sub></p>

## <a name="millhouse.land.support/land-pull-main-script">`land-pull-main-script`</a>




Fast-forward the canonical main checkout to origin/main.

  This stays inline as the small-script exemplar: eight lines of shell and no
  data-shaping logic do not earn a separate file.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land/support.clj#L69-L81">Source</a></sub></p>

## <a name="millhouse.land.support/land-quality-gate-script">`land-quality-gate-script`</a>




POSIX script that validates and runs the target repository's quality contract.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land/support.clj#L43-L45">Source</a></sub></p>

## <a name="millhouse.land.support/non-blank-string?">`non-blank-string?`</a>
``` clojure
(non-blank-string? v)
```
Function.

Return true when v is a non-blank string.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land/support.clj#L23-L26">Source</a></sub></p>

## <a name="millhouse.land.support/pr-checks-argv">`pr-checks-argv`</a>
``` clojure
(pr-checks-argv policy branch)
```
Function.

Return argv for the shared PR checks gate.

  `policy` must be `"required"` or `"allow-empty"`. Both policies require an
  open, non-draft PR into `main` whose branch and head match the checked-out and
  pushed `branch`. `allow-empty` accepts a zero-length GitHub check rollup after
  those validations. Any nonempty rollup is delegated to `gh pr checks --watch
  --fail-fast`; `required` waits up to 120 seconds for initial check registration,
  then fails specifically if the rollup is still empty. After a successful
  checks wait, the gate revalidates PR, local, and pushed heads against the
  original frozen commit.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land/support.clj#L51-L63">Source</a></sub></p>

## <a name="millhouse.land.support/script">`script`</a>
``` clojure
(script name)
```
Function.

Return the frozen source of a named workspace script.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land/support.clj#L28-L36">Source</a></sub></p>

## <a name="millhouse.land.support/sh-gate">`sh-gate`</a>
``` clojure
(sh-gate script name & args)
```
Function.

Return shell argv that runs script with name as `$0` and args as positionals.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land/support.clj#L38-L41">Source</a></sub></p>

## <a name="millhouse.land.support/shell-gate">`shell-gate`</a>
``` clojure
(shell-gate id title dependencies argv timeout instruction)
```
Function.

Build a shell gate whose request is frozen with the worktree context.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/land/src/millhouse/land/support.clj#L120-L128">Source</a></sub></p>
