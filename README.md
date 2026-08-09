# Millhouse spools

<p align="center">
	<img width="460" src="https://media.giphy.com/media/v1.Y2lkPTc5MGI3NjExaGZqMDBldmZ6anp0NjcybmQ2Y2s0OHlrbXhibWp1OWlvNjRiMzMzdCZlcD12MV9naWZzX3NlYXJjaCZjdD1n/bYpgM8bi7QV3i/giphy.gif">
</p>

Millhouse is the experimental arm of Millstrand. It owns the six external spools extracted from Millstrand under the `millhouse.spools.*` namespace family.

Read the [public documentation](https://codethread.github.io/millhouse.spool/).

| Root | Namespace | Documentation |
| --- | --- | --- |
| `spools/workflow` | `millhouse.spools.workflow` | [contract](spools/workflow/README.md) · [cookbook](spools/workflow/workflow.cookbook.md) · [API](spools/workflow/workflow.api.md) |
| `spools/chime` | `millhouse.spools.chime` | [contract](spools/chime/README.md) · [cookbook](spools/chime/chime.cookbook.md) · [API](spools/chime/chime.api.md) |
| `spools/cron` | `millhouse.spools.cron` | [contract](spools/cron/README.md) · [cookbook](spools/cron/cron.cookbook.md) · [API](spools/cron/cron.api.md) |
| `spools/code-executor` | `millhouse.spools.executors.code` | [contract](spools/code-executor/README.md) · [cookbook](spools/code-executor/code.cookbook.md) · [API](spools/code-executor/code.api.md) |
| `spools/shell-executor` | `millhouse.spools.executors.shell` | [contract](spools/shell-executor/README.md) · [cookbook](spools/shell-executor/shell.cookbook.md) · [API](spools/shell-executor/shell.api.md) |
| `spools/kanban` | `millhouse.spools.kanban` | [contract](spools/kanban/README.md) · [cookbook](spools/kanban/kanban.cookbook.md) · [API](spools/kanban/kanban.api.md) · [peering API](spools/kanban/kanban.peering.api.md) |

Each entry is an independent root with its own `deps.edn`, `src`, and spool-owned `test` tree. Repository-level consumer, discovery, integration, and shared test-support code remains under the top-level `test` tree. The executor roots require this family's Workflow root and must activate after it.

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
           millhouse.spools.executors/shell "spools/shell-executor"
           millhouse.spools/kanban "spools/kanban"}}}}
```

There is no `:git/tag` until this family publishes a release marker. Chime and Cron retain the source repository's current quality boundary: conventions, reflection, and runtime tests cover them, while cljfmt, clj-kondo, and Splint do not.

## Testing

The default suite requires namespaces serially, then runs them concurrently with isolated output and summaries:

```text
clojure -M:test
clojure -M:test --serial
clojure -M:test millhouse.spools.workflow-test
clojure -M:test --stress 10
```

Focused runs are serial. Stress mode launches each parallel iteration in a fresh JVM so loaded fixture namespaces and other JVM-global state cannot leak between repetitions. Namespaces proven to require JVM-global isolation belong in the runner's documented serial island.
