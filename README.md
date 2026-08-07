# Millhouse spools

Millhouse is the experimental arm of Millstrand. It carries five spools copied from the main repository under the `millhouse.spools.*` namespace family. Their in-tree originals remain in place for the consumer cutover.

| Root | Namespace | Documentation |
| --- | --- | --- |
| `spools/workflow` | `millhouse.spools.workflow` | [contract](spools/workflow/README.md) · [cookbook](spools/workflow/workflow.cookbook.md) · [API](spools/workflow/workflow.api.md) |
| `spools/chime` | `millhouse.spools.chime` | [contract](spools/chime/README.md) · [cookbook](spools/chime/chime.cookbook.md) · [API](spools/chime/chime.api.md) |
| `spools/cron` | `millhouse.spools.cron` | [contract](spools/cron/README.md) · [cookbook](spools/cron/cron.cookbook.md) · [API](spools/cron/cron.api.md) |
| `spools/code-executor` | `millhouse.spools.executors.code` | [contract](spools/code-executor/README.md) · [API](spools/code-executor/code.api.md) |
| `spools/shell-executor` | `millhouse.spools.executors.shell` | [contract](spools/shell-executor/README.md) · [cookbook](spools/shell-executor/shell.cookbook.md) · [API](spools/shell-executor/shell.api.md) |

Each entry is an independent root with its own `deps.edn` and `src` tree. The executor roots require this family's Workflow root and must activate after it.

## Consumption

Millhouse is untagged work in progress. Consumers pin one commit and select the roots they approve:

```clojure
{:spools
 {millhouse/spools
  {:git/url "https://github.com/codethread/millhouse.spool.git"
   :git/sha "<40-lowercase-hex>"
   :roots {millhouse.spools/workflow "spools/workflow"
           millhouse.spools/chime "spools/chime"
           millhouse.spools/cron "spools/cron"
           millhouse.spools.executors/code "spools/code-executor"
           millhouse.spools.executors/shell "spools/shell-executor"}}}}
```

There is no `:git/tag` until this family publishes a release marker. Chime and Cron retain the source repository's current quality boundary: conventions, reflection, and runtime tests cover them, while cljfmt, clj-kondo, and Splint do not.
