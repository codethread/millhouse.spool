# Millhouse Workflow spool

`millhouse/workflow` is a tools.deps library containing the workflow engine, worker CLI, code and shell gate executors, and reusable Millstrand workflows. Its focused cookbooks and API references remain separate:

- [workflow cookbook](./workflow.cookbook.md) · [workflow API](./workflow.api.md)
- [code executor cookbook](./code.cookbook.md) · [code executor API](./code.api.md)
- [shell executor cookbook](./shell.cookbook.md) · [shell executor API](./shell.api.md)
- [Millstrand workflows API](./millstrand-workflows.api.md)
- [Explicit guarded validation retry](./validation.md) — opt-in recipes, schemas,
  terminal evidence and best-effort resumability

## Activation model

Provider namespaces now contain only inert `def*` declarations. Requiring one
makes its Vars available but publishes no operation, workflow, executor, query,
or lifecycle declaration. A consumer selects exactly those Vars it wants with
the matching `use-*!` form in its own module.

The workflow engine itself is the bootstrap namespace and must be activated
before modules that publish workflow definitions or executors:

```clojure
(runtime/module! runtime :workflow/engine
  {:ns 'millhouse.workflow
   :required? true})
```

### Select only the worker CLI

```clojure
(ns app.workflow-cli
  (:require [millhouse.workflow.cli :as cli]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.millstrand.alpha :as millstrand]))

(millstrand/use-op! cli/workflow)
(lifecycle/use-seed! cli/workflow-glossary-seed)
```

Activate `app.workflow-cli` after `:workflow/engine`. Add `millhouse/workflow` to the workspace's `deps.edn`, and use `:after` to declare module ordering.

### Select an executor

```clojure
(ns app.shell-executor
  (:require [millhouse.executors.shell :as shell]
            [millhouse.workflow :as workflow]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.millstrand.alpha :as millstrand]))

(workflow/use-executor! shell/shell-stalled?)
(millstrand/use-query! shell/stalled-shell-gates)
(lifecycle/use-resource! shell/shell-pool shell/shell-handler)
(lifecycle/use-reconcile! shell/shell-attempts)
```

The code executor follows the same pattern with `code/code-stalled?`,
`code/stalled-code-gates`, and `code/code-engine`.

### Select reusable workflows

Every reusable workflow namespace exposes ordinary `defworkflow` Vars. Select
only the definitions required by your workspace:

```clojure
(ns app.release-workflows
  (:require [millhouse.millstrand-workflows :as workflows]
            [millhouse.workflow :as workflow]))

(workflow/use-workflow! workflows/publish-spool-kondo)
```

This works because `defworkflow` creates an inert, metadata-bearing Var;
`use-workflow!` publishes that Var from the consumer module. Registered names
therefore retain normal workflow entrypoint validation.

### Activate everything

For workspaces that want the complete shipped surface, activate the bundled selector namespace after the engine:

```clojure
(runtime/module! runtime :workflow/all
  {:ns 'millhouse.workflow.spool
   :after [:workflow/engine]})
```

`millhouse.workflow.spool` selects the CLI, both executors, their queries and lifecycle declarations, and `publish-spool-kondo`. It is a convenience entry point, not a requirement.

## Author workflow data

Default to a short linear sequence; branch or loop only for a real decision or
repetition, and keep a cohesive artifact checklist within one step.

Start with the [linear evidence recipe](workflow.cookbook.md#recipe-a-linear-evidence-handoff).
`workflow list` introduces purpose; `show` describes parameters and declared
shape, not rendered instructions. `start` and `ready` expose the current ready
items. Put the immediate action, evidence references and completion fact in each
item's instruction, not only in discovery prose or arbitrary attributes.

Declare `:depends-on` for a linear sequence; source order does not serialize.
Split at meaningful output, owner, wait, authorization or recovery boundaries.
Ordinary instructions and fixed-call inputs render at pour. Later `complete
--context` does **not** re-render them: read durable results explicitly, or use a
justified fresh continuation. Defer parameters must be supplied explicitly.
Keep delegated-agent prompts separate from driver instructions; neither a human
label nor actor attribution substitutes for actual user authorization.

`workflow` returns a validated definition map. Compose it with `step`, `gate`,
`checkpoint`, `call`, and `defer`. Publish consumer definitions with inert
`defworkflow` plus `use-workflow!` in the owning module; use `defworkflow!` only
when deliberately defining and selecting a consumer-owned declaration together.

```clojure
(workflow/defworkflow build
  "Build the requested feature."
  {:entrypoints #{:start}}
  (workflow/workflow "Build"
    (workflow/step :implement "Implement" :self)))

(workflow/use-workflow! build)
```

The `doc` argument may be a computed Clojure expression rather than a string
literal; its evaluated result must be a non-blank string.

## Actor attribution

Workflow mutations that record an actor (`complete`, `next`, `choose`, and
`defer`) use `--by-identity ID` and persist canonical
`identity/by-identity`, where `ID` is a friendly actor identity. Resolution in
the identity registry is best-effort enrichment, so a nonblank unresolved value
still records valid work attribution. The old `--by` spelling is not an alias.
This matches Kanban note authorship and deliberately differs from Kanban claim
ownership (`--owner ID`) and native-session references (`--identity` and
`--parent-identity`). Executor-owned completions instead record
`workflow/executor` plus an optional opaque `workflow/executor-run-id`; these
fields are provenance, never actor identity or authorization. The downstream
Harnesses agent adapter contract is:

```clojure
(workflow/run-complete! {:run-id workflow-run-id
                         :step gate-id
                         :executor "agent"
                         :executor-run-id harnesses-run-id
                         :attributes outcome-attributes})
```

`:step` is mandatory for gates; `:attributes` and `:context` are optional. The
adapter must not send its run ID as `:by-identity`. Provenance does not bypass
protected-gate lifecycle hooks. Read `strand help workflow <verb>` for each
action's supported flags.

See the focused documentation above for graph composition, routing, run driving,
executor request contracts, recovery, discovery, and reusable workflow inputs.
