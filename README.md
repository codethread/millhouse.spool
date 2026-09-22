# Millhouse spools

<p align="center">
	<img width="460" src="https://media.giphy.com/media/v1.Y2lkPTc5MGI3NjExaGZqMDBldmZ6anp0NjcybmQ2Y2s0OHlrbXhibWp1OWlvNjRiMzMzdCZlcD12MV9naWZzX3NlYXJjaCZjdD1n/bYpgM8bi7QV3i/giphy.gif">
</p>

Millhouse is the experimental arm of [Millstrand](https://codethread.github.io/millstrand/). It exposes spools under the `millhouse.spools.*` namespace.

> Stable candidates will be merged back to `millstrand`.

Read the [public documentation](https://codethread.github.io/millhouse.spool/).

| Spool                                                                                                   | Info                                                                                                                                         | Links                                                                                                                                                                                         |
| ------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [Auto-run](spools/auto-run/README.md) (`millhouse.spools/auto-run`) | Opt-in feature admission, delivery explanations, blocker reporting, worktree preparation and landing handoff. | [contract](spools/auto-run/README.md) · [API](spools/auto-run/auto-run.api.md) |
| [Workflow](spools/workflow/README.md) (`millhouse.spools/workflow`)                                  | One selectively activated root for the workflow engine, worker CLI, code and shell executors, and reusable Millstrand workflows.             | [contract](spools/workflow/README.md) · [workflow](spools/workflow/workflow.cookbook.md) · [code](spools/workflow/code.cookbook.md) · [shell](spools/workflow/shell.cookbook.md) · [reusable workflows API](spools/workflow/millstrand-workflows.api.md) |
| [Chime](spools/chime/README.md) (`millhouse.spools.chime`)                                              | Turn meaningful graph events into local notifications with workspace-owned rules and your preferred notifier.                                | [contract](spools/chime/README.md) · [cookbook](spools/chime/chime.cookbook.md) · [API](spools/chime/chime.api.md)                                                                            |
| [Cron](spools/cron/README.md) (`millhouse.spools.cron`)                                                 | Run durable, interval-based jobs through Millstrand's scheduler, with optional jitter and reloadable handlers.                               | [contract](spools/cron/README.md) · [cookbook](spools/cron/cron.cookbook.md) · [API](spools/cron/cron.api.md)                                                                                 |
| [Identity](spools/identity/README.md) (`millhouse.spools.identity`)                                      | Give each logical harness session a friendly identity and connect it to the runs it performs.                                                 | [contract](spools/identity/README.md) · [API](spools/identity/identity.api.md)                                                                                                             |
| [Land](spools/land/README.md) (`millhouse.spools/land`)                                                     | Review evidence, exact-HEAD validation, strict FIFO merge turns, and safe cleanup for `origin/main` GitHub repositories.                                      | [contract](spools/land/README.md) · [cookbook](spools/land/land.cookbook.md) · [API](spools/land/land.api.md)                                                                                                            |
| [Kanban](spools/kanban/README.md) (`millhouse.spools.kanban`)                                           | Manage user–agent work as a shared board with priorities, handoffs, task dependencies, and review.      | [contract](spools/kanban/README.md) · [cookbook](spools/kanban/kanban.cookbook.md) · [API](spools/kanban/kanban.api.md)                  |

## Consumption

Millhouse publishes breaking releases as annotated `vN` tags. Consumers add only the ordinary tools.deps libraries they use:

```clojure
{:deps
 {millhouse.spools/chime
  {:git/url "https://github.com/codethread/millhouse.spool.git"
   :git/tag "v4"
   :deps/root "spools/chime"}
  millhouse.spools/cron
  {:git/url "https://github.com/codethread/millhouse.spool.git"
   :git/tag "v4"
   :deps/root "spools/cron"}
  millhouse.spools/identity
  {:git/url "https://github.com/codethread/millhouse.spool.git"
   :git/tag "v4"
   :deps/root "spools/identity"}
  millhouse.spools/land
  {:git/url "https://github.com/codethread/millhouse.spool.git"
   :git/sha "804a970a29c76b60eeba86d562e3cb3628c16b29"
   :deps/root "spools/land"}
  millhouse.spools/kanban
  {:git/url "https://github.com/codethread/millhouse.spool.git"
   :git/tag "v4"
   :deps/root "spools/kanban"}
  millhouse.spools/workflow
  {:git/url "https://github.com/codethread/millhouse.spool.git"
   :git/tag "v4"
   :deps/root "spools/workflow"}}}
```

The Workflow library contains the workflow engine, gate executors, and reusable Millstrand workflows.

## Development

Each spool and `.millstrand` is an independent tools.deps project. Configure
your editor to select the nearest `deps.edn`; the repository intentionally has
no aggregate clojure-lsp configuration. The root project owns repository
scripts and cross-spool integration tests.

Install native `clj-kondo` v2026.08.04. `make lint-clj` refreshes dependency
exports and lints every project independently; `make clean-kondo` removes only
generated imports and caches. Run `make quality` before landing changes.

Follow the shared [Clojure lint and editor configuration](https://github.com/codethread/codethread.spool/blob/main/docs/processes/kondo-and-lsp.md) when refreshing static-analysis configuration.
