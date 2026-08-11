# Millhouse Millstrand-workflows spool

`millhouse.spools.millstrand-workflows` publishes repeatable workflows around
Millstrand and clj-kondo support. `publish-spool-kondo` covers producer-owned
exports, `bootstrap-kondo` handles first-time consumer adoption, and
`bump-spool` updates pinned spool families before reusing that bootstrap path.
These workflows describe and record work; they do not choose branches, edit
consumer repositories on the caller's behalf, push, land, or restart runtimes.

`bump-millstrand` inspects the consumer's exact `deps.edn` coordinate first. A
local sibling coordinate stays local and requires an explicit decision before
bootstrap. A Git/SHA-pinned coordinate delegates to `bump-spool`, which asks
Millstrand for the latest peeled SHA automatically. A local coordinate never
receives an invented SHA.

## Identity and activation

- Root: `spools/millstrand-workflows`
- Namespace: `millhouse.spools.millstrand-workflows`
- Spool key: `millhouse.spools/millstrand-workflows`

Activate it after the Workflow spool:

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

Resolve the registered workflow and start it with the owning root, public
namespace, spool key, and explicit macro-to-hook mappings. It verifies one
producer source, publishes resources on the root classpath, reviews external
imports and overlapping remaps, tests the exported contract, updates docs, and
checks `git diff --check`, clean status, and cache hygiene.

## `bootstrap-kondo`

Start it with the exact consumer worktree and Millstrand workspace:

```clojure
{:worktree "/abs/path/to/consumer-worktree"
 :workspace "/abs/path/to/consumer-worktree/.millstrand"}
```

The first checkpoint asks `greenfield` versus `brownfield` before route work.
Greenfield creates a minimal `.clj-kondo/config.edn` only when absent and
ensures `.clj-kondo/.cache/` is ignored. Brownfield inventories existing config,
imports, hooks, and ignore rules, then merges safely without duplicating
producer-owned hooks or replacing them with consumer remaps.

Both routes run one full resolved-classpath
`clj-kondo --lint "$(clojure -Spath)" --dependencies --parallel --copy-configs --skip-lint`
import. They record provenance for Millstrand and every installed sibling spool,
reject duplicate or overlapping mappings, check cache hygiene, let the agent
discover the consumer's appropriate local quality checks, and leave a precise
local handover. No fixed repository quality command is assumed.

## `bump-spool`

Provide family names only; versions and SHAs are intentionally not request
parameters:

```clojure
{:families ["io.millstrand/millstrand" "millhouse/spools"]
 :worktree "/abs/path/to/consumer-worktree"
 :workspace "/abs/path/to/consumer-worktree/.millstrand"
 :direct-user-request false}
```

For each family, the workflow emits:

```text
strand --workspace <workspace> spool bump <family> --latest sha
```

An already-current coordinate is recorded and accepted. The workflow then
calls `bootstrap-kondo`, including its greenfield/brownfield choice, one full
classpath import, provenance/duplicate/cache validation, local quality-check
discovery, and handover. Runtime refresh and cutover retain the explicit
direct-user boundary; ordinary agent and nested calls never stop or restart a
runtime.

## `bump-millstrand`

Use exactly one Millstrand family:

```clojure
{:families ["io.millstrand/millstrand"]
 :worktree "/abs/path/to/consumer-worktree"
 :workspace "/abs/path/to/consumer-worktree/.millstrand"
 :direct-user-request false
 :deps-file "deps.edn"}
```

After inspecting `deps-file`, choose `:local-checkout` or `:git-sha-pinned`.
The local route preserves the checkout and calls `bootstrap-kondo`; the pinned
route calls `bump-spool`, which uses the same automatic `--latest sha` default.
Neither route invents a SHA or assumes a fixed quality command.

## See also

- [`millstrand-workflows.cookbook.md`](./millstrand-workflows.cookbook.md) —
  recipes for producer publication and consumer adoption.
- [`millstrand-workflows.api.md`](./millstrand-workflows.api.md) — generated API
  documentation.
- [`../workflow/README.md`](../workflow/README.md) — the Workflow spool that
  executes registered definitions.
