# Root-suite fixtures

Choose the cheapest sufficient proof by assertions, not the nearest fixture.
Independent packages keep their own fixtures and classpaths; this directory is
root-suite development infrastructure, not a dependency to add to a spool.

See the [Workflow proof ownership map](../spools/workflow/test/README.md) for the
compiler, authoring, spec, runtime, composition and publication partitions.

## Supported choices

- **Pure/declaration:** ordinary `clojure.test`; use
  `millstrand.test.alpha/collect-module-forms` to inspect public selections as
  data. This proves neither publication nor startup. See `declared-op` in
  [the Workflow CLI pilot](../spools/workflow/test/millhouse/workflow_cli_test.clj).
- **Runtime-backed:** `millhouse.test-support/with-embedded-runtime` takes a
  callback `(fn [runtime config-dir-file] ...)`, optionally preceded by
  `{:prefix "test-name" :storage :sqlite-memory}`. It binds the current runtime,
  starts a full unpublished Weaver world, and owns shutdown and generated
  workspace cleanup, including when the callback throws. Each call gets fresh
  database, runtime, workspace and registry state. It activates no modules.
- **Existing callers:** `with-runtime` is the same full-world fixture, with the
  same options. Both default to `:sqlite-file`; no existing caller is silently
  switched to memory. `activate-spool!` retains the shared namespace-activation
  lock; do not remove that isolation to save time.
- **Startup/reload/durable reopen:** use upstream `with-weaver-world` directly
  for `:deps-edn`, activation files, `:files` or retained roots. An explicit
  `:root` is retained by default; the caller owns cleanup. Two embedded runtimes
  over one file database prove reopen, not replacement-process adoption.
- **Process topology:** keep real disposable process fixtures for built CLI or
  generation-adoption claims. Direct function calls are not transport evidence.

Storage is independent of the proof tier. Opt into `:sqlite-memory` for
serialized, non-durable contracts only. It is real SQLite with a held connection,
not file persistence or multi-connection contention evidence. It still pays
world and dependency-basis startup. Use `:sqlite-file` for those other claims.
Unknown local options and unsupported storage values fail rather than falling
back. For the complete upstream options, use its public fixture directly.

## Pinned API limitation

At Millstrand `34f940ddb2e69898554bf76251749715b250ae15`,
`millstrand.test.alpha/activate-module!` requires an existing runtime;
`millstrand.api.current.alpha/with-runtime` only binds one. The public runtime
API has no constructor. The supported world fixture creates a generation basis
and starts Weaver; there is no supported downstream bare-runtime constructor
with deterministic teardown independent of world/basis startup. Do not copy
upstream repository helpers or import `millstrand.core.*` to fill that gap.

## Public bare-runtime fixture promotion addendum

The limitation above records the pinned pre-promotion API. After adopting a
landed Millstrand revision that exports
`millstrand.test.alpha/run-with-bare-runtime`, direct runtime contracts may use
that public fixture instead of starting a Weaver world:

```clojure
(t/run-with-bare-runtime
 {:storage :sqlite-file :name "runtime-contract"}
 (fn [{:keys [runtime config-dir data-dir state-dir storage db-path]}]
   ...))
```

The options map is closed to `:storage` (`:sqlite-file` by default or
`:sqlite-memory`) and `:name`. The callback receives the explicit `:runtime`,
`:config-dir`, `:data-dir`, `:state-dir`, and `:storage`; file storage also
provides `:db-path`. Each invocation owns fresh runtime state, SQLite storage,
registries, and temporary paths, and cleans them up after success or failure.
The fixture reuses the test JVM classpath without resolving per-world
`deps.edn`, does not publish or mutate an ambient runtime, and supports both
Clojure CLI and plain-Java test JVM launches without adding a CLI-basis
requirement.

Use `t/activate-module!` with the explicit runtime for classpath-visible module
activation. Keep `run-with-weaver-world` for startup files, workspace dependency
resolution, full refresh, durable reopen, and other world/process claims. Do
not change a Millhouse pin to an unreleased or placeholder revision; adoption
waits for the coordinator's immutable landed core SHA.

## Definition-CLI pilot (4zjji)

Same host, fresh JVM, focused `clojure.test/run-tests`, no lock wait. A wrapper
around public `run-with-weaver-world` measured actual invocations and elapsed
world/body time. This is a single-run comparison, not aggregate acceptance.

| Measurement | Before | After |
| --- | ---: | ---: |
| Tests / assertions | 21 / 82 | 16 / 74 |
| Actual world starts | 21 | 13 |
| Test execution | 38.413 s | 20.326 s |
| World setup/teardown (world minus callback) | 36.211 s | 19.058 s |
| Command wall time | 40.97 s | 22.35 s |

Wall minus test time (2.56 / 2.02 s) includes JVM/load/exit overhead, not just JVM
startup. Basis starts are inferred as 21 / 13 from the pinned implementation,
not independently instrumented. The earlier ~134 s baseline is not comparable.

Moved declaration metadata, argv parsing and unknown-verb refusal to no-world
proofs. Retained real opt-in publication, parsed handler dispatch/return shapes,
live registry reload/omission, entrypoint filtering, full definition/spec/defer
projection, no execution during discovery, fail-loud missing definitions and
executor catalogue proofs. Those runtime claims still justify 13 worlds.

Deleted six redundant groups: partition/legacy-spool smoke (did not actually
exercise removal), the repeated `--all` case, raw-function registration refusal
(already in Workflow core), a second defer projection, obsolete-field absence
(already implied by exact projection), and repeated defaults projection. We
consciously lose those extra examples and legacy-entrypoint absence checking,
not actual cutover, durability or concurrency evidence. No failing baseline
assertion was deleted. The fixture contract also adds two separate root tests
(11 assertions, two worlds) for option refusal, binding, fresh state and cleanup
on success/failure; these are not included in the CLI pilot timings.
