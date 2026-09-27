# Workflow proof ownership

The `bak7b` partition gives subsequent workers disjoint files. Every namespace
below lives under `spools/workflow/test/millhouse/`; the original namespace now
contains only compiler proofs.

| File | Namespace | Owner | Tests |
| --- | --- | --- | ---: |
| `workflow_test.clj` | `millhouse.workflow-test` | Authoring/compiler (`bak7b`) | 24 |
| `workflow_authoring_test.clj` | `millhouse.workflow-authoring-test` | Authoring/declarations (`bak7b`) | 29 |
| `workflow_spec_test.clj` | `millhouse.workflow-spec-test` | Spec/JSON projections (`bak7b`) | 10 |
| `workflow_runtime_test.clj` | `millhouse.workflow-runtime-test` | Runtime contracts (`w0njw`) | 66 |
| `workflow_composition_test.clj` | `millhouse.workflow-composition-test` | Returning composition (`w0njw`) | 21 |
| `workflow_registry_test.clj` | `millhouse.workflow-registry-test` | Registry/publication (`qamqi`) | 31 |

Runtime and publication tests moved without changed assertions or fixture tiers.
Qualified fixture symbols, expected symbol strings and auto-qualified specs now
name their owning namespace. Each partition keeps its own small definition/spec
fixtures rather than importing another test namespace. In particular, live-spec
removal/redefinition in runtime tests cannot alter the spec or registry proofs.
No new runtime dependency, shared mutable world or root fixture API was added.

The root runner and Workflow package alias both register all six namespaces.
Runtime tests temporarily redefine `batch/apply!`, `weaver/ready` and
`workflow/attention`; that partition is in the runner's serial island so the new
partitions cannot overlap those JVM-global substitutions. Existing serial-island
members and worker-count policy are unchanged. Other fixture worlds remain
fresh per test and retain file storage and cleanup.

## Practical-value changes

- Replace the passive `defworkflow` world with `collect-module-forms` in a fresh,
  finally-removed namespace. Metadata remains checked, including a computed
  docstring. Collection proves inert declaration, not module publication; real
  publication, omission rejection and owner replacement remain in registry tests.
- Retain `defworkflow-evaluates-computed-doc-once` after independent review:
  deterministic metadata alone would not detect double evaluation. Its counter
  is now test-local inside a fresh, finally-removed namespace, not a global atom.
- Remove `spec-forms-is-cycle-safe-and-deterministic`: the retained exact recursive
  graph/alias expectations already require finite, ordered, deduplicated output.
  The extra repeated invocation is no longer checked separately.
- Remove `json-params-cannot-express-string-keyed-maps`: the retained exact nested
  keywordization result already excludes string keys. The redundant
  `s/map-of string?` rejection example is dropped.
- Remove one literally duplicated unknown-workflow-option assertion. Every
  builder's typo refusal and diagnostic context remain covered.

No failing assertion was removed. Safety, transaction refusal, concurrency,
returning composition and publication tests were moved, not simplified. Tests
that simulate a restart signal or replace a stored fingerprint still prove only
those API/data contracts, not actual replacement-process adoption.

## Measurement

A single focused before/after comparison uses fresh JVMs and a wrapper around
public `run-with-weaver-world`, counting actual invocations and timing its body
and complete lifetime. There is no lock wait. The baseline source is commit
`c56faf42c39e20ed1144675ecada3a89b6d54cc6`; the original source snapshot was loaded
before measurement. After measurement runs all six partitions serially with the
same wrapper. Wall minus test time includes JVM/load/exit overhead, not just JVM
startup. Basis starts are inferred from the pinned fixture, not independently
instrumented. Aggregate three-run acceptance belongs to `2k1bl`.

The initial prepared worktree lacked generated clj-kondo imports. Both
`make kondo-import-root` and `make kondo-import-workflow` were needed before the
original hook proofs passed. Failed setup attempts remain recorded on task
`1rrys`; they are not the green baseline or evidence of a test regression.

| Measurement | Before | After |
| --- | ---: | ---: |
| Tests / assertions | 183 / 850 | 180 / 843 |
| Actual world starts | 118 | 117 |
| Test execution | 208.406 s | 183.998 s |
| World setup/teardown (world minus callback) | 198.214 s | 174.858 s |
| Command wall time | 211.64 s | 186.34 s |
| Wall minus test execution | 3.234 s | 2.342 s |

Both runs passed. The after column measures initial implementation `d027597`.
Review then restored the no-world computed-doc proof: final counts are
**181 tests / 845 assertions**, with **117 worlds** unchanged. The focused authoring
recheck passed 29 tests / 139 assertions in 0.474 s; it is not a replacement
whole-partition timing sample.

The observed 24.408 s test-time difference is not a defensible
causal speedup estimate: one removed world cannot explain it, and these are
single runs on a shared host. No meaningful suite speedup is promised.

The only fixture saving claimed here is one authoring world. Namespace splitting
is an ownership/discovery improvement, not a fixture-cost reduction. The
[supported fixture limitation](../../../test/README.md#pinned-api-limitation)
remains: runtime contracts still need embedded worlds because no supported cheap
bare-runtime constructor is available. Stateful fixture reduction belongs to the
subsequent runtime and publication cards.

## Promotion addendum

The limitation above describes the earlier pinned core. After adopting a landed
core revision that exports `millstrand.test.alpha/run-with-bare-runtime`, use
that public fixture for direct runtime contracts and classpath-visible module
activation. It retains `:storage` and `:name`, supplies explicit runtime and
config/data/state paths (plus file-storage `:db-path`), and owns fresh state and
cleanup. It does not resolve per-world dependencies or mutate ambient runtime
selection, and it supports plain-Java test JVMs without a new CLI-basis
requirement.

Keep `run-with-weaver-world` for startup files, workspace dependency
resolution, full refresh, durable reopen, and transport claims. Use separate
disposable process fixtures for process topology, generation adoption, and
replacement-process claims. The historical measurements above are unchanged;
pin adoption waits for the coordinator's immutable landed core SHA.
