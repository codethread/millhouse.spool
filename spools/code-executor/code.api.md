
-----
# <a name="millhouse.spools.executors.code">millhouse.spools.executors.code</a>


Fulfil workflow `:code` gates by invoking trusted Clojure functions.

  The executor resolves a gate's fully qualified `code/fn` through the runtime
  spool classloader, invokes it with the poured `code/params` map on a bounded
  worker pool, and owns the gate's terminal transition. Successful non-nil
  returns are recorded as `code/result`; exceptions and timeouts stamp
  `gate/error`. Claim tokens prevent an abandoned invocation from publishing a
  late result. There is no process isolation: a resolved function runs with
  the weaver's ambient Clojure authority and owns any subprocesses it starts.




## <a name="millhouse.spools.executors.code/close-code-engine!">`close-code-engine!`</a>
``` clojure
(close-code-engine! ctx)
```
Function.

Close code executor resources and unregister its event handler.

  This lifecycle callback removes `:code/engine` and shuts down the worker and
  timeout pools owned by the matching open operation.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/code-executor/src/millhouse/spools/executors/code.clj#L136-L146">Source</a></sub></p>

## <a name="millhouse.spools.executors.code/code-engine">`code-engine`</a>




Own the code executor's event handler and worker resources.

  Opening this module resource registers the `:code` workflow executor; closing
  it unregisters graph scanning and stops both executor pools.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/code-executor/src/millhouse/spools/executors/code.clj#L148-L154">Source</a></sub></p>

## <a name="millhouse.spools.executors.code/code-stalled?">`code-stalled?`</a>
``` clojure
(code-stalled? gate-view)
```
Function.

Return durable stall detail for a ready `:code` gate view, or nil.

  A gate view is a map containing its string `:id`. The result is
  `{:gate id :error detail}` when the current gate is ready and carries
  `gate/error`; otherwise the result is nil. This predicate is the executor's
  coordinator-facing attention surface.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/code-executor/src/millhouse/spools/executors/code.clj#L84-L97">Source</a></sub></p>

## <a name="millhouse.spools.executors.code/on-event">`on-event`</a>
``` clojure
(on-event _event)
```
Function.

Scan for ready `:code` gates after a graph mutation.

  This function is registered as the `:code/engine` event handler by the
  `code-engine` lifecycle resource. The scan is also performed during resource
  opening, so durable gates that were already ready are reconciled immediately.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/code-executor/src/millhouse/spools/executors/code.clj#L75-L82">Source</a></sub></p>

## <a name="millhouse.spools.executors.code/open-code-engine!">`open-code-engine!`</a>
``` clojure
(open-code-engine! ctx)
```
Function.

Open the code executor handler and worker resources.

  This lifecycle callback registers the `:code/engine` graph handler, creates
  the bounded worker and timeout pools, scans existing ready gates, and returns
  the engine handle owned by `code-engine`.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/code-executor/src/millhouse/spools/executors/code.clj#L119-L134">Source</a></sub></p>

## <a name="millhouse.spools.executors.code/stalled-code-gates">`stalled-code-gates`</a>




Return active code gates carrying a durable `gate/error` stamp.

  Use this named query to find code gates that a coordinator can inspect and
  deliberately re-arm by removing `gate/error` after fixing the request or
  resolved function.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/code-executor/src/millhouse/spools/executors/code.clj#L99-L108">Source</a></sub></p>
