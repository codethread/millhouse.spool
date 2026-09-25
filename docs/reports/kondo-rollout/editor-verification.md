# Headless editor diagnostics verification

**Research task:** `qflyz` under feature `alri9`

**Reviewed:** 2026-09-13

## Recommendation

Use the installed `clojure-lsp diagnostics` CLI as the headless editor check. It starts the same Clojure LSP analysis used by editors, resolves the selected `deps.edn` classpath, and copies clj-kondo configuration exports by default (`:copy-kondo-configs? true`). It is therefore a stronger editor-facing check than invoking standalone clj-kondo alone.

Run one LSP project per package `deps.edn`, not from an arbitrary repository root. Clojure-lsp detects the `deps.edn` at `--project-root`; it does not turn a multi-root repository into one project automatically. The selected root must also be the same root that owns the corresponding `.clj-kondo/imports`.

The current representative check requires the full commit SHA of a published
`v5` or later Millhouse revision:

```nu
./scripts/verify-editor-diagnostics.sh MILLHOUSE_SHA
```

The reproduction results and repository inventory below are historical evidence
from 2026-09-13; their pre-v5 names are not current installation instructions.
See the [v5 migration guide](../../v5.md) for current coordinates.

It is deliberately disposable. It creates a temporary exact-pin consumer with Millstrand and the macro-owning Chime package, sets a temporary `XDG_CONFIG_HOME` and LSP `:cache-path`, and removes all three temporary directories. It does not change human editor configuration or caches.

The fixture verifies both halves of a meaningful diagnostic result:

1. Chime's exported hook accepts actual generated handler names `sample-rule-rule` and `sample-rule-bang-rule` with no diagnostics.
2. An appended `missing-editor-sentinel` produces an `unresolved-symbol` diagnostic and nonzero exit status.

The second assertion prevents a zero-diagnostic result from passing because the LSP did not analyze the fixture. It also guards against repeating the withdrawn false positive: `defrule` generates `<name>-rule`; `defexecutor` generates `<name>-stalled?`.

## Reproduction result

On this machine, `clojure-lsp 2026.07.06-14.34.19` (bundled `clj-kondo 2026.05.26-SNAPSHOT`) passed the valid fixture and copied:

```text
.clj-kondo/imports/io.millstrand/millstrand/config.edn
.clj-kondo/imports/millhouse.spools/chime/config.edn
```

After the sentinel was added, its output was:

```text
src/consumer.clj:8:1: error: [unresolved-symbol] Unresolved symbol: missing-editor-sentinel
```

with exit status 3. The separately installed project linter is `clj-kondo v2026.08.04`; this version difference is intentional evidence that LSP diagnostics and shipped Make lint are distinct checks. A rollout should pin and report both rather than claim they are byte-identical.

## Minimal settings and command shape

For an ad hoc, clean check, use explicit settings rather than writing a persistent `.lsp/config.edn`:

```sh
clojure-lsp diagnostics --raw \
  --project-root path/to/package-root \
  --settings '{:cache-path "/tmp/isolated-lsp-cache"
               :project-specs [{:project-path "deps.edn"
                                :classpath-cmd ["clojure" "-Srepro" "-Spath"]}]}' \
  --filenames path/to/package-root/src
```

`-Srepro` prevents user-level tools.deps configuration from altering the resolved dependency graph. For linting test sources, change only the declared classpath command to the package's existing test alias (for example `["clojure" "-Srepro" "-Spath" "-M:test"]`) and lint the matching `test` directory. Do not add source paths by hand when `deps.edn` already expresses them; use `:source-aliases` only for a declared alias whose extra source paths are required by the editor.

For a human editor, start clojure-lsp at the package root with `clojure` on its PATH. Put any required project settings in that package's `.lsp/config.edn`.

### Workspace source discovery: independently verified correction

`--filenames` does not force analysis of files outside the discovered source paths. A workspace containing `init.clj` and a deps-only `deps.edn` can return “No diagnostics found” without analyzing its bootstrap code. Absolute filenames alone do not fix this.

The coordinator reproduced this in a disposable Harnesses checkout: an unresolved symbol appended to `.millstrand/init.clj` was missed until the workspace source scope was explicitly configured. Then the same file produced an unresolved-symbol error and exit status 3. Such workspaces need a local `.millstrand/.lsp/config.edn` containing `{:source-paths ["."]}`, or an equally precise scope. Millstrand's own workspace already declares `:paths ["."]`.

Use absolute filenames, distinct per-project caches, and a negative check in a disposable copy of the actual workspace file. A valid fixture under `src` proves macro imports, but does not prove that a separate workspace bootstrap is in scope. This follows the documented fallback for source files outside declared project profiles ([source discovery](https://clojure-lsp.io/settings/#source-paths-discovery)).

## Common Make contract

Every Clojure-bearing repository should expose the following semantics, with recipe paths chosen deliberately for each package root:

| Target | Required behavior |
| --- | --- |
| `kondo-import` | Create `.clj-kondo`, resolve that package's selected classpath with `clojure -Srepro -Spath` (or its declared test alias), clear only `.clj-kondo/imports`, then run `clj-kondo --repro --lint "$classpath" --copy-configs --skip-lint`. |
| `kondo-lint` | Lint exactly the package source and relevant test/workspace paths with existing imports and `clj-kondo --repro --parallel --lint`. |
| `kondo` | Complete `kondo-import` before invoking `kondo-lint`, including under parallel Make. Import each selected basis once. |

A repository-level aggregate may invoke these targets once per nested package root. It must not resolve one unrelated root's classpath and import its configs into another root. The import target is a refresh operation: it is required when an effective dependency/export changes. CI and ordinary quality should invoke `kondo` so import happens before lint; `kondo-lint` permits a deliberate source-only rerun against existing imports.

Owner roots expose only their own macro mappings under `resources/clj-kondo.exports/<group>/<artifact>/`. Consumer roots only retain imported copies under `.clj-kondo/imports`; they do not copy producer mappings into local configuration.

## Active rollout inventory

The user narrowed this rollout to these five active repositories. Earlier investigation of archived or experimental repositories does not extend the implementation scope.

| Repository | LSP/package roots requiring deliberate handling | Macro-export or legacy concern |
| --- | --- | --- |
| `skein-src` | root, `.millstrand`, `spools/batteries`, `spools/unsafe-text-search` | Root exports Millstrand macros; workspace is a separate deps root. |
| `millhouse.spool` | root, `.millstrand`, Chime, Cron, Identity, Kanban, Workflow | Only Chime, Cron, and Workflow own exports; the three resource paths must stay package-local. |
| `codethread.spool` | `.millstrand`, `spools/config`, `spools/ralph` | No root `deps.edn`; config is a consumer and must not own Workflow mappings. |
| `harnesses.spool` | root, `.millstrand` | Root owns Harnesses export. |
| `devflow.spool` | root, `.millstrand`, `kanban-adapter` | Consumer imports exist; no owner export currently. |

Neither the lack of a current export nor an empty root `:paths` makes a root exempt: it still needs an explicit lint scope and resolved classpath if it consumes macro-owning dependencies.

## Limits

This proves static editor diagnostics and export imports, not runtime macro evaluation, complete repository quality, or the correctness of every macro surface. The representative proof covers Millstrand plus Chime. Cron and Workflow require direct dependencies in a fixture that uses them; their actual generated names remain `<job>` and `<executor>-stalled?`, respectively.

## Sources

- [clojure-lsp CLI](https://clojure-lsp.io/api/cli/)
- [clojure-lsp settings: classpath scan, source aliases, and copied Kondo configs](https://clojure-lsp.io/settings/)
- [clj-kondo export/import contract](https://cljdoc.org/d/clj-kondo/clj-kondo/2026.08.04/doc/configuration#exporting-and-importing-configuration)
- [Existing exact-pin Kondo consumer reproduction](../kondo-investigation/coordinator-reproduction.json)
