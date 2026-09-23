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
| `millhouse.spools.auto-run-recovery` | Register accepted recovery workers and verify frozen settlement evidence. |

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

The public `autonomous-land` call keeps its parameters. New pours record review,
handoff preparation, acceptance and worker release, followed by settlement,
verification, signoff and landing observation. A separate `auto-run/role=finisher`
custody anchor remains open across that entire finisher sequence; the same run
serves it until verified delivery. Existing stored runs are not migrated.

`auto-run register-worker` is coordinator-only policy for explicitly authorized
recovery, not another launch or an authorization mechanism. It requires the
supported Harnesses `call-with-run-publication-lock` API; dependency activation
must be ordered before using it. It checks a unique accepted continuation path,
every predecessor settled, same task/root/worktree, expected current-worker receipt and
no frozen/accepted finisher. Exact request replay does not mutate anything.
See [repository recovery examples](../../docs/auto-run.md#authorized-recovery).
This library and the Millhouse workspace pin Harnesses
`4ac638d679bc238fd8a373d52c3dbf2a7f682be0`, which provides that API. Other
repositories' pin updates and production activation remain separately coordinated.

Run the focused library tests with `clojure -M:test` from this directory.
The repository quality gate includes this root's formatting, lint, reflection,
documentation and disposable Weaver tests.
