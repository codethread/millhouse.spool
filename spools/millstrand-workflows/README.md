# Millhouse Millstrand-workflows spool

`millhouse.spools.millstrand-workflows` publishes the repeatable workflow
obligations around reusable Millstrand and clj-kondo spool support. Its first
workflow, `publish-spool-kondo`, is a reviewable publisher checklist for a root
that owns macros. It does not inspect a source tree or claim automatic macro
discovery: the caller supplies the owning root, public namespace, spool key, and
every macro-to-hook mapping.

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

The root's `deps.edn` includes both `src` and `resources`. The exported
`clj-kondo.exports/millhouse.spools/millstrand-workflows/config.edn` and its hook
resource are therefore visible to a consumer through the root classpath.

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

1. Confirm the named root owns the listed macro forms.
2. Keep `resources` on that root's classpath and verify the resource resolves.
3. Add the explicit `clj-kondo.exports` config for each macro and hook.
4. Implement hooks that model the named macro syntax shapes.
5. Test the exported config and hooks from a clean consumer classpath.
6. Update the contract, cookbook, and generated API documentation.

A macro shape change requires a reviewed export hook, focused tests, and
documentation in the same change. The workflow supplies instructions and action
references; a publisher performs and records the filesystem and quality work.

## See also

- [`millstrand-workflows.cookbook.md`](./millstrand-workflows.cookbook.md) — the
  publisher recipe and export layout.
- [`millstrand-workflows.api.md`](./millstrand-workflows.api.md) — generated API
  documentation.
- [`../workflow/README.md`](../workflow/README.md) — the Workflow spool that
  executes the registered definition.
