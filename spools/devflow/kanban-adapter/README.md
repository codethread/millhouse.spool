# devflow-kanban-adapter

The kanban binding for devflow's pluggable seams, shipped as its own root
(`millhouse/devflow-kanban-adapter`) so the main `millhouse/devflow` root stays
coupled to no card system. If your workspace runs both devflow and Millhouse
kanban, activate this root instead of hand-rolling the same glue.

## Dependencies

Unlike the main devflow root, this root requires `millhouse/kanban`.

## What it ships

- **`author-kanban-cards`** — a call-only card-authoring target with four
  ordered boundaries: draft breakdown → publish/recover epic → publish/recover
  feature graph → record exact review set. Its explicit defer params include
  `feature` and the verified `repository`, `mainline`, `merged-revision`,
  `proposal-path`, `merge-evidence` receipt; parent context is not inherited.
  The feature graph uses `strand weave --pattern kanban-batch` for atomic card
  and dependency creation. That pattern does **not** create the epic or parent
  its features. The epic is created separately, then missing `parent-of` links
  are reconciled. The grouping epic stays out of the review set.
- **`decompose-kanban`** — devflow's published `decompose-open` template bound with `#{author-card-strands author-kanban-cards}`, so the defer's worker chooses between the strand-native default and the board per feature.
- **`repoint-decompose!`** — re-points the routed `:decompose` stage name at `decompose-kanban` in the registry's direct layer, so `land-proposal`'s landed choice routes into the kanban-bound variant. Its exact public input is `{:runtime <active Millstrand runtime>}` and its exact result is `{:repointed :decompose}`; extra or missing keys fail with allowed/received diagnostics. The lifecycle-context adapter is `repoint-decompose-seed!`, which validates the owning `::repoint-seed-context` spec: `:runtime` is required, and any additional keyword metadata keys with arbitrary values are accepted.

## Publication receipts

The ordered steps record `devflow/breakdown-draft`, `devflow/epic-receipt`,
`devflow/card-publication` and `devflow/review-set` on their own strands. Read
completed outputs with the run subgraph and `strand show <step-id>`; they do not
appear automatically in a later ready prompt. Store external ids immediately,
before closing the step. The final review set is the exact `{id, title}` vector
read by the parent's review handoff.

The draft reference links the epic source, feature bodies and publication
mapping. On interruption, reuse verified recorded outputs. Atomic batch creation
is not idempotent: if a response was lost, inspect and reconcile the exact draft
inventory before retrying; ambiguity requires intervention, not another batch.
Neither this workflow nor process success authenticates a human decision or
proves an external mutation. Completion receipts are driver obligations on the
engine's existing attribute surface; no unsupported output-validation API is used.

## Consuming it

Add Devflow, the adapter, and its Kanban dependency to `deps.edn`:

Generate the selected dependency closure from the tested Millhouse checkout:

```text
scripts/consumer-deps.sh MILLHOUSE_SHA millhouse/devflow millhouse/devflow-kanban-adapter
```

Use the printed `:deps` map in the consumer. See [distribution](../../../README.md#consumption)
for why multiple Git roots need direct coordinates for their shared closure.

Activate kanban and the adapter after devflow and workflow:

```clojure
(runtime/module! runtime :millhouse/kanban
  {:ns 'millhouse.kanban
   :required? true})
(runtime/module! runtime :devflow/kanban-adapter
  {:ns 'millhouse.devflow-kanban-adapter
   :after [:devflow :millhouse/kanban]
   :required? true})
```

The `:decompose` re-point lives in the registry's direct layer, so it is a
lifecycle seed, not a module declaration:

```clojure
(lifecycle/defseed! devflow-kanban-adapter-decompose
  "Route the :decompose stage name at the kanban-bound variant."
  {:apply 'millhouse.devflow-kanban-adapter/repoint-decompose-seed!})
```

`defseed!` is an idempotent process-lifetime lifecycle effect. The coordinator invokes its `:apply` callable once for each weaver generation, passing a context map whose `:runtime` is the active runtime plus lifecycle metadata. The adapter validates that context against `::repoint-seed-context`, whose open metadata policy accepts additional keyword keys with arbitrary values, then projects it into `repoint-decompose!`; the direct registry entry is therefore re-established after every refresh. The lifecycle result is data, `{:repointed :decompose}`, which the seed runner records as the effect result; it is not a module declaration or a workflow step handle. Without the seed, `decompose-kanban` stays reachable by its own name (`strand workflow show decompose-kanban`) while the routed `:decompose` keeps devflow's strand-native default.
