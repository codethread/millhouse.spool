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

The runner's namespace in-run totals (the sum of its `:elapsed-ms` namespace
summaries) are separate from process wall time:

| Fixture | Sample 1 | Sample 2 | Sample 3 | Median |
| --- | ---: | ---: | ---: | ---: |
| Pinned world | 92.639 s | 93.303 s | 104.585 s | 93.303 s |
| Local-root world | 89.090 s | 86.705 s | 87.193 s | 87.193 s |
| Local-root bare | 3.035 s | 2.897 s | 2.860 s | 2.897 s |

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

```nu
clojure -M:test millhouse.workflow-runtime-test millhouse.test-support-test
```

For the post-change comparison, the reproducible launcher runs exactly three
fresh JVMs serially for each fixture. `--core-root` is required for this paired
POC and prevents an accidental pinned-dependency run:

```nu
python3 docs/reports/bare-runtime-poc/run_benchmark.py --label local-world --fixture world --core-root /Users/ct/dev/projects/skein-src__spike--bare-runtime-fixture
python3 docs/reports/bare-runtime-poc/run_benchmark.py --label local-bare --fixture bare --core-root /Users/ct/dev/projects/skein-src__spike--bare-runtime-fixture
```

One supplemental counted verification per fixture used the same launcher:

```nu
python3 docs/reports/bare-runtime-poc/run_benchmark.py --label supplemental-counted-world --fixture world --core-root /Users/ct/dev/projects/skein-src__spike--bare-runtime-fixture --counted --samples 1
python3 docs/reports/bare-runtime-poc/run_benchmark.py --label supplemental-counted-bare --fixture bare --core-root /Users/ct/dev/projects/skein-src__spike--bare-runtime-fixture --counted --samples 1
```

The launcher invokes `clojure -M:test` for both rows. `-Sdeps` supplies only a
top-level local-root override for `io.millstrand/millstrand`; it does not edit
or persist `deps.edn`. The world command adds a temporary test alias that loads
`world_fixture_override.clj` before the unchanged runner. That file redirects
the benchmark-only `run-with-bare-runtime` Var to public
`run-with-weaver-world`, forwarding the original fixture `:deps-edn` roots.
This makes the world and bare rows use the same Millhouse source and upstream
core checkout.

The benchmark implementation was `f12549af870e9aa5b1b140617a4b3a29908b96d2`
(`feat(test): prototype a classpath-backed runtime fixture`). The current
candidate core checkout is `56b824f0`, which adds only a type hint to that
implementation. No upstream files were edited from this worktree.

## Raw evidence

- `raw/pinned-world-1.log` through `raw/pinned-world-3.log` contain the
  before-edit command output and `/usr/bin/time` results.
- `raw/pinned-world.json` records the pinned samples.
- `raw/local-world-1.log` through `raw/local-world-3.log` and
  `raw/local-world.json` record the local-root world samples.
- `raw/local-bare-1.log` through `raw/local-bare-3.log` and
  `raw/local-bare.json` record the local-root bare samples.
- `raw/supplemental-counted-world-1.log`, its count JSON, and the matching
  bare files record measured public-fixture invocation counts and source URL.
- `raw/full-root-candidate.log` records the one locked full root candidate.

Each accepted raw run reports zero failures and zero errors. The counted
world verification measured **57** `run-with-bare-runtime` seam calls and **57**
actual `run-with-weaver-world` calls. The counted bare verification measured
**57** bare calls and **0** world calls. Both recorded the source URL
`file:/Users/ct/dev/projects/skein-src__spike--bare-runtime-fixture/src/millstrand/test/alpha.clj`.
The fixture-support tests also verify unknown-option/storage refusal,
current-runtime binding, fresh state, and cleanup after a callback failure.
No assertions or tests were changed.

## Change and evidence boundary

`millhouse.test-support/with-embedded-runtime` now calls only the public
`millstrand.test.alpha/run-with-bare-runtime` API. It retains the existing
`:prefix` to `:name` mapping, `:sqlite-file` default, explicit memory-storage
option, runtime binding, callback shape, and module-activation lock. No
`millstrand.core.*` namespace or private fixture was imported.

Bare runtime is the appropriate tier for these direct runtime contracts. This
POC does not claim startup-file loading, dependency resolution, full workspace
refresh, transport behavior, durable reopen, or process replacement. Those
claims remain on the full public Weaver-world fixture.

The one full root candidate was run under `/tmp/millstrand-test.lock` with the
current local-root core override. Root and Workflow Kondo imports were prepared
with that same override first. The exact candidate command was:

```nu
flock -w 180 /tmp/millstrand-test.lock /usr/bin/time -p clojure -Sdeps '{:deps {io.millstrand/millstrand {:local/root "/Users/ct/dev/projects/skein-src__spike--bare-runtime-fixture"}}}' -M:test --serial
```

It passed **490 tests / 3,969 assertions** with zero failures/errors, in
**266.47 s** process wall time and **262.059 s** namespace in-run total. This
is root-only evidence; independent package and release quality gates remain
pending.

During launcher debugging, one `-i ... -M:test` attempt failed before loading
the runner because Clojure treated `-M:test` as a file. A subsequent alias-order
attempt produced three green 5.365 s, 5.176 s, and 5.085 s samples that had no
world marker and were actually bare runs. Those mislabeled samples are excluded
from every table. Their old logs were overwritten while correcting the
launcher; no results are fabricated from them.
