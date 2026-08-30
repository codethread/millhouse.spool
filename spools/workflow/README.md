# Millhouse Workflow spool

`millhouse.spools/workflow` is one spool root containing the workflow engine,
worker CLI, code and shell gate executors, and reusable Millstrand workflows.
It has one [`deps.edn`](./deps.edn), while the existing focused cookbooks and API
references remain separate:

- [workflow cookbook](./workflow.cookbook.md) · [workflow API](./workflow.api.md)
- [code executor cookbook](./code.cookbook.md) · [code executor API](./code.api.md)
- [shell executor cookbook](./shell.cookbook.md) · [shell executor API](./shell.api.md)

## Activation model

Provider namespaces now contain only inert `def*` declarations. Requiring one
makes its Vars available but publishes no operation, workflow, executor, query,
or lifecycle declaration. A consumer selects exactly those Vars it wants with
the matching `use-*!` form in its own module.

The workflow engine itself is the bootstrap namespace and must be activated
before modules that publish workflow definitions or executors:

```clojure
(runtime/module! runtime :workflow/engine
  {:ns 'millhouse.spools.workflow
   :required? true})
```

### Select only the worker CLI

```clojure
(ns app.workflow-cli
  (:require [millhouse.spools.workflow.cli :as cli]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.millstrand.alpha :as millstrand]))

(millstrand/use-op! cli/workflow)
(lifecycle/use-seed! cli/workflow-glossary-seed)
```

Activate `app.workflow-cli` after `:workflow/engine`. The workspace's `deps.edn` must make `millhouse.spools/workflow` available.

`spools.edn` approval and the `:spools` module option are not supported by the deps-native runtime. Declare the library in `deps.edn` and keep module ordering in `:after` instead.

### Select an executor

```clojure
(ns app.shell-executor
  (:require [millhouse.spools.executors.shell :as shell]
            [millhouse.spools.workflow :as workflow]
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
  (:require [millhouse.spools.millstrand-workflows :as workflows]
            [millhouse.spools.workflow :as workflow]))

(workflow/use-workflow! workflows/publish-spool-kondo)
```

This works because `defworkflow` creates an inert, metadata-bearing Var;
`use-workflow!` publishes that Var from the consumer module. Registered names
therefore retain normal workflow entrypoint validation.

### Activate everything

For workspaces that want the complete shipped surface, activate the bundled selector namespace after the engine:

```clojure
(runtime/module! runtime :workflow/all
  {:ns 'millhouse.spools.workflow.spool
   :after [:workflow/engine]})
```

`millhouse.spools.workflow.spool` selects the CLI, both executors, their queries and lifecycle declarations, and `publish-spool-kondo`. It is a convenience entry point, not a requirement.

## Author workflow data

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

See the focused documentation above for graph composition, routing, run driving,
executor request contracts, recovery, discovery, and reusable workflow inputs.
