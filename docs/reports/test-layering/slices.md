# Landed slice reconciliation

Verified 27 September 2026 against `8b66d71bc223118c559c0972a0011234e3937ea3`.
All 21 dependency cards are closed with outcome `done`; all squash commits below
are ancestors of that revision. Card/PR review and final landing receipts were
checked separately from source diffs. The epic is `c5mdb`.

The table describes **actual assertions**, not fixture names. Moves within an
existing test retain its name. Renaming Cron's reopen proof did not remove a
process proof: its body was already two embedded runtimes. World numbers below
are historical slice evidence, not additive or same-host aggregate measurements.
Several scopes overlap during partitioning, and earlier measurement HEADs differ
from final review-repaired HEADs.

| Card / PR / squash | Retained practical proof | Moved or simplified | Deleted / consciously lost protection |
| --- | --- | --- | --- |
| `4zjji` / [65](https://github.com/codethread/millhouse.spool/pull/65) / `b0c53e2` | CLI publication, dispatch, reload/omission, discovery without execution and exact projections; fresh-state/cleanup fixture contract added | Metadata, parser and unknown-verb refusals become no-world; CLI 21→16 tests, 21→13 measured worlds | Repeated all/default/defer projections, legacy-field/entrypoint absence examples and raw-function registration duplicate |
| `bak7b` / [71](https://github.com/codethread/millhouse.spool/pull/71) / `7cd6375` | Compiler, specs, runtime/composition and publication separated into six discovered namespaces; computed-doc-once proof restored at review | Passive authoring becomes collected declarations in finally-removed namespace; 118→117 measured worlds | Repeated recursive-spec determinism invocation, redundant string-key rejection and one duplicate unknown-option assertion; exact recursive/nested projections remain |
| `w0njw` / [86](https://github.com/codethread/millhouse.spool/pull/86) / `07e39e2` | Rollback, no-partial-write, actors, returning composition, concurrent fill, liveness, live specs, await budget and notes refusal | Pure option/schema checks; attention/history folded into lifecycles; 87→75 tests, 87→73 measured worlds (72 memory, one file) | Unknown-run diagnostics, repeated completion/name-start/input/root-swap examples, legacy-note projection and exact query-call shape. Malformed-defer API wiring is not proved by its pure schema replacement; both diagnostic/wiring losses were explicitly accepted after review |
| `qamqi` / [78](https://github.com/codethread/millhouse.spool/pull/78) / `8b66d71` | Owner-complete publication, removal, override restoration, rejected-publication atomicity and ordering | Pure executor authoring/refusal checks; 31→30 tests, 30→27 measured worlds | Duplicate executor-membership example; declaration-map membership remains |
| `sua9o` / [73](https://github.com/codethread/millhouse.spool/pull/73) / `c3d3c99` | Real argv mutations, refusals, actor/gate contracts, single-winner close, await timeout, receipts and source composition | Six retained groups lose worlds: declaration metadata, argv, payload parsing, two JSON-shape groups and stale-frontier guard; 46→38 tests, 46→32 source-derived worlds | Eight overlapping matrices: duplicate context write, complete/choose/defer role refusals, broad defer roles, next ambiguity, unknown API keys and final-defer example. Per-verb spelling, multi-defer ambiguity and rendered help projection are consciously lost |
| `5xofk` / [75](https://github.com/codethread/millhouse.spool/pull/75) / `fe8c518` | Real short-command execution, bounded output, simulated timeout/cancellation, custody failures and planned Weaver replacement adoption | Direct request/descriptor/environment contracts; fewer runtime matrices; 49→46 tests, 43→37 measured worlds | Exact query shape and redundant success/error/timeout combinations; timeout test now accurately names simulated custody, not OS killing. Replacement and oversized-output proofs carry retained practical contracts |
| `0rh0t` / [72](https://github.com/codethread/millhouse.spool/pull/72) / `69164e9` | Callback propagation, completion/nil omission, ownership and unchanged discovery world; serial island retained | Pure request validation and result proofs; Code plus discovery 17/87→14/76 tests/assertions, 16→13 measured worlds | Private state/query-shape checks and duplicate completion/dispatch example. Final review restored nil-omission assertion: earlier 14/75 evidence is not final |
| `o7mjd` / [77](https://github.com/codethread/millhouse.spool/pull/77) / `8ce8fe4` | Native binding, conflict-before-write, forwarded origin, cross-runtime wiring and cold durable reopen | Five receiver-validation cases use fresh targets instead of five origin/target pairs; 24 tests retained, 36→31 measured worlds | No safety case deleted; those five cases no longer each repeat origin-runtime creation. Dedicated two-world wiring remains |
| `iamyj` / [66](https://github.com/codethread/millhouse.spool/pull/66) / `c56faf4` | Board transitions, ownership, atomic batch creation/refusal and relevant command behavior | Declarations/export argv are pure; 55→49 tests, 53→49 measured worlds | Internal key/projection/provenance snapshots, generic prose wrapping, redundant export activation and owner-declaration enumeration |
| `wymmm` / [87](https://github.com/codethread/millhouse.spool/pull/87) / `9c7c363` | FIFO/exclusion, reviewed exact HEAD, dirty refusal, protected gates, merge reconciliation and cleanup | Declaration checks and repeated script traversals simplified; 54→53 focused tests, 35 measured worlds | Duplicate optional-PR/review traversal and immediate-success example, not the protected review boundary |
| `k0owp` / [67](https://github.com/codethread/millhouse.spool/pull/67) / `d24b9be` | Dispatch admission, publication adoption, incomplete-preparation intervention and restart/ownership safety | Pure callback construction; consolidate repeated setup; 23→22 tests, 21→20 measured worlds | Extra callback-title example and overlapping interrupted-publication scenario; useful title propagation/adoption assertions remain |
| `48ybj` / [68](https://github.com/codethread/millhouse.spool/pull/68) / `e36d9df` | Selected reviewer, no-change admission, policy and lifecycle | Three graph-only worlds no longer load unused scheduler/reviewer setup; eight tests and six worlds remain | Six repeated card-metadata assertions; not reviewer scheduling behavior. Little fixture-start reduction is claimed |
| `am4w5` / [74](https://github.com/codethread/millhouse.spool/pull/74) / `2cd01b9` | Meaningful notifier lifecycle, rule isolation, durable match baseline and error behavior | 20→18 tests; 19→17 worlds estimated from source, not independently measured | Empty-install rule listing and duplicate README end-to-end example; other real notification proofs remain |
| `9ixi5` / [76](https://github.com/codethread/millhouse.spool/pull/76) / `4b12da1` | Scheduling/rearm, cadence, owner reconciliation, manual-clock timeout, event-lane isolation and durable reopen | Invalid inputs reject before runtime access; close state-shape executor; 15→14 tests, 12→10 measured worlds | Mocked exact default-timeout forwarding only. Reopen rename/doc correction changes no lifecycle assertion/body |
| `nesmy` / [80](https://github.com/codethread/millhouse.spool/pull/80) / `8a0e670` | Lifecycle custody, native/restart and conservative reconciliation | 28→26 focused tests, 18→17 measured worlds; private classification repetition reduced | Obsolete `agent await` absence, redundant candidate rotation/classification and private shape expectations |
| `7auta` / [84](https://github.com/codethread/millhouse.spool/pull/84) / `69e5233` | Assignment lineage, native registration, publication atomicity, target exclusion and completion not closing/changing card | Completion/owner/lane assertions folded into continuation; four worlds removed | Default-policy registration duplicate, duplicate appended guidance/independent readiness and executor state-shape test; no target/custody authority replaced by process exit |
| `gs2u1` / [70](https://github.com/codethread/millhouse.spool/pull/70) / `9bd78bb` | Provider argv/guidance, catalog availability and reviewer selection | 51 tests retained; three redundant reviewer refreshes removed | Seven repeated metadata/shape assertions; no manufactured replacement tests or speedup claim |
| `mz5u0` / [69](https://github.com/codethread/millhouse.spool/pull/69) / `e22db1d` | Guidance admission, transport/capability and disposable-home lifetime/cleanup | Keep existing world-owned fixtures; delete unused helper file with no consumers | 36 low-value repeated assertions; no test names removed and no mutable world sharing |
| `aqaq4` / [83](https://github.com/codethread/millhouse.spool/pull/83) / `bce20ac` | Real process closure, birth-identity fences, signal cleanup and native hook conformance | Clojure 41→40 tests; Vitest 35→32; measured temporary homes 9→6 and child starts 65→52 | Specific private reuse-between-signal-and-join example and repeated plugin matrices; stronger retained interleavings still forbid signalling/adopting replacements |
| `y15ly` / [82](https://github.com/codethread/millhouse.spool/pull/82) / `267ad35` | Publication, query/CLI, receipt/resume, failed-choice frontier and adapter registry behavior; separate equivalence verifier | Devflow test classpath no longer includes adapter/Kanban; pure invalid planning/seed checks; independent entrypoint execution 35→22 tests and 30→17 measured worlds | Metadata/facade/title matrices, duplicate intake/adapter walkthroughs and repeated invalid-start no-root assertions; direct seed success checks installed definition. Four equivalence worlds remain outside the measured entrypoint totals |
| `sm1eb` / [81](https://github.com/codethread/millhouse.spool/pull/81) / `828d9ed` | Real dependency/bootstrap ordering, optional executor activation and consumer provenance | Five complete-CLI checks moved from duplicate Config world to existing disposable workspace test; 9→8 measured worlds | Duplicate bootstrap world only; no live workspace activation or reduced consumer assertion set |

## Integration changes included in landed slices

Not every changed file is the headline namespace. The audit included these
explicitly authorized repairs, rather than attributing their assertions to the
slice's focused counts:

- PR66/PR72: deterministic completed-scanner evidence and related conformance
  diagnostics. Process-exit/birth evidence remains distinct from live authority.
- PR73: Codex no-input subprocess pipe handling (with real EPIPE regressions)
  and finite scripted PR-registration clock readings (including a positive
  deadline failure proof). No production deadline was raised.
- PR79 (`3de736f`): shared-lock acquisition retries, not retries of a started
  test suite; the root lock tests are outside the migration slices.
- PR85 (`6b57739`): landing-card/recovery-policy changes coexist in the measured
  base. Aggregate timing is not attributed solely to pruning.
- Aggregate repair `cd208f5`: exact affected-selection expectation follows the
  independent Devflow alias; the failed original probe is retained as evidence.

## Evidence precision

Child counts describe their focused scopes and measured revisions. For example,
Workflow runtime's first after-sample was 75/428; review restored five assertions,
so its final slice is 75/433 with the same 73 worlds. Workflow partition's first
after-sample was 180/843; review restored the pure computed-doc-once proof, making
181/845 without adding worlds. Do not substitute earlier timing samples for
final-HEAD validation or sum overlapping partition totals.

Source inventories are not invocation measurements: parameterized helpers can
start several worlds, and `:refer` occurrences are not calls. Aggregate world
counts and current executed test/assertion totals belong to the
[acceptance report](README.md), not a grep-derived coverage floor.
