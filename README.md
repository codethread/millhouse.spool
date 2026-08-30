# Millhouse spools

<p align="center">
	<img width="460" src="https://media.giphy.com/media/v1.Y2lkPTc5MGI3NjExaGZqMDBldmZ6anp0NjcybmQ2Y2s0OHlrbXhibWp1OWlvNjRiMzMzdCZlcD12MV9naWZzX3NlYXJjaCZjdD1n/bYpgM8bi7QV3i/giphy.gif">
</p>

Millhouse is the experimental arm of [Millstrand](https://codethread.github.io/millstrand/). It exposes spools under the `millhouse.spools.*` namespace.

> Stable candidates will be merged back to `millstrand`.

Read the [public documentation](https://codethread.github.io/millhouse.spool/).

| Spool                                                                                                   | Info                                                                                                                                         | Links                                                                                                                                                                                         |
| ------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [Workflow](spools/workflow/README.md) (`millhouse.spools/workflow`)                                  | One selectively activated root for the workflow engine, worker CLI, code and shell executors, and reusable Millstrand workflows.             | [contract](spools/workflow/README.md) · [workflow](spools/workflow/workflow.cookbook.md) · [code](spools/workflow/code.cookbook.md) · [shell](spools/workflow/shell.cookbook.md) · [reusable workflows API](spools/workflow/millstrand-workflows.api.md) |
| [Chime](spools/chime/README.md) (`millhouse.spools.chime`)                                              | Turn meaningful graph events into local notifications with workspace-owned rules and your preferred notifier.                                | [contract](spools/chime/README.md) · [cookbook](spools/chime/chime.cookbook.md) · [API](spools/chime/chime.api.md)                                                                            |
| [Cron](spools/cron/README.md) (`millhouse.spools.cron`)                                                 | Run durable, interval-based jobs through Millstrand's scheduler, with optional jitter and reloadable handlers.                               | [contract](spools/cron/README.md) · [cookbook](spools/cron/cron.cookbook.md) · [API](spools/cron/cron.api.md)                                                                                 |
| [Identity](spools/identity/README.md) (`millhouse.spools.identity`)                                      | Give each logical harness session a friendly identity and connect it to the runs it performs.                                                 | [contract](spools/identity/README.md) · [API](spools/identity/identity.api.md)                                                                                                             |
| [Kanban](spools/kanban/README.md) (`millhouse.spools.kanban`)                                           | Manage user–agent work as a shared board with priorities, handoffs, task dependencies, review, and optional peer-to-peer card transfer.      | [contract](spools/kanban/README.md) · [cookbook](spools/kanban/kanban.cookbook.md) · [API](spools/kanban/kanban.api.md) · [peering API](spools/kanban/kanban.peering.api.md)                  |

## Consumption

Millhouse publishes breaking releases as annotated `vN` tags. Consumers add
only the ordinary tools.deps libraries they use:

```clojure
{:deps
 {millhouse.spools/workflow
  {:git/url "https://github.com/codethread/millhouse.spool.git"
   :git/tag "v2"
   :deps/root "spools/workflow"}}}
```

`v2` is intentionally breaking: the former workflow, code-executor, shell-executor, and Millstrand-workflows roots are replaced by `millhouse.spools/workflow`. Chime and Cron retain the source repository's current quality boundary: conventions, reflection, and runtime tests cover them, while cljfmt, clj-kondo, and Splint do not.
