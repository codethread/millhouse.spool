# Millhouse shell executor spool

`millhouse.spools.executors.shell` fulfils workflow gates by running trusted
operating-system commands asynchronously and recording the result on the gate.
Use the [cookbook](./shell.cookbook.md) for compositions and the [API
reference](./shell.api.md) for the precise request, outcome, and coordinator
surfaces.

## 1. Activation

The shell executor depends on the workflow spool. Activate workflow first and
order the shell module after it:

```clojure
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(def runtime (current/runtime))
(runtime/module! runtime :millhouse/spools-workflow
  {:ns 'millhouse.spools.workflow
   :spools ['millhouse.spools/workflow]})
(runtime/module! runtime :millhouse/spools-shell
  {:ns 'millhouse.spools.executors.shell
   :spools ['millhouse.spools.executors/shell
            'millhouse.spools/workflow]
   :after [:millhouse/spools-workflow]})
```

Activation declares the `shell` attribute namespace, registers the workflow executor and stalled-gate query, starts the graph-change scan handler, and reconciles any Mill-owned shell attempts retained across Weaver replacement. A durable ready `:shell` gate can therefore be dispatched as soon as the module reconciles.

## 2. Authoring shell gates

Author an ordinary workflow gate with waiter `:shell`. The gate's request is
trusted workflow-definition data; pour-time parameters only supply values that
the definition explicitly interpolates.

| Attribute | Contract |
| --- | --- |
| `workflow/gate` | Must be `"shell"` to select this executor. Other waiters are ignored, even when they carry `shell/*` attributes. |
| `shell/argv` | Required command arguments. The executor invokes the vector directly, with no implicit shell; shell syntax is opt-in by putting `sh -c` (or another shell) in the vector. |
| `shell/cwd` | Optional working directory for the child process. |
| `shell/timeout-secs` | Optional positive wall-clock limit. |
| `shell/timeout-deadline` | Internal durable absolute deadline for an active attempt. |
| `shell/timeout-intent` | Internal durable timeout classification retained while Mill cancellation completes. |

The request is validated before a process starts. A valid command that exits zero closes the gate and releases dependent steps. A non-zero exit, timeout, spawn failure, or invalid request leaves the gate active and ready with a durable `gate/error`; it never masquerades as a completed workflow step. The executor captures a bounded combined output tail for process outcomes. Commands are launched through Mill-owned process custody, so a Weaver replacement does not lose the child or its terminal fact.

## 3. Recovery and coordination

Failures are deliberate stalls, not automatic retries. The shell executor skips a gate while `gate/error`, its in-flight `shell/running` claim, or an unacknowledged `shell/custody-handle` is present, so graph activity cannot repeatedly launch an expensive command. A coordinator fixes the command or environment, removes the failure stamp, and lets the next scan retry it. A custody terminal fact is committed to the matching attempt before its handle is acknowledged. Missing, stale, or mismatched facts are reported as owner-local reconciliation failures; the spool never guesses which gate they belong to.

The failure predicate and named query are the durable attention surfaces. Their
return shapes, exact clearing calls, and the distinction between an absent
attribute and blank-string data are documented with `shell-stalled?` and
`stalled-shell-gates` in the [generated API](./shell.api.md).

## 4. Millstrand state and APIs

| Surface | Identity | Consumer contract |
| --- | --- | --- |
| Executor | `shell` from `defexecutor` | Claims ready workflow gates whose waiter is `:shell`; healthy gates remain ordinary waiting work. |
| Request spec | `millhouse.spools.executors.shell/request` | Projects the required `shell/argv` and optional `shell/cwd` and `shell/timeout-secs` shape through executor discovery. |
| Attribute namespace | `shell` | Publishes command inputs and process outcome attributes. Active attempts also retain internal deadline and timeout-intent attributes for replacement reconciliation. |
| Failure state | `gate/error` on the gate | Inherited workflow failure stamp; its presence makes the ready gate a coordinator-visible stall. |
| Named query | `stalled-shell-gates` | Selects active `:shell` gates carrying `gate/error`. |
| Event handler | `shell/engine` | Scans ready gates after relevant graph changes and on activation. |
| Lifecycle resources | `shell-pool`, `shell-handler` | Own the runtime worker pool and module-scoped scan handler. |
| Lifecycle reconciliation | `shell-attempts` | Reconciles durable attempt ids with Mill custody records and acknowledges terminal facts only after the gate update. |

The complete public function and lifecycle reference is in
[`shell.api.md`](./shell.api.md).
