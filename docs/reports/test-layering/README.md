# Test-layering acceptance — 27 September 2026

Feature `2k1bl`, epic `c5mdb`. Measurement source: `cd208f5d75265c16ecf28a599ca501ef2b10ffe4`, on top of all 21 landed slices at `8b66d71bc223118c559c0972a0011234e3937ea3`.

## Result

Three green root runs have a **7m42.2s median** (range **7m36.6s–7m44.3s**), versus the historical **10m09.8s** median: **24.2% lower observed in-run time**. This is not a like-for-like coverage or isolated optimization benchmark. Full quality/package acceptance and focused isolation evidence are recorded below.

Counts are evidence, not a preservation target. The earlier profiling note `u2ga5` reported 525 tests / 4,072 assertions; the final root suite has 490 / 3,969. At the epic policy-start revision `92524a0`, a static inventory finds 530 declared root test vars (including five affected-selector tests absent from the earlier profiling breakdown); the final inventory is 490. These static counts are distinct from the earlier measured 525. The suites are not identical: Workflow was partitioned, low-value examples were removed, and fixture/lock/tooling checks were added. Independent package totals are separate and must not be added to root totals as unique coverage.

The only aggregate integration repair updates the exact expected affected-component set in `test/millhouse/affected_test.clj:40`: Devflow's narrowed test alias no longer depends on the adapter. The adapter still selects `devflow-check`, which runs its independent gate. No selector, production behavior, package dependency, pool size, timeout or assertion strength changed.

## Comparable root measurements

| Run | Load/dispatch | Serial island | Parallel phase | In-run total | Process wall | Lock + launcher |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 1.997 s | 273.981 s | 186.223 s | 462.201 s | 464.065 s | 0.032 s |
| 2 | 1.750 s | 273.739 s | 181.068 s | 456.557 s | 458.353 s | 0.049 s |
| 3 | 1.771 s | 277.009 s | 185.518 s | 464.298 s | 466.067 s | 0.046 s |

Median serial/parallel phases: **273.981 / 185.518 s**; ranges **273.739–277.009 / 181.068–186.223 s**. Process-minus-in-run is **1.769–1.863 s**; probe preload within that overhead is **0.053–0.059 s**. [Machine-readable measurements](measurements.json) include all 28 namespace timings and log hashes.

Host `ct.local`: Apple M4, 10 logical CPUs, macOS 26.5.1 (25F80) arm64, Zulu OpenJDK 21.0.11, Clojure CLI 1.12.6.1673. Three separate fresh JVMs, normal await scale, existing ten-worker maximum, the same clean source revision and generated Kondo imports. All full runs hold `/tmp/millstrand-test.lock`. Other services on this shared host were not stopped; these are observations, not a controlled causal estimate.

A temporary external launcher invokes the existing root runner with its unchanged namespace list and serial/parallel functions. `System/nanoTime` wrappers time runner phases and public `run-with-weaver-world` calls, including callback duration. No source instrumentation is delivered. In-run time excludes JVM/load/exit outside the runner; process-minus-in-run is not described as pure JVM startup. Outer-minus-inner measures lock wait plus launcher overhead. Load/dispatch is the remainder after serial and parallel phases. Probe preload is disclosed separately. Namespace durations overlap; their sum is not elapsed wall time.

Baseline `u2ga5` used the same Apple M4/10-CPU host class and runner-phase clocks: median 609.753 s, range 598.864–638.280 s; serial 237.314–260.043 s, parallel 356.471–375.375 s. Its exact source revision and complete host/toolchain fingerprint were not recorded in that note. Comparison is therefore historical, not an identical-workload benchmark.

The partition added `workflow-runtime-test` to the serial island because its global redefinitions would otherwise race other partitions. Existing members remain; this is an honest isolation cost, not a regression to hide by increasing parallelism. The former single Workflow parallel critical path no longer exists. Shell is now the parallel critical path (namespace median **184.444 s**); run CLI **159.238 s**, Identity **146.388 s**, registry **142.630 s** and composition **116.709 s** remain expensive. Serial Workflow runtime **76.086 s**, Kanban **72.583 s** and merge queue **44.982 s** dominate the serial island. Its increase relative to the historical baseline is explained in part by the safety-motivated partition move; no causal attribution to that move alone is claimed.

Each run observes **368 public fixture calls**, of which **367 reach a started world callback**. The remaining call is the fixture test's invalid-storage refusal, rejected before creation; it is not counted as a world/basis start. Setup/teardown sums are **1,233.022 / 1,193.968 / 1,226.450 s** (median **1,226.450 s**), versus callback sums **89.608 / 87.571 / 88.788 s**. Setup/teardown remains **about 93%** of summed world lifetimes.

World accounting covers public fixture calls in this JVM, including nested and direct users. It excludes worlds launched inside external process proofs. Summed world and callback lifetimes can include nested/overlapping work; they are not wall-time partitions. World-minus-own-callback isolates setup/teardown at that boundary. Each successful public world builds one generation basis in the pinned helper; basis counts are inferred, not independently instrumented. Baseline's 338 calls counted only the common `with-runtime` seam, so comparing that count directly with the new all-world count would be misleading.

## Retained proofs and accepted tradeoffs

All 21 cards are closed/done. Their recorded squash merge commits are ancestors of the measurement revision; each has accepted independent review and landing evidence. See [slice reconciliation](slices.md) for PR/commit references and actual moved/deleted groups. Historical worker HEADs need not be ancestors after squash/rebase; landed source, not worker exit, is the acceptance basis.

- Pure declaration, parsing, shape and projection checks no longer pretend to prove activation. The shared `with-embedded-runtime` helper still constructs a full Weaver world; memory SQLite changes storage, not the proof tier or basis startup.
- Runtime tests retain transaction rollback, no-partial-write refusals, actor/executor separation, run guards, returning composition and liveness. Source publication retains owner replacement, omission, rejection atomicity and ordering. File-backed tests retain concurrent actors and durable reopen.
- Shell retains real short-command execution and planned replacement-generation adoption. Its embedded timeout/cancellation checks simulate custody and do not prove OS process-tree killing. Chime keeps notifier lifecycle, isolation and durable baselines. Cron's unchanged two-runtime proof is correctly called durable reopen, not process replacement. Harnesses keeps native attachment, publication, reconciliation and real process cleanup; deterministic birth-identity tests do not claim live PID-reuse evidence.
- Accepted losses include duplicated role/error spellings, internal state/query-shape checks, metadata/title snapshots, redundant transition/registration matrices, exact mocked default forwarding, and specific legacy/unknown-input examples. Workflow runtime's unknown-run diagnostics and malformed-defer API-wiring gaps were explicitly accepted after review; direct schema assertions are not represented as equivalent API coverage.
- The acceptance repair `cd208f5` deletes no test. The failed first probe remains recorded: 17 failures (16 missing generated Kondo hooks; one stale dependency expectation), exit 1 at `8b66d71`. After import preparation and the explained repair, focused verification passed 34 tests / 185 assertions. The failed sample is excluded from green statistics, not erased.

## Root checks, discovery and isolation

The 28 root runner namespaces resolve to 28 files. All six Workflow partitions are registered both in the root runner and the Workflow package test alias. Nine namespaces remain serial. Independent Harnesses, Devflow, adapter, Config and workspace runners use their own classpaths and fixtures; no new import from root test internals was introduced.

Root checks outside migration ownership remain appropriate:

| Group | Proof and state ownership | Decision |
| --- | --- | --- |
| Authoring forms | Pure collected declarations, inert/bang semantics and duplicate refusal; local contribution collection | Keep four cheap tests |
| Package layout / consumer dependency closure | Exact declared package graph, local roots and exported lint configs | Keep three cheap tests |
| Consumer | Fresh Land-only startup world with real nREPL; separate temporary tools.deps/Kondo consumer process | Keep two integration tests; serial startup isolation |
| Affected selection | Pure reverse closure plus a disposable Git repository, removed in `finally` | Keep five tests; repair stale exact expectation |
| Quality lock | Isolated lock path, deterministic acquisition faults plus actual `flock` exclusion and release | Keep two subprocess tests; never contend on the live suite lock internally |
| Fixture support | Fresh runtimes/databases/directories and binding; success/failure cleanup and option refusal | Keep two tests / two worlds |
| Executor discovery | Real activation/publication ordering, not merely declaration collection | Keep the existing world |

Explicit roots own `finally` cleanup; generated worlds own shutdown/deletion. Guidance homes remain under disposable world lifetime. Workflow global spec/redefinition proofs remain in the serial island.

Focused same-JVM checks passed with `MILLSTRAND_TEST_AWAIT_SCALE=3` at the same test-source revision:

- Three repetitions of seven Workflow concurrent-fill, restart-API and live-spec cases plus fixture success/failure cleanup: **24 tests / 78 assertions**, **41.75 s**, zero failures/errors. Restart-API cases still do not claim process replacement.
- Three repetitions of Harnesses guidance fixture hygiene, process identity, process cleanup and scanner namespaces: **66 / 552**, **36.26 s**, zero failures/errors. Fresh per-test resources remain; this does not share a world across repetitions.

The first direct Harnesses focused launcher omitted the package Makefile's real-Node `PATH` preparation and failed before process/scanner readiness (30 failures / three errors across the three repetitions). The interactive shell resolved a Volta shim rather than `node -p process.execPath`. Matching the existing Makefile environment resolved that failure; no source, assertion or deadline was changed. Both outputs are retained. The full package gate already used the correct environment and passed.

Discovery limits are explicit: Harnesses' external assignment acceptance namespace is opt-in via `--e2e` and requires supplied Mill/Strand binaries; it is not part of `make quality-full` and was not counted or run here. It was unchanged by the slices. Published-remote distribution/consumer smoke is a separate release gate, not implied by local package success. No paid provider, live activation, installed plugin mutation or shared Weaver restart was used.

## Independent gates

`.millstrand/land-quality.sh TEST_FULL=1` passed once at clean `cd208f5`, exit 0, **748.62 s process wall** including static checks, another uninstrumented full root run and all package gates. Its own lock was acquired on the first attempt. Individual package clocks were not collected; no per-package timing is inferred from this combined duration.

| Gate | Executed evidence |
| --- | --- |
| Root | 490 tests / 3,969 assertions, zero failures/errors |
| Harnesses Clojure | 179 / 1,430, zero failures/errors |
| Native plugins | Typecheck; Vitest 32 passed / one existing opt-in skip; Codex 0.154.0 CLI-only conformance, SIGINT/SIGTERM and closed-parent writer cleanup; formatting |
| Devflow | 17 / 160; identity-check clean |
| Optional Kanban adapter | Independent 5 / 36; separate card-authoring equivalence clean |
| Config | 6 / 118, including consumer provenance |
| Disposable workspace | 1 / 25, including relocated complete-CLI checks |
| Kanban dashboard | Go build/vet/test passed; app and board packages have tests |

Formatting, Kondo, Splint, conventions, reflection, generated API docs, Markdown links and strict documentation build also passed. The existing plugin skip requires `PI_PROMPT_OWNER_SOURCE` for actual consumer prompt-owner composition; that external consumer test is not claimed here and no skip was added.

Default Harnesses checks include Clojure, plugin typechecking/Vitest, Codex conformance/signal cleanup and formatting. Devflow runs its own tests, the independently optional adapter, identity checking and the unchanged card-authoring equivalence verifier. Config and disposable workspace checks remain separate. A default root-only run does not cover any of these gates.

## Remaining costs and supported-API follow-up

The practical win is fewer unjustified worlds and clearer claims, not a sub-five-minute promise. Retained process topology, durable reopen and source publication deserve their higher cost. Cheap authoring/layout checks do not warrant cosmetic refactors.

At pinned Millstrand `34f940ddb2e69898554bf76251749715b250ae15`, `activate-module!` needs an existing runtime and `with-runtime` only binds one. A downstream bare-runtime constructor with deterministic shutdown, fresh store/registries and no per-world dependency-basis startup is not supported. Request that explicit public fixture contract upstream as a separately authorized follow-up; do not import `millstrand.core.*`, copy private fixtures or simulate publication to bypass the gap. Its acceptance must distinguish file persistence/concurrent actors from serialized memory storage and prove cleanup on failure. No upstream implementation, basis caching or pool-tuning project is included here.

Feature completion remains owned by the independent delivery finisher after exact-HEAD quality/review, FIFO landing and cleanup. The epic must remain open until this final feature's outcome is verified. This report does not assert deployment or close the epic.

## Durable evidence

Task `45jfm` retains the exact temporary probe (`coin4`), failed original output (`xdr4j`), three green root outputs (`aphoi`, `jpouw`, `lan98`) and complete metrics (`yjh5p`). Task `mx4e8` retains full quality output (`4e9o4`), focused root/Harnesses results (`6zqrm`, `r14q1`) and the failed unprepared Harnesses launcher (`vsqu2`, diagnosis `wif56`). Read any note with `strand show NOTE_ID`; profiling baseline is `u2ga5`. The final feature/PR records exact report HEAD, independent review and finisher handoff separately.

## Promotion addendum

The supported-API follow-up described above is resolved by the landed public
`millstrand.test.alpha/run-with-bare-runtime` fixture. Once consumers adopt its
immutable core SHA, use it for direct runtime-state contracts: it accepts only
`:storage` and `:name`, supplies explicit runtime and config/data/state paths
(and file-storage `:db-path`), and owns fresh state and cleanup. It does not
resolve dependencies per world or mutate ambient runtime selection, and it
supports plain-Java test JVMs without adding a Clojure CLI basis requirement.

The historical measurements and pinned limitation above remain unchanged. Keep
Weaver-world and process fixtures for startup, dependency resolution, refresh,
durable reopen, transport, and replacement-generation claims. Millhouse pin
updates remain pending the coordinator's immutable landed core SHA.
