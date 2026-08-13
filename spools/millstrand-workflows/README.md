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

`bootstrap-kondo` asks for `greenfield` or `brownfield` before route-specific work. Greenfield creates `.clj-kondo/config.edn` only when absent and establishes the cache ignore rule. Brownfield inventories existing config, imports, hooks, and ignore rules before merging only missing local settings. Both routes preserve existing LSP settings while ensuring `.lsp/config.edn` contains `:copy-kondo-configs? false`; explicit bootstrap is the sole Kondo import owner. Before importing, both routes verify a repository-native or standalone clj-kondo command that accepts the required import flags. If neither exists, the agent stops to discuss the [official installation options](https://github.com/clj-kondo/clj-kondo/blob/master/doc/install.md) and obtains explicit approval for the user's chosen method before running an installer or package manager.

For every intended installed root, read the live `strand --workspace <workspace> spool status` result. Before deriving paths, parse the selected spool metadata and consumer producer metadata once into an `owned-roots` table keyed by exact family/root and coordinate. A local coordinate that resolves inside the consumer worktree identifies a consumer-owned producer root; each candidate must match exactly one status root or the import fails loudly. Canonicalize every installed contribution entry, every consumer classpath entry, and every owned classpath or export comparison path with the same path operation before taking their union or applying ownership filtering. A failed canonicalization is a loud pre-copy failure. Exclude entries that are exact owned producer classpath or export paths, or descendants of those paths. The final `RESOLVED_CLASSPATH` and the pre-command invariant use canonical paths only. Keep non-owned dependency exports, including a pinned remote `millhouse/spools` family. A declared `millstrand/source-root` is handled specially: remove exactly its relative path segments from the end of `sync.root` to derive `BASE`, verify the reconstruction, read `BASE/deps.edn`, and include its declared paths, including `resources`; retain this explicit source-root/`BASE` contribution even when it is under the worktree. Require `BASE/resources/clj-kondo.exports/io.millstrand/millstrand/`; do not search upward or guess. Absent `:paths` means `src`, while explicit `:paths []` remains empty. The installed spool root is still resolved normally, and a plain consumer `clojure -Spath` is insufficient.

The import records owned roots, retained dependency roots, derivations, the verified Kondo command, and the final canonical-filtered classpath before one `KONDO_CMD --lint RESOLVED_CLASSPATH --dependencies --parallel --copy-configs --skip-lint` invocation. Immediately before the command, it asserts using only canonical paths that no owned producer classpath or export path, or descendant, remains in `RESOLVED_CLASSPATH`, and that every canonical owned export path is absent. Do not copy and clean up self-imports afterward. Unresolved or ambiguously owned roots, failed canonicalization, unreconcilable canonical ownership, a missing base export, an invalid base `deps.edn`, or an empty filtered installed-spool contribution fail loudly with the applicable invariant. Validation reuses the same ownership table, checks one provenance source per retained mapping, duplicate and overlap decisions, repository-relative self-imports, LSP ownership, and tracked cache files; legitimate remote producer imports remain valid. Quality checks are discovered from the consumer repository and handed over with the before/during/after self-import result.

## 4. Update dependency coordinates

`bump-spool` accepts family names only. It requests each remote default-branch HEAD SHA with `spool bump <family> --latest sha`, records an already-current coordinate as successful, then reuses `bootstrap-kondo` and refreshes the selected runtime.

After refresh, `configure-consumer-tooling` inspects the repository and asks the agent to choose one style:

The consumer-tooling inspection derives its target workspace as `worktree/.millstrand` and acquires that exact world before repository classification and the routed bootstrap. On `mill/no-selected-weaver`, it starts only that disposable consumer Weaver, records its PID and identities, and reruns status. A disposable workflow-host workspace is never forwarded to the consumer tooling child.

- `app`: a non-Clojure product that uses Clojure only for Millstrand config, tooling, and tests;
- `spool`: a repository that owns and publishes one or more spool roots;
- `clojure-app`: an ordinary Clojure application that also has Millstrand config.

Each continuation starts with a required manual alignment and proof of the invocation producer coordinate. Ordinary consumers supply a pinned remote `millhouse/spools` family with its exact `git/url` and full lowercase `git/sha`; Millhouse itself supplies its exact local self root and `local/root`. Before bootstrap or tools.deps alignment, the agent compares that coordinate with the consumer's `spools.edn` activation and every relevant Millhouse root in `deps.edn`. When they differ, the agent manually updates only those coordinates, preserves each `:deps/root`, and records the before and after values. Shared roots must use one version-coherent family coordinate. Missing or incomparable metadata stops the workflow. The workflow never infers a running workflow SHA or automates these edits.

After that proof, each continuation walks through tools.deps, clojure-lsp, clj-kondo and lint, tests, and Weaver proof. These are ordinary agent steps, not gates. The agent adapts the repository's existing files and commands, records the actual evidence, and stops when the style or classpath ownership is ambiguous. `spools.edn` remains the Weaver's approval graph; `deps.edn` and the LSP, lint, and test configuration provide a matching view for Clojure tools.

`bump-millstrand` first inspects the exact `io.millstrand/millstrand` entry in `deps-file`. A local sibling coordinate stays local and requires an explicit continuation decision before bootstrap. A Git/SHA-pinned coordinate delegates to `bump-spool`. Both routes reach the same repository-style tooling choice. Neither route invents a SHA or assumes a fixed quality command.

Runtime refresh and cutover retain the direct-user boundary. When refresh reports no pending generation, the chosen route proves real Weaver behavior immediately. When it reports a pending generation, the workflow separates prepared tooling, proof against the current generation, and the Weaver check that remains unfinished. Ordinary agent and nested calls hand that check over and never stop or restart a runtime. A direct-user cutover repeats the chosen Weaver check after the new generation is adopted.

## 5. Millstrand state and APIs

| Surface | Identity | Consumer contract |
| --- | --- | --- |
| Workflow registry | `workflow/definition-kind` owned by `millhouse.spools.millstrand-workflows` | Publishes the producer, bootstrap, bump, tooling-choice, and continuation workflow definitions during module refresh. |
| Agent instructions | `workflow/action-ref` on each workflow step | Exposes the exact producer, classpath, LSP, lint, test, Weaver, and handover obligation for the driving agent. |
| Repository style | Agent choice in `configure-consumer-tooling` | Selects `app`, `spool`, or `clojure-app`; its setup steps are manual and add no executor gate. |
| Kondo import boundary | `.lsp/config.edn :copy-kondo-configs? false` | Keeps explicit bootstrap as the sole consumer-side import owner. |
| Runtime cutover boundary | `:direct-user-request` | Allows stop/restart instructions only for a direct user request; other callers receive a handover. |
