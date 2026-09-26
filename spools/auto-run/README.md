# Auto-run

`millhouse/auto-run` publishes opt-in Kanban feature admission into
repository-owned workflows. It depends on Harnesses, Kanban, Workflow and Land;
it does not depend on Millhouse configuration or activate any dispatcher.

The public namespaces are:

| Namespace | Purpose |
| --- | --- |
| `millhouse.auto-run` | Configure, stop, scan, inspect and explain admission. |
| `millhouse.auto-run-reporting` | Declare blocker patterns and derived labels. |
| `millhouse.auto-run-worktree` | Prepare a worktree through wktree policy. |
| `millhouse.auto-run-land` | Compose worker and finisher landing handoffs. |
| `millhouse.auto-run-explain` | Collect and classify delivery evidence. |
| `millhouse.auto-run-recovery` | Register accepted recovery workers and verify frozen settlement evidence. |

Add this root through a pinned Git dependency with `:deps/root "spools/auto-run"`.
Millhouse's config root already includes it. Repository modules explicitly
select the operation, reporting patterns, workflows and lifecycle configuration;
no default seat, cadence, workflow, concurrency or automatic activation is
introduced by this library.

See the [activation and delivery contract](https://github.com/codethread/millhouse.spool/blob/main/docs/processes/auto-run.md)
and the [API](auto-run.api.md).

The extraction preserves persisted `auto-run/*` attributes, the scheduler key
`millhouse/auto-run`, and the `millhouse.auto-run.explain/v1` result schema.
Consumers change imports and pins; existing delivery receipts and repository
configuration retain their meaning. Changed pins require a new Weaver generation.

The public `autonomous-land` call keeps its parameters. New pours record review,
handoff preparation, acceptance and worker release, followed by settlement,
verification, signoff and landing observation. A separate `auto-run/role=finisher`
custody anchor remains open across that entire finisher sequence; the same run
serves it until verified delivery. Existing stored runs are not migrated.

Repository delivery workflows can use `validation-failure-policy` for implementation
and pre-review quality/CI gates: agents diagnose and repair their own scoped work,
including assigned flaky-test repairs, while unrelated flakes, infrastructure and
unknown failures require intervention. This is agent guidance, not an automated
log classifier. It requires durable failure evidence, settled shell custody and
fresh quality/CI at the repaired revision. `failure-policy` retains the separate
explicit recovery boundary for handoff and landing; repair authority cannot bypass
executor results, recipe refusals, finisher custody or queue ownership.

`auto-run register-worker` is coordinator-only policy for explicitly authorized
recovery, not another launch or an authorization mechanism. It requires the
supported Harnesses `call-with-run-publication-lock` API; dependency activation
must be ordered before using it. It checks a unique accepted continuation path,
every predecessor settled, same task/root/worktree, expected current-worker receipt and
no frozen/accepted finisher. Exact request replay does not mutate anything.
See [repository recovery examples](../../docs/auto-run.md#authorized-recovery).
This library and the Millhouse workspace select the local Harnesses package at
the same Millhouse revision. External consumers composing multiple Git roots
use the [generated dependency closure](../../README.md#consumption), not a
separate Harnesses repository pin. Production activation remains separately
authorized.

Run the focused library tests with `clojure -M:test` from this directory.
The repository quality gate includes this root's formatting, lint, reflection,
documentation and disposable Weaver tests.
