# Millhouse Millstrand-workflows spool

`millhouse.spools.millstrand-workflows` publishes repeatable workflows around
reusable Millstrand and clj-kondo spool support. `publish-spool-kondo` is a
reviewable publisher checklist for a root that owns macros, while `bump-spool`
is the portable consumer workflow for updating pinned spool dependencies and
refreshing the selected runtime. Neither workflow performs filesystem edits or
runtime cutover itself: callers supply the explicit context and record each
instruction's result.

`bump-millstrand` is the Millstrand-specific entrypoint. It first asks the
caller to inspect the consumer's exact `deps.edn` coordinate. A local sibling
coordinate is preserved and requires an explicit local-checkout decision before
the consumer clj-kondo import/validation path runs; a Git/SHA coordinate routes
to the registered `bump-spool` workflow with the Millstrand request. A local
coordinate never receives an invented SHA.

## Identity and activation

- Root: `spools/millstrand-workflows`
- Namespace: `millhouse.spools.millstrand-workflows`
- Spool key: `millhouse.spools/millstrand-workflows`

Approve the root through the repository family entry, then activate it after the
Workflow spool:

```clojure
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(def runtime (current/runtime))
(runtime/module! runtime :millhouse/millstrand-workflows
  {:ns 'millhouse.spools.millstrand-workflows
   :spools ['millhouse.spools/millstrand-workflows
            'millhouse.spools/workflow]
   :after [:millhouse/workflow]
   :required? true})
```

## `publish-spool-kondo`

Resolve the registered workflow and start it with a complete parameter map:

```clojure
{:spool-root "spools/example-macros"
 :namespace "example.macros"
 :spool-key "example/macros"
 :macro-forms [{:macro "example.macros/defwidget"
                :hook "hooks.example/defwidget"}]}
```

The workflow walks these obligations in order:

1. Confirm one producer source owns the listed macro forms.
2. Keep `resources` on that root's classpath and verify the resource resolves.
3. Add the explicit `clj-kondo.exports` config for each macro and hook.
4. Implement hooks that model the named macro syntax shapes.
5. Review external imports, inspect import drift, and reject overlapping consumer remaps.
6. Check that no generated self-import or tracked `.clj-kondo/.cache` file is present.
7. Test the exported config and hooks from a clean consumer classpath.
8. Update the contract, cookbook, and generated API documentation.
9. Finish with `git diff --check` and an empty `git status --short`.

A macro shape change requires a reviewed export hook, focused tests, and
documentation in the same change. The workflow supplies instructions and action
references; a publisher performs and records the filesystem and quality work.

## `bump-spool`

Resolve `bump-spool` from the same activated module and provide the exact
consumer worktree and Millstrand workspace:

```clojure
{:bumps [{:family "io.millstrand/millstrand" :version "v12"}]
 :worktree "/abs/path/to/consumer-worktree"
 :workspace "/abs/path/to/consumer-worktree/.millstrand"
 :direct-user-request false
 :quality-argv ["make" "quality"]}
```

The workflow confirms the selected world, coordinates each requested bump, and imports all dependency clj-kondo exports in one classpath invocation. The review then checks one producer source, reviewed external imports, overlapping consumer remaps, import drift, and tracked cache files before committing the copied configuration. It runs the consumer's quality command and requires a clean final Git status before refreshing the selected runtime. Runtime stop/start cutover is conditional on an explicit direct-user request; otherwise the final step hands over the pending generation without stopping or restarting anything.

## `bump-millstrand`

Start it with exactly one Millstrand request and the same explicit consumer
world used by `bump-spool`:

```clojure
{:bumps [{:family "io.millstrand/millstrand" :version "latest"}]
 :worktree "/abs/path/to/consumer-worktree"
 :workspace "/abs/path/to/consumer-worktree/.millstrand"
 :direct-user-request false
 :deps-file "deps.edn"
 :quality-argv ["make" "quality"]}
```

The workflow records an agent classification choice after inspecting
`deps-file`. The local branch records a second `:move-forward`/`:stop` choice,
keeps the local checkout unchanged, imports all resolved clj-kondo exports, and
runs dependency validation plus the consumer quality command. The pinned branch
calls registered `bump-spool`, which performs the requested bump and continues
through its config, quality, and runtime-generation result. Runtime stop/start
remains conditional on `:direct-user-request`; no agent or nested call grants
that authority.

## See also

- [`millstrand-workflows.cookbook.md`](./millstrand-workflows.cookbook.md) — the
  publisher recipe and export layout.
- [`millstrand-workflows.api.md`](./millstrand-workflows.api.md) — generated API
  documentation.
- [`../workflow/README.md`](../workflow/README.md) — the Workflow spool that
  executes the registered definition.
