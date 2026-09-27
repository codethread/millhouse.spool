# Bare-runtime fixture POC

This report compares the unchanged Workflow runtime tests before and after
switching Millhouse's existing `with-embedded-runtime` seam to the public
`millstrand.test.alpha/run-with-bare-runtime` fixture.

## Result

Every sample passed **57 tests / 332 assertions**: Workflow runtime had
**55 / 321**, and fixture support had **2 / 11**. The three samples in each
column were separate JVM processes run sequentially after dependency caches
were warmed.

| Fixture and dependency source | Sample 1 | Sample 2 | Sample 3 | Median |
| --- | ---: | ---: | ---: | ---: |
| Pinned world, before edit | 94.810 s | 95.320 s | 106.810 s | 95.320 s |
| Local-root world, benchmark override | 91.772 s | 89.088 s | 89.654 s | 89.654 s |
| Local-root bare runtime | 5.296 s | 5.309 s | 5.254 s | 5.296 s |

The local-root bare median is **16.9x lower** than the local-root world
median (a **94.1% lower** observed process wall time). This is an observation
of this focused test process, not a causal suite-wide estimate.

The pinned baseline is a before-edit run using the unchanged command and the
pinned Millstrand dependency in `deps.edn`. Its `/usr/bin/time -p` `real`
measurements are recorded to two decimal seconds. The local-root measurements
use the Python script's monotonic process clock and are recorded to the
nearest millisecond. The pinned baseline is not a like-for-like core-source
comparison; the local-root world and bare rows are the comparable pair.

## Reproduction

The dependency declaration in `deps.edn` was not changed. First warm the
normal dependency cache, then run the focused baseline command three times,
serially, before changing the fixture:

```text
clojure -M:test millhouse.workflow-runtime-test millhouse.test-support-test
```

For the post-change comparison, the reproducible launcher runs exactly three
fresh JVMs serially for each fixture:

```text
python3 docs/reports/bare-runtime-poc/run_benchmark.py \
  --label local-world \
  --fixture world \
  --core-root /Users/ct/dev/projects/skein-src__spike--bare-runtime-fixture

python3 docs/reports/bare-runtime-poc/run_benchmark.py \
  --label local-bare \
  --fixture bare \
  --core-root /Users/ct/dev/projects/skein-src__spike--bare-runtime-fixture
```

The launcher invokes `clojure -M:test` for both rows. `-Sdeps` supplies only a
top-level local-root override for `io.millstrand/millstrand`; it does not edit
or persist `deps.edn`. The world command adds a temporary test alias that loads
`world_fixture_override.clj` before the unchanged runner. That file redirects
the benchmark-only `run-with-bare-runtime` Var to public
`run-with-weaver-world`, forwarding the original fixture `:deps-edn` roots.
This makes the world and bare rows use the same Millhouse source and upstream
core checkout.

The upstream checkout was `f12549af870e9aa5b1b140617a4b3a29908b96d2`
(`feat(test): prototype a classpath-backed runtime fixture`). No upstream
files were edited from this worktree.

## Raw evidence

- `raw/pinned-world-1.log` through `raw/pinned-world-3.log` contain the
  before-edit command output and `/usr/bin/time` results.
- `raw/pinned-world.json` records the pinned samples.
- `raw/local-world-1.log` through `raw/local-world-3.log` and
  `raw/local-world.json` record the local-root world samples.
- `raw/local-bare-1.log` through `raw/local-bare-3.log` and
  `raw/local-bare.json` record the local-root bare samples.

Each raw run reports zero failures and zero errors. The fixture-support tests
also verify unknown-option/storage refusal, current-runtime binding, fresh
state, and cleanup after a callback failure. No assertions or tests were
changed.

## Change and evidence boundary

`millhouse.test-support/with-embedded-runtime` now calls only the public
`millstrand.test.alpha/run-with-bare-runtime` API. It retains the existing
`:prefix` to `:name` mapping, `:sqlite-file` default, explicit memory-storage
option, runtime binding, callback shape, and module-activation lock. No
`millstrand.core.*` namespace or private fixture was imported.

Bare runtime is the appropriate tier for these direct runtime contracts. This
POC does not claim startup-file loading, dependency resolution, full workspace
refresh, transport behavior, durable reopen, or process replacement. Those
claims remain on the full public Weaver-world fixture. No full suite was run;
only the two requested focused namespaces were run.
