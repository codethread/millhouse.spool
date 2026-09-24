# Terra — clj-kondo package-export investigation

**Task:** `7bqzi` (delegated under feature `a4jme`)

**Date:** 2026-09-13

**Scope:** read-only audit and disposable consumer experiment. No source, dependency, or workspace changes were made.

## Conclusion

The owner-packaging hypothesis is true for Millstrand and the three macro-owning Millhouse spools: each owner puts `resources` in its own `:paths`, and its `resources/clj-kondo.exports/<org>/<lib>/` directory is therefore on the resolved classpath. A clean consumer can import those exports with one `clj-kondo --copy-configs` invocation.

This is **not automatic on ordinary lint**. `deps.edn` only makes resources classpath-visible; clj-kondo copies them only when asked with `--copy-configs` and an existing consumer `.clj-kondo` directory. The copied `imports/*` configs then auto-load by default in subsequent lint runs. `--dependencies` is a separate, recommended analysis-cache warm-up; it is not required to copy exports.

**Correction (2026-09-13):** the initially reported Chime/Workflow hook defect was a false positive. `defrule` creates `<name>-rule`, while `defexecutor` creates `<name>-stalled?`; the first fixture incorrectly passed the declaration names to the corresponding `use-*!` forms. A rerun at exactly the same pins with `sample-rule-rule`, `sample-rule-bang-rule`, `sample-executor-stalled?`, and `sample-executor-bang-stalled?` had zero findings. The owner exports correctly model the generated-Var contract.

## Owner layout and current pins

| Owner / consumer | Classpath declaration | Export present | Current source HEAD vs consumer pin |
| --- | --- | --- | --- |
| Millstrand | `skein-src/deps.edn:1` has `"resources"` | `skein-src/resources/clj-kondo.exports/io.millstrand/millstrand/config.edn:1` | HEAD `3c80a710`; Codethread pin `310368dff` (2026-09-07), an ancestor of HEAD |
| Millhouse Chime | `millhouse.spool/spools/chime/deps.edn:1` has `"resources"` | `.../chime/resources/clj-kondo.exports/millhouse.spools/chime/config.edn:1` | HEAD `1cca62b0`; tested / Codethread-family pin `f487eb42` (2026-08-30), an ancestor |
| Millhouse Cron | `millhouse.spool/spools/cron/deps.edn:1` has `"resources"` | `.../cron/resources/clj-kondo.exports/millhouse.spools/cron/config.edn:1` | same Millhouse HEAD/pin |
| Millhouse Workflow | `millhouse.spool/spools/workflow/deps.edn:1` has `"resources"` | `.../workflow/resources/clj-kondo.exports/millhouse.spools/workflow/config.edn:1` | same Millhouse HEAD/pin |
| Codethread config root | `spools/config/deps.edn:1` has only `"src"`; it is a consumer, not an export owner | none expected | pins Workflow at `f487eb42` in `spools/config/deps.edn:5-7`; Workflow in turn pins Millstrand `09a2cad2` at `.../workflow/deps.edn:3-6` |

Codethread's resolved standard config classpath nevertheless contains the pinned Workflow `resources` and pinned Millstrand `resources` (the latter via Workflow). It does **not** bring Chime or Cron into the standard config root: those are distinct package roots and must be direct dependencies when their macro APIs are consumed. Codethread's test alias also names Millstrand directly at `spools/config/deps.edn:22-25`.

The source checkout contains newer commits after both pins. The tested resources exist at the pinned SHAs, so this is not a source-HEAD-only result. Do not infer that a current checkout change is present in consumers without a pin bump.

## Reproducible disposable consumer evidence

Created a `mktemp -d` consumer (removed on shell exit) with direct git coordinates at exactly Codethread's Millstrand pin and Millhouse pin, with `:deps/root` respectively `.`, `spools/chime`, `spools/cron`, and `spools/workflow`.

```text
clojure -Spath | tr ':' '\n' | rg 'millstrand|millhouse.spools'
# .../io.millstrand/millstrand/310368.../resources
# .../millhouse.spools/chime/f487.../spools/chime/resources
# .../millhouse.spools/cron/f487.../spools/cron/resources
# .../millhouse.spools/workflow/f487.../spools/workflow/resources

clj-kondo --repro --lint "$(clojure -Spath)" --copy-configs --skip-lint
# Configs copied:
# - .clj-kondo/imports/io.millstrand/millstrand
# - .clj-kondo/imports/millhouse.spools/chime
# - .clj-kondo/imports/millhouse.spools/cron
# - .clj-kondo/imports/millhouse.spools/workflow
# (also transitive next.jdbc and rewrite-clj exports)
```

Before copying, linting declarations emitted six unresolved declaration names. After copy, the first fixture used `sample-rule` / `sample-rule-bang` in `use-rule!` and `sample-executor` / `sample-executor-bang` in `use-executor!`. It produced four unresolved-symbol findings. That command result is preserved as an investigation observation, but is **not a product defect**: those are not the Vars produced by the macros.

Pin-source and exported-hook inspection established the contract:

- Chime's `rule-authoring-plan` names its generated handler `(str name "-rule")` at `/Users/ct/dev/projects/millhouse.spool/spools/chime/src/millhouse/spools/chime.clj:86-103`. Its Kondo hook performs the same transformation.
- Workflow's executor plan names its handler `(str name "-stalled?")` at `/Users/ct/dev/projects/millhouse.spool/spools/workflow/src/millhouse/spools/workflow.clj:1017-1027`. Its Kondo hook does the same; the shipped selector uses `code/code-stalled?` and `shell/shell-stalled?` at `/Users/ct/dev/projects/millhouse.spool/spools/workflow/src/millhouse/spools/workflow/spool.clj:16`.

A second clean exact-pin consumer imported the same configs once, then linted all fixture declaration/use pairs with the contract-correct generated symbols: `sample-rule-rule`, `sample-rule-bang-rule`, `sample-executor-stalled?`, and `sample-executor-bang-stalled?`. Result:

```edn
{:findings [], :summary {:error 0, :warning 0, :info 0, :type :summary,
                         :duration 61, :files 1}}
```

Cron's declarations are correctly `:lint-as .../def` (`.../cron/.../config.edn:1-6`); its `use-job!` also linted cleanly. The Millstrand declaration/use pair and Workflow `defworkflow`/`use-workflow!` pair also linted cleanly.

Millstrand's owner export covers its declaration and use family directly at `skein-src/resources/clj-kondo.exports/io.millstrand/millstrand/config.edn:3-31`. Millstrand also has an in-repo disposable-consumer contract covering all of its exported families at `/Users/ct/dev/projects/skein-src/test/clojure/millstrand/quality/kondo_export_test.clj:80-244`.

## Automatic vs explicit steps

1. **Automatic after dependency resolution:** tools.deps reads each selected package-root `deps.edn`; `:paths ["resources"]` places the owner export on the runtime classpath.
2. **Explicit once per consumer bootstrap/update:** create `.clj-kondo`, then run `clj-kondo --lint "$(clojure -Spath)" --copy-configs --skip-lint`. This materializes `.clj-kondo/imports/<org>/<lib>`.
3. **Automatic after import:** clj-kondo uses `.clj-kondo/*/*/config.edn` by default. No local macro mapping is required.
4. **Recommended separately:** run `clj-kondo --lint "$(clojure -Spath)" --dependencies --parallel` to analyze dependencies and warm its cache, then lint project source normally.

Primary docs:

- clj-kondo export/import contract: https://cljdoc.org/d/clj-kondo/clj-kondo/2026.08.04/doc/configuration#exporting-and-importing-configuration
- Clojure CLI `:paths`, git SHA selection, and `:deps/root`: https://clojure.org/reference/deps_edn

## Recommended minimal follow-up

No owner-hook repair or Codethread-local mapping is warranted: the corrected exact-pin consumer has zero findings. Keep the established owner export/import model. If a future cross-repository acceptance test is added, make its fixture use the documented generated Vars (`<rule>-rule`, `<executor>-stalled?`) rather than declaration names; this prevents the same false-positive interpretation.

## Uncertainty

The experiment proves static lint behavior, not runtime macro expansion or release policy. The source checkout is ahead of consumer pins, but both pinned exports were verified present and the corrected test executed against those pins.
