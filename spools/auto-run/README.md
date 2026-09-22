# Auto-run

`millhouse.spools/auto-run` publishes opt-in Kanban feature admission into
repository-owned workflows. It depends on Harnesses, Kanban, Workflow and Land;
it does not depend on Codethread configuration or activate any dispatcher.

The public namespaces are:

| Namespace | Purpose |
| --- | --- |
| `millhouse.spools.auto-run` | Configure, stop, scan, inspect and explain admission. |
| `millhouse.spools.auto-run-reporting` | Declare blocker patterns and derived labels. |
| `millhouse.spools.auto-run-worktree` | Prepare a worktree through wktree policy. |
| `millhouse.spools.auto-run-land` | Compose worker and finisher landing handoffs. |
| `millhouse.spools.auto-run-explain` | Collect and classify delivery evidence. |

Add this root through a pinned Git dependency with `:deps/root "spools/auto-run"`.
Codethread's config root already includes it. Repository modules explicitly
select the operation, reporting patterns, workflows and lifecycle configuration;
no default seat, cadence, workflow, concurrency or automatic activation is
introduced by this library.

See the [activation and delivery contract](https://github.com/codethread/codethread.spool/blob/main/docs/processes/auto-run.md)
and the [API](auto-run.api.md).

The extraction preserves persisted `auto-run/*` attributes, the scheduler key
`codethread/auto-run`, and the `codethread.auto-run.explain/v1` result schema.
Consumers change imports and pins; existing delivery receipts and repository
configuration retain their meaning. Changed pins require a new Weaver generation.

Run the focused library tests with `clojure -M:test` from this directory.
The repository quality gate includes this root's formatting, lint, reflection,
documentation and disposable Weaver tests.
