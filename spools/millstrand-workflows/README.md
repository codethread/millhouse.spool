# Millhouse Millstrand-workflows spool

`millhouse.spools.millstrand-workflows` publishes guided workflows for producer-owned clj-kondo exports and consumer dependency adoption. The workflows describe and record work; they do not choose branches, edit consumer repositories on the caller's behalf, push, land, or restart runtimes.

## 1. Activation

Activate it after the Workflow spool. The root is `spools/millstrand-workflows`, the namespace is `millhouse.spools.millstrand-workflows`, and the spool key is `millhouse.spools/millstrand-workflows`.

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

Activation publishes the workflow definitions described below.

## 2. Publish a producer export

`publish-spool-kondo` is for a spool root that owns macro source. It requires one producer source and an explicit macro-to-hook mapping for each form. The workflow verifies that `resources` is on the root classpath, publishes the export and hooks, reviews external imports and overlapping consumer remaps, tests the exported contract, updates the root's docs, and checks cache hygiene and clean status.

The producer resource directory is the source of truth for its mapping. A changed macro shape requires a reviewed export hook, focused tests, and documentation; the workflow does not discover macros or edit files automatically.

## 3. Adopt exports in a consumer

`bootstrap-kondo` asks for `greenfield` or `brownfield` before route-specific work. Greenfield creates `.clj-kondo/config.edn` only when absent and establishes the cache ignore rule. Brownfield inventories existing config, imports, hooks, and ignore rules before merging only missing local settings. Both routes preserve existing LSP settings while ensuring `.lsp/config.edn` contains `:copy-kondo-configs? false`; explicit bootstrap is the sole Kondo import owner.

For every intended installed root, read the live `strand --workspace <workspace> spool status` result and derive classpath directories from its reported `sync.root` and `deps.edn` `:paths`. A declared `millstrand/source-root` is handled specially: remove exactly its relative path segments from the end of `sync.root` to derive `BASE`, verify the reconstruction, read `BASE/deps.edn`, and include its declared paths, including `resources`. Require `BASE/resources/clj-kondo.exports/io.millstrand/millstrand/`; do not search upward or guess. Absent `:paths` means `src`, while explicit `:paths []` remains empty. The installed spool root is still resolved normally, and a plain consumer `clojure -Spath` is insufficient.

The import records exact roots, derivations, and the combined classpath before one `clj-kondo --lint RESOLVED_CLASSPATH --dependencies --parallel --copy-configs --skip-lint` invocation. Unresolved roots, a missing base export, an invalid base `deps.edn`, or an empty installed-spool contribution fail loudly with the applicable invariant. Validation checks one provenance source per imported mapping, duplicate and overlap decisions, repository-relative self-imports, LSP ownership, and tracked cache files; legitimate producer imports remain valid. Quality checks are discovered from the consumer repository and handed over with the before/during/after self-import result.

## 4. Update dependency coordinates

`bump-spool` accepts family names only. It requests each remote default-branch HEAD SHA with `spool bump <family> --latest sha`, records an already-current coordinate as successful, then reuses `bootstrap-kondo` before handing over the refreshed runtime.

`bump-millstrand` first inspects the exact `io.millstrand/millstrand` entry in `deps-file`. A local sibling coordinate stays local and requires an explicit continuation decision before bootstrap. A Git/SHA-pinned coordinate delegates to `bump-spool`. Neither route invents a SHA or assumes a fixed quality command.

Runtime refresh and cutover retain the direct-user boundary: ordinary agent and nested calls hand over the pending generation and never stop or restart a runtime.

## 5. Millstrand state and APIs

| Surface | Identity | Consumer contract |
| --- | --- | --- |
| Workflow registry | `workflow/definition-kind` owned by `millhouse.spools.millstrand-workflows` | Publishes the producer, bootstrap, bump, and continuation workflow definitions during module refresh. |
| Agent instructions | `workflow/action-ref` on each workflow step | Exposes the exact producer, classpath, validation, quality, and handover obligation for the driving agent. |
| Kondo import boundary | `.lsp/config.edn :copy-kondo-configs? false` | Keeps explicit bootstrap as the sole consumer-side import owner. |
| Runtime cutover boundary | `:direct-user-request` | Allows stop/restart instructions only for a direct user request; other callers receive a handover. |
