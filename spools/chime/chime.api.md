
-----
# <a name="millhouse.spools.chime">millhouse.spools.chime</a>


Human-attention notification bridge for Millstrand graph events.

  Chime watches strand mutations, evaluates small userland rules, and sends
  attention notices through a workspace-bound local notifier command. It owns
  only weaver-lifetime runtime state and composes the public weaver/event API.

  Module authors normally use `defrule!` (or `defrule` plus `use-rule!`),
  `set-notifier!`, and the direct rule seam (`register!`/`unregister!`). The
  lifecycle callbacks and `engine` resource are public because the runtime
  resolves them by symbol; activation owns their registration and cleanup.




## <a name="millhouse.spools.chime/*runtime*">`*runtime*`</a>




Runtime captured for asynchronous notifier worker threads.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/chime/src/millhouse/spools/chime.clj#L147-L149">Source</a></sub></p>

## <a name="millhouse.spools.chime/close-engine!">`close-engine!`</a>
``` clojure
(close-engine! {:keys [runtime resource], :as context})
```
Function.

Close Chime's atomic engine boundary for a validated lifecycle context.

  `context` conforms to `::lifecycle-context`; its `:resource` conforms to
  `::engine-handle`, and the return value conforms to `::lifecycle-result`.

  A failed close restores the active cluster before surfacing the failure. The
  retained resource handle can therefore be retried without exposing a
  half-closed handler, barrier, or rule view.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/chime/src/millhouse/spools/chime.clj#L571-L606">Source</a></sub></p>

## <a name="millhouse.spools.chime/defrule">`defrule`</a>
``` clojure
(defrule & args)
```
Macro.

Define an inert rule declaration; return its Var.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/chime/src/millhouse/spools/chime.clj#L109-L110">Source</a></sub></p>

## <a name="millhouse.spools.chime/defrule!">`defrule!`</a>
``` clojure
(defrule! & args)
```
Macro.

Define and select a rule declaration; return its Var.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/chime/src/millhouse/spools/chime.clj#L109-L110">Source</a></sub></p>

## <a name="millhouse.spools.chime/engine">`engine`</a>




Own Chime's handler, mutation barrier, and visible rule view atomically.

  Activation applies this resource; removing it unregisters the event handler
  and mutation barrier and retracts the visible rule view.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/chime/src/millhouse/spools/chime.clj#L608-L614">Source</a></sub></p>

## <a name="millhouse.spools.chime/mutation-registration-barrier!">`mutation-registration-barrier!`</a>
``` clojure
(mutation-registration-barrier! _context)
```
Function.

Serialize a pending graph mutation after any in-progress rule registration.

  Installed as a synchronous pre-commit hook. Its return value is ignored.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/chime/src/millhouse/spools/chime.clj#L463-L470">Source</a></sub></p>

## <a name="millhouse.spools.chime/notifier">`notifier`</a>
``` clojure
(notifier)
```
Function.

Return the current notifier binding, or nil when none is bound.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/chime/src/millhouse/spools/chime.clj#L208-L211">Source</a></sub></p>

## <a name="millhouse.spools.chime/notify!">`notify!`</a>
``` clojure
(notify! notification)
```
Function.

Send one notification through the current binding.

  Returns an inspectable map immediately. Missing notifier is recorded as a loud
  failure instead of silently dropping the notification. With a bound notifier,
  the return has `:status :started`, the expanded `:argv`, and `:title`; a
  missing binding returns `:status :failed` with the recorded `:failure`.

  ```clojure
  (chime/notify! {:title "Build finished"
                  :body "All strands under the plan are closed."})
  ```
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/chime/src/millhouse/spools/chime.clj#L252-L275">Source</a></sub></p>

## <a name="millhouse.spools.chime/on-event">`on-event`</a>
``` clojure
(on-event event)
```
Function.

Weaver event handler: scan graph changes for attention notifications.

  Activation registers this handler for strand mutations, batch application,
  burning, and superseding. Consumers normally let the `engine` resource own
  that registration rather than calling this function directly.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/chime/src/millhouse/spools/chime.clj#L453-L460">Source</a></sub></p>

## <a name="millhouse.spools.chime/open-engine!">`open-engine!`</a>
``` clojure
(open-engine! {:keys [runtime], :as context})
```
Function.

Open Chime's atomic engine boundary for a validated lifecycle context.

  `context` conforms to `::lifecycle-context`; the returned handle conforms to
  `::engine-handle`.

  The handler, mutation barrier, and visible rule view change under their
  shared monitor. A failed open compensates back to the inactive boundary so a
  lifecycle retry never inherits a half-open engine.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/chime/src/millhouse/spools/chime.clj#L545-L569">Source</a></sub></p>

## <a name="millhouse.spools.chime/recent-failures">`recent-failures`</a>
``` clojure
(recent-failures)
```
Function.

Return the last 100 notifier, process, and rule failures for this weaver lifetime.

  Entries diverge from the blessed event-failure entry
  (`millstrand.api.events.alpha/recent-failures`) on two keys, because chime's
  failures carry no event context to describe them with:

  - `:kind` — `:notifier-missing`, `:process`, or `:rule`. The blessed entry has
    no counterpart; it discriminates on `:event/type`, which chime's failures do
    not have. Two of chime's three kinds are not throws at all, so the kind is
    the only thing that says what went wrong.
  - `:message` — present only when something threw, not `:exception/message`:
    a missing notifier and a non-zero notifier exit are failures without an
    exception to take a message from. Every entry also has `:failed/at`, an
    ISO-8601 timestamp for when Chime recorded it.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/chime/src/millhouse/spools/chime.clj#L164-L180">Source</a></sub></p>

## <a name="millhouse.spools.chime/register!">`register!`</a>
``` clojure
(register! name fn-symbol)
```
Function.

Register or replace a notification rule.

  `fn-symbol` names a function receiving `{:event .. :strand ..}` and returning
  nil or `{:title .. :body ..}`. The context also includes `:ready-ids`, the
  ready strand ids computed for the scan. Currently matching strands become the
  rule's initial seen baseline, so durable conditions do not notify after
  registration even when they have never notified before. Mutations serialized
  after registration notify normally.

  ```clojure
  (chime/register! :agent-failure 'my.rules/agent-failed)
  ```

  This is the direct runtime-local seam; module authors generally use
  `defrule` so owner refresh can retract declarations.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/chime/src/millhouse/spools/chime.clj#L292-L330">Source</a></sub></p>

## <a name="millhouse.spools.chime/reset-seen!">`reset-seen!`</a>
``` clojure
(reset-seen!)
```
Function.

Clear per-weaver notification deduplication and batch-scan state.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/chime/src/millhouse/spools/chime.clj#L182-L187">Source</a></sub></p>

## <a name="millhouse.spools.chime/rule-declaration">`rule-declaration`</a>
``` clojure
(rule-declaration rule-key options fn-sym)
```
Function.

Return a validated Chime rule declaration.

  `rule-key` is a keyword and `fn-sym` is a fully qualified symbol. `options`
  conforms to `::rule-options`; override intent remains collection metadata.
  Consumers normally create declarations through `defrule` and select them with
  `use-rule!`.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/chime/src/millhouse/spools/chime.clj#L67-L82">Source</a></sub></p>

## <a name="millhouse.spools.chime/rule-kind">`rule-kind`</a>




Owner-partitioned kind id for Chime notification rules.

  `use-rule!` publishes declarations under this identity; the active module
  reconciles the effective entries into Chime's visible rule view.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/chime/src/millhouse/spools/chime.clj#L38-L43">Source</a></sub></p>

## <a name="millhouse.spools.chime/rules">`rules`</a>
``` clojure
(rules)
```
Function.

Return registered notification rules ordered by key.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/chime/src/millhouse/spools/chime.clj#L332-L335">Source</a></sub></p>

## <a name="millhouse.spools.chime/scan!">`scan!`</a>
``` clojure
(scan!)
(scan! event)
```
Function.

Evaluate registered rules against currently affected strands.

  Rules receive `{:event .. :strand .. :ready-ids #{..}}`; `:ready-ids` is
  computed once per scan. Batch events and their per-strand fanout share a
  `:batch/id`, and only the first event of a batch triggers a scan. The scan
  walks the whole current graph, so a rule can notify about a strand different
  from the event's directly affected strand. Call the zero-argument form from
  trusted code or use the event-handler path installed by activation.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/chime/src/millhouse/spools/chime.clj#L429-L451">Source</a></sub></p>

## <a name="millhouse.spools.chime/set-notifier!">`set-notifier!`</a>
``` clojure
(set-notifier! notifier)
```
Function.

Bind the local notifier command for this weaver lifetime.

  The binding is `{:argv [..]}`. Chime appends the notification title as the
  final argument and writes the body to stdin. Rebinding replaces the prior
  value; pass a valid binding after every weaver startup or config reload.

  ```clojure
  (require '[millhouse.spools.chime :as chime])
  (chime/set-notifier! {:argv ["my-notify"]})
  ```

  The command runs with the local user's authority and must accept the title
  as its final argument.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/chime/src/millhouse/spools/chime.clj#L189-L206">Source</a></sub></p>

## <a name="millhouse.spools.chime/unregister!">`unregister!`</a>
``` clojure
(unregister! name)
```
Function.

Unregister a notification rule by key.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/chime/src/millhouse/spools/chime.clj#L337-L358">Source</a></sub></p>

## <a name="millhouse.spools.chime/use-rule!">`use-rule!`</a>
``` clojure
(use-rule! & args)
```
Macro.

Select one or more rule declaration Vars; return them as a vector.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/chime/src/millhouse/spools/chime.clj#L109-L110">Source</a></sub></p>
