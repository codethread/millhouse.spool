
-----
# <a name="millhouse.executors.code">millhouse.executors.code</a>


Code adapter for the shared Workflow execution lifecycle.

  Trusted callbacks occupy one of eight zero-queue workers until they actually
  return. Interruption requests stop, not settlement. Nil succeeds; non-JSON
  results fail. Lost local handles are unknown and never authorize a new launch.




## <a name="millhouse.executors.code/acknowledge!">`acknowledge!`</a>
``` clojure
(acknowledge! rt {:keys [attempt-id]})
```
Function.

Forget positively settled local evidence after the common result is durable.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/code.clj#L108-L117">Source</a></sub></p>

## <a name="millhouse.executors.code/close-code-engine!">`close-code-engine!`</a>
``` clojure
(close-code-engine! {:keys [runtime resource]})
```
Function.

Persist stop intent before interrupting workers; retain unconfirmed handles.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/code.clj#L145-L150">Source</a></sub></p>

## <a name="millhouse.executors.code/code-engine">`code-engine`</a>




Select the common Code lifecycle and own its eight invocation workers.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/code.clj#L152-L155">Source</a></sub></p>

## <a name="millhouse.executors.code/executor">`executor`</a>




Inert Code descriptor. Select code-engine to activate this driver.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/code.clj#L119-L124">Source</a></sub></p>

## <a name="millhouse.executors.code/observe!">`observe!`</a>
``` clojure
(observe! rt {:keys [attempt-id]})
```
Function.

Observe the exact local invocation; absence is never positive settlement.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/code.clj#L46-L52">Source</a></sub></p>

## <a name="millhouse.executors.code/open-code-engine!">`open-code-engine!`</a>
``` clojure
(open-code-engine! {:keys [runtime]})
```
Function.

Open the bounded backend and select its common lifecycle descriptor.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/code.clj#L126-L143">Source</a></sub></p>

## <a name="millhouse.executors.code/request">`request`</a>
``` clojure
(request {:keys [gate]})
```
Function.

Project the captured gate image without rereading live graph inputs.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/code.clj#L24-L29">Source</a></sub></p>

## <a name="millhouse.executors.code/start!">`start!`</a>
``` clojure
(start! rt {:keys [attempt-id], :as context})
```
Function.

Offer one invocation to the eight-worker pool; explicit busy means no acceptance.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/code.clj#L80-L97">Source</a></sub></p>

## <a name="millhouse.executors.code/stop!">`stop!`</a>
``` clojure
(stop! rt {:keys [attempt-id], :as context})
```
Function.

Interrupt this exact worker without claiming that the callable has settled.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/workflow/src/millhouse/executors/code.clj#L99-L106">Source</a></sub></p>
