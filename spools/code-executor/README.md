# Millhouse code executor spool

`millhouse.spools.executors.code` fulfils ready workflow gates whose waiter is
`:code` by invoking trusted Clojure functions inside the weaver process. It
publishes the `code/*` gate vocabulary, records successful returns, and leaves
failures ready for deliberate recovery. The [cookbook](./code.cookbook.md)
shows compositions; the [generated API](./code.api.md) has the exact public
function contracts.

## 1. Activation

The code executor has its own root and depends on the workflow spool. Approve
both roots, load workflow first, and order the code executor after every module
that defines a function it may resolve:

```clojure
(runtime/module! runtime :millhouse/spools-workflow
  {:ns 'millhouse.spools.workflow
   :spools ['millhouse.spools/workflow]})
(runtime/module! runtime :my/code-functions
  {:ns 'my.code-functions
   :after [:millhouse/spools-workflow]})
(runtime/module! runtime :millhouse/spools-code
  {:ns 'millhouse.spools.executors.code
   :spools ['millhouse.spools.executors/code
            'millhouse.spools/workflow]
   :after [:my/code-functions]})
```

Activation publishes the `:code` workflow executor, the `stalled-code-gates`
query, and the `code/*` attribute namespace. It also scans durable ready gates
immediately, so existing work does not need a new graph mutation to start.

## 2. Gate request attributes

Author a normal `(workflow/gate ... :code ...)`. The request attributes are
snapshots poured with the gate; the function Var is resolved when execution
starts.

| Attribute | Required | Consumer contract |
|---|---|---|
| `workflow/gate` = `"code"` | yes | Selects this executor. Other waiters are ignored. |
| `code/fn` | yes | String spelling a fully qualified symbol naming a callable Var. Symbols, unqualified names, closures, and unresolved Vars fail loudly. |
| `code/params` | yes | JSON object passed to the function. Its keys may be strings or keywords, and all values must be JSON-safe. |
| `code/timeout-secs` | no | Positive integer wall-clock bound. Invalid values fail loudly; there is no implicit clamp. |

The executor validates the named specs `:code/fn`, `:code/params`, and
`:code/timeout-secs` before invocation. Their combined request spec,
`:millhouse.spools.executors.code/request`, is declared on the executor
registry entry, so `strand workflow executors` can project the request
contract and attribute template.

## 3. Invocation and outcomes

Code runs with the weaver's ambient Clojure authority. There is no process
isolation; a function owns every subprocess or other external resource it
starts. A normal return closes the gate through `workflow/complete!` with
`workflow/outcome-by` set to `"code"`. Non-nil returns must be JSON-safe and
are stamped as `code/result`; nil returns omit that attribute.

The executor uses a unique `code/running` claim for each accepted invocation.
Every terminal write re-reads that claim. If a timeout or coordinator recovery
has removed or replaced it, the old invocation's result is discarded.

| Attribute | Consumer contract |
|---|---|
| `code/running` | Unique claim token for one accepted invocation. |
| `code/result` | JSON-safe non-nil return value on a successful closed gate. |
| `gate/error` | Durable validation, resolution, exception, or timeout detail; its presence stalls the gate. |

## 4. Concurrency and timeout

The module owns a fixed pool of eight daemon worker threads with no task queue.
When all workers are occupied, a ready gate remains unclaimed for a later
event-driven scan. A gate is not claimed merely because it was offered to a
saturated pool.

On timeout, the executor interrupts the worker, clears its matching claim, and
stamps `gate/error`. Clojure code cannot be killed safely: long-running
functions must cooperate with interruption, for example by checking
`Thread/interrupted` in loops. A stubborn function can occupy one worker
permanently, making pool saturation visible. Interrupting a function does not
terminate child processes; code that starts one must clean it up itself.

## 5. Failure and recovery

Exceptions, invalid requests, unresolved functions, invalid results, and timeouts leave the gate active and ready with `gate/error`. An interrupted in-JVM callback is different: its matching `code/running` claim is cleared without an error stamp, leaving the gate retryable. Later scans skip a gate while that key is present. A blank error is still present data and does not re-arm the gate.

After fixing the function or request data, a coordinator removes
`gate/error`. The next scan resolves the current Var and retries the poured
request. A weaver crash can leave `code/running` without a live invocation;
removing that token re-arms the gate. Because terminal writes are claim
guarded, an invocation from the old claim cannot publish into the recovered
claim.

## 6. Millstrand state and APIs

| Surface | Identity | Consumer contract |
|---|---|---|
| Workflow executor | `code` from `workflow/defexecutor!` | Fulfils ready `:code` gates and reports `{:gate id :error detail}` for a ready gate carrying `gate/error`. |
| Named query | `stalled-code-gates` from `millstrand/defquery` | Selects active code gates carrying `gate/error` for coordinator inspection. |
| Attribute namespace | `code/*` from the `code-engine` lifecycle resource | Publishes request, claim, and result attributes listed above. |
| Event handler | `:code/engine` | Re-scans ready code gates after graph mutations and is removed when the module closes. |
| Request tooling | `:millhouse.spools.executors.code/request` on executor registration | Projects the combined request contract and copyable attribute template through workflow tooling. |
