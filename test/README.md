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
- **Runtime-backed direct contracts:**
  `millhouse.test-support/with-embedded-runtime` delegates to the adopted public
  `millstrand.test.alpha/run-with-bare-runtime`. It takes the existing callback
  shape `(fn [runtime config-dir-file] ...)`, keeps `:prefix` and `:storage`,
  defaults to file-backed SQLite, owns fresh state and cleanup, and activates no
  modules implicitly. Existing `with-runtime` callers retain this same seam and
  `activate-spool!` retains its namespace-activation lock. The adopted core
  revision is `0f1762063b3b7e6fae7576c6025ec14c79cd314a`.
- **Startup/reload/durable reopen:** use upstream `with-weaver-world` directly
  for `:deps-edn`, activation files, `:files`, retained roots, and dependency
  resolution. An explicit `:root` is retained by default; the caller owns
  cleanup. Embedded runtimes over one file database prove durable reopen, not
  replacement-process adoption.
- **Process topology:** use separate disposable process fixtures for built CLI,
  generation-adoption, and replacement-process claims. Direct function calls and
  embedded worlds do not prove process topology.

Storage is independent of the proof tier. For the bare runtime, `:sqlite-file`
remains the default and `:sqlite-memory` is an explicit choice for serialized,
non-durable contracts only. Memory SQLite is real SQLite with a held connection,
not file persistence or multi-connection contention evidence. The bare fixture
reuses the test JVM classpath without per-world dependency resolution or world
basis startup. Use file storage for durability, filesystem, or connection-
topology claims; use `with-weaver-world` when those claims also require
workspace startup or dependency inputs. Unknown local options and unsupported
storage values fail rather than falling back.

The live Millstrand pins and hardcoded acceptance references adopt
`0f1762063b3b7e6fae7576c6025ec14c79cd314a`. Historical reports and raw runs retain
their original pins and provenance.

## Historical definition-CLI pilot (4zjji)

The following measurements are historical evidence from the pre-promotion
fixture path; they do not describe the current public bare-runtime contract.

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
