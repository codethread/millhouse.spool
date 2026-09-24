# Codethread configuration

An opt-in shared bootstrap, agent/reviewer catalog, help, and Devflow election.
This is not a Millstrand core default. Install `codethread/config` from the
Millhouse revision you selected, with `:deps/root "spools/config"`; see
[consumption](../../README.md#consumption). No Ralph code is included.

## Activation

Register the shared agent and landing surface before consumer-specific configuration:

```clojure
(require '[ct.spools.codethread.bootstrap :as codethread])
(codethread/register! runtime)
```

`register!` owns ordering for the Harnesses providers and command surface, the shared aliases and reviewer lenses, and the shared Millhouse landing workflow. It deliberately does not activate the asynchronous Workflow `:agent` executor. Register repository-specific aliases, flags, and workflows next, then activate the executor last:

```clojure
(codethread/register-executor! runtime [:consumer/aliases
                                        :consumer/workflows])
```

The optional second argument adds explicit `:after` edges for consumer modules that must reconcile before the executor's initial scan of restored ready gates. `(codethread/register-executor! runtime)` is sufficient when there are no consumer modules to name. The stable executor module id is `:millstrand/spools-agent-executor`; the bootstrap owns that module, so consumers must not register its namespace separately.

The stable catalog module ids, in order, are `:millhouse/spools-identity`, `:millhouse/spools-workflow`, `:millhouse/spools-kanban`, `:millstrand/spools-harnesses`, `:codethread/config-agents`, `:codethread/config-reviewers`, and `:millhouse/spools-land`. Kanban activates before Harnesses because assignment prompts consume Kanban's current-ownership projections. Repository-specific workflows are not activated by the catalog bootstrap.

Consumers that need landing without the Codethread agent catalog can depend on the independent `millhouse.spools/land` root and register `millhouse.spools.land.spool` after Millhouse Workflow and Kanban. The root does not depend on Harnesses provider code; its reviewer seat is ordinary workflow data, and the consumer supplies the `:agent` executor.

The preferred role aliases are `grunt`, `luna`, `oracle`, `reviewer`, and `tui`. `grunt` prefers the `deepseek` (`deepseek-v4-flash`) seat and falls back to `luna`; the `seat/allow-china` flag defaults true and makes every DeepSeek-powered seat unavailable when set false. Shared reviewer lenses select `grunt` first while retaining their existing role fallbacks. The delegated- coordination aliases `coordinator`, `sub-coordinator`, and `sub-coordinator-sol` are not elected by the catalog while they remain under test. The bounded `sub-coordinator` intentionally remains Luna-first because it is a dedicated coordination role with a provider-neutral runbook and explicit Terra fallback, not a mechanical implementation seat. Register the two sub-coordinator aliases on demand through the additive seams in `ct.spools.codethread.sub-coordinator`. The bounded `sub-coordinator` carries its complete runbook as supported alias system guidance. See the [rollout procedure](../../docs/processes/sub-coordinator-rollout.md) for its Codex handoff, Terra/high fallback, and additive live-registration proof without runtime mutation.

Claude and Cursor are registered but disabled by default, matching the Harnesses workspace policy. A consumer can explicitly enable them after startup with `strand agent config set harness/claude true` or the equivalent Cursor flag. This is process-local configuration.

Inspect the resulting surface with:

```text
strand agent list
strand agent reviewers
strand workflow list
strand workflow show land
strand help merge-queue
```

The rollout smoke evaluates actual consumer `.millstrand/deps.edn`, `.millstrand/init.clj`, and referenced workspace files in disposable in-memory worlds. It applies local dependency overrides only inside those worlds and checks module activation, the queue command, mandatory basic review, and all three landing definitions:

```text
cd spools/config
clojure -M:consumer-smoke MILLHOUSE CONSUMER...
```

The first path identifies the consolidated Millhouse checkout. Pass consumer checkouts as remaining arguments. The smoke never starts or mutates a canonical Weaver.

The optional `ct.spools.codethread.config` module still selects this repository's Batteries help rendering and the external Devflow Kanban adapter. Consumers that want it must activate Batteries, Devflow, and the adapter first; Kanban is already part of the bootstrap. This election stays outside the bootstrap so a catalog consumer does not inherit Devflow workflows merely by selecting shared agents.

Headless Workflow gates use waiter `:agent` with `harness/alias` and an optional `harness/prompt` and `harness/cwd`. The executor creates a tracked run and closes the gate only after it delivers a successful non-blank result. Waiting is done through `strand await` queries, not an `agent await` command.

Opt-in [automatic card pickup](../../docs/processes/auto-run.md) is also available through the config root's `millhouse.spools/auto-run` dependency. Repositories supply delivery workflows, worker defaults, worktree preparation, and a concurrency limit; the dispatcher assigns labelled, ready features once without a coordinator agent. It is not activated by the shared bootstrap.


## Checks

Run `make check` in this package. Tests use disposable Weaver worlds. The
repository workspace checks run centrally at the Millhouse root.
