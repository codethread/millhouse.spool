# Millhouse Workflow spool

`millhouse.spools.workflow` turns Clojure data into ordinary Millstrand strand
graphs. A definition can describe agent-owned work, external gates, decisions,
and returning sub-flows; a run is durable, inspectable, and driven one ready
item at a time. The [cookbook](./workflow.cookbook.md) covers compositions and
the [generated API](./workflow.api.md) contains exact signatures and focused
examples.

## 1. Activation

Activate the engine before any spool that authors or fulfills workflow gates:

```clojure
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(def runtime (current/runtime))
(runtime/module! runtime :millhouse/spools-workflow
  {:ns 'millhouse.spools.workflow
   :spools ['millhouse.spools/workflow]
   :required? true})
```

The engine publishes no CLI operation. Opt into the worker surface separately,
after the engine:

```clojure
(runtime/module! runtime :millhouse/spools-workflow-cli
  {:ns 'millhouse.spools.workflow.cli
   :spools ['millhouse.spools/workflow]
   :after [:millhouse/spools-workflow]})
```

<a name="3-definition-layer"></a>
## 2. Define workflow data

`workflow` returns a validated definition map. Use `step` for work performed by
the driving agent, `gate` for work owned by an external actor, `checkpoint` for
a choice, `call` for a procedure known at authoring time, and `defer` for a
procedure selected by a worker at run time.

```clojure
(workflow/workflow
  "Ship an artifact"
  (workflow/step :build "Build the artifact" :self "Build and verify the artifact.")
  (workflow/gate :ci "Wait for CI" :ci
                 :depends-on [:build]
                 "Wait for the CI provider to report success.")
  (workflow/checkpoint :release "Release?"
                       :depends-on [:ci]
                       :choices [:yes :no]))
```

Every step has an id and title. `step` and `gate` also accept an optional final
instruction string or rendering function; it is stored as
`workflow/instruction` and surfaced as `:instruction` in ready views. Existing
keyword options remain optional and precede the instruction. Setting the same
instruction through `:attributes` as well fails loudly. `:depends-on` gives
ordinary graph ordering; absence of an edge permits parallel ready work. Conditions remove steps at
compile time. A dependent of a removed step is spliced onto that step's own
dependencies, so a conditional branch cannot leave a dangling blocker. Unknown
references and a step id colliding with the root fail before materialization.

Titles, descriptions, and attributes may be functions of resolved params. A
`:loop` expands a step over `{:count n}` or `{:each xs}`; `:chain true` links
successive copies, and a dependency on the base id waits for every copy. Loop
conditions use workflow params, while rendered values also receive `:item` and
zero-based `:i`.

`step` accepts only `:self` as its waiter. Use `gate` for a named waiter such as
`:ci`, `:human`, or `:subagent`. A gate remains an ordinary workflow step, but
its view carries `:gate` and `complete!` requires a non-blank `:by`. A registered
gate executor may keep `await!` quiet while it is healthy; an unregistered
waiter is always surfaced as attention.

`call` expands a known definition inline and exposes a procedure join for
downstream dependencies. The join closes automatically when its inner work
closes. `defer` has no `:condition` or `:loop`: bind it with `bind-defers` to a
non-empty set of registered definitions advertising `:call`. `defer!` fills the
ready selection and pours the target beneath the current root; it returns to the
declaring workflow instead of transferring ownership of the run.

<a name="5-checkpoints-and-routing"></a>
## 3. Publish definitions and contracts

Use inert `defworkflow` to create a declaration Var, then select it with `use-workflow!` in a publishing module. `defworkflow!` combines those two steps when the definition belongs to the same module:

```clojure
(defworkflow! build
  "Build the requested feature."
  {:entrypoints #{:start}
   :param-spec ::build-params
   :defaults {:reviewer "agent"}
   :example {:feature "demo"}
   :param-docs {:feature "Feature to build."}}
  (workflow "Build"
    (step :implement "Implement" :self)))
```

The definition Var is ordinary Clojure data. Inert definitions contribute nothing; typed selection is the publication boundary, so omitting a selection on refresh removes that owner's entry. A registered name can advertise `:start`, `:continue`, and `:call`; the corresponding boundaries are enforced for worker starts, named continuations, and calls/defer targets. Direct Clojure values remain trusted.

`:defaults` merge below caller params. `:param-spec` validates the complete
resolved map before compilation or routing. `:example` must be a complete
JSON-compatible valid map, and `:param-docs` documents the outer keys declared
by the spec. `spec-forms` and the discovery projections expose the live contract
without executing predicates. A JSON worker should pass objects through
`json->params`, which recursively keywordizes object keys.

`checkpoint` choices may be keywords or maps with labels, descriptions, and one
of `:next` or `:revise`. `:next` names a registered continuation or Var and
abandons the remainder of the current stage in the same mutation. `:revise`
re-pours the run's own definition with override params, so starts from a Var or
registered name are required. A choice `:input` names a whole-map spec; the
choice is validated against the live spec before any close or continuation pour.

## 4. Drive a run

`start!` accepts a workflow map, definition Var, or registered name and returns
the common result shape:

```clojure
{:ready [step-view ...]
 :done false}
```

The same shape comes back from `complete!`, `choose!`, `defer!`, and
`advance!`. `ready` is the complete frontier; `ready-step`, `ready-checkpoint`,
and `ready-gates` are role-specific reads. `done?` is true only when no active
workflow root or work remains. A run id with no root is an error, not an empty
run.

Use `complete!` for an ordinary ready step and `choose!` for a checkpoint.
`complete!` may merge caller-owned outcome attributes and shallow-update the
run's JSON-safe context in the same close. Use `defer!` for a defer; neither
`complete!` nor `advance!` can fill one. `advance!` is the convenience operation
for one ordinary step or checkpoint, with explicit `:choice`/`:input` only for a
checkpoint. A gate is never inferred and must be selected explicitly with `:by`.

All mutations validate before writing and resolve the frontier again under the
run guard. If another worker moved it first, the request fails as a stale
frontier and writes nothing; read `ready` and retry against the new frontier.
Closing a gate records its actor, and closing a checkpoint records its outcome
and optional actor/input. Routed closes and continuation pours are one batch, so
a failed route leaves the current run resumable.

`await!` waits through healthy executor-owned gates and returns when a run is
done or needs a self step, checkpoint, defer, unattended gate, executor stall,
or timeout. `run-history` projects closed work across every molecule in a run.
`squash-run!` archives a finished run as one digest and burns its molecules;
`pour!`/`wisp!`, `bond!`, `burn!`, and `squash!` are the lower-level molecule
operations for trusted Clojure composition.

## 5. Discover and drive from the worker surface

After activating the CLI module, the `workflow` operation provides a narrow
registered-definition boundary:

```text
strand workflow list
strand workflow show build
strand workflow start build run-1 --params '{"feature":"demo"}'
strand workflow ready run-1
strand workflow complete run-1
strand workflow choices run-1
strand workflow choose run-1 --choice yes
strand workflow next run-1
strand workflow defer run-1 --workflow review --params '{"artifact":"a"}'
strand workflow await run-1
```

`list` shows registered names and entrypoints; `show` adds the definition's
params, shape, choices, calls, gates, and defer allowlists. `executors` exposes
registered waiter adapters and any declared request contract. The run verbs
accept JSON objects, return the same result shape, and never accept caller
supplied topology. Read `choices` before `choose` when a choice has an input
contract, and read `show` before filling a defer so the target's params are
known. Repeating `--attr` keys in one request is an error; typed `--attributes`
values are merged beneath explicit string `--attr` values.

## 6. Millstrand state and APIs

| Surface | Identity | Consumer contract |
| --- | --- | --- |
| Workflow definitions | `workflow/definition-kind` | Module-owned names resolve to static definition Vars; publication validates the complete candidate registry. |
| Gate executors | `workflow/executor-kind` | A waiter maps to a stall predicate and may publish a request spec for discovery. |
| Workflow graph | `workflow/run-id`, `workflow/role`, `workflow/form`, `workflow/position` | Root, step, checkpoint, defer, procedure, and digest strands remain inspectable through ordinary graph APIs. |
| Workflow routing | `workflow/context`, `workflow/definition-name`, `workflow/stage-params` | Continuations and revisions carry the run context and routing identity needed for the next boundary. |
| Gate and decision state | `workflow/gate`, `workflow/checkpoint`, `workflow/choices`, `workflow/choice-details` | External ownership and available decisions are visible on ready step views and discovery reads. |
| Outcomes | `workflow/outcome`, `workflow/outcome-input`, `workflow/outcome-by` | Choice and actor records are written with the closing mutation; caller attributes remain their own vocabulary. |
| Returning composition | `workflow/defer`, `workflow/defer-workflows`, `workflow/procedure` | A filled defer records its selected target and returns through an auto-closed procedure join. |
| Archival | `workflow/role "digest"`, `workflow/summary` | `squash!` and `squash-run!` leave a closed digest with the folded-root/count metadata. |
| Worker tooling | `workflow` from `millhouse.spools.workflow.cli` | Opt-in list/show/executors and run verbs expose registered workflows without publishing topology. |
| Authoring tooling | `resources/clj-kondo.exports/millhouse.spools/workflow/` | The root exports `defworkflow`, `defworkflow!`, `use-workflow!`, `defexecutor`, `defexecutor!`, and `use-executor!`; consumers must include this root's `resources` path. |
