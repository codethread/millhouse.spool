# Clojure lint and editor configuration

This is the shared procedure for Millstrand and its sibling repositories. Implement it according to the repository's package layout. The contract is package-owned macro exports, imports from declared dependencies, and checks that cover the actual source files.

## Choose the analysis roots

A repository and a Clojure package are not always the same boundary. Start by identifying the independently consumable `deps.edn` roots and the aliases needed to analyze their source, tests, and development code.

| Layout | Procedure |
| --- | --- |
| One package at the repository root | Resolve that package's appropriate basis and lint its source and tests. |
| Multiple packages | Import and lint each package with its own basis. A repository Makefile can aggregate these operations. |
| Repository root contains shared tests | Use its test basis for those tests, and still check independently shipped packages with their own bases. |
| Repository has no root `deps.edn` | Aggregate the real package roots; do not invent a combined classpath just for lint. |
| `.millstrand` has its own `deps.edn` | Treat workspace configuration as a separate consumer, including its bootstrap and local namespaces. |
| Legacy workspace has no separate basis | Identify the declared basis that supports it and explicitly include its files. Record that choice locally. |

Do not let a broad test classpath hide a dependency missing from an independently shipped package. Keep compatible historical library pins when older APIs are intentional. A broken dependency pair needs a compatible pair or a justified API change; a linter classpath override must not conceal the mismatch.

## Export the macros a package supplies

For a package that supplies public macros needing analysis support:

1. Keep its configuration under `resources/clj-kondo.exports/<group>/<artifact>/config.edn`, with any hooks in that export tree.
2. Include `resources` in that package's `deps.edn` paths so tools.deps consumers receive the export through the dependency classpath.
3. Describe only macros owned by that package. Choose `:lint-as` when another form faithfully models its binding shape; use a hook when the shape requires custom analysis. Verify the hook accepts the macro's actual arguments.
4. Keep macro behavior and its consumer proof together when changing a public declaration form or generated Var name.

A pure consumer needs no empty export tree. Private test helper macros can keep authored hooks in the repository's local `.clj-kondo` configuration; do not publish those test namespaces as a downstream API. Consumers import producer mappings instead of maintaining copies of those mappings locally.

Some ordinary macros need no custom analysis. Record that conclusion after checking their actual use; do not add mappings solely because a macro exists.

## Import through the declared dependency graph

Resolve the selected basis from the package directory. Use `-Srepro` to exclude user-level tools.deps configuration. For example:

```sh
clojure -Srepro -Spath
clojure -Srepro -Spath -M:test
```

Keep `-Spath` before the `-M` alias. Select the repository's actual alias; do not assume every package has `:test`.

The refresh operation creates the local `.clj-kondo` directory, removes that root's generated imports, resolves its classpath, and copies exported configs:

```sh
mkdir -p .clj-kondo &&
  rm -rf .clj-kondo/imports &&
  classpath="$(clojure -Srepro -Spath -M:test)" &&
  clj-kondo --repro --lint "$classpath" --copy-configs --skip-lint
```

Use the resolved classpath directly. Do not append sibling resource directories by hand. A failed resolver must prevent the Kondo invocation; embedding a failing command substitution inside its arguments can hide the failure.

Keep generated imports and caches out of Git, including nested roots. Track authored configuration, hooks, and producer exports. If imports were previously committed, remove those generated files from tracking while retaining the authored files. Verify the tracked-file inventory after refreshing; an ignore rule does not untrack existing files.

## Provide the common Make commands

Every Clojure-bearing sibling repository exposes these commands at its root:

| Command             | Contract                                                                  |
| ------------------- | ------------------------------------------------------------------------- |
| `make kondo-import` | Refresh dependency exports for each selected analysis root.               |
| `make kondo-lint`   | Lint the intended source/test/workspace files using existing imports.     |
| `make kondo`        | Complete all imports before starting lint, including under parallel Make. |

A single-package Makefile can implement the operations directly. A repository with several packages can provide package-local Makefiles or an explicit root dispatcher. Preserve each package's working directory and dependency basis.

One valid ordering pattern is:

```make
.PHONY: kondo kondo-import kondo-lint

kondo: kondo-import
	$(MAKE) kondo-lint
```

Imports and lint must not be sibling prerequisites that can race under `-j`. Aggregate loops must stop on any failed package, using `set -e` or explicit failure handling. A later success must not overwrite an earlier failure.

Use `kondo` in ordinary quality and CI. Keep `kondo-lint` available for a focused source-only rerun. When renaming existing commands, update their CI, script, and documentation callers or retain a deliberate compatibility alias.

Use a consistent pinned standalone Kondo version, whether invoked through the native executable or a Maven alias. The September 2026 rollout uses `2026.08.04`; changing this baseline requires checking the affected repositories and generated templates.

## Configure and verify the editor separately

Run clojure-lsp at the package root. Let it resolve the package's dependencies and copy their Kondo exports. If the default discovery is insufficient, use package-local `.lsp/config.edn` settings for the declared aliases or an explicit `:project-specs` classpath command. Avoid a broad parent configuration that merges unrelated packages.

The headless `clojure-lsp diagnostics --raw` command checks the same analyzer used by editors. Run it with a disposable `XDG_CONFIG_HOME`, a separate cache for each project, explicit project roots, and the intended source/test files. Use comma-separated absolute paths with `--filenames`.

**Source coverage needs its own proof.** `--filenames` does not force analysis of files outside the discovered source paths. A deps-only workspace can omit `init.clj` and report “No diagnostics found” without analyzing it. When runtime bootstrap files sit outside declared source paths, give that workspace a local configuration such as:

```clojure
{:source-paths ["."]}
```

Choose a narrower scope when the layout calls for one. Existing declared paths or aliases take precedence as the normal way to describe package source; this setting handles files that discovery otherwise misses.

For each distinct layout, check both outcomes in a disposable copy:

- Valid source and tests produce no errors or warnings from the selected basis.
- An unresolved sentinel appended to an actual intended source file is reported with a nonzero exit status. Include the workspace bootstrap when it is a separate analysis root.

Do not suppress findings to manufacture a pass. Fix real problems and explain narrow false positives. Record informational diagnostics separately. Record both clojure-lsp and its bundled Kondo version: the editor analyzer can differ from the standalone executable.

## Prove the consumer and finish the change

For each macro owner, use a disposable consumer with declared local or immutable Git dependencies and fresh configuration. Import from its resolved classpath, assert that the expected export arrived, and lint real public forms. Cover inert declarations, bang forms, selection forms, and references to generated Vars where that API supplies them. Confirm generated names from source or macroexpansion; a guessed name can produce a false hook defect.

Before landing:

- Run `make -j kondo` from a fresh import state and the relevant ordinary quality gate. Run focused consumer tests for changed exports.
- Inject a controlled resolver or recursive Make failure and verify the aggregate fails before dependent work runs.
- Verify actual editor source coverage as described above, including the negative check. Check generated repositories too when changing templates.
- Review the diff for copied imports, duplicated producer mappings, missing package bases, and stale callers. Leave generated files ignored.
- Publish changed producer exports before updating consumer Git SHAs. Refresh and recheck affected consumers with those published pins. Runtime restarts belong to the coordinated rollout; do not interrupt active tracked agents.

Link this procedure from each repository's development documentation and record its selected package roots, aliases, and commands there. Keep dated rollout matrices and command results in reports so this procedure remains reusable.

## References

- [clj-kondo export and import configuration](https://cljdoc.org/d/clj-kondo/clj-kondo/2026.08.04/doc/configuration#exporting-and-importing-configuration)
- [clojure-lsp source discovery and configuration](https://clojure-lsp.io/settings/#source-paths-discovery)
- [clojure-lsp CLI](https://clojure-lsp.io/api/cli/)
- [Rollout verification evidence](../reports/kondo-rollout/editor-verification.md)
