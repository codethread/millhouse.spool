# Millhouse spools

<p align="center">
	<img width="460" src="https://media.giphy.com/media/v1.Y2lkPTc5MGI3NjExaGZqMDBldmZ6anp0NjcybmQ2Y2s0OHlrbXhibWp1OWlvNjRiMzMzdCZlcD12MV9naWZzX3NlYXJjaCZjdD1n/bYpgM8bi7QV3i/giphy.gif">
</p>

Millhouse is the experimental arm of [Millstrand](https://codethread.github.io/millstrand/). It exposes spools under the `millhouse.spools.*` namespace.

> Stable candidates will be merged back to `millstrand`.

Read the [public documentation](https://codethread.github.io/millhouse.spool/).

| Spool                                                                                                   | Info                                                                                                                                         | Links                                                                                                                                                                                         |
| ------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [Workflow](spools/workflow/README.md) (`millhouse.spools.workflow`)                                     | Define durable, Clojure-native workflows that agents can drive step by step, including human or external-system gates and routing decisions. | [contract](spools/workflow/README.md) · [cookbook](spools/workflow/workflow.cookbook.md) · [API](spools/workflow/workflow.api.md)                                                             |
| [Workflow Code executor](spools/code-executor/README.md) (`millhouse.spools.executors.code`)            | Let a workflow gate invoke trusted Clojure functions inside the weaver, with timeouts and explicit recovery.                                 | [contract](spools/code-executor/README.md) · [cookbook](spools/code-executor/code.cookbook.md) · [API](spools/code-executor/code.api.md)                                                      |
| [Workflow Shell executor](spools/shell-executor/README.md) (`millhouse.spools.executors.shell`)         | Let a workflow gate run a process with bounded output, timeouts, and durable failure details.                                                | [contract](spools/shell-executor/README.md) · [cookbook](spools/shell-executor/shell.cookbook.md) · [API](spools/shell-executor/shell.api.md)                                                 |
| [Millstrand workflows](spools/millstrand-workflows/README.md) (`millhouse.spools.millstrand-workflows`) | Reuse guided workflows for publishing clj-kondo support and adopting or updating approved spool families.                                    | [contract](spools/millstrand-workflows/README.md) · [cookbook](spools/millstrand-workflows/millstrand-workflows.cookbook.md) · [API](spools/millstrand-workflows/millstrand-workflows.api.md) |
| [Chime](spools/chime/README.md) (`millhouse.spools.chime`)                                              | Turn meaningful graph events into local notifications with workspace-owned rules and your preferred notifier.                                | [contract](spools/chime/README.md) · [cookbook](spools/chime/chime.cookbook.md) · [API](spools/chime/chime.api.md)                                                                            |
| [Cron](spools/cron/README.md) (`millhouse.spools.cron`)                                                 | Run durable, interval-based jobs through Millstrand's scheduler, with optional jitter and reloadable handlers.                               | [contract](spools/cron/README.md) · [cookbook](spools/cron/cron.cookbook.md) · [API](spools/cron/cron.api.md)                                                                                 |
| [Identity](spools/identity/README.md) (`millhouse.spools.identity`)                                         | Give each logical harness session a friendly identity and connect it to the runs it performs.                                                   | [contract](spools/identity/README.md)                                                                                                                                                      |
| [Kanban](spools/kanban/README.md) (`millhouse.spools.kanban`)                                           | Manage user–agent work as a shared board with priorities, handoffs, task dependencies, review, and optional peer-to-peer card transfer.      | [contract](spools/kanban/README.md) · [cookbook](spools/kanban/kanban.cookbook.md) · [API](spools/kanban/kanban.api.md) · [peering API](spools/kanban/kanban.peering.api.md)                  |

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
           millhouse.spools/kanban "spools/kanban"
           millhouse.spools/identity "spools/identity"
           millhouse.spools/millstrand-workflows "spools/millstrand-workflows"}}}}
```

There is no `:git/tag` until this family publishes a release marker. Chime and Cron retain the source repository's current quality boundary: conventions, reflection, and runtime tests cover them, while cljfmt, clj-kondo, and Splint do not.
