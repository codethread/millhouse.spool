
-----
# <a name="millhouse.spools.executors.shell">millhouse.spools.executors.shell</a>


Fulfil workflow `:shell` gates by running their command off the event thread.

  The shell executor watches workflow runs for ready gates whose waiter is
  `:shell`, runs the gate's `shell/argv` directly (no implicit shell) on a
  spool-owned worker pool, and closes the gate through
  `millhouse.spools.workflow/complete!` on a zero exit. A non-zero exit, timeout,
  spawn error, or invalid argv stamps a loud, distinct `gate/error` and leaves
  the gate ready and stamped rather than masquerading as a completed run. It is
  a subagent-executor sibling minus everything agent-run-specific: the failure
  detail lives on the gate itself, so there is no separate run strand, no
  `delegates` edge, and no session/harness vocabulary. Request validation and
  the durable coordinator surfaces are described on the public executor and
  query Vars below.




## <a name="millhouse.spools.executors.shell/*runtime*">`*runtime*`</a>




Runtime captured for asynchronous shell-executor worker threads.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/shell-executor/src/millhouse/spools/executors/shell.clj#L51-L53">Source</a></sub></p>

## <a name="millhouse.spools.executors.shell/close-shell-handler!">`close-shell-handler!`</a>
``` clojure
(close-shell-handler! ctx)
```
Function.

Unregister shell scanning when the module is removed.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/shell-executor/src/millhouse/spools/executors/shell.clj#L481-L488">Source</a></sub></p>

## <a name="millhouse.spools.executors.shell/close-shell-pool!">`close-shell-pool!`</a>
``` clojure
(close-shell-pool! ctx)
```
Function.

Close the runtime-lifetime shell worker pool.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/shell-executor/src/millhouse/spools/executors/shell.clj#L457-L464">Source</a></sub></p>

## <a name="millhouse.spools.executors.shell/non-blank-string?">`non-blank-string?`</a>
``` clojure
(non-blank-string? value)
```
Function.

Return true when `value` is a non-blank string.

  The shell request spec uses this predicate for the optional `shell/cwd`
  attribute.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/shell-executor/src/millhouse/spools/executors/shell.clj#L111-L117">Source</a></sub></p>

## <a name="millhouse.spools.executors.shell/on-event">`on-event`</a>
``` clojure
(on-event _event)
```
Function.

Weaver event handler: graph changes may make a `:shell` gate ready.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/shell-executor/src/millhouse/spools/executors/shell.clj#L346-L349">Source</a></sub></p>

## <a name="millhouse.spools.executors.shell/open-shell-handler!">`open-shell-handler!`</a>
``` clojure
(open-shell-handler! ctx)
```
Function.

Declare shell vocabulary, register scanning, and run the initial scan.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/shell-executor/src/millhouse/spools/executors/shell.clj#L466-L479">Source</a></sub></p>

## <a name="millhouse.spools.executors.shell/open-shell-pool!">`open-shell-pool!`</a>
``` clojure
(open-shell-pool! ctx)
```
Function.

Open the runtime-lifetime shell worker pool.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/shell-executor/src/millhouse/spools/executors/shell.clj#L447-L455">Source</a></sub></p>

## <a name="millhouse.spools.executors.shell/scan!">`scan!`</a>
``` clojure
(scan!)
```
Function.

Dispatch every ready `:shell` gate not already claimed or errored.

  Enumerates ready gates purely through the workflow surface and serializes on a
  runtime-owned monitor so concurrent scans cannot double-launch a gate. Each
  accepted gate receives a `shell/running` claim before its process is submitted
  to the worker pool; the event thread never waits for the child. Scans run on
  relevant graph changes and once during handler activation.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/shell-executor/src/millhouse/spools/executors/shell.clj#L324-L344">Source</a></sub></p>

## <a name="millhouse.spools.executors.shell/shell-handler">`shell-handler`</a>




Own the shell event handler for the lifetime of the module.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/shell-executor/src/millhouse/spools/executors/shell.clj#L496-L500">Source</a></sub></p>

## <a name="millhouse.spools.executors.shell/shell-pool">`shell-pool`</a>




Own the shell worker pool for the lifetime of the runtime.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/shell-executor/src/millhouse/spools/executors/shell.clj#L490-L494">Source</a></sub></p>

## <a name="millhouse.spools.executors.shell/shell-stalled?">`shell-stalled?`</a>
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

  A zero exit closes the gate through `workflow/complete!` with `:by "shell"`
  and records `shell/exit-code` plus the bounded 16 KiB combined stdout/stderr
  tail in `shell/output`. A non-zero exit, timeout, spawn error, or invalid
  request leaves the gate ready with `gate/error`; process failures also record
  the exit code and output. A timeout force-kills the process tree. Invalid
  requests spawn no process. The executor skips a gate while `gate/error` or
  `shell/running` is present.

  For a stalled ready gate this function returns
  `{:gate gate-id :error detail}`. Remove the `gate/error` attribute (and any
  stale `shell/running` claim after a crash) to re-arm the next scan; a blank
  string is still present data.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/shell-executor/src/millhouse/spools/executors/shell.clj#L354-L388">Source</a></sub></p>

## <a name="millhouse.spools.executors.shell/stalled-shell-gates">`stalled-shell-gates`</a>




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
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/shell-executor/src/millhouse/spools/executors/shell.clj#L390-L413">Source</a></sub></p>
