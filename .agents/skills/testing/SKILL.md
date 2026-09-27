---
name: testing
description: Use when choosing test layers, writing or reviewing tests, or running and gating them — the testing pyramid, supported Millstrand fixtures, isolation, and evidence boundaries.
---

# Testing

## Choose the cheapest sufficient proof

Use the standard testing pyramid: many focused unit/direct tests, fewer
integration tests, and narrow process E2E proofs. Choose by the behavior an
assertion proves, not the fixture a nearby test happens to use. Reassess existing
tests and fixtures you change; pre-existing cost is not a reason to retain it.
Keep cleanup scoped to that work rather than requesting unrelated refactors.
The workspace's `test-layering` reviewer checks this boundary; select it with
`strand agent review --agent test-layering` and inspect the returned run's result.

```text
Pure input/output or declaration data? -> Unit/direct authoring test
Caller-visible API with runtime state? -> Direct runtime test
Startup, publication, transport, events or reload? -> Embedded Weaver world
Built CLI/process topology or generation adoption? -> Process E2E
```

Use `millstrand.test.alpha` (aliased below as `t`) and blessed
`millstrand.api.*.alpha` APIs. Millstrand's repository-only test helpers and
`millstrand.core.*` internals are not downstream fixture APIs.

### Unit and direct tests

- Use ordinary Clojure tests for pure behavior. `t/collect-module-forms` collects
  authoring declarations as data without starting a world; it does not prove
  publication, reconciliation, source loading, or dependency resolution.
- For runtime-backed API contracts, pass an explicit unpublished runtime to the
  blessed APIs; use `millstrand.api.current.alpha/with-runtime` when the caller
  uses runtime-bound APIs. `t/activate-module!` activates an already-classpath-
  visible namespace on an existing bare runtime. It does not prove
  startup-file/dependency loading.
- On a core revision exporting `t/run-with-bare-runtime`, use that public fixture
  for direct runtime-state contracts. Its closed options are `:storage`
  (`:sqlite-file` by default or `:sqlite-memory`) and `:name`. The callback
  receives explicit `:runtime`, `:config-dir`, `:data-dir`, `:state-dir`, and
  `:storage`; file storage also provides `:db-path`. Each invocation owns fresh
  state and cleanup, performs no per-world dependency resolution, and does not
  mutate ambient runtime selection. It supports plain-Java test JVM launches;
  no new Clojure CLI basis requirement is part of this contract.
- Check the available fixture before choosing it. Use the bare fixture for
  classpath-visible activation and direct runtime behavior; use the Weaver-world
  fixture for startup files, workspace dependency resolution, full refresh, and
  durable reopen. Use separate disposable process fixtures for process topology
  and replacement-generation claims. Do not invent a constructor or import
  upstream private test helpers when the consumer's pinned core does not yet
  export the promoted fixture.

### Embedded Weaver worlds

`t/with-weaver-world [ctx opts]`, `t/run-with-weaver-world opts f`, and
`t/weaver-world-fixture` own startup, shutdown, and disposable workspace cleanup.
The context exposes `:runtime` and workspace paths. Use this tier for real
startup/config, module publication, dependency bases, transports, storage
integration, events, scheduling, and refresh.

- Declare `:deps-edn`, optional `:deps-local-edn`, `:init-clj`, optional
  `:init-local-clj`, and workspace-relative `:files` when those inputs are the
  subject. Dependency presence does not activate modules. `t/spool-checkout-root`
  locates an ordinary `:local/root` dependency; it does not load or activate it.
- `t/repl!` evaluates forms over the world's real nREPL transport. Calling an API
  directly does not prove transport behavior. `t/declare-module!`,
  `t/refresh-modules!`, `t/plan-modules`, and `t/module-status` exercise module
  lifecycle through the public runtime API.
- Choose storage separately from the tier. `:storage :sqlite-memory` uses real
  Xerial SQLite with one held connection: suitable for serialized, non-durable
  contracts, not file persistence or multi-connection contention evidence.
  `:sqlite-file` is the default; retain it for durability/reopen, filesystem, or
  connection-topology claims. Use file SQLite when background executors or
  scheduled work can access the database concurrently with the test body; a
  sequential test body does not make those actors serialized. Memory storage
  still pays world/basis startup.
- For time, use `t/manual-clock`, `t/set-clock!`, and `t/advance!`.
  `t/await-quiescent!` settles the event lane, not work dispatched off it; join
  that work with its own completion signal or established bounded await helper.

### Process E2E

Use repository-built `mill`/`strand` and separate disposable supervisor/Weaver
processes only for claims requiring that topology. Embedded worlds can observe
`:restart-required`, but only replacement-process evidence proves adoption of a
changed dependency basis. Two embedded runtimes over a retained file database
prove durable reopen, not process replacement. Preserve those distinct proofs.

## Isolation and evidence gates

Tests use `:publish? false`, explicit runtimes, and disposable worlds—not the
shared `.millstrand` world. Ordinary suite commands need no `--workspace`.
An explicit fixture `:root` is retained by default; its caller owns cleanup.
Stop and wait for only the exact PIDs owned by a process fixture. Never restart a
shared Weaver as part of a test.

| When | What |
| --- | --- |
| Iterate / slice Done-when | `clojure -M:test <ns…>` |
| Inspect affected selection | `make test-plan` (override with `TEST_BASE=feature/parent` for stacks) |
| Local quality gate | `flock -w 180 /tmp/millstrand-test.lock make quality` |
| Queue acceptance | `.millstrand/land-quality.sh` (owns the lock; do not nest it) |
| Explicit full validation | `flock -w 180 /tmp/millstrand-test.lock make quality-full` |

Focused tests need no lock. Warm REPL iteration and focused runs are not landing
gates. `MILLSTRAND_TEST_AWAIT_SCALE` scales await budgets (CI uses 3), not test
layers. After spool/API docstring changes run `make api-docs`; after validation,
`git status --short` must show no generated SQLite or runtime metadata artifacts.

For deeper fixture contracts, use the pinned Millstrand API docs and the
[consumer testing guide](https://github.com/codethread/millstrand/blob/main/docs/spools/testing.md).
`mill prime millstrand` locates the installed source/reference; its
`devflow/specs/testing.md` owns the upstream tier contract, not this skill.
