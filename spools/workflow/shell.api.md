
-----
# <a name="millhouse.executors.shell">millhouse.executors.shell</a>


Shell adapter for the common Workflow execution lifecycle.

  Mill owns processes and retained terminal evidence. Stable owner/key adoption
  never relaunches missing custody; only the common driver delivers and retries.




## <a name="millhouse.executors.shell/acknowledge!">`acknowledge!`</a>
``` clojure
(acknowledge! rt context)
```
Function.

Acknowledge exact retained terminal evidence after durable common delivery.

  Missing evidence cannot confirm an acknowledgement whose response was lost.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/shell.clj#L142-L152">Source</a></sub></p>

## <a name="millhouse.executors.shell/close-shell-engine!">`close-shell-engine!`</a>
``` clojure
(close-shell-engine! {:keys [runtime resource], :as context})
```
Function.

Remove admission, preserving Mill-owned commands on planned Weaver shutdown.

  Module removal records stop intent instead; it never guesses settlement.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/shell.clj#L179-L184">Source</a></sub></p>

## <a name="millhouse.executors.shell/executor">`executor`</a>




Inert Shell descriptor; select shell-engine to activate common execution.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/shell.clj#L154-L159">Source</a></sub></p>

## <a name="millhouse.executors.shell/observe!">`observe!`</a>
``` clojure
(observe! rt {:keys [reference], :as context})
```
Function.

Adopt only the exact owner/key, including a lost launch response.

  Missing custody, including a new Mill lifetime, is unknown, not permission
  to launch. Transport failures propagate to the common unknown observation.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/shell.clj#L123-L131">Source</a></sub></p>

## <a name="millhouse.executors.shell/open-shell-engine!">`open-shell-engine!`</a>
``` clojure
(open-shell-engine! {:keys [runtime]})
```
Function.

Select Shell after refusing unresolved legacy execution evidence.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/shell.clj#L166-L177">Source</a></sub></p>

## <a name="millhouse.executors.shell/request">`request`</a>
``` clojure
(request {:keys [gate]})
```
Function.

Project argv, cwd and timeout from the complete captured gate image.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/shell.clj#L26-L31">Source</a></sub></p>

## <a name="millhouse.executors.shell/retry-validation!">`retry-validation!`</a>
``` clojure
(retry-validation! request)
```
Function.

Delegate the existing validation retry entrypoint to the common attempt path.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/shell.clj#L161-L164">Source</a></sub></p>

## <a name="millhouse.executors.shell/shell-engine">`shell-engine`</a>




Select the common Shell driver; Mill retains process custody across shutdown.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/shell.clj#L186-L189">Source</a></sub></p>

## <a name="millhouse.executors.shell/start!">`start!`</a>
``` clojure
(start! rt {:keys [attempt-id request]})
```
Function.

Launch shell-free argv using the stable attempt key and Mill owner.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/shell.clj#L114-L121">Source</a></sub></p>

## <a name="millhouse.executors.shell/stop!">`stop!`</a>
``` clojure
(stop! rt {:keys [reference], :as context})
```
Function.

Request cancellation of the exact owned handle, then observe settlement.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/shell.clj#L133-L140">Source</a></sub></p>
