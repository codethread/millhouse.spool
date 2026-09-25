
-----
# <a name="millhouse.executors.shell">millhouse.executors.shell</a>


Fulfil workflow `:shell` gates by running their command off the event thread.

  The shell executor watches workflow runs for ready gates whose waiter is
  `:shell`, reserves a durable attempt, and launches the gate's `shell/argv`
  directly (no implicit shell) through Mill-owned process custody. It closes the
  gate through `millhouse.workflow/complete!` on a zero exit. A non-zero
  exit, timeout, spawn error, or invalid argv stamps a loud, distinct
  `gate/error` and leaves the gate ready and stamped rather than masquerading as
  a completed run. Terminal custody facts are committed to the matching attempt
  before acknowledgement, and module-owned reconciliation repairs the in-flight
  view after Weaver replacement. It is an agent-executor sibling minus
  everything harness-run-specific: the failure detail lives on the gate itself, so
  there is no separate run strand, no `delegates` edge, and no session/harness
  vocabulary. Request validation and the durable coordinator surfaces are
  described on the public executor and query Vars below.




## <a name="millhouse.executors.shell/*runtime*">`*runtime*`</a>




Runtime captured for asynchronous shell-executor worker threads.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/shell.clj#L63-L65">Source</a></sub></p>

## <a name="millhouse.executors.shell/apply-shell-attempts!">`apply-shell-attempts!`</a>
``` clojure
(apply-shell-attempts! {:keys [runtime], :as context})
```
Function.

Reconcile durable shell attempts with retained Mill custody facts.

  Missing, stale, and mismatched facts are owner-local errors. They are returned
  in the reconcile summary and, when a gate still exists, stamped on that gate;
  the reconciler does not invent a replacement attempt or acknowledge evidence
  it cannot correlate.

  Deferred admission schedules one runtime-owned retry reader, stops at worker
  shutdown, and never relaunches processes.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/shell.clj#L1279-L1345">Source</a></sub></p>

## <a name="millhouse.executors.shell/close-shell-handler!">`close-shell-handler!`</a>
``` clojure
(close-shell-handler! ctx)
```
Function.

Unregister shell scanning when the module is removed.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/shell.clj#L1473-L1480">Source</a></sub></p>

## <a name="millhouse.executors.shell/close-shell-pool!">`close-shell-pool!`</a>
``` clojure
(close-shell-pool! ctx)
```
Function.

Close the runtime-lifetime shell worker pool.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/shell.clj#L1450-L1457">Source</a></sub></p>

## <a name="millhouse.executors.shell/non-blank-string?">`non-blank-string?`</a>
``` clojure
(non-blank-string? value)
```
Function.

Return true when `value` is a non-blank string.

  The shell request spec uses this predicate for the optional `shell/cwd`
  attribute.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/shell.clj#L124-L130">Source</a></sub></p>

## <a name="millhouse.executors.shell/on-event">`on-event`</a>
``` clojure
(on-event _event)
```
Function.

Weaver event handler: graph changes may make a `:shell` gate ready.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/shell.clj#L764-L767">Source</a></sub></p>

## <a name="millhouse.executors.shell/open-shell-handler!">`open-shell-handler!`</a>
``` clojure
(open-shell-handler! ctx)
```
Function.

Register shell scanning and run the initial scan.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/shell.clj#L1459-L1471">Source</a></sub></p>

## <a name="millhouse.executors.shell/open-shell-pool!">`open-shell-pool!`</a>
``` clojure
(open-shell-pool! ctx)
```
Function.

Open the runtime-lifetime shell worker pool.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/shell.clj#L1440-L1448">Source</a></sub></p>

## <a name="millhouse.executors.shell/quiesce-run!">`quiesce-run!`</a>
``` clojure
(quiesce-run! run-id reason)
```
Function.

Freeze and stop all active shell gates belonging to `run-id`.

  Return `{:run-id id :gates [...]}`. Each gate reports `:gate-id` and
  `:attempted?`, true when Mill custody confirms a launch, including recovery
  through its owner-scoped attempt key. A prevented launch clears its claim.

  Persist `reason` as each gate's `gate/error`. Serialize against claims,
  launches and terminal acknowledgement until cancellation settles, waiting up
  to ten seconds per process. Uncertain or unsettled cancellation throws and
  retains custody and the frozen gates for explicit reconciliation. Settled
  facts remain for normal executor acknowledgement. Clear the frozen errors
  only when deliberately resuming the run.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/shell.clj#L852-L884">Source</a></sub></p>

## <a name="millhouse.executors.shell/read-shell-attempts">`read-shell-attempts`</a>
``` clojure
(read-shell-attempts {:keys [runtime]})
```
Function.

Return durable shell attempts owned by this spool.

  Closed gates remain in this view until their custody handle is acknowledged;
  that lets a later reconciliation finish an interrupted terminal commit without
  replaying the shell command.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/shell.clj#L1100-L1117">Source</a></sub></p>

## <a name="millhouse.executors.shell/read-shell-custody">`read-shell-custody`</a>
``` clojure
(read-shell-custody {:keys [runtime]})
```
Function.

Return custody records, or an explicit deferred result when Mill custody is
  temporarily unavailable during Weaver replacement.

  Other listing failures remain loud: an empty durable-attempt set is not a
  reason to reinterpret an unavailable custody read as a successful empty
  listing.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/shell.clj#L1139-L1160">Source</a></sub></p>

## <a name="millhouse.executors.shell/remove-shell-attempts!">`remove-shell-attempts!`</a>
``` clojure
(remove-shell-attempts! _context)
```
Function.

Report removal of the shell reconciliation effect without guessing cleanup.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/shell.clj#L1347-L1350">Source</a></sub></p>

## <a name="millhouse.executors.shell/retire-quiesced-attempts!">`retire-quiesced-attempts!`</a>
``` clojure
(retire-quiesced-attempts! runtime {:keys [run-id gates]})
```
Function.

Reconcile settled custody attempts before a caller removes quiescence fences.

  Accept only the exact plans returned by `quiesce-run!`. A still-current
  attempt must remain frozen and have a terminal custody fact; normal terminal
  reconciliation then records the frozen outcome, acknowledges Mill custody,
  and clears that exact attempt identity. An observer that already performed
  those steps is an idempotent success. Mismatch or unsettled custody fails
  loudly and leaves the gate fenced.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/shell.clj#L886-L958">Source</a></sub></p>

## <a name="millhouse.executors.shell/retry-validation!">`retry-validation!`</a>
``` clojure
(retry-validation! request)
```
Function.

Reserve exactly one explicitly requested attempt on a failed opted-in gate.

  Lock order is shell scan monitor then workflow run guard, matching terminal
  completion. The registered precommit hook compares gate and root pre-images
  inside the batch transaction. No queue hook is bypassed. A known settled
  terminal receipt permits progress when acknowledgement bookkeeping is unknown;
  the uncertainty stays in the action, never becomes confirmed acknowledgement.
  Dry-run reads only. Repeat the same request key to reconcile an ambiguous result.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/shell.clj#L1019-L1078">Source</a></sub></p>

## <a name="millhouse.executors.shell/scan!">`scan!`</a>
``` clojure
(scan!)
```
Function.

Dispatch every ready `:shell` gate whose nearest workflow root is active.

  Readiness is selected once at the storage boundary. Gates with no nearest
  workflow root (orphans), or with a closed or replaced nearest root, are
  omitted. An active nearest root with a malformed `workflow/run-id` fails
  loudly with gate/root context. Root ownership is then checked only for those
  selected gates, so an unrelated graph event does not project the global ready
  frontier once per active workflow. Before claiming, dispatch revalidates each
  gate's current state, fence, claim, and dependency readiness so a stale
  selection cannot launch work made unready by a concurrent repair. The scan
  still serializes on a runtime-owned monitor so concurrent scans cannot
  double-launch a gate. Each accepted gate receives a `shell/running` claim
  before its process is submitted
  to the worker pool; the event thread never waits for the child. Scans run on
  relevant graph changes and once during handler activation.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/shell.clj#L732-L762">Source</a></sub></p>

## <a name="millhouse.executors.shell/shell-attempts">`shell-attempts`</a>




Reconcile Mill-owned shell attempts with durable workflow gates.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/shell.clj#L1352-L1358">Source</a></sub></p>

## <a name="millhouse.executors.shell/shell-handler">`shell-handler`</a>




Own the shell event handler for the lifetime of the module.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/shell.clj#L1488-L1492">Source</a></sub></p>

## <a name="millhouse.executors.shell/shell-pool">`shell-pool`</a>




Own the shell worker pool for the lifetime of the runtime.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/shell.clj#L1482-L1486">Source</a></sub></p>

## <a name="millhouse.executors.shell/shell-stalled?">`shell-stalled?`</a>
``` clojure
(shell-stalled? gate-view)
```
Function.

Return durable stall detail for a ready `:shell` gate view, or nil.

  The executor accepts a gate with `workflow/gate` equal to `"shell"` and a
  request matching `::request`: `shell/argv` is a non-empty sequential value of
  strings, while `shell/cwd` and `shell/timeout-secs` are optional non-blank and
  positive-integer values. The command is passed directly to `ProcessBuilder`,
  so shell syntax must be explicit in the argv, for example:

  ```clojure
  (workflow/gate :verify "Run tests" :shell
                 :attributes {"shell/argv" ["clojure" "-M:test"]
                              "shell/cwd" "/workspace/app"
                              "shell/timeout-secs" 600})
  ```

  A zero exit closes the gate through `workflow/complete!` with `:executor "shell"`
  and records `shell/exit-code` plus the bounded 16 KiB combined stdout/stderr
  tail in `shell/output`. A non-zero exit, timeout, spawn error, or invalid
  request leaves the gate ready with `gate/error`; process failures also record
  the exit code and output. Mill owns the process tree and terminal fact. Invalid
  requests spawn no process. The executor skips a gate while `gate/error`,
  `shell/running`, or an unacknowledged `shell/custody-handle` is present.

  For a stalled ready gate this function returns
  `{:gate gate-id :error detail}`. Remove the `gate/error` attribute (and any
  stale `shell/running` claim after a crash) to re-arm the next scan; a blank
  string is still present data.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/shell.clj#L1360-L1394">Source</a></sub></p>

## <a name="millhouse.executors.shell/stalled-shell-gates">`stalled-shell-gates`</a>




Return active shell gates carrying a durable error stamp.

  The query is the persistence-side companion to `shell-stalled?`:

  ```clojure
  (weaver/list-query runtime 'stalled-shell-gates {})
  ```

  Recovery removes the error key rather than replacing it with a blank string;
  a trusted nil patch re-arms a ready gate for the next event-driven scan:

  ```clojure
  (weaver/update! runtime gate-id
                  {:attributes {"gate/error" nil
                                "shell/running" nil}})
  ```

  Rewrite request attributes in the same update when fixing the underlying
  command or working directory.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/shell.clj#L1396-L1419">Source</a></sub></p>

## <a name="millhouse.executors.shell/timeout-wake">`timeout-wake`</a>
``` clojure
(timeout-wake {:keys [runtime payload]})
```
Function.

Cancel a shell attempt when its durable absolute timeout is due.

  The wake is deliberately keyed by attempt identity. A terminal commit before
  its deadline cancels it; a stale delivery re-reads the gate and therefore
  cannot cancel a newer attempt that reused the workflow gate.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/shell.clj#L1119-L1137">Source</a></sub></p>
