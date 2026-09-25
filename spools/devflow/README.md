# devflow.spool

An opinionated **feature-delivery lifecycle**, built on [Millhouse workflows](https://github.com/codethread/millhouse.spool/tree/f80b80c8697e48a6ce56344372a32136d2bf279c/spools/workflow) and shipped as a git-distributed spool for [Millstrand](https://github.com/codethread/millstrand).

You give it a feature name. It walks you and your agents from "here's a rough
idea" to either **reviewed implementation cards on mainline** or **accepted,
implemented code** — and leaves a documentation trail behind in the repo.

```sh
strand workflow start search-filters --workflow intake \
  --params '{"feature":"search-filters"}'
# => next up: create the worktree
```

Everything is keyed by that feature name — it *is* the `workflow/run-id`, so
there is no separate run handle to keep.

## What you get out of it

> **Code tells you *what*. Devflow docs tell you *why*.**

```
devflow/
|-- rfcs/YYYY-MM-DD-<slug>.md
|-- specs/<spec-name>.md              <- canonical: how the system behaves
|-- feat/<feature>/
|   |-- proposal.md                   <- why, agreed before any code
|   |-- specs/<spec-name>.delta.md    <- how this feature changes the above
|   `-- <feature>.plan.md
`-- archive/yy-mm-dd__<feature>/      <- every past feature, proposals and deltas
```

Tasks and implementation cards are not files: the shipped default authors them as **strands in the Millstrand graph** (`strand ready --query devflow-tasks`), and workspaces can plug in their own card system instead.

- **`proposal.md` is plan mode, written down.** It comes before any code and the
  run stops for human sign-off, so there's time to align with the agent while
  changing your mind is still cheap. Approval freezes it as the record of what
  was agreed.
- **`specs/` + `<spec>.delta.md` keep the docs with the code.** `specs/` is
  canonical for the system's observable behaviours; every feature states its
  change as a delta beside its proposal, reviewable as a diff against the current
  contract. Those deltas merge into the canonical specs when the feature
  completes — updating the docs *is* how a feature finishes.
- **`archive/` holds every feature that came before**, proposals and deltas
  intact: why the current specs say what they say.
- **Stable ids throughout** — `PROP-Sfl-001`, `PLAN-Sfl-001.P1` — so you can
  point an agent at one exact paragraph in chat, and still find it by the same
  search years later.

The rules for writing each document ship with the spool as markdown guides:
`strand devflow guidance proposal` (or `(devflow/guidance :proposal)` from
Clojure) returns its purpose, prerequisites, procedure, constraints, checklist,
and markdown template. No external skill file needed.

## How it gets there

- **A staged lifecycle.** Intake → proposal → sign-off →
  (cards | spec+plan → tasks → execution), each stage its own workflow
  definition.
- **Two working models.** Either hand the feature off to a card loop (each card
  worked cold by its own agent), or drive spec/plan/tasks/implementation inside
  the one run.
- **Pluggable decomposition.** Task and card authoring are `workflow/defer`
  selection points. The shipped targets author strands; bind your own
  (GitHub issues, Jira, ...) against the published `tasks-open` /
  `decompose-open` templates without touching devflow.
- **Revision loops that don't waste work.** "Revise" re-runs a stage and skips
  the setup steps it already did.
- **Delegated review and execution.** Card reviews fan out to tracked headless agents
  (focused per-card reviews, then one set-level cohesion review); approved task
  queues can run as sequential `:agent` gates.
- **An abort path from every human decision point**, with a required reason.

👉 **[devflow.md](./devflow.md) is the guide** — the documents, the stage flows,
and how to drive a run.

## Install

### Dependencies

Select a tested Millhouse revision as described in [consumption](../../README.md#consumption).
Use `millhouse/devflow` with `:deps/root "spools/devflow"`. The optional
`millhouse/devflow-kanban-adapter` uses `:deps/root "spools/devflow/kanban-adapter"`.
Both retain their original namespace and coordinate. Devflow itself has no
Kanban dependency. Agent gates require an explicitly activated Harnesses
executor; the opt-in Codethread bootstrap is one way to compose it.

### Activate the modules

From trusted `init.clj` or REPL code:

```clojure
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime]
         '[millhouse.config.bootstrap :as codethread])

(def runtime (current/runtime))

(codethread/register! runtime)

;; Consumer modules follow the shared catalog. Keep this order so all aliases,
;; elections, and workflows exist before the executor's first ready-gate scan.
(runtime/module! runtime
  :millstrand/spools-batteries
  {:ns 'millstrand.spools.batteries
   :required? true})

(runtime/module! runtime
  :millhouse/workflow-providers
  {:ns 'millhouse.workflow.spool
   :after [:millhouse/workflow]
   :required? true})

(runtime/module! runtime
  :millhouse/kanban
  {:ns 'millhouse.kanban
   :required? true})

(runtime/module! runtime
  :devflow
  {:ns 'millhouse.devflow
   :after [:millhouse/workflow]
   :required? true})

(runtime/module! runtime
  :devflow/kanban-adapter
  {:ns 'millhouse.devflow-kanban-adapter
   :after [:devflow :millhouse/kanban :millhouse/workflow]
   :required? true})

(runtime/module! runtime
  :millhouse/config-help
  {:ns 'millhouse.config.help
   :after [:millstrand/spools-batteries]
   :required? true})

(runtime/module! runtime
  :millhouse/config-devflow
  {:ns 'millhouse.config.devflow
   :required? true})

(runtime/module! runtime
  :millhouse/config
  {:ns 'millhouse.config
   :after [:millhouse/config-help
           :millhouse/config-devflow
           :millstrand/spools-batteries
           :devflow/kanban-adapter]
   :required? true})

(runtime/module! runtime
  :devflow/reviewers
  {:file "me/reviewers.clj"
   :after [:millhouse/config]
   :required? true})

;; This is the only :agent executor. Activate it after all consumer modules.
(codethread/register-executor!
 runtime
 [:millhouse/workflow-providers
  :millhouse/kanban
  :devflow
  :devflow/kanban-adapter
  :millhouse/config
  :devflow/reviewers])
```

`codethread/register!` supplies shared identity, Workflow, Harnesses, role
aliases, and review lenses without activating an executor. Consumer modules then
register their own Batteries, provider, card, Devflow, adapter, and workspace
config surfaces. The final `codethread/register-executor!` call activates the
sole Harnesses `:agent` executor and takes the consumer module ids that must
reconcile before its initial scan. Consumers that do not use the adapter may omit it and its corresponding
`:after` entries. Ralph is superseded by Auto-run and is not shipped. There is
no `spool`, `contribute`, or `reconcile` Var to call.

### Check it worked

The generic workflow CLI discovers and drives Devflow. `intake` is its sole
startable definition; the other stages are routed or callable components:

```sh
strand workflow list
strand workflow list --entrypoint continue
strand workflow show intake
strand devflow guidance
```

## Using it

Devflow is a set of ordinary Millstrand workflows. Start and drive the `intake` workflow through the generic surface:

```sh
strand workflow start search-filters --workflow intake \
  --params '{"feature":"search-filters","worktree-check":"already-in-worktree-ok"}'
strand workflow ready search-filters
strand workflow next search-filters --choice already-in-worktree --input \
  '{"repository":"/path/to/repo","worktree":"/path/to/feature","branch":"feature"}'
```

Resume and find work across sessions with Devflow's named queries:

```sh
strand list --query devflow-runs
strand ready --query devflow-ready
strand ready --query devflow-tasks
```

`strand devflow guidance [<guide>]` serves the static authoring knowledge
(`(devflow/guidance)` is its Clojure twin). See [devflow.md](./devflow.md) for
lifecycle flows and generic workflow commands.

## Development

Follow the shared [Kondo and Clojure LSP process](https://github.com/codethread/millhouse.spool/blob/main/docs/processes/kondo-and-lsp.md) when refreshing static-analysis configuration.
